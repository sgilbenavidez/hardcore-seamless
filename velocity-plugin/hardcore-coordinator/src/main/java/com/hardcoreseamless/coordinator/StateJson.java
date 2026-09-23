package com.hardcoreseamless.coordinator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads/writes {@link CoordinatorState} to/from state.json (see {@link MiniJson} for the parser).
 *
 * <p>{@code phase}, {@code activeRunStartedAtEpochMs} and {@code pendingDeath} are read leniently
 * (missing/null all default to ACTIVE / 0 / null) so a state.json written by the FASE 4/5/6
 * plugin - before these FASE 7 fields existed - still loads correctly instead of forcing a reset
 * to runId=1. This preserved real production continuity across the FASE 7 deploy.
 */
final class StateJson {

    private StateJson() {
    }

    static String write(CoordinatorState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"runId\": ").append(state.runId()).append(",\n");
        sb.append("  \"active\": ").append(MiniJson.quote(state.active())).append(",\n");
        sb.append("  \"phase\": ").append(MiniJson.quote(state.phase().name())).append(",\n");
        sb.append("  \"activeRunStartedAtEpochMs\": ").append(state.activeRunStartedAtEpochMs()).append(",\n");
        sb.append("  \"servers\": {\n");
        int i = 0;
        int total = state.servers().size();
        for (Map.Entry<String, BackendState> entry : state.servers().entrySet()) {
            BackendState bs = entry.getValue();
            sb.append("    ").append(MiniJson.quote(entry.getKey())).append(": {\n");
            sb.append("      \"status\": ").append(MiniJson.quote(bs.status().name())).append(",\n");
            sb.append("      \"generation\": ").append(bs.generation()).append(",\n");
            sb.append("      \"seed\": ").append(bs.seed() == null ? "null" : bs.seed()).append("\n");
            sb.append("    }");
            i++;
            sb.append(i < total ? ",\n" : "\n");
        }
        sb.append("  },\n");
        if (state.pendingDeath() == null) {
            sb.append("  \"pendingDeath\": null,\n");
        } else {
            PendingDeath pd = state.pendingDeath();
            sb.append("  \"pendingDeath\": {\n");
            sb.append("    \"eventId\": ").append(MiniJson.quote(pd.eventId())).append(",\n");
            sb.append("    \"lostRunId\": ").append(pd.lostRunId()).append(",\n");
            sb.append("    \"playerUuid\": ").append(MiniJson.quote(pd.playerUuid())).append(",\n");
            sb.append("    \"playerName\": ").append(MiniJson.quote(pd.playerName())).append(",\n");
            sb.append("    \"timestampEpochMs\": ").append(pd.timestampEpochMs()).append("\n");
            sb.append("  },\n");
        }
        sb.append("  \"deathCounts\": {\n");
        int di = 0;
        int deathTotal = state.deathCounts().size();
        for (Map.Entry<String, Integer> entry : state.deathCounts().entrySet()) {
            sb.append("    ").append(MiniJson.quote(entry.getKey())).append(": ").append(entry.getValue());
            di++;
            sb.append(di < deathTotal ? ",\n" : "\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    static CoordinatorState read(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);

        int runId = ((Number) root.get("runId")).intValue();
        String active = (String) root.get("active");

        RunPhase phase = root.get("phase") != null ? RunPhase.valueOf((String) root.get("phase")) : RunPhase.ACTIVE;
        long activeRunStartedAtEpochMs = root.get("activeRunStartedAtEpochMs") != null
                ? ((Number) root.get("activeRunStartedAtEpochMs")).longValue() : 0L;

        Object serversObj = root.get("servers");
        if (!(serversObj instanceof Map<?, ?> serversMap)) {
            throw new IllegalArgumentException("state.json 'servers' is not an object");
        }
        Map<String, BackendState> servers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : serversMap.entrySet()) {
            String name = (String) e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> serverObj)) {
                throw new IllegalArgumentException("state.json servers." + name + " is not an object");
            }
            BackendStatus status = BackendStatus.valueOf((String) serverObj.get("status"));
            int generation = ((Number) serverObj.get("generation")).intValue();
            Object seedObj = serverObj.get("seed");
            Long seed = seedObj == null ? null : ((Number) seedObj).longValue();
            servers.put(name, new BackendState(status, generation, seed));
        }

        PendingDeath pendingDeath = null;
        Object pendingObj = root.get("pendingDeath");
        if (pendingObj instanceof Map<?, ?> pendingMap) {
            pendingDeath = new PendingDeath(
                    (String) pendingMap.get("eventId"),
                    ((Number) pendingMap.get("lostRunId")).intValue(),
                    (String) pendingMap.get("playerUuid"),
                    (String) pendingMap.get("playerName"),
                    ((Number) pendingMap.get("timestampEpochMs")).longValue()
            );
        }

        Map<String, Integer> deathCounts = new LinkedHashMap<>();
        Object deathCountsObj = root.get("deathCounts");
        if (deathCountsObj instanceof Map<?, ?> deathCountsMap) {
            for (Map.Entry<?, ?> e : deathCountsMap.entrySet()) {
                deathCounts.put((String) e.getKey(), ((Number) e.getValue()).intValue());
            }
        }

        return new CoordinatorState(runId, active, phase, activeRunStartedAtEpochMs, servers, pendingDeath,
                Map.copyOf(deathCounts));
    }
}
