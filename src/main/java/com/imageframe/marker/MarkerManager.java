package com.imageframe.marker;

import com.imageframe.ImageFrameMod;
import com.imageframe.Perms;
import com.imageframe.image.ImageMap;
import com.imageframe.util.Frames;
import com.imageframe.util.ItemsX;
import com.imageframe.util.Lang;
import com.imageframe.util.Text;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live marker placement: after {@code /imageframe marker add} the crosshair position on
 * an image map is tracked and confirmed with a right click.
 */
public final class MarkerManager {

    public static final class EditData {
        final ImageMap image;
        final String name;
        final String type;
        final String caption;
        final byte rotation;
        int tile = -1;
        int x;
        int y;
        boolean valid;

        EditData(ImageMap image, String name, String type, String caption, byte rotation) {
            this.image = image;
            this.name = name;
            this.type = type;
            this.caption = caption;
            this.rotation = rotation;
        }
    }

    private final ImageFrameMod mod;
    private final Map<UUID, EditData> editing = new HashMap<>();

    public MarkerManager(ImageFrameMod mod) {
        this.mod = mod;
    }

    public boolean isEditing(ServerPlayer player) {
        return editing.containsKey(player.getUUID());
    }

    public void startEditing(ServerPlayer player, ImageMap image, String name, String type, byte rotation, String caption) {
        editing.put(player.getUUID(), new EditData(image, name, type, caption, rotation));
        player.sendSystemMessage(Lang.t("marker_add_begin", name, image.name()));
        if (!mod.manager().markerTypes().rendersOnFrame(type)) {
            player.sendSystemMessage(Lang.t("marker_not_render"));
        }
    }

    public void cancel(ServerPlayer player) {
        if (editing.remove(player.getUUID()) != null) {
            player.sendSystemMessage(Lang.t("marker_cancelled"));
        }
    }

    public void onQuit(ServerPlayer player) {
        editing.remove(player.getUUID());
    }

    /** @return true if the right click confirmed a marker. */
    public boolean confirm(ServerPlayer player, ItemFrame frame) {
        EditData data = editing.remove(player.getUUID());
        if (data == null) {
            return false;
        }
        Integer imageIndex = ItemsX.imageIndex(frame.getItem());
        if (imageIndex == null || imageIndex != data.image.index()) {
            player.sendSystemMessage(Lang.t("marker_cancelled"));
            return true;
        }
        if (data.tile < 0 || !data.valid) {
            player.sendSystemMessage(Lang.t("marker_cancelled"));
            return true;
        }
        if (!mod.permits(player, Perms.Node.MARKER_UNLIMITED)
                && data.image.markers().get(data.tile).size() >= mod.config().mapMarkerLimit) {
            player.sendSystemMessage(Lang.t("marker_limit", mod.config().mapMarkerLimit));
            return true;
        }
        data.image.markers().get(data.tile).put(data.name,
                new ImageMap.Marker(data.name, (byte) data.x, (byte) data.y, data.rotation, data.type,
                        data.caption == null ? "" : data.caption));
        data.image.save();
        data.image.markUpdated();
        sendMapToNearby(data.image, data.tile, 64.0);
        player.sendSystemMessage(Lang.t("marker_added"));
        return true;
    }

    public void tick() {
        if (editing.isEmpty()) {
            return;
        }
        var server = mod.server();
        if (server == null) {
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, EditData> entry : editing.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                finished.add(entry.getKey());
                continue;
            }
            EditData data = entry.getValue();
            if (!data.image.valid()) {
                finished.add(entry.getKey());
                continue;
            }
            var hit = Frames.rayTrace(player.level(), player.getEyePosition(), player.getViewVector(1.0F), 5.0);
            if (hit == null) {
                continue;
            }
            ItemStack stack = hit.frame().getItem();
            Integer imageIndex = ItemsX.imageIndex(stack);
            if (imageIndex == null || imageIndex != data.image.index()) {
                continue;
            }
            int tile = ItemsX.tileIndex(stack);
            if (tile < 0 || tile >= data.image.tileCount()) {
                continue;
            }
            int[] pixel = Frames.pixel(hit.frame(), hit.point());
            if (pixel == null) {
                continue;
            }
            data.tile = tile;
            data.x = pixel[0];
            data.y = pixel[1];
            data.valid = true;
            sendPreview(player, data, hit.frame());
        }
        for (UUID uuid : finished) {
            editing.remove(uuid);
        }
    }

    private void sendPreview(ServerPlayer player, EditData data, ItemFrame frame) {
        List<MapDecoration> decorations = new ArrayList<>(data.image.decorations(data.tile));
        Holder<MapDecorationType> type = mod.manager().markerTypes().holder(data.type);
        if (type != null) {
            Optional<Component> caption = data.caption == null || data.caption.isBlank()
                    ? Optional.empty() : Optional.of(Text.legacy(data.caption));
            decorations.add(new MapDecoration(type, (byte) data.x, (byte) data.y, data.rotation, caption));
        }
        byte[] colors = data.image.colorsFor(data.tile, data.image.currentTick());
        int mapId = data.image.mapIdForTile(data.tile);
        mod.sender().send(player, mapId, colors, decorations);
    }

    public void sendMapToNearby(ImageMap image, int tile, double distance) {
        int mapId = image.mapIdForTile(tile);
        byte[] colors = image.colorsFor(tile, image.currentTick());
        List<MapDecoration> decorations = image.decorations(tile);
        for (var player : mod.server().getPlayerList().getPlayers()) {
            if (player.level() == null) {
                continue;
            }
            mod.sender().send(player, mapId, colors, decorations);
        }
    }
}
