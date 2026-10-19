package com.imageframe.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Small helper for {@code &}-style legacy colour codes and text components.
 */
public final class Text {

    private Text() {
    }

    public static MutableComponent legacy(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        MutableComponent result = Component.empty();
        StringBuilder buffer = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code == '#') {
                    int start = i + 2;
                    if (start < input.length() && input.charAt(start) == '#') {
                        start++;
                    }
                    if (start + 6 <= input.length()) {
                        try {
                            int rgb = Integer.parseInt(input.substring(start, start + 6), 16);
                            if (!buffer.isEmpty()) {
                                result.append(Component.literal(buffer.toString()).withStyle(style));
                                buffer.setLength(0);
                            }
                            style = style.withColor(TextColor.fromRgb(rgb));
                            i = start + 5;
                            continue;
                        } catch (NumberFormatException ignored) {
                            // fall through to plain text
                        }
                    }
                }
                ChatFormatting format = ChatFormatting.getByCode(code);
                if (format != null) {
                    if (!buffer.isEmpty()) {
                        result.append(Component.literal(buffer.toString()).withStyle(style));
                        buffer.setLength(0);
                    }
                    if (format == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (format.ordinal() < 16) {
                        style = style.withColor(format);
                    } else {
                        style = style.applyFormat(format);
                    }
                    i++;
                    continue;
                }
            }
            buffer.append(c);
        }
        if (!buffer.isEmpty()) {
            result.append(Component.literal(buffer.toString()).withStyle(style));
        }
        return result;
    }

    public static Component legacyNoItalic(String input) {
        return legacy(input).withStyle(Style.EMPTY.withItalic(false));
    }
}
