package com.hardcoreseamless.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateJsonTest {

    @Test
    void roundTripsWithSeedsAndNegativeNumbers() {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(BackendStatus.ACTIVE, 2, -8245886443898679866L));
        servers.put("server-b", new BackendState(BackendStatus.READY, 1, 6605584822522826534L));
        CoordinatorState original = new CoordinatorState(7, "server-a", RunPhase.ACTIVE, 123456789L, servers, null,
                Map.of());

        String json = StateJson.write(original);
        CoordinatorState parsed = StateJson.read(json);

        assertEquals(original.runId(), parsed.runId());
        assertEquals(original.active(), parsed.active());
        assertEquals(original.phase(), parsed.phase());
        assertEquals(original.activeRunStartedAtEpochMs(), parsed.activeRunStartedAtEpochMs());
        assertEquals(original.get("server-a"), parsed.get("server-a"));
        assertEquals(original.get("server-b"), parsed.get("server-b"));
        assertNull(parsed.pendingDeath());
    }

    @Test
    void roundTripsNullSeed() {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(BackendStatus.ACTIVE, 0, null));
        servers.put("server-b", new BackendState(BackendStatus.READY, 0, null));
        CoordinatorState original = new CoordinatorState(1, "server-a", RunPhase.ACTIVE, 0L, servers, null, Map.of());

        CoordinatorState parsed = StateJson.read(StateJson.write(original));

        assertNull(parsed.get("server-a").seed());
        assertEquals(0, parsed.get("server-b").generation());
    }

    @Test
    void roundTripsPendingDeath() {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(BackendStatus.ACTIVE, 0, 1L));
        servers.put("server-b", new BackendState(BackendStatus.RECYCLING, 0, 2L));
        PendingDeath pending = new PendingDeath("event-123", 5, "uuid-abc", "Steve", 999L);
        CoordinatorState original = new CoordinatorState(5, "server-a", RunPhase.WAITING_FOR_STANDBY,
                111L, servers, pending, Map.of());

        CoordinatorState parsed = StateJson.read(StateJson.write(original));

        assertEquals(RunPhase.WAITING_FOR_STANDBY, parsed.phase());
        assertEquals(pending, parsed.pendingDeath());
    }

    @Test
    void roundTripsDeathCounts() {
        Map<String, BackendState> servers = new LinkedHashMap<>();
        servers.put("server-a", new BackendState(BackendStatus.ACTIVE, 0, 1L));
        servers.put("server-b", new BackendState(BackendStatus.READY, 0, 2L));
        Map<String, Integer> deathCounts = new LinkedHashMap<>();
        deathCounts.put("Steve", 3);
        deathCounts.put("Steve", 1);
        CoordinatorState original = new CoordinatorState(4, "server-a", RunPhase.ACTIVE, 0L, servers, null,
                deathCounts);

        CoordinatorState parsed = StateJson.read(StateJson.write(original));

        assertEquals(deathCounts, parsed.deathCounts());
    }

    @Test
    void readsFormatMissingDeathCountsWithLenientDefault() {
        // Exactly the shape written before deathCounts existed.
        String oldFormatJson = """
                {
                  "runId": 3,
                  "active": "server-a",
                  "phase": "ACTIVE",
                  "activeRunStartedAtEpochMs": 0,
                  "servers": {
                    "server-a": { "status": "ACTIVE", "generation": 0, "seed": null },
                    "server-b": { "status": "READY", "generation": 0, "seed": null }
                  },
                  "pendingDeath": null
                }
                """;

        CoordinatorState parsed = StateJson.read(oldFormatJson);

        assertEquals(Map.of(), parsed.deathCounts());
    }

    @Test
    void readsOldFormatMissingPhaseFieldsWithLenientDefaults() {
        // Exactly the shape written by the FASE 4/5/6 plugin, before phase/pendingDeath existed.
        String oldFormatJson = """
                {
                  "runId": 23,
                  "active": "server-a",
                  "servers": {
                    "server-b": { "status": "READY", "generation": 11, "seed": -8172297154021411634 },
                    "server-a": { "status": "ACTIVE", "generation": 11, "seed": 6556750131092450639 }
                  }
                }
                """;

        CoordinatorState parsed = StateJson.read(oldFormatJson);

        assertEquals(23, parsed.runId());
        assertEquals("server-a", parsed.active());
        assertEquals(RunPhase.ACTIVE, parsed.phase());
        assertEquals(0L, parsed.activeRunStartedAtEpochMs());
        assertNull(parsed.pendingDeath());
        assertEquals(11, parsed.get("server-a").generation());
    }
}
