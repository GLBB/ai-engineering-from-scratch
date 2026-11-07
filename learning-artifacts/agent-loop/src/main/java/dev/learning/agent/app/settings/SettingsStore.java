package dev.learning.agent.app.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores the selected protocol; changes take effect when the process starts again. */
@Service
public final class SettingsStore {
    private final ObjectMapper json;
    private final Path file;
    private final ApiProtocol activeProtocol;

    @Autowired
    SettingsStore(ObjectMapper json) throws IOException {
        this(json, projectRoot().resolve("config/settings.json"));
    }

    SettingsStore(ObjectMapper json, Path file) throws IOException {
        this.json = json;
        this.file = file;
        this.activeProtocol = read();
    }

    public ApiProtocol activeProtocol() {
        return activeProtocol;
    }

    synchronized SettingsStatus status() throws IOException {
        ApiProtocol saved = read();
        return new SettingsStatus(activeProtocol, saved, saved != activeProtocol);
    }

    synchronized SettingsStatus save(ApiProtocol protocol) throws IOException {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol is required");
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new Settings(protocol));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new SettingsStatus(activeProtocol, protocol, protocol != activeProtocol);
    }

    private ApiProtocol read() throws IOException {
        if (!Files.exists(file)) {
            return ApiProtocol.CHAT_COMPLETIONS;
        }
        Settings settings = json.readValue(file.toFile(), Settings.class);
        if (settings.protocol() == null) {
            throw new IOException("settings.json must contain a valid protocol");
        }
        return settings.protocol();
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("learning-artifacts/agent-loop");
        return Files.isRegularFile(module.resolve("pom.xml")) ? module : current;
    }

    record Settings(ApiProtocol protocol) {
    }

    record SettingsStatus(ApiProtocol activeProtocol, ApiProtocol savedProtocol, boolean restartRequired) {
    }
}
