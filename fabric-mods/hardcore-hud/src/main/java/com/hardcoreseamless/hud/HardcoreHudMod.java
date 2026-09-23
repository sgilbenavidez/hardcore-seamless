package com.hardcoreseamless.hud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FASE 9 — purely visual, server-side only, no client mod required. Two independent
 * responsibilities, neither of which touch death/switch/recycle logic (see ARCHITECTURE.md "No
 * hacer" list):
 *
 * <ol>
 *   <li>TAB health: bootstraps a vanilla scoreboard objective (criterion {@code health}, render
 *   type {@code hearts}, display slot {@code list}) on every server start - re-created here
 *   because {@code world/} (where the scoreboard lives) is deleted on every recycle (FASE 5).</li>
 *   <li>Death counter: a second scoreboard objective on the sidebar slot, showing the total
 *   deaths so far as its title and each connected player's own lifetime death count as their row
 *   (all read from HardcoreCoordinator's {@code state.json} {@code deathCounts} map, read-only,
 *   never used for control logic - the count is attributed to whoever actually died, not the
 *   run/backend). Vanilla has no
 *   command-only way to place a panel at the top-left specifically; the sidebar (top-right) is the
 *   closest built-in "discreet corner panel" without adding a client mod - see README.md for that
 *   tradeoff.</li>
 * </ol>
 *
 * <p>Built and verified the same way as {@code hardcore-death-signal}:
 * {@code loom.officialMojangMappings()}, every API used here checked with {@code javap} against
 * the real remapped Minecraft jar (never assumed from Yarn-mapping tutorials).
 */
public final class HardcoreHudMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardcoreHud");
    private static final String HEALTH_OBJECTIVE_NAME = "hc_health";
    private static final String DEATHS_OBJECTIVE_NAME = "hc_deaths";

    // Once a second - the death counter and player list don't need every-tick responsiveness.
    private static final int DEATH_COUNTER_INTERVAL_TICKS = 20;

    // HardcoreCoordinator (a separate JVM/process, Velocity-side) owns runId - this mod only ever
    // reads its state.json for display, the same file-based decoupling already used for the death
    // event spool between HardcoreDeathSignal and HardcoreCoordinator. Read-only: never touched
    // for control logic, only to show a number (spec section 4: don't touch runId's lifecycle).
    private static final Path DEFAULT_STATE_JSON_PATH =
            Path.of("E:/minecraft-hardcore/proxy/velocity/plugins/hardcore-coordinator/state.json");
    // HardcoreCoordinator (StateJson.write) always emits deathCounts as a flat {"name": N, ...}
    // object with no nested braces, so a non-greedy match up to the first '}' is exact - same
    // read-only, display-only contract as runId above (spec: never used for control logic).
    private static final Pattern DEATH_COUNTS_BLOCK_PATTERN =
            Pattern.compile("\"deathCounts\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final Pattern DEATH_COUNT_ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");

    private final Path stateJsonPath;
    private int deathCounterTickCounter = 0;
    private boolean stateJsonReadFailureLogged = false;

    public HardcoreHudMod() {
        String override = System.getProperty("hardcore.stateJsonPath");
        this.stateJsonPath = override != null ? Path.of(override) : DEFAULT_STATE_JSON_PATH;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::setupTabHealth);
        ServerLifecycleEvents.SERVER_STARTED.register(this::setupDeathCounter);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        LOGGER.info("[HardcoreHud] LOADED");
    }

    // --- TAB health (spec section 6) ---------------------------------------------------------

    private void setupTabHealth(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(HEALTH_OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(HEALTH_OBJECTIVE_NAME, ObjectiveCriteria.HEALTH,
                    Component.literal("Health"), ObjectiveCriteria.RenderType.HEARTS);
            LOGGER.info("[HardcoreHud] TAB_HEALTH_OBJECTIVE_CREATED name={}", HEALTH_OBJECTIVE_NAME);
        }
        scoreboard.setDisplayObjective(Scoreboard.DISPLAY_SLOT_LIST, objective);
        LOGGER.info("[HardcoreHud] TAB_HEALTH_READY objective={}", HEALTH_OBJECTIVE_NAME);
    }

    // --- Death counter (discreet corner panel: sidebar - see class javadoc for why not top-left)

    private void setupDeathCounter(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(DEATHS_OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(DEATHS_OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
                    Component.literal("Runs Lost"), ObjectiveCriteria.RenderType.INTEGER);
            LOGGER.info("[HardcoreHud] DEATH_COUNTER_OBJECTIVE_CREATED name={}", DEATHS_OBJECTIVE_NAME);
        }
        scoreboard.setDisplayObjective(Scoreboard.DISPLAY_SLOT_SIDEBAR, objective);
        LOGGER.info("[HardcoreHud] DEATH_COUNTER_READY objective={}", DEATHS_OBJECTIVE_NAME);
    }

    private void updateDeathCounter(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(DEATHS_OBJECTIVE_NAME);
        if (objective == null) {
            return;
        }

        Map<String, Integer> deathCounts = readDeathCounts();
        int totalDeaths = deathCounts.values().stream().mapToInt(Integer::intValue).sum();
        objective.setDisplayName(Component.literal("\u2620 Runs Lost: " + totalDeaths));

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<String> currentNames = new HashSet<>();
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().getName();
            currentNames.add(name);
            // Each row shows THAT player's own lifetime death count, attributed by whoever actually
            // died (DeathCoordinator.withDeathCounted) - never a shared/zeroed placeholder.
            scoreboard.getOrCreatePlayerScore(name, objective).setScore(deathCounts.getOrDefault(name, 0));
        }
        // Drop rows for anyone who disconnected/switched away, so the panel never shows a stale
        // name (spec section 5.5-equivalent for this feature: reflect who's connected right now).
        for (var score : List.copyOf(scoreboard.getPlayerScores(objective))) {
            if (!currentNames.contains(score.getOwner())) {
                scoreboard.resetPlayerScore(score.getOwner(), objective);
            }
        }
    }

    private Map<String, Integer> readDeathCounts() {
        Map<String, Integer> result = new HashMap<>();
        try {
            String content = Files.readString(stateJsonPath);
            Matcher blockMatcher = DEATH_COUNTS_BLOCK_PATTERN.matcher(content);
            if (blockMatcher.find()) {
                Matcher entryMatcher = DEATH_COUNT_ENTRY_PATTERN.matcher(blockMatcher.group(1));
                while (entryMatcher.find()) {
                    result.put(entryMatcher.group(1), Integer.parseInt(entryMatcher.group(2)));
                }
            }
        } catch (IOException e) {
            if (!stateJsonReadFailureLogged) {
                LOGGER.warn("[HardcoreHud] STATE_JSON_READ_FAILED path={} (will keep trying, logged once)",
                        stateJsonPath, e);
                stateJsonReadFailureLogged = true;
            }
        }
        return result;
    }

    private void onEndTick(MinecraftServer server) {
        deathCounterTickCounter++;
        if (deathCounterTickCounter >= DEATH_COUNTER_INTERVAL_TICKS) {
            deathCounterTickCounter = 0;
            updateDeathCounter(server);
        }
    }
}
