package com.hardcoreseamless.deathsignal;

/** Immutable death-event payload. See ARCHITECTURE.md / README.md for the schema this produces. */
public record DeathEvent(String backend, long timestampEpochMs, String playerUuid, String playerName,
                          String dimension, double x, double y, double z, String damageSource, long worldSeed) {

    String toJson(String eventId) {
        return "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"eventId\": " + q(eventId) + ",\n"
                + "  \"backend\": " + q(backend) + ",\n"
                + "  \"timestampEpochMs\": " + timestampEpochMs + ",\n"
                + "  \"playerUuid\": " + q(playerUuid) + ",\n"
                + "  \"playerName\": " + q(playerName) + ",\n"
                + "  \"dimension\": " + q(dimension) + ",\n"
                + "  \"x\": " + x + ",\n"
                + "  \"y\": " + y + ",\n"
                + "  \"z\": " + z + ",\n"
                + "  \"damageSource\": " + q(damageSource) + ",\n"
                + "  \"worldSeed\": " + worldSeed + "\n"
                + "}\n";
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
