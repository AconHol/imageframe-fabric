package com.imageframe.frame;

import com.imageframe.ImageFrameMod;
import com.imageframe.image.ImageMap;
import com.imageframe.util.Frames;
import com.imageframe.util.ItemsX;
import com.imageframe.util.Lang;
import com.imageframe.util.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "combined map" item: one paper item that places a whole multi-part image on a
 * wall of item frames and is returned when any part is broken.
 */
public final class CombinedMapManager {

    private final ImageFrameMod mod;

    public CombinedMapManager(ImageFrameMod mod) {
        this.mod = mod;
    }

    public boolean isCombined(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.PAPER) && ItemsX.imageIndex(stack) != null;
    }

    public ImageMap imageFor(ItemStack stack) {
        Integer index = ItemsX.imageIndex(stack);
        return index == null ? null : mod.manager().byIndex(index);
    }

    public ItemStack combinedItem(ImageMap image) {
        ItemStack stack = new ItemStack(Items.PAPER);
        ItemsX.mergeCustomTag(stack, tag -> tag.putInt(ItemsX.KEY_IMAGE, image.index()));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Text.legacyNoItalic(
                Lang.plain("combined_name", image.name(), image.width(), image.height())));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.legacyNoItalic(Lang.plain("combined_lore1", image.width(), image.height())));
        lore.add(Text.legacyNoItalic(Lang.plain("combined_lore2")));
        lore.add(Text.legacyNoItalic(Lang.plain("combined_lore3", image.index())));
        lore.add(Text.legacyNoItalic(Lang.plain("combined_lore4", image.creatorName())));
        stack.set(net.minecraft.core.component.DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /** @return true when the interaction was consumed. */
    public boolean place(ServerPlayer player, ItemFrame clicked) {
        if (!(clicked.level() instanceof ServerLevel level)) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (!isCombined(held)) {
            return false;
        }
        ImageMap image = imageFor(held);
        if (image == null || !image.valid()) {
            player.sendSystemMessage(Lang.t("invalid_image_map"));
            return true;
        }
        if (!image.canEdit(player)) {
            player.sendSystemMessage(Lang.t("no_permission"));
            return true;
        }
        float yaw = player.getYRot();
        Frames.Selection selection = Frames.findPlacement(level, clicked, image.width(), image.height(), yaw);
        if (selection == null) {
            player.sendSystemMessage(Lang.t("not_enough_space"));
            return true;
        }
        for (ItemFrame frame : selection.frames()) {
            if (!frame.getItem().isEmpty()) {
                player.sendSystemMessage(Lang.t("item_frame_occupied"));
                return true;
            }
        }
        UUID placement = UUID.randomUUID();
        for (int tile = 0; tile < selection.frames().size(); tile++) {
            ItemFrame frame = selection.frames().get(tile);
            ItemStack item = image.itemForTile(tile);
            ItemsX.setPlacement(item, placement, yaw);
            frame.setItem(item, false);
            frame.setRotation(selection.rotation());
            mod.frames().track(frame);
        }
        held.shrink(1);
        player.containerMenu.broadcastChanges();
        level.playSound(null, clicked.blockPosition(), net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        player.sendSystemMessage(Lang.t("gave_combined"));
        return true;
    }

    /** Handles attacking a frame that belongs to a combined map placement. */
    public boolean onAttack(ServerPlayer player, ItemFrame clicked) {
        ItemStack stack = clicked.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        Integer imageIndex = ItemsX.imageIndex(stack);
        UUID placement = ItemsX.placement(stack);
        if (imageIndex == null || placement == null) {
            return false;
        }
        if (!(clicked.level() instanceof ServerLevel level)) {
            return false;
        }
        ImageMap image = mod.manager().byIndex(imageIndex);
        if (image == null) {
            return false;
        }
        List<ItemFrame> frames = Frames.findPlacementFrames(level, clicked, imageIndex, placement,
                image.width(), image.height());
        if (frames.size() != image.tileCount()) {
            return false;
        }
        if (!image.canEdit(player)) {
            player.sendSystemMessage(Lang.t("no_permission"));
            return true;
        }
        for (ItemFrame frame : frames) {
            if (frame == clicked) {
                continue;
            }
            frame.setItem(ItemStack.EMPTY, false);
        }
        clicked.setItem(combinedItem(image), false);
        return false;
    }
}
