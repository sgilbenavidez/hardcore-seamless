package com.hardcoreseamless.coordinator;

/** Immutable per-backend state. {@code seed} is null only before the first recycle ever assigns one. */
public record BackendState(BackendStatus status, int generation, Long seed) {

    public BackendState withStatus(BackendStatus newStatus) {
        return new BackendState(newStatus, generation, seed);
    }

    public BackendState recycled(long newSeed) {
        return new BackendState(BackendStatus.READY, generation + 1, newSeed);
    }
}
