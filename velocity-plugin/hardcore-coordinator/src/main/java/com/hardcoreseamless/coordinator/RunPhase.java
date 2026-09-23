package com.hardcoreseamless.coordinator;

/** Lifecycle phase of the current run, orthogonal to per-backend {@link BackendStatus}. See FASE 7 spec section 15. */
public enum RunPhase {
    /** Normal play - a death event will be accepted. */
    ACTIVE,
    /** A death was just accepted; about to check the standby and switch. Transient. */
    ENDING,
    /** A death was accepted but the standby isn't READY yet - waiting for its recycle to finish
     * before auto-resuming the switch. Can persist across a Velocity restart. */
    WAITING_FOR_STANDBY,
    /** The actual switch operation is in flight. Transient. */
    SWITCHING
}
