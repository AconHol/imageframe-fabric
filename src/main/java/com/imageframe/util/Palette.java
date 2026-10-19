package com.imageframe.util;

import net.minecraft.world.level.material.MapColor;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Conversion of Java2D images into vanilla map colour indices.
 *
 * <p>The map palette has 256 entries: index 0 is transparent, indices 1..255 are
 * {@code MapColor} base colours multiplied by one of the four brightness modifiers.</p>
 */
public final class Palette {

    public static final int MAP_SIZE = 128;
    public static final int MAP_PIXELS = MAP_SIZE * MAP_SIZE;
    public static final byte TRANSPARENT = 0;

    private static final int[] RGB = new int[256];
    private static final byte[] LUT = new byte[32 * 32 * 32];

    static {
        RGB[0] = 0;
        for (int i = 1; i < 256; i++) {
            RGB[i] = MapColor.getColorFromPackedId(i) & 0xFFFFFF;
        }
        for (int r = 0; r < 32; r++) {
            for (int g = 0; g < 32; g++) {
                for (int b = 0; b < 32; b++) {
                    LUT[(r << 10) | (g << 5) | b] = nearestExact(r << 3, g << 3, b << 3);
                }
            }
        }
    }

    private Palette() {
    }

    private static byte nearestExact(int r, int g, int b) {
        int best = 1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 1; i < 256; i++) {
            int color = RGB[i];
            int dr = (color >> 16 & 0xFF) - r;
            int dg = (color >> 8 & 0xFF) - g;
            int db = (color & 0xFF) - b;
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return (byte) best;
    }

    public static byte nearest(int r, int g, int b) {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        return LUT[((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)];
    }

    public static int rgb(byte index) {
        return RGB[index & 0xFF];
    }

    public static int red(byte index) {
        return RGB[index & 0xFF] >> 16 & 0xFF;
    }

    public static int green(byte index) {
        return RGB[index & 0xFF] >> 8 & 0xFF;
    }

    public static int blue(byte index) {
        return RGB[index & 0xFF] & 0xFF;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Fit the source into a width*128 x height*128 transparent canvas, keeping aspect ratio. */
    public static BufferedImage fit(BufferedImage source, int width, int height) {
        int canvasWidth = width * MAP_SIZE;
        int canvasHeight = height * MAP_SIZE;
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double wRatio = (double) canvasWidth / (double) source.getWidth();
            double hRatio = (double) canvasHeight / (double) source.getHeight();
            if (wRatio < hRatio) {
                int h = (int) Math.round(source.getHeight() * wRatio);
                graphics.drawImage(source, 0, (canvasHeight - h) / 2, canvasWidth, h, null);
            } else {
                int w = (int) Math.round(source.getWidth() * hRatio);
                graphics.drawImage(source, (canvasWidth - w) / 2, 0, w, canvasHeight, null);
            }
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    /** Extract one 128x128 tile of a fitted canvas. */
    public static BufferedImage tile(BufferedImage canvas, int tileX, int tileY) {
        return canvas.getSubimage(tileX * MAP_SIZE, tileY * MAP_SIZE, MAP_SIZE, MAP_SIZE);
    }

    public enum Dithering {
        NEAREST_COLOR("nearest-color"),
        FLOYD_STEINBERG("floyd-steinberg"),
        ATKINSON("atkinson"),
        ORDERED_BAYER_4X4("ordered-bayer-4x4"),
        ORDERED_BAYER_8X8("ordered-bayer-8x8"),
        SIERRA_LITE("sierra-lite");

        private final String name;

        Dithering(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static Dithering byName(String name) {
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (Dithering dithering : values()) {
                    if (dithering.name.equals(lower)) {
                        return dithering;
                    }
                }
            }
            return NEAREST_COLOR;
        }

        public static Dithering byNameOrNull(String name) {
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (Dithering dithering : values()) {
                    if (dithering.name.equals(lower)) {
                        return dithering;
                    }
                }
            }
            return null;
        }
    }

    private record Diffusion(int x, int y, double weight) {
    }

    private static final Diffusion[] FLOYD_STEINBERG = {
            new Diffusion(1, 0, 7.0 / 16), new Diffusion(-1, 1, 3.0 / 16),
            new Diffusion(0, 1, 5.0 / 16), new Diffusion(1, 1, 1.0 / 16)};
    private static final Diffusion[] ATKINSON = {
            new Diffusion(1, 0, 1.0 / 8), new Diffusion(2, 0, 1.0 / 8),
            new Diffusion(-1, 1, 1.0 / 8), new Diffusion(0, 1, 1.0 / 8),
            new Diffusion(1, 1, 1.0 / 8), new Diffusion(0, 2, 1.0 / 8)};
    private static final Diffusion[] SIERRA_LITE = {
            new Diffusion(1, 0, 2.0 / 4), new Diffusion(-1, 1, 1.0 / 4),
            new Diffusion(0, 1, 1.0 / 4)};

    private static final int[][] BAYER_4X4 = {
            {0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static final int[][] BAYER_8X8 = {
            {0, 48, 12, 60, 3, 51, 15, 63}, {32, 16, 44, 28, 35, 19, 47, 31},
            {8, 56, 4, 52, 11, 59, 7, 55}, {40, 24, 36, 20, 43, 27, 39, 23},
            {2, 50, 14, 62, 1, 49, 13, 61}, {34, 18, 46, 30, 33, 17, 45, 29},
            {10, 58, 6, 54, 9, 57, 5, 53}, {42, 26, 38, 22, 41, 25, 37, 21}};
    private static final double BAYER_STRENGTH = 64.0;

    /** Convert an image to 16384 map colour bytes (row major). */
    public static byte[] toMapColors(BufferedImage image, Dithering dithering) {
        return switch (dithering) {
            case FLOYD_STEINBERG -> errorDiffusion(image, FLOYD_STEINBERG);
            case ATKINSON -> errorDiffusion(image, ATKINSON);
            case SIERRA_LITE -> errorDiffusion(image, SIERRA_LITE);
            case ORDERED_BAYER_4X4 -> ordered(image, BAYER_4X4);
            case ORDERED_BAYER_8X8 -> ordered(image, BAYER_8X8);
            case NEAREST_COLOR -> nearestColor(image);
        };
    }

    private static byte[] nearestColor(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] result = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha < 128) {
                    result[y * w + x] = TRANSPARENT;
                } else {
                    result[y * w + x] = nearest(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF);
                }
            }
        }
        return result;
    }

