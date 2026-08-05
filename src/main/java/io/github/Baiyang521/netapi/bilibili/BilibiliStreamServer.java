package io.github.Baiyang521.netapi.bilibili;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class BilibiliStreamServer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static BilibiliStreamServer instance;

    private final HttpServer server;
    private final String publicHost;
    private final File cacheDir;
    private final long maxAgeMillis;
    private final int maxCacheFiles;
    private final BilibiliHttpClient http = new BilibiliHttpClient();

    private BilibiliStreamServer(HttpServer server, String publicHost, File cacheDir,
                                 long maxAgeMillis, int maxCacheFiles) {
        this.server = server;
        this.publicHost = publicHost;
        this.cacheDir = cacheDir;
        this.maxAgeMillis = maxAgeMillis;
        this.maxCacheFiles = maxCacheFiles;
    }

    public static synchronized String createUrl(String key, String directUrl, File dataFolder) {
        try {
            BilibiliStreamServer current = instance;
            if (current == null) {
                current = start(dataFolder);
            }
            if (current == null) {
                log("Bilibili stream server is disabled or failed to start");
                return null;
            }
            current.prepare(key, directUrl);
            int port = current.server.getAddress().getPort();
            return "http://" + current.publicHost + ":" + port + "/bilibili/" + key + ".m4a";
        } catch (Exception e) {
            log("Unable to prepare Bilibili stream: " + e.getMessage());
            return null;
        }
    }

    private static synchronized BilibiliStreamServer start(File dataFolder) {
        if (instance != null) {
            return instance;
        }
        File folder = dataFolder == null ? new File("allmusic_server") : dataFolder;
        JsonObject config = loadConfig(folder);
        if (!getBoolean(config, "enabled", true)) {
            return null;
        }
        String bindHost = getString(config, "bind-host", "0.0.0.0");
        int port = getInt(config, "port", 28990);
        String publicHost = getString(config, "public-host", "localhost");
        File cacheDir = new File(folder, getString(config, "cache-dir", "netapi-cache"));
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            log("Unable to create Bilibili cache directory: " + cacheDir);
            return null;
        }
        long maxAgeMillis = getLong(config, "cache-max-age-hours", 24L) * 60L * 60L * 1000L;
        int maxCacheFiles = Math.max(1, getInt(config, "max-cache-files", 20));
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            server.createContext("/bilibili/", BilibiliStreamServer::handle);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "netapi-stream");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            instance = new BilibiliStreamServer(server, publicHost, cacheDir, maxAgeMillis, maxCacheFiles);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (instance != null) {
                    instance.server.stop(0);
                }
            }));
            log("Bilibili stream server started on port " + server.getAddress().getPort());
            return instance;
        } catch (IOException e) {
            log("Unable to start Bilibili stream server: " + e.getMessage());
            return null;
        }
    }

    private void prepare(String key, String directUrl) throws Exception {
        File file = fileFor(key);
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < maxAgeMillis) {
            return;
        }
        byte[] source = http.getBytes(directUrl, "");
        if (source == null) {
            throw new IOException("Unable to download Bilibili audio");
        }
        byte[] m4a = BilibiliRemuxer.toM4A(source);
        File temp = new File(cacheDir, key + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(m4a);
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        cleanup();
    }

    private File fileFor(String key) {
        return new File(cacheDir, key + ".m4a");
    }

    private void cleanup() {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null || files.length <= maxCacheFiles) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - maxCacheFiles; i++) {
            try {
                Files.deleteIfExists(files[i].toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            String key = name.endsWith(".m4a") ? name.substring(0, name.length() - 4) : name;
            if (instance == null || !TOKEN_PATTERN.matcher(key).matches()) {
                sendError(exchange, 404);
                return;
            }
            File file = instance.fileFor(key);
            if (!file.isFile()) {
                sendError(exchange, 404);
                return;
            }
            byte[] data = Files.readAllBytes(file.toPath());
            long start = 0L;
            long end = data.length - 1L;
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                long[] parsed = parseRange(range.substring(6), data.length);
                if (parsed == null) {
                    sendError(exchange, 416);
                    return;
                }
                start = parsed[0];
                end = parsed[1];
                partial = true;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "audio/mp4");
            headers.set("Accept-Ranges", "bytes");
            headers.set("Cache-Control", "private, max-age=86400");
            int status = partial ? 206 : 200;
            if (partial) {
                headers.set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            exchange.sendResponseHeaders(status, end - start + 1L);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(data, (int) start, (int) (end - start + 1L));
            }
        } catch (Exception e) {
            log("Bilibili stream request failed: " + e.getMessage());
            sendError(exchange, 500);
        }
    }

    private static long[] parseRange(String value, long length) {
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start;
            long end;
            String first = value.substring(0, dash).trim();
            String second = value.substring(dash + 1).trim();
            if (first.isEmpty()) {
                long suffix = Long.parseLong(second);
                if (suffix <= 0L) {
                    return null;
                }
                start = Math.max(0L, length - suffix);
                end = length - 1L;
            } else {
                start = Long.parseLong(first);
                end = second.isEmpty() ? length - 1L : Long.parseLong(second);
            }
            if (start < 0L || start >= length || end < start) {
                return null;
            }
            end = Math.min(end, length - 1L);
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sendError(HttpExchange exchange, int code) throws IOException {
        if (exchange.getResponseCode() != -1) {
            return;
        }
        exchange.sendResponseHeaders(code, -1L);
        exchange.close();
    }

    private static JsonObject loadConfig(File folder) {
        File file = new File(folder, "netapi.json");
        if (!file.exists()) {
            JsonObject defaults = new JsonObject();
            defaults.addProperty("enabled", true);
            defaults.addProperty("bind-host", "0.0.0.0");
            defaults.addProperty("port", 28990);
            defaults.addProperty("public-host", "localhost");
            defaults.addProperty("cache-dir", "netapi-cache");
            defaults.addProperty("cache-max-age-hours", 24);
            defaults.addProperty("max-cache-files", 20);
            try {
                Files.writeString(file.toPath(), AllMusic.gson.toJson(defaults));
            } catch (IOException e) {
                log("Unable to write netapi.json: " + e.getMessage());
            }
            return defaults;
        }
        try {
            JsonObject root = AllMusic.gson.fromJson(Files.readString(file.toPath()), JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (Exception e) {
            log("Unable to read netapi.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                return object.getAsJsonPrimitive(key).getAsBoolean();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                return object.getAsJsonPrimitive(key).getAsInt();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long getLong(JsonObject object, String key, long fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                return object.getAsJsonPrimitive(key).getAsLong();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            String value = object.getAsJsonPrimitive(key).getAsString();
            return value == null || value.isEmpty() ? fallback : value;
        }
        return fallback;
    }

    private static void log(String message) {
        if (AllMusic.log != null) {
            AllMusic.log.data("<light_purple>[Bilibili]<yellow>" + message);
        }
    }
}
