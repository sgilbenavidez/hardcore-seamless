package com.hardcoreseamless.coordinator;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * FASE 4: programmatic ACTIVE/STANDBY switching, exposed via /hs status and /hs switch.
 * FASE 5: automatic recycle of the old ACTIVE after every switch (new world, new seed) and
 * persisted state (generation/runId/seed) surviving a Velocity restart.
 * FASE 7: player death (via HardcoreDeathSignal, the Fabric sensor mod on A/B) is a second
 * *trigger* for the exact same {@link SwitchService#switchNow()} pipeline - see
 * {@link DeathCoordinator}. Still no inventory/XP/stats reset, no resource pack - see FASE 7 spec
 * sections 46-48.
 */
@Plugin(
        id = "hardcore-coordinator",
        name = "HardcoreCoordinator",
        version = "0.5.0",
        description = "Programmatic ACTIVE/STANDBY switching, automatic backend recycle, and death-triggered run loss.",
        authors = {"HardcoreSeamless"}
)
public final class HardcoreCoordinatorPlugin {

    private static final Path PROJECT_ROOT = Path.of("E:\\minecraft-hardcore");
    private static final Map<String, Integer> BACKEND_GAME_PORTS = Map.of(
            "server-a", 25566,
            "server-b", 25567
    );
    private static final long DEATH_POLL_INTERVAL_MS = 100;
    private static final long BOOT_REPROBE_INTERVAL_MS = 2000;
    private static final long BOOT_REPROBE_WINDOW_MS = 5 * 60 * 1000;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile StateManager stateManager;
    /** Backends demoted ACTIVE/READY -> FAILED at boot ONLY because their port was still closed,
     * mapped to the status they had persisted. See {@link #reprobeBootDemotions()}. */
    private final Map<String, BackendStatus> bootDemotions = new ConcurrentHashMap<>();
    private long bootReprobeDeadlineMs;

    @Inject
    public HardcoreCoordinatorPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        CoordinatorConfig config;
        try {
            config = CoordinatorConfig.loadOrCreate(dataDirectory, logger);
        } catch (IOException e) {
            logger.error("[HardcoreCoordinator] CONFIG_LOAD_FAILED - plugin will not register its command", e);
            return;
        }

        StateManager stateManager = StateManager.loadOrInit(dataDirectory, logger, () -> defaultState(config));
        recoverState(stateManager);
        this.stateManager = stateManager;
        if (!bootDemotions.isEmpty()) {
            bootReprobeDeadlineMs = System.currentTimeMillis() + BOOT_REPROBE_WINDOW_MS;
            server.getScheduler().buildTask(this, this::reprobeBootDemotions)
                    .delay(BOOT_REPROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .repeat(BOOT_REPROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .schedule();
        }

        RecycleService recycleService = new RecycleService(logger, PROJECT_ROOT);
        SwitchService switchService = new SwitchService(
                server, logger, stateManager, recycleService, config.switchTimeoutMs());

        DeathCoordinator deathCoordinator = new DeathCoordinator(
                logger, stateManager, switchService, server, config.deathSpoolDir(), config.autoDeathSwitch());

        server.getScheduler().buildTask(this, deathCoordinator::pollOnce)
                .repeat(DEATH_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .schedule();

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("hs")
                .plugin(this)
                .build();
        commandManager.register(meta, new HsCommand(switchService, config));

        CoordinatorState state = stateManager.snapshot();
        logger.info("[HardcoreCoordinator] LOADED runId={} phase={} active={} servers={} switchTimeoutMs={} "
                + "admins={} deathSpoolDir={} autoDeathSwitch={}",
                state.runId(), state.phase(), state.active(), state.servers(), config.switchTimeoutMs(),
                config.admins(), config.deathSpoolDir(), config.autoDeathSwitch());
    }

    /**
     * Routes every NEW connection to whatever backend is CURRENTLY tracked as ACTIVE, instead of
     * Velocity's static {@code try = [...]} list in velocity.toml (which never changes at
     * runtime). Without this, a player reconnecting after a death-triggered or manual switch
     * would land back on the old (now DRAINING/RECYCLING) backend - this went unnoticed through
     * FASE 4-6 only because the same player stayed continuously connected in every test there;
     * FASE 7's death flow is the first scenario where a fresh connection after a switch actually
     * matters. Falls back to Velocity's own default (untouched) if the active backend somehow
     * isn't registered.
     */
    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        StateManager sm = this.stateManager;
        if (sm == null) {
            return;
        }
        String activeName = sm.snapshot().active();
        server.getServer(activeName).ifPresent(event::setInitialServer);
    }

    /**
     * Mirrors {@link #onPlayerChooseInitialServer} for the OTHER way a player lands on a backend:
     * getting kicked from one they were already on (e.g. a keepalive timeout from a flaky client
     * connection - observed live: a player on the real ACTIVE backend timed out and Velocity's own
     * default handling then consulted velocity.toml's static {@code try = [...]} list, which has no
     * idea which backend is ACTIVE right now, and silently dropped them onto the STANDBY backend's
     * world instead (often freshly recycled and empty) - not a crash, just the wrong world, until
     * they manually reconnected and {@link #onPlayerChooseInitialServer} put them back correctly.
     *
     * <p>Never redirects a player back INTO the backend they were just kicked from - retrying the
     * exact same connection immediately isn't something to guess about here. If the kick came from
     * the ACTIVE backend, just disconnect them cleanly so a manual reconnect goes through the
     * ACTIVE-aware path above; if it came from a stale STANDBY connection, redirect straight to
     * ACTIVE instead of whatever the static list would pick.
     */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        StateManager sm = this.stateManager;
        if (sm == null) {
            return;
        }
        String activeName = sm.snapshot().active();
        String kickedFrom = event.getServer().getServerInfo().getName();
        Component reason = event.getServerKickReason().orElse(Component.text("Disconnected"));

        if (kickedFrom.equals(activeName)) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
            logger.info("[HardcoreCoordinator] KICK_FROM_ACTIVE_DISCONNECTED player={} backend={}",
                    event.getPlayer().getUsername(), kickedFrom);
            return;
        }

        Optional<RegisteredServer> activeServer = server.getServer(activeName);
        if (activeServer.isPresent()) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(activeServer.get(), reason));
            logger.info("[HardcoreCoordinator] KICK_FROM_STANDBY_REDIRECTED player={} from={} to={}",
                    event.getPlayer().getUsername(), kickedFrom, activeName);
        }
    }

    private CoordinatorState defaultState(CoordinatorConfig config) {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put(config.activeServer(), new BackendState(BackendStatus.ACTIVE, 0, null));
        servers.put(config.standbyServer(), new BackendState(BackendStatus.READY, 0, null));
        return new CoordinatorState(1, config.activeServer(), RunPhase.ACTIVE,
                System.currentTimeMillis(), servers, null, Map.of());
    }

    /**
     * Boot-time recovery (FASE 5 spec section 20, extended FASE 7 section 23). Only Velocity
     * restarted here - A/B always start before Velocity per this project's established boot order,
     * so a genuinely healthy backend should already be listening by the time this runs. A bounded,
     * synchronous TCP-connect check (no RCON) is enough to distinguish "really there" from
     * "state.json is stale" for ACTIVE/READY backends. Anything found mid-transition
     * (DRAINING/RECYCLING/STARTING) at boot means Velocity crashed or was restarted mid-recycle -
     * never assumed READY, always demoted to FAILED, matching the spec's explicit safe default.
     *
     * <p>A pending death (phase ENDING/WAITING_FOR_STANDBY) is deliberately left untouched here -
     * {@link DeathCoordinator}'s constructor re-checks and resumes it right after this runs.
     */
    private void recoverState(StateManager stateManager) {
        stateManager.update(state -> {
            Map<String, BackendState> updated = new LinkedHashMap<>(state.servers());
            for (Map.Entry<String, BackendState> entry : state.servers().entrySet()) {
                String name = entry.getKey();
                BackendState bs = entry.getValue();
                BackendStatus recovered = switch (bs.status()) {
                    case ACTIVE, READY -> isPortOpen(name) ? bs.status() : BackendStatus.FAILED;
                    case DRAINING, RECYCLING, STARTING -> BackendStatus.FAILED;
                    case FAILED -> BackendStatus.FAILED;
                };
                if (recovered != bs.status()) {
                    if (bs.status() == BackendStatus.ACTIVE || bs.status() == BackendStatus.READY) {
                        bootDemotions.put(name, bs.status());
                    }
                    logger.warn("[HardcoreCoordinator] STATE_RECOVERY backend={} persistedStatus={} "
                            + "recoveredStatus={}", name, bs.status(), recovered);
                    updated.put(name, bs.withStatus(recovered));
                }
            }
            return new CoordinatorState(state.runId(), state.active(), state.phase(),
                    state.activeRunStartedAtEpochMs(), Map.copyOf(updated), state.pendingDeath(),
                    state.deathCounts());
        });
    }

    /**
     * Velocity and A/B are often launched together, so a perfectly healthy backend can still be
     * mid-boot when {@link #recoverState} probes it (observed live: server-b opened its port ~3s
     * after Velocity's check, stayed FAILED for the whole session, and the next death sat in
     * WAITING_FOR_STANDBY forever with every player stuck in spectator). A closed port at boot is
     * therefore not proof of failure: for a bounded window, keep probing and restore the persisted
     * ACTIVE/READY status once the port opens - but only if nothing else has touched the backend
     * since (still FAILED). DeathCoordinator's poll then picks up the READY standby on its own.
     */
    private void reprobeBootDemotions() {
        if (bootDemotions.isEmpty()) {
            return;
        }
        boolean expired = System.currentTimeMillis() > bootReprobeDeadlineMs;
        for (Map.Entry<String, BackendStatus> entry : Map.copyOf(bootDemotions).entrySet()) {
            String name = entry.getKey();
            BackendStatus persisted = entry.getValue();
            if (isPortOpen(name)) {
                bootDemotions.remove(name);
                boolean[] restored = {false};
                stateManager.update(cur -> {
                    BackendState bs = cur.get(name);
                    if (bs == null || bs.status() != BackendStatus.FAILED) {
                        return cur;
                    }
                    restored[0] = true;
                    return cur.withServer(name, bs.withStatus(persisted));
                });
                if (restored[0]) {
                    logger.info("[HardcoreCoordinator] STATE_RECOVERY_LATE backend={} restoredStatus={}",
                            name, persisted);
                }
            } else if (expired) {
                bootDemotions.remove(name);
                logger.warn("[HardcoreCoordinator] STATE_RECOVERY_GAVE_UP backend={} - port still closed "
                        + "after {}ms, leaving FAILED", name, BOOT_REPROBE_WINDOW_MS);
            }
        }
    }

    private boolean isPortOpen(String backendName) {
        Integer port = BACKEND_GAME_PORTS.get(backendName);
        if (port == null) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
