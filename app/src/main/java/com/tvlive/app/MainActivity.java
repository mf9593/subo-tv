package com.tvlive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.tvlive.app.data.Channel;
import com.tvlive.app.data.ChannelStore;
import com.tvlive.app.player.LivePlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播主界面。
 *
 * <p>遥控器操作：</p>
 * <ul>
 *     <li>上/下、CH+/-：上/下一个台（循环）</li>
 *     <li>数字键：输入台号直接跳台（1.5 秒后自动切）</li>
 *     <li>方向盘中间键 / OK：频道列表</li>
 *     <li>左/右：调音量</li>
 *     <li>菜单键：菜单（频道列表 / 重新载入 M3U / 缓冲策略 / 帮助）</li>
 *     <li>返回键：连按两次退出</li>
 * </ul>
 */
public class MainActivity extends Activity implements LivePlayer.Listener {

    private static final String TAG = "TvLive";

    private static final String PREFS = "tvlive";
    private static final String KEY_LAST_INDEX = "last_index";

    private static final long OSD_HIDE_DELAY = 4000L;
    private static final long TOAST_HIDE_DELAY = 2500L;
    private static final long NUMBER_COMMIT_DELAY = 1500L;
    private static final long EXIT_CONFIRM_WINDOW = 2000L;

    private View statePanel;
    private TextView stateText;
    private View osdPanel;
    private TextView osdNumber;
    private TextView osdName;
    private TextView osdUrl;
    private TextView toastText;

    private LivePlayer livePlayer;
    private final List<Channel> channels = new ArrayList<Channel>();
    private int currentIndex = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder numberBuffer = new StringBuilder();
    private SharedPreferences prefs;
    private long lastBackTime = 0L;

