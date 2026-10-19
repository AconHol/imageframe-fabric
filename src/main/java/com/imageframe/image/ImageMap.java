package com.imageframe.image;

import com.imageframe.ImageFrameMod;
import com.imageframe.util.ItemsX;
import com.imageframe.util.Lang;
import com.imageframe.util.Palette;
import com.imageframe.util.Text;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One image map: every tile is a separate vanilla filled map id. Static images store
 * one colour buffer per tile, animated images store the distinct animation frames.
 */
public final class ImageMap {

    public enum Kind {
        STATIC, ANIMATED, OVERLAY
    }

    public record Marker(String name, byte x, byte y, byte rotation, String type, String caption) {
    }

    private final ImageMapManager manager;
    private final int index;
    private volatile String name;
    private volatile String url;
    private final int width;
    private final int height;
    private volatile Palette.Dithering dithering;
    private volatile UUID creator;
    private volatile String creatorName;
    private final long creationTime;
    private volatile Kind kind;
    private final List<Integer> mapIds = new ArrayList<>();
    private volatile byte[][] staticColors;
    private volatile byte[][][] animationColors;
    private volatile int[][] animationFakeIds;
    private volatile int[] sequence = new int[0];
    private volatile boolean paused;
    private volatile int pausedAt = -1;
    private volatile int tickOffset;
    private final List<Map<String, Marker>> markers = new ArrayList<>();
    private final Map<UUID, String> access = new LinkedHashMap<>();
    private volatile boolean valid = true;
    private volatile int version;
    private volatile List<Integer> overlayBaseMapIds;

    public ImageMap(ImageMapManager manager, int index, String name, String url, int width, int height,
                    Palette.Dithering dithering, UUID creator, String creatorName, long creationTime, Kind kind) {
        this.manager = manager;
        this.index = index;
        this.name = name;
        this.url = url;
        this.width = width;
        this.height = height;
        this.dithering = dithering;
        this.creator = creator;
        this.creatorName = creatorName;
        this.creationTime = creationTime;
        this.kind = kind;
        resizeMarkers();
    }

    public static ImageMap build(ImageMapManager manager, int index, String name, String url, int width, int height,
                                 Palette.Dithering dithering, UUID creator, String creatorName, long creationTime,
                                 MediaLoader.Media media, List<Integer> mapIds) {
        boolean animated = media.animated() && media.frames().size() > 1;
        ImageMap imageMap = new ImageMap(manager, index, name, url, width, height, dithering, creator, creatorName,
                creationTime, animated ? Kind.ANIMATED : Kind.STATIC);
        imageMap.mapIds.clear();
        imageMap.mapIds.addAll(mapIds);
        imageMap.applyMedia(media);
        return imageMap;
    }

    public static ImageMap buildOverlay(ImageMapManager manager, int index, String name, String url, int width,
                                        int height, Palette.Dithering dithering, UUID creator, String creatorName,
                                        long creationTime, MediaLoader.Media media, List<Integer> baseMapIds) {
        ImageMap imageMap = new ImageMap(manager, index, name, url, width, height, dithering, creator, creatorName,
                creationTime, Kind.OVERLAY);
        imageMap.mapIds.clear();
        imageMap.mapIds.addAll(baseMapIds);
        imageMap.overlayBaseMapIds = new ArrayList<>(baseMapIds);
        imageMap.applyMedia(media);
        imageMap.kind = Kind.OVERLAY;
        return imageMap;
    }

    public boolean isOverlay() {
        return kind == Kind.OVERLAY;
    }

    /** Keeps this image marked as an overlay after its colours were regenerated. */
    public void markOverlay() {
        this.kind = Kind.OVERLAY;
    }

    public void setCreator(UUID creator, String creatorName) {
        this.creator = creator;
        this.creatorName = creatorName;
    }

    private void resizeMarkers() {
        while (markers.size() < width * height) {
            markers.add(new LinkedHashMap<>());
        }
    }

