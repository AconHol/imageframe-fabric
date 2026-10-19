package com.imageframe.image;

import com.imageframe.ImageFrameMod;
import com.imageframe.util.ItemsX;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of loaded item frames that currently display one of our maps.
 */
public final class FrameTracker {

    public record TrackedFrame(UUID uuid, ServerLevel level, ItemFrame frame, int imageIndex, int tile) {
    }

    private final ImageFrameMod mod;
    private final Map<UUID, TrackedFrame> frames = new HashMap<>();
    private final Map<UUID, ItemFrame> unresolved = new HashMap<>();
    private long lastMaintenance;

    public FrameTracker(ImageFrameMod mod) {
        this.mod = mod;
    }

    public void track(ItemFrame frame) {
        if (frame == null || !frame.isAlive() || !(frame.level() instanceof ServerLevel level)) {
            return;
        }
        Integer mapId = ItemsX.mapId(frame.getItem());
        if (mapId == null) {
            frames.remove(frame.getUUID());
            unresolved.remove(frame.getUUID());
            return;
        }
        ImageMap image = mod.manager().byBaseMapId(mapId);
        if (image == null || !image.valid()) {
            frames.remove(frame.getUUID());
            unresolved.put(frame.getUUID(), frame);
            return;
        }
        int tile = image.tileForMapId(mapId);
        if (tile < 0) {
            frames.remove(frame.getUUID());
            unresolved.put(frame.getUUID(), frame);
            return;
        }
        unresolved.remove(frame.getUUID());
        frames.put(frame.getUUID(), new TrackedFrame(frame.getUUID(), level, frame, image.index(), tile));
    }

    public void untrack(Entity entity) {
        if (entity != null) {
            frames.remove(entity.getUUID());
            unresolved.remove(entity.getUUID());
        }
    }

    public void onEntityLoad(Entity entity, ServerLevel level) {
        if (entity instanceof ItemFrame frame) {
            track(frame);
        }
    }

    public void onEntityUnload(Entity entity) {
        untrack(entity);
    }

    public void tick() {
        long now = mod.manager().tickCounter();
        if (now - lastMaintenance < 40) {
            return;
        }
        lastMaintenance = now;
        revalidate();
    }

    public void revalidate() {
        List<ItemFrame> tracked = new ArrayList<>();
        for (TrackedFrame frame : new ArrayList<>(frames.values())) {
            tracked.add(frame.frame());
        }
        for (ItemFrame frame : tracked) {
            if (!frame.isAlive()) {
                frames.remove(frame.getUUID());
                continue;
            }
            track(frame);
        }
        for (ItemFrame frame : new ArrayList<>(unresolved.values())) {
            if (!frame.isAlive()) {
                unresolved.remove(frame.getUUID());
                continue;
            }
            track(frame);
        }
    }

    public List<TrackedFrame> framesForImage(int imageIndex) {
        List<TrackedFrame> list = new ArrayList<>();
        for (TrackedFrame frame : frames.values()) {
            if (frame.imageIndex() == imageIndex && frame.frame().isAlive()) {
                list.add(frame);
            }
        }
        return list;
    }

    public List<TrackedFrame> all() {
        return new ArrayList<>(frames.values());
    }

    public void removeForImage(int imageIndex) {
        frames.values().removeIf(frame -> frame.imageIndex() == imageIndex);
        unresolved.values().removeIf(frame -> {
            Integer mapId = ItemsX.mapId(frame.getItem());
            if (mapId == null) {
                return true;
            }
            ImageMap image = mod.manager().byBaseMapId(mapId);
            return image != null && image.index() == imageIndex;
        });
    }

    public TrackedFrame byFrame(ItemFrame frame) {
        return frames.get(frame.getUUID());
    }

    public void clear() {
        frames.clear();
        unresolved.clear();
    }
}
