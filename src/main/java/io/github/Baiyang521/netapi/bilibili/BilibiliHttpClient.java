package io.github.Baiyang521.netapi.bilibili;

import com.coloryr.allmusic.libs.org.apache.hc.client5.http.classic.methods.HttpGet;
import com.coloryr.allmusic.libs.org.apache.hc.client5.http.config.RequestConfig;
import com.coloryr.allmusic.libs.org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import com.coloryr.allmusic.libs.org.apache.hc.client5.http.protocol.HttpClientContext;
import com.coloryr.allmusic.libs.org.apache.hc.core5.http.HttpEntity;
import com.coloryr.allmusic.libs.org.apache.hc.core5.http.io.entity.EntityUtils;
import com.coloryr.allmusic.libs.org.apache.hc.core5.util.Timeout;
import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.music.MusicHttpClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class BilibiliHttpClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024 * 1024;

    public JsonObject getJson(String url, String cookieHeader) {
        String body = get(url, cookieHeader);
        if (body == null) {
            return null;
        }
        try {
            return AllMusic.gson.fromJson(body, JsonObject.class);
        } catch (JsonParseException e) {
            log("Bilibili response is not valid JSON");
            return null;
        }
    }

    public String get(String url, String cookieHeader) {
        byte[] body = getBytes(url, cookieHeader);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    public byte[] getBytes(String url, String cookieHeader) {
        try {
            HttpGet request = new HttpGet(url);
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            request.setHeader("Accept", "application/json, text/plain, */*");
            request.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            request.setHeader("Referer", "https://www.bilibili.com/");
            request.setHeader("Origin", "https://www.bilibili.com");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                request.setHeader("Cookie", cookieHeader);
            }

            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(10))
                    .setResponseTimeout(Timeout.ofSeconds(120))
                    .build());
            try (CloseableHttpResponse response = MusicHttpClient.client.execute(request, context)) {
                int statusCode = response.getCode();
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    log("Bilibili returned an empty response");
                    return null;
                }
                byte[] body = readBody(entity.getContent());
                EntityUtils.consume(entity);
                if (statusCode != 200) {
                    log("Bilibili HTTP " + statusCode + ": "
                            + truncate(new String(body, StandardCharsets.UTF_8)));
                    return null;
                }
                return body;
            }
        } catch (Exception e) {
            log("Bilibili request failed: " + e.getMessage());
        }
        return null;
    }

    private static byte[] readBody(InputStream input) throws Exception {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int length;
            while ((length = stream.read(buffer)) != -1) {
                total += length;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Bilibili response is too large");
                }
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static void log(String message) {
        if (AllMusic.log != null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>" + message);
        }
    }
}
