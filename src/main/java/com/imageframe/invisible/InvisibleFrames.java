package com.imageframe.invisible;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import com.imageframe.util.ItemsX;
import com.imageframe.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Survival friendly invisible item frames, plus conversion of dropped item frames by
 * splashing them with potions of invisibility.
 */
public final class InvisibleFrames {

    public record FrameKey(String dimension, int x, int y, int z) {
    }

    private static final Gson GSON = new GsonBuilder().create();

    private final ImageFrameMod mod;
    private final Set<FrameKey> invisibleFrames = new HashSet<>();
    private final Map<UUID, AbstractThrownPotion> trackedPotions = new HashMap<>();

    public InvisibleFrames(ImageFrameMod mod) {
        this.mod = mod;
    }

    private Path file() {
        return mod.storage().root().resolve("invisible_frames.json");
    }

    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> encoded = GSON.fromJson(reader, new TypeToken<List<String>>() {
            }.getType());
            if (encoded != null) {
                for (String entry : encoded) {
                    FrameKey key = decode(entry);
                    if (key != null) {
                        invisibleFrames.add(key);
                    }
                }
            }
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.warn("Unable to read invisible item frames", e);
        }
    }

    private static FrameKey decode(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new FrameKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encode(FrameKey key) {
        return key.dimension() + "|" + key.x() + "|" + key.y() + "|" + key.z();
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            List<String> encoded = new ArrayList<>();
            for (FrameKey key : invisibleFrames) {
                encoded.add(encode(key));
            }
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(encoded, writer);
            }
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.warn("Unable to save invisible item frames", e);
        }
    }

    public ItemStack item(boolean glow, int count) {
        return ItemsX.invisibleFrameItem(glow, count);
    }

    public void onEntityLoad(Entity entity, ServerLevel level) {
        if (entity instanceof ItemFrame frame) {
            FrameKey key = key(frame);
            if (invisibleFrames.contains(key)) {
                applyState(frame);
            }
        } else if (entity instanceof AbstractThrownPotion potion) {
            trackedPotions.put(potion.getUUID(), potion);
        }
    }

    public void onEntityUnload(Entity entity) {
        if (entity != null) {
            trackedPotions.remove(entity.getUUID());
        }
    }

    private FrameKey key(ItemFrame frame) {
        BlockPos pos = com.imageframe.util.Frames.position(frame);
        return new FrameKey(frame.level().dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    public void applyState(ItemFrame frame) {
        boolean empty = frame.getItem().isEmpty();
        if (empty) {
            frame.setInvisible(false);
            frame.setGlowingTag(mod.config().invisibleFrame.glowEmptyFrames);
        } else {
            frame.setInvisible(true);
            frame.setGlowingTag(false);
        }
    }

    public void tick() {
        long tick = mod.tickCounter();
        if (!trackedPotions.isEmpty()) {
            List<UUID> finished = new ArrayList<>();
            for (Map.Entry<UUID, AbstractThrownPotion> entry : trackedPotions.entrySet()) {
                AbstractThrownPotion potion = entry.getValue();
                if (potion.isRemoved() || !potion.isAlive()) {
                    finished.add(entry.getKey());
                    convert(potion);
                }
            }
            for (UUID uuid : finished) {
                trackedPotions.remove(uuid);
            }
        }
        if (tick % 20 == 0) {
            refreshFrames();
        }
    }

    private void refreshFrames() {
        if (invisibleFrames.isEmpty()) {
            return;
        }
        List<FrameKey> keys = new ArrayList<>(invisibleFrames);
        for (FrameKey key : keys) {
            ServerLevel level = mod.level(key.dimension());
            if (level == null) {
                continue;
            }
            BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
            if (!level.isLoaded(pos)) {
                continue;
            }
            List<ItemFrame> frames = level.getEntities(EntityTypeTest.forClass(ItemFrame.class),
                    new AABB(pos).inflate(2.0), frame -> FramesKeyMatch(frame, key));
            if (frames.isEmpty()) {
                invisibleFrames.remove(key);
                continue;
            }
            for (ItemFrame frame : frames) {
                applyState(frame);
            }
        }
    }

    private boolean FramesKeyMatch(ItemFrame frame, FrameKey key) {
        return key.equals(key(frame));
    }

    private void convert(AbstractThrownPotion potion) {
        int remaining = mod.config().invisibleFrame.maxConversionsPerSplash;
        if (remaining == 0) {
            return;
        }
        ItemStack potionItem = potion.getItem();
        PotionContents contents = potionItem.isEmpty() ? null : potionItem.get(DataComponents.POTION_CONTENTS);
        boolean invisibility = false;
        if (contents != null) {
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (effect.getEffect().value() == MobEffects.INVISIBILITY.value()) {
                    invisibility = true;
                    break;
                }
            }
        }
        if (!invisibility) {
            return;
        }
        if (!(potion.level() instanceof ServerLevel level)) {
            return;
        }
        AABB box = potion.getBoundingBox().inflate(4.0, 2.0, 4.0);
        List<ItemEntity> items = level.getEntities(EntityTypeTest.forClass(ItemEntity.class), box, entity -> true);
        int converted = 0;
        for (ItemEntity item : items) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = item.getItem();
            if (stack.isEmpty()
                    || (!stack.is(net.minecraft.world.item.Items.ITEM_FRAME)
                    && !stack.is(net.minecraft.world.item.Items.GLOW_ITEM_FRAME))
                    || ItemsX.isInvisibleFrameItem(stack)) {
                continue;
            }
            if (item.position().distanceToSqr(potion.position()) >= 16.0) {
                continue;
            }
            boolean glow = stack.is(net.minecraft.world.item.Items.GLOW_ITEM_FRAME);
            int amount = stack.getCount();
            int take = remaining < 0 ? amount : Math.min(amount, remaining);
            ItemStack convertedStack = ItemsX.invisibleFrameItem(glow, take);
            if (take >= amount) {
                item.setItem(convertedStack);
            } else {
                stack.shrink(take);
                item.setItem(stack);
                ItemEntity extra = new ItemEntity(level, item.getX(), item.getY(), item.getZ(), convertedStack);
                extra.setDeltaMovement(item.getDeltaMovement());
                level.addFreshEntity(extra);
            }
            converted += take;
            if (remaining > 0) {
                remaining -= take;
            }
            level.playSound(null, item.blockPosition(), net.minecraft.sounds.SoundEvents.ENCHANTMENT_TABLE_USE,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        if (converted > 0) {
            ImageFrameFabric.LOGGER.debug("Converted {} item frame(s) to invisible item frames", converted);
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(potion) < 64.0) {
                    player.sendSystemMessage(Lang.t("potion_converted", converted));
                }
            }
        }
    }

    /** Handles placing an invisible item frame item on a block. */
    public boolean onUseBlock(ServerPlayer player, ItemStack stack, Level level, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !ItemsX.isInvisibleFrameItem(stack)) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos clicked = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos pos = clicked.relative(face);
        if (serverLevel.getBlockState(clicked).isFaceSturdy(serverLevel, clicked, face)
                == false) {
            return false;
        }
        boolean glow = ItemsX.isGlowFrameItem(stack);
        ItemFrame frame = glow ? new GlowItemFrame(serverLevel, pos, face) : new ItemFrame(serverLevel, pos, face);
        if (!frame.survives()) {
            return false;
        }
        List<ItemFrame> existing = serverLevel.getEntities(EntityTypeTest.forClass(ItemFrame.class),
                frame.getBoundingBox().inflate(0.01), entity -> entity.isAlive());
        if (!existing.isEmpty()) {
            return false;
        }
        if (!serverLevel.addFreshEntity(frame)) {
            return false;
        }
        frame.playPlacementSound();
        invisibleFrames.add(key(frame));
        applyState(frame);
        stack.shrink(1);
        player.containerMenu.broadcastChanges();
        save();
        return true;
    }

    public void onLeftClickFrame(ServerPlayer player, ItemFrame frame) {
        FrameKey key = key(frame);
        if (invisibleFrames.remove(key)) {
            save();
        }
    }

    public void give(ServerPlayer target, boolean glow, int count) {
        int remaining = Math.max(1, count);
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            ItemsX.give(target, ItemsX.invisibleFrameItem(glow, amount));
        }
    }

    public Set<FrameKey> keys() {
        return invisibleFrames;
    }
}
