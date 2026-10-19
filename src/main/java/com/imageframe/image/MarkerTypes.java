package com.imageframe.image;

import net.minecraft.core.Holder;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of the vanilla map decoration icons, exposed under their asset names.
 */
public final class MarkerTypes {

    private final Map<String, Holder<MapDecorationType>> byName = new LinkedHashMap<>();
    private final List<String> suggestions = new ArrayList<>();

    public MarkerTypes() {
        for (Field field : MapDecorationTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Holder.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof Holder<?> holder)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Holder<MapDecorationType> typed = (Holder<MapDecorationType>) holder;
                String key = field.getName().toLowerCase(Locale.ROOT);
                byName.putIfAbsent(key, typed);
                suggestions.add(key);
                if (typed.value() != null && typed.value().assetId() != null) {
                    byName.putIfAbsent(typed.value().assetId().getPath().toLowerCase(Locale.ROOT), typed);
                }
            } catch (ReflectiveOperationException ignored) {
                // ignore
            }
        }
        // Bukkit MapCursor.Type aliases used by the original plugin.
        alias("mansion", "woodland_mansion");
        alias("monument", "ocean_monument");
        alias("temple", "jungle_temple");
        alias("hut", "swamp_hut");
        alias("target", "target_point");
        alias("white_x", "target_x");
        alias("frame", "frame");
        alias("player", "player");
        suggestions.sort(String::compareTo);
    }

    private void alias(String name, String target) {
        Holder<MapDecorationType> holder = byName.get(target);
        if (holder != null) {
            byName.putIfAbsent(name, holder);
        }
    }

    public Holder<MapDecorationType> holder(String name) {
        if (name == null) {
            return null;
        }
        Holder<MapDecorationType> holder = byName.get(name.toLowerCase(Locale.ROOT));
        return holder == null ? byName.get("red_marker") : holder;
    }

    public boolean rendersOnFrame(String name) {
        Holder<MapDecorationType> holder = holder(name);
        return holder != null && holder.value() != null && holder.value().showOnItemFrame();
    }

    public List<String> suggestions() {
        return suggestions;
    }
}
