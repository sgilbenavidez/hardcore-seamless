package com.hardcoreseamless.coordinator;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;

/**
 * Owns the single programmatic switch operation. Never manipulates Minecraft packets directly -
 * every player transfer goes through {@link Player#createConnectionRequest(RegisteredServer)},
 * Velocity's own official API.
 *
 * <p>FASE 5 change from FASE 4: the ACTIVE/STANDBY model itself now lives in {@link StateManager}
 * (persisted, with generation/seed/runId), not as plain fields here. A switch may only target a
 * backend whose persisted status is {@link BackendStatus#READY} (spec section 4's invariant) -
 * this is a <em>separate</em> protection from {@code switchInProgress} below, which only guards
 * the short critical section of transferring players and swapping ACTIVE/STANDBY. Once that
 * swap commits, {@code switchInProgress} is released immediately and the old ACTIVE's recycle
 * runs fully in the background (via {@link RecycleService}) - the player never waits for it.
 */
public final class SwitchService {

    private final ProxyServer server;
    private final Logger logger;
    private final StateManager stateManager;
    private final RecycleExecutor recycleService;
    private final long switchTimeoutMs;

    private final AtomicBoolean switchInProgress = new AtomicBoolean(false);

    /** Invoked (backendName, success) whenever a background recycle finishes - registered by
     * DeathCoordinator so it can auto-resume a death that was WAITING_FOR_STANDBY (FASE 7 spec
     * section 20). Optional: null-safe no-op if nothing registers. */
    private volatile BiConsumer<String, Boolean> recycleCompletionListener;

    public SwitchService(ProxyServer server, Logger logger, StateManager stateManager,
                          RecycleExecutor recycleService, long switchTimeoutMs) {
        this.server = server;
        this.logger = logger;
        this.stateManager = stateManager;
        this.recycleService = recycleService;
        this.switchTimeoutMs = switchTimeoutMs;
    }

    public void setRecycleCompletionListener(BiConsumer<String, Boolean> listener) {
        this.recycleCompletionListener = listener;
    }

    public CoordinatorState currentState() {
        return stateManager.snapshot();
    }

    public boolean isSwitchInProgress() {
        return switchInProgress.get();
    }

    private static String theOther(CoordinatorState state, String name) {
        for (String key : state.servers().keySet()) {
            if (!key.equals(name)) {
                return key;
            }
        }
        throw new IllegalStateException("No other backend besides " + name);
    }

    /** Runs the full switch: current ACTIVE -> current STANDBY. Never blocks the calling thread. */
    public CompletableFuture<SwitchOutcome> switchNow() {
        CoordinatorState snapshotBefore = stateManager.snapshot();
        String sourceName = snapshotBefore.active();
        String targetName = theOther(snapshotBefore, sourceName);

        // Section 4's invariant: never switch to a target that isn't READY. Checked BEFORE the
        // switchInProgress gate so a recycling standby can't even attempt to start a switch.
        BackendState targetState = snapshotBefore.get(targetName);
        if (targetState == null || targetState.status() != BackendStatus.READY) {
            logger.info("[HardcoreCoordinator] SWITCH_REJECTED reason=STANDBY_NOT_READY target={} targetStatus={}",
                    targetName, targetState == null ? "UNKNOWN" : targetState.status());
            return CompletableFuture.completedFuture(SwitchOutcome.standbyNotReady(sourceName, targetName));
        }

        if (!switchInProgress.compareAndSet(false, true)) {
            logger.info("[HardcoreCoordinator] SWITCH_REJECTED reason=SWITCH_ALREADY_IN_PROGRESS");
            return CompletableFuture.completedFuture(SwitchOutcome.alreadyInProgress(sourceName, targetName));
        }

        long start = System.currentTimeMillis();

        Optional<RegisteredServer> sourceOpt = server.getServer(sourceName);
        Optional<RegisteredServer> targetOpt = server.getServer(targetName);
        if (sourceOpt.isEmpty() || targetOpt.isEmpty()) {
            switchInProgress.set(false);
            logger.warn("[HardcoreCoordinator] SWITCH_FAILED reason=SERVER_NOT_REGISTERED source={} target={}",
                    sourceName, targetName);
            return CompletableFuture.completedFuture(
                    SwitchOutcome.failed(sourceName, targetName, "SERVER_NOT_REGISTERED", 0, 0,
                            System.currentTimeMillis() - start));
        }
        RegisteredServer sourceServer = sourceOpt.get();
        RegisteredServer targetServer = targetOpt.get();

        PingOptions pingOptions = PingOptions.builder()
                .timeout(Duration.ofMillis(Math.min(switchTimeoutMs, 5000)))
                .build();

        return targetServer.ping(pingOptions)
                .handle((ping, ex) -> ex == null)
                .thenCompose(reachable -> {
                    if (!Boolean.TRUE.equals(reachable)) {
                        switchInProgress.set(false);
                        long duration = System.currentTimeMillis() - start;
                        logger.warn("[HardcoreCoordinator] SWITCH_FAILED reason=STANDBY_UNREACHABLE "
                                + "source={} target={} durationMs={}", sourceName, targetName, duration);
                        return CompletableFuture.completedFuture(
                                SwitchOutcome.failed(sourceName, targetName, "STANDBY_UNREACHABLE", 0, 0, duration));
                    }
                    return doTransfer(sourceServer, targetServer, sourceName, targetName, start);
                });
    }

