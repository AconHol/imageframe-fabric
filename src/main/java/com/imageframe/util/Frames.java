package com.imageframe.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Item frame geometry: rectangle selection, ordering, rotations, combined map
 * placement and map-pixel resolution for markers.
 */
public final class Frames {

    public record Selection(List<ItemFrame> frames, int width, int height, int rotation) {
    }

    /** Position of the block the frame occupies (the frame hangs in front of its attached block). */
    public static BlockPos position(ItemFrame frame) {
        return frame.blockPosition().relative(frame.getDirection());
    }

    public static Vec3 up(ItemFrame frame, float yaw) {
        Direction facing = frame.getDirection();
        if (facing.getAxis().isHorizontal()) {
            return new Vec3(0, 1, 0);
        }
        boolean ceiling = facing == Direction.DOWN;
        return Direction.fromYRot(yaw + (ceiling ? 180F : 0F)).getUnitVec3();
    }

    public static Vec3 left(ItemFrame frame, float yaw) {
        Direction facing = frame.getDirection();
        Vec3 up = up(frame, yaw);
        if (facing.getAxis().isHorizontal()) {
            Vec3 view = new Vec3(-facing.getStepX(), 0, -facing.getStepZ()).normalize();
            return up.cross(view);
        }
        boolean ceiling = facing == Direction.DOWN;
        return rotateY(up, ceiling ? -90 : 90);
    }

    public static Vec3 rotateY(Vec3 vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = vector.x * cos + vector.z * sin;
        double z = -vector.x * sin + vector.z * cos;
        return new Vec3(x, vector.y, z);
    }

    /** Rotation (0,2,4,6 = 0/90/180/270 degrees) that should be applied to the item frame. */
    public static int rotationFor(ItemFrame frame, float yaw) {
        Direction facing = frame.getDirection();
        if (facing.getAxis().isHorizontal()) {
            return 0;
        }
        boolean ceiling = facing == Direction.DOWN;
        int steps = Math.floorMod(Math.round((yaw + (ceiling ? 180F : 0F)) / 90F), 4);
        return steps * 2;
    }

