package io.github.Baiyang521.netapi.bilibili;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class BilibiliWbiSigner {
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private BilibiliWbiSigner() {
    }

    public static String mixinKey(String imgUrl, String subUrl) {
        String raw = fileStem(imgUrl) + fileStem(subUrl);
        StringBuilder out = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index >= 0 && index < raw.length()) {
                out.append(raw.charAt(index));
                if (out.length() == 32) {
                    break;
                }
            }
        }
        if (out.length() < 32) {
            throw new IllegalArgumentException("Invalid Bilibili WBI key URLs");
        }
        return out.toString();
    }

    public static Map<String, String> sign(Map<String, String> original, String mixinKey) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (original != null) {
            for (Map.Entry<String, String> entry : original.entrySet()) {
                sorted.put(entry.getKey(), sanitize(entry.getValue()));
            }
        }
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));

        String query = queryString(sorted, false);
        String wrid = md5Hex(query + mixinKey);
        LinkedHashMap<String, String> result = new LinkedHashMap<>(sorted);
        result.put("w_rid", wrid);
        return result;
    }

    public static String queryString(Map<String, String> values, boolean sanitizeWbiValues) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (sanitizeWbiValues) {
                value = sanitize(value);
            }
            out.append(encode(entry.getKey())).append('=').append(encode(value));
        }
        return out.toString();
    }

    public static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int b = item & 0xFF;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' || b == '~') {
                out.append((char) b);
            } else {
                out.append('%').append(HEX[(b >>> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return out.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[!'()*]", "");
    }

    private static String fileStem(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                int b = item & 0xFF;
                out.append(HEX[(b >>> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return out.toString().toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
