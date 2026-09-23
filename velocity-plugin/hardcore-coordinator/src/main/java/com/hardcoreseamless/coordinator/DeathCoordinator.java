package com.hardcoreseamless.coordinator;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Consumes death events written by the HardcoreDeathSignal Fabric mod (one JSON file per real
 * death, in {@code spoolRoot/inbox/}) and turns the first valid one per run into exactly the same
 * {@link SwitchService#switchNow()} operation already certified in FASE 4-6 - see FASE 7 spec
 * section 2: death is a new *trigger*, never a second transfer mechanism.
 *
 * <p>Polling (not filesystem watching) because it's simpler, and per spec section 12 a light poll
 * (see {@link HardcoreCoordinatorPlugin} for the interval) is explicitly acceptable as long as it
 * doesn't busy-spin or block Velocity's main thread - every call here runs on the scheduler task
 * thread, never inline with a network event.
 */
public final class DeathCoordinator {

    private final Logger logger;
    private final StateManager stateManager;
    private final SwitchService switchService;
    private final ProxyServer server;
    private final Path inboxDir;
    private final Path processedDir;
    private final Path invalidDir;
    private final boolean autoSwitchEnabled;
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    public DeathCoordinator(Logger logger, StateManager stateManager, SwitchService switchService,
                             ProxyServer server, Path spoolRoot, boolean autoSwitchEnabled) {
        this.logger = logger;
        this.stateManager = stateManager;
        this.switchService = switchService;
        this.server = server;
        this.inboxDir = spoolRoot.resolve("inbox");
        this.processedDir = spoolRoot.resolve("processed");
        this.invalidDir = spoolRoot.resolve("invalid");
        this.autoSwitchEnabled = autoSwitchEnabled;

        preloadProcessedIds();
        switchService.setRecycleCompletionListener(this::onBackendRecycled);

        // Recovery (FASE 7 spec section 23): if Velocity restarted while a death was already
        // accepted and waiting on the standby, re-check right now instead of only on the next
        // recycle completion - the standby may already be READY by the time we reboot.
        CoordinatorState initial = stateManager.snapshot();
        if (initial.phase() == RunPhase.WAITING_FOR_STANDBY || initial.phase() == RunPhase.ENDING) {
            logger.info("[HardcoreCoordinator] RECOVERED_PENDING_DEATH phase={} pendingDeath={}",
                    initial.phase(), initial.pendingDeath());
            attemptDeathSwitch();
        }
    }

    /** Called periodically by the plugin's scheduler task. */
    public void pollOnce() {
        if (Files.isDirectory(inboxDir)) {
            try (Stream<Path> stream = Files.list(inboxDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(this::processFile);
            } catch (IOException e) {
                logger.error("[HardcoreCoordinator] DEATH_POLL_ERROR", e);
            }
        }

        // Self-healing retry: WAITING_FOR_STANDBY is normally cleared by onBackendRecycled() when
        // a recycle finishes, but a switch can also land back in WAITING_FOR_STANDBY because the
        // transfer itself failed (e.g. a player-count-dependent race on the target, not the
        // standby being unready) - in that case no new recycle is ever going to complete, so that
        // callback would never fire again and the run would stay stuck forever. Checking here on
        // every poll makes recovery independent of *why* a retry is needed; attemptDeathSwitch()
        // is already a safe no-op unless phase is ENDING/WAITING_FOR_STANDBY, and switchNow()
        // itself guards against overlapping with a switch already in flight - reuses the same
        // certified pipeline, never a second mechanism (spec section 19: must scale to N players).
        if (autoSwitchEnabled) {
            attemptDeathSwitch();
        }
    }

    private void processFile(Path file) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            // Could be a half-written .json if something violated the atomic-rename contract -
            // leave it for the next poll rather than guessing.
            return;
        }

        ParsedDeathEvent event;
        try {
            event = ParsedDeathEvent.parse(raw);
        } catch (RuntimeException e) {
            logger.warn("[HardcoreCoordinator] DEATH_EVENT_INVALID file={} reason=PARSE_ERROR", file.getFileName(), e);
            moveTo(file, invalidDir);
            return;
        }

        handleEvent(event, file);
    }

    private void handleEvent(ParsedDeathEvent event, Path file) {
        if (!seenEventIds.add(event.eventId())) {
            logger.info("[HardcoreCoordinator] DEATH_IGNORED reason=DUPLICATE_EVENT eventId={}", event.eventId());
            moveTo(file, processedDir);
            return;
        }

        CoordinatorState snapshot = stateManager.snapshot();

        if (!snapshot.servers().containsKey(event.backend())) {
            logger.warn("[HardcoreCoordinator] DEATH_IGNORED reason=UNKNOWN_BACKEND eventId={} backend={}",
                    event.eventId(), event.backend());
            moveTo(file, invalidDir);
            return;
        }

        if (!event.backend().equals(snapshot.active())) {
            logger.info("[HardcoreCoordinator] DEATH_IGNORED reason=BACKEND_NOT_ACTIVE eventId={} backend={} active={}",
                    event.eventId(), event.backend(), snapshot.active());
            moveTo(file, processedDir);
            return;
        }

        if (event.timestampEpochMs() < snapshot.activeRunStartedAtEpochMs()) {
            logger.info("[HardcoreCoordinator] DEATH_IGNORED reason=STALE_EVENT eventId={} eventTs={} runStartedAt={}",
                    event.eventId(), event.timestampEpochMs(), snapshot.activeRunStartedAtEpochMs());
            moveTo(file, processedDir);
            return;
        }

        if (snapshot.phase() != RunPhase.ACTIVE) {
            logger.info("[HardcoreCoordinator] DEATH_IGNORED reason=RUN_ALREADY_ENDING eventId={} phase={}",
                    event.eventId(), snapshot.phase());
            moveTo(file, processedDir);
            return;
        }

        // Accept - persist BEFORE doing anything else (spec section 15).
        int lostRunId = snapshot.runId();
        PendingDeath pending = new PendingDeath(event.eventId(), lostRunId, event.playerUuid(),
                event.playerName(), event.timestampEpochMs());
        stateManager.update(cur -> cur.withPhase(RunPhase.ENDING).withPendingDeath(pending)
                .withDeathCounted(event.playerName()));

        logger.info("[HardcoreCoordinator] DEATH_ACCEPTED eventId={} runId={} backend={} player={} cause={}",
                event.eventId(), lostRunId, event.backend(), event.playerName(), event.damageSource());
        logger.info("[HardcoreCoordinator] GLOBAL_RUN_LOST runId={}", lostRunId);
        RunUx.runLost(logger, playersOn(event.backend()), event.playerName());
        logger.info("[HardcoreCoordinator] RUN_LOST_UX_SENT runId={}", lostRunId);

        moveTo(file, processedDir);

        if (!autoSwitchEnabled) {
            logger.info("[HardcoreCoordinator] DEATH_DIAGNOSTIC_MODE - AUTO_DEATH_SWITCH disabled, not switching.");
            return;
        }

        attemptDeathSwitch();
    }

    /** Tries to actually perform the death-triggered switch right now. Safe to call repeatedly -
     * it's a no-op unless phase is ENDING or WAITING_FOR_STANDBY. */
    private void attemptDeathSwitch() {
        CoordinatorState snapshot = stateManager.snapshot();
        if (snapshot.phase() != RunPhase.ENDING && snapshot.phase() != RunPhase.WAITING_FOR_STANDBY) {
            return;
        }

        String activeName = snapshot.active();
        String standbyName = theOther(snapshot, activeName);
        BackendState standby = snapshot.get(standbyName);

        if (standby.status() != BackendStatus.READY) {
            boolean alreadyWaiting = snapshot.phase() == RunPhase.WAITING_FOR_STANDBY;
            stateManager.update(cur -> cur.withPhase(RunPhase.WAITING_FOR_STANDBY));
            PendingDeath pd = snapshot.pendingDeath();
            logger.info("[HardcoreCoordinator] DEATH_WAITING_FOR_STANDBY lostRunId={} standby={} standbyStatus={}",
                    pd != null ? pd.lostRunId() : snapshot.runId(), standbyName, standby.status());
            // Only once per transition into WAITING_FOR_STANDBY (spec section 21) - a retry that
            // finds the standby still not ready must not resend the message.
            if (!alreadyWaiting) {
                RunUx.preparingNextRun(logger, playersOn(activeName));
            }
            return;
        }

        stateManager.update(cur -> cur.withPhase(RunPhase.SWITCHING));
        logger.info("[HardcoreCoordinator] DEATH_SWITCH_BEGIN source={} target={}", activeName, standbyName);

        switchService.switchNow().whenComplete((outcome, ex) -> {
            boolean ok = ex == null && outcome != null && outcome.isSuccess();
            if (!ok) {
                logger.error("[HardcoreCoordinator] DEATH_SWITCH_FAILED source={} target={} - "
                        + "reverting to WAITING_FOR_STANDBY so it can be retried", activeName, standbyName);
                stateManager.update(cur -> cur.withPhase(RunPhase.WAITING_FOR_STANDBY));
            }
            // On success, SwitchService.commitSwitch() already reset phase=ACTIVE, cleared
            // pendingDeath, and stamped activeRunStartedAtEpochMs - nothing more to do here.
        });
    }

    /** Registered with SwitchService - fires whenever ANY recycle finishes, for either backend. */
    private void onBackendRecycled(String backendName, boolean success) {
        if (!success) {
            return;
        }
        CoordinatorState snapshot = stateManager.snapshot();
        if (snapshot.phase() != RunPhase.WAITING_FOR_STANDBY) {
            return;
        }
        String standbyName = theOther(snapshot, snapshot.active());
        if (!standbyName.equals(backendName)) {
            return;
        }
        logger.info("[HardcoreCoordinator] PENDING_DEATH_AUTO_RESUME backend={}", backendName);
        attemptDeathSwitch();
    }

    private Collection<Player> playersOn(String backendName) {
        return server.getServer(backendName)
                .<Collection<Player>>map(rs -> List.copyOf(rs.getPlayersConnected()))
                .orElse(List.of());
    }

    private static String theOther(CoordinatorState state, String name) {
        for (String key : state.servers().keySet()) {
            if (!key.equals(name)) {
                return key;
            }
        }
        throw new IllegalStateException("No other backend besides " + name);
    }

    private void moveTo(Path file, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            Files.move(file, targetDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("[HardcoreCoordinator] DEATH_EVENT_MOVE_FAILED file={}", file.getFileName(), e);
        }
    }

    private void preloadProcessedIds() {
        if (!Files.isDirectory(processedDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(processedDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .forEach(seenEventIds::add);
        } catch (IOException e) {
            logger.warn("[HardcoreCoordinator] Failed to preload processed death event IDs", e);
        }
    }
}
