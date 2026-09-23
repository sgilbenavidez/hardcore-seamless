package com.hardcoreseamless.coordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Owns the single {@link CoordinatorState} instance and every mutation to it. Every {@link
 * #update} call persists immediately (write-temp-then-atomic-replace, per FASE 5 spec section 19),
 * so state.json always reflects the latest committed transition - this is what makes the boot-time
 * recovery in {@link HardcoreCoordinatorPlugin} meaningful.
 */
public final class StateManager {

    private final Path stateFile;
    private final Logger logger;
    private CoordinatorState state;

    private StateManager(Path stateFile, Logger logger, CoordinatorState initial) {
        this.stateFile = stateFile;
        this.logger = logger;
        this.state = initial;
    }

    static StateManager loadOrInit(Path dataDirectory, Logger logger, Supplier<CoordinatorState> defaultSupplier) {
        Path stateFile = dataDirectory.resolve("state.json");
        CoordinatorState loaded = null;
        if (Files.exists(stateFile)) {
            try {
                String json = Files.readString(stateFile);
                loaded = StateJson.read(json);
                logger.info("[HardcoreCoordinator] STATE_LOADED path={} runId={} active={}",
                        stateFile, loaded.runId(), loaded.active());
            } catch (Exception e) {
                logger.error("[HardcoreCoordinator] STATE_LOAD_FAILED path={} - falling back to defaults",
                        stateFile, e);
            }
        }

        boolean isFresh = loaded == null;
        CoordinatorState initial = isFresh ? defaultSupplier.get() : loaded;
        StateManager manager = new StateManager(stateFile, logger, initial);
        if (isFresh) {
            logger.info("[HardcoreCoordinator] STATE_INITIALIZED (no prior state.json) runId={} active={}",
                    initial.runId(), initial.active());
            manager.persist();
        }
        return manager;
    }

    public synchronized CoordinatorState snapshot() {
        return state;
    }

    public synchronized CoordinatorState update(UnaryOperator<CoordinatorState> mutator) {
        state = mutator.apply(state);
        persist();
        return state;
    }

    private void persist() {
        try {
            String json = StateJson.write(state);
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Path bak = stateFile.resolveSibling(stateFile.getFileName() + ".bak");
            if (Files.exists(stateFile)) {
                Files.copy(stateFile, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("[HardcoreCoordinator] STATE_PERSIST_FAILED path={}", stateFile, e);
        }
    }
}
