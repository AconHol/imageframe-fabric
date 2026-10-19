package com.imageframe.frame;

import com.imageframe.ImageFrameMod;
import com.imageframe.util.Frames;
import com.imageframe.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles the /imageframe select tool: right-click two corners of an item frame wall.
 */
public final class SelectionManager {

    private final ImageFrameMod mod;
    private final Set<UUID> inSelection = new HashSet<>();
    private final Map<UUID, ItemFrame> firstCorners = new HashMap<>();
    private final Map<UUID, Frames.Selection> confirmed = new HashMap<>();

    public SelectionManager(ImageFrameMod mod) {
        this.mod = mod;
    }

    public boolean toggle(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (inSelection.remove(uuid)) {
            firstCorners.remove(uuid);
            return false;
        }
        inSelection.add(uuid);
        firstCorners.remove(uuid);
        confirmed.remove(uuid);
        return true;
    }

    public boolean isInSelection(ServerPlayer player) {
        return inSelection.contains(player.getUUID());
    }

    public boolean isInSelection(UUID uuid) {
        return inSelection.contains(uuid);
    }

    public void clearConfirmed(ServerPlayer player) {
        confirmed.remove(player.getUUID());
        firstCorners.remove(player.getUUID());
        inSelection.remove(player.getUUID());
    }

    public Frames.Selection confirmed(ServerPlayer player) {
        return confirmed.get(player.getUUID());
    }

    public void onQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        inSelection.remove(uuid);
        firstCorners.remove(uuid);
        confirmed.remove(uuid);
    }

    /** @return true when the interaction was consumed by the selection tool. */
    public boolean handleInteract(ServerPlayer player, ItemFrame frame) {
        UUID uuid = player.getUUID();
        if (!inSelection.contains(uuid)) {
            return false;
        }
        ItemFrame first = firstCorners.remove(uuid);
        if (first == null) {
            firstCorners.put(uuid, frame);
            player.sendSystemMessage(Lang.t("selection_corner1"));
            return true;
        }
        player.sendSystemMessage(Lang.t("selection_corner2"));
        if (!(frame.level() instanceof ServerLevel level)) {
            return true;
        }
        float yaw = player.getYRot();
        Frames.Selection selection = Frames.select(level, first, frame, yaw);
        inSelection.remove(uuid);
        if (selection == null) {
            player.sendSystemMessage(Lang.t("selection_invalid"));
            return true;
        }
        if ((long) selection.width() * selection.height() > mod.config().maxSize) {
            player.sendSystemMessage(Lang.t("selection_oversize", mod.config().maxSize));
            return true;
        }
        confirmed.put(uuid, selection);
        player.sendSystemMessage(Lang.t("selection_success", selection.width(), selection.height()));
        return true;
    }

    public void directSelection(ServerPlayer player, ServerLevel level, BlockPos pos1, BlockPos pos2, float yaw) {
        ItemFrame first = findFrame(level, pos1, null);
        if (first == null) {
            player.sendSystemMessage(Lang.t("selection_invalid"));
            return;
        }
        ItemFrame second = findFrame(level, pos2, first.getDirection());
        if (second == null) {
            player.sendSystemMessage(Lang.t("selection_invalid"));
            return;
        }
        Frames.Selection selection = Frames.select(level, first, second, yaw);
        if (selection == null) {
            player.sendSystemMessage(Lang.t("selection_invalid"));
            return;
        }
        if ((long) selection.width() * selection.height() > mod.config().maxSize) {
            player.sendSystemMessage(Lang.t("selection_oversize", mod.config().maxSize));
            return;
        }
        confirmed.put(player.getUUID(), selection);
        inSelection.remove(player.getUUID());
        player.sendSystemMessage(Lang.t("selection_success", selection.width(), selection.height()));
    }

    private ItemFrame findFrame(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing) {
        var frames = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ItemFrame.class),
                new net.minecraft.world.phys.AABB(pos).inflate(1.0),
                frame -> frame.isAlive() && Frames.position(frame).equals(pos)
                        && (facing == null || frame.getDirection() == facing));
        return frames.isEmpty() ? null : frames.get(0);
    }
}
