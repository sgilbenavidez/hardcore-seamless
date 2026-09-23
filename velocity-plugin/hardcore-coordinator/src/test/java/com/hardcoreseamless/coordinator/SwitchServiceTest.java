package com.hardcoreseamless.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/**
 * Unit tests for SwitchService, per FASE 5 spec section 53: READY-target required, generation
 * increment, runId increment, failed recycle leaves FAILED, switch rejected while standby not
 * READY, double-switch rejection. Velocity's interfaces are mocked (20-30+ abstract methods each,
 * not hand-implemented - see FASE 4 spec section 26); RecycleExecutor is faked (never spawns a
 * real process against the real project scripts - see RecycleExecutor's javadoc).
 */
class SwitchServiceTest {

    private static final long TIMEOUT_MS = 2000;

    private final Logger logger = mock(Logger.class);

    @TempDir
    Path tempDir;

    private StateManager newStateManager(CoordinatorState initial) {
        return StateManager.loadOrInit(tempDir, logger, () -> initial);
    }

    private CoordinatorState twoServerState(BackendStatus activeStatus, BackendStatus standbyStatus) {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(activeStatus, 0, 111L));
        servers.put("server-b", new BackendState(standbyStatus, 0, 222L));
        return new CoordinatorState(1, "server-a", RunPhase.ACTIVE, 0L, servers, null, Map.of());
    }

    private RecycleExecutor fakeRecycle(RecycleResult result) {
        return (backendKey, oldGeneration) -> CompletableFuture.completedFuture(result);
    }

    @Test
    void successfulSwitchCommitsActiveDrainingAndIncrementsRunId() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);
        Player player = fakePlayer("Steve", serverB, ConnectionRequestBuilder.Status.SUCCESS);

        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of(player));

        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.READY));
        RecycleExecutor recycle = fakeRecycle(new RecycleResult(true, "server-a", 1L, 2L, 111L, 999L, 5000, null));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        SwitchOutcome outcome = service.switchNow().get(5, TimeUnit.SECONDS);

        assertTrue(outcome.isSuccess());
        assertEquals(1, outcome.playersTransferred());
        assertEquals("server-b", outcome.activeServer());
        assertEquals("server-a", outcome.standbyServer());

        CoordinatorState after = stateManager.snapshot();
        assertEquals(2, after.runId());
        assertEquals("server-b", after.active());
        assertEquals(BackendStatus.ACTIVE, after.get("server-b").status());
        // The fake recycle executor completes synchronously (already-completed future), so by the
        // time switchNow()'s own future resolves, the whenComplete callback has already run too.
        assertEquals(BackendStatus.READY, after.get("server-a").status());
        assertEquals(1, after.get("server-a").generation());
        assertEquals(999L, after.get("server-a").seed());
        assertFalse(service.isSwitchInProgress());
    }

    @Test
    void failedRecycleLeavesBackendFailed() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);

        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of());

        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.READY));
        RecycleExecutor recycle = fakeRecycle(RecycleResult.failed("WORLD_DELETE_FAILED"));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        service.switchNow().get(5, TimeUnit.SECONDS);

        CoordinatorState after = stateManager.snapshot();
        assertEquals(BackendStatus.FAILED, after.get("server-a").status());
        assertEquals(0, after.get("server-a").generation(), "generation must not advance on a failed recycle");
    }

    @Test
    void switchRejectedWhenStandbyNotReady() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.RECYCLING));
        RecycleExecutor recycle = fakeRecycle(RecycleResult.failed("unused"));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        SwitchOutcome outcome = service.switchNow().get(5, TimeUnit.SECONDS);

        assertEquals(SwitchOutcome.Status.STANDBY_NOT_READY, outcome.status());
        assertEquals("server-a", outcome.activeServer());
        // Must reject before ever touching Velocity - no server lookups, no pings, no transfers.
        verify(proxy, never()).getServer(any());
        CoordinatorState after = stateManager.snapshot();
        assertEquals("server-a", after.active());
        assertEquals(BackendStatus.ACTIVE, after.get("server-a").status());
        assertEquals(BackendStatus.RECYCLING, after.get("server-b").status());
    }

    @Test
    void zeroPlayerSwitchStillCommits() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);

        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of());

        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.READY));
        RecycleExecutor recycle = fakeRecycle(new RecycleResult(true, "server-a", 1L, 2L, 111L, 333L, 4000, null));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        SwitchOutcome outcome = service.switchNow().get(5, TimeUnit.SECONDS);

        assertTrue(outcome.isSuccess());
        assertEquals(0, outcome.playersTransferred());
        assertEquals("server-b", stateManager.snapshot().active());
    }

    @Test
    void secondSwitchRejectedWhileFirstInProgress() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);

        CompletableFuture<ConnectionRequestBuilder.Result> pendingTransfer = new CompletableFuture<>();
        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn("Steve");
        ConnectionRequestBuilder requestBuilder = mock(ConnectionRequestBuilder.class);
        when(player.createConnectionRequest(serverB)).thenReturn(requestBuilder);
        when(requestBuilder.connect()).thenReturn(pendingTransfer);

        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of(player));

        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.READY));
        RecycleExecutor recycle = fakeRecycle(new RecycleResult(true, "server-a", 1L, 2L, 111L, 999L, 1000, null));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        CompletableFuture<SwitchOutcome> first = service.switchNow();
        assertTrue(service.isSwitchInProgress());

        SwitchOutcome second = service.switchNow().get(5, TimeUnit.SECONDS);
        assertEquals(SwitchOutcome.Status.ALREADY_IN_PROGRESS, second.status());

        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        pendingTransfer.complete(result);
        SwitchOutcome firstOutcome = first.get(5, TimeUnit.SECONDS);
        assertTrue(firstOutcome.isSuccess());
    }

    @Test
    void runIdIncrementsAcrossConsecutiveSwitches() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer serverA = mock(RegisteredServer.class);
        RegisteredServer serverB = mock(RegisteredServer.class);

        when(proxy.getServer("server-a")).thenReturn(Optional.of(serverA));
        when(proxy.getServer("server-b")).thenReturn(Optional.of(serverB));
        when(serverA.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverB.ping(any(PingOptions.class))).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(serverA.getPlayersConnected()).thenReturn(List.of());
        when(serverB.getPlayersConnected()).thenReturn(List.of());

        StateManager stateManager = newStateManager(twoServerState(BackendStatus.ACTIVE, BackendStatus.READY));
        // Every recycle instantly reports READY again, regardless of which backend, so the next
        // switch is immediately legal too.
        RecycleExecutor recycle = (backendKey, oldGeneration) -> CompletableFuture.completedFuture(
                new RecycleResult(true, backendKey, 1L, 2L, 111L, 555L + oldGeneration, 1000, null));
        SwitchService service = new SwitchService(proxy, logger, stateManager, recycle, TIMEOUT_MS);

        assertEquals(1, stateManager.snapshot().runId());
        service.switchNow().get(5, TimeUnit.SECONDS);
        assertEquals(2, stateManager.snapshot().runId());
        assertEquals("server-b", stateManager.snapshot().active());

        service.switchNow().get(5, TimeUnit.SECONDS);
        assertEquals(3, stateManager.snapshot().runId());
        assertEquals("server-a", stateManager.snapshot().active());
        assertEquals(1, stateManager.snapshot().get("server-a").generation());
    }

    private Player fakePlayer(String username, RegisteredServer target, ConnectionRequestBuilder.Status status) {
        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);

        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(status == ConnectionRequestBuilder.Status.SUCCESS);
        when(result.getStatus()).thenReturn(status);

        ConnectionRequestBuilder requestBuilder = mock(ConnectionRequestBuilder.class);
        when(requestBuilder.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(player.createConnectionRequest(target)).thenReturn(requestBuilder);

        return player;
    }
}
