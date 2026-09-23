package com.hardcoreseamless.coordinator;

import java.util.Map;

/** Parsed form of a death-event JSON file written by the HardcoreDeathSignal Fabric mod. */
public record ParsedDeathEvent(String eventId, String backend, long timestampEpochMs, String playerUuid,
                                String playerName, String damageSource) {

    static ParsedDeathEvent parse(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);

        Object schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || ((Number) schemaVersion).intValue() != 1) {
            throw new IllegalArgumentException("Unsupported or missing schemaVersion: " + schemaVersion);
        }

        String eventId = requireString(root, "eventId");
        String backend = requireString(root, "backend");
        long timestampEpochMs = ((Number) requireNonNull(root, "timestampEpochMs")).longValue();
        String playerUuid = requireString(root, "playerUuid");
        String playerName = requireString(root, "playerName");
        String damageSource = requireString(root, "damageSource");

        return new ParsedDeathEvent(eventId, backend, timestampEpochMs, playerUuid, playerName, damageSource);
    }

    private static Object requireNonNull(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private static String requireString(Map<String, Object> root, String key) {
        return (String) requireNonNull(root, key);
    }
}
