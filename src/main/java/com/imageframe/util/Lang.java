package com.imageframe.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.imageframe.Config;
import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Message catalog. Built-in English and Simplified Chinese strings can be extended or
 * overridden by JSON files in {@code config/imageframe/lang/<language>.json}; every
 * player can additionally pick their own language with {@code /imageframe preference}.
 */
public final class Lang {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, String[]> MESSAGES = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> OVERRIDES = new LinkedHashMap<>();
    private static final List<String> BUILT_IN = List.of("en_us", "zh_cn");

    private static void add(String key, String en, String zh) {
        MESSAGES.put(key, new String[]{en, zh});
    }

    static {
        add("prefix", "&b[ImageFrame]&r ", "&b[ImageFrame]&r ");
        add("no_permission", "&cYou do not have permission to do that.", "&c你没有权限执行此操作。");
        add("invalid_usage", "&cInvalid usage, use /imageframe help.", "&c用法错误，请输入 /imageframe help 查看帮助。");
        add("no_console", "&cThis command can only be used by a player.", "&c此命令只能由玩家执行。");
        add("not_an_image_map", "&cThat is not an ImageFrame image map.", "&c这不是 ImageFrame 的图片地图。");
        add("duplicate_name", "&cYou already have an image map with that name.", "&c你已经创建过同名图片地图。");
        add("oversize", "&cThat image is too large, the maximum is %s maps.", "&c图片尺寸过大，最大允许 %s 张地图。");
        add("creation_limit", "&cYou have reached your creation limit of %s image maps.", "&c你已达到 %s 个图片地图的创建上限。");
        add("not_enough_maps", "&cYou need %s empty maps in your inventory.", "&c你的背包中需要 %s 张空地图。");
        add("image_processing", "&cDownloading and processing your image, please wait...", "&e正在下载并处理图片，请稍候……");
        add("image_created", "&aImage map created successfully!", "&a图片地图创建成功！");
        add("unable_to_load", "&cUnable to load that image. Make sure it is a direct link to a png/jpeg/gif.", "&c无法加载该图片，请确认它是指向 png/jpeg/gif 的直链。");
        add("image_over_max_size", "&cThat image is larger than the configured maximum of %s bytes.", "&c图片超过配置的上限 %s 字节。");
        add("url_restricted", "&cThat URL is not whitelisted in the config.", "&c该链接不在配置的白名单中。");
        add("processing_timeout", "&cProcessing that image took too long.", "&c处理图片超时。");
        add("selection_begin", "&aSelection mode enabled. Right-click the first item frame, then the opposite corner.", "&a已进入选区模式。右键第一个展示框，再右键对角展示框。");
        add("selection_clear", "&eSelection mode disabled.", "&e已退出选区模式。");
        add("selection_corner1", "&aFirst corner selected, now right-click the opposite corner.", "&a已选择第一个角，请右键对角展示框。");
        add("selection_corner2", "&aSecond corner selected.", "&a已选择第二个角。");
        add("selection_success", "&aSelected %sx%s item frames. Use /imageframe create <name> <url> selection", "&a已选中 %sx%s 个展示框，使用 /imageframe create <名称> <链接> selection");
        add("selection_invalid", "&cInvalid selection, make sure the frames form a filled rectangle on the same wall.", "&c选区无效，请确保展示框在同一面并构成完整矩形。");
        add("selection_none", "&cYou have not confirmed a selection yet, use /imageframe select first.", "&c你还没有确认选区，请先使用 /imageframe select。");
        add("selection_oversize", "&cThe selection is larger than the maximum of %s maps.", "&c选区超过最大 %s 张地图的限制。");
        add("selection_incorrect_size", "&cThe selection must be %sx%s for that image.", "&c该图片需要 %sx%s 的选区。");
        add("item_frame_occupied", "&cOne or more item frames are occupied or protected.", "&c有展示框已被占用或受保护。");
        add("not_enough_space", "&cThere is not enough empty item frames here.", "&c这里的空展示框不够。");
        add("invalid_image_map", "&cThat combined map refers to an image map that no longer exists.", "&c该组合地图指向的图片地图已不存在。");
        add("combined_name", "&b%s &f(%sx%s)", "&b%s &f(%sx%s)");
        add("combined_lore1", "&aPlace on an item frame to display a %sx%s image", "&a放在物品展示框上可展示 %sx%s 的图片");
        add("combined_lore2", "&7The clicked frame will be used as the top-left corner", "&7点击的展示框将作为左上角");
        add("combined_lore3", "&7Image ID: %s", "&7图片编号：%s");
        add("combined_lore4", "&7Creator: %s", "&7创建者：%s");
        add("marker_limit", "&cThis map already has the maximum of %s markers.", "&c该地图已达到 %s 个标记的上限。");
        add("marker_duplicate", "&cThere is already a marker with that name.", "&c已存在同名标记。");
        add("marker_add_begin", "&aMove your crosshair over an ImageFrame map and right-click to place marker '%s' of '%s'.", "&a将准星对准 ImageFrame 地图，右键放置标记 '%s'（图片 %s）。");
        add("marker_not_render", "&eThat marker type is not shown on item frames.", "&e该标记类型不会显示在物品展示框中。");
        add("marker_added", "&aMarker added.", "&a标记已添加。");
        add("marker_removed", "&aMarker removed.", "&a已移除标记。");
        add("marker_not_found", "&cNo marker with that name exists on that image map.", "&c该图片上没有此名称的标记。");
        add("marker_cleared", "&aAll markers cleared.", "&a已清除全部标记。");
        add("marker_cancelled", "&eMarker placement cancelled.", "&e已取消标记放置。");
        add("reloaded", "&aConfiguration reloaded.", "&a配置已重新加载。");
        add("resynced", "&aStorage re-synced.", "&a存储已重新同步。");
        add("deleted", "&aDeleted image map '%s'.", "&a已删除图片地图 '%s'。");
        add("renamed", "&aRenamed image map to '%s'.", "&a已重命名为 '%s'。");
        add("refreshed", "&aRefreshed image map '%s'.", "&a已刷新图片地图 '%s'。");
        add("refresh_processing", "&eRefreshing '%s' from its source...", "&e正在从源地址刷新 '%s'……");
        add("playback_paused", "&eAnimation paused state changed.", "&e动画播放状态已切换。");
        add("playback_jump", "&aJumped animation to %s seconds.", "&a动画已跳转到 %s 秒。");
        add("not_animated", "&cThat image map is not animated.", "&c该图片不是动图。");
        add("gave_maps", "&aGave %s map item(s).", "&a已给予 %s 个地图物品。");
        add("gave_combined", "&aGave a combined map item.", "&a已给予组合地图物品。");
        add("gave_invisible_frame", "&aGave %s invisible item frame(s).", "&a已给予 %s 个隐形物品展示框。");
        add("gave_invisible_frame_target", "&aGave %s invisible item frame(s) to %s.", "&a已给予 %s 个隐形物品展示框（玩家 %s）。");
        add("potion_converted", "&aConverted %s item frame(s) into invisible item frames.", "&a已将 %s 个物品展示框转化为隐形展示框。");
        add("list_header", "&bImage maps of %s:", "&b%s 的图片地图：");
        add("list_line", "&7- &f#%s %s &7(%sx%s, %s)", "&7- &f#%s %s &7(%sx%s, %s)");
        add("list_empty", "&7No image maps found.", "&7没有找到图片地图。");
        add("info_header", "&bImage map #%s: %s", "&b图片地图 #%s：%s");
        add("info_line", "&7%s: &f%s", "&7%s：&f%s");
        add("set_access_done", "&aAccess updated for %s.", "&a已更新 %s 的访问权限。");
        add("access_none", "&cYou do not have access to that image map.", "&c你没有该图片地图的访问权限。");
        add("player_not_found", "&cThat player could not be found.", "&c找不到该玩家。");
        add("purged", "&aPurged %s image map(s).", "&a已清除 %s 个图片地图。");
        add("purged_all", "&aPurged all %s image map(s).", "&a已清除全部 %s 个图片地图。");
        add("overlay_only_held", "&cHold a filled map in your main hand to use this.", "&c请将一张已填充的地图拿在主手。");
        add("overlay_only_selection", "&cRight-click item frames holding filled maps to overlay them.", "&c请对含有已填充地图的展示框进行选区以叠加图片。");
        add("invalid_overlay_map", "&cThat map is already an ImageFrame map or is invalid.", "&c该地图已是 ImageFrame 地图或无效。");
        add("overlay_created", "&aOverlay image map created.", "&a叠加图片地图创建成功。");
        add("overlay_animated_unsupported", "&cOverlay images cannot be animated.", "&c叠加图片不支持动画。");
        add("overlay_unsupported_dimension", "&cOverlay maps must be single-map images.", "&c叠加地图必须是单张地图的图片。");
        add("unable_to_change_image_type", "&cChanging between animated and static images is not supported by refresh.", "&crefresh 不支持在静态图与动图之间切换。");
        add("not_supported", "&eThat feature is not supported by the Fabric reimplementation.", "&e此功能在 Fabric 复刻版中暂不支持。");
        add("version", "&bImageFrame (Fabric reimplementation) v%s &7- Minecraft 26.2", "&bImageFrame（Fabric 复刻版）v%s &7- Minecraft 26.2");
        add("help_header", "&bImageFrame commands:", "&bImageFrame 命令：");
        add("help_line", "&e%s &7- %s", "&e%s &7- %s");
        add("reload_config", "&eReloading config...", "&e正在重新加载配置……");
        add("admin_delete", "&aDeleted image map #%s.", "&a已删除图片地图 #%s。");
        add("preference_changed", "&aPreference '%s' set to '%s'.", "&a偏好 '%s' 已设为 '%s'。");
        add("preference_invalid", "&cInvalid preference type or value.", "&c无效的偏好类型或值。");
        add("preference_unset", "&aPreference '%s' reset to the server default.", "&a偏好 '%s' 已重置为服务器默认值。");
        add("preference_value", "&7%s: &f%s", "&7%s：&f%s");
        add("preference_types", "&7Available preferences: %s", "&7可用偏好：%s");
        add("animated_disabled_hint", "&eAnimated maps are disabled in your preference.", "&e你已在偏好中关闭动态地图。");
        add("unknown_error", "&cUnexpected error: %s", "&c发生意外错误：%s");
        add("download_failed", "&cDownload failed: %s", "&c下载失败：%s");
        add("decode_failed", "&cUnable to decode that image format.", "&c无法解码该图片格式。");
        add("invisible_frame_name", "Invisible Item Frame", "隐形物品展示框");
        add("invisible_frame_lore", "Place this on a block to get an invisible item frame", "放在方块上可获得隐形物品展示框");
        add("set_creator", "&aImage map #%s is now owned by %s (%s).", "&a图片地图 #%s 的创建者已改为 %s（%s）。");
        add("update_checking", "&eChecking for updates...", "&e正在检查更新……");
        add("update_latest", "&aYou are running the latest version (%s).", "&a你正在使用最新版本（%s）。");
        add("update_available", "&eA new version is available: %s &7(you have %s)&f %s", "&e有新版本可用：%s &7（当前 %s）&f %s");
        add("update_not_configured", "&eNo update check URL is configured in config.json (updateCheckUrl).", "&e未在 config.json 中配置更新检查地址（updateCheckUrl）。");
        add("update_failed", "&cUpdate check failed: %s", "&c检查更新失败：%s");
        add("upload_link", "&aUpload your image here: &e%s &7(expires in 5 minutes)", "&a在此上传图片：&e%s &7（5 分钟内有效）");
        add("upload_expired", "&cThe upload link expired.", "&c上传链接已过期。");
        add("upload_waiting", "&eWaiting for you to upload an image...", "&e正在等待上传图片……");
        add("upload_received", "&aImage uploaded, processing...", "&a图片已上传，正在处理……");
        add("upload_only_players", "&cUploading is only available to players.", "&c上传功能仅限玩家使用。");
        add("migration_started", "&eMigration started, see the console for progress.", "&e迁移已开始，进度请查看控制台。");
        add("migration_unsupported", "&c%s is not a supported source for migration.", "&c%s 不是受支持的迁移来源。");
        add("migration_done", "&aMigration finished: %s image map(s) imported.", "&a迁移完成：已导入 %s 个图片地图。");
        add("migration_failed", "&cMigration failed: %s", "&c迁移失败：%s");
        add("storage_migrate_unsupported", "&eThis build only uses the file storage backend, storagemigrate is not applicable.", "&e本构建仅使用文件存储后端，无需存储迁移。");
    
        add("info_url", "URL", "链接");
        add("info_size", "Size", "尺寸");
        add("info_dithering", "Dithering", "抖动算法");
        add("info_type", "Type", "类型");
        add("info_creator", "Creator", "创建者");
        add("info_created", "Created", "创建时间");
        add("info_map_ids", "Map IDs", "地图 ID");
        add("kind_static", "Static", "静态");
        add("kind_animated", "Animated", "动画");
        add("kind_overlay", "Overlay", "叠加");
        add("lore_image_id", "&7Image ID: &f#%s", "&7图片编号：&f#%s");
        add("lore_creator", "&7Creator: &f%s", "&7创建者：&f%s");
        add("upload_service_title", "ImageFrame upload service", "ImageFrame 图片上传服务");
        add("upload_service_body", "<p>This is the ImageFrame upload service.</p><p>Use the private link given by the <code>/imageframe create &lt;name&gt; upload ...</code> command.</p>", "<p>这是 ImageFrame 图片上传服务。</p><p>请使用游戏内 <code>/imageframe create &lt;名称&gt; upload ...</code> 命令生成的私有链接。</p>");
        add("upload_not_found_title", "Not found", "未找到");
        add("upload_not_found_body", "<p>Not found.</p>", "<p>页面不存在。</p>");
        add("upload_unknown_link", "<p>Unknown upload link.</p>", "<p>未知的上传链接。</p>");
        add("upload_expired_title", "Expired", "链接已过期");
        add("upload_expired_body", "<p>This upload link has expired. Run the command again in game.</p>", "<p>该上传链接已过期，请在游戏内重新执行命令。</p>");
        add("upload_error_title", "Error", "错误");
        add("upload_failed_body", "<p>Upload failed.</p>", "<p>上传失败。</p>");
        add("upload_page_title", "Upload image", "上传图片");
        add("upload_page_info", "<p>Image name: <b>%s</b> (%sx%s maps)</p>", "<p>图片名称：<b>%s</b>（%s×%s 格）</p>");
        add("upload_button", "Upload", "上传");
        add("upload_page_hint", "<p class=\"hint\">Maximum file size: %s MiB. The link expires in 5 minutes.</p>", "<p class=\"hint\">最大文件大小：%s MiB，链接 5 分钟内有效。</p>");
        add("upload_empty_body", "<p>Empty upload.</p>", "<p>上传内容为空。</p>");
        add("upload_no_file_body", "<p>No image file was found in the upload.</p>", "<p>上传内容中未找到图片文件。</p>");
        add("upload_unsupported_body", "<p>Unsupported file type. Use PNG, JPEG, GIF, BMP or WEBP.</p>", "<p>不支持的文件格式，请使用 PNG、JPEG、GIF、BMP 或 WEBP。</p>");
        add("upload_complete_title", "Upload complete", "上传完成");
        add("upload_complete_body", "<p>Your image was uploaded. You can close this page and return to the game.</p>", "<p>图片已上传，可以关闭此页面并返回游戏。</p>");
}