    private CompletableFuture<SwitchOutcome> doTransfer(RegisteredServer sourceServer, RegisteredServer targetServer,
                                                          String sourceName, String targetName, long start) {
        List<Player> playersToTransfer = List.copyOf(sourceServer.getPlayersConnected());
        int total = playersToTransfer.size();

        logger.info("[HardcoreCoordinator] SWITCH_REQUEST source={} target={} players={}",
                sourceName, targetName, total);

        if (total == 0) {
            return CompletableFuture.completedFuture(commitSwitch(sourceName, targetName, List.of(), start));
        }

        // Transfer one player at a time, never in parallel. Transferring N players to the same
        // freshly-booted backend concurrently was observed live to race a non-thread-safe lazily
        // populated registry-encoding cache inside vanilla/Fabric itself (a real
        // ConcurrentModificationException in HashMap.computeIfAbsent while encoding a login packet
        // for the second simultaneous connection), which Velocity then reports back to us as a
        // spurious SERVER_DISCONNECTED transfer failure - not anything in this plugin. Sequential
        // transfer sidesteps that engine-level race entirely and is what actually makes this
        // correct for any N, not just 2 (spec section 19).
        CompletableFuture<List<Boolean>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (Player player : playersToTransfer) {
            chain = chain.thenCompose(resultsSoFar -> transferOnePlayer(player, targetServer, sourceName, targetName)
                    .thenApply(ok -> {
                        List<Boolean> next = new ArrayList<>(resultsSoFar);
                        next.add(ok);
                        return next;
                    }));
        }

        return chain.thenApply(results -> finishSwitch(results, playersToTransfer, sourceName, targetName, start));
    }

