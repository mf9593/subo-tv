package com.tvlive.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表的读取与缓存。
 *
 * <p>优先级：外部 M3U 文件 &gt; 内置 assets/channels.m3u &gt; 上次缓存的列表。</p>
 * <p>外部文件可以放在（任选其一）：</p>
 * <ul>
 *     <li>/sdcard/tvlive.m3u</li>
 *     <li>/sdcard/Download/tvlive.m3u</li>
 *     <li>U 盘挂载目录根下（路径因盒子而异，可通过菜单里的「当前频道来源」查看）</li>
 * </ul>
 */
public final class ChannelStore {

    private static final String PREFS = "tvlive_channels";
    private static final String KEY_JSON = "channels";
    private static final String ASSET_NAME = "channels.m3u";

    private ChannelStore() {
    }

    /** 启动时取频道：优先缓存的列表，缓存为空再重新载入 */
    public static List<Channel> load(Context context) {
        // 空壳模式：没有导入源时不返回缓存，直接返回空列表
        if (SourceStore.list(context).isEmpty()) {
            return new ArrayList<Channel>();
        }
        List<Channel> cached = fromPrefs(context);
        if (!cached.isEmpty()) return cached;
        return reload(context);
    }

    /** 强制重新载入：激活的源 &gt; 外部 M3U &gt; 内置 assets */
    public static List<Channel> reload(Context context) {
        List<Channel> list = new ArrayList<Channel>();
        String sourcePath = null;

        // 1) 用户导入的多源文件（激活的那个）
        SourceStore.Source active = SourceStore.active(context);
        if (active != null && !active.text.trim().isEmpty()) {
            List<Channel> parsed = M3uParser.parse(active.text);
            if (!parsed.isEmpty()) {
                list = parsed;
                sourcePath = "导入源：" + active.name;
            }
        }

        // 2) 外部 M3U 文件（仅当用户放入设备时使用）
        if (list.isEmpty()) {
            for (String path : externalPaths(context)) {
                File file = new File(path);
                if (!file.exists() || !file.isFile() || !file.canRead()) continue;
                List<Channel> parsed = M3uParser.parse(readFile(file));
                if (!parsed.isEmpty()) {
                    list = parsed;
                    sourcePath = path;
                    break;
                }
            }
        }

        // 3) 内置 assets（空壳模式：不内置数据，删除此回退）
        // if (list.isEmpty()) {
        //     List<Channel> parsed = M3uParser.parse(readAsset(context));
        //     if (!parsed.isEmpty()) {
        //         list = parsed;
        //         sourcePath = "内置 " + ASSET_NAME;
        //     }
        // }

        save(context, list);
        if (sourcePath != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("last_source", sourcePath).apply();
        }
        return list;
    }

    public static String lastSource(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("last_source", null);
        return value == null ? "未导入" : value;
    }

    /** 可能存放外部 M3U 的位置 */
    public static List<String> externalPaths(Context context) {
        List<String> paths = new ArrayList<String>();
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) {
                paths.add(new File(ext, "tvlive.m3u").getAbsolutePath());
                paths.add(new File(new File(ext, "Download"), "tvlive.m3u").getAbsolutePath());
                paths.add(new File(new File(ext, "U盘"), "tvlive.m3u").getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
        try {
            File appDir = context.getExternalFilesDir(null);
            if (appDir != null) {
                paths.add(new File(appDir, "channels.m3u").getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
        return paths;
    }

    /* ==================== 读写 ==================== */

    private static void save(Context context, List<Channel> list) {
        JSONArray array = new JSONArray();
        for (Channel channel : list) {
            JSONObject item = new JSONObject();
            try {
                item.put("n", channel.number);
                item.put("name", channel.name);
                JSONArray urls = new JSONArray();
                for (String url : channel.urls) urls.put(url);
                item.put("urls", urls);
                if (channel.group != null) item.put("g", channel.group);
            } catch (Exception ignored) {
                continue;
            }
            array.put(item);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, array.toString()).apply();
    }

    private static List<Channel> fromPrefs(Context context) {
        List<Channel> list = new ArrayList<Channel>();
        String json;
        try {
            json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_JSON, null);
        } catch (Throwable t) {
            return list;
        }
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                List<String> urls = new ArrayList<String>();
                JSONArray urlArray = item.optJSONArray("urls");
                if (urlArray != null) {
                    for (int j = 0; j < urlArray.length(); j++) {
                        String url = urlArray.optString(j, "");
                        if (!url.isEmpty()) urls.add(url);
                    }
                }
                if (urls.isEmpty()) {
                    // 兼容旧版缓存：只有单个 url 字段
                    String old = item.optString("url", "");
                    if (!old.isEmpty()) urls.add(old);
                }
                if (urls.isEmpty()) continue;
                list.add(new Channel(item.optInt("n", i + 1),
                        item.optString("name", "频道 " + (i + 1)),
                        urls,
                        item.optString("g", null)));
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private static String readFile(File file) {
        StringBuilder builder = new StringBuilder();
        try (InputStream input = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Throwable ignored) {
            return "";
        }
        return builder.toString();
    }

    private static String readAsset(Context context) {
        StringBuilder builder = new StringBuilder();
        try (InputStream input = context.getAssets().open(ASSET_NAME);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Throwable ignored) {
            return "";
        }
        return builder.toString();
    }
}
