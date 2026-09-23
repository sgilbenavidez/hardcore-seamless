package com.hardcoreseamless.deathsignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Writes one death-event JSON file per real death, atomically (write to {@code .tmp}, then
 * {@code ATOMIC_MOVE} to the final {@code .json} name) so the consumer (HardcoreCoordinator) never
 * sees a partially-written file - it only ever looks at {@code *.json} inside {@code inbox/}, per
 * FASE 7 spec section 9.
 */
public final class DeathEventWriter {

    private final Path inboxDir;

    public DeathEventWriter(Path spoolRoot) throws IOException {
        this.inboxDir = spoolRoot.resolve("inbox");
        Files.createDirectories(inboxDir);
        Files.createDirectories(spoolRoot.resolve("processed"));
        Files.createDirectories(spoolRoot.resolve("invalid"));
    }

    public String write(DeathEvent event) throws IOException {
        String eventId = UUID.randomUUID().toString();
        String json = event.toJson(eventId);

        Path tmp = inboxDir.resolve(eventId + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        Path finalPath = inboxDir.resolve(eventId + ".json");
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE);

        return eventId;
    }
}
