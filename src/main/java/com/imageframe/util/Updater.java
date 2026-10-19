package com.imageframe.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Minimal update checker. The original plugin queries SpigotMC/Modrinth; a Fabric
 * reimplementation has no fixed distribution channel, so the check URL is configurable.
 * The endpoint is expected to return JSON like
 * {@code {"version":"1.1.0","url":"https://...","notes":"..."}}.
 */
public final class Updater {

    public record CheckResult(boolean ok, String latestVersion, String downloadUrl, String notes, String error) {
    }

    private static final Gson GSON = new Gson();

    private final ImageFrameMod mod;

    public Updater(ImageFrameMod mod) {
        this.mod = mod;
    }

    public String currentVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("imageframe")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    public void checkAsync(Consumer<CheckResult> callback) {
        mod.manager().executor().submit(() -> {
            CheckResult result;
            try {
                result = check();
            } catch (Throwable throwable) {
                result = new CheckResult(false, null, null, null,
                        throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
            }
            CheckResult finalResult = result;
            var server = mod.server();
            if (server != null) {
                server.execute(() -> callback.accept(finalResult));
            }
        });
    }

    public CheckResult check() {
        String url = mod.config().updateCheckUrl;
        if (url == null || url.isBlank()) {
            return new CheckResult(false, null, null, null, "not-configured");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "ImageFrame-Fabric/" + currentVersion());
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) {
                return new CheckResult(false, null, null, null, "HTTP " + status);
            }
            try (InputStream input = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json == null) {
                    return new CheckResult(false, null, null, null, "empty response");
                }
                String latest = json.has("version") ? json.get("version").getAsString() : null;
                String download = json.has("url") ? json.get("url").getAsString() : null;
                String notes = json.has("notes") ? json.get("notes").getAsString() : null;
                return new CheckResult(true, latest, download, notes, null);
            }
        } catch (Throwable throwable) {
            return new CheckResult(false, null, null, null,
                    throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean isNewer(String latest, String current) {
        if (latest == null || latest.isBlank()) {
            return false;
        }
        return compareVersions(clean(latest), clean(current)) > 0;
    }

    private static String clean(String version) {
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        int plus = value.indexOf('+');
        if (plus >= 0) {
            value = value.substring(0, plus);
        }
        return value;
    }

    private static int compareVersions(String a, String b) {
        String[] left = a.split("[.\\-]");
        String[] right = b.split("[.\\-]");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            String partLeft = i < left.length ? left[i] : "0";
            String partRight = i < right.length ? right[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(partLeft), Integer.parseInt(partRight));
            } catch (NumberFormatException e) {
                cmp = partLeft.compareToIgnoreCase(partRight);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    public void logFailure(CheckResult result) {
        if (!result.ok() && !"not-configured".equals(result.error())) {
            ImageFrameFabric.LOGGER.warn("Update check failed: {}", result.error());
        }
    }
}
