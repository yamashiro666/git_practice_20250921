package com.practice.client.tracking.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AppConfig {
    public final long samplingWindowMillis;

    private AppConfig(long samplingWindowMillis) {
        this.samplingWindowMillis = samplingWindowMillis;
    }

    public static AppConfig load(Path propertiesPath) {
        Properties p = new Properties();
        try (var in = Files.newInputStream(propertiesPath)) {
            p.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties: " + propertiesPath, e);
        }
        long ms = Long.parseLong(p.getProperty("sampling.window.millis", "30000"));
        return new AppConfig(ms);
    }
}