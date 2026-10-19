package com.imageframe.commands;

import com.imageframe.ImageFrameMod;
import com.imageframe.Perms;
import com.imageframe.frame.SelectionManager;
import com.imageframe.image.ImageMap;
import com.imageframe.image.ImageMapManager;
import com.imageframe.migration.PluginMigration;
import com.imageframe.util.Frames;
import com.imageframe.util.ItemsX;
import com.imageframe.util.Lang;
import com.imageframe.util.Palette;
import com.imageframe.upload.ImageUploadService;
import com.imageframe.upload.PendingUpload;
import com.imageframe.util.Updater;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * All /imageframe commands. The command tree mirrors the original plugin.
 */
public final class ImageFrameCommand {

    private ImageFrameCommand() {
    }

    private static ImageFrameMod mod() {
        return ImageFrameMod.get();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("imageframe")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> message(ctx, "version", version()), false);
                    return 1;
                })
                .then(Commands.literal("help").executes(ImageFrameCommand::help))
                .then(Commands.literal("version").executes(ctx -> reply(ctx, message(ctx, "version", version()))))
                .then(Commands.literal("reload")
                        .requires(source -> Perms.check(source, Perms.Node.RELOAD))
                        .executes(ImageFrameCommand::reload))
                .then(Commands.literal("resync")
                        .requires(source -> Perms.check(source, Perms.Node.RESYNC))
                        .executes(ImageFrameCommand::resync))
                .then(Commands.literal("update")
                        .requires(source -> Perms.check(source, Perms.Node.UPDATE))
                        .executes(ImageFrameCommand::updateCheck))
                .then(createCommand())
                .then(overlayCommand())
                .then(selectCommand())
                .then(Commands.literal("playback")
                        .requires(source -> Perms.check(source, Perms.Node.PLAYBACK))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.literal("pause")
                                        .executes(ctx -> playbackPause(ctx, null))
                                        .then(Commands.argument("state", BoolArgumentType.bool())
                                                .executes(ctx -> playbackPause(ctx, BoolArgumentType.getBool(ctx, "state")))))
                                .then(Commands.literal("jumpto")
                                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(-1000F, 100000F))
                                                .executes(ImageFrameCommand::playbackJump)))))
                .then(markerCommand())
                .then(Commands.literal("refresh")
                        .requires(source -> Perms.check(source, Perms.Node.REFRESH))
                        .executes(ctx -> refreshCommand(ctx, null, null, null))
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ctx -> refreshCommand(ctx, StringArgumentType.getString(ctx, "target"), null, null))
                                .then(Commands.argument("second", StringArgumentType.string())
                                        .executes(ctx -> refreshCommand(ctx,
                                                StringArgumentType.getString(ctx, "target"),
                                                StringArgumentType.getString(ctx, "second"), null))
                                        .then(Commands.argument("dithering", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ditheringNames(), builder))
                                                .executes(ctx -> refreshCommand(ctx,
                                                        StringArgumentType.getString(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "second"),
                                                        StringArgumentType.getString(ctx, "dithering")))))))
                .then(Commands.literal("rename")
                        .requires(source -> Perms.check(source, Perms.Node.RENAME))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ImageFrameCommand::rename))))
                .then(Commands.literal("info")
                        .requires(source -> Perms.check(source, Perms.Node.INFO))
                        .executes(ctx -> info(ctx, null))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "image")))))
                .then(Commands.literal("list")
                        .requires(source -> Perms.check(source, Perms.Node.LIST))
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("get")
                        .requires(source -> Perms.check(source, Perms.Node.GET))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ctx -> get(ctx, null, null))
                                .then(Commands.argument("second", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(getSuggestions(), builder))
                                        .executes(ctx -> get(ctx, StringArgumentType.getString(ctx, "second"), null))
                                        .then(Commands.argument("third", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        playerNames(), builder))
                                                .executes(ctx -> get(ctx,
                                                        StringArgumentType.getString(ctx, "second"),
                                                        StringArgumentType.getString(ctx, "third")))))))
                .then(Commands.literal("delete")
                        .requires(source -> Perms.check(source, Perms.Node.DELETE))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ImageFrameCommand::delete)))
                .then(Commands.literal("clone")
                        .requires(source -> Perms.check(source, Perms.Node.CLONE))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> clone(ctx, "separated"))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        List.of("combined", "separated", "selection"), builder))
                                                .executes(ctx -> clone(ctx, StringArgumentType.getString(ctx, "mode")))))))
                .then(Commands.literal("setaccess")
                        .requires(source -> Perms.check(source, Perms.Node.SET_ACCESS))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(), builder))
                                        .then(Commands.argument("access", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        List.of("none", "view", "edit"), builder))
                                                .executes(ImageFrameCommand::setAccess)))))
                .then(Commands.literal("giveinvisibleframe")
                        .requires(source -> Perms.check(source, Perms.Node.GIVE_INVISIBLE_FRAME))
                        .executes(ctx -> giveInvisibleFrame(ctx, "invisible", 1, null))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("invisible", "glowing"), builder))
                                .executes(ctx -> giveInvisibleFrame(ctx,
                                        StringArgumentType.getString(ctx, "type"), 1, null))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 3456))
                                        .executes(ctx -> giveInvisibleFrame(ctx,
                                                StringArgumentType.getString(ctx, "type"),
                                                IntegerArgumentType.getInteger(ctx, "amount"), null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(), builder))
                                                .executes(ctx -> giveInvisibleFrame(ctx,
                                                        StringArgumentType.getString(ctx, "type"),
                                                        IntegerArgumentType.getInteger(ctx, "amount"),
                                                        StringArgumentType.getString(ctx, "player")))))))
                .then(Commands.literal("purge")
                        .requires(source -> Perms.check(source, Perms.Node.PURGE))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> purge(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("admindelete")
                        .requires(source -> Perms.check(source, Perms.Node.ADMIN_DELETE))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ImageFrameCommand::adminDelete)))
                .then(Commands.literal("adminsetcreator")
                        .requires(source -> Perms.check(source, Perms.Node.ADMIN_SET_CREATOR))
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(), builder))
                                        .executes(ImageFrameCommand::adminSetCreator))))
                .then(Commands.literal("adminmigrate")
                        .requires(source -> Perms.check(source, Perms.Node.ADMIN_MIGRATE))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ImageFrameCommand::adminMigrate)))
                .then(Commands.literal("storagemigrate")
                        .requires(source -> Perms.check(source, Perms.Node.STORAGE_MIGRATE))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ImageFrameCommand::storageMigrate)))
                .then(Commands.literal("preference")
                        .requires(source -> Perms.check(source, Perms.Node.PREFERENCE))
                        .executes(ImageFrameCommand::preferenceInfo)
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("viewAnimatedMaps", "language"), builder))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                preferenceValues(StringArgumentType.getString(ctx, "type")), builder))
                                        .executes(ctx -> preferenceSet(ctx, null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(), builder))
                                                .executes(ctx -> preferenceSet(ctx,
                                                        StringArgumentType.getString(ctx, "player")))))));
        var registered = dispatcher.register(root);
        // Register the aliases used by the original plugin.
        try {
            dispatcher.register(Commands.literal("iframe").redirect(registered));
            dispatcher.register(Commands.literal("frame").redirect(registered));
        } catch (Throwable ignored) {
            // aliases are best effort
        }
    }

    private static String version() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("imageframe")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        LiteralArgumentBuilder<CommandSourceStack> create = Commands.literal("create")
                .requires(source -> Perms.check(source, Perms.Node.CREATE));
        RequiredArgumentBuilder<CommandSourceStack, String> name = Commands.argument("name", StringArgumentType.word());
        RequiredArgumentBuilder<CommandSourceStack, String> url = Commands.argument("url", StringArgumentType.string());
        // NB: Brigadier builds argument builders eagerly when they are attached with then(...),
        // so the whole url sub tree must be completed before name.then(url) is called.
        url.then(Commands.literal("selection")
                .executes(ctx -> createImage(ctx, true, null, defaultMode()))
                .then(ditheringNode(ctx -> createImage(ctx, true, StringArgumentType.getString(ctx, "dithering"), defaultMode()))
                        .then(modeNode(true))));
        var width = Commands.argument("width", IntegerArgumentType.integer(1, 128));
        var height = Commands.argument("height", IntegerArgumentType.integer(1, 128));
        url.then(width.then(height
                .executes(ctx -> createImage(ctx, false, null, defaultMode()))
                .then(ditheringNode(ctx -> createImage(ctx, false, StringArgumentType.getString(ctx, "dithering"), defaultMode()))
                        .then(modeNode(false)))));
        url.executes(ctx -> fail(ctx, "invalid_usage"));
        name.then(url);
        create.then(name);
        return create;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> ditheringNode(
            com.mojang.brigadier.Command<CommandSourceStack> command) {
        return Commands.argument("dithering", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ditheringNames(), builder))
                .executes(command);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> modeNode(boolean selection) {
        return Commands.argument("mode", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        List.of("combined", "separated"), builder))
                .executes(ctx -> {
                    String mode = StringArgumentType.getString(ctx, "mode");
                    return createImage(ctx, selection, ditheringOf(ctx), mode);
                });
    }

    private static String defaultMode() {
        return mod().config().combinedByDefault ? "combined" : "separated";
    }

    private static String ditheringOf(CommandContext<CommandSourceStack> ctx) {
        try {
            return StringArgumentType.getString(ctx, "dithering");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int createImage(CommandContext<CommandSourceStack> ctx, boolean selectionMode,
                                   String ditheringName, String mode) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String url = StringArgumentType.getString(ctx, "url");
        boolean combined = mode.equalsIgnoreCase("combined");
        if (!combined && !mode.equalsIgnoreCase("separated")) {
            return fail(ctx, "invalid_usage");
        }
        Palette.Dithering dithering = Palette.Dithering.byName(ditheringName);
        ImageMapManager manager = mod().manager();
        if (manager.nameExists(player.getUUID(), name)) {
            return fail(ctx, "duplicate_name");
        }
        if (!isUpload(url) && !mod().config().isUrlAllowed(url)) {
            return fail(ctx, "url_restricted");
        }
        int width;
        int height;
        Frames.Selection selection = null;
        if (selectionMode) {
            selection = mod().selection().confirmed(player);
            if (selection == null) {
                return fail(ctx, "selection_none");
            }
            for (ItemFrame frame : selection.frames()) {
                if (!frame.getItem().isEmpty()) {
                    return fail(ctx, "item_frame_occupied");
                }
            }
            width = selection.width();
            height = selection.height();
        } else {
            width = IntegerArgumentType.getInteger(ctx, "width");
            height = IntegerArgumentType.getInteger(ctx, "height");
        }
        if ((long) width * height > mod().config().maxSize) {
            return fail(ctx, "oversize", mod().config().maxSize);
        }
        boolean bypass = mod().permits(player, Perms.Node.CREATION_LIMIT_BYPASS);
        int limit = mod().config().creationLimit(bypass);
        if (limit >= 0 && manager.fromCreator(player.getUUID()).size() >= limit) {
            return fail(ctx, "creation_limit", limit);
        }
        if (isUpload(url)) {
            if (!mod().uploadService().isOperational()) {
                return fail(ctx, "not_supported");
            }
            PendingUpload pending = mod().uploadService().createPending(player, name, width, height);
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_link", mod().uploadService().publicUrl(pending)), false);
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_waiting"), false);
            Frames.Selection finalSelection = selection;
            Palette.Dithering finalDithering = dithering;
            int finalWidth = width;
            int finalHeight = height;
            boolean finalCombined = combined;
            awaitUpload(ctx, pending, path -> {
                if (finalSelection != null) {
                    mod().selection().clearConfirmed(player);
                }
                ctx.getSource().sendSuccess(() -> message(ctx, "image_processing"), false);
                manager.createAsync(new ImageMapManager.CreateRequest(player, name,
                        path.toAbsolutePath().toString(), finalWidth, finalHeight, finalDithering, finalCombined,
                        finalSelection, false));
            });
            return 1;
        }
        ctx.getSource().sendSuccess(() -> message(ctx, "image_processing"), false);
        if (selectionMode) {
            mod().selection().clearConfirmed(player);
        }
        manager.createAsync(new ImageMapManager.CreateRequest(player, name, url, width, height, dithering,
                combined, selection, false));
        return 1;
    }

    // ------------------------------------------------------------------
    // overlay
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> overlayCommand() {
        return Commands.literal("overlay")
                .requires(source -> Perms.check(source, Perms.Node.OVERLAY))
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("url", StringArgumentType.string())
                                .executes(ctx -> overlay(ctx, null, null))
                                .then(Commands.literal("selection")
                                        .executes(ctx -> overlay(ctx, "selection", null))
                                        .then(Commands.argument("dithering", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ditheringNames(), builder))
                                                .executes(ctx -> overlay(ctx, "selection",
                                                        StringArgumentType.getString(ctx, "dithering")))))
                                .then(Commands.argument("dithering", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ditheringNames(), builder))
                                        .executes(ctx -> overlay(ctx, null,
                                                StringArgumentType.getString(ctx, "dithering"))))));
    }

    private static int overlay(CommandContext<CommandSourceStack> ctx, String mode, String ditheringName)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String url = StringArgumentType.getString(ctx, "url");
        if (!isUpload(url) && !mod().config().isUrlAllowed(url)) {
            return fail(ctx, "url_restricted");
        }
        if (mod().manager().nameExists(player.getUUID(), name)) {
            return fail(ctx, "duplicate_name");
        }
        int width;
        int height;
        List<Integer> baseMapIds = new ArrayList<>();
        if ("selection".equals(mode)) {
            Frames.Selection selection = mod().selection().confirmed(player);
            if (selection == null) {
                return fail(ctx, "selection_none");
            }
            width = selection.width();
            height = selection.height();
            for (ItemFrame frame : selection.frames()) {
                Integer mapId = ItemsX.mapId(frame.getItem());
                if (mapId == null || mod().manager().byAnyMapId(mapId) != null) {
                    return fail(ctx, "invalid_overlay_map");
                }
                if (baseMapIds.contains(mapId)) {
                    return fail(ctx, "invalid_overlay_map");
                }
                baseMapIds.add(mapId);
            }
        } else {
            Integer mapId = ItemsX.mapId(player.getMainHandItem());
            if (mapId == null) {
                return fail(ctx, "overlay_only_held");
            }
            if (mod().manager().byAnyMapId(mapId) != null) {
                return fail(ctx, "invalid_overlay_map");
            }
            width = 1;
            height = 1;
            baseMapIds.add(mapId);
        }
        if ((long) width * height > mod().config().maxSize) {
            return fail(ctx, "oversize", mod().config().maxSize);
        }
        boolean bypass = mod().permits(player, Perms.Node.CREATION_LIMIT_BYPASS);
        int limit = mod().config().creationLimit(bypass);
        if (limit >= 0 && mod().manager().fromCreator(player.getUUID()).size() >= limit) {
            return fail(ctx, "creation_limit", limit);
        }
        Palette.Dithering dithering = Palette.Dithering.byName(ditheringName);
        if (isUpload(url)) {
            if (!mod().uploadService().isOperational()) {
                return fail(ctx, "not_supported");
            }
            PendingUpload pending = mod().uploadService().createPending(player, name, width, height);
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_link", mod().uploadService().publicUrl(pending)), false);
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_waiting"), false);
            List<Integer> finalBaseMapIds = baseMapIds;
            int finalWidth = width;
            int finalHeight = height;
            awaitUpload(ctx, pending, path -> {
                ctx.getSource().sendSuccess(() -> message(ctx, "image_processing"), false);
                mod().manager().createOverlayAsync(new ImageMapManager.OverlayRequest(player, name,
                        path.toAbsolutePath().toString(), dithering, finalWidth, finalHeight, finalBaseMapIds));
            });
            return 1;
        }
        ctx.getSource().sendSuccess(() -> message(ctx, "image_processing"), false);
        mod().manager().createOverlayAsync(new ImageMapManager.OverlayRequest(player, name, url, dithering,
                width, height, baseMapIds));
        return 1;
    }

    // ------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> selectCommand() {
        return Commands.literal("select")
                .requires(source -> Perms.check(source, Perms.Node.SELECT))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    boolean active = mod().selection().toggle(player);
                    return reply(ctx, message(ctx, active ? "selection_begin" : "selection_clear"));
                })
                .then(Commands.argument("x1", IntegerArgumentType.integer(-30000000, 30000000))
                        .then(Commands.argument("y1", IntegerArgumentType.integer(-2048, 2048))
                                .then(Commands.argument("z1", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(Commands.argument("x2", IntegerArgumentType.integer(-30000000, 30000000))
                                                .then(Commands.argument("y2", IntegerArgumentType.integer(-2048, 2048))
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer(-30000000, 30000000))
                                                                .executes(ImageFrameCommand::directSelect)))))));
    }

    private static int directSelect(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return fail(ctx, "no_console");
        }
        BlockPos first = new BlockPos(IntegerArgumentType.getInteger(ctx, "x1"),
                IntegerArgumentType.getInteger(ctx, "y1"), IntegerArgumentType.getInteger(ctx, "z1"));
        BlockPos second = new BlockPos(IntegerArgumentType.getInteger(ctx, "x2"),
                IntegerArgumentType.getInteger(ctx, "y2"), IntegerArgumentType.getInteger(ctx, "z2"));
        mod().selection().directSelection(player, player.level(), first, second, player.getYRot());
        return 1;
    }

    // ------------------------------------------------------------------
    // marker
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> markerCommand() {
        return Commands.literal("marker")
                .requires(source -> Perms.check(source, Perms.Node.MARKER))
                .then(Commands.literal("add")
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("direction", IntegerArgumentType.integer(0, 15))
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                mod().manager().markerTypes().suggestions(), builder))
                                                        .executes(ctx -> markerAdd(ctx, ""))
                                                        .then(Commands.argument("caption", StringArgumentType.greedyString())
                                                                .executes(ctx -> markerAdd(ctx,
                                                                        StringArgumentType.getString(ctx, "caption")))))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ImageFrameCommand::markerRemove))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("image", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(imageNames(), builder))
                                .executes(ImageFrameCommand::markerClear)))
                .then(Commands.literal("cancel").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    mod().markers().cancel(player);
                    return 1;
                }));
    }

    private static int markerAdd(CommandContext<CommandSourceStack> ctx, String caption) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        String name = StringArgumentType.getString(ctx, "name");
        byte direction = (byte) IntegerArgumentType.getInteger(ctx, "direction");
        String type = StringArgumentType.getString(ctx, "type");
        if (image.marker(0, name) != null) {
            return fail(ctx, "marker_duplicate");
        }
        mod().markers().startEditing(player, image, name, type, direction, caption);
        return 1;
    }

    private static int markerRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        String name = StringArgumentType.getString(ctx, "name");
        for (int tile = 0; tile < image.tileCount(); tile++) {
            if (image.markers().get(tile).remove(name) != null) {
                image.save();
                image.markUpdated();
                return reply(ctx, message(ctx, "marker_removed"));
            }
        }
        return fail(ctx, "marker_not_found");
    }

    private static int markerClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        image.clearMarkers();
        image.save();
        return reply(ctx, message(ctx, "marker_cleared"));
    }

    // ------------------------------------------------------------------
    // playback
    // ------------------------------------------------------------------

    private static int playbackPause(CommandContext<CommandSourceStack> ctx, Boolean state) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        if (!image.isAnimated()) {
            return fail(ctx, "not_animated");
        }
        boolean target = state == null ? !image.paused() : state;
        image.setPaused(target);
        image.save();
        return reply(ctx, message(ctx, "playback_paused"));
    }

    private static int playbackJump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        if (!image.isAnimated()) {
            return fail(ctx, "not_animated");
        }
        float seconds = FloatArgumentType.getFloat(ctx, "seconds");
        image.setPlaybackSeconds(seconds);
        image.save();
        return reply(ctx, message(ctx, "playback_jump", seconds));
    }

    // ------------------------------------------------------------------
    // refresh / rename / info / list / get / delete / clone
    // ------------------------------------------------------------------

    private static int refreshCommand(CommandContext<CommandSourceStack> ctx, String target, String second,
                                      String third) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = null;
        String url = null;
        String dithering = null;
        if (target != null) {
            image = resolve(ctx, target);
            if (image != null) {
                if (second != null) {
                    if (Palette.Dithering.byNameOrNull(second.toLowerCase(Locale.ROOT)) != null) {
                        dithering = third == null ? second : third;
                    } else {
                        url = second;
                        if (third != null) {
                            dithering = third;
                        }
                    }
                }
            } else if (Palette.Dithering.byNameOrNull(target.toLowerCase(Locale.ROOT)) != null) {
                dithering = target;
                if (second != null && Palette.Dithering.byNameOrNull(second.toLowerCase(Locale.ROOT)) == null) {
                    url = second;
                }
                if (third != null) {
                    dithering = third;
                }
            } else {
                url = target;
                if (second != null && Palette.Dithering.byNameOrNull(second.toLowerCase(Locale.ROOT)) != null) {
                    dithering = second;
                }
            }
        }
        if (image == null) {
            image = heldImage(player);
        }
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        if (url != null && !isUpload(url) && !mod().config().isUrlAllowed(url)) {
            return fail(ctx, "url_restricted");
        }
        Palette.Dithering newDithering = dithering == null ? null : Palette.Dithering.byName(dithering);
        ImageMap finalImage = image;
        if (url != null && isUpload(url)) {
            if (!mod().uploadService().isOperational()) {
                return fail(ctx, "not_supported");
            }
            PendingUpload pending = mod().uploadService().createPending(player, image.name(), image.width(),
                    image.height());
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_link", mod().uploadService().publicUrl(pending)), false);
            ctx.getSource().sendSuccess(() -> message(ctx, "upload_waiting"), false);
            awaitUpload(ctx, pending, path -> {
                ctx.getSource().sendSuccess(() -> message(ctx, "image_processing"), false);
                mod().manager().refreshAsync(finalImage, path.toAbsolutePath().toString(), newDithering);
            });
            return 1;
        }
        ctx.getSource().sendSuccess(() -> message(ctx, "refresh_processing", finalImage.name()), false);
        mod().manager().refreshAsync(image, url, newDithering);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (mod().manager().nameExists(image.creator(), name)) {
            return fail(ctx, "duplicate_name");
        }
        image.rename(name);
        image.save();
        return reply(ctx, message(ctx, "renamed", name));
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        ImageMap image;
        if (argument != null) {
            image = resolve(ctx, argument);
        } else {
            ServerPlayer player = ctx.getSource().getPlayer();
            image = player == null ? null : heldImage(player);
        }
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        ImageMap finalImage = image;
        ctx.getSource().sendSuccess(() -> message(ctx, "info_header", finalImage.index(), finalImage.name()), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_url"), finalImage.url()), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_size"), finalImage.width() + "x" + finalImage.height()), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_dithering"), finalImage.dithering().getName()), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_type"), Lang.plain("kind_" + finalImage.kind().name().toLowerCase(java.util.Locale.ROOT))), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_creator"), finalImage.creatorName() + " / " + finalImage.creator()), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_created"), mod().formatTime(finalImage.creationTime())), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "info_line", Lang.plain("info_map_ids"), String.valueOf(finalImage.mapIds())), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String playerName) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID creator = player.getUUID();
        String displayName = player.getScoreboardName();
        if (playerName != null && !playerName.equalsIgnoreCase(displayName)) {
            if (!Perms.check(player, Perms.Node.LIST_OTHERS)) {
                return fail(ctx, "no_permission");
            }
            ServerPlayer other = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
            if (other == null) {
                return fail(ctx, "player_not_found");
            }
            creator = other.getUUID();
            displayName = other.getScoreboardName();
        }
        List<ImageMap> images = mod().manager().fromCreator(creator);
        if (images.isEmpty()) {
            return reply(ctx, message(ctx, "list_empty"));
        }
        String finalDisplayName = displayName;
        ctx.getSource().sendSuccess(() -> message(ctx, "list_header", finalDisplayName), false);
        for (ImageMap image : images) {
            ImageMap finalImage = image;
            ctx.getSource().sendSuccess(() -> Text_list(ctx, finalImage), false);
        }
        return 1;
    }

    private static Component Text_list(CommandContext<CommandSourceStack> ctx, ImageMap image) {
        return rawMessage(ctx, "list_line", image.index(), image.name(), image.width(), image.height(),
                Lang.plain("kind_" + image.kind().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static List<String> getSuggestions() {
        List<String> values = new ArrayList<>(List.of("combined", "separated", "selection"));
        values.addAll(playerNames());
        return values;
    }

    private static int get(CommandContext<CommandSourceStack> ctx, String second, String third)
            throws CommandSyntaxException {
        ServerPlayer requester = ctx.getSource().getPlayer();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        boolean combined = mod().config().combinedByDefault;
        Frames.Selection selection = null;
        String playerName = null;
        if (second != null) {
            if (second.equalsIgnoreCase("combined")) {
                combined = true;
                playerName = third;
            } else if (second.equalsIgnoreCase("separated")) {
                combined = false;
                playerName = third;
            } else if (second.equalsIgnoreCase("selection")) {
                if (requester == null) {
                    return fail(ctx, "no_console");
                }
                selection = mod().selection().confirmed(requester);
                if (selection == null) {
                    return fail(ctx, "selection_none");
                }
                playerName = third;
            } else {
                playerName = second;
            }
        }
        ServerPlayer target;
        if (playerName != null) {
            if (requester == null || !Perms.check(requester, Perms.Node.GET_OTHERS)) {
                return fail(ctx, "no_permission");
            }
            target = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return fail(ctx, "player_not_found");
            }
        } else {
            if (requester == null) {
                return fail(ctx, "no_console");
            }
            target = requester;
        }
        if (requester != null && !image.canView(requester)) {
            return fail(ctx, "access_none");
        }
        if (selection != null) {
            if (selection.width() != image.width() || selection.height() != image.height()) {
                return fail(ctx, "selection_incorrect_size", image.width(), image.height());
            }
            for (int tile = 0; tile < selection.frames().size(); tile++) {
                selection.frames().get(tile).setItem(image.itemForTile(tile), false);
                selection.frames().get(tile).setRotation(selection.rotation());
                mod().frames().track(selection.frames().get(tile));
            }
            mod().selection().clearConfirmed(requester);
            mod().sender().sendImage(target, image);
            return reply(ctx, message(ctx, "gave_maps", image.tileCount()));
        }
        if (combined) {
            ItemsX.give(target, mod().combined().combinedItem(image));
            mod().sender().sendImage(target, image);
            return reply(ctx, message(ctx, "gave_combined"));
        }
        for (ItemStack item : image.items()) {
            ItemsX.give(target, item);
        }
        mod().sender().sendImage(target, image);
        return reply(ctx, message(ctx, "gave_maps", image.tileCount()));
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        image.delete();
        return reply(ctx, message(ctx, "deleted", image.name()));
    }

    private static int clone(CommandContext<CommandSourceStack> ctx, String mode) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canView(player)) {
            return fail(ctx, "access_none");
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (mod().manager().nameExists(player.getUUID(), name)) {
            return fail(ctx, "duplicate_name");
        }
        Frames.Selection selection = null;
        boolean combined = mode.equalsIgnoreCase("combined");
        if (mode.equalsIgnoreCase("selection")) {
            selection = mod().selection().confirmed(player);
            if (selection == null) {
                return fail(ctx, "selection_none");
            }
            if (selection.width() != image.width() || selection.height() != image.height()) {
                return fail(ctx, "selection_incorrect_size", image.width(), image.height());
            }
        }
        mod().manager().cloneAsync(new ImageMapManager.CloneRequest(player, image, name, combined, selection));
        return reply(ctx, message(ctx, "image_processing"));
    }

    private static int setAccess(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        if (!image.canEdit(player)) {
            return fail(ctx, "no_permission");
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        var target = mod().preferences().resolveTarget(player, targetName);
        if (target == null) {
            return fail(ctx, "player_not_found");
        }
        String access = StringArgumentType.getString(ctx, "access");
        image.setAccess(target.uuid(), access);
        image.save();
        return reply(ctx, message(ctx, "set_access_done", target.name()));
    }

    private static int giveInvisibleFrame(CommandContext<CommandSourceStack> ctx, String type, int amount,
                                          String playerName) throws CommandSyntaxException {
        ServerPlayer giver = ctx.getSource().getPlayer();
        ServerPlayer target = giver;
        if (playerName != null) {
            target = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return fail(ctx, "player_not_found");
            }
        } else if (giver == null) {
            return fail(ctx, "no_console");
        }
        boolean glow = "glowing".equalsIgnoreCase(type);
        mod().invisible().give(target, glow, amount);
        if (target == giver) {
            return reply(ctx, message(ctx, "gave_invisible_frame", amount));
        }
        return reply(ctx, message(ctx, "gave_invisible_frame_target", amount, target.getScoreboardName()));
    }

    private static int purge(CommandContext<CommandSourceStack> ctx, String target) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (target.equals("*")) {
            if (!Perms.check(player, Perms.Node.PURGE_ALL)) {
                return fail(ctx, "no_permission");
            }
            int count = mod().manager().all().size();
            for (ImageMap image : new ArrayList<>(mod().manager().all())) {
                image.delete();
            }
            return reply(ctx, message(ctx, "purged_all", count));
        }
        ServerPlayer other = ctx.getSource().getServer().getPlayerList().getPlayerByName(target);
        if (other == null) {
            return fail(ctx, "player_not_found");
        }
        List<ImageMap> images = mod().manager().fromCreator(other.getUUID());
        for (ImageMap image : images) {
            image.delete();
        }
        return reply(ctx, message(ctx, "purged", images.size()));
    }

    private static int adminDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        int index = image.index();
        image.delete();
        return reply(ctx, message(ctx, "admin_delete", index));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        mod().reloadConfig();
        return reply(ctx, message(ctx, "reloaded"));
    }

    private static int resync(CommandContext<CommandSourceStack> ctx) {
        mod().storage().load();
        mod().frames().revalidate();
        return reply(ctx, message(ctx, "resynced"));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> message(ctx, "help_header"), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe create <name> <url|upload> [width] [height] [dithering] [combined|separated]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe create <name> <url|upload> selection [dithering] [combined|separated]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe overlay <name> <url|upload> [selection] [dithering]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe select [x1 y1 z1 x2 y2 z2]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe list [player] | info [image] | get <image> [combined|separated|selection] [player]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe rename <image> <name> | delete <image> | clone <image> <name> [mode]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe refresh [image] [url|dithering] [dithering]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe marker add|remove|clear|cancel ...", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe playback <image> pause [true|false] | jumpto <seconds>", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe setaccess <image> <player> <none|view|edit>", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe giveinvisibleframe [invisible|glowing] [amount] [player]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe preference <type> <value> [player]", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe update | reload | resync", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe purge <player|*> | admindelete <image> | adminsetcreator <image> <player>", ""), false);
        ctx.getSource().sendSuccess(() -> rawMessage(ctx, "help_line", "/imageframe adminmigrate <path> | storagemigrate <path>", ""), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // preferences / admin tools / update check
    // ------------------------------------------------------------------

    private static int preferenceInfo(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> message(ctx, "preference_types",
                String.join(", ", preferenceTypes())), false);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            var preferences = mod().preferences();
            ctx.getSource().sendSuccess(() -> rawMessage(ctx, "preference_value",
                    "viewAnimatedMaps", String.valueOf(preferences.viewAnimatedMaps(player))), false);
            ctx.getSource().sendSuccess(() -> rawMessage(ctx, "preference_value",
                    "language", preferences.effectiveLanguage(player)), false);
        }
        return 1;
    }

    private static List<String> preferenceTypes() {
        return List.of("viewAnimatedMaps", "language");
    }

    private static List<String> preferenceValues(String type) {
        if (type != null && "language".equalsIgnoreCase(type)) {
            List<String> values = new ArrayList<>(Lang.availableLanguages());
            values.add("unset");
            return values;
        }
        return List.of("true", "false", "unset");
    }

    private static int preferenceSet(CommandContext<CommandSourceStack> ctx, String playerName) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer requester = source.getPlayer();
        String type = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
        String rawValue = StringArgumentType.getString(ctx, "value");
        var preferences = mod().preferences();
        if (playerName == null) {
            if (requester == null) {
                return fail(ctx, "no_console");
            }
            playerName = requester.getScoreboardName();
        } else if (requester != null && !Perms.check(requester, Perms.Node.PREFERENCE_OTHERS)) {
            return fail(ctx, "no_permission");
        }
        var target = preferences.resolveTarget(requester, playerName);
        if (target == null) {
            return fail(ctx, "player_not_found");
        }
        boolean unset = "unset".equalsIgnoreCase(rawValue) || "default".equalsIgnoreCase(rawValue);
        if (type.equals("viewanimatedmaps") || type.equals("view_animated_maps")) {
            Boolean value = unset ? null : parseBoolean(rawValue);
            if (!unset && value == null) {
                return fail(ctx, "preference_invalid");
            }
            preferences.setViewAnimatedMaps(target.uuid(), value);
            if (unset) {
                return reply(ctx, message(ctx, "preference_unset", "viewAnimatedMaps"));
            }
            return reply(ctx, message(ctx, "preference_changed", "viewAnimatedMaps", String.valueOf(value)));
        }
        if (type.equals("language")) {
            String value = null;
            if (!unset) {
                String candidate = rawValue.toLowerCase(Locale.ROOT);
                if (!Lang.isLanguageSupported(candidate)) {
                    return fail(ctx, "preference_invalid");
                }
                value = candidate;
            }
            preferences.setLanguage(target.uuid(), value);
            if (unset) {
                return reply(ctx, message(ctx, "preference_unset", "language"));
            }
            return reply(ctx, message(ctx, "preference_changed", "language", value));
        }
        return fail(ctx, "preference_invalid");
    }

    private static Boolean parseBoolean(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "true", "on", "yes", "enabled", "enable" -> Boolean.TRUE;
            case "false", "off", "no", "disabled", "disable" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static int adminSetCreator(CommandContext<CommandSourceStack> ctx) {
        ImageMap image = resolve(ctx, StringArgumentType.getString(ctx, "image"));
        if (image == null) {
            return fail(ctx, "not_an_image_map");
        }
        String playerName = StringArgumentType.getString(ctx, "player");
        var target = mod().preferences().resolveTarget(null, playerName);
        if (target == null) {
            return fail(ctx, "player_not_found");
        }
        image.setCreator(target.uuid(), target.name());
        image.save();
        return reply(ctx, message(ctx, "set_creator", image.index(), target.name(), target.uuid()));
    }

    private static int updateCheck(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String current = version();
        source.sendSuccess(() -> Lang.t(player, "version", current), false);
        source.sendSuccess(() -> Lang.t(player, "update_checking"), false);
        mod().updater().checkAsync(result -> {
            if (!result.ok()) {
                if ("not-configured".equals(result.error())) {
                    source.sendSuccess(() -> Lang.t(player, "update_not_configured"), false);
                } else {
                    source.sendSuccess(() -> Lang.t(player, "update_failed", result.error()), false);
                }
                return;
            }
            if (Updater.isNewer(result.latestVersion(), current)) {
                String notes = result.notes() == null ? "" : result.notes();
                source.sendSuccess(() -> Lang.t(player, "update_available", result.latestVersion(), current,
                        result.downloadUrl() == null ? notes : result.downloadUrl() + " " + notes), false);
            } else {
                source.sendSuccess(() -> Lang.t(player, "update_latest", current), false);
            }
        });
        return 1;
    }

    private static int adminMigrate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String path = StringArgumentType.getString(ctx, "path");
        source.sendSuccess(() -> Lang.t(player, "migration_started"), false);
        new PluginMigration(mod()).migrateAsync(source, player, path, result -> {
        });
        return 1;
    }

    private static int storageMigrate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String path = StringArgumentType.getString(ctx, "path");
        source.sendSuccess(() -> Lang.t(player, "migration_started"), false);
        mod().manager().executor().submit(() -> {
            var server = mod().server();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                try {
                    int imported = mod().storage().importFrom(Path.of(path));
                    if (imported < 0) {
                        // Not an ImageFrame-Fabric storage directory; try the original plugin layout.
                        new PluginMigration(mod()).migrateAsync(source, player, path, result -> {
                        });
                        return;
                    }
                    mod().frames().revalidate();
                    source.sendSuccess(() -> Lang.t(player, "migration_done", imported), false);
                } catch (Throwable throwable) {
                    String message = throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName() : throwable.getMessage();
                    source.sendFailure(Lang.t(player, "migration_failed", message));
                }
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Component message(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        return Lang.t(ctx.getSource().getPlayer(), key, args);
    }

    private static boolean isUpload(String url) {
        return url != null && "upload".equalsIgnoreCase(url.trim());
    }

    private static void awaitUpload(CommandContext<CommandSourceStack> ctx, PendingUpload pending,
                                    java.util.function.Consumer<Path> onReady) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        mod().manager().executor().submit(() -> {
            Path file;
            try {
                file = pending.awaitFile(ImageUploadService.EXPIRATION_MS + 30000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            var server = mod().server();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                if (file == null) {
                    source.sendFailure(Lang.t(player, "upload_expired"));
                    return;
                }
                source.sendSuccess(() -> Lang.t(player, "upload_received"), false);
                onReady.accept(file);
            });
        });
    }

    private static Component rawMessage(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        return Lang.raw(ctx.getSource().getPlayer(), key, args);
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, Component component) {
        ctx.getSource().sendSuccess(() -> component, false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(message(ctx, key, args));
        return 0;
    }

    private static ImageMap resolve(CommandContext<CommandSourceStack> ctx, String argument) {
        ImageMap image = mod().manager().byName(argument);
        if (image != null) {
            return image;
        }
        try {
            return mod().manager().byIndex(Integer.parseInt(argument));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ImageMap heldImage(ServerPlayer player) {
        Integer mapId = ItemsX.mapId(player.getMainHandItem());
        if (mapId == null) {
            mapId = ItemsX.mapId(player.getOffhandItem());
        }
        return mapId == null ? null : mod().manager().byAnyMapId(mapId);
    }

    private static List<String> imageNames() {
        List<String> names = new ArrayList<>();
        for (ImageMap image : mod().manager().all()) {
            names.add(image.name());
        }
        return names;
    }

    private static List<String> ditheringNames() {
        List<String> names = new ArrayList<>();
        for (Palette.Dithering dithering : Palette.Dithering.values()) {
            names.add(dithering.getName());
        }
        return names;
    }

    private static List<String> playerNames() {
        List<String> names = new ArrayList<>();
        var server = mod().server();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                names.add(player.getScoreboardName());
            }
        }
        return names;
    }
}
