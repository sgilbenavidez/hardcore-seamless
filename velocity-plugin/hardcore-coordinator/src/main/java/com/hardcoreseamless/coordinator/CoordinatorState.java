package com.hardcoreseamless.coordinator;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of the whole coordinator: which backend is ACTIVE, the current run counter,
 * the death-trigger lifecycle phase, every backend's individual state, and the lifetime death
 * count per player name (survives recycles/restarts since it lives in state.json, not in the
 * per-generation world/ directory - see HardcoreHudMod for the read-only display side). */
public record CoordinatorState(int runId, String active, RunPhase phase, long activeRunStartedAtEpochMs,
                                Map<String, BackendState> servers, PendingDeath pendingDeath,
                                Map<String, Integer> deathCounts) {

    public BackendState get(String backend) {
        return servers.get(backend);
    }

    public CoordinatorState withServer(String backend, BackendState newState) {
        Map<String, BackendState> copy = new LinkedHashMap<>(servers);
        copy.put(backend, newState);
        return new CoordinatorState(runId, active, phase, activeRunStartedAtEpochMs, Map.copyOf(copy), pendingDeath,
                deathCounts);
    }

    public CoordinatorState withActive(String newActive, int newRunId) {
        return new CoordinatorState(newRunId, newActive, phase, activeRunStartedAtEpochMs, servers, pendingDeath,
                deathCounts);
    }

    public CoordinatorState withPhase(RunPhase newPhase) {
        return new CoordinatorState(runId, active, newPhase, activeRunStartedAtEpochMs, servers, pendingDeath,
                deathCounts);
    }

    public CoordinatorState withActiveRunStarted(long epochMs) {
        return new CoordinatorState(runId, active, phase, epochMs, servers, pendingDeath, deathCounts);
    }

    public CoordinatorState withPendingDeath(PendingDeath death) {
        return new CoordinatorState(runId, active, phase, activeRunStartedAtEpochMs, servers, death, deathCounts);
    }

    /** Attributes one more death to {@code playerName} (spec: whoever actually died, never the
     * run/backend). Keyed by name, matching every other player-facing display in this project. */
    public CoordinatorState withDeathCounted(String playerName) {
        Map<String, Integer> copy = new LinkedHashMap<>(deathCounts);
        copy.merge(playerName, 1, Integer::sum);
        return new CoordinatorState(runId, active, phase, activeRunStartedAtEpochMs, servers, pendingDeath,
                Map.copyOf(copy));
    }
}
