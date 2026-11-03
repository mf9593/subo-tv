package com.tvlive.app.server;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * 极简内置 Web 后台。
 *
 * <p>局域网内任意设备浏览器访问 {@code http://<本机IP>:<port>/} 即可：
 * ① 输入网络直播源地址 ② 上传本地 m3u/txt 文件 ③ 查看当前源。
 * 提交后通过回调把内容交给上层（存为 SourceStore 源并刷新）。</p>
 */
public class LocalWebServer {

    private static final String TAG = "LocalWebServer";
    public static final int PORT = 8090;

    public interface Callback {
        /** 收到新的直播源内容（name 为文件名或地址，text 为完整内容） */
        void onSourceSubmitted(String name, String url, String text);
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running = false;

    public LocalWebServer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public boolean start() {
        if (running) return true;
        try {
            serverSocket = new ServerSocket(PORT);
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            Socket socket = serverSocket.accept();
                            handle(socket);
                        } catch (Throwable t) {
                            if (running) Log.w(TAG, "accept: " + t);
                        }
                    }
                }
            }, "tv-web-server");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "Web 后台已启动: " + baseUrl());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "start: " + t);
            return false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignored) {
        }
    }

    public String baseUrl() {
        return "http://" + localIp() + ":" + PORT + "/";
    }

    /* ==================== 请求处理 ==================== */

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 手动按行读请求行/头（不缓冲，避免 BufferedReader 预读 body）
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";

            // 读请求头
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }

            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            android.util.Log.d("WebServer", "req " + method + " " + route + " len=" + contentLength);

            if ("POST".equalsIgnoreCase(method) && "/add".equals(route)) {
                // 表单：url 参数
                String url = queryValue(query, "url");
                if (url != null && !url.isEmpty()) {
                    fetchAndSubmit("网络源", url, url);
                }
                writeHtml(out, page("已提交：正在拉取 <b>" + escape(url) + "</b><br>请回到电视查看。"));
            } else if ("POST".equalsIgnoreCase(method) && "/check".equals(route)) {
                // 检验连通性（异步，不阻塞 accept 循环）
                final String url = queryValue(query, "url");
                final OutputStream chkOut = out;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final int code = checkUrl(url);
                        String body;
                        if (code >= 200 && code < 300) {
                            body = page("<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"vertical-align:middle;margin-right:4px\"><polyline points=\"20 6 9 17 4 12\"/></svg> 地址可用（HTTP " + code + "）<p>" + escape(url) + "</p>");
                        } else {
                            body = page("<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#FF5252\" stroke-width=\"2.5\" stroke-linecap=\"round\" style=\"vertical-align:middle;margin-right:4px\"><line x1=\"18\" y1=\"6\" x2=\"6\" y2=\"18\"/><line x1=\"6\" y1=\"6\" x2=\"18\" y2=\"18\"/></svg> 地址不可用（" + (code < 0 ? "无法连接" : "HTTP " + code) + "）<p>" + escape(url) + "</p>");
                        }
                        try { writeHtml(chkOut, body); } catch (Throwable ignored) {}
                    }
                }, "tv-web-check").start();
            } else if ("POST".equalsIgnoreCase(method) && "/fetch".equals(route)) {
                // 获取并解析（异步，不阻塞 accept 循环）
                final String url = queryValue(query, "url");
                final OutputStream fOut = out;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String text = fetch(url);
                        if (text == null || text.trim().isEmpty()) {
                            try { writeHtml(fOut, page("<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#FF5252\" stroke-width=\"2.5\" stroke-linecap=\"round\" style=\"vertical-align:middle;margin-right:4px\"><line x1=\"18\" y1=\"6\" x2=\"6\" y2=\"18\"/><line x1=\"6\" y1=\"6\" x2=\"18\" y2=\"18\"/></svg> 获取失败，请检查地址<p>" + escape(url) + "</p>")); } catch (Throwable ignored) {}
                        } else {
                            String preview = parsePreview(text);
                            String name = nameFromUrl(url);
                            try { writeHtml(fOut, fetchResult(name, url, text, preview)); } catch (Throwable ignored) {}
                        }
                    }
                }, "tv-web-fetch").start();
            } else if ("POST".equalsIgnoreCase(method) && "/upload".equals(route)) {
                android.util.Log.d("WebServer", "upload start len=" + contentLength);
                // 原始 body 作为文件内容（按字节读 Content-Length，UTF-8 解码）
                byte[] body = readBody(in, contentLength);
                String text = new String(body, StandardCharsets.UTF_8);
                android.util.Log.d("WebServer", "upload body len=" + body.length);
                String name = queryValue(query, "name");
                if (name == null || name.isEmpty()) name = "上传源";
                submit(name, null, text);
                writeHtml(out, page("已保存源：<b>" + escape(name) + "</b>（" + countLines(text) + " 行）<br>请回到电视查看。"));
            } else {
                writeHtml(out, home());
            }
            socket.close();
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t);
        }
    }

    private void fetchAndSubmit(final String name, final String url, final String address) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = fetch(url);
                if (text != null && !text.trim().isEmpty()) {
                    submit(name, address, text);
                } else {
                    Log.w(TAG, "fetch failed: " + url);
                }
            }
        }, "tv-web-fetch").start();
    }

    /** 检验地址连通性：返回 HTTP 状态码，-1 表示无法连接 */
    private int checkUrl(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TvLive/1.0");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            try {
                conn.getInputStream().close();
            } catch (Throwable ignored) {
            }
            return code;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从内容里提取频道列表预览（前若干行），返回 HTML */
    private String parsePreview(String text) {
        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (String l : text.split("\\r?\\n")) {
            String line = l.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            total++;
            if (sb.length() < 400) {
                if (sb.length() > 0) sb.append("<br>");
                sb.append(escape(line.length() > 60 ? line.substring(0, 60) + "…" : line));
            }
        }
        return "<div style=\"text-align:left;margin:8px 0;padding:14px;border-radius:12px;"
                + "background:#0b0f16;border:1px solid #2a3545;font-size:12px;color:#9fb0c6\">"
                + "<div style=\"color:#4A9EFF;font-size:13px;margin-bottom:8px\">解析到 <b>" + total + "</b> 个频道</div>"
                + sb.toString() + "</div>";
    }

    private String nameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "网络源";
        String name = url;
        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash < url.length() - 1) name = url.substring(slash + 1);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (name.length() > 40) name = name.substring(0, 40);
        return name.isEmpty() ? "网络源" : name;
    }

    /** 获取成功后的结果页：预览 + 添加按钮 */
    private String fetchResult(String name, String url, String text, String preview) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>电视直播</title><style>"
                + "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;min-height:100vh;"
                + "background:radial-gradient(1200px 600px at 20% -10%,#1a2740 0%,#0b0f16 55%) fixed,#0b0f16;"
                + "color:#e8edf3;padding:40px 24px;line-height:1.7;font-size:15px}"
                + ".wrap{max-width:560px;margin:0 auto}"
                + "h2{font-size:20px;font-weight:800;margin-bottom:8px;letter-spacing:.5px}"
                + "p{color:#9fb0c6;font-size:14px;margin-bottom:8px;word-break:break-all}"
                + "button{width:100%;padding:14px;border:none;border-radius:12px;overflow:hidden;margin-top:14px;"
                + "background:linear-gradient(135deg,#4A9EFF,#3D8AE8);color:#05110f;font-size:16px;font-weight:800;cursor:pointer;"
                + "transition:filter .2s,box-shadow .2s;box-shadow:0 6px 20px rgba(74,158,255,.2);-webkit-tap-highlight-color:transparent}"
                + "button:hover{filter:brightness(1.1);box-shadow:0 8px 24px rgba(74,158,255,.3)}"
                + "a{display:inline-block;margin-top:14px;color:#4A9EFF;text-decoration:none;font-size:14px}"
                + "a:hover{filter:brightness(1.2)}"
                + "</style></head><body><div class=\"wrap\">"
                + "<h2><svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"vertical-align:middle;margin-right:6px\"><polyline points=\"20 6 9 17 4 12\"/></svg> 获取成功</h2><p>" + escape(name) + "</p>"
                + preview
                + "<button onclick=\"add()\">添加为当前源</button>"
                + "<br><a href=\"/\"><svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"vertical-align:middle;margin-right:4px\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/><polyline points=\"12 19 5 12 12 5\"/></svg> 返回</a>"
                + "<script>function add(){fetch('/add?url='+encodeURIComponent('"
                + escape(url).replace("'", "\\'") + "'),{method:'POST'}).then(r=>r.text()).then(t=>document.body.innerHTML=t);}</script>"
                + "</div></body></html>";
    }

    private void submit(final String name, final String url, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onSourceSubmitted(name, url, text);
            }
        });
    }

    private String fetch(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TvLive/1.0");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "fetch: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 InputStream 按 Content-Length 读请求体（字节） */
    private static byte[] readBody(InputStream in, int length) throws Exception {
        if (length <= 0) return new byte[0];
        // 防止恶意超大 body 导致 OOM
        if (length > 10 * 1024 * 1024) length = 10 * 1024 * 1024;
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) break;
            read += n;
        }
        // 不足 Content-Length 时截断
        if (read < length) {
            byte[] truncated = new byte[read];
            System.arraycopy(data, 0, truncated, 0, read);
            return truncated;
        }
        return data;
    }

    /** 从 InputStream 按行读（不缓冲，避免预读 body）；返回 null 表示流结束 */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String queryValue(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(key)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return null;
    }

    private static int countLines(String text) {
        if (text == null) return 0;
        int n = 0;
        for (String l : text.split("\\r?\\n")) {
            if (!l.trim().isEmpty()) n++;
        }
        return n;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /* ==================== 页面 ==================== */

    /** 读取 App 图标（ic_launcher）并转 base64 PNG，用于网页 logo */
    private String iconBase64() {
        try {
            InputStream is = context.getResources()
                    .openRawResource(com.tvlive.app.R.drawable.ic_launcher);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 内联 SVG 矢量图标 */
    private String icon(String name, int size) {
        switch(name) {
            case "globe":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg>";
            case "link":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#5a6b80\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71\"/><path d=\"M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71\"/></svg>";
            case "folder":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z\"/></svg>";
            case "info":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"12\" y1=\"16\" x2=\"12\" y2=\"12\"/><line x1=\"12\" y1=\"8\" x2=\"12.01\" y2=\"8\"/></svg>";
            case "bulb":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#5a6b80\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 18h6\"/><path d=\"M10 22h4\"/><path d=\"M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0 0 18 8 6 6 0 0 0 6 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 0 1 8.91 14\"/></svg>";
            case "signal":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M5 12.55a11 11 0 0 1 14.01 0\"/><path d=\"M1.42 9a16 16 0 0 1 21.16 0\"/><path d=\"M8.53 16.11a6 6 0 0 1 6.95 0\"/><line x1=\"12\" y1=\"20\" x2=\"12.01\" y2=\"20\"/></svg>";
            case "check":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><polyline points=\"20 6 9 17 4 12\"/></svg>";
            case "arrow-left":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/><polyline points=\"12 19 5 12 12 5\"/></svg>";
            case "tv":
                return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#4A9EFF\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"2\" y=\"4\" width=\"20\" height=\"14\" rx=\"2\"/><path d=\"M8 21h8M12 18v3\"/></svg>";
            default:
                return "";
        }
    }

    private String home() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">");
        html.append("<title>电视直播 · 源后台</title><style>");
        // 基础
        html.append("*{box-sizing:border-box;margin:0;padding:0}");
        html.append("body{font-family:-apple-system,'PingFang SC','Microsoft YaHei','Segoe UI',Roboto,sans-serif;");
        html.append("min-height:100vh;color:#e8edf3;");
        html.append("background:radial-gradient(1200px 600px at 20% -10%,#1d2b3f 0%,#0b0f16 55%) fixed,#0b0f16;");
        html.append("padding:28px 20px calc(40px + env(safe-area-inset-bottom));line-height:1.65}");
        // 头部
        html.append(".hero{display:flex;align-items:center;gap:14px;margin-bottom:26px}");
        html.append(".hero .logo-img{width:52px;height:52px}");
        html.append(".hero h1{font-size:22px;font-weight:800;letter-spacing:.5px}");
        html.append(".hero p{font-size:13px;color:#7d8ea3;margin-top:2px}");
        html.append(".hero .live{display:inline-flex;align-items:center;gap:6px;font-size:11px;color:#4A9EFF;");
        html.append("background:rgba(74,158,255,.12);border:1px solid rgba(74,158,255,.3);padding:3px 10px;border-radius:20px;margin-left:auto}");
        html.append(".hero .live::before{content:'';width:6px;height:6px;border-radius:50%;background:#4A9EFF;");
        html.append("box-shadow:0 0 8px #4A9EFF;animation:pulse 1.6s infinite}");
        html.append("@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}");
        // 连接信息栏
        html.append(".conn{display:flex;align-items:center;justify-content:space-between;gap:12px;");
        html.append("background:rgba(74,158,255,.08);border:1px solid rgba(74,158,255,.2);border-radius:14px;");
        html.append("padding:10px 16px;margin-bottom:22px;font-size:12px}");
        html.append(".conn .info{display:flex;align-items:center;gap:8px;color:#7d8ea3}");
        html.append(".conn .info b{color:#4A9EFF;font-weight:600}");
        html.append(".conn .ip{font-family:Consolas,monospace;color:#4A9EFF;font-size:12px}");
        // 卡片
        html.append(".card{background:linear-gradient(135deg,rgba(20,26,36,.92),rgba(15,20,28,.92));");
        html.append("border:1px solid rgba(35,44,58,.9);border-radius:20px;");
        html.append("padding:22px;margin-bottom:16px;backdrop-filter:blur(8px);");
        html.append("box-shadow:0 4px 20px rgba(0,0,0,.2);transition:border-color .2s,box-shadow .2s}");
        html.append(".card:hover{border-color:rgba(74,158,255,.2);box-shadow:0 4px 24px rgba(74,158,255,.08)}");
        html.append(".card h2{font-size:14px;font-weight:700;color:#9fb0c6;letter-spacing:1px;margin-bottom:14px;display:flex;align-items:center;gap:8px}");
        html.append(".card h2 .ic{width:18px;height:18px;flex-shrink:0}");
        html.append(".divider{height:1px;background:rgba(35,44,58,.7);margin:6px 0 16px}");
        // 输入
        html.append(".input-wrap{position:relative;margin-bottom:12px}");
        html.append(".input-wrap input{width:100%;padding:14px 16px 14px 42px;border-radius:12px;");
        html.append("border:1px solid #2a3545;background:#0b0f16;color:#fff;font-size:15px;outline:none;transition:border-color .2s,box-shadow .2s}");
        html.append(".input-wrap input:focus{border-color:#4A9EFF;box-shadow:0 0 0 3px rgba(74,158,255,.12)}");
        html.append(".input-wrap .icon{position:absolute;left:14px;top:50%;transform:translateY(-50%);width:16px;height:16px}");
        html.append("input[type=file]{width:100%;padding:11px;border-radius:12px;border:1px solid #2a3545;");
        html.append("background:#0b0f16;color:#9fb0c6;font-size:13px;margin-bottom:12px;outline:none}");
        html.append("input[type=file]::file-selector-button{background:#1b2431;color:#cfd9e6;border:none;border-radius:8px;overflow:hidden;padding:8px 14px;margin-right:10px;cursor:pointer;font-size:13px}");
        // 按钮
        html.append("button{width:100%;padding:14px;border:none;border-radius:12px;overflow:hidden;background:linear-gradient(135deg,#4A9EFF,#3D8AE8);");
        html.append("color:#05110f;font-size:16px;font-weight:800;letter-spacing:.5px;cursor:pointer;transition:transform .1s,box-shadow .2s,filter .2s;-webkit-tap-highlight-color:transparent}");
        html.append("button:active{transform:scale(.97)}");
        html.append("button:hover{filter:brightness(1.1);box-shadow:0 6px 20px rgba(74,158,255,.3)}");
        html.append("button:disabled{opacity:.5;cursor:not-allowed}");
        html.append("button.sec{background:linear-gradient(135deg,#1b2431,#232c3a);color:#e8edf3;font-weight:600}");
        html.append("button.sec:hover{box-shadow:0 4px 16px rgba(0,0,0,.3);filter:none}");
        // 底部
        html.append(".tip{font-size:12px;color:#5a6b80;margin-top:10px;display:flex;align-items:center;gap:6px}");
        html.append(".tip b{color:#8fb0c6;font-weight:600}");
        html.append(".tip-ic{width:14px;height:14px;flex-shrink:0}");
        html.append(".footer{text-align:center;font-size:11px;color:#42506a;margin-top:20px;padding-top:20px;border-top:1px solid rgba(35,44,58,.5)}");
        html.append(".footer span{color:#4A9EFF}");
        html.append("</style></head><body>");

        // 头部（logo 内联 SVG，与 App 图标一致）
        html.append("<div class=\"hero\">");
        html.append("<svg class=\"logo-img\" width=\"52\" height=\"52\" viewBox=\"0 0 200 200\" xmlns=\"http://www.w3.org/2000/svg\">");
        html.append("<defs><linearGradient id=\"bgGrad\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"100%\">");
        html.append("<stop offset=\"0%\" stop-color=\"#42A5F5\"/><stop offset=\"100%\" stop-color=\"#1565C0\"/>");
        html.append("</linearGradient></defs>");
        html.append("<rect width=\"200\" height=\"200\" rx=\"28\" ry=\"28\" fill=\"url(#bgGrad)\"/>");
        html.append("<line x1=\"100\" y1=\"55\" x2=\"70\" y2=\"25\" stroke=\"white\" stroke-width=\"5\" stroke-linecap=\"round\"/>");
        html.append("<line x1=\"100\" y1=\"55\" x2=\"130\" y2=\"25\" stroke=\"white\" stroke-width=\"5\" stroke-linecap=\"round\"/>");
        html.append("<path d=\"M56,55 L144,55 A16,16 0 0 1 160,71 L160,119 A16,16 0 0 1 144,135 L56,135 A16,16 0 0 1 40,119 L40,71 A16,16 0 0 1 56,55 Z M92,80 L92,110 L116,95 Z\" fill=\"white\" fill-rule=\"evenodd\"/>");
        html.append("<rect x=\"86\" y=\"135\" width=\"28\" height=\"8\" rx=\"3\" ry=\"3\" fill=\"white\"/>");
        html.append("<circle cx=\"152\" cy=\"50\" r=\"8\" fill=\"#FF5252\"/>");
        html.append("</svg>");
        html.append("<div><h1>电视直播 · 源后台</h1><p>在下方添加或上传直播源，自动同步到电视</p></div>");
        html.append("<span class=\"live\">已连接</span>");
        html.append("</div>");

        // 连接信息栏
        String ip = localIp();
        html.append("<div class=\"conn\">");
        html.append("<div class=\"info\">" + icon("signal", 14) + " 电视已连接 · <b>自动同步</b></div>");
        html.append("<div class=\"ip\">IP: " + ip + "</div>");
        html.append("</div>");

        // 网络地址
        html.append("<div class=\"card\"><h2><span class=\"ic\">" + icon("globe", 18) + "</span>输入网络直播源地址</h2><div class=\"divider\"></div>");
        html.append("<div class=\"input-wrap\"><span class=\"icon\">" + icon("link", 16) + "</span>");
        html.append("<input type=\"text\" id=\"url\" placeholder=\"https://example.com/live.m3u\" autocomplete=\"off\" spellcheck=\"false\">");
        html.append("</div>");
        html.append("<button onclick=\"add()\">拉取并添加</button>");
        html.append("<div style=\"display:flex;gap:10px;margin-top:10px\">");
        html.append("<button class=\"sec\" style=\"flex:1\" onclick=\"check()\">检验</button>");
        html.append("<button class=\"sec\" style=\"flex:1\" onclick=\"fetchit()\">获取频道列表</button>");
        html.append("</div>");
        html.append("<div class=\"tip\"><span class=\"tip-ic\">" + icon("bulb", 14) + "</span>支持 m3u / m3u8 / txt 格式的网络地址</div>");
        html.append("</div>");

        // 上传文件
        html.append("<div class=\"card\"><h2><span class=\"ic\">" + icon("folder", 18) + "</span>上传本地文件</h2><div class=\"divider\"></div>");
        html.append("<input type=\"file\" id=\"file\" accept=\".m3u,.m3u8,.txt\">");
        html.append("<button onclick=\"upload()\">上传并添加</button>");
        html.append("</div>");

        // 说明
        html.append("<div class=\"card\"><h2><span class=\"ic\">" + icon("info", 18) + "</span>使用说明</h2><div class=\"divider\"></div>");
        html.append("<div class=\"tip\">① 添加成功后回到电视，菜单 → 重新载入频道</div>");
        html.append("<div class=\"tip\" style=\"margin-top:6px\">② 也可在电视上：菜单 → 频道导入，手动输入地址</div>");
        html.append("</div>");

        html.append("<div class=\"footer\">速播 · 源后台 · 仅供测试 · <span>电视直播</span></div>");

        html.append("<script>");
        html.append("function add(){var u=document.getElementById('url').value.trim();if(!u)return alert('请先输入直播源地址');");
        html.append("var b=document.getElementById('url');b.disabled=true;");
        html.append("fetch('/add?url='+encodeURIComponent(u),{method:'POST'}).then(r=>r.text()).then(t=>document.body.innerHTML=t).catch(function(){alert('提交失败，请重试');b.disabled=false;});}");
        html.append("function upload(){var f=document.getElementById('file').files[0];if(!f)return alert('请先选择文件');");
        html.append("var r=new FileReader();r.onload=function(){");
        html.append("fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:r.result}).then(x=>x.text()).then(t=>document.body.innerHTML=t).catch(function(){alert('上传失败，请重试');});};");
        html.append("r.readAsText(f);}");
        html.append("</script></body></html>");
        return html.toString();
    }

    private String page(String content) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>电视直播</title><style>"
                + "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;min-height:100vh;"
                + "background:radial-gradient(1200px 600px at 20% -10%,#1a2740 0%,#0b0f16 55%) fixed,#0b0f16;"
                + "color:#e8edf3;padding:40px 24px;line-height:1.7;font-size:15px}"
                + ".wrap{max-width:560px;margin:0 auto;text-align:center}"
                + ".ok{width:80px;height:80px;border-radius:50%;background:linear-gradient(135deg,#4A9EFF,#3D8AE8);"
                + "display:flex;align-items:center;justify-content:center;margin:0 auto 28px;"
                + "box-shadow:0 8px 30px rgba(74,158,255,.3);animation:pop .4s ease}"
                + "@keyframes pop{0%{transform:scale(.5);opacity:0}100%{transform:scale(1);opacity:1}}"
                + "h2{font-size:20px;font-weight:800;margin-bottom:12px;letter-spacing:.5px}"
                + "p{color:#9fb0c6;font-size:14px;margin-bottom:8px;word-break:break-all}"
                + ".btn{display:block;width:fit-content;margin:28px auto 0;padding:14px 36px;border-radius:12px;overflow:hidden;"
                + "background:linear-gradient(135deg,#4A9EFF,#3D8AE8);color:#05110f;text-decoration:none;font-weight:700;"
                + "font-size:15px;transition:filter .2s,box-shadow .2s;box-shadow:0 6px 20px rgba(74,158,255,.25)}"
                + "a.btn:hover{filter:brightness(1.1);box-shadow:0 8px 24px rgba(74,158,255,.35)}"
                + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"ok\"><svg width=\"40\" height=\"40\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><polyline points=\"20 6 9 17 4 12\"/></svg></div>" + content
                + "<a class=\"btn\" href=\"/\"><svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"vertical-align:middle;margin-right:4px\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/><polyline points=\"12 19 5 12 12 5\"/></svg> 返回首页</a>"
                + "</div></body></html>";
    }

    private void writeHtml(OutputStream out, String html) throws Exception {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    /* ==================== 本机 IP ==================== */

    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                            + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
            // 有线回退：遍历网卡
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }
}
