package com.tvlive.app.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>也接受只有 URL 的行（名称自动编号），以及 {@code #genre#} 分组标记。</p>
 *
 * <p><b>多源合并：</b>名称和分组完全相同的多个地址会被合并成一个频道，
 * 按出现顺序保存为多个源，供「自动换源」使用。</p>
 */
public final class M3uParser {

    private M3uParser() {
    }

    public static List<Channel> parse(String text) {
        if (text == null || text.trim().isEmpty()) return new ArrayList<Channel>();

        String[] lines = text.split("\\r?\\n");
        String pendingName = null;
        String pendingGroup = null;      // #EXTINF 的单条分组（仅对下一行有效）
        String genreGroup = null;        // #genre# 的持续分组（对其后所有行有效）
        int number = 1;

        // key = group + "\u0000" + name，value = 地址列表；LinkedHashMap 保序
        Map<String, List<String>> byKey = new LinkedHashMap<String, List<String>>();
        Map<String, String> nameByKey = new LinkedHashMap<String, String>();
        Map<String, String> groupByKey = new LinkedHashMap<String, String>();

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            // #genre# 分组标记，例如「央视频道,#genre#」——对其后所有行持续生效
            if (line.endsWith(",#genre#")) {
                String genre = line.substring(0, line.length() - ",#genre#".length()).trim();
                // 兜底：去掉残留的末尾逗号（兼容旧数据如「央视频道,,#genre#」）
                genre = genre.replaceAll(",+$", "").trim();
                if (!genre.isEmpty()) {
                    genreGroup = genre;
                    pendingGroup = null;
                }
                continue;
            }

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

            // 分组：单条 #EXTINF 优先，否则用 #genre# 持续分组
            String group = pendingGroup != null ? pendingGroup : genreGroup;
            String key = (group == null ? "" : group) + "\u0000" + name;
            List<String> urls = byKey.get(key);
            if (urls == null) {
                urls = new ArrayList<String>();
                byKey.put(key, urls);
                nameByKey.put(key, name);
                if (group != null) groupByKey.put(key, group);
            }
            urls.add(url);
            number++;

            pendingName = null;
            pendingGroup = null;
        }

        // 输出为 Channel 列表
        List<Channel> list = new ArrayList<Channel>();
        int index = 1;
        for (Map.Entry<String, List<String>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            list.add(new Channel(index,
                    nameByKey.get(key),
                    entry.getValue(),
                    groupByKey.get(key)));
            index++;
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
