package com.hardcoreseamless.coordinator;

/** A death that was accepted (the run it happened in is lost) but hasn't triggered its switch yet
 * because the standby wasn't READY. Persisted so it survives a Velocity restart (FASE 7 spec section 23). */
public record PendingDeath(String eventId, int lostRunId, String playerUuid, String playerName, long timestampEpochMs) {
}
