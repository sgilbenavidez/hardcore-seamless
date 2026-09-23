package com.hardcoreseamless.coordinator;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hs status} and {@code /hs switch}. The player never picks a destination server - the
 * coordinator decides based on current ACTIVE/STANDBY (see SwitchService). {@code /server} remains
 * available as a diagnostic tool only, never used by these commands.
 */
public final class HsCommand implements SimpleCommand {

    private final SwitchService switchService;
    private final CoordinatorConfig config;

    public HsCommand(SwitchService switchService, CoordinatorConfig config) {
        this.switchService = switchService;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            source.sendPlainMessage("Usage: /hs status | /hs switch");
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> handleStatus(source);
            case "switch" -> handleSwitch(source);
            default -> source.sendPlainMessage("Unknown subcommand. Usage: /hs status | /hs switch");
        }
    }

    private void handleStatus(CommandSource source) {
        CoordinatorState state = switchService.currentState();
        source.sendPlainMessage("runId=" + state.runId() + " phase=" + state.phase());
        for (Map.Entry<String, BackendState> entry : state.servers().entrySet()) {
            BackendState bs = entry.getValue();
            String seed = bs.seed() == null ? "unknown" : bs.seed().toString();
            source.sendPlainMessage(String.format("%s: status=%s generation=%d seed=%s",
                    entry.getKey(), bs.status(), bs.generation(), seed));
        }
        source.sendPlainMessage("switchInProgress=" + switchService.isSwitchInProgress());
        if (state.pendingDeath() != null) {
            PendingDeath pd = state.pendingDeath();
            source.sendPlainMessage(String.format("pendingDeath=true lostRunId=%d player=%s",
                    pd.lostRunId(), pd.playerName()));
        } else {
            source.sendPlainMessage("pendingDeath=false");
        }
    }

    private void handleSwitch(CommandSource source) {
        CoordinatorState before = switchService.currentState();
        source.sendPlainMessage("Switch requested (" + before.active() + " -> ...)...");
        switchService.switchNow().thenAccept(outcome -> source.sendPlainMessage(describe(outcome)));
    }

    private String describe(SwitchOutcome outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> String.format("Switch complete. active=%s standby(recycling)=%s "
                    + "playersTransferred=%d durationMs=%d",
                    outcome.activeServer(), outcome.standbyServer(), outcome.playersTransferred(),
                    outcome.durationMs());
            case ALREADY_IN_PROGRESS -> "A switch is already in progress. Try again shortly.";
            case STANDBY_NOT_READY -> String.format(
                    "Switch REJECTED: standby is not READY yet (still recycling?). active=%s unchanged.",
                    outcome.activeServer());
            case PARTIAL_FAILURE -> String.format(
                    "Switch FAILED (partial): %d/%d players transferred, active/standby unchanged (active=%s).",
                    outcome.playersTransferred(), outcome.playersTransferred() + outcome.playersFailed(),
                    outcome.activeServer());
            case FAILED -> String.format("Switch FAILED (%s). active/standby unchanged (active=%s).",
                    outcome.reason(), outcome.activeServer());
        };
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource source = invocation.source();
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (source instanceof Player player) {
            return config.admins().contains(player.getUsername());
        }
        return false;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("status", "switch");
        }
        return List.of();
    }
}
