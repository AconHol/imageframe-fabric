package com.imageframe.upload;

import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.imageframe.util.Lang;

/**
 * Embedded upload web service: the Fabric replacement for the original plugin's upload
 * service. A player runs {@code /imageframe create <name> upload ...}, receives a private
 * link, uploads an image in the browser, and creation continues automatically.
 */
public final class ImageUploadService implements AutoCloseable {

    public static final long EXPIRATION_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_HTML_BYTES = 1024L * 1024L;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F', '8'};
    private static final byte[] BMP_MAGIC = {'B', 'M'};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};

    private final ImageFrameMod mod;
    private final Map<UUID, PendingUpload> pending = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executors;
    private ScheduledExecutorService cleaner;
    private Path uploadDir;

    public ImageUploadService(ImageFrameMod mod) {
        this.mod = mod;
    }

    public boolean isOperational() {
        return server != null;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        var config = mod.config().uploadService;
        if (!config.enabled) {
            return;
        }
        try {
            uploadDir = mod.storage().root().resolve("upload");
            Files.createDirectories(uploadDir);
            server = HttpServer.create(new InetSocketAddress(config.host, config.port), 0);
            executors = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "ImageFrame-Upload");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executors);
            server.createContext("/", this::handle);
            server.start();
            cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ImageFrame-Upload-Cleaner");
                thread.setDaemon(true);
                return thread;
            });
            cleaner.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
            ImageFrameFabric.LOGGER.info("ImageFrame upload service listening on {}:{}", config.host, config.port);
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.warn("Unable to start the ImageFrame upload service", throwable);
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (cleaner != null) {
            cleaner.shutdownNow();
            cleaner = null;
        }
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable ignored) {
            }
            server = null;
        }
        if (executors != null) {
            executors.shutdownNow();
            executors = null;
        }
        for (PendingUpload upload : pending.values()) {
            upload.cancel();
        }
        pending.clear();
    }

    public synchronized void restart() {
        close();
        start();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingUpload> entry : pending.entrySet()) {
            PendingUpload upload = entry.getValue();
            if (now - upload.createdAt() > EXPIRATION_MS + 60000L) {
                pending.remove(entry.getKey());
                upload.cancel();
            }
        }
    }

    public PendingUpload createPending(ServerPlayer player, String name, int width, int height) {
        PendingUpload upload = new PendingUpload(player.getUUID(), player.getScoreboardName(), name, width, height);
        pending.put(upload.token(), upload);
        ImageFrameFabric.LOGGER.info("Created upload link for {} ({}) token={}", player.getScoreboardName(), name, upload.token());
        return upload;
    }

    public String publicUrl(PendingUpload upload) {
        var config = mod.config().uploadService;
        String base = config.publicUrl == null ? "" : config.publicUrl.trim();
        if (base.isEmpty()) {
            base = "http://127.0.0.1:" + config.port;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/upload/" + upload.token();
    }

    // ------------------------------------------------------------------
    // HTTP handling
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                sendHtml(exchange, 200, page(Lang.plain("upload_service_title"), Lang.plain("upload_service_body")));
                return;
            }
            if (path.startsWith("/upload/")) {
                String tokenPart = path.substring("/upload/".length());
                UUID token;
                try {
                    token = UUID.fromString(tokenPart);
                } catch (IllegalArgumentException e) {
                    sendHtml(exchange, 404, page(Lang.plain("upload_not_found_title"), Lang.plain("upload_unknown_link")));
                    return;
                }
                PendingUpload upload = pending.get(token);
                if (upload == null || upload.isExpired()) {
                    sendHtml(exchange, 410, page(Lang.plain("upload_expired_title"), Lang.plain("upload_expired_body")));
                    return;
                }
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handlePost(exchange, upload);
                } else {
                    sendHtml(exchange, 200, uploadPage(upload));
                }
                return;
            }
            sendHtml(exchange, 404, page(Lang.plain("upload_not_found_title"), Lang.plain("upload_not_found_body")));
        } catch (Throwable throwable) {
            ImageFrameFabric.LOGGER.warn("Upload request failed", throwable);
            try {
                sendHtml(exchange, 500, page(Lang.plain("upload_error_title"), Lang.plain("upload_failed_body")));
            } catch (Throwable ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private String uploadPage(PendingUpload upload) {
        long maxMb = Math.max(1, mod.config().maxImageFileSize / (1024L * 1024L));
        return page(Lang.plain("upload_page_title"),
                Lang.plain("upload_page_info", escape(upload.name()), upload.width(), upload.height())
                        + "<form method=\"post\" enctype=\"multipart/form-data\">"
                        + "<input type=\"file\" name=\"image\" accept=\"image/png,image/jpeg,image/gif,image/bmp,image/webp\" required>"
                        + "<button type=\"submit\">" + Lang.plain("upload_button") + "</button></form>"
                        + Lang.plain("upload_page_hint", maxMb));
    }

    private void handlePost(HttpExchange exchange, PendingUpload upload) throws IOException {
        long maxBytes = Math.max(1024L, mod.config().maxImageFileSize) + 2L * 1024L * 1024L;
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = readLimited(input, maxBytes);
        }
        if (body.length == 0) {
            sendHtml(exchange, 400, page(Lang.plain("upload_error_title"), Lang.plain("upload_empty_body")));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] file = extractFile(body, contentType);
        if (file == null || file.length == 0) {
            sendHtml(exchange, 400, page(Lang.plain("upload_error_title"), Lang.plain("upload_no_file_body")));
            return;
        }
        String extension = detectExtension(file);
        if (extension == null) {
            sendHtml(exchange, 400, page(Lang.plain("upload_error_title"), Lang.plain("upload_unsupported_body")));
            return;
        }
        Path target = uploadDir.resolve(upload.token() + extension);
        Files.write(target, file);
        upload.complete(target);
        sendHtml(exchange, 200, page(Lang.plain("upload_complete_title"), Lang.plain("upload_complete_body")));
        ImageFrameFabric.LOGGER.info("Received upload '{}' from {} ({} bytes)", upload.name(), upload.ownerName(), file.length);
    }

    private static byte[] extractFile(byte[] body, String contentType) {
        if (contentType == null) {
            return null;
        }
        int boundaryIndex = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (boundaryIndex < 0) {
            // The page always posts multipart, but accept a raw body as a fallback.
            return body;
        }
        String boundary = contentType.substring(boundaryIndex + "boundary=".length()).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int partStart = indexOf(body, delimiter, 0);
        while (partStart >= 0) {
            int headersStart = partStart + delimiter.length;
            if (headersStart + 2 <= body.length && body[headersStart] == '\r' && body[headersStart + 1] == '\n') {
                headersStart += 2;
            }
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headersStart);
            if (headersEnd < 0) {
                return null;
            }
            String headers = new String(body, headersStart, headersEnd - headersStart, StandardCharsets.ISO_8859_1);
            int dataStart = headersEnd + 4;
            int next = indexOf(body, delimiter, dataStart);
            if (next < 0) {
                return null;
            }
            int dataEnd = next;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            }
            if (headers.toLowerCase(Locale.ROOT).contains("filename=")) {
                byte[] data = new byte[dataEnd - dataStart];
                System.arraycopy(body, dataStart, data, 0, data.length);
                return data;
            }
            partStart = next;
        }
        return null;
    }

    private static String detectExtension(byte[] file) {
        if (startsWith(file, PNG_MAGIC)) {
            return ".png";
        }
        if (startsWith(file, JPEG_MAGIC)) {
            return ".jpg";
        }
        if (startsWith(file, GIF_MAGIC)) {
            return ".gif";
        }
        if (startsWith(file, BMP_MAGIC)) {
            return ".bmp";
        }
        if (startsWith(file, RIFF_MAGIC) && file.length > 12
                && file[8] == 'W' && file[9] == 'E' && file[10] == 'B' && file[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Upload too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escape(title) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;"
                + "background:#111;color:#eee}button,input{font-size:16px;margin:8px 0}"
                + "code{background:#222;padding:2px 4px;border-radius:4px}.hint{color:#aaa;font-size:14px}"
                + "</style></head><body><h1>ImageFrame</h1>" + body + "</body></html>";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
