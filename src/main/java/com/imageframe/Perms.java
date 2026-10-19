package com.imageframe;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Permission mapping. The original plugin exposes ~30 Bukkit permission nodes. A Fabric
 * server has no Bukkit permission system, so every node can be configured as
 * {@code all} (everybody), {@code op} (operators / admins) or {@code none} (nobody).
 * The defaults mirror the original plugin defaults.
 */
public final class Perms {

    public enum Node {
        CREATE("create", "op"),
        CREATE_ANIMATED("create.animated", "op"),
        CREATE_OTHERS("create.others", "op"),
        OVERLAY("overlay", "op"),
        CLONE("clone", "op"),
        PLAYBACK("playback", "op"),
        SELECT("select", "op"),
        MARKER("marker", "op"),
        MARKER_UNLIMITED("marker.unlimited", "op"),
        REFRESH("refresh", "op"),
        RENAME("rename", "op"),
        INFO("info", "all"),
        LIST("list", "all"),
        LIST_OTHERS("list.others", "op"),
        DELETE("delete", "op"),
        PURGE("purge", "none"),
        PURGE_ALL("purge.all", "none"),
        GET("get", "op"),
        GET_OTHERS("get.others", "op"),
        SET_ACCESS("setaccess", "op"),
        ADMIN_BYPASS("adminbypass", "op"),
        ADMIN_DELETE("admindelete", "op"),
        GIVE_INVISIBLE_FRAME("giveinvisibleframe", "op"),
        PREFERENCE("preference", "all"),
        RELOAD("reload", "op"),
        RESYNC("resync", "op"),
        CREATION_LIMIT_BYPASS("createlimit.unlimited", "op"),
        PREFERENCE_OTHERS("preference.others", "op"),
        UPDATE("update", "op"),
        ADMIN_SET_CREATOR("adminsetcreator", "op"),
        ADMIN_MIGRATE("adminmigrate", "op"),
        STORAGE_MIGRATE("storagemigrate", "op");

        public final String node;
        public final String defaultMode;

        Node(String node, String defaultMode) {
            this.node = node;
            this.defaultMode = defaultMode;
        }
    }

    private Perms() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Node node : Node.values()) {
            map.put(node.node, node.defaultMode);
        }
        return map;
    }

    public static String mode(Node node) {
        Config config = ImageFrameMod.get().config();
        String value = config.permissions.get(node.node);
        return value == null ? node.defaultMode : value.toLowerCase(Locale.ROOT);
    }

    public static boolean check(CommandSourceStack source, Node node) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return check(player, node);
    }

    public static boolean check(ServerPlayer player, Node node) {
        String mode = mode(node);
        return switch (mode) {
            case "all" -> true;
            case "none" -> false;
            default -> isOp(player);
        };
    }

    public static boolean isOp(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        return server.getPlayerList().isOp(new NameAndId(player.getUUID(), player.getScoreboardName()));
    }
}
