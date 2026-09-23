package com.hardcoreseamless.coordinator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Plugin configuration, loaded from {@code config.properties} in the plugin's data directory.
 *
 * <p>activeServer/standbyServer are used only to seed a brand-new state.json on the very first
 * boot (see {@link StateManager#loadOrInit}) - once state.json exists, IT is the source of truth
 * for active/standby/generation/seed/runId, not this file (FASE 5+).
 */
public record CoordinatorConfig(String activeServer, String standbyServer, Set<String> admins,
                                 long switchTimeoutMs, Path deathSpoolDir, boolean autoDeathSwitch) {

    private static final String DEFAULT_CONTENT = """
            # HardcoreCoordinator configuration.
            #
            # activeServer/standbyServer: only used to seed a brand-new state.json on the very
            # first boot. Once state.json exists, it is the source of truth (see FASE 5/7 docs).
            activeServer=server-a
            standbyServer=server-b

            # Comma-separated usernames allowed to run /hs switch. The Velocity console always has
            # permission regardless of this list. This is a minimal stand-in for a real permission
            # plugin (LuckPerms etc.), appropriate for this phase - not meant to be the long-term
            # permission mechanism.
            admins=

            # Maximum time (seconds) a single switch operation may take before individual player
            # transfers are considered failed and the operation reports FAILED/PARTIAL_FAILURE.
            switchTimeoutSeconds=15

            # Where HardcoreDeathSignal (the Fabric mod on A/B) writes death events. Must match
            # that mod's -Dhardcore.deathSpoolDir (or its default) on both backends. Forward
            # slashes on purpose - java.util.Properties would otherwise mangle backslashes on
            # read (they're its escape character), and Java's NIO paths accept '/' fine on Windows.
            deathSpoolDir=E:/minecraft-hardcore/runtime/death-events

            # false = diagnostic mode: death events are still read, validated, logged as
            # DEATH_ACCEPTED, and moved to processed/, but no switch is triggered. Used to verify
            # the sensor in isolation before enabling the real automatic behavior (FASE 7 spec
            # section 29). true = normal operation.
            autoDeathSwitch=true
            """;

    public static CoordinatorConfig loadOrCreate(Path dataDirectory, Logger logger) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.properties");
        if (!Files.exists(configPath)) {
            Files.writeString(configPath, DEFAULT_CONTENT);
            logger.info("[HardcoreCoordinator] CONFIG_CREATED path={}", configPath);
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        }

        String active = props.getProperty("activeServer", "server-a").trim();
        String standby = props.getProperty("standbyServer", "server-b").trim();

        String adminsRaw = props.getProperty("admins", "");
        Set<String> admins = Arrays.stream(adminsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        long timeoutSeconds;
        try {
            timeoutSeconds = Long.parseLong(props.getProperty("switchTimeoutSeconds", "15").trim());
        } catch (NumberFormatException e) {
            logger.warn("[HardcoreCoordinator] CONFIG_INVALID_TIMEOUT falling back to 15s", e);
            timeoutSeconds = 15;
        }

        String spoolDirRaw = props.getProperty("deathSpoolDir", "E:/minecraft-hardcore/runtime/death-events").trim();
        Path deathSpoolDir = Path.of(spoolDirRaw);

        boolean autoDeathSwitch = Boolean.parseBoolean(props.getProperty("autoDeathSwitch", "true").trim());

        return new CoordinatorConfig(active, standby, admins, timeoutSeconds * 1000L, deathSpoolDir, autoDeathSwitch);
    }
}