    private final Runnable osdHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (osdPanel != null) osdPanel.setVisibility(View.GONE);
        }
    };

    private final Runnable toastHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (toastText != null) toastText.setVisibility(View.GONE);
        }
    };

    private final Runnable numberCommitRunnable = new Runnable() {
        @Override
        public void run() {
            String typed = numberBuffer.toString();
            numberBuffer.setLength(0);
            if (typed.isEmpty()) return;
            try {
                jumpToNumber(Integer.parseInt(typed));
            } catch (Exception ignored) {
            }
        }
    };

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();

        livePlayer = new LivePlayer(this, this);
        SurfaceView surface = (SurfaceView) findViewById(R.id.surface);
        livePlayer.attach(surface);

        List<Channel> loaded = ChannelStore.load(this);
        Log.i(TAG, "频道数: " + loaded.size() + " 来源: " + ChannelStore.lastSource(this));
        channels.clear();
        channels.addAll(loaded);

        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel));
            return;
        }
        int index = prefs.getInt(KEY_LAST_INDEX, 0);
        if (index < 0 || index >= channels.size()) index = 0;
        switchTo(index, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (livePlayer != null) livePlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (livePlayer != null) livePlayer.resume();
    }

    @Override
    protected void onDestroy() {
        if (livePlayer != null) livePlayer.release();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        statePanel = findViewById(R.id.statePanel);
        stateText = (TextView) findViewById(R.id.stateText);
        osdPanel = findViewById(R.id.osdPanel);
        osdNumber = (TextView) findViewById(R.id.osdNumber);
        osdName = (TextView) findViewById(R.id.osdName);
        osdUrl = (TextView) findViewById(R.id.osdUrl);
        toastText = (TextView) findViewById(R.id.toastText);
    }

    /* ==================== 遥控器按键 ==================== */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                changeChannel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                changeChannel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjustVolume(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjustVolume(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                showChannelList();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_INFO:
                showOsd();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (numberBuffer.length() > 0) {
                    numberBuffer.setLength(0);
                    handler.removeCallbacks(numberCommitRunnable);
                    showOsd();
                    return true;
                }
                handleBack();
                return true;
            default:
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    appendNumber(keyCode - KeyEvent.KEYCODE_0);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    private void handleBack() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < EXIT_CONFIRM_WINDOW) {
            finish();
            return;
        }
        lastBackTime = now;
        showToast("再按一次返回键退出");
    }

    private void appendNumber(int digit) {
        if (numberBuffer.length() >= 3) numberBuffer.setLength(0);
        numberBuffer.append(digit);
        if (osdPanel != null) {
            osdNumber.setText(numberBuffer.toString());
            osdName.setText("输入台号…");
            osdUrl.setText("");
        }
        showOsd();
        handler.removeCallbacks(numberCommitRunnable);
        handler.postDelayed(numberCommitRunnable, NUMBER_COMMIT_DELAY);
    }

    private void jumpToNumber(int number) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number == number) {
                switchTo(i, true);
                return;
            }
        }
        showToast("没有 " + number + " 台");
        if (currentIndex >= 0 && currentIndex < channels.size()) {
            updateOsd(channels.get(currentIndex));
        }
    }

    private void adjustVolume(boolean up) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            showToast("音量 " + current + " / " + max);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 换台 ==================== */

    private void changeChannel(int delta) {
        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel));
            return;
        }
        int next = currentIndex < 0 ? 0 : currentIndex + delta;
        switchTo(next, true);
    }

    private void switchTo(int index, boolean showOsdBalloon) {
        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel));
            return;
        }
        if (index < 0) index = channels.size() - 1;
        if (index >= channels.size()) index = 0;

        currentIndex = index;
        Channel channel = channels.get(index);
        prefs.edit().putInt(KEY_LAST_INDEX, index).apply();

        Log.i(TAG, "切台: " + channel.number + " " + channel.name);
        updateOsd(channel);
        if (showOsdBalloon) showOsd();
        showState("正在连接 " + channel.name + "…");
        livePlayer.play(channel.url, channel.name);
    }

    private void updateOsd(Channel channel) {
        if (channel == null) return;
        osdNumber.setText(String.valueOf(channel.number));
        osdName.setText(channel.name);
        osdUrl.setText(channel.url);
    }

    /* ==================== 播放回调 ==================== */

    @Override
    public void onStateChanged(int state, String channelName) {
        if (state == LivePlayer.STATE_PLAYING) {
            hideState();
        } else if (state == LivePlayer.STATE_CONNECTING) {
            if (statePanel.getVisibility() != View.VISIBLE) {
                showState("正在连接 " + (channelName == null ? "" : channelName) + "…");
            }
        }
    }

    @Override
    public void onError(String message) {
        showState("播放失败：" + message
                + "\n\n按上下键换台，数字键直接跳台，菜单键检查设置");
    }

    /* ==================== 界面元素 ==================== */

    private void showState(String text) {
        if (stateText != null) stateText.setText(text);
        if (statePanel != null) statePanel.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        if (statePanel != null) statePanel.setVisibility(View.GONE);
    }

    private void showOsd() {
        if (osdPanel == null) return;
        osdPanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(osdHideRunnable);
        handler.postDelayed(osdHideRunnable, OSD_HIDE_DELAY);
    }

    private void showToast(String text) {
        if (toastText == null) return;
        toastText.setText(text);
        toastText.setVisibility(View.VISIBLE);
        handler.removeCallbacks(toastHideRunnable);
        handler.postDelayed(toastHideRunnable, TOAST_HIDE_DELAY);
    }

    /* ==================== 弹窗 ==================== */

    private void showChannelList() {
        if (channels.isEmpty()) {
            showToast("还没有频道");
            return;
        }
        ArrayAdapter<Channel> adapter =
                new ArrayAdapter<Channel>(this, R.layout.item_channel, channels);
        new AlertDialog.Builder(this)
                .setTitle("频道列表（共 " + channels.size() + " 个）")
                .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        switchTo(which, true);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showMenu() {
        final String[] items = new String[]{
                "频道列表",
                "重新载入频道（外部 M3U / 内置列表）",
                "缓冲策略：" + (livePlayer != null && livePlayer.isLowLatency() ? "低延迟" : "流畅优先"),
                "帮助与说明"
        };
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            showChannelList();
                        } else if (which == 1) {
                            reloadChannels();
                        } else if (which == 2) {
                            toggleBufferMode();
                        } else {
                            showHelp();
                        }
                    }
                })
                .setNegativeButton("返回", null)
                .show();
    }

    private void reloadChannels() {
        List<Channel> reloaded = ChannelStore.reload(this);
        channels.clear();
        channels.addAll(reloaded);
        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel));
            showToast("没有找到频道文件");
            return;
        }
        showToast("已载入 " + channels.size() + " 个频道\n" + ChannelStore.lastSource(this));
        switchTo(0, true);
    }

    private void toggleBufferMode() {
        if (livePlayer == null) return;
        boolean lowLatency = !livePlayer.isLowLatency();
        livePlayer.setLowLatency(lowLatency);
        showToast(lowLatency ? "已切换到低延迟模式（换台更快）" : "已切换到流畅优先模式（更抗卡顿）");
    }

    private void showHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("遥控器操作\n");
        sb.append("· 上/下、CH+/-：上/下一个台\n");
        sb.append("· 数字键：输入台号直接跳台\n");
        sb.append("· 方向盘中间键：频道列表\n");
        sb.append("· 左/右：调音量\n");
        sb.append("· 返回键：连按两次退出\n\n");
        sb.append("添加自己的频道\n");
        sb.append("把 tvlive.m3u 放到内置存储根目录或 Download 目录，然后菜单 → 重新载入频道。\n\n");
        sb.append("M3U 写法示例\n");
        sb.append("#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n");
        sb.append("http://example.com/cctv1.m3u8\n\n");
        sb.append("支持 http/https 的 HLS(m3u8)、RTSP 摄像头流、mp4 等。\n\n");
        sb.append("当前频道来源：").append(ChannelStore.lastSource(this));

        new AlertDialog.Builder(this)
                .setTitle("帮助")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }
}
