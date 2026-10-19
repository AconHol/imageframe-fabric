package com.imageframe.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per player preferences, mirroring the original plugin's IFPlayerPreference system.
 * Supported preferences: {@code viewAnimatedMaps} (boolean) and {@code language}.
 * A {@code null} value means "use the server default".
 */
public final class PlayerPreferences {

    public static final class Data {
        public Boolean viewAnimatedMaps;
        public String language;

        public Data copy() {
            Data data = new Data();
            data.viewAnimatedMaps = viewAnimatedMaps;
            data.language = language;
            return data;
        }
    }

    public record PlayerTarget(UUID uuid, String name) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ImageFrameMod mod;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();

    public PlayerPreferences(ImageFrameMod mod) {
        this.mod = mod;
    }

    private Path dir() {
        return mod.storage().root().resolve("players");
    }

    private Path file(UUID uuid) {
        return dir().resolve(uuid + ".json");
    }

    public Data get(UUID uuid) {
        return cache.computeIfAbsent(uuid, key -> load(key));
    }

    public void load(ServerPlayer player) {
        get(player.getUUID());
    }

    private Data load(UUID uuid) {
        Path path = file(uuid);
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Data data = GSON.fromJson(reader, Data.class);
                if (data != null) {
                    return data;
                }
            } catch (Exception e) {
                ImageFrameFabric.LOGGER.warn("Unable to read preferences of {}", uuid, e);
            }
        }
        return new Data();
    }

    public void save(ServerPlayer player) {
        Data data = cache.get(player.getUUID());
        if (data != null) {
            save(player.getUUID(), data);
        }
    }

    public void save(UUID uuid, Data data) {
        try {
            Files.createDirectories(dir());
            try (Writer writer = Files.newBufferedWriter(file(uuid), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.warn("Unable to save preferences of {}", uuid, e);
        }
    }

    /** @return the effective animated map setting for a player. */
    public boolean viewAnimatedMaps(ServerPlayer player) {
        Boolean value = get(player.getUUID()).viewAnimatedMaps;
        return value == null ? mod.config().viewAnimatedMaps : value;
    }

    /** @return the preferred language code, or {@code null} to use the server default. */
    public String language(ServerPlayer player) {
        return get(player.getUUID()).language;
    }

    /** @return the effective language code of a player. */
    public String effectiveLanguage(ServerPlayer player) {
        String language = language(player);
        return language == null ? mod.config().language : language;
    }

    public void setViewAnimatedMaps(UUID uuid, Boolean value) {
        Data data = get(uuid);
        data.viewAnimatedMaps = value;
        save(uuid, data);
    }

    public void setLanguage(UUID uuid, String value) {
        Data data = get(uuid);
        data.language = value;
        save(uuid, data);
    }

    /** Resolve a target player by name, accepting online players only on a Fabric server. */
    public PlayerTarget resolveTarget(ServerPlayer requester, String name) {
        if (name == null || name.isBlank()) {
            return new PlayerTarget(requester.getUUID(), requester.getScoreboardName());
        }
        ServerPlayer online = mod.server().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new PlayerTarget(online.getUUID(), online.getScoreboardName());
        }
        // Fall back to the vanilla usercache.json so offline players can be configured too.
        Path cacheFile = Path.of("usercache.json");
        if (Files.isRegularFile(cacheFile)) {
            try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                var entries = GSON.fromJson(reader, com.google.gson.JsonArray.class);
                if (entries != null) {
                    for (var element : entries) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        var object = element.getAsJsonObject();
                        if (object.has("name") && name.equalsIgnoreCase(object.get("name").getAsString())
                                && object.has("uuid")) {
                            return new PlayerTarget(UUID.fromString(object.get("uuid").getAsString()),
                                    object.get("name").getAsString());
                        }
                    }
                }
            } catch (Exception e) {
                ImageFrameFabric.LOGGER.warn("Unable to read usercache.json", e);
            }
        }
        return null;
    }
}