    public void applyMedia(MediaLoader.Media media) {
        int frames = media.frames().size();
        boolean animated = media.animated() && frames > 1;
        int maxFrames = manager.mod().config().maxAnimationFrames;
        if (!animated) {
            BufferedImage canvas = Palette.fit(media.frames().get(0), width, height);
            byte[][] colors = new byte[width * height][];
            for (int tile = 0; tile < width * height; tile++) {
                int tileX = tile % width;
                int tileY = tile / width;
                colors[tile] = Palette.toMapColors(Palette.tile(canvas, tileX, tileY), dithering);
            }
            staticColors = colors;
            animationColors = null;
            animationFakeIds = null;
            sequence = new int[0];
            kind = Kind.STATIC;
        } else {
            // Expand the GIF into a 20 ticks per second sequence.
            List<Integer> ticks = new ArrayList<>();
            outer:
            for (int frame = 0; frame < frames; frame++) {
                int delay = Math.max(10, media.delaysMillis().get(frame));
                int repeat = Math.max(1, Math.round(delay / 50.0F));
                for (int i = 0; i < repeat; i++) {
                    ticks.add(frame);
                    if (ticks.size() >= maxFrames) {
                        break outer;
                    }
                }
            }
            int tickCount = ticks.size();
            List<byte[][]> distinctColors = new ArrayList<>();
            List<int[][]> distinctFakeIds = new ArrayList<>();
            int[] tickToDistinct = new int[tickCount];
            BufferedImage lastFitted = null;
            int distinctCount = 0;
            for (int tick = 0; tick < tickCount; tick++) {
                int frame = ticks.get(tick);
                if (lastFitted != null && tick > 0 && ticks.get(tick - 1) == frame && tickToDistinct[tick - 1] >= 0) {
                    tickToDistinct[tick] = tickToDistinct[tick - 1];
                    continue;
                }
                BufferedImage fitted = Palette.fit(media.frames().get(frame), width, height);
                boolean duplicate = lastFitted != null && sameImage(lastFitted, fitted);
                if (duplicate) {
                    tickToDistinct[tick] = distinctCount - 1;
                    continue;
                }
                lastFitted = fitted;
                byte[][] colors = new byte[width * height][];
                int[][] fakeIds = new int[width * height][1];
                for (int tile = 0; tile < width * height; tile++) {
                    int tileX = tile % width;
                    int tileY = tile / width;
                    colors[tile] = Palette.toMapColors(Palette.tile(fitted, tileX, tileY), dithering);
                }
                distinctColors.add(colors);
                distinctFakeIds.add(fakeIds);
                tickToDistinct[tick] = distinctCount;
                distinctCount++;
            }
            int distinct = distinctColors.size();
            byte[][][] animationColors = new byte[width * height][distinct][];
            int[][] animationFakeIds = new int[width * height][distinct];
            for (int tile = 0; tile < width * height; tile++) {
                byte[] last = null;
                for (int frame = 0; frame < distinct; frame++) {
                    byte[] colors = distinctColors.get(frame)[tile];
                    if (last != null && java.util.Arrays.equals(last, colors)) {
                        animationColors[tile][frame] = null;
                        animationFakeIds[tile][frame] = -1;
                    } else {
                        animationColors[tile][frame] = colors;
                        animationFakeIds[tile][frame] = manager.nextFakeMapId();
                        last = colors;
                    }
                }
            }
            sequence = new int[tickCount];
            for (int tick = 0; tick < tickCount; tick++) {
                sequence[tick] = Math.max(0, tickToDistinct[tick]);
            }
            this.animationColors = animationColors;
            this.animationFakeIds = animationFakeIds;
            this.staticColors = null;
            this.kind = Kind.ANIMATED;
        }
        if (pausedAt >= 0) {
            pausedAt = Math.floorMod(pausedAt, Math.max(1, sequenceLength()));
        }
        markUpdated();
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        return java.util.Arrays.equals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
    }

    // ------------------------------------------------------------------
    // Basic accessors
    // ------------------------------------------------------------------

