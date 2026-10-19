package com.imageframe;

import com.imageframe.commands.ImageFrameCommand;
import com.imageframe.frame.CombinedMapManager;
import com.imageframe.frame.SelectionManager;
import com.imageframe.image.FrameTracker;
import com.imageframe.image.ImageMapManager;
import com.imageframe.image.MapSender;
import com.imageframe.invisible.InvisibleFrames;
import com.imageframe.marker.MarkerManager;
import com.imageframe.player.PlayerPreferences;
import com.imageframe.storage.Storage;
import com.imageframe.upload.ImageUploadService;
import com.imageframe.util.Lang;
import com.imageframe.util.Updater;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Glue class holding all managers and wiring the Fabric event callbacks.
 */
public final class ImageFrameMod {

    private static final ImageFrameMod INSTANCE = new ImageFrameMod();

    private Config config;
    private Storage storage;
    private ImageMapManager manager;
    private MapSender sender;
    private FrameTracker frames;
    private SelectionManager selection;
    private CombinedMapManager combined;
    private MarkerManager markers;
    private InvisibleFrames invisible;
    private PlayerPreferences preferences;
    private ImageUploadService uploadService;
    private Updater updater;
    private volatile MinecraftServer server;
    private DateTimeFormatter dateFormatter;
    private SimpleDateFormat legacyDateFormatter;

    private ImageFrameMod() {
    }

    public static ImageFrameMod get() {
        return INSTANCE;
    }

    public void init() {
        config = Config.load();
        try {
            dateFormatter = DateTimeFormatter.ofPattern(config.dateFormat).withZone(ZoneId.systemDefault());
        } catch (Exception e) {
            dateFormatter = null;
            legacyDateFormatter = new SimpleDateFormat(config.dateFormat);
        }
        manager = new ImageMapManager(this);
        storage = new Storage(this);
        sender = new MapSender(this);
        frames = new FrameTracker(this);
        selection = new SelectionManager(this);
        combined = new CombinedMapManager(this);
        markers = new MarkerManager(this);
        invisible = new InvisibleFrames(this);
        preferences = new PlayerPreferences(this);
        updater = new Updater(this);
        uploadService = new ImageUploadService(this);
        Lang.load();

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            frames.onEntityLoad(entity, level);
            invisible.onEntityLoad(entity, level);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            frames.onEntityUnload(entity);
            invisible.onEntityUnload(entity);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, mcServer) -> {
            ServerPlayer player = handler.getPlayer();
            sender.onPlayerJoin(player);
            preferences.load(player);
            markers.onQuit(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, mcServer) -> {
            ServerPlayer player = handler.getPlayer();
            sender.onPlayerLeave(player);
            selection.onQuit(player);
            markers.onQuit(player);
            preferences.save(player);
        });
        UseEntityCallback.EVENT.register(this::onUseEntity);
        AttackEntityCallback.EVENT.register(this::onAttackEntity);
        UseBlockCallback.EVENT.register(this::onUseBlock);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ImageFrameCommand.register(dispatcher));
        ImageFrameFabric.LOGGER.info("ImageFrame (Fabric reimplementation) initialized");
    }

    private void onServerStarted(MinecraftServer mcServer) {
        this.server = mcServer;
        storage.load();
        invisible.load();
        Lang.load();
        for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
            sender.onPlayerJoin(player);
        }
        frames.revalidate();
        uploadService.start();
        ImageFrameFabric.LOGGER.info("ImageFrame loaded {} image map(s)", manager.all().size());
    }

    private void onServerStopping(MinecraftServer mcServer) {
        try {
            uploadService.close();
            storage.saveAll();
            invisible.save();
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.warn("Failed to save ImageFrame data", throwable);
        }
    }

    private void onServerTick(MinecraftServer mcServer) {
        manager.tick();
        try {
            frames.tick();
            sender.tick();
            markers.tick();
            invisible.tick();
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.error("ImageFrame tick failed", throwable);
        }
    }

    private InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player,
                                         net.minecraft.world.level.Level world,
                                         InteractionHand hand, Entity entity,
                                         net.minecraft.world.phys.EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(entity instanceof ItemFrame frame)) {
            return InteractionResult.PASS;
        }
        if (markers.isEditing(serverPlayer)) {
            markers.confirm(serverPlayer, frame);
            return InteractionResult.SUCCESS;
        }
        if (selection.handleInteract(serverPlayer, frame)) {
            return InteractionResult.SUCCESS;
        }
        if (combined.place(serverPlayer, frame)) {
            return InteractionResult.SUCCESS;
        }
        if (server != null) {
            server.execute(() -> frames.track(frame));
        }
        return InteractionResult.PASS;
    }

    private InteractionResult onAttackEntity(net.minecraft.world.entity.player.Player player,
                                             net.minecraft.world.level.Level world,
                                             InteractionHand hand, Entity entity,
                                             net.minecraft.world.phys.EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(entity instanceof ItemFrame frame)) {
            return InteractionResult.PASS;
        }
        invisible.onLeftClickFrame(serverPlayer, frame);
        if (combined.onAttack(serverPlayer, frame)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
                                         net.minecraft.world.level.Level world, InteractionHand hand,
                                         net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (invisible.onUseBlock(serverPlayer, serverPlayer.getMainHandItem(), world, hand, hitResult)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Config config() {
        return config;
    }

    public Storage storage() {
        return storage;
    }

    public ImageMapManager manager() {
        return manager;
    }

    public MapSender sender() {
        return sender;
    }

    public FrameTracker frames() {
        return frames;
    }

    public SelectionManager selection() {
        return selection;
    }

    public CombinedMapManager combined() {
        return combined;
    }

    public MarkerManager markers() {
        return markers;
    }

    public InvisibleFrames invisible() {
        return invisible;
    }

    public PlayerPreferences preferences() {
        return preferences;
    }

    public Updater updater() {
        return updater;
    }

    public ImageUploadService uploadService() {
        return uploadService;
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean permits(ServerPlayer player, Perms.Node node) {
        return Perms.check(player, node);
    }

    public long tickCounter() {
        return manager == null ? 0 : manager.tickCounter();
    }

    public ServerLevel level(String dimensionKey) {
        MinecraftServer mcServer = server;
        if (mcServer == null || dimensionKey == null) {
            return null;
        }
        for (ServerLevel level : mcServer.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimensionKey)) {
                return level;
            }
        }
        return null;
    }

    public String formatTime(long millis) {
        try {
            if (dateFormatter != null) {
                return dateFormatter.format(Instant.ofEpochMilli(millis));
            }
            return legacyDateFormatter.format(new Date(millis));
        } catch (Exception e) {
            return Long.toString(millis);
        }
    }

    public void reloadConfig() {
        config = Config.load();
        Lang.load();
        if (uploadService != null) {
            uploadService.restart();
        }
    }

    public List<ImageMapManager> managers() {
        return List.of(manager);
    }
}
