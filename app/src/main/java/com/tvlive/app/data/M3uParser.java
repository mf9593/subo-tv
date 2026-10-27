package com.tvlive.app.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 M3U 播放列表解析器。
 *
 * <p>支持两种写法：</p>
 * <pre>
 * #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1 综合
 * http://example.com/cctv1.m3u8
 *
 * CCTV-2 财经,http://example.com/cctv2.m3u8
 * </pre>
 * <p>也接受只有 URL 的行（名称自动编号）。</p>
 */
public final class M3uParser {

    private M3uParser() {
    }

    public static List<Channel> parse(String text) {
        List<Channel> list = new ArrayList<Channel>();
        if (text == null || text.trim().isEmpty()) return list;

        String[] lines = text.split("\\r?\\n");
        String pendingName = null;
        String pendingGroup = null;
        int number = 1;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "";
                pendingGroup = attribute(line, "group-title");
                if (pendingName.isEmpty()) {
                    String tvgName = attribute(line, "tvg-name");
                    if (tvgName != null) pendingName = tvgName;
                }
                continue;
            }
            if (line.startsWith("#")) continue;

            String url = line;
            String name = pendingName;

            // 兼容「名称,地址」这种一行写法
            if (!isUrl(line) && line.indexOf(',') > 0) {
                int comma = line.indexOf(',');
                String maybeName = line.substring(0, comma).trim();
                String maybeUrl = line.substring(comma + 1).trim();
                if (isUrl(maybeUrl)) {
                    name = maybeName;
                    url = maybeUrl;
                }
            }

            if (!isUrl(url)) {
                pendingName = null;
                pendingGroup = null;
                continue;
            }

            if (name == null || name.isEmpty()) {
                name = "频道 " + number;
            }
            list.add(new Channel(number, name, url, pendingGroup));
            number++;
            pendingName = null;
            pendingGroup = null;
        }
        return list;
    }

    private static boolean isUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("rtsp://") || lower.startsWith("rtsps://")
                || lower.startsWith("rtmp://") || lower.startsWith("rtmps://")
                || lower.startsWith("udp://") || lower.startsWith("rtp://");
    }

    /** 取 key="value" 形式属性 */
    private static String attribute(String line, String key) {
        String token = key + "=\"";
        int start = line.indexOf(token);
        if (start < 0) return null;
        start += token.length();
        int end = line.indexOf('"', start);
        if (end < 0) return null;
        String value = line.substring(start, end).trim();
        return value.isEmpty() ? null : value;
    }
}