    public static Selection select(ServerLevel level, ItemFrame first, ItemFrame second, float yaw) {
        if (first == null || second == null) {
            return null;
        }
        Direction facing = first.getDirection();
        if (facing != second.getDirection()) {
            return null;
        }
        if (first.getUUID().equals(second.getUUID())) {
            return new Selection(List.of(first), 1, 1, rotationFor(first, yaw));
        }
        Vec3 up = up(first, yaw);
        Vec3 left = left(first, yaw);
        BlockPos posA = position(first);
        BlockPos posB = position(second);
        Vec3 delta = new Vec3(posB.getX() - posA.getX(), posB.getY() - posA.getY(), posB.getZ() - posA.getZ());
        int deltaUp = (int) Math.round(delta.dot(up));
        int deltaLeft = (int) Math.round(delta.dot(left));
        Vec3 remainder = delta.subtract(up.scale(deltaUp)).subtract(left.scale(deltaLeft));
        if (remainder.lengthSqr() > 0.0001) {
            return null;
        }
        int width = Math.abs(deltaLeft) + 1;
        int height = Math.abs(deltaUp) + 1;
        int aUp = (int) Math.round(new Vec3(posA.getX(), posA.getY(), posA.getZ()).dot(up));
        int aLeft = (int) Math.round(new Vec3(posA.getX(), posA.getY(), posA.getZ()).dot(left));
        int maxUp = Math.max(aUp, aUp + deltaUp);
        int maxLeft = Math.max(aLeft, aLeft + deltaLeft);
        Vec3 topLeft = up.scale(maxUp).add(left.scale(maxLeft));
        BlockPos topLeftPos = new BlockPos((int) Math.round(topLeft.x), (int) Math.round(topLeft.y), (int) Math.round(topLeft.z));
        Map<BlockPos, ItemFrame> available = collect(level, topLeftPos, up, left, width, height, facing);
        List<ItemFrame> ordered = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                Vec3 target = new Vec3(topLeftPos.getX(), topLeftPos.getY(), topLeftPos.getZ())
                        .subtract(up.scale(row)).subtract(left.scale(column));
                BlockPos pos = new BlockPos((int) Math.round(target.x), (int) Math.round(target.y), (int) Math.round(target.z));
                ItemFrame frame = available.get(pos);
                if (frame == null) {
                    return null;
                }
                ordered.add(frame);
            }
        }
        return new Selection(ordered, width, height, rotationFor(first, yaw));
    }

    private static Map<BlockPos, ItemFrame> collect(ServerLevel level, BlockPos topLeft, Vec3 up, Vec3 left,
                                                    int width, int height, Direction facing) {
        Vec3 cornerA = new Vec3(topLeft.getX(), topLeft.getY(), topLeft.getZ());
        Vec3 cornerB = cornerA.subtract(up.scale(height - 1)).subtract(left.scale(width - 1));
        double minX = Math.min(cornerA.x, cornerB.x) - 1.5;
        double minY = Math.min(cornerA.y, cornerB.y) - 1.5;
        double minZ = Math.min(cornerA.z, cornerB.z) - 1.5;
        double maxX = Math.max(cornerA.x, cornerB.x) + 1.5;
        double maxY = Math.max(cornerA.y, cornerB.y) + 1.5;
        double maxZ = Math.max(cornerA.z, cornerB.z) + 1.5;
        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        List<ItemFrame> frames = level.getEntities(EntityTypeTest.forClass(ItemFrame.class), box,
                frame -> frame.getDirection() == facing && frame.isAlive());
        Map<BlockPos, ItemFrame> map = new HashMap<>();
        for (ItemFrame frame : frames) {
            map.put(position(frame), frame);
        }
        return map;
    }

    /**
     * Finds the item frames a combined map item should fill when clicked on {@code clicked}.
     * The clicked frame is preferred as the top-left corner, but other corners are tried too.
     */
    public static Selection findPlacement(ServerLevel level, ItemFrame clicked, int width, int height, float yaw) {
        Vec3 up = up(clicked, yaw);
        Vec3 left = left(clicked, yaw);
        BlockPos origin = position(clicked);
        List<BlockPos> offsets = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                Vec3 offset = up.scale(row).add(left.scale(column));
                offsets.add(origin.offset((int) Math.round(offset.x), (int) Math.round(offset.y), (int) Math.round(offset.z)));
            }
        }
        offsets.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        Map<BlockPos, ItemFrame> nearby = collect(level, origin, up, left, Math.max(width, height) + 2,
                Math.max(width, height) + 2, clicked.getDirection());
        for (BlockPos candidate : offsets) {
            Vec3 oppositeVec = new Vec3(candidate.getX(), candidate.getY(), candidate.getZ())
                    .subtract(up.scale(height - 1)).subtract(left.scale(width - 1));
            BlockPos opposite = new BlockPos((int) Math.round(oppositeVec.x), (int) Math.round(oppositeVec.y),
                    (int) Math.round(oppositeVec.z));
            ItemFrame first = nearby.get(candidate);
            ItemFrame second = nearby.get(opposite);
            if (first == null || second == null) {
                continue;
            }
            Selection selection = select(level, first, second, yaw);
            if (selection == null || selection.width != width || selection.height != height) {
                continue;
            }
            boolean empty = true;
            for (ItemFrame frame : selection.frames) {
                if (!frame.getItem().isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return selection;
            }
        }
        return null;
    }

    /** All frames belonging to the same combined-map placement around {@code clicked}. */
    public static List<ItemFrame> findPlacementFrames(ServerLevel level, ItemFrame clicked, int imageIndex,
                                                      UUID placement, int width, int height) {
        BlockPos origin = position(clicked);
        int radius = Math.max(width, height) + 2;
        AABB box = new AABB(origin.getX() - radius, origin.getY() - radius, origin.getZ() - radius,
                origin.getX() + radius, origin.getY() + radius, origin.getZ() + radius);
        List<ItemFrame> frames = level.getEntities(EntityTypeTest.forClass(ItemFrame.class), box,
                frame -> frame.getDirection() == clicked.getDirection() && frame.isAlive());
        List<ItemFrame> matches = new ArrayList<>();
        for (ItemFrame frame : frames) {
            var stack = frame.getItem();
            Integer index = ItemsX.imageIndex(stack);
            UUID framePlacement = ItemsX.placement(stack);
            if (index != null && index == imageIndex && placement != null && placement.equals(framePlacement)) {
                matches.add(frame);
            }
        }
        return matches;
    }

    /**
     * Converts a ray/frame intersection point into map marker coordinates (-128..127).
     * Returns null for floor/ceiling frames.
     */
    public static int[] pixel(ItemFrame frame, Vec3 hit) {
        Direction facing = frame.getDirection();
        if (!facing.getAxis().isHorizontal()) {
            return null;
        }
        Vec3 view = new Vec3(-facing.getStepX(), 0, -facing.getStepZ()).normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = view.cross(up);
        Vec3 offset = hit.subtract(frame.position());
        offset = offset.subtract(view.scale(offset.dot(view)));
        int rotation = frame.getRotation();
        if (rotation != 0) {
            offset = rotateAroundAxis(offset, facing.getUnitVec3(), Math.toRadians(rotation * 45.0));
        }
        int x = (int) Math.round(offset.dot(right) * 256.0);
        int y = (int) Math.round(offset.dot(up) * -256.0);
        return new int[]{clampByte(x), clampByte(y)};
    }

    private static int clampByte(int value) {
        return Math.max(-128, Math.min(127, value));
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dot = vector.dot(axis);
        Vec3 cross = axis.cross(vector);
        return vector.scale(cos).add(cross.scale(sin)).add(axis.scale(dot * (1 - cos)));
    }

    public record FrameHit(ItemFrame frame, Vec3 point) {
    }

    /** Ray-traces item frames near the player's eyes. */
    public static FrameHit rayTrace(ServerLevel level, Vec3 start, Vec3 direction, double distance) {
        Vec3 end = start.add(direction.scale(distance));
        AABB box = new AABB(start, end).inflate(1.0);
        List<ItemFrame> frames = level.getEntities(EntityTypeTest.forClass(ItemFrame.class), box,
                frame -> frame.isAlive());
        FrameHit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemFrame frame : frames) {
            if (frame.getItem().isEmpty()) {
                continue;
            }
            var clip = frame.getBoundingBox().inflate(0.05).clip(start, end);
            if (clip.isEmpty()) {
                continue;
            }
            double dist = clip.get().distanceToSqr(start);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = new FrameHit(frame, clip.get());
            }
        }
        return best;
    }
}
