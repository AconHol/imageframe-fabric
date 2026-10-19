package com.imageframe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON configuration. Mirrors the useful options of the original ImageFrame config.yml.
 * The file lives at {@code config/imageframe/config.json}.
 */
public final class Config {

    public String language = "zh_cn";
    public String dateFormat = "yyyy-MM-dd HH:mm:ss";
    public String mapItemFormat = "&f{Name} &7({X}, {Y})";
    public boolean requireEmptyMaps = true;
    public int maxSize = 100;
    public boolean restrictImageUrl = false;
    public List<String> urlWhitelist = new ArrayList<>(List.of(
            "https://i.imgur.com", "http://i.imgur.com",
            "https://storage.googleapis.com", "http://storage.googleapis.com",
            "https://cdn.discordapp.com", "http://cdn.discordapp.com",
            "https://media.discordapp.net", "http://media.discordapp.net",
            "https://textures.minecraft.net", "http://textures.minecraft.net"));
    public long maxImageFileSize = 50L * 1024L * 1024L;
    public int maxProcessingTime = 60;
    public int parallelProcessingLimit = 1;
    public Map<String, Integer> playerCreationLimit = new LinkedHashMap<>(Map.of("default", 10));
    public int mapMarkerLimit = 20;
    public boolean combinedByDefault = false;
    public boolean handleAnimatedMapsOnMainThread = true;
    public int maxAnimationFrames = 600;
    public boolean viewAnimatedMaps = true;
    public boolean debugLogging = false;
    public InvisibleFrame invisibleFrame = new InvisibleFrame();
    public boolean checkForUpdates = false;
    public String updateCheckUrl = "";
    public UploadService uploadService = new UploadService();
    public Map<String, String> permissions = Perms.defaults();

    public static final class UploadService {
        public boolean enabled = false;
        public String host = "0.0.0.0";
        public int port = 8163;
        public String publicUrl = "";
    }

    public static final class InvisibleFrame {
        public int maxConversionsPerSplash = 8;
        public boolean glowEmptyFrames = true;
    }

    private transient Path file;

    public static Config load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("imageframe");
        Path file = dir.resolve("config.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Config config = null;
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    config = gson.fromJson(reader, Config.class);
                }
            }
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.error("Failed to read config, using defaults", e);
        }
        if (config == null) {
            config = new Config();
        }
        config.sanitize();
        config.file = file;
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.warn("Failed to save config", e);
        }
        return config;
    }

    private void sanitize() {
        if (language == null) language = "zh_cn";
        language = language.toLowerCase(Locale.ROOT);
        if (maxSize < 1) maxSize = 1;
        if (maxSize > 2500) maxSize = 2500;
        if (maxImageFileSize < 1024) maxImageFileSize = 1024;
        if (maxProcessingTime < 1) maxProcessingTime = 1;
        if (maxAnimationFrames < 1) maxAnimationFrames = 1;
        if (maxAnimationFrames > 2000) maxAnimationFrames = 2000;
        if (parallelProcessingLimit < 1) parallelProcessingLimit = 1;
        if (playerCreationLimit == null) playerCreationLimit = new LinkedHashMap<>();
        if (playerCreationLimit.isEmpty()) playerCreationLimit.put("default", 10);
        if (permissions == null) permissions = Perms.defaults();
        if (invisibleFrame == null) invisibleFrame = new InvisibleFrame();
        if (urlWhitelist == null) urlWhitelist = new ArrayList<>();
        if (updateCheckUrl == null) updateCheckUrl = "";
        if (uploadService == null) uploadService = new UploadService();
        if (uploadService.host == null) uploadService.host = "0.0.0.0";
        if (uploadService.port < 1 || uploadService.port > 65535) uploadService.port = 8163;
        if (uploadService.publicUrl == null) uploadService.publicUrl = "";
    }

    public void save() {
        if (file == null) {
            return;
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.warn("Failed to save config", e);
        }
    }

    public int creationLimit(boolean adminBypass) {
        if (adminBypass) {
            return -1;
        }
        Integer limit = playerCreationLimit.get("default");
        return limit == null ? 10 : limit;
    }

    public boolean isUrlAllowed(String url) {
        if (!restrictImageUrl) {
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String prefix : urlWhitelist) {
            if (prefix != null && !prefix.isBlank() && lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unused")
    private static final TypeToken<Map<String, Integer>> INT_MAP = new TypeToken<>() {
    };
}