    private CompletableFuture<Boolean> transferOnePlayer(Player player, RegisteredServer targetServer,
                                                           String sourceName, String targetName) {
        String username = player.getUsername();
        return player.createConnectionRequest(targetServer).connect()
                .orTimeout(switchTimeoutMs, TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    if (ex != null) {
                        logger.warn("[HardcoreCoordinator] PLAYER_TRANSFER player={} source={} target={} "
                                + "result=FAILED reason={}", username, sourceName, targetName,
                                ex.getClass().getSimpleName());
                        return false;
                    }
                    boolean ok = result.isSuccessful();
                    if (ok) {
                        logger.info("[HardcoreCoordinator] PLAYER_TRANSFER player={} source={} target={} "
                                + "result=SUCCESS", username, sourceName, targetName);
                    } else {
                        logger.warn("[HardcoreCoordinator] PLAYER_TRANSFER player={} source={} target={} "
                                + "result=FAILED status={}", username, sourceName, targetName, result.getStatus());
                    }
                    return ok;
                });
    }

    private SwitchOutcome finishSwitch(List<Boolean> results, List<Player> playersToTransfer,
                                        String sourceName, String targetName, long start) {
        int total = playersToTransfer.size();
        List<Player> transferredOk = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i)) {
                transferredOk.add(playersToTransfer.get(i));
            }
        }
        long succeeded = transferredOk.size();

        if (succeeded == total) {
            return commitSwitch(sourceName, targetName, transferredOk, start);
        } else if (succeeded > 0) {
            switchInProgress.set(false);
            long duration = System.currentTimeMillis() - start;
            long failed = total - succeeded;
            logger.warn("[HardcoreCoordinator] SWITCH_PARTIAL_FAILURE active={} standby={} "
                    + "playersTransferred={} playersFailed={} durationMs={}", sourceName, targetName,
                    succeeded, failed, duration);
            return SwitchOutcome.partialFailure(sourceName, targetName, (int) succeeded, (int) failed, duration);
        } else {
            switchInProgress.set(false);
            long duration = System.currentTimeMillis() - start;
            logger.warn("[HardcoreCoordinator] SWITCH_FAILED reason=ALL_TRANSFERS_FAILED source={} target={} "
                    + "durationMs={}", sourceName, targetName, duration);
            return SwitchOutcome.failed(sourceName, targetName, "ALL_TRANSFERS_FAILED", 0, total, duration);
        }
    }

    /** All players (if any) confirmed on the new backend - commit the ACTIVE/STANDBY swap, release
     * switchInProgress, and kick off the old ACTIVE's recycle fully in the background. */
    private SwitchOutcome commitSwitch(String sourceName, String targetName, List<Player> transferred, long start) {
        CoordinatorState updated = stateManager.update(cur -> {
            int newRunId = cur.runId() + 1;
            CoordinatorState next = cur.withActive(targetName, newRunId);
            next = next.withServer(targetName, next.get(targetName).withStatus(BackendStatus.ACTIVE));
            next = next.withServer(sourceName, next.get(sourceName).withStatus(BackendStatus.DRAINING));
            // Whatever death (if any) triggered this switch has now been fulfilled, and a new run
            // begins right now regardless of trigger (manual /hs switch or a death) - this is what
            // future stale-event checks compare against (FASE 7 spec section 14).
            next = next.withPhase(RunPhase.ACTIVE);
            next = next.withActiveRunStarted(System.currentTimeMillis());
            next = next.withPendingDeath(null);
            return next;
        });

        switchInProgress.set(false);
        long duration = System.currentTimeMillis() - start;
        logger.info("[HardcoreCoordinator] SWITCH_COMPLETE runId={} active={} oldActive={} "
                + "playersTransferred={} durationMs={}", updated.runId(), targetName, sourceName,
                transferred.size(), duration);

        if (!transferred.isEmpty()) {
            RunUx.newRunStarted(logger, transferred, updated.runId());
            logger.info("[HardcoreCoordinator] NEW_RUN_UX_SENT runId={} active={}", updated.runId(), targetName);
        }

        beginRecycle(sourceName);

        return SwitchOutcome.success(targetName, sourceName, transferred.size(), duration);
    }

    private void beginRecycle(String backendName) {
        int oldGeneration = stateManager.snapshot().get(backendName).generation();
        stateManager.update(cur -> cur.withServer(backendName,
                cur.get(backendName).withStatus(BackendStatus.RECYCLING)));

        recycleService.recycleAsync(backendName, oldGeneration).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("[BackendRecycle] RECYCLE_UNEXPECTED_ERROR backend={}", backendName, ex);
                stateManager.update(cur -> cur.withServer(backendName,
                        cur.get(backendName).withStatus(BackendStatus.FAILED)));
                notifyRecycleCompletion(backendName, false);
                return;
            }
            if (result.ready()) {
                CoordinatorState updated = stateManager.update(cur -> cur.withServer(backendName,
                        cur.get(backendName).recycled(result.newSeed())));
                BackendState newState = updated.get(backendName);
                logger.info("[BackendRecycle] READY backend={} generation={} seed={} durationMs={}",
                        backendName, newState.generation(), newState.seed(), result.durationMs());
                notifyRecycleCompletion(backendName, true);
            } else {
                stateManager.update(cur -> cur.withServer(backendName,
                        cur.get(backendName).withStatus(BackendStatus.FAILED)));
                logger.error("[BackendRecycle] RECYCLE_FAILED backend={} reason={} durationMs={}",
                        backendName, result.reason(), result.durationMs());
                notifyRecycleCompletion(backendName, false);
            }
        });
    }

    private void notifyRecycleCompletion(String backendName, boolean success) {
        BiConsumer<String, Boolean> listener = recycleCompletionListener;
        if (listener != null) {
            listener.accept(backendName, success);
        }
    }
}
