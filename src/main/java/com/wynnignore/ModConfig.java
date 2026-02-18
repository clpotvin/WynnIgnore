package com.wynnignore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("wynnignore_config.json");

    private static volatile ModConfig instance;
    private static final Object LOCK = new Object();

    // Configuration values
    private int warIgnoreDurationMinutes = 5;
    private double warIgnoreDistance = 10.0;
    private int commandDelayMs = 500;
    private int commandMaxRetries = 3;

    public static ModConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    public int getWarIgnoreDurationMinutes() {
        return warIgnoreDurationMinutes;
    }

    public void setWarIgnoreDurationMinutes(int minutes) {
        this.warIgnoreDurationMinutes = Math.max(1, Math.min(60, minutes));
        save();
    }

    public long getWarIgnoreDurationMs() {
        return warIgnoreDurationMinutes * 60L * 1000L;
    }

    public double getWarIgnoreDistance() {
        return warIgnoreDistance;
    }

    public void setWarIgnoreDistance(double distance) {
        this.warIgnoreDistance = Math.max(1.0, Math.min(50.0, distance));
        save();
    }

    public int getCommandDelayMs() {
        return commandDelayMs;
    }

    public void setCommandDelayMs(int ms) {
        this.commandDelayMs = Math.max(100, Math.min(1000, ms));
        save();
    }

    public int getCommandMaxRetries() {
        return commandMaxRetries;
    }

    public void setCommandMaxRetries(int retries) {
        this.commandMaxRetries = Math.max(0, Math.min(5, retries));
        save();
    }

    public void save() {
        synchronized (LOCK) {
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            } catch (IOException e) {
                WynnIgnoreMod.LOGGER.error("Failed to save config", e);
            }
        }
    }

    private static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    // Validate loaded values
                    config.warIgnoreDurationMinutes = Math.max(1, Math.min(60, config.warIgnoreDurationMinutes));
                    config.warIgnoreDistance = Math.max(1.0, Math.min(50.0, config.warIgnoreDistance));
                    config.commandDelayMs = Math.max(100, Math.min(1000, config.commandDelayMs));
                    config.commandMaxRetries = Math.max(0, Math.min(5, config.commandMaxRetries));
                    return config;
                }
            } catch (Exception e) {
                WynnIgnoreMod.LOGGER.error("Failed to load config, using defaults", e);
            }
        }
        return new ModConfig();
    }
}
