package com.hardcoreseamless.coordinator;

/** Lifecycle status of a single backend (server-a or server-b). See FASE 5 spec section 3. */
public enum BackendStatus {
    /** Where players currently connected to this run should be. */
    ACTIVE,
    /** Fully booted and eligible to become ACTIVE on the next switch. */
    READY,
    /** Just stopped being ACTIVE; about to be recycled. */
    DRAINING,
    /** JVM stopped / world being replaced. */
    RECYCLING,
    /** New world created, backend booting. */
    STARTING,
    /** Recycle failed; this backend cannot receive players until fixed. */
    FAILED
}
