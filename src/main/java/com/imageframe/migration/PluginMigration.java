package com.imageframe.migration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import com.imageframe.image.ImageMap;
import com.imageframe.image.MediaLoader;
import com.imageframe.util.Lang;
import com.imageframe.util.Palette;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Imports data from the original ImageFrame plugin (file storage backend).
 *
 * <p>Expected layout: {@code <path>/data/<index>/data.json} plus cached {@code <tile>.png}
 * images, and optionally {@code <path>/players/<uuid>.json} for preferences.</p>
 */
public final class PluginMigration {

    public record Result(int imported, int skipped, int preferences, List<String> errors) {
    }

    private record Prepared(int index, String type, String name, String url, int width, int height,
                            Palette.Dithering dithering, UUID creator, long creationTime,
                            List<Integer> mapIds, List<MarkerData> markers, List<AccessData> access,
                            MediaLoader.Media media) {
    }

    private record MarkerData(int tile, String name, byte x, byte y, byte rotation, String type, String caption) {
    }

    private record AccessData(UUID uuid, String mode) {
    }

    private record PreferenceData(UUID uuid, Boolean viewAnimatedMaps, String language) {
    }

    private static final Gson GSON = new Gson();

    private final ImageFrameMod mod;

    public PluginMigration(ImageFrameMod mod) {
        this.mod = mod;
    }

    public void migrateAsync(CommandSourceStack source, ServerPlayer player, String rawPath,
                             java.util.function.Consumer<Result> callback) {
        mod.manager().executor().submit(() -> {
            Result result;
            try {
                result = migrate(rawPath);
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                ImageFrameFabric.LOGGER.warn("ImageFrame migration failed", throwable);
                mod.server().execute(() -> {
                    source.sendFailure(Lang.t(player, "migration_failed", message));
                    callback.accept(new Result(0, 0, 0, List.of(message)));
                });
                return;
            }
            Result finalResult = result;
            mod.server().execute(() -> {
                source.sendSuccess(() -> Lang.t(player, "migration_done", finalResult.imported()), false);
                for (String error : finalResult.errors()) {
                    ImageFrameFabric.LOGGER.warn("Migration: {}", error);
                }
                callback.accept(finalResult);
            });
        });
    }

