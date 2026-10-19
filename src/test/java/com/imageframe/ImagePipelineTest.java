package com.imageframe;

import com.imageframe.image.MediaLoader;
import com.imageframe.util.Palette;
import com.imageframe.util.Text;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImagePipelineTest {

    @BeforeAll
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void staticPngDecodes() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/test.png")) {
            assertNotNull(input, "test.png resource");
            Path temp = Files.createTempFile("imageframe-test", ".png");
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            MediaLoader.Media media = MediaLoader.load(temp.toString(), new Config());
            assertFalse(media.animated());
            assertEquals(1, media.frames().size());
            assertEquals(256, media.frames().get(0).getWidth());
            Files.deleteIfExists(temp);
        }
    }

    @Test
    public void animatedGifDecodes() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/anim.gif")) {
            assertNotNull(input, "anim.gif resource");
            Path temp = Files.createTempFile("imageframe-test", ".gif");
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            MediaLoader.Media media = MediaLoader.load(temp.toString(), new Config());
            assertTrue(media.animated());
            assertEquals(4, media.frames().size());
            assertEquals(100, media.delaysMillis().get(0));
            Files.deleteIfExists(temp);
        }
    }

    @Test
    public void paletteConversion() {
        BufferedImage opaque = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = opaque.createGraphics();
        graphics.setColor(new Color(200, 30, 30));
        graphics.fillRect(0, 0, 128, 128);
        graphics.dispose();
        byte[] colors = Palette.toMapColors(opaque, Palette.Dithering.NEAREST_COLOR);
        assertEquals(Palette.MAP_PIXELS, colors.length);
        for (byte color : colors) {
            assertTrue((color & 0xFF) != 0, "opaque pixels must not map to transparent");
        }

        BufferedImage transparent = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        byte[] empty = Palette.toMapColors(transparent, Palette.Dithering.NEAREST_COLOR);
        for (byte color : empty) {
            assertEquals(0, color & 0xFF);
        }

        for (Palette.Dithering dithering : Palette.Dithering.values()) {
            byte[] dithered = Palette.toMapColors(opaque, dithering);
            assertEquals(Palette.MAP_PIXELS, dithered.length);
        }
    }

    @Test
    public void fittingKeepsAspectRatio() {
        BufferedImage wide = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D wideGraphics = wide.createGraphics();
        wideGraphics.setColor(new Color(10, 200, 10));
        wideGraphics.fillRect(0, 0, 256, 128);
        wideGraphics.dispose();
        BufferedImage canvas = Palette.fit(wide, 2, 2);
        assertEquals(256, canvas.getWidth());
        assertEquals(256, canvas.getHeight());
        // The top and bottom rows must be transparent because the image is letterboxed.
        assertEquals(0, canvas.getRGB(0, 0) >>> 24);
        assertEquals(255, canvas.getRGB(0, 128) >>> 24);
        BufferedImage firstTile = Palette.tile(canvas, 0, 0);
        assertEquals(128, firstTile.getWidth());
        assertEquals(128, firstTile.getHeight());
    }

    @Test
    public void updateVersionComparison() {
        assertTrue(com.imageframe.util.Updater.isNewer("1.1.0", "1.0.0"));
        assertTrue(com.imageframe.util.Updater.isNewer("v2.0.0+mc26.2", "1.9.9"));
        assertFalse(com.imageframe.util.Updater.isNewer("1.0.0", "1.0.0"));
        assertFalse(com.imageframe.util.Updater.isNewer("0.9.0", "1.0.0"));
        assertFalse(com.imageframe.util.Updater.isNewer(null, "1.0.0"));
    }

    @Test
    public void legacyColorsParse() {
        var component = Text.legacy("&aHello &cWorld");
        assertEquals(2, component.getSiblings().size());
        assertEquals("Hello ", component.getSiblings().get(0).getString());
        assertEquals("World", component.getSiblings().get(1).getString());
    }

    @Test
    public void markerTypesResolve() {
        var types = new com.imageframe.image.MarkerTypes();
        assertNotNull(types.holder("red_marker"));
        assertNotNull(types.holder("RED_MARKER"));
        assertNotNull(types.holder("mansion"));
        assertFalse(types.suggestions().isEmpty());
    }

    @Test
    public void configDefaultsAreSane() {
        Config config = new Config();
        assertTrue(config.maxSize > 0);
        assertTrue(config.maxAnimationFrames > 0);
        List<String> allowed = config.urlWhitelist;
        assertNotNull(allowed);
    }
}
