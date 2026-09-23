package com.hardcoreseamless.coordinator;

/**
 * Result of a single {@link SwitchService#switchNow()} call.
 *
 * <p>For {@code SUCCESS}, activeServer/standbyServer reflect the NEW (post-swap) state. For every
 * other status, they reflect the ORIGINAL (preserved) state - the switch never partially applies
 * the active/standby swap.
 */
public record SwitchOutcome(
        Status status,
        String activeServer,
        String standbyServer,
        int playersTransferred,
        int playersFailed,
        long durationMs,
        String reason
) {

    public enum Status {
        SUCCESS,
        ALREADY_IN_PROGRESS,
        STANDBY_NOT_READY,
        PARTIAL_FAILURE,
        FAILED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static SwitchOutcome success(String newActive, String newStandby, int transferred, long durationMs) {
        return new SwitchOutcome(Status.SUCCESS, newActive, newStandby, transferred, 0, durationMs, null);
    }

    public static SwitchOutcome partialFailure(String preservedActive, String preservedStandby,
                                                int transferred, int failed, long durationMs) {
        return new SwitchOutcome(Status.PARTIAL_FAILURE, preservedActive, preservedStandby,
                transferred, failed, durationMs, "SWITCH_PARTIAL_FAILURE");
    }

    public static SwitchOutcome failed(String preservedActive, String preservedStandby, String reason,
                                        int transferred, int failed, long durationMs) {
        return new SwitchOutcome(Status.FAILED, preservedActive, preservedStandby, transferred, failed,
                durationMs, reason);
    }

    public static SwitchOutcome alreadyInProgress(String currentActive, String currentStandby) {
        return new SwitchOutcome(Status.ALREADY_IN_PROGRESS, currentActive, currentStandby, 0, 0, 0,
                "SWITCH_ALREADY_IN_PROGRESS");
    }

    public static SwitchOutcome standbyNotReady(String currentActive, String currentStandby) {
        return new SwitchOutcome(Status.STANDBY_NOT_READY, currentActive, currentStandby, 0, 0, 0,
                "STANDBY_NOT_READY");
    }
}
