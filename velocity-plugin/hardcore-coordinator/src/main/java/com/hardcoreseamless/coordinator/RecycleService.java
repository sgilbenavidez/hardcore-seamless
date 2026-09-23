package com.hardcoreseamless.coordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Invokes scripts/windows/recycle-backend.ps1 as an external process for the given backend
 * ("A" or "B" only - see the fixed BACKEND_KEY_TO_LETTER map, never derived from arbitrary input)
 * and parses its result. Always runs on a dedicated single-thread daemon executor, never on
 * Velocity's main/event thread (FASE 5 spec section 6).
 *
 * <p><b>Output is redirected to a log file, not read via a Java-side pipe.</b> An earlier version
 * piped the child's stdout directly (ProcessBuilder + BufferedReader). That hung indefinitely in
 * practice: recycle-backend.ps1 itself starts the new backend JVM via Start-Process, and on
 * Windows that grandchild process inherits a duplicate handle to the stdout pipe whose write end
 * Java was reading - so the pipe never sees EOF as long as the (intentionally long-lived) backend
 * keeps running, which is always, since that's the whole point of starting it. Caught via live
 * end-to-end testing (see FASE 5 report), not something a unit test would have revealed. File
 * redirection sidesteps the problem entirely - Process.waitFor() waits on the actual process
 * handle, which is unaffected by any of this.
 */
public final class RecycleService implements RecycleExecutor {

    private static final java.util.Map<String, String> BACKEND_KEY_TO_LETTER = java.util.Map.of(
            "server-a", "A",
            "server-b", "B"
    );

    private final Logger logger;
    private final Path scriptPath;
    private final Path recycleLogDir;
    private final ExecutorService executor;

    public RecycleService(Logger logger, Path projectRoot) {
        this.logger = logger;
        this.scriptPath = projectRoot.resolve("scripts").resolve("windows").resolve("recycle-backend.ps1");
        // Outside any backend's world/ directory on purpose (spec section 50) - these logs must
        // survive the very world-delete the recycle they describe just performed.
        this.recycleLogDir = projectRoot.resolve("logs").resolve("recycle");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hardcore-coordinator-recycle");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<RecycleResult> recycleAsync(String backendKey, int oldGeneration) {
        String letter = BACKEND_KEY_TO_LETTER.get(backendKey);
        if (letter == null) {
            return CompletableFuture.completedFuture(RecycleResult.failed("UNKNOWN_BACKEND_KEY"));
        }
        String operationId = UUID.randomUUID().toString();
        logger.info("[BackendRecycle] RECYCLE_BEGIN operationId={} backend={} oldGeneration={}",
                operationId, backendKey, oldGeneration);
        return CompletableFuture.supplyAsync(() -> runBlocking(letter, operationId), executor);
    }

    private RecycleResult runBlocking(String backendLetter, String operationId) {
        Path logFile = recycleLogDir.resolve(operationId + ".log");
        try {
            Files.createDirectories(recycleLogDir);

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", scriptPath.toString(), "-Backend", backendLetter);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            Process proc = pb.start();

            // Tail the log file while waiting, so Velocity's own log still shows near-live
            // progress - this is best-effort visibility only; correctness does not depend on it.
            int linesLoggedSoFar = 0;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(180);
            boolean exited = false;
            while (System.currentTimeMillis() < deadline) {
                exited = proc.waitFor(1, TimeUnit.SECONDS);
                linesLoggedSoFar = tailNewLines(logFile, linesLoggedSoFar, operationId);
                if (exited) {
                    break;
                }
            }
            // Final read in case the process wrote its last line right before exiting.
            tailNewLines(logFile, linesLoggedSoFar, operationId);

            if (!exited) {
                proc.destroyForcibly();
                logger.error("[BackendRecycle] RECYCLE_PROCESS_TIMEOUT operationId={}", operationId);
                return RecycleResult.failed("PROCESS_TIMEOUT");
            }

            List<String> allLines = Files.exists(logFile) ? Files.readAllLines(logFile) : List.of();
            String lastResultLine = allLines.stream()
                    .filter(l -> l.startsWith("RECYCLE_RESULT"))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (lastResultLine == null) {
                logger.error("[BackendRecycle] RECYCLE_NO_RESULT_LINE operationId={} exitCode={}",
                        operationId, proc.exitValue());
                return RecycleResult.failed("NO_RESULT_LINE");
            }
            return RecycleResult.parse(lastResultLine);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("[BackendRecycle] RECYCLE_PROCESS_ERROR operationId={}", operationId, e);
            return RecycleResult.failed("PROCESS_ERROR_" + e.getClass().getSimpleName());
        }
    }

    /** Logs any lines appended to the file since {@code fromLine}, returns the new total line count. */
    private int tailNewLines(Path logFile, int fromLine, String operationId) {
        if (!Files.exists(logFile)) {
            return fromLine;
        }
        try {
            List<String> lines = Files.readAllLines(logFile);
            for (int i = fromLine; i < lines.size(); i++) {
                logger.info("[BackendRecycle] operationId={} {}", operationId, lines.get(i));
            }
            return lines.size();
        } catch (IOException e) {
            // Transient (e.g. file being written to) - not fatal, just skip this tail attempt.
            return fromLine;
        }
    }
}