    public Result migrate(String rawPath) throws Exception {
        Path path = Path.of(rawPath);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }
        Path dataDir = Files.isDirectory(path.resolve("data")) ? path.resolve("data") : path;
        List<Prepared> prepared = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dataDir)) {
            stream.filter(Files::isDirectory).forEach(directories::add);
        }
        directories.sort(Comparator.comparingInt(PluginMigration::numericName));
        for (Path directory : directories) {
            Path jsonFile = directory.resolve("data.json");
            if (!Files.isRegularFile(jsonFile)) {
                continue;
            }
            try {
                Prepared entry = readPrepared(directory, jsonFile);
                if (entry == null) {
                    skipped++;
                } else {
                    prepared.add(entry);
                }
            } catch (Throwable throwable) {
                errors.add(directory.getFileName() + ": " + throwable.getMessage());
                skipped++;
            }
        }
        List<PreferenceData> preferences = readPlayerPreferences(path);
        int imported = 0;
        for (Prepared entry : prepared) {
            try {
                finish(entry);
                imported++;
            } catch (Throwable throwable) {
                errors.add(entry.name() + ": " + throwable.getMessage());
                skipped++;
            }
        }
        for (PreferenceData preference : preferences) {
            if (preference.viewAnimatedMaps() != null) {
                mod.preferences().setViewAnimatedMaps(preference.uuid(), preference.viewAnimatedMaps());
            }
            if (preference.language() != null && Lang.isLanguageSupported(preference.language())) {
                mod.preferences().setLanguage(preference.uuid(), preference.language());
            }
        }
        mod.storage().saveState();
        ImageFrameFabric.LOGGER.info("Migrated {} image map(s), skipped {}, preferences {}",
                imported, skipped, preferences.size());
        return new Result(imported, skipped, preferences.size(), errors);
    }

    private static int numericName(Path path) {
        try {
            return Integer.parseInt(path.getFileName().toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private Prepared readPrepared(Path directory, Path jsonFile) throws Exception {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            json = GSON.fromJson(reader, JsonObject.class);
        }
        if (json == null) {
            return null;
        }
        String name = json.has("name") ? json.get("name").getAsString() : "Unnamed";
        String url = json.has("url") ? json.get("url").getAsString() : "";
        String type = json.has("type") ? json.get("type").getAsString() : "";
        int width = json.has("width") ? json.get("width").getAsInt() : 1;
        int height = json.has("height") ? json.get("height").getAsInt() : 1;
        width = Math.max(1, width);
        height = Math.max(1, height);
        int tiles = width * height;
        Palette.Dithering dithering = Palette.Dithering.byName(
                json.has("ditheringType") ? json.get("ditheringType").getAsString() : null);
        UUID creator;
        try {
            creator = UUID.fromString(json.has("creator") ? json.get("creator").getAsString()
                    : new UUID(0, 0).toString());
        } catch (Exception e) {
            creator = new UUID(0, 0);
        }
        long creationTime = json.has("creationTime") ? json.get("creationTime").getAsLong()
                : System.currentTimeMillis();
        List<Integer> mapIds = new ArrayList<>();
        List<MarkerData> markers = new ArrayList<>();
        if (json.has("mapdata") && json.get("mapdata").isJsonArray()) {
            JsonArray mapData = json.getAsJsonArray("mapdata");
            for (int tile = 0; tile < mapData.size(); tile++) {
                JsonElement element = mapData.get(tile);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject tileJson = element.getAsJsonObject();
                if (tileJson.has("mapid")) {
                    mapIds.add(tileJson.get("mapid").getAsInt());
                }
                if (tileJson.has("markers") && tileJson.get("markers").isJsonArray()) {
                    for (JsonElement markerElement : tileJson.getAsJsonArray("markers")) {
                        if (!markerElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject markerJson = markerElement.getAsJsonObject();
                        String markerName = markerJson.has("name") ? markerJson.get("name").getAsString() : "";
                        if (markerName.isEmpty()) {
                            continue;
                        }
                        markers.add(new MarkerData(tile, markerName,
                                (byte) (markerJson.has("x") ? markerJson.get("x").getAsInt() : 0),
                                (byte) (markerJson.has("y") ? markerJson.get("y").getAsInt() : 0),
                                (byte) (markerJson.has("direction") ? markerJson.get("direction").getAsInt() : 0),
                                markerJson.has("type") ? markerJson.get("type").getAsString().toLowerCase() : "red_marker",
                                markerJson.has("caption") ? markerJson.get("caption").getAsString() : ""));
                    }
                }
            }
        }
        if (mapIds.size() != tiles) {
            mapIds.clear();
        }
        List<AccessData> access = new ArrayList<>();
        if (json.has("hasAccess") && json.get("hasAccess").isJsonObject()) {
            JsonObject accessJson = json.getAsJsonObject("hasAccess");
            for (String key : accessJson.keySet()) {
                try {
                    String mode = accessJson.get(key).getAsString().toLowerCase();
                    access.add(new AccessData(UUID.fromString(key), "edit".equals(mode) ? "edit" : "view"));
                } catch (Exception ignored) {
                }
            }
        }
        MediaLoader.Media media = loadMedia(directory, url, width, height, tiles);
        if (media == null) {
            return null;
        }
        return new Prepared(0, type, name, url, width, height, dithering, creator, creationTime, mapIds,
                markers, access, media);
    }

    private MediaLoader.Media loadMedia(Path directory, String url, int width, int height, int tiles) {
        if (url != null && !url.isBlank()) {
            try {
                return MediaLoader.load(url, mod.config());
            } catch (Throwable throwable) {
                ImageFrameFabric.LOGGER.info("Migration: unable to download {}, trying cached tiles", url);
            }
        }
        try {
            List<BufferedImage> images = new ArrayList<>();
            for (int tile = 0; tile < tiles; tile++) {
                Path file = directory.resolve(tile + ".png");
                if (!Files.isRegularFile(file)) {
                    images.clear();
                    break;
                }
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) {
                    images.clear();
                    break;
                }
                images.add(image);
            }
            if (images.isEmpty()) {
                return null;
            }
            BufferedImage combined;
            if (images.size() == 1) {
                combined = images.get(0);
            } else {
                int tileWidth = images.get(0).getWidth();
                int tileHeight = images.get(0).getHeight();
                combined = new BufferedImage(tileWidth * width, tileHeight * height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = combined.createGraphics();
                for (int tile = 0; tile < images.size(); tile++) {
                    graphics.drawImage(images.get(tile), (tile % width) * tileWidth, (tile / width) * tileHeight, null);
                }
                graphics.dispose();
            }
            return new MediaLoader.Media(List.of(combined), List.of(1000), false);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private void finish(Prepared prepared) {
        var manager = mod.manager();
        int tiles = prepared.width() * prepared.height();
        List<Integer> mapIds = prepared.mapIds();
        if (mapIds.size() != tiles) {
            mapIds = new ArrayList<>(tiles);
            for (int tile = 0; tile < tiles; tile++) {
                mapIds.add(mod.server().overworld().getFreeMapId().id());
            }
        }
        boolean overlay = prepared.type() != null && prepared.type().toLowerCase().contains("overlay");
        int index = manager.nextImageIndex();
        ImageMap image;
        if (overlay) {
            image = ImageMap.buildOverlay(manager, index, prepared.name(), prepared.url(), prepared.width(),
                    prepared.height(), prepared.dithering(), prepared.creator(), prepared.creator().toString(),
                    prepared.creationTime(), prepared.media(), mapIds);
        } else {
            image = ImageMap.build(manager, index, prepared.name(), prepared.url(), prepared.width(),
                    prepared.height(), prepared.dithering(), prepared.creator(), prepared.creator().toString(),
                    prepared.creationTime(), prepared.media(), mapIds);
        }
        manager.bumpImageIndex();
        for (MarkerData marker : prepared.markers()) {
            if (marker.tile() < 0 || marker.tile() >= tiles) {
                continue;
            }
            image.markers().get(marker.tile()).put(marker.name(), new ImageMap.Marker(marker.name(), marker.x(),
                    marker.y(), marker.rotation(), marker.type(), marker.caption()));
        }
        for (AccessData access : prepared.access()) {
            image.setAccess(access.uuid(), access.mode());
        }
        manager.register(image);
        mod.storage().saveImage(image);
        if (overlay) {
            for (net.minecraft.server.level.ServerPlayer online : mod.server().getPlayerList().getPlayers()) {
                mod.sender().sendOverlay(online, image);
            }
        }
    }

    private List<PreferenceData> readPlayerPreferences(Path root) {
        Path playersDir = root.resolve("players");
        if (!Files.isDirectory(playersDir)) {
            return List.of();
        }
        List<PreferenceData> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(playersDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    if (json == null || !json.has("uuid")) {
                        continue;
                    }
                    UUID uuid = UUID.fromString(json.get("uuid").getAsString());
                    Boolean view = null;
                    String language = null;
                    if (json.has("preferences") && json.get("preferences").isJsonObject()) {
                        JsonObject preferences = json.getAsJsonObject("preferences");
                        JsonElement viewElement = preferences.get("viewAnimatedMaps");
                        if (viewElement != null && viewElement.isJsonPrimitive()) {
                            try {
                                view = viewElement.getAsBoolean();
                            } catch (Exception ignored) {
                                String value = viewElement.getAsString();
                                if ("true".equalsIgnoreCase(value)) {
                                    view = true;
                                } else if ("false".equalsIgnoreCase(value)) {
                                    view = false;
                                }
                            }
                        }
                        JsonElement languageElement = preferences.get("language");
                        if (languageElement != null && languageElement.isJsonPrimitive()) {
                            String value = languageElement.getAsString();
                            if (Lang.isLanguageSupported(value)) {
                                language = value;
                            }
                        }
                    }
                    result.add(new PreferenceData(uuid, view, language));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.warn("Unable to list plugin player data", e);
        }
        return result;
    }
}
