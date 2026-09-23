package com.hardcoreseamless.coordinator;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

/**
 * Minimal, best-effort UX for the hardcore death flow (FASE 8 spec sections 20-23). Uses Velocity's
 * own Adventure {@code Player#sendMessage}/{@code Player#showTitle} - verified against the real
 * running {@code velocity-4.2.0-30.jar} via javap, not assumed from an older Adventure version's
 * tutorials (spec section 22).
 *
 * <p>UX is a <em>consequence</em> of state, never a precondition for it (spec section 23): every
 * send here is wrapped so a failure only logs a warning and can never block or fail the actual
 * switch/recycle pipeline.
 */
final class RunUx {

    private RunUx() {
    }

    static void runLost(Logger logger, Collection<Player> players, String deadPlayerName) {
        Component title = Component.text("RUN LOST", NamedTextColor.DARK_RED);
        Component subtitle = Component.text(deadPlayerName + " died", NamedTextColor.GRAY);
        broadcastTitle(logger, players, title, subtitle);
        broadcastMessage(logger, players,
                Component.text("RUN LOST - " + deadPlayerName + " died", NamedTextColor.DARK_RED));
    }

    static void preparingNextRun(Logger logger, Collection<Player> players) {
        broadcastMessage(logger, players, Component.text("Preparing next run...", NamedTextColor.GOLD));
    }

    static void newRunStarted(Logger logger, Collection<Player> players, int runId) {
        Component title = Component.text("RUN #" + runId, NamedTextColor.GREEN);
        Component subtitle = Component.text("New world started", NamedTextColor.GRAY);
        broadcastTitle(logger, players, title, subtitle);
        broadcastMessage(logger, players,
                Component.text("RUN #" + runId + " - New world started", NamedTextColor.GREEN));
    }

    private static void broadcastTitle(Logger logger, Collection<Player> players, Component title, Component subtitle) {
        Title t = Title.title(title, subtitle);
        for (Player p : players) {
            try {
                p.showTitle(t);
            } catch (RuntimeException e) {
                logger.warn("[HardcoreCoordinator] UX_SEND_FAILED type=title player={}", p.getUsername(), e);
            }
        }
    }

    private static void broadcastMessage(Logger logger, Collection<Player> players, Component message) {
        for (Player p : players) {
            try {
                p.sendMessage(message);
            } catch (RuntimeException e) {
                logger.warn("[HardcoreCoordinator] UX_SEND_FAILED type=chat player={}", p.getUsername(), e);
            }
        }
    }
}
