package com.wynnignore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class IgnoreListManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_DATA_VERSION = 2;
    private final Path configDir;
    private Path configPath;

    private final Set<String> ignoredPlayers = new HashSet<>();
    // Maps player name to expiry timestamp (when they should be auto-unignored)
    private final Map<String, Long> timedIgnores = new HashMap<>();
    // Track players currently being processed to avoid duplicate queue entries
    private final Set<String> pendingUnignores = new HashSet<>();

    public IgnoreListManager() {
        this.configDir = FabricLoader.getInstance().getConfigDir();
        this.configPath = configDir.resolve("wynnignore.json");
    }

    /**
     * Switches the ignore list to a different server's data file.
     * Saves current data, clears state, and loads the new server's data.
     */
    public void setServer(String serverType) {
        save();

        if ("beta".equals(serverType)) {
            this.configPath = configDir.resolve("wynnignore_beta.json");
        } else {
            this.configPath = configDir.resolve("wynnignore.json");
        }

        ignoredPlayers.clear();
        timedIgnores.clear();
        pendingUnignores.clear();
        load();

        WynnIgnoreMod.LOGGER.info("Switched to {} server ignore list", serverType);
    }

    public void addPlayer(String name) {
        String lowerName = name.toLowerCase();
        if (ignoredPlayers.add(lowerName)) {
            save();
            WynnIgnoreMod.LOGGER.info("Added {} to ignore list", name);
        }
    }

    public void removePlayer(String name) {
        String lowerName = name.toLowerCase();
        boolean removed = ignoredPlayers.remove(lowerName);
        timedIgnores.remove(lowerName);
        pendingUnignores.remove(lowerName);
        if (removed) {
            save();
            WynnIgnoreMod.LOGGER.info("Removed {} from ignore list", name);
        }
    }

    public Set<String> getIgnoredPlayers() {
        return Collections.unmodifiableSet(new HashSet<>(ignoredPlayers));
    }

    public boolean isIgnored(String name) {
        return ignoredPlayers.contains(name.toLowerCase());
    }

    /**
     * Adds a timed ignore with the specified duration in minutes.
     */
    public void addTimedIgnore(String name, int durationMinutes) {
        String lowerName = name.toLowerCase();
        ignoredPlayers.add(lowerName);
        long expiryTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L);
        timedIgnores.put(lowerName, expiryTime);
        save();
        WynnIgnoreMod.LOGGER.info("Timed-ignored {} (will auto-unignore in {} minutes)", name, durationMinutes);
    }

    /**
     * Adds a war ignore using the configured duration.
     */
    public void addWarIgnore(String name) {
        addTimedIgnore(name, ModConfig.getInstance().getWarIgnoreDurationMinutes());
    }

    /**
     * Checks for expired timed ignores and queues them for removal.
     * Called on tick and on world join.
     */
    public void checkTimedIgnoreExpiry() {
        if (timedIgnores.isEmpty()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        List<String> toUnignore = new ArrayList<>();

        for (Map.Entry<String, Long> entry : timedIgnores.entrySet()) {
            String playerName = entry.getKey();
            // Check if expired and not already pending
            if (currentTime >= entry.getValue() && !pendingUnignores.contains(playerName)) {
                toUnignore.add(playerName);
                pendingUnignores.add(playerName);
            }
        }

        if (!toUnignore.isEmpty()) {
            CommandHandler.queueTimedUnignores(toUnignore);
        }
    }

    /**
     * Called when a timed unignore has been confirmed by the server.
     */
    public void onTimedUnignoreComplete(String name) {
        String lowerName = name.toLowerCase();
        timedIgnores.remove(lowerName);
        ignoredPlayers.remove(lowerName);
        pendingUnignores.remove(lowerName);
        save();
    }

    /**
     * Returns true if the player has a timed ignore.
     */
    public boolean isTimedIgnore(String name) {
        return timedIgnores.containsKey(name.toLowerCase());
    }

    public int getTimedIgnoreCount() {
        return timedIgnores.size();
    }

    /**
     * Gets the remaining time in minutes for a timed ignore, or -1 if not timed.
     */
    public long getRemainingMinutes(String name) {
        Long expiry = timedIgnores.get(name.toLowerCase());
        if (expiry == null) {
            return -1;
        }
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            return 0;
        }
        return remaining / (60 * 1000);
    }

    public void save() {
        try {
            SaveData data = new SaveData();
            data.version = CURRENT_DATA_VERSION;
            data.ignoredPlayers = new ArrayList<>(ignoredPlayers);
            data.timedIgnores = new HashMap<>(timedIgnores);

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            WynnIgnoreMod.LOGGER.error("Failed to save ignore list", e);
        }
    }

    public void load() {
        if (!Files.exists(configPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            SaveData data = GSON.fromJson(reader, SaveData.class);
            if (data != null) {
                if (data.ignoredPlayers != null) {
                    ignoredPlayers.clear();
                    for (String name : data.ignoredPlayers) {
                        ignoredPlayers.add(name.toLowerCase());
                    }
                }

                // Handle data based on version
                if (data.version >= 2) {
                    // Current format: timedIgnores contains expiry timestamps
                    if (data.timedIgnores != null) {
                        timedIgnores.clear();
                        timedIgnores.putAll(data.timedIgnores);
                    }
                } else if (data.warIgnoredPlayers != null && !data.warIgnoredPlayers.isEmpty()) {
                    // Version 1 or unversioned: warIgnoredPlayers contains start timestamps
                    // Migrate to new format
                    timedIgnores.clear();
                    long durationMs = ModConfig.getInstance().getWarIgnoreDurationMs();
                    for (Map.Entry<String, Long> entry : data.warIgnoredPlayers.entrySet()) {
                        long expiryTime = entry.getValue() + durationMs;
                        timedIgnores.put(entry.getKey(), expiryTime);
                    }
                    WynnIgnoreMod.LOGGER.info("Migrated {} war-ignored players to new timed ignore format", timedIgnores.size());
                    // Save immediately to persist migration
                    save();
                }
            }
            WynnIgnoreMod.LOGGER.info("Loaded {} ignored players ({} timed)",
                ignoredPlayers.size(), timedIgnores.size());
        } catch (Exception e) {
            WynnIgnoreMod.LOGGER.error("Failed to load ignore list", e);
        }
    }

    private static class SaveData {
        int version = 1;
        List<String> ignoredPlayers;
        Map<String, Long> timedIgnores;
        // Old format for migration (version 1)
        Map<String, Long> warIgnoredPlayers;
    }
}