    public ImageMapManager manager() {
        return manager;
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    public void setUrlInternal(String url) {
        this.url = url;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int tileCount() {
        return width * height;
    }

    public Palette.Dithering dithering() {
        return dithering;
    }

    public UUID creator() {
        return creator;
    }

    public String creatorName() {
        return creatorName;
    }

    public long creationTime() {
        return creationTime;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isAnimated() {
        return kind == Kind.ANIMATED;
    }

    public boolean valid() {
        return valid;
    }

    public int version() {
        return version;
    }

    public List<Integer> mapIds() {
        return mapIds;
    }

    public int mapIdForTile(int tile) {
        return mapIds.get(tile);
    }

    public int tileForMapId(int mapId) {
        return mapIds.indexOf(mapId);
    }

    public int tileForFakeMapId(int mapId) {
        int[][] ids = animationFakeIds;
        if (ids == null) {
            return -1;
        }
        for (int tile = 0; tile < ids.length; tile++) {
            for (int frame = 0; frame < ids[tile].length; frame++) {
                if (ids[tile][frame] == mapId) {
                    return tile;
                }
            }
        }
        return -1;
    }

    public Map<UUID, String> access() {
        return access;
    }

    public List<Map<String, Marker>> markers() {
        return markers;
    }

    public List<Integer> overlayBaseMapIds() {
        return overlayBaseMapIds;
    }

    public void setOverlayBaseMapIds(List<Integer> ids) {
        this.overlayBaseMapIds = ids == null ? null : new ArrayList<>(ids);
    }

    public int sequenceLength() {
        return sequence.length;
    }

    public boolean paused() {
        return paused;
    }

    public int pausedAt() {
        return pausedAt;
    }

    public void setPaused(boolean paused) {
        if (paused && pausedAt < 0) {
            pausedAt = currentTick();
        } else if (!paused && pausedAt >= 0) {
            setTickOffset(Math.floorMod(pausedAt - (int) (manager.tickCounter() % Math.max(1, sequenceLength())), Math.max(1, sequenceLength())));
            pausedAt = -1;
        }
        this.paused = paused;
        markUpdated();
    }

    public void setTickOffset(int offset) {
        this.tickOffset = offset;
        markUpdated();
    }

    public void setPlaybackSeconds(double seconds) {
        int length = Math.max(1, sequenceLength());
        int ticks = seconds < 0 ? length + (int) Math.ceil((seconds + 1) * 20) : (int) Math.floor(seconds * 20);
        ticks = Math.min(Math.max(0, ticks), length - 1);
        if (paused) {
            pausedAt = ticks;
        } else {
            setTickOffset(Math.floorMod(ticks - (int) (manager.tickCounter() % length), length));
        }
        markUpdated();
    }

    public int currentTick() {
        int length = sequenceLength();
        if (length <= 0) {
            return 0;
        }
        if (paused && pausedAt >= 0) {
            return Math.floorMod(pausedAt, length);
        }
        return Math.floorMod((int) (manager.tickCounter() % length) + tickOffset, length);
    }

    public byte[] colorsFor(int tile, int tick) {
        if (!isAnimated()) {
            byte[][] colors = staticColors;
            return colors == null || tile < 0 || tile >= colors.length ? null : colors[tile];
        }
        int[] seq = sequence;
        if (seq.length == 0) {
            return null;
        }
        int distinct = seq[Math.floorMod(tick, seq.length)];
        byte[][] colors = animationColors == null || tile < 0 || tile >= animationColors.length ? null : animationColors[tile];
        if (colors == null || colors.length == 0) {
            return null;
        }
        int frame = Math.min(distinct, colors.length - 1);
        for (int i = frame; i >= 0; i--) {
            if (colors[i] != null) {
                return colors[i];
            }
        }
        for (int i = frame + 1; i < colors.length; i++) {
            if (colors[i] != null) {
                return colors[i];
            }
        }
        return null;
    }

    /** Fake map id used by the client side animation trick, -1 if this frame is unchanged. */
    public int fakeMapIdFor(int tile, int tick) {
        return fakeMapIdFor(tile, tick, false);
    }

    public int fakeMapIdFor(int tile, int tick, boolean lookbehind) {
        int[][] ids = animationFakeIds;
        if (ids == null || tile < 0 || tile >= ids.length) {
            return -1;
        }
        int[] seq = sequence;
        if (seq.length == 0) {
            return -1;
        }
        int distinct = seq[Math.floorMod(tick, seq.length)];
        int[] frames = ids[tile];
        if (distinct >= frames.length) {
            distinct = frames.length - 1;
        }
        int mapId = frames[distinct];
        if (mapId >= 0 || !lookbehind) {
            return mapId;
        }
        for (int i = distinct; i >= 0; i--) {
            if (frames[i] >= 0) {
                return frames[i];
            }
        }
        for (int i = distinct + 1; i < frames.length; i++) {
            if (frames[i] >= 0) {
                return frames[i];
            }
        }
        return -1;
    }

    public int[] allFakeMapIds() {
        int[][] ids = animationFakeIds;
        if (ids == null) {
            return new int[0];
        }
        List<Integer> list = new ArrayList<>();
        for (int[] tileIds : ids) {
            for (int id : tileIds) {
                if (id >= 0) {
                    list.add(id);
                }
            }
        }
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    public void setStaticColors(byte[][] colors) {
        this.staticColors = colors;
    }

    public byte[][] staticColors() {
        return staticColors;
    }

    public void setAnimation(byte[][][] colors, int[][] fakeIds, int[] sequence) {
        this.animationColors = colors;
        this.animationFakeIds = fakeIds;
        this.sequence = sequence;
    }

    public void setAnimationColors(byte[][][] colors) {
        this.animationColors = colors;
    }

    public void setAnimationFakeIds(int[][] fakeIds) {
        this.animationFakeIds = fakeIds;
    }

    public void setSequence(int[] sequence) {
        this.sequence = sequence;
    }

    public int distinctCount() {
        int[][] ids = animationFakeIds;
        if (ids == null || ids.length == 0) {
            return 0;
        }
        return ids[0].length;
    }

    public byte[][][] animationColors() {
        return animationColors;
    }

    public int[][] animationFakeIds() {
        return animationFakeIds;
    }

    public int[] sequence() {
        return sequence;
    }

    // ------------------------------------------------------------------
    // Item creation
    // ------------------------------------------------------------------

    public ItemStack itemForTile(int tile) {
        if (tile < 0 || tile >= mapIds.size()) {
            throw new IndexOutOfBoundsException("tile " + tile);
        }
        String format = manager.mod().config().mapItemFormat;
        int x = tile % width;
        int y = tile / width;
        boolean hideName = format == null || format.isBlank();
        String nameText = hideName ? null : format
                .replace("{ImageID}", String.valueOf(index))
                .replace("{X}", String.valueOf(x))
                .replace("{Y}", String.valueOf(y))
                .replace("{Name}", name)
                .replace("{Width}", String.valueOf(width))
                .replace("{Height}", String.valueOf(height))
                .replace("{DitheringType}", dithering.getName())
                .replace("{CreatorName}", creatorName)
                .replace("{CreatorUUID}", creator.toString())
                .replace("{TimeCreated}", manager.mod().formatTime(creationTime));
        List<Component> lore = new ArrayList<>();
        if (nameText != null && !nameText.isBlank()) {
            lore.add(Text.legacyNoItalic(nameText));
        }
        lore.add(Text.legacyNoItalic(Lang.plain("lore_image_id", "#" + index)));
        lore.add(Text.legacyNoItalic(Lang.plain("lore_creator", creatorName)));
        return ItemsX.imageMapItem(mapIds.get(tile), index, tile, null, lore);
    }

    public List<ItemStack> items() {
        List<ItemStack> items = new ArrayList<>(mapIds.size());
        for (int tile = 0; tile < mapIds.size(); tile++) {
            items.add(itemForTile(tile));
        }
        return items;
    }

    public List<MapDecoration> decorations(int tile) {
        List<MapDecoration> decorations = new ArrayList<>();
        if (tile < 0 || tile >= markers.size()) {
            return decorations;
        }
        for (Marker marker : markers.get(tile).values()) {
            Holder<MapDecorationType> type = manager.markerTypes().holder(marker.type());
            if (type == null) {
                continue;
            }
            Optional<Component> caption = marker.caption() == null || marker.caption().isBlank()
                    ? Optional.empty()
                    : Optional.of(Text.legacy(marker.caption()));
            decorations.add(new MapDecoration(type, marker.x(), marker.y(), marker.rotation(), caption));
        }
        return decorations;
    }

    // ------------------------------------------------------------------
    // Frame placement
    // ------------------------------------------------------------------

    public void fillFrames(List<ItemFrame> frames, int rotation) {
        if (frames.size() != mapIds.size()) {
            throw new IllegalArgumentException("frames size mismatch");
        }
        for (int tile = 0; tile < frames.size(); tile++) {
            ItemFrame frame = frames.get(tile);
            if (!frame.isAlive()) {
                continue;
            }
            frame.setItem(itemForTile(tile), false);
            frame.setRotation(rotation);
            manager.frames().track(frame);
        }
    }

    public void clearFrames(List<ItemFrame> frames) {
        for (ItemFrame frame : frames) {
            if (frame.isAlive()) {
                frame.setItem(ItemStack.EMPTY, false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    public Marker marker(int tile, String name) {
        if (tile < 0 || tile >= markers.size()) {
            return null;
        }
        return markers.get(tile).get(name);
    }

    public boolean addMarker(int tile, String name, Marker marker) {
        if (tile < 0 || tile >= markers.size()) {
            return false;
        }
        markers.get(tile).put(name, marker);
        markUpdated();
        return true;
    }

    public boolean removeMarker(String name) {
        boolean removed = false;
        for (Map<String, Marker> map : markers) {
            removed |= map.remove(name) != null;
        }
        if (removed) {
            markUpdated();
        }
        return removed;
    }

    public void clearMarkers() {
        for (Map<String, Marker> map : markers) {
            map.clear();
        }
        markUpdated();
    }

    // ------------------------------------------------------------------
    // Management
    // ------------------------------------------------------------------

    public boolean canView(ServerPlayer player) {
        return creator.equals(player.getUUID()) || access.containsKey(player.getUUID())
                || PermsCheck.isAdmin(player) || manager.mod().permits(player, com.imageframe.Perms.Node.ADMIN_BYPASS);
    }

    public boolean canEdit(ServerPlayer player) {
        if (creator.equals(player.getUUID())) {
            return true;
        }
        String level = access.get(player.getUUID());
        if (level != null && level.equalsIgnoreCase("edit")) {
            return true;
        }
        return manager.mod().permits(player, com.imageframe.Perms.Node.ADMIN_BYPASS);
    }

    public void setAccess(UUID uuid, String level) {
        if (level == null || level.equalsIgnoreCase("none")) {
            access.remove(uuid);
        } else {
            access.put(uuid, level.toLowerCase(Locale.ROOT));
        }
        markUpdated();
    }

    public void rename(String newName) {
        String old = this.name;
        this.name = newName;
        manager.onRenamed(this, old);
        markUpdated();
    }

    public void setDithering(Palette.Dithering dithering) {
        this.dithering = dithering;
        markUpdated();
    }

    public void markUpdated() {
        version++;
        manager.onImageUpdated(this);
    }

    public void save() {
        manager.storage().saveImage(this);
    }

    public void delete() {
        valid = false;
        manager.remove(this);
    }

    /** Sends a message to the creator of this image (if online), using their language. */
    public void creatorPlayerMessage(String key, Object... args) {
        net.minecraft.server.MinecraftServer server = manager.mod().server();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(creator);
        if (player != null) {
            player.sendSystemMessage(com.imageframe.util.Lang.t(player, key, args));
        }
    }

    private static final class PermsCheck {
        private static boolean isAdmin(ServerPlayer player) {
            return com.imageframe.Perms.isOp(player);
        }
    }

    public CompoundTag toMetadata() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        tag.putString("name", name);
        tag.putString("url", url);
        tag.putInt("width", width);
        tag.putInt("height", height);
        tag.putString("dithering", dithering.getName());
        tag.putString("creator", creator.toString());
        tag.putString("creatorName", creatorName);
        tag.putLong("creationTime", creationTime);
        tag.putString("kind", kind.name());
        int[] ids = new int[mapIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = mapIds.get(i);
        }
        tag.putIntArray("mapIds", ids);
        tag.putBoolean("paused", paused);
        tag.putInt("pausedAt", pausedAt);
        tag.putInt("tickOffset", tickOffset);
        int[] seq = sequence;
        tag.putIntArray("sequence", seq);
        int distinct = distinctCount();
        tag.putInt("distinct", distinct);
        for (int tile = 0; tile < distinct; tile++) {
            // nothing - kept below for clarity
        }
        for (int tile = 0; tile < (animationFakeIds == null ? 0 : animationFakeIds.length); tile++) {
            tag.putIntArray("fakeIds" + tile, animationFakeIds[tile]);
        }
        CompoundTag accessTag = new CompoundTag();
        for (Map.Entry<UUID, String> entry : access.entrySet()) {
            accessTag.putString(entry.getKey().toString(), entry.getValue());
        }
        tag.put("access", accessTag);
        CompoundTag markersTag = new CompoundTag();
        for (int tile = 0; tile < markers.size(); tile++) {
            CompoundTag tileTag = new CompoundTag();
            for (Map.Entry<String, Marker> entry : markers.get(tile).entrySet()) {
                Marker marker = entry.getValue();
                CompoundTag markerTag = new CompoundTag();
                markerTag.putByte("x", marker.x());
                markerTag.putByte("y", marker.y());
                markerTag.putByte("rotation", marker.rotation());
                markerTag.putString("type", marker.type());
                markerTag.putString("caption", marker.caption() == null ? "" : marker.caption());
                tileTag.put(entry.getKey(), markerTag);
            }
            markersTag.put(String.valueOf(tile), tileTag);
        }
        tag.put("markers", markersTag);
        if (overlayBaseMapIds != null) {
            int[] base = new int[overlayBaseMapIds.size()];
            for (int i = 0; i < base.length; i++) {
                base[i] = overlayBaseMapIds.get(i);
            }
            tag.putIntArray("overlayBase", base);
        }
        return tag;
    }

    public static ImageMap fromMetadata(ImageMapManager manager, CompoundTag tag) {
        int index = tag.getIntOr("index", -1);
        String name = tag.getStringOr("name", "");
        String url = tag.getStringOr("url", "");
        int width = tag.getIntOr("width", 1);
        int height = tag.getIntOr("height", 1);
        Palette.Dithering dithering = Palette.Dithering.byName(tag.getStringOr("dithering", "nearest-color"));
        UUID creator;
        try {
            creator = UUID.fromString(tag.getStringOr("creator", new UUID(0, 0).toString()));
        } catch (Exception e) {
            creator = new UUID(0, 0);
        }
        String creatorName = tag.getStringOr("creatorName", "?");
        long creationTime = tag.getLongOr("creationTime", System.currentTimeMillis());
        String kindName = tag.getStringOr("kind", "STATIC");
        Kind kind = "ANIMATED".equals(kindName) ? Kind.ANIMATED : ("OVERLAY".equals(kindName) ? Kind.OVERLAY : Kind.STATIC);
        ImageMap map = new ImageMap(manager, index, name, url, width, height, dithering, creator, creatorName,
                creationTime, kind);
        map.mapIds.clear();
        var ids = tag.getIntArray("mapIds");
        if (ids.isPresent()) {
            for (int id : ids.get()) {
                map.mapIds.add(id);
            }
        }
        map.paused = tag.getBooleanOr("paused", false);
        map.pausedAt = tag.getIntOr("pausedAt", -1);
        map.tickOffset = tag.getIntOr("tickOffset", 0);
        var sequence = tag.getIntArray("sequence");
        sequence.ifPresent(ints -> map.sequence = ints);
        int distinct = tag.getIntOr("distinct", 0);
        if (distinct > 0) {
            int tiles = map.width * map.height;
            int[][] fakeIds = new int[tiles][distinct];
            for (int tile = 0; tile < tiles; tile++) {
                var fakeArray = tag.getIntArray("fakeIds" + tile);
                if (fakeArray.isPresent() && fakeArray.get().length == distinct) {
                    fakeIds[tile] = fakeArray.get();
                } else {
                    java.util.Arrays.fill(fakeIds[tile], -1);
                }
            }
            map.animationFakeIds = fakeIds;
        }
        CompoundTag accessTag = tag.getCompoundOrEmpty("access");
        for (String key : accessTag.keySet()) {
            try {
                map.access.put(UUID.fromString(key), accessTag.getStringOr(key, "view"));
            } catch (Exception ignored) {
            }
        }
        CompoundTag markersTag = tag.getCompoundOrEmpty("markers");
        for (String tileKey : markersTag.keySet()) {
            int tile;
            try {
                tile = Integer.parseInt(tileKey);
            } catch (NumberFormatException e) {
                continue;
            }
            if (tile < 0 || tile >= map.markers.size()) {
                continue;
            }
            CompoundTag tileTag = markersTag.getCompoundOrEmpty(tileKey);
            for (String markerName : tileTag.keySet()) {
                CompoundTag markerTag = tileTag.getCompoundOrEmpty(markerName);
                map.markers.get(tile).put(markerName, new Marker(markerName,
                        markerTag.getByteOr("x", (byte) 0),
                        markerTag.getByteOr("y", (byte) 0),
                        markerTag.getByteOr("rotation", (byte) 0),
                        markerTag.getStringOr("type", "red_marker"),
                        markerTag.getStringOr("caption", "")));
            }
        }
        var overlay = tag.getIntArray("overlayBase");
        if (overlay.isPresent() && overlay.get().length > 0) {
            List<Integer> base = new ArrayList<>();
            for (int id : overlay.get()) {
                base.add(id);
            }
            map.overlayBaseMapIds = base;
        }
        return map;
    }

    private static CompoundTag emptyCompound() {
        return new CompoundTag();
    }

    static {
        // no-op initializer kept for clarity
        emptyCompound();
    }
}
