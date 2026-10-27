package com.tvlive.app.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import java.util.Locale;

/**
 * 直播播放器封装（ExoPlayer 2.19.1）。
 *
 * <p>支持 http(s) 的 HLS / 渐进式文件，以及 rtsp 摄像头流。
 * 自带失败重试与「按 HLS 再试一次」的兜底，适配 4.4 老盒子。</p>
 */
public class LivePlayer {

    private static final String TAG = "TvLivePlayer";

    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_ERROR = 3;

    public interface Listener {
        void onStateChanged(int state, String channelName);

        void onError(String message);
    }

    /** 失败重试次数 */
    private static final int MAX_RETRY = 2;
    /** 直播流结束时自动重连间隔（毫秒） */
    private static final long RECONNECT_DELAY_MS = 2500;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
                Log.i(TAG, "播放就绪: " + currentName);
                retryCount = 0;
                listener.onStateChanged(STATE_PLAYING, currentName);
            } else if (playbackState == Player.STATE_BUFFERING) {
                listener.onStateChanged(STATE_CONNECTING, currentName);
            } else if (playbackState == Player.STATE_ENDED) {
                // 直播流断开：稍后自动重连同一路
                handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            String message = describe(error);
            Log.w(TAG, "播放出错: " + message
                    + " | code=" + error.getErrorCodeName()
                    + " | url=" + currentUrl);
            if (!triedHlsFallback && isHttp(currentUrl)) {
                // 扩展名不规范的地址，按 HLS 再试一次
                triedHlsFallback = true;
                retryAsHls(message);
                return;
            }
            if (retryCount < MAX_RETRY) {
                retryCount++;
                listener.onStateChanged(STATE_CONNECTING,
                        currentName + "（第 " + retryCount + " 次重试）");
                handler.postDelayed(reconnectRunnable, 1500);
                return;
            }
            listener.onStateChanged(STATE_ERROR, currentName);
            listener.onError(message);
        }
    };
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentUrl != null && !released) start();
        }
    };

    private ExoPlayer player;
    private SurfaceView surfaceView;
    private String currentUrl;
    private String currentName;
    private boolean lowLatency = false;
    private boolean triedHlsFallback = false;
    private int retryCount = 0;
    private boolean released = false;

    public LivePlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /* ==================== 对外接口 ==================== */

    public void attach(SurfaceView view) {
        this.surfaceView = view;
        if (player != null && view != null) {
            try {
                player.setVideoSurfaceView(view);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isLowLatency() {
        return lowLatency;
    }

    /** 切换缓冲策略（低延迟优先 / 流畅优先），会重新构建播放器 */
    public void setLowLatency(boolean lowLatency) {
        if (this.lowLatency == lowLatency) return;
        this.lowLatency = lowLatency;
        releasePlayer();
        if (currentUrl != null) start();
    }

    public void play(String url, String channelName) {
        if (url == null || url.trim().isEmpty()) return;
        Log.i(TAG, "开始播放: " + channelName + " -> " + url);
        currentUrl = url.trim();
        currentName = channelName == null ? "" : channelName;
        retryCount = 0;
        triedHlsFallback = false;
        released = false;
        start();
    }

    public void pause() {
        if (player == null) return;
        try {
            player.pause();
        } catch (Throwable ignored) {
        }
    }

    public void resume() {
        if (player == null) return;
        try {
            player.play();
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    /* ==================== 内部实现 ==================== */

    private void start() {
        if (released || currentUrl == null) return;
        Log.d(TAG, "start(): retry=" + retryCount + " hlsFallback=" + triedHlsFallback);
        try {
            ensurePlayer();
            listener.onStateChanged(STATE_CONNECTING, currentName);
            player.setMediaSource(buildSource(currentUrl));
            player.prepare();
            player.play();
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            listener.onStateChanged(STATE_ERROR, currentName);
            listener.onError("播放器初始化失败：" + message);
        }
    }

    private void retryAsHls(String lastError) {
        try {
            ensurePlayer();
            listener.onStateChanged(STATE_CONNECTING, currentName + "（改用 HLS）");
            player.setMediaSource(new HlsMediaSource.Factory(new DefaultDataSource.Factory(context))
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(currentUrl))));
            player.prepare();
            player.play();
        } catch (Throwable t) {
            listener.onStateChanged(STATE_ERROR, currentName);
            listener.onError(lastError);
        }
    }

    private void ensurePlayer() {
        if (player != null) return;

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context);
        // 硬解失败时尝试其他解码器，老盒子上很关键
        renderers.setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        lowLatency ? 6000 : 20000,
                        lowLatency ? 15000 : 60000,
                        lowLatency ? 400 : 1000,
                        lowLatency ? 800 : 2000)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setLoadControl(loadControl)
                .build();
        player.addListener(playerListener);
        if (surfaceView != null) {
            try {
                player.setVideoSurfaceView(surfaceView);
            } catch (Throwable ignored) {
            }
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.removeListener(playerListener);
        } catch (Throwable ignored) {
        }
        try {
            player.release();
        } catch (Throwable ignored) {
        }
        player = null;
    }

    /** 按协议选 MediaSource：rtsp 走 RTSP，m3u8 走 HLS，其余走渐进式 */
    private MediaSource buildSource(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        MediaItem item = MediaItem.fromUri(uri);
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(context);

        if ("rtsp".equals(scheme) || "rtsps".equals(scheme)) {
            return new RtspMediaSource.Factory()
                    // 摄像头基本都要 TCP 内联，UDP 容易被路由器/防火墙丢弃
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(12000)
                    .setUserAgent("TvLive/1.0")
                    .createMediaSource(item);
        }
        if (isHls(url)) {
            return new HlsMediaSource.Factory(dataSource)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSource).createMediaSource(item);
    }

    private boolean isHls(String url) {
        if (url == null) return false;
        return url.toLowerCase(Locale.ROOT).contains("m3u8");
    }

    private boolean isHttp(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 把 ExoPlayer 的错误码翻译成用户能看懂的中文 */
    private String describe(PlaybackException error) {
        if (error == null) return "未知错误";
        int code = error.errorCode;
        String reason;
        switch (code) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                reason = "连不上服务器（网络或地址不通）";
                break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                reason = "连接超时（服务器无响应）";
                break;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                reason = "服务器拒绝访问（HTTP 状态异常，可能是地址过期或需要鉴权）";
                break;
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                reason = "地址不存在（404）";
                break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                reason = "流格式不支持（可能是加密流或非标准封装）";
                break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                reason = "解码器初始化失败（该盒子可能不支持此编码，如 H.265）";
                break;
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                reason = "解码失败（编码格式不被支持，如 H.265/AV1）";
                break;
            default:
                reason = "播放失败（" + error.getErrorCodeName() + "）";
                break;
        }
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            reason = reason + "：" + cause.getMessage();
        }
        return reason;
    }
}
