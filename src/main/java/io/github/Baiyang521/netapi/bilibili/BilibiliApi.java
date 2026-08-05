package io.github.Baiyang521.netapi.bilibili;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BilibiliApi {
    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}", Pattern.CASE_INSENSITIVE);
    private static final Pattern AID_PATTERN = Pattern.compile("av[0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]+");
    private static final long CACHE_TTL = 30L * 60L * 1000L;

    private final BilibiliHttpClient http = new BilibiliHttpClient();
    private final BilibiliSession session = new BilibiliSession(http);
    private final Map<String, Cached<VideoInfo>> videoCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<String>> urlCache = new ConcurrentHashMap<>();

    public String normalizeId(String arg) {
        String value = arg == null ? "" : arg.trim();
        Matcher bvid = BVID_PATTERN.matcher(value);
        if (bvid.find()) {
            String id = bvid.group();
            return id.startsWith("bv") ? "BV" + id.substring(2) : id;
        }
        Matcher aid = AID_PATTERN.matcher(value);
        if (aid.find()) {
            return aid.group().toLowerCase(Locale.ROOT);
        }
        Matcher number = NUMBER_PATTERN.matcher(value);
        if (number.find()) {
            return "av" + number.group();
        }
        return value;
    }

    public boolean checkId(String id) {
        String value = normalizeId(id);
        return BVID_PATTERN.matcher(value).matches()
                || AID_PATTERN.matcher(value).matches();
    }

    public List<SearchResult> search(String keyword) {
        String mixinKey = session.mixinKey();
        if (mixinKey.isEmpty()) {
            log("Bilibili search is unavailable: no WBI key");
            return Collections.emptyList();
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword == null ? "" : keyword);
        params.put("page", "1");
        Map<String, String> signed = BilibiliWbiSigner.sign(params, mixinKey);
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?"
                + BilibiliWbiSigner.queryString(signed, false);

        JsonObject root = http.getJson(url, session.cookieHeader());
        if (root == null) {
            return Collections.emptyList();
        }
        int code = getInt(root, "code", -1);
        if (code != 0) {
            log("Bilibili search failed: code=" + code + ", message=" + getString(root, "message", ""));
            return Collections.emptyList();
        }
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : null;
        if (data == null || !data.has("result") || !data.get("result").isJsonArray()) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();
        JsonArray array = data.getAsJsonArray("result");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String bvid = getString(item, "bvid", "");
            String aid = getString(item, "aid", "");
            String id = bvid.isEmpty() ? (aid.isEmpty() ? "" : "av" + aid) : bvid;
            if (id.isEmpty()) {
                continue;
            }
            String title = cleanTitle(getString(item, "title", ""));
            String author = getString(item, "author", "");
            String duration = getString(item, "duration", "");
            results.add(new SearchResult(id, title, author, duration.isEmpty() ? "Bilibili" : duration));
        }
        return results;
    }

    public VideoInfo getVideo(String id) {
        String key = normalizeId(id);
        Cached<VideoInfo> cached = videoCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.value;
        }

        String url;
        if (key.startsWith("av")) {
            url = "https://api.bilibili.com/x/web-interface/view?aid=" + key.substring(2);
        } else {
            url = "https://api.bilibili.com/x/web-interface/view?bvid=" + BilibiliWbiSigner.encode(key);
        }

        JsonObject root = http.getJson(url, session.cookieHeader());
        if (root == null) {
            return null;
        }
        int code = getInt(root, "code", -1);
        if (code != 0) {
            log("Bilibili view failed: code=" + code + ", message=" + getString(root, "message", ""));
            return null;
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            log("Bilibili view response has no data");
            return null;
        }
        JsonObject data = root.getAsJsonObject("data");
        long cid = getLong(data, "cid", 0L);
        if (cid == 0L) {
            log("Bilibili view response has no cid");
            return null;
        }

        String bvid = getString(data, "bvid", key);
        long aid = getLong(data, "aid", 0L);
        String title = getString(data, "title", "");
        JsonObject owner = data.has("owner") && data.get("owner").isJsonObject()
                ? data.getAsJsonObject("owner") : null;
        String author = getString(owner, "name", "");
        long duration = getLong(data, "duration", 0L);
        String pic = normalizePic(getString(data, "pic", ""));
        VideoInfo info = new VideoInfo(bvid, aid, cid, title, author, duration, pic);

        videoCache.put(key, new Cached<>(info));
        videoCache.put(bvid, new Cached<>(info));
        if (!key.startsWith("av") && aid > 0L) {
            videoCache.put("av" + aid, new Cached<>(info));
        }
        return info;
    }

    public String getPlayUrl(String id) {
        String key = normalizeId(id);
        Cached<String> cachedUrl = urlCache.get(key);
        if (cachedUrl != null && !cachedUrl.expired()) {
            return cachedUrl.value;
        }

        VideoInfo info = getVideo(key);
        if (info == null) {
            return null;
        }
        String cacheKey = info.bvid + ":" + info.cid;
        cachedUrl = urlCache.get(cacheKey);
        if (cachedUrl != null && !cachedUrl.expired()) {
            return cachedUrl.value;
        }

        String url = "https://api.bilibili.com/x/player/playurl?bvid="
                + BilibiliWbiSigner.encode(info.bvid)
                + "&cid=" + info.cid
                + "&fnval=16&fnver=0&fourk=1";
        JsonObject root = http.getJson(url, session.cookieHeader());
        if (root == null) {
            return null;
        }
        int code = getInt(root, "code", -1);
        if (code != 0) {
            log("Bilibili playurl failed: code=" + code + ", message=" + getString(root, "message", ""));
            return null;
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            return null;
        }
        JsonObject data = root.getAsJsonObject("data");
        String playUrl = pickDashAudio(data);
        if (playUrl == null) {
            playUrl = pickDurl(data);
        }
        if (playUrl == null) {
            log("Bilibili playurl response has no usable audio stream");
            return null;
        }

        Cached<String> cached = new Cached<>(playUrl);
        urlCache.put(key, cached);
        urlCache.put(cacheKey, cached);
        return playUrl;
    }

    public static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String value = title.replaceAll("<[^>]+>", "");
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private static String pickDashAudio(JsonObject data) {
        if (!data.has("dash") || !data.get("dash").isJsonObject()) {
            return null;
        }
        JsonObject dash = data.getAsJsonObject("dash");
        if (!dash.has("audio") || !dash.get("audio").isJsonArray()) {
            return null;
        }
        JsonArray audio = dash.getAsJsonArray("audio");
        JsonObject best = null;
        long bestBandwidth = -1L;
        for (JsonElement element : audio) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            long bandwidth = getLong(item, "bandwidth", 0L);
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth;
                best = item;
            }
        }
        if (best == null) {
            return null;
        }
        String url = getString(best, "baseUrl", "");
        if (url.isEmpty()) {
            url = getString(best, "base_url", "");
        }
        return url.isEmpty() ? null : url;
    }

    private static String pickDurl(JsonObject data) {
        if (!data.has("durl") || !data.get("durl").isJsonArray()) {
            return null;
        }
        JsonArray durl = data.getAsJsonArray("durl");
        if (durl.size() == 0 || !durl.get(0).isJsonObject()) {
            return null;
        }
        String url = getString(durl.get(0).getAsJsonObject(), "url", "");
        return url.isEmpty() ? null : url;
    }

    private static String normalizePic(String pic) {
        if (pic == null || pic.isEmpty()) {
            return "";
        }
        if (pic.startsWith("//")) {
            return "https:" + pic;
        }
        if (pic.startsWith("http://")) {
            return "https://" + pic.substring("http://".length());
        }
        return pic;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            String value = object.getAsJsonPrimitive(key).getAsString();
            return value == null ? fallback : value;
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

    private static int getInt(JsonObject object, String key, int fallback) {
        return (int) getLong(object, key, fallback);
    }

    private static void log(String message) {
        if (AllMusic.log != null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>" + message);
        }
    }

    public static final class SearchResult {
        public final String id;
        public final String name;
        public final String author;
        public final String al;

        public SearchResult(String id, String name, String author, String al) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.al = al;
        }
    }

    private static final class Cached<T> {
        private final T value;
        private final long expireAt;

        private Cached(T value) {
            this.value = value;
            this.expireAt = System.currentTimeMillis() + CACHE_TTL;
        }

        private boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
