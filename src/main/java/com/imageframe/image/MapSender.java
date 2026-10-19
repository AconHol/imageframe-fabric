package com.imageframe.image;

import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import com.imageframe.util.ItemsX;
import net.minecraft.core.component.DataComponents;
import com.imageframe.util.Palette;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends map data and item frame updates to players. Image maps are not registered as
 * vanilla saved map data; the packets are generated directly so animations can reuse
 * a large pool of client side "fake" map ids.
 */
public final class MapSender {

    public static final double STATIC_VIEW_DISTANCE_SQR = 128.0 * 128.0;
    public static final double ANIMATION_VIEW_DISTANCE_SQR = 96.0 * 96.0;
    private static final int STATIC_RESEND_TICKS = 200;

    private final ImageFrameMod mod;
    private final Map<UUID, Map<Integer, Long>> lastStaticSent = new HashMap<>();
    private final Map<UUID, Set<Integer>> knownFakeIds = new HashMap<>();
    private final Map<UUID, Map<Integer, Long>> lastOverlaySent = new HashMap<>();
    private final Map<Integer, CachedBase> baseColorCache = new HashMap<>();
    private EntityDataAccessor<ItemStack> itemFrameDataItem;
    private volatile boolean smoothAnimationUnavailable;
    private boolean smoothAnimationWarningLogged;

    private record CachedBase(byte[] colors, long tick) {
    }

    public MapSender(ImageFrameMod mod) {
        this.mod = mod;
    }

    public void onPlayerJoin(ServerPlayer player) {
        lastStaticSent.put(player.getUUID(), new HashMap<>());
        knownFakeIds.put(player.getUUID(), new HashSet<>());
    }

    public void onPlayerLeave(ServerPlayer player) {
        lastStaticSent.remove(player.getUUID());
        knownFakeIds.remove(player.getUUID());
        lastOverlaySent.remove(player.getUUID());
    }

    public void invalidate(ImageMap image) {
        int[] fakeIds = image.allFakeMapIds();
        for (Set<Integer> known : knownFakeIds.values()) {
            for (int id : fakeIds) {
                known.remove(id);
            }
        }
        List<Integer> mapIds = image.mapIds();
        for (Map<Integer, Long> sent : lastStaticSent.values()) {
            for (int id : mapIds) {
                sent.remove(id);
            }
        }
        for (Map<Integer, Long> sent : lastOverlaySent.values()) {
            for (int id : mapIds) {
                sent.remove(id);
            }
        }
        for (int id : mapIds) {
            baseColorCache.remove(id);
        }
    }

    // ------------------------------------------------------------------
    // Packet primitives
    // ------------------------------------------------------------------

    public void send(ServerPlayer player, int mapId, byte[] colors, List<MapDecoration> decorations) {
        byte[] data = colors == null ? new byte[Palette.MAP_PIXELS] : colors;
        Optional<List<MapDecoration>> decorationList = decorations == null || decorations.isEmpty()
                ? Optional.empty() : Optional.of(decorations);
        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(new MapId(mapId), (byte) 0, false,
                decorationList, Optional.of(new MapItemSavedData.MapPatch(0, 0, Palette.MAP_SIZE, Palette.MAP_SIZE, data)));
        player.connection.send(packet);
    }

    public void sendImage(ServerPlayer player, ImageMap image) {
        if (!image.valid()) {
            return;
        }
        int tick = image.currentTick();
        for (int tile = 0; tile < image.tileCount(); tile++) {
            send(player, image.mapIdForTile(tile), image.colorsFor(tile, tick), image.decorations(tile));
        }
    }

    public void sendHeldAndNearby(ServerPlayer player, ImageMap image) {
        sendImage(player, image);
        if (image.isAnimated()) {
            sendAllAnimationFrames(player, image);
        }
    }

