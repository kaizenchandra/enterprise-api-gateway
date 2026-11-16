package io.acme.gateway.data;

import io.acme.gateway.policy.Snapshot;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Used only on the single bounded configuration worker, never the proxy event loop.
 */
final class SnapshotCache {
    private final Path file;
    private final ObjectMapper json;

    SnapshotCache(String path, ObjectMapper json) {
        this.file = path.isBlank() ? null : Path.of(path);
        this.json = json;
    }

    Entry read() throws IOException {
        if (file == null || !Files.exists(file)) return null;
        return json.readValue(Files.readAllBytes(file), Entry.class);
    }

    void write(Entry entry) throws IOException {
        if (file == null) return;
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "snapshot-", ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(entry));
            try {
                Files.setPosixFilePermissions(
                        temporary,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Entry(Snapshot snapshot, Instant verifiedAt) {
    }
}
