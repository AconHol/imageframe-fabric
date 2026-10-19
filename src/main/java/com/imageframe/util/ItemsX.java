package com.imageframe.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Item stack helpers. All ImageFrame metadata is stored inside the vanilla
 * {@code minecraft:custom_data} component so that items survive server restarts.
 */
public final class ItemsX {

    public static final String KEY_IMAGE = "ImageFrameImage";
    public static final String KEY_TILE = "ImageFrameTile";
    public static final String KEY_PLACEMENT = "ImageFramePlacement";
    public static final String KEY_YAW = "ImageFrameYaw";
    public static final String KEY_INVISIBLE = "ImageFrameInvisible";

    private ItemsX() {
    }

    public static Integer mapId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        MapId mapId = stack.get(DataComponents.MAP_ID);
        return mapId == null ? null : mapId.id();
    }

    public static CompoundTag customTag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    public static void mergeCustomTag(ItemStack stack, java.util.function.Consumer<CompoundTag> action) {
        CompoundTag tag = customTag(stack);
        if (tag == null) {
            tag = new CompoundTag();
        }
        action.accept(tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static Integer imageIndex(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        if (tag == null || !tag.contains(KEY_IMAGE)) {
            return null;
        }
        return tag.getIntOr(KEY_IMAGE, -1);
    }

    public static int tileIndex(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        if (tag == null) {
            return -1;
        }
        return tag.getIntOr(KEY_TILE, -1);
    }

    public static UUID placement(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        if (tag == null) {
            return null;
        }
        var array = tag.getIntArray(KEY_PLACEMENT);
        if (array.isEmpty() || array.get().length != 4) {
            return null;
        }
        int[] value = array.get();
        long most = ((long) value[0] << 32) | (value[1] & 0xFFFFFFFFL);
        long least = ((long) value[2] << 32) | (value[3] & 0xFFFFFFFFL);
        return new UUID(most, least);
    }

    public static float placementYaw(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        return tag == null ? 0F : tag.getFloatOr(KEY_YAW, 0F);
    }

    public static void setPlacement(ItemStack stack, UUID uuid, float yaw) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        int[] array = new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
        mergeCustomTag(stack, tag -> {
            tag.putIntArray(KEY_PLACEMENT, array);
            tag.putFloat(KEY_YAW, yaw);
        });
    }

    public static ItemStack filledMap(int mapId) {
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.set(DataComponents.MAP_ID, new MapId(mapId));
        return stack;
    }

    public static ItemStack imageMapItem(int mapId, int imageIndex, int tile, Component name, List<Component> lore) {
        ItemStack stack = filledMap(mapId);
        mergeCustomTag(stack, tag -> {
            tag.putInt(KEY_IMAGE, imageIndex);
            tag.putInt(KEY_TILE, tile);
        });
        if (name != null) {
            stack.set(DataComponents.CUSTOM_NAME, name);
        }
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(lore)));
        }
        return stack;
    }

    public static boolean isImageMapItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.FILLED_MAP) && imageIndex(stack) != null;
    }

    public static boolean isInvisibleFrameItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!stack.is(Items.ITEM_FRAME) && !stack.is(Items.GLOW_ITEM_FRAME)) {
            return false;
        }
        CompoundTag tag = customTag(stack);
        return tag != null && tag.getBooleanOr(KEY_INVISIBLE, false);
    }

    public static boolean isGlowFrameItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.GLOW_ITEM_FRAME);
    }

    public static ItemStack invisibleFrameItem(boolean glow, int count) {
        ItemStack stack = new ItemStack(glow ? Items.GLOW_ITEM_FRAME : Items.ITEM_FRAME, Math.max(1, count));
        mergeCustomTag(stack, tag -> tag.putBoolean(KEY_INVISIBLE, true));
        Component name = Text.legacyNoItalic(Lang.plain("invisible_frame_name", new Object[0]));
        stack.set(DataComponents.CUSTOM_NAME, name);
        List<Component> lore = new ArrayList<>();
        lore.add(Text.legacyNoItalic("&7" + Lang.plain("invisible_frame_lore")));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    public static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copy();
        Inventory inventory = player.getInventory();
        boolean added = inventory.add(copy);
        if (!added || !copy.isEmpty()) {
            player.drop(copy, false);
        }
        player.containerMenu.broadcastChanges();
    }

    public static int countEmptyMaps(ServerPlayer player) {
        int count = 0;
        var items = player.getInventory().getNonEquipmentItems();
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && stack.is(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Removes up to {@code needed} empty maps, returns how many were removed. */
    public static int removeEmptyMaps(ServerPlayer player, int needed) {
        int removed = 0;
        var items = player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size() && removed < needed; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !stack.is(Items.MAP)) {
                continue;
            }
            int take = Math.min(needed - removed, stack.getCount());
            removed += take;
            stack.shrink(take);
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
        return removed;
    }

    public static List<ItemStack> allInventoryItems(ServerPlayer player) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            list.add(stack);
        }
        list.add(player.getMainHandItem());
        list.add(player.getOffhandItem());
        return list;
    }
}