    public void sendAllAnimationFrames(ServerPlayer player, ImageMap image) {
        int[][] fakeIds = image.animationFakeIds();
        byte[][][] colors = image.animationColors();
        if (fakeIds == null || colors == null) {
            return;
        }
        Set<Integer> known = knownFakeIds.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
        for (int tile = 0; tile < fakeIds.length; tile++) {
            int[] tileFakeIds = fakeIds[tile];
            for (int frame = 0; frame < tileFakeIds.length; frame++) {
                int fakeId = tileFakeIds[frame];
                if (fakeId < 0) {
                    continue;
                }
                byte[] frameColors = tile < colors.length && frame < colors[tile].length ? colors[tile][frame] : null;
                if (frameColors == null) {
                    continue;
                }
                send(player, fakeId, frameColors, image.decorations(tile));
                known.add(fakeId);
            }
        }
    }

    public void sendFakeItem(ServerPlayer player, ItemFrame frame, int fakeMapId) {
        EntityDataAccessor<ItemStack> accessor = itemFrameDataItem();
        if (accessor == null) {
            return;
        }
        ItemStack stack = frame.getItem().copy();
        stack.set(DataComponents.MAP_ID, new MapId(fakeMapId));
        sendItemPacket(player, frame, accessor, stack);
    }

    private void sendItemPacket(ServerPlayer player, ItemFrame frame, EntityDataAccessor<ItemStack> accessor, ItemStack stack) {
        var value = SynchedEntityData.DataValue.create(accessor, stack);
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        values.add(value);
        player.connection.send(new ClientboundSetEntityDataPacket(frame.getId(), values));
    }

    private EntityDataAccessor<ItemStack> itemFrameDataItem() {
        if (itemFrameDataItem == null) {
            try {
                Field field = ItemFrame.class.getDeclaredField("DATA_ITEM");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                EntityDataAccessor<ItemStack> accessor = (EntityDataAccessor<ItemStack>) field.get(null);
                itemFrameDataItem = accessor;
            } catch (ReflectiveOperationException e) {
                smoothAnimationUnavailable = true;
                if (!smoothAnimationWarningLogged) {
                    smoothAnimationWarningLogged = true;
                    ImageFrameFabric.LOGGER.warn("Unable to access ItemFrame.DATA_ITEM; falling back to colour-based frame animation", e);
                }
            }
        }
        return itemFrameDataItem;
    }

    // ------------------------------------------------------------------
    // Tick logic
    // ------------------------------------------------------------------

    public void tick() {
        MinecraftServer server = mod.server();
        if (server == null) {
            return;
        }
        long tick = mod.tickCounter();
        for (ImageMap image : mod.manager().all()) {
            if (!image.valid()) {
                continue;
            }
            List<FrameTracker.TrackedFrame> frames = mod.frames().framesForImage(image.index());
            if (frames.isEmpty()) {
                continue;
            }
            if (!image.isAnimated()) {
                if (tick % 20 == 0) {
                    for (FrameTracker.TrackedFrame frame : frames) {
                        staticSweep(frame, image, tick);
                    }
                }
            } else {
                int current = image.currentTick();
                boolean smooth = smoothAnimationEnabled();
                for (FrameTracker.TrackedFrame frame : frames) {
                    if (smooth) {
                        smoothAnimationSweep(frame, image, current, tick);
                    } else {
                        colorAnimationSweep(frame, image, current, tick);
                    }
                }
                if (mod.config().debugLogging && tick % 200 == 0) {
                    ImageFrameFabric.LOGGER.info("Animated image '{}' tick={} mode={} trackedFrames={}",
                            image.name(), current, smooth ? "smooth" : "color", frames.size());
                }
            }
        }
        if (tick % 2 == 0) {
            heldMapSweep(server, tick);
        }
        if (tick % 2 == 0) {
            overlaySweep(server, tick);
        }
    }

