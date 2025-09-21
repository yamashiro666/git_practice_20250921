package com.practice.client.tracking.infrastructure.persistence.token;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public class FileTokenStore implements TokenStore {
    private final Path path;
    public FileTokenStore(Path path) { this.path = path; }
    public Optional<String> load() {
        try { return Files.exists(path) ? Optional.of(Files.readString(path)) : Optional.empty(); }
        catch (Exception e) { return Optional.empty(); }
    }
    public void save(String token) {
        try { Files.writeString(path, token, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (Exception ignore) {}
    }
}
