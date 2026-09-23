package com.hardcoreseamless.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/**
 * DeathCoordinator tests per FASE 7 spec section 39: first death accepted, second death while
 * ENDING/WAITING_FOR_STANDBY ignored, duplicate event ignored, stale event ignored, death from a
 * non-active backend ignored. Uses a REAL SwitchService (not mocked away) so these tests actually
 * verify death reuses the certified switch pipeline (spec section 2), not a parallel mechanism -
 * only Velocity's own interfaces and the recycle process are faked/mocked.
 *
 * <p>Auto-resume of a WAITING_FOR_STANDBY death when the standby's recycle completes is
 * deliberately NOT unit-tested here (it's tightly coupled to real recycle timing) - it's covered
 * by the mandatory live test in FASE 7 spec section 37.
 */
class DeathCoordinatorTest {

    private final Logger logger = mock(Logger.class);

    @TempDir
    Path stateDir;

    @TempDir
    Path spoolRoot;

    private StateManager newStateManager(CoordinatorState initial) {
        return StateManager.loadOrInit(stateDir, logger, () -> initial);
    }

    private CoordinatorState twoServerState(BackendStatus standbyStatus, long activeRunStartedAt) {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(BackendStatus.ACTIVE, 0, 111L));
        servers.put("server-b", new BackendState(standbyStatus, 0, 222L));
        return new CoordinatorState(1, "server-a", RunPhase.ACTIVE, activeRunStartedAt, servers, null, Map.of());
    }

    private ProxyServer newMockProxy() {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);
        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverA.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of());
        when(serverB.getPlayersConnected()).thenReturn(List.of());
        return proxy;
    }

    private SwitchService newSwitchService(StateManager stateManager, ProxyServer proxy) {
        RecycleExecutor recycle = (backendKey, oldGeneration) -> CompletableFuture.completedFuture(
                new RecycleResult(true, backendKey, 1L, 2L, 111L, 999L, 1000, null));
        return new SwitchService(proxy, logger, stateManager, recycle, 2000);
    }

    private void writeEvent(String eventId, String backend, long timestampEpochMs) throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "eventId": "%s",
                  "backend": "%s",
                  "timestampEpochMs": %d,
                  "playerUuid": "%s",
                  "playerName": "Steve",
                  "dimension": "minecraft:overworld",
                  "x": 0.0,
                  "y": 64.0,
                  "z": 0.0,
                  "damageSource": "fall",
                  "worldSeed": 123
                }
                """.formatted(eventId, backend, timestampEpochMs, UUID.randomUUID());
        Path inbox = spoolRoot.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve(eventId + ".json"), json);
    }

    @Test
    void firstDeathAcceptedAndSwitchesWhenStandbyReady() throws Exception {
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        writeEvent("death-1", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        CoordinatorState after = stateManager.snapshot();
        assertEquals("server-b", after.active());
        assertEquals(2, after.runId());
        assertEquals(RunPhase.ACTIVE, after.phase());
        assertNull(after.pendingDeath());
        assertTrue(Files.exists(spoolRoot.resolve("processed").resolve("death-1.json")));
        assertFalse(Files.exists(spoolRoot.resolve("inbox").resolve("death-1.json")));
        assertEquals(1, after.deathCounts().get("Steve"), "the death must be attributed to whoever died");
    }

    @Test
    void duplicateEventDoesNotDoubleCountTheDeath() throws Exception {
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        writeEvent("death-dup", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();
        writeEvent("death-dup", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        assertEquals(1, stateManager.snapshot().deathCounts().get("Steve"));
    }

    @Test
    void secondDeathIgnoredWhileWaitingForStandby() throws Exception {
        // Standby RECYCLING (not READY) - the first death can be accepted but can't switch yet.
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.RECYCLING, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        writeEvent("death-1", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        CoordinatorState afterFirst = stateManager.snapshot();
        assertEquals(RunPhase.WAITING_FOR_STANDBY, afterFirst.phase());
        assertEquals("death-1", afterFirst.pendingDeath().eventId());
        assertEquals("server-a", afterFirst.active(), "must not have switched - standby wasn't READY");

        writeEvent("death-2", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        CoordinatorState afterSecond = stateManager.snapshot();
        assertEquals("death-1", afterSecond.pendingDeath().eventId(), "second death must not replace the pending one");
        assertEquals(1, afterSecond.runId(), "no switch should have happened at all yet");
    }

    @Test
    void duplicateEventIdIgnored() throws Exception {
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        writeEvent("death-dup", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();
        int runIdAfterFirst = stateManager.snapshot().runId();

        // Same eventId written again (e.g. a retried/duplicated file).
        writeEvent("death-dup", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        assertEquals(runIdAfterFirst, stateManager.snapshot().runId(), "duplicate must not trigger a second switch");
    }

    @Test
    void staleEventIgnored() throws Exception {
        long runStartedAt = System.currentTimeMillis();
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, runStartedAt));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        writeEvent("stale-death", "server-a", runStartedAt - 60_000);
        coordinator.pollOnce();

        CoordinatorState after = stateManager.snapshot();
        assertEquals(RunPhase.ACTIVE, after.phase());
        assertEquals("server-a", after.active(), "a stale event must never trigger a switch");
        assertEquals(1, after.runId());
    }

    @Test
    void deathFromNonActiveBackendIgnored() throws Exception {
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, true);

        // server-b is the STANDBY, not active - a death reported from it must never cause a run loss.
        writeEvent("death-from-standby", "server-b", System.currentTimeMillis());
        coordinator.pollOnce();

        CoordinatorState after = stateManager.snapshot();
        assertEquals(RunPhase.ACTIVE, after.phase());
        assertEquals("server-a", after.active());
        assertEquals(1, after.runId());
    }

    @Test
    void diagnosticModeAcceptsButDoesNotSwitch() throws Exception {
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.READY, 0L));
        ProxyServer proxy = newMockProxy();
        SwitchService switchService = newSwitchService(stateManager, proxy);
        DeathCoordinator coordinator = new DeathCoordinator(logger, stateManager, switchService, proxy, spoolRoot, false);

        writeEvent("death-diag", "server-a", System.currentTimeMillis());
        coordinator.pollOnce();

        CoordinatorState after = stateManager.snapshot();
        assertEquals("server-a", after.active(), "diagnostic mode must never actually switch");
        assertEquals(1, after.runId());
        assertTrue(Files.exists(spoolRoot.resolve("processed").resolve("death-diag.json")),
                "the event is still validated and filed away even in diagnostic mode");
    }
}
