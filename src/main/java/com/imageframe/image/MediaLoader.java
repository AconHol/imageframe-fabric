package com.imageframe.image;

import com.imageframe.Config;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and decodes images / animated GIFs.
 */
public final class MediaLoader {

    public static final int MAX_PIXELS = 40_000_000;

    public record Media(List<BufferedImage> frames, List<Integer> delaysMillis, boolean animated) {
    }

    private MediaLoader() {
    }

    public static Media load(String source, Config config) throws IOException {
        byte[] bytes = readBytes(source, config);
        boolean gif = isGif(bytes);
        if (gif) {
            List<MediaFrame> gifFrames = decodeGif(bytes);
            if (gifFrames.size() <= 1) {
                BufferedImage image = gifFrames.isEmpty() ? null : gifFrames.get(0).image;
                if (image == null) {
                    throw new IOException("Unable to decode gif");
                }
                List<BufferedImage> frames = new ArrayList<>();
                frames.add(image);
                List<Integer> delays = new ArrayList<>();
                delays.add(0);
                return new Media(frames, delays, false);
            }
            List<BufferedImage> frames = new ArrayList<>(gifFrames.size());
            List<Integer> delays = new ArrayList<>(gifFrames.size());
            for (MediaFrame frame : gifFrames) {
                frames.add(frame.image);
                delays.add(frame.delayMillis);
            }
            return new Media(frames, delays, true);
        }
        BufferedImage image = decodeStatic(bytes);
        List<BufferedImage> frames = new ArrayList<>();
        frames.add(image);
        List<Integer> delays = new ArrayList<>();
        delays.add(0);
        return new Media(frames, delays, false);
    }

    public static byte[] readBytes(String source, Config config) throws IOException {
        long max = config.maxImageFileSize;
        byte[] local = readLocalFile(source, config);
        if (local != null) {
            if (local.length > max) {
                throw new IOException("file too large");
            }
            return local;
        }
        URL url;
        try {
            url = URI.create(source).toURL();
        } catch (Exception e) {
            throw new IOException("invalid url", e);
        }
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("unsupported protocol");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(Math.max(5, config.maxProcessingTime) * 1000);
            connection.setRequestProperty("User-Agent", "ImageFrame-Fabric/1.0 (Minecraft 26.2)");
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long length = connection.getContentLengthLong();
            if (length > max) {
                throw new IOException("file too large");
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, max);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLocalFile(String source, Config config) throws IOException {
        Path path = null;
        try {
            if (source.startsWith("file:")) {
                path = Path.of(URI.create(source));
            } else if (!source.contains("://")) {
                Path candidate = Path.of(source);
                if (Files.isRegularFile(candidate)) {
                    path = candidate;
                }
            }
        } catch (Exception ignored) {
            path = null;
        }
        if (path == null) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("file not found: " + path);
        }
        return readLimited(Files.newInputStream(path), config.maxImageFileSize);
    }

    private static byte[] readLimited(InputStream input, long max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("file too large");
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
    }

    private static BufferedImage decodeStatic(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unable to decode image");
        }
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new IOException("image too large");
        }
        return image;
    }

    private record MediaFrame(BufferedImage image, int delayMillis) {
    }

    /**
     * GIF decoder following the GIF89a disposal rules. Java's ImageIO can read GIF but
     * returns raw frames, so frames are composited onto a logical screen here.
     */
    private static List<MediaFrame> decodeGif(byte[] bytes) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        List<MediaFrame> frames = new ArrayList<>();
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(stream);
            int width = -1;
            int height = -1;
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata != null) {
                IIOMetadataNode root = (IIOMetadataNode) streamMetadata.getAsTree(streamMetadata.getNativeMetadataFormatName());
                var screen = root.getElementsByTagName("LogicalScreenDescriptor");
                if (screen != null && screen.getLength() > 0) {
                    IIOMetadataNode descriptor = (IIOMetadataNode) screen.item(0);
                    width = Integer.parseInt(descriptor.getAttribute("logicalScreenWidth"));
                    height = Integer.parseInt(descriptor.getAttribute("logicalScreenHeight"));
                }
            }
            BufferedImage master = null;
            Graphics2D masterGraphics = null;
            for (int frameIndex = 0; ; frameIndex++) {
                BufferedImage image;
                try {
                    image = reader.read(frameIndex);
                } catch (IndexOutOfBoundsException end) {
                    break;
                }
                if (image == null) {
                    break;
                }
                if (width <= 0 || height <= 0) {
                    width = image.getWidth();
                    height = image.getHeight();
                }
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(frameIndex)
                        .getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode gce = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                int delay = gce == null ? 50 : Integer.parseInt(gce.getAttribute("delayTime")) * 10;
                String disposal = gce == null ? "" : gce.getAttribute("disposalMethod");
                int x = 0;
                int y = 0;
                if (master == null) {
                    master = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    masterGraphics = master.createGraphics();
                    masterGraphics.setBackground(new Color(0, 0, 0, 0));
                } else {
                    var children = root.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        var node = children.item(i);
                        if ("ImageDescriptor".equals(node.getNodeName())) {
                            var attributes = node.getAttributes();
                            x = Integer.parseInt(attributes.getNamedItem("imageLeftPosition").getNodeValue());
                            y = Integer.parseInt(attributes.getNamedItem("imageTopPosition").getNodeValue());
                        }
                    }
                }
                masterGraphics.drawImage(image, x, y, null);
                BufferedImage copy = new BufferedImage(master.getColorModel(), master.copyData(null),
                        master.isAlphaPremultiplied(), null);
                frames.add(new MediaFrame(copy, Math.max(10, delay)));
                if ("restoreToPrevious".equals(disposal)) {
                    BufferedImage from = null;
                    for (int i = frameIndex - 1; i >= 0; i--) {
                        if (!"restoreToPrevious".equals(disposal) || frameIndex == 0) {
                            from = frames.get(i).image;
                            break;
                        }
                    }
                    if (from != null) {
                        master = new BufferedImage(from.getColorModel(), from.copyData(null),
                                from.isAlphaPremultiplied(), null);
                        masterGraphics.dispose();
                        masterGraphics = master.createGraphics();
                        masterGraphics.setBackground(new Color(0, 0, 0, 0));
                    }
                } else if ("restoreToBackgroundColor".equals(disposal)) {
                    masterGraphics.clearRect(x, y, image.getWidth(), image.getHeight());
                }
            }
            if (masterGraphics != null) {
                masterGraphics.dispose();
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }
}
