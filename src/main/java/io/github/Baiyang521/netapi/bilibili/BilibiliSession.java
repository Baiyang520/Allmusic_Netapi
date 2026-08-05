package io.github.Baiyang521.netapi.bilibili;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BilibiliSession {
    private static final long MIXIN_TTL = 60L * 60L * 1000L;

    private final BilibiliHttpClient http;
    private volatile String buvid3 = "";
    private volatile String buvid4 = "";
    private volatile String mixinKey = "";
    private volatile long mixinExpireAt;

    public BilibiliSession(BilibiliHttpClient http) {
        this.http = http;
    }

    public synchronized void ensureDeviceIds() {
        if (!buvid3.isEmpty()) {
            return;
        }
        JsonObject root = http.getJson("https://api.bilibili.com/x/frontend/finger/spi", cookieHeader());
        if (root == null) {
            return;
        }
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : null;
        if (data == null) {
            log("Unable to read Bilibili device fingerprint");
            return;
        }
        String b3 = getString(data, "b_3", "");
        String b4 = getString(data, "b_4", "");
        if (b3.isEmpty()) {
            log("Bilibili device fingerprint is empty");
            return;
        }
        buvid3 = b3;
        buvid4 = b4;
    }

    public synchronized String mixinKey() {
        ensureDeviceIds();
        long now = System.currentTimeMillis();
        if (!mixinKey.isEmpty() && now < mixinExpireAt) {
            return mixinKey;
        }
        JsonObject root = http.getJson("https://api.bilibili.com/x/web-interface/nav", cookieHeader());
        if (root == null || !root.has("data") || !root.get("data").isJsonObject()) {
            return "";
        }
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("wbi_img") || !data.get("wbi_img").isJsonObject()) {
            log("Bilibili nav response has no wbi_img");
            return "";
        }
        JsonObject wbi = data.getAsJsonObject("wbi_img");
        String imgUrl = getString(wbi, "img_url", "");
        String subUrl = getString(wbi, "sub_url", "");
        try {
            mixinKey = BilibiliWbiSigner.mixinKey(imgUrl, subUrl);
            mixinExpireAt = now + MIXIN_TTL;
            return mixinKey;
        } catch (IllegalArgumentException e) {
            log("Unable to derive Bilibili WBI mixin key");
            return "";
        }
    }

    public synchronized String cookieHeader() {
        StringBuilder out = new StringBuilder();
        appendCookie(out, "buvid3", buvid3);
        appendCookie(out, "buvid4", buvid4);
        return out.toString();
    }

    public synchronized Map<String, String> sign(Map<String, String> params) {
        return BilibiliWbiSigner.sign(params, mixinKey());
    }

    private static void appendCookie(StringBuilder out, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append("; ");
        }
        out.append(name).append('=').append(value);
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            String value = object.getAsJsonPrimitive(key).getAsString();
            return value == null ? fallback : value;
        }
        return fallback;
    }

    private static void log(String message) {
        if (AllMusic.log != null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>" + message);
        }
    }
}
