package com.imageframe.image;

import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import com.imageframe.util.Frames;
import com.imageframe.util.ItemsX;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds every known image map and owns the asynchronous image processing pipeline.
 */
public final class ImageMapManager {

    private static final int FAKE_MAP_ID_START = 1_600_000_000;

    private final ImageFrameMod mod;
    private final MarkerTypes markerTypes = new MarkerTypes();
    private final List<ImageMap> images = new ArrayList<>();
    private final Map<String, ImageMap> byName = new LinkedHashMap<>();
    private final AtomicLong tickCounter = new AtomicLong();
    private final ExecutorService executor;
    private int nextImageIndex = 1;
    private int fakeIdCounter = FAKE_MAP_ID_START;

    public ImageMapManager(ImageFrameMod mod) {
        this.mod = mod;
        this.executor = Executors.newFixedThreadPool(Math.max(1, mod.config().parallelProcessingLimit), runnable -> {
            Thread thread = new Thread(runnable, "ImageFrame-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ImageFrameMod mod() {
        return mod;
    }

    public FrameTracker frames() {
        return mod.frames();
    }

    public com.imageframe.storage.Storage storage() {
        return mod.storage();
    }

    public MapSender sender() {
        return mod.sender();
    }

    public ExecutorService executor() {
        return executor;
    }

    public MarkerTypes markerTypes() {
        return markerTypes;
    }

    public long tickCounter() {
        return tickCounter.get();
    }

    public void tick() {
        tickCounter.incrementAndGet();
    }

    public int nextFakeMapId() {
        return fakeIdCounter++;
    }

    public int nextImageIndex() {
        return nextImageIndex;
    }

    public void setCounters(int nextImageIndex, int fakeIdCounter) {
        this.nextImageIndex = Math.max(1, nextImageIndex);
        this.fakeIdCounter = Math.max(FAKE_MAP_ID_START, fakeIdCounter);
    }

    public int fakeIdCounter() {
        return fakeIdCounter;
    }

    public void bumpImageIndex() {
        nextImageIndex++;
    }

    public List<ImageMap> all() {
        return images;
    }

    public ImageMap byIndex(int index) {
        for (ImageMap image : images) {
            if (image.index() == index) {
                return image;
            }
        }
        return null;
    }

    public ImageMap byName(String name) {
        if (name == null) {
            return null;
        }
        return byName.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Resolves an image by name, preferring the given player's own images. */
    public ImageMap resolve(String name, UUID player) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);
        ImageMap direct = byName.get(key);
        if (direct != null) {
            return direct;
        }
        try {
            int index = Integer.parseInt(name);
            return byIndex(index);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public ImageMap byBaseMapId(int mapId) {
        for (ImageMap image : images) {
            if (image.valid() && image.tileForMapId(mapId) >= 0) {
                return image;
            }
        }
        return null;
    }

    public ImageMap byAnyMapId(int mapId) {
        for (ImageMap image : images) {
            if (!image.valid()) {
                continue;
            }
            if (image.tileForMapId(mapId) >= 0 || image.tileForFakeMapId(mapId) >= 0) {
                return image;
            }
        }
        return null;
    }

    public int tileForMapId(ImageMap image, int mapId) {
        int tile = image.tileForMapId(mapId);
        if (tile >= 0) {
            return tile;
        }
        return image.tileForFakeMapId(mapId);
    }

    public List<ImageMap> fromCreator(UUID creator) {
        List<ImageMap> list = new ArrayList<>();
        for (ImageMap image : images) {
            if (image.creator().equals(creator)) {
                list.add(image);
            }
        }
        return list;
    }

    public void register(ImageMap image) {
        images.add(image);
        images.sort(java.util.Comparator.comparingInt(ImageMap::index));
        byName.put(image.name().toLowerCase(java.util.Locale.ROOT), image);
        nextImageIndex = Math.max(nextImageIndex, image.index() + 1);
    }

    /** Drops the in-memory registry without touching disk, used before a storage reload. */
    public void resetRegistry() {
        images.clear();
        byName.clear();
    }

    public void remove(ImageMap image) {
        images.remove(image);
        byName.remove(image.name().toLowerCase(java.util.Locale.ROOT));
        mod.frames().removeForImage(image.index());
        if (image.isOverlay()) {
            mod.sender().restoreOverlay(image);
        }
        mod.storage().deleteImage(image);
        mod.sender().invalidate(image);
        ImageFrameFabric.LOGGER.info("Deleted image map #{} ({})", image.index(), image.name());
    }

    public void onImageUpdated(ImageMap image) {
        mod.sender().invalidate(image);
    }

    public void onRenamed(ImageMap image, String oldName) {
        if (oldName != null) {
            byName.remove(oldName.toLowerCase(java.util.Locale.ROOT));
        }
        byName.put(image.name().toLowerCase(java.util.Locale.ROOT), image);
    }

    public boolean nameExists(UUID creator, String name) {
        for (ImageMap image : images) {
            if (image.creator().equals(creator) && image.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean anyNameExists(String name) {
        return byName.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Creation pipeline
    // ------------------------------------------------------------------

    public record CreateRequest(ServerPlayer player, String name, String url, int width, int height,
                                com.imageframe.util.Palette.Dithering dithering, boolean combined,
                                Frames.Selection selection, boolean survivalMapsConsumed) {
    }

    public void createAsync(CreateRequest request) {
        executor.submit(() -> {
            try {
                ImageFrameFabric.LOGGER.info("Creating image map '{}' from '{}' for {}", request.name(), request.url(),
                        request.player().getScoreboardName());
                MediaLoader.Media media = MediaLoader.load(request.url(), mod.config());
                mod.server().execute(() -> finishCreate(request, media));
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                mod.server().execute(() -> {
                    request.player().sendSystemMessage(com.imageframe.util.Lang.t("download_failed", message));
                    refundMaps(request);
                });
                ImageFrameFabric.LOGGER.warn("Failed to load image {}", request.url(), throwable);
            }
        });
    }

    private void refundMaps(CreateRequest request) {
        if (request.survivalMapsConsumed()) {
            ItemsX.give(request.player(), new ItemStack(net.minecraft.world.item.Items.MAP,
                    request.width() * request.height()));
        }
    }

    private void finishCreate(CreateRequest request, MediaLoader.Media media) {
        try {
            ServerPlayer player = request.player();
            if (!mod.config().requireEmptyMaps || player.isCreative()) {
                // no map consumption needed
            } else if (!request.survivalMapsConsumed()) {
                int needed = request.width() * request.height();
                int removed = ItemsX.removeEmptyMaps(player, needed);
                if (removed < needed) {
                    player.sendSystemMessage(com.imageframe.util.Lang.t("not_enough_maps", needed));
                    return;
                }
            }
            int tiles = request.width() * request.height();
            List<Integer> mapIds = new ArrayList<>(tiles);
            for (int i = 0; i < tiles; i++) {
                mapIds.add(mod.server().overworld().getFreeMapId().id());
            }
            int index = nextImageIndex;
            ImageFrameFabric.LOGGER.info("Saving image map #{} '{}' ({}x{})", index, request.name(), request.width(), request.height());
            ImageMap image = ImageMap.build(this, index, request.name(), request.url(), request.width(),
                    request.height(), request.dithering(), player.getUUID(), player.getScoreboardName(),
                    System.currentTimeMillis(), media, mapIds);
            bumpImageIndex();
            register(image);
            mod.storage().saveImage(image);
            if (request.combined()) {
                ItemsX.give(player, mod.combined().combinedItem(image));
            } else if (request.selection() != null) {
                image.fillFrames(request.selection().frames(), request.selection().rotation());
            } else {
                for (ItemStack item : image.items()) {
                    ItemsX.give(player, item);
                }
            }
            mod.sender().sendHeldAndNearby(player, image);
            player.sendSystemMessage(com.imageframe.util.Lang.t("image_created"));
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.error("Failed to finish image creation", throwable);
            request.player().sendSystemMessage(com.imageframe.util.Lang.t("unknown_error", String.valueOf(throwable)));
        }
    }

    public record OverlayRequest(ServerPlayer player, String name, String url,
                                 com.imageframe.util.Palette.Dithering dithering, int width, int height,
                                 List<Integer> baseMapIds) {
    }

    public void createOverlayAsync(OverlayRequest request) {
        executor.submit(() -> {
            try {
                ImageFrameFabric.LOGGER.info("Creating overlay image map '{}' from '{}' for {}", request.name(),
                        request.url(), request.player().getScoreboardName());
                MediaLoader.Media media = MediaLoader.load(request.url(), mod.config());
                mod.server().execute(() -> finishCreateOverlay(request, media));
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                mod.server().execute(() -> request.player().sendSystemMessage(
                        com.imageframe.util.Lang.t("download_failed", message)));
                ImageFrameFabric.LOGGER.warn("Failed to load overlay image {}", request.url(), throwable);
            }
        });
    }

    private void finishCreateOverlay(OverlayRequest request, MediaLoader.Media media) {
        try {
            ServerPlayer player = request.player();
            if (media.animated() && media.frames().size() > 1) {
                player.sendSystemMessage(com.imageframe.util.Lang.t("overlay_animated_unsupported"));
                return;
            }
            int index = nextImageIndex;
            ImageMap image = ImageMap.buildOverlay(this, index, request.name(), request.url(), request.width(),
                    request.height(), request.dithering(), player.getUUID(), player.getScoreboardName(),
                    System.currentTimeMillis(), media, request.baseMapIds());
            bumpImageIndex();
            register(image);
            mod.storage().saveImage(image);
            for (ServerPlayer online : mod.server().getPlayerList().getPlayers()) {
                mod.sender().sendOverlay(online, image);
            }
            player.sendSystemMessage(com.imageframe.util.Lang.t("overlay_created"));
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.error("Failed to finish overlay creation", throwable);
            request.player().sendSystemMessage(com.imageframe.util.Lang.t("unknown_error", String.valueOf(throwable)));
        }
    }

    public void refreshAsync(ImageMap image, String newUrl) {
        refreshAsync(image, newUrl, null);
    }

    public void refreshAsync(ImageMap image, String newUrl, com.imageframe.util.Palette.Dithering newDithering) {
        executor.submit(() -> {
            try {
                MediaLoader.Media media = MediaLoader.load(newUrl == null ? image.url() : newUrl, mod.config());
                boolean animated = media.animated() && media.frames().size() > 1;
                if (animated && image.isOverlay()) {
                    mod.server().execute(() -> image.creatorPlayerMessage("overlay_animated_unsupported"));
                    return;
                }
                if (animated != image.isAnimated() && !image.isOverlay()) {
                    mod.server().execute(() -> image.creatorPlayerMessage("unable_to_change_image_type"));
                    return;
                }
                mod.server().execute(() -> {
                    if (newUrl != null) {
                        image.setUrlInternal(newUrl);
                    }
                    if (newDithering != null) {
                        image.setDithering(newDithering);
                    }
                    boolean overlay = image.isOverlay();
                    image.applyMedia(media);
                    if (overlay) {
                        image.markOverlay();
                    }
                    image.save();
                    image.markUpdated();
                    for (ServerPlayer player : mod.server().getPlayerList().getPlayers()) {
                        if (overlay) {
                            mod.sender().sendOverlay(player, image);
                        } else {
                            mod.sender().sendHeldAndNearby(player, image);
                        }
                    }
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                mod.server().execute(() -> image.creatorPlayerMessage("download_failed", message));
            }
        });
    }

    public record CloneRequest(ServerPlayer player, ImageMap source, String name, boolean combined,
                               Frames.Selection selection) {
    }

    public void cloneAsync(CloneRequest request) {
        executor.submit(() -> {
            try {
                MediaLoader.Media media = MediaLoader.load(request.source().url(), mod.config());
                mod.server().execute(() -> {
                    ImageMap source = request.source();
                    int tiles = source.tileCount();
                    ImageMap copy;
                    if (source.isOverlay()) {
                        List<Integer> baseIds = source.overlayBaseMapIds() == null
                                ? new ArrayList<>() : new ArrayList<>(source.overlayBaseMapIds());
                        copy = ImageMap.buildOverlay(this, nextImageIndex, request.name(), source.url(),
                                source.width(), source.height(), source.dithering(), request.player().getUUID(),
                                request.player().getScoreboardName(), System.currentTimeMillis(), media, baseIds);
                    } else {
                        List<Integer> mapIds = new ArrayList<>(tiles);
                        for (int i = 0; i < tiles; i++) {
                            mapIds.add(mod.server().overworld().getFreeMapId().id());
                        }
                        copy = ImageMap.build(this, nextImageIndex, request.name(), source.url(),
                                source.width(), source.height(), source.dithering(), request.player().getUUID(),
                                request.player().getScoreboardName(), System.currentTimeMillis(), media, mapIds);
                    }
                    bumpImageIndex();
                    // Copy markers across.
                    for (int tile = 0; tile < tiles; tile++) {
                        for (Map.Entry<String, ImageMap.Marker> entry : source.markers().get(tile).entrySet()) {
                            copy.markers().get(tile).put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (!source.isOverlay()) {
                        copy.setOverlayBaseMapIds(source.overlayBaseMapIds());
                    }
                    register(copy);
                    mod.storage().saveImage(copy);
                    if (source.isOverlay()) {
                        for (ServerPlayer online : mod.server().getPlayerList().getPlayers()) {
                            mod.sender().sendOverlay(online, copy);
                        }
                        request.player().sendSystemMessage(com.imageframe.util.Lang.t("overlay_created"));
                        return;
                    }
                    if (request.selection() != null) {
                        copy.fillFrames(request.selection().frames(), request.selection().rotation());
                    } else if (request.combined()) {
                        ItemsX.give(request.player(), mod.combined().combinedItem(copy));
                    } else {
                        for (ItemStack item : copy.items()) {
                            ItemsX.give(request.player(), item);
                        }
                    }
                    mod.sender().sendHeldAndNearby(request.player(), copy);
                    request.player().sendSystemMessage(com.imageframe.util.Lang.t("image_created"));
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                mod.server().execute(() -> request.player().sendSystemMessage(com.imageframe.util.Lang.t("download_failed", message)));
            }
        });
    }
}
