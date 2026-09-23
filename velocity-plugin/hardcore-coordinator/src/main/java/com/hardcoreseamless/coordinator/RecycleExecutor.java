package com.hardcoreseamless.coordinator;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over "recycle this backend" so SwitchService's tests never spawn a real
 * powershell.exe process against the real project scripts (which would try to actually stop and
 * delete a live backend's world). {@link RecycleService} is the real implementation; tests
 * substitute a fake.
 */
public interface RecycleExecutor {
    CompletableFuture<RecycleResult> recycleAsync(String backendKey, int oldGeneration);
}
