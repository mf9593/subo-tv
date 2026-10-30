package com.tvlive.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 多直播源文件管理。
 *
 * <p>可保存多个直播源（网络地址或本地文本），每个源有名称、地址、内容；
 * 同一时刻只能激活一个源。</p>
 */
public final class SourceStore {

    private static final String TAG = "SourceStore";
    private static final String PREFS = "tvlive_sources";
    private static final String KEY_LIST = "sources";
    private static final String KEY_ACTIVE = "active_id";

    private SourceStore() {
    }

    /** 一个直播源文件 */
    public static class Source {
        public final String id;
        public final String name;
        public final String url;      // 网络地址；本地导入则为空
        public final String text;     // 完整内容
        public final long time;

        public Source(String id, String name, String url, String text, long time) {
            this.id = id;
            this.name = name;
            this.url = url == null ? "" : url;
            this.text = text == null ? "" : text;
            this.time = time;
        }
    }

    /* ==================== 读写 ==================== */

    public static List<Source> list(Context context) {
        List<Source> out = new ArrayList<Source>();
        try {
            String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LIST, "[]");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                out.add(new Source(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("url"),
                        o.optString("text"),
                        o.optLong("time", 0)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "list: " + t);
        }
        return out;
    }

    public static Source active(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE, null);
        if (id == null) return null;
        for (Source s : list(context)) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public static void setActive(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ACTIVE, id).apply();
    }

    /** 新增一个源并自动激活；返回新源（失败返回 null） */
    public static Source add(Context context, String name, String url, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        List<Source> all = list(context);
        String id = String.valueOf(System.currentTimeMillis());
        Source s = new Source(id, name, url, text, System.currentTimeMillis());
        all.add(0, s);
        save(context, all);
        setActive(context, id);
        return s;
    }

    public static boolean remove(Context context, String id) {
        List<Source> all = list(context);
        List<Source> kept = new ArrayList<Source>();
        boolean removed = false;
        for (Source s : all) {
            if (s.id.equals(id)) {
                removed = true;
            } else {
                kept.add(s);
            }
        }
        if (!removed) return false;
        save(context, kept);
        // 若删除的是激活源，激活第一个
        String active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE, null);
        if (id.equals(active)) {
            String next = kept.isEmpty() ? null : kept.get(0).id;
            setActive(context, next);
        }
        return true;
    }

    private static void save(Context context, List<Source> all) {
        JSONArray array = new JSONArray();
        for (Source s : all) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("text", s.text);
                o.put("time", s.time);
                array.put(o);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, array.toString()).apply();
    }

    /* ==================== 网络 ==================== */

    /** 拉取远程地址内容；成功返回文本，失败返回 null */
    public static String fetch(Context context, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TvLive/1.0");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream in = conn.getInputStream();
                return readAll(in);
            }
            Log.w(TAG, "fetch HTTP " + code + " for " + url);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "fetch: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 检验地址可达性：返回 HTTP 状态码（>=200 且 <300 为可用），异常返回 -1 */
    public static int check(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TvLive/1.0");
            // 只读头部，避免下载整个流
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.getInputStream().close();
            return code;
        } catch (Throwable t) {
            Log.w(TAG, "check: " + t);
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