    private static byte[] errorDiffusion(BufferedImage image, Diffusion[] diffusions) {
        int w = image.getWidth();
        int h = image.getHeight();
        double[][] red = new double[h][w];
        double[][] green = new double[h][w];
        double[][] blue = new double[h][w];
        boolean[][] transparent = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                transparent[y][x] = (argb >>> 24) < 128;
                red[y][x] = argb >> 16 & 0xFF;
                green[y][x] = argb >> 8 & 0xFF;
                blue[y][x] = argb & 0xFF;
            }
        }
        byte[] result = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (transparent[y][x]) {
                    result[y * w + x] = TRANSPARENT;
                    continue;
                }
                int r = clamp((int) Math.round(red[y][x]));
                int g = clamp((int) Math.round(green[y][x]));
                int b = clamp((int) Math.round(blue[y][x]));
                byte mapped = nearest(r, g, b);
                result[y * w + x] = mapped;
                double errorR = red[y][x] - red(mapped);
                double errorG = green[y][x] - green(mapped);
                double errorB = blue[y][x] - blue(mapped);
                for (Diffusion diffusion : diffusions) {
                    int tx = x + diffusion.x;
                    int ty = y + diffusion.y;
                    if (tx >= 0 && tx < w && ty >= 0 && ty < h && !transparent[ty][tx]) {
                        red[ty][tx] += errorR * diffusion.weight;
                        green[ty][tx] += errorG * diffusion.weight;
                        blue[ty][tx] += errorB * diffusion.weight;
                    }
                }
            }
        }
        return result;
    }

    private static byte[] ordered(BufferedImage image, int[][] matrix) {
        int w = image.getWidth();
        int h = image.getHeight();
        int size = matrix.length;
        int area = size * size;
        byte[] result = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    result[y * w + x] = TRANSPARENT;
                    continue;
                }
                double threshold = ((matrix[y % size][x % size] + 0.5) / area) - 0.5;
                int r = clamp((int) Math.round((argb >> 16 & 0xFF) + threshold * BAYER_STRENGTH));
                int g = clamp((int) Math.round((argb >> 8 & 0xFF) + threshold * BAYER_STRENGTH));
                int b = clamp((int) Math.round((argb & 0xFF) + threshold * BAYER_STRENGTH));
                result[y * w + x] = nearest(r, g, b);
            }
        }
        return result;
    }

    /** Blend an image over an existing map colour buffer (transparent pixels keep the base colour). */
    public static byte[] overlay(byte[] base, BufferedImage image, Dithering dithering) {
        byte[] over = toMapColors(image, dithering);
        byte[] result = base == null ? new byte[MAP_PIXELS] : base.clone();
        for (int i = 0; i < MAP_PIXELS && i < over.length && i < result.length; i++) {
            if (over[i] != TRANSPARENT) {
                result[i] = over[i];
            }
        }
        return result;
    }
}