    private Lang() {
    }

    public static synchronized void load() {
        OVERRIDES.clear();
        Path dir = langDir();
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.warn("Unable to create language directory", e);
        }
        dumpDefaults(dir);
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                String fileName = path.getFileName().toString();
                String code = fileName.substring(0, fileName.length() - ".json".length()).toLowerCase(Locale.ROOT);
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {
                    }.getType());
                    if (map != null) {
                        OVERRIDES.put(code, new LinkedHashMap<>(map));
                    }
                } catch (Exception e) {
                    ImageFrameFabric.LOGGER.warn("Unable to read language file {}", fileName, e);
                }
            });
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.warn("Unable to list language files", e);
        }
    }

    private static Path langDir() {
        try {
            ImageFrameMod mod = ImageFrameMod.get();
            if (mod == null || mod.storage() == null) {
                return null;
            }
            return mod.storage().root().resolve("lang");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dumpDefaults(Path dir) {
        for (String code : BUILT_IN) {
            Path file = dir.resolve(code + ".json");
            if (Files.exists(file)) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> entry : MESSAGES.entrySet()) {
                map.put(entry.getKey(), "zh_cn".equals(code) ? entry.getValue()[1] : entry.getValue()[0]);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(map, writer);
            } catch (Exception e) {
                ImageFrameFabric.LOGGER.warn("Unable to write default language file {}", code, e);
            }
        }
    }

    public static synchronized List<String> availableLanguages() {
        TreeSet<String> all = new TreeSet<>(BUILT_IN);
        all.addAll(OVERRIDES.keySet());
        return new ArrayList<>(all);
    }

    public static boolean isLanguageSupported(String code) {
        if (code == null) {
            return false;
        }
        String lower = code.toLowerCase(Locale.ROOT);
        return BUILT_IN.contains(lower) || OVERRIDES.containsKey(lower);
    }

    private static String defaultLanguage() {
        try {
            Config config = ImageFrameMod.get().config();
            if (config != null && config.language != null) {
                String lang = config.language.toLowerCase(Locale.ROOT);
                if (isLanguageSupported(lang)) {
                    return lang;
                }
            }
        } catch (Throwable ignored) {
        }
        return "en_us";
    }

    private static String languageOf(ServerPlayer player) {
        if (player == null) {
            return defaultLanguage();
        }
        try {
            String lang = ImageFrameMod.get().preferences().language(player);
            if (lang != null && isLanguageSupported(lang)) {
                return lang.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return defaultLanguage();


    }

    private static String pattern(String key, String language) {
        Map<String, String> overrides = OVERRIDES.get(language);
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
        }
        String[] pair = MESSAGES.get(key);
        if (pair == null) {
            return key;
        }
        return "zh_cn".equals(language) ? pair[1] : pair[0];
    }

    public static String prefix(String language) {
        return pattern("prefix", language);
    }

    public static Component t(String key, Object... args) {
        return t(defaultLanguage(), key, args);
    }

    public static Component t(ServerPlayer player, String key, Object... args) {
        return t(languageOf(player), key, args);
    }

    private static Component t(String language, String key, Object... args) {
        String text = pattern(key, language);
        if (args.length > 0) {
            text = String.format(text, args);
        }
        return Text.legacy(prefix(language) + text);
    }

    /** A component without the plugin prefix, resolved for the given player. */
    public static Component raw(ServerPlayer player, String key, Object... args) {
        return raw(languageOf(player), key, args);
    }

    public static Component raw(String key, Object... args) {
        return raw(defaultLanguage(), key, args);
    }

    private static Component raw(String language, String key, Object... args) {
        String text = pattern(key, language);
        if (args.length > 0) {
            text = String.format(text, args);
        }
        return Text.legacy(text);
    }

    public static String plain(String key, Object... args) {
        String text = pattern(key, defaultLanguage());
        return args.length == 0 ? text : String.format(text, args);
    }
}
