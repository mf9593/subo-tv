package com.tvlive.app.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.PlaybackState;
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
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 直播播放器封装（ExoPlayer 2.19.1）。
 *
 * <p>支持 http(s) 的 HLS / 渐进式文件，以及 rtsp 摄像头流。
 * 自带失败重试与「按 HLS 再试一次」的兜底，适配 4.4 老盒子。</p>
 *
 * <p><b>多源：</b>一个频道可含多个地址。开启「自动换源」后，当前源重试耗尽即按顺序
 * 尝试下一个源；也可通过 {@link #nextSource()} 手动切换。</p>
 */
public class LivePlayer {

    private static final String TAG = "TvLivePlayer";

    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_ERROR = 3;

    public interface Listener {
        void onStateChanged(int state, String channelName);

        /** 当前源切换时回调（index 从 0 开始，total 为该频道源总数） */
        void onSourceChanged(int index, int total);

        /** 自动换源已尝试完该频道所有源 */
        void onSourcesExhausted();

        void onError(String message);

        /** 视频尺寸变化（用于显示分辨率） */
        void onVideoSizeChanged(int width, int height);
    }

    /** 每个源内部的失败重试次数（耗尽后进入换源逻辑） */
    private static final int MAX_RETRY = 2;
    /** 直播流结束时自动重连间隔（毫秒） */
    private static final long RECONNECT_DELAY_MS = 2500;
    /** 默认播放就绪超时（毫秒） */
    private static final long DEFAULT_READY_TIMEOUT_MS = 5000L;
    /** RTSP 流就绪超时（毫秒） */
    private static final long RTSP_READY_TIMEOUT_MS = 20000L;
    /** 当前生效的就绪超时（毫秒） */
    private long currentReadyTimeoutMs = DEFAULT_READY_TIMEOUT_MS;
    /** 无视频帧超时（毫秒）：STATE_READY 但无 onVideoSizeChanged 回调时触发 */
    private static final long NO_VIDEO_TIMEOUT_MS = 3000L;

    /** 解码方式 */
    public static final int DECODE_HW = 0;   // 硬解
    public static final int DECODE_SW = 1;   // 软解

    /** 画面缩放模式 */
    public static final int SCALE_FIT = 0;   // 按比例（等比缩放，留黑边）
    public static final int SCALE_FILL = 1;  // 全屏填充（拉伸铺满）

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updatePlaybackState();
            if (playbackState == Player.STATE_READY) {
                Log.i(TAG, "播放就绪: " + currentName + " [源 " + (sourceIndex + 1) + "/" + sources.size() + "]");
                cancelReadyTimeout();
                // 换源记忆：保存当前源下标
                if (sourceMemoryEnabled && currentName != null) {
                    sourceMemory.put(currentName, sourceIndex);
                }
                listener.onStateChanged(STATE_PLAYING, currentName);
                // 开始无视频帧检测（黑屏时自动换源）
                hasVideoFrame = false;
                scheduleNoVideoTimeout();
            } else if (playbackState == Player.STATE_BUFFERING) {
                listener.onStateChanged(STATE_CONNECTING, currentName);
                // 进入缓冲即开始计时，防止一直转圈无报错
                scheduleReadyTimeout();
                cancelNoVideoTimeout();
            } else if (playbackState == Player.STATE_ENDED) {
                // 直播流断开：累计断开次数，超过阈值后换源
                endedCount++;
                Log.w(TAG, "直播流断开: " + currentName + " [源 " + (sourceIndex + 1) + "/"
                        + sources.size() + "] 断开次数=" + endedCount);
                if (endedCount >= 2) {
                    // 连续断开 2 次：当前源已死，触发换源
                    Log.i(TAG, "当前源连续断开，触发自动换源");
                    handleFailure("直播流连续断开（当前源不可用）");
                } else {
                    handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
                }
            } else if (playbackState == Player.STATE_IDLE) {
                Log.w(TAG, "播放空闲: " + currentName + " [源 " + (sourceIndex + 1) + "/" + sources.size() + "] state=" + playbackState);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            cancelReadyTimeout();
            updatePlaybackState();
            String message = describe(error);
            Log.w(TAG, "播放出错: " + message
                    + " | code=" + error.getErrorCodeName()
                    + " | url=" + currentUrl());
            handleFailure(message);
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                hasVideoFrame = true;
                cancelNoVideoTimeout();
                listener.onVideoSizeChanged(videoSize.width, videoSize.height);
            }
        }
    };
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!released && currentUrl() != null) start();
        }
    };
    private final Runnable readyTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) return;
            if (player.getPlaybackState() != Player.STATE_READY) {
                Log.w(TAG, "播放超时 " + currentReadyTimeoutMs + "ms 未就绪: " + currentName + " url=" + currentUrl());
                // 超时直接换源：跳过 HLS 兜底和重试，节省时间
                retryCount = MAX_RETRY;
                triedHlsFallback = true;
                handleFailure("播放超时（" + (currentReadyTimeoutMs / 1000) + " 秒未就绪）");
            }
        }
    };

    private final Runnable noVideoTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) return;
            if (player.getPlaybackState() == Player.STATE_READY && !hasVideoFrame) {
                Log.w(TAG, "无视频帧超时: " + currentName + " [源 " + (sourceIndex + 1) + "/"
                        + sources.size() + "] 已就绪但无画面");
                retryCount = MAX_RETRY;
                triedHlsFallback = true;
                handleFailure("播放就绪但无画面（可能源已失效）");
            }
        }
    };

    /** 统一失败处理：HLS 兜底 → 重试 → 换源 → 报错 */
    private void handleFailure(String message) {
        // 解析/解码错误：重试无效，直接换源
        if (message.contains("流格式不支持") || message.contains("解码器初始化失败") || message.contains("解码失败")) {
            retryCount = MAX_RETRY;
            triedHlsFallback = true;
        }
        if (!triedHlsFallback && isHttp(currentUrl()) && !isUdpxyStream(currentUrl())) {
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
        // 当前源重试耗尽：尝试下一个源
        if (tryNextSource()) {
            return;
        }
        listener.onStateChanged(STATE_ERROR, currentName);
        listener.onError(message);
    }

    private void scheduleReadyTimeout() {
        handler.removeCallbacks(readyTimeoutRunnable);
        currentReadyTimeoutMs = isUdpxyStream(currentUrl()) ? RTSP_READY_TIMEOUT_MS : readyTimeoutMs;
        handler.postDelayed(readyTimeoutRunnable, currentReadyTimeoutMs);
    }

    private void cancelReadyTimeout() {
        handler.removeCallbacks(readyTimeoutRunnable);
    }

    private void scheduleNoVideoTimeout() {
        handler.removeCallbacks(noVideoTimeoutRunnable);
        // udpxy TS-over-HTTP 流的 onVideoSizeChanged 不触发，跳过无视频帧检测
        if (isUdpxyStream(currentUrl())) return;
        handler.postDelayed(noVideoTimeoutRunnable, NO_VIDEO_TIMEOUT_MS);
    }

    private void cancelNoVideoTimeout() {
        handler.removeCallbacks(noVideoTimeoutRunnable);
    }

    private ExoPlayer player;
    private android.media.session.MediaSession mediaSession;
    private SurfaceView surfaceView;
    private String currentName;
    /** 当前频道的所有源 */
    private final List<String> sources = new ArrayList<String>();
    /** 当前正在使用的源下标 */
    private int sourceIndex = 0;
    /** 是否启用自动换源 */
    private boolean autoSource = true;
    /** 自动换源延迟（毫秒）：当前源失败后等多久再切下一个源 */
    private long autoSourceDelayMs = 3000L;
    /** 解码方式：DECODE_HW 硬解 / DECODE_SW 软解 */
    private int decodeMode = DECODE_HW;
    /** 播放就绪超时（毫秒），默认 5 秒 */
    private long readyTimeoutMs = DEFAULT_READY_TIMEOUT_MS;
    /** 画面缩放：SCALE_FIT 按比例 / SCALE_FILL 全屏填充 */
    private int scaleMode = SCALE_FIT;
    private boolean lowLatency = false;
    private boolean triedHlsFallback = false;
    private int retryCount = 0;
    private boolean released = false;
    /** 换源记忆：频道名 -> 上次使用的源下标 */
    private final java.util.Map<String, Integer> sourceMemory = new java.util.HashMap<String, Integer>();
    /** 是否启用换源记忆 */
    private boolean sourceMemoryEnabled = true;
    /** 连续 STATE_ENDED 次数（超过阈值后换源） */
    private int endedCount = 0;
    /** 是否已收到视频帧（用于黑屏检测） */
    private boolean hasVideoFrame = false;

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
        if (currentUrl() != null) start();
    }

    public boolean isAutoSource() {
        return autoSource;
    }

    /** 开/关自动换源 */
    public void setAutoSource(boolean autoSource) {
        this.autoSource = autoSource;
    }

    /** 开/关换源记忆 */
    public void setSourceMemory(boolean enabled) {
        this.sourceMemoryEnabled = enabled;
    }

    /** 设置自动换源延迟（毫秒），0 表示立即换 */
    public void setAutoSourceDelay(long delayMs) {
        this.autoSourceDelayMs = delayMs < 0 ? 0 : delayMs;
    }

    public long getAutoSourceDelay() {
        return autoSourceDelayMs;
    }

    /** 设置解码方式（硬解/软解），会重建播放器 */
    public void setDecoderMode(int mode) {
        if (this.decodeMode == mode) return;
        this.decodeMode = mode;
        releasePlayer();
        if (currentUrl() != null) start();
    }

    public int getDecoderMode() {
        return decodeMode;
    }

    /** 设置播放就绪超时（毫秒），最小 5000 */
    public void setReadyTimeout(long timeoutMs) {
        this.readyTimeoutMs = timeoutMs < 5000L ? 5000L : timeoutMs;
    }

    public long getReadyTimeout() {
        return readyTimeoutMs;
    }

    /** 设置画面缩放模式（按比例/全屏填充），立即生效 */
    public void setScaleMode(int mode) {
        this.scaleMode = mode;
        applyScaleMode();
    }

    public int getScaleMode() {
        return scaleMode;
    }

    private void applyScaleMode() {
        if (player == null) return;
        try {
            // 与 SurfaceHolder 的 VIDEO_SCALING_MODE 一致：SCALE_TO_FIT=1, SCALE_TO_FIT_WITH_CROP=2
            int scaling = scaleMode == SCALE_FILL ? 2 : 1;
            player.setVideoScalingMode(scaling);
        } catch (Throwable ignored) {
        }
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public int getSourceCount() {
        return sources.size();
    }

    /**
     * 播放一个频道的所有源。
     *
     * @param urls        该频道的全部播放地址（按优先级排序）
     * @param channelName 频道名
     */
    public void play(List<String> urls, String channelName) {
        if (urls == null || urls.isEmpty()) {
            Log.w(TAG, "play() 无可用源: " + channelName);
            listener.onStateChanged(STATE_ERROR, channelName == null ? "" : channelName);
            listener.onError("该频道没有可用的播放地址");
            return;
        }
        Log.i(TAG, "开始播放: " + channelName + " 源数=" + urls.size());
        sources.clear();
        for (String u : urls) {
            if (u != null && !u.trim().isEmpty()) sources.add(u.trim());
        }
        currentName = channelName == null ? "" : channelName;
        sourceIndex = 0;
        retryCount = 0;
        triedHlsFallback = false;
        endedCount = 0;
        released = false;
        cancelReadyTimeout();
        // 换源记忆：恢复上次使用的源下标
        if (sourceMemoryEnabled && sourceMemory.containsKey(currentName)) {
            int saved = sourceMemory.get(currentName);
            if (saved >= 0 && saved < sources.size()) {
                sourceIndex = saved;
                Log.i(TAG, "换源记忆恢复: " + currentName + " -> 源 " + (sourceIndex + 1) + "/" + sources.size());
            }
        }
        notifySourceChanged();
        start();
    }

    /** 切换到下一个源（手动或自动），成功返回 true */
    public boolean nextSource() {
        if (sources.size() <= 1) return false;
        if (sourceIndex + 1 < sources.size()) {
            sourceIndex++;
        } else {
            sourceIndex = 0;
        }
        retryCount = 0;
        triedHlsFallback = false;
        notifySourceChanged();
        if (!released && currentUrl() != null) start();
        return true;
    }

    /** 切换到上一个源 */
    public boolean prevSource() {
        if (sources.size() <= 1) return false;
        if (sourceIndex - 1 >= 0) {
            sourceIndex--;
        } else {
            sourceIndex = sources.size() - 1;
        }
        retryCount = 0;
        triedHlsFallback = false;
        notifySourceChanged();
        if (!released && currentUrl() != null) start();
        return true;
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

    /* ==================== 换源逻辑 ==================== */

    private boolean tryNextSource() {
        if (!autoSource) return false;
        if (sources.size() <= 1) {
            // 单源频道也自动跳下一个台，不显示错误
            Log.i(TAG, "单源频道失败，通知上层自动切台");
            if (listener != null) listener.onSourcesExhausted();
            return true;
        }
        // 已到最后一路：所有源都失败，交给上层跳下一个台
        if (sourceIndex >= sources.size() - 1) {
            Log.i(TAG, "所有源已耗尽，通知上层自动切台");
            if (listener != null) listener.onSourcesExhausted();
            return true;
        }
        sourceIndex++;
        retryCount = 0;
        triedHlsFallback = false;
        endedCount = 0;
        Log.i(TAG, "自动换源 -> [" + (sourceIndex + 1) + "/" + sources.size() + "] " + currentUrl());
        notifySourceChanged();
        listener.onStateChanged(STATE_CONNECTING,
                currentName + "（换源 " + (sourceIndex + 1) + "/" + sources.size() + "）");
        handler.postDelayed(reconnectRunnable, autoSourceDelayMs);
        return true;
    }

    private void notifySourceChanged() {
        if (listener != null) listener.onSourceChanged(sourceIndex, sources.size());
    }

    private String currentUrl() {
        if (sources.isEmpty()) return null;
        if (sourceIndex < 0 || sourceIndex >= sources.size()) return null;
        return sources.get(sourceIndex);
    }

    /* ==================== 内部实现 ==================== */

    private void start() {
        if (released || currentUrl() == null) return;
        String url = currentUrl();
        Log.d(TAG, "start(): url=" + url + " retry=" + retryCount + " hlsFallback=" + triedHlsFallback);

        // Android 12+: 确保音频模式正常，避免 AudioTrack 被系统释放
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_NORMAL);
                Log.d(TAG, "音频模式已设为 MODE_NORMAL");
            }
        } catch (Throwable ignored) {
        }

        try {
            ensurePlayer();
            listener.onStateChanged(STATE_CONNECTING, currentName);
            player.setMediaSource(buildSource(url));
            player.prepare();
            player.play();
            // 开始就绪超时计时（udpxy TS 流用更长超时）
            long timeout = isUdpxyStream(url) ? RTSP_READY_TIMEOUT_MS : readyTimeoutMs;
            currentReadyTimeoutMs = timeout;
            Log.d(TAG, "就绪超时: " + timeout + "ms");
            handler.removeCallbacks(readyTimeoutRunnable);
            handler.postDelayed(readyTimeoutRunnable, timeout);
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
                    .createMediaSource(MediaItem.fromUri(Uri.parse(currentUrl()))));
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

        // 软解：优先选系统软件解码器（API 21+ 支持 isSoftwareOnly）
        if (decodeMode == DECODE_SW) {
            renderers.setMediaCodecSelector(new MediaCodecSelector() {
                @Override
                public List<MediaCodecInfo> getDecoderInfos(
                        String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) {
                    List<MediaCodecInfo> all;
                    try {
                        all = MediaCodecUtil.getDecoderInfos(
                                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    } catch (Throwable t) {
                        all = null;
                    }
                    if (android.os.Build.VERSION.SDK_INT < 21 || all == null || all.size() <= 1) {
                        return all;
                    }
                    List<MediaCodecInfo> soft = new ArrayList<MediaCodecInfo>();
                    for (MediaCodecInfo info : all) {
                        try {
                            // ExoPlayer MediaCodecInfo.hardwareAccelerated==false 表示软件解码器
                            if (!info.hardwareAccelerated) soft.add(info);
                        } catch (Throwable ignored) {
                        }
                    }
                    return soft.isEmpty() ? all : soft;
                }
            });
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        lowLatency ? 6000 : 20000,
                        lowLatency ? 15000 : 60000,
                        lowLatency ? 400 : 1000,
                        lowLatency ? 800 : 2000)
                .build();

        // Android 12+ 必须在 player 创建前创建并激活 MediaSession
        // 否则 AudioTrack 会被系统释放导致无声
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                mediaSession = new android.media.session.MediaSession(context, "TvLivePlayer");
                mediaSession.setActive(true);
                mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
                    @Override
                    public void onPlay() {
                        if (player != null && !player.isPlaying())
                            player.play();
                    }
                    @Override
                    public void onPause() {
                        if (player != null && player.isPlaying())
                            player.pause();
                    }
                    @Override
                    public void onStop() {
                        if (player != null && player.getPlaybackState() != Player.STATE_IDLE)
                            player.stop();
                    }
                });
                Log.d(TAG, "MediaSession 已创建并激活");
            } catch (Throwable t) {
                Log.w(TAG, "MediaSession 创建失败: " + t);
            }
        }

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setLoadControl(loadControl)
                .build();
        player.addListener(playerListener);
        applyScaleMode();
        if (surfaceView != null) {
            try {
                player.setVideoSurfaceView(surfaceView);
            } catch (Throwable ignored) {
            }
        }

        // 更新初始播放状态
        updatePlaybackState();
    }

    /** 更新 MediaSession 的 PlaybackState，Android 12+ 依赖此状态路由音频 */
    private void updatePlaybackState() {
        if (mediaSession == null || player == null) return;
        try {
            int state;
            int ps = player.getPlaybackState();
            if (ps == Player.STATE_READY || ps == Player.STATE_BUFFERING) {
                state = PlaybackState.STATE_PLAYING;
            } else if (ps == Player.STATE_IDLE || ps == Player.STATE_ENDED) {
                state = PlaybackState.STATE_STOPPED;
            } else {
                state = PlaybackState.STATE_BUFFERING;
            }
            PlaybackState.Builder builder = new PlaybackState.Builder()
                    .setState(state, player.getCurrentPosition(), 1.0f);
            mediaSession.setPlaybackState(builder.build());
        } catch (Throwable ignored) {
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
        if (mediaSession != null) {
            try {
                mediaSession.release();
            } catch (Throwable ignored) {
            }
            mediaSession = null;
        }
    }

    /** 按协议选 MediaSource：rtsp 走 RTSP，m3u8 走 HLS，udpxy 走渐进式+TS，其余走渐进式 */
    private MediaSource buildSource(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(context);

        // RTSP 直连（真正的 rtsp:// URL）
        if ("rtsp".equals(scheme) || "rtsps".equals(scheme)) {
            return new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(12000)
                    .setUserAgent("TvLive/1.0")
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        // udpxy / TS-over-HTTP：http URL 路径含 /rtp/，服务器是 UDP→HTTP 代理，
        // 返回的是原始 MPEG-TS 数据（0x47 同步字节），不是 RTSP
        if (isUdpxyStream(url)) {
            Log.i(TAG, "udpxy TS-over-HTTP: " + url);
            MediaItem item = new MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType("video/mp2t")
                    .build();
            return new ProgressiveMediaSource.Factory(dataSource)
                    .createMediaSource(item);
        }
        MediaItem item = MediaItem.fromUri(uri);
        if (isHls(url)) {
            return new HlsMediaSource.Factory(dataSource)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSource).createMediaSource(item);
    }

    /** 判断是否为 udpxy TS-over-HTTP 流（http URL 路径含 /rtp/，返回原始 MPEG-TS 数据） */
    private boolean isUdpxyStream(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        return lower.contains("/rtp/") || lower.contains("/rtsp/");
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
