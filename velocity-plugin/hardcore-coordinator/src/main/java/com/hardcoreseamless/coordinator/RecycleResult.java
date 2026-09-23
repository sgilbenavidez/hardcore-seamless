package com.hardcoreseamless.coordinator;

import java.util.HashMap;
import java.util.Map;

/** Parsed form of recycle-backend.ps1's final {@code RECYCLE_RESULT ...} stdout line. */
public record RecycleResult(boolean ready, String backend, Long oldPid, Long newPid, Long oldSeed,
                             Long newSeed, long durationMs, String reason) {

    static RecycleResult parse(String line) {
        Map<String, String> fields = new HashMap<>();
        String rest = line.substring("RECYCLE_RESULT".length()).trim();
        for (String token : rest.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq < 0) {
                continue;
            }
            fields.put(token.substring(0, eq), token.substring(eq + 1));
        }
        boolean ready = "READY".equals(fields.get("status"));
        return new RecycleResult(
                ready,
                fields.get("backend"),
                parseLongOrNull(fields.get("oldPid")),
                parseLongOrNull(fields.get("newPid")),
                parseLongOrNull(fields.get("oldSeed")),
                parseLongOrNull(fields.get("newSeed")),
                parseLongOrNull(fields.get("durationMs")) == null ? 0 : parseLongOrNull(fields.get("durationMs")),
                fields.get("reason"));
    }

    static RecycleResult failed(String reason) {
        return new RecycleResult(false, null, null, null, null, null, 0, reason);
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