    private void staticSweep(FrameTracker.TrackedFrame tracked, ImageMap image, long tick) {
        ServerLevel level = tracked.level();
        ItemFrame frame = tracked.frame();
        if (!frame.isAlive()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(frame) > STATIC_VIEW_DISTANCE_SQR) {
                continue;
            }
            Map<Integer, Long> sent = lastStaticSent.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
            for (int tile = 0; tile < image.tileCount(); tile++) {
                int mapId = image.mapIdForTile(tile);
                Long last = sent.get(mapId);
                if (last == null || tick - last > STATIC_RESEND_TICKS) {
                    send(player, mapId, image.colorsFor(tile, 0), image.decorations(tile));
                    sent.put(mapId, tick);
                }
            }
        }
    }

    private void colorAnimationSweep(FrameTracker.TrackedFrame tracked, ImageMap image, int currentTick, long tick) {
        ServerLevel level = tracked.level();
        ItemFrame frame = tracked.frame();
        if (!frame.isAlive()) {
            return;
        }
        int tile = tracked.tile();
        int mapId = image.mapIdForTile(tile);
        if (mapId < 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(frame) > ANIMATION_VIEW_DISTANCE_SQR) {
                continue;
            }
            if (!viewAnimatedMaps(player)) {
                // Show the first frame as a static image for players who disabled animation.
                if (tick % 20 == 0) {
                    Map<Integer, Long> sent = lastStaticSent.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
                    Long last = sent.get(mapId);
                    if (last == null || tick - last > STATIC_RESEND_TICKS) {
                        send(player, mapId, image.colorsFor(tile, 0), image.decorations(tile));
                        sent.put(mapId, tick);
                    }
                }
                continue;
            }
            if (tick % 2 != 0) {
                continue;
            }
            byte[] colors = image.colorsFor(tile, currentTick);
            if (colors != null) {
                send(player, mapId, colors, image.decorations(tile));
            }
        }
    }

    private boolean smoothAnimationEnabled() {
        if (smoothAnimationUnavailable) {
            return false;
        }
        String mode = mod.config().frameAnimationMode;
        if (mode != null && "color".equalsIgnoreCase(mode)) {
            return false;
        }
        if (itemFrameDataItem() == null) {
            smoothAnimationUnavailable = true;
            return false;
        }
        return true;
    }

    /**
     * Smooth animation: the client already has every animation frame cached under a "fake" map id,
     * so each tick we only change the item frame's displayed item to the map id of the current frame.
     * This is the same technique as the original ImageFrame plugin and only sends a few dozen bytes
     * per frame instead of a full 16 KiB map colour patch.
     */
    private void smoothAnimationSweep(FrameTracker.TrackedFrame tracked, ImageMap image, int currentTick, long tick) {
        ServerLevel level = tracked.level();
        ItemFrame frame = tracked.frame();
        if (!frame.isAlive()) {
            return;
        }
        int tile = tracked.tile();
        int fakeMapId = image.fakeMapIdFor(tile, currentTick, image.paused());
        if (fakeMapId < 0) {
            // This tile did not change in the current frame; the client keeps showing the previous one.
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(frame) > ANIMATION_VIEW_DISTANCE_SQR) {
                continue;
            }
            if (!viewAnimatedMaps(player)) {
                // Show the first frame as a static image for players who disabled animation.
                if (tick % 20 == 0) {
                    sendRealItem(player, frame);
                }
                continue;
            }
            if (mod.markers().isEditing(player)) {
                // Marker placement needs the real map item on the client.
                if (tick % 10 == 0) {
                    sendRealItem(player, frame);
                }
                continue;
            }
            Set<Integer> known = knownFakeIds.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
            if (!known.contains(fakeMapId)) {
                sendAllAnimationFrames(player, image);
            }
            sendFakeItem(player, frame, fakeMapId);
        }
    }

    public void sendRealItem(ServerPlayer player, ItemFrame frame) {
        EntityDataAccessor<ItemStack> accessor = itemFrameDataItem();
        if (accessor == null) {
            return;
        }
        sendItemPacket(player, frame, accessor, frame.getItem().copy());
    }

    /**
     * Reverts any client-side fake animation items to the real item and forgets cached frames.
     * Called when an image is refreshed or deleted so clients do not keep a stale frame.
     */
    public void restoreFrameItems(ImageMap image) {
        if (image == null) {
            return;
        }
        for (FrameTracker.TrackedFrame tracked : mod.frames().framesForImage(image.index())) {
            ItemFrame frame = tracked.frame();
            if (!frame.isAlive()) {
                continue;
            }
            for (ServerPlayer player : tracked.level().players()) {
                if (player.distanceToSqr(frame) <= STATIC_VIEW_DISTANCE_SQR) {
                    sendRealItem(player, frame);
                }
            }
        }
        invalidate(image);
    }

    private void heldMapSweep(MinecraftServer server, long tick) {
        boolean sendBase = tick % 10 == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            checkHeldMap(player, main, tick, sendBase);
            if (!ItemStack.isSameItemSameComponents(main, off)) {
                checkHeldMap(player, off, tick, sendBase);
            }
        }
    }

    private void checkHeldMap(ServerPlayer player, ItemStack stack, long tick, boolean sendBase) {
        Integer mapId = ItemsX.mapId(stack);
        if (mapId == null) {
            return;
        }
        ImageMap image = mod.manager().byAnyMapId(mapId);
        if (image == null || !image.valid()) {
            return;
        }
        int tile = image.tileForMapId(mapId);
        if (tile < 0) {
            tile = image.tileForFakeMapId(mapId);
        }
        if (tile < 0) {
            return;
        }
        if (!image.isAnimated()) {
            if (!sendBase) {
                return;
            }
            Map<Integer, Long> sent = lastStaticSent.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
            Long last = sent.get(mapId);
            if (last == null || tick - last > STATIC_RESEND_TICKS) {
                send(player, mapId, image.colorsFor(tile, 0), image.decorations(tile));
                sent.put(mapId, tick);
            }
        } else if (!viewAnimatedMaps(player)) {
            if (!sendBase) {
                return;
            }
            send(player, mapId, image.colorsFor(tile, 0), image.decorations(tile));
        } else {
            int current = image.currentTick();
            send(player, mapId, image.colorsFor(tile, current), image.decorations(tile));
            if (!knownFakeIds.containsKey(player.getUUID())) {
                sendAllAnimationFrames(player, image);
            }
        }
    }

    // ------------------------------------------------------------------
    // Overlay images (painted onto pre-existing vanilla map ids)
    // ------------------------------------------------------------------

    public byte[] baseColorsFor(int mapId) {
        MinecraftServer server = mod.server();
        if (server == null) {
            return null;
        }
        MapId id = new MapId(mapId);
        for (ServerLevel level : server.getAllLevels()) {
            MapItemSavedData data = level.getMapData(id);
            if (data != null && data.colors != null) {
                return data.colors.clone();
            }
        }
        return null;
    }

    private byte[] cachedBaseColors(int mapId, long tick) {
        CachedBase cached = baseColorCache.get(mapId);
        if (cached != null && tick - cached.tick() < 40) {
            return cached.colors();
        }
        byte[] colors = baseColorsFor(mapId);
        baseColorCache.put(mapId, new CachedBase(colors, tick));
        return colors;
    }

    /** Restores the original map colours after an overlay image is removed. */
    public void restoreOverlay(ImageMap image) {
        List<Integer> bases = image.overlayBaseMapIds();
        if (bases == null || bases.isEmpty()) {
            return;
        }
        MinecraftServer server = mod.server();
        if (server == null) {
            return;
        }
        byte[][] colors = new byte[bases.size()][];
        for (int tile = 0; tile < bases.size(); tile++) {
            colors[tile] = baseColorsFor(bases.get(tile));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int tile = 0; tile < bases.size(); tile++) {
                if (colors[tile] != null) {
                    send(player, bases.get(tile), colors[tile], null);
                }
            }
        }
        for (int base : bases) {
            baseColorCache.remove(base);
        }
        for (Map<Integer, Long> sent : lastOverlaySent.values()) {
            for (int base : bases) {
                sent.remove(base);
            }
        }
    }

    /** Immediately applies all overlay images the player is able to see. */
    public void sendOverlay(ServerPlayer player, ImageMap image) {
        if (!image.isOverlay()) {
            return;
        }
        List<Integer> held = new ArrayList<>();
        Integer main = ItemsX.mapId(player.getMainHandItem());
        Integer off = ItemsX.mapId(player.getOffhandItem());
        if (main != null) {
            held.add(main);
        }
        if (off != null && !off.equals(main)) {
            held.add(off);
        }
        sendOverlayFor(player, image, held, mod.tickCounter(), true);
    }

    private void overlaySweep(MinecraftServer server, long tick) {
        List<ImageMap> overlays = null;
        for (ImageMap image : mod.manager().all()) {
            if (image.valid() && image.isOverlay()) {
                if (overlays == null) {
                    overlays = new ArrayList<>();
                }
                overlays.add(image);
            }
        }
        if (overlays == null || overlays.isEmpty()) {
            return;
        }
        boolean frameScan = tick % 20 == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) {
                continue;
            }
            List<Integer> held = new ArrayList<>();
            Integer main = ItemsX.mapId(player.getMainHandItem());
            Integer off = ItemsX.mapId(player.getOffhandItem());
            if (main != null) {
                held.add(main);
            }
            if (off != null && !off.equals(main)) {
                held.add(off);
            }
            for (ImageMap image : overlays) {
                sendOverlayFor(player, image, held, tick, false);
            }
            if (frameScan) {
                List<Integer> frameIds = new ArrayList<>();
                List<ItemFrame> frames = level.getEntities(EntityTypeTest.forClass(ItemFrame.class),
                        player.getBoundingBox().inflate(64.0), frame -> ItemsX.mapId(frame.getItem()) != null);
                for (ItemFrame frame : frames) {
                    Integer id = ItemsX.mapId(frame.getItem());
                    if (id != null && !frameIds.contains(id)) {
                        frameIds.add(id);
                    }
                }
                if (!frameIds.isEmpty()) {
                    for (ImageMap image : overlays) {
                        sendOverlayFor(player, image, frameIds, tick, true);
                    }
                }
            }
        }
    }

    private void sendOverlayFor(ServerPlayer player, ImageMap image, List<Integer> mapIds, long tick, boolean force) {
        List<Integer> bases = image.overlayBaseMapIds();
        if (bases == null || bases.isEmpty()) {
            return;
        }
        Map<Integer, Long> sent = lastOverlaySent.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        for (int tile = 0; tile < bases.size(); tile++) {
            int base = bases.get(tile);
            if (!mapIds.contains(base)) {
                continue;
            }
            Long last = sent.get(base);
            if (!force && last != null && tick - last < 2) {
                continue;
            }
            byte[] baseColors = cachedBaseColors(base, tick);
            if (baseColors == null) {
                continue;
            }
            byte[] overlayColors = image.colorsFor(tile, 0);
            if (overlayColors == null) {
                continue;
            }
            byte[] merged = baseColors.clone();
            int limit = Math.min(merged.length, overlayColors.length);
            boolean changed = false;
            for (int i = 0; i < limit; i++) {
                if ((overlayColors[i] & 0xFF) != 0) {
                    merged[i] = overlayColors[i];
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            send(player, base, merged, image.decorations(tile));
            sent.put(base, tick);
        }
    }

    private boolean viewAnimatedMaps(ServerPlayer player) {
        try {
            return mod.preferences().viewAnimatedMaps(player);
        } catch (Throwable ignored) {
            return mod.config().viewAnimatedMaps;
        }
    }

    public List<MapDecoration> decorations(ImageMap image, int tile) {
        return image.decorations(tile);
    }

    public List<byte[]> debugColors(ImageMap image) {
        List<byte[]> list = new ArrayList<>();
        for (int tile = 0; tile < image.tileCount(); tile++) {
            list.add(image.colorsFor(tile, 0));
        }
        return list;
    }
}
