package com.tvlive.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tvlive.app.data.Channel;
import com.tvlive.app.data.ChannelStore;
import com.tvlive.app.data.SourceStore;
import com.tvlive.app.player.LivePlayer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 电视直播主界面。
 *
 * <p>遥控器操作：</p>
 * <ul>
 *     <li>上/下、CH+/-：上/下一个台（循环）</li>
 *     <li>数字键：输入台号直接跳台（1.5 秒后自动切）</li>
 *     <li>左/右：切换当前频道的播放源</li>
 *     <li>OK：频道列表（左侧两列：分类 | 频道）</li>
 *     <li>菜单键：设置（右侧面板）</li>
 *     <li>返回键：关面板，连按两次退出</li>
 * </ul>
 */
public class MainActivity extends Activity implements LivePlayer.Listener {

    private static final String TAG = "TvLive";

    private static final String PREFS = "tvlive";
    private static final String KEY_LAST_INDEX = "last_index";
    private static final String KEY_AUTO_SOURCE = "auto_source";
    private static final String KEY_AUTO_SOURCE_DELAY = "auto_source_delay";
    private static final String KEY_KEY_HINT = "key_hint_visible";
    private static final String KEY_DECODE_MODE = "decode_mode";
    private static final String KEY_READY_TIMEOUT = "ready_timeout";
    private static final String KEY_SCALE_MODE = "scale_mode";
    private static final String KEY_SOURCE_MEMORY = "source_memory";
    private static final String KEY_LAST_SOURCE_PREFIX = "last_source_";

    /** 自动换源延迟可选值（秒），0=立即 */
    private static final int[] AUTO_SOURCE_DELAYS = new int[]{0, 2, 3, 5, 8, 10};
    private static final String[] AUTO_SOURCE_DELAY_LABELS =
            new String[]{"立即", "2 秒", "3 秒", "5 秒", "8 秒", "10 秒"};

    /** 就绪超时可选值（秒），最小 5 */
    private static final int[] READY_TIMEOUTS = new int[]{5, 8, 10, 15, 20};
    private static final String[] READY_TIMEOUT_LABELS =
            new String[]{"5 秒", "8 秒", "10 秒", "15 秒", "20 秒"};

    private static final long OSD_HIDE_DELAY = 4000L;
    private static final long TOAST_HIDE_DELAY = 2500L;
    private static final long VOLUME_HIDE_DELAY = 2000L;
    private static final long NUMBER_COMMIT_DELAY = 1500L;
    private static final long EXIT_CONFIRM_WINDOW = 2000L;
    private static final long CLOCK_TICK = 1000L;

    private TextView clockText;
    private View statePanel;
    private ProgressBar stateSpinner;
    private TextView stateText;
    private View osdPanel;
    private TextView osdNumber;
    private TextView osdName;
    private TextView osdGroup;
    private TextView osdUrl;
    private TextView osdResolution;
    private View volumePanel;
    private ProgressBar volumeBar;
    private TextView volumeText;
    private TextView toastText;

    // 频道面板（左侧两列）
    private View channelPanel;
    private View channelPanelContent;
    private ListView categoryList;
    private ListView channelList;
    private TextView channelCount;
    private TextView categoryCurrent;

    // 设置面板（右侧）
    private View settingsPanel;
    private View settingsPanelContent;
    private ListView settingsList;
    private TextView settingsVersion;
    // 设备信息栏（IP + adb 状态 + adb 地址端口）
    private View deviceInfoBar;
    private TextView deviceIpText;
    private TextView adbStatusText;

    private LivePlayer livePlayer;
    private GestureDetector gestureDetector;
    private com.tvlive.app.server.LocalWebServer webServer;
    private ImportDialog importDialog;
    private int settingOptionLocked = -1;  // 当前锁定的多选项设置下标（-1=未锁定）
    private int settingsSelectedPos = 0;   // 设置列表当前选中项（用于高亮）

    /* 设置项下标常量（原在 SettingsAdapter 内部，提到外层以便 cycleSettingOption 访问） */
    private static final int SWITCH_SOURCE = 0;
    private static final int AUTO_SOURCE = 1;
    private static final int SOURCE_DELAY = 2;
    private static final int SCALE = 3;
    private static final int TIMEOUT = 4;
    private static final int IMPORT = 5;
    private static final int RELOAD = 6;
    private static final int DECODE = 7;
    private static final int BUFFER = 8;
    private static final int KEY_HINT = 9;
    private static final int THEME = 10;
    private static final int SOURCE_MEMORY = 11;
    private static final int HELP = 12;
    private static final int CHECK_UPDATE = 13;
    private final List<Channel> channels = new ArrayList<Channel>();
    private final List<String> categories = new ArrayList<String>();
    private int currentCategory = 0;
    private int currentIndex = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder numberBuffer = new StringBuilder();
    private SharedPreferences prefs;
    private long lastBackTime = 0L;

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            if (clockText != null) clockText.setText(clockFormat.format(new Date()));
            handler.postDelayed(this, CLOCK_TICK);
        }
    };

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

    private final Runnable volumeHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (volumePanel != null) volumePanel.setVisibility(View.GONE);
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
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        // 沉浸式全屏：隐藏顶部状态栏和底部导航栏
        applyImmersive();
        // decor view 布局完成后再应用一次
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                applyImmersive();
            }
        });

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ThemeManager.init(this);
        bindViews();
        setPanelWidths();

        livePlayer = new LivePlayer(this, this);
        livePlayer.setAutoSource(prefs.getBoolean(KEY_AUTO_SOURCE, true));
        livePlayer.setAutoSourceDelay(prefs.getInt(KEY_AUTO_SOURCE_DELAY, 3) * 1000L);
        livePlayer.setDecoderMode(prefs.getInt(KEY_DECODE_MODE, LivePlayer.DECODE_HW));
        livePlayer.setReadyTimeout(prefs.getInt(KEY_READY_TIMEOUT, 5) * 1000L);
        livePlayer.setScaleMode(prefs.getInt(KEY_SCALE_MODE, LivePlayer.SCALE_FIT));
        SurfaceView surface = (SurfaceView) findViewById(R.id.surface);
        livePlayer.attach(surface);
        setupTouch(surface);

        List<Channel> loaded = ChannelStore.load(this);
        Log.i(TAG, "频道数: " + loaded.size() + " 来源: " + ChannelStore.lastSource(this));
        channels.clear();
        channels.addAll(loaded);

        // Web 后台 + 导入弹窗：即使无频道也要初始化，否则无法导入
        setupWebServer();

        // 调试：通过 intent extra 直接打开导入弹窗（adb am start 触发）
        if (getIntent().hasExtra("action")) {
            String action = getIntent().getStringExtra("action");
            final String act = action;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if ("open_import".equals(act)) {
                        android.util.Log.d("MainActivity", "DEBUG: opening import dialog");
                        openImportDialog();
                    } else if ("open_channels".equals(act)) {
                        android.util.Log.d("MainActivity", "DEBUG: opening channel panel");
                        openChannelPanel();
                    } else if ("open_settings".equals(act)) {
                        android.util.Log.d("MainActivity", "DEBUG: opening settings panel");
                        openSettingsPanel();
                    } else if ("show_debug".equals(act)) {
                        android.util.Log.d("MainActivity", "DEBUG: showing debug overlay");
                        if (isDebuggable()) {
                            TextView dbg = findViewById(R.id.debugInfo);
                            if (dbg != null) dbg.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }, 1000);
        }

        if (channels.isEmpty()) {
            if (livePlayer != null) livePlayer.release();
            showState(getString(R.string.no_channel), false);
            return;
        }
        rebuildCategories();
        setupChannelPanel();
        int index = prefs.getInt(KEY_LAST_INDEX, 0);
        if (index < 0 || index >= channels.size()) index = 0;
        switchTo(index, true);
    }

    /* ==================== Web 后台（频道导入） ==================== */

    private void setupWebServer() {
        importDialog = new ImportDialog(this, new ImportDialog.Listener() {
            @Override
            public void onSourcesChanged() {
                reloadChannels();
            }

            @Override
            public void onImportClosed() {
                // 退出频道导入：关闭 Web 后台服务，防止后台被他人上传文件影响播放
                if (webServer != null) {
                    try {
                        webServer.stop();
                    } catch (Throwable t) {
                        android.util.Log.w("MainActivity", "webServer stop: " + t);
                    }
                }
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        View touch = findViewById(R.id.touchLayer);
                        if (touch != null) touch.requestFocus();
                        getWindow().getDecorView().requestFocus();
                    }
                }, 300);
            }
        });
        // 初始化叠加层（替代 Dialog）
        android.widget.FrameLayout overlay = (android.widget.FrameLayout) findViewById(R.id.importOverlay);
        if (overlay != null) {
            importDialog.init(overlay);
            android.util.Log.d("MainActivity", "setupWebServer: importOverlay initialized");
        } else {
            android.util.Log.e("MainActivity", "setupWebServer: importOverlay not found!");
        }
        webServer = new com.tvlive.app.server.LocalWebServer(this,
                new com.tvlive.app.server.LocalWebServer.Callback() {
                    @Override
                    public void onSourceSubmitted(String name, String url, String text) {
                        SourceStore.add(MainActivity.this, name, url, text);
                        showToast("已从后台添加源：" + name);
                        if (importDialog != null) importDialog.refresh();
                        reloadChannels();
                    }
                });
        // Web 后台改为按需启动：点击"频道导入"时启动，退出导入时关闭，
        // 避免后台常驻被他人上传文件影响播放。
    }

    /** 打开频道导入弹窗（按需启动 Web 后台服务） */
    private void openImportDialog() {
        if (importDialog == null) { android.util.Log.e("MainActivity", "openImportDialog: importDialog is null"); return; }
        // 启动 Web 后台（已运行则幂等返回）
        String url = "";
        if (webServer != null) {
            try {
                webServer.start();
            } catch (Throwable t) {
                android.util.Log.w("MainActivity", "webServer start: " + t);
            }
            url = webServer.baseUrl();
        }
        android.util.Log.d("MainActivity", "openImportDialog: calling show, url=" + url);
        importDialog.show(url);
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
        handler.post(clockRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(clockRunnable);
    }

    @Override
    protected void onDestroy() {
        if (livePlayer != null) livePlayer.release();
        if (webServer != null) webServer.stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        clockText = (TextView) findViewById(R.id.clockText);
        statePanel = findViewById(R.id.statePanel);
        stateSpinner = (ProgressBar) findViewById(R.id.stateSpinner);
        stateText = (TextView) findViewById(R.id.stateText);
        osdPanel = findViewById(R.id.osdPanel);
        osdNumber = (TextView) findViewById(R.id.osdNumber);
        osdName = (TextView) findViewById(R.id.osdName);
        osdGroup = (TextView) findViewById(R.id.osdGroup);
        osdUrl = (TextView) findViewById(R.id.osdUrl);
        osdResolution = (TextView) findViewById(R.id.infoResolution);
        volumePanel = findViewById(R.id.volumePanel);
        volumeBar = (ProgressBar) findViewById(R.id.volumeBar);
        volumeText = (TextView) findViewById(R.id.volumeText);
        toastText = (TextView) findViewById(R.id.toastText);
        channelPanel = findViewById(R.id.channelPanel);
        channelPanelContent = findViewById(R.id.channelPanelContent);
        categoryList = (ListView) findViewById(R.id.categoryList);
        channelList = (ListView) findViewById(R.id.channelList);
        channelCount = (TextView) findViewById(R.id.channelCount);
        categoryCurrent = (TextView) findViewById(R.id.categoryCurrent);
        settingsPanel = findViewById(R.id.settingsPanel);
        settingsPanelContent = findViewById(R.id.settingsPanelContent);
        settingsList = (ListView) findViewById(R.id.settingsList);
        settingsVersion = (TextView) findViewById(R.id.settingsVersion);
        deviceInfoBar = findViewById(R.id.deviceInfoBar);
        deviceIpText = (TextView) findViewById(R.id.deviceIpText);
        adbStatusText = (TextView) findViewById(R.id.adbStatusText);
        // 面板遮罩区域点击关闭面板
        channelPanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isChannelPanelOpen()) closeChannelPanelOnly();
            }
        });
        settingsPanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isSettingsPanelOpen()) closeSettingsPanel();
            }
        });
        bindSettingsClick();
        // 底部按键提示开关
        View keyHint = findViewById(R.id.keyHint);
        if (keyHint != null) {
            keyHint.setVisibility(prefs.getBoolean(KEY_KEY_HINT, true) ? View.VISIBLE : View.GONE);
        }
    }

    /* ==================== 触屏支持 ==================== */

    /** 触屏手势：单击左半屏=频道面板，单击右半屏=设置面板，左右滑=换源，上下滑=换台 */
    private void setupTouch(SurfaceView surface) {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                float x = e.getX();
                if (x < screenW / 2.0f) {
                    // 左半屏：切换频道面板
                    if (isChannelPanelOpen()) {
                        closeChannelPanelOnly();
                    } else {
                        openChannelPanel();
                    }
                } else {
                    // 右半屏：切换设置面板
                    if (isSettingsPanelOpen()) {
                        closeSettingsPanel();
                    } else {
                        openSettingsPanel();
                    }
                }
                showOsd();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < 60 && Math.abs(dy) < 60) return false;
                if (Math.abs(dx) > Math.abs(dy)) {
                    // 左右滑：换源
                    switchSource(dx > 0 ? -1 : 1);
                } else {
                    // 上下滑：换台
                    changeChannel(dy > 0 ? 1 : -1);
                }
                return true;
            }
        });
        if (surface != null) {
            // 优先在 touchLayer 上设置触摸监听（最可靠）
            View touchLayer = findViewById(R.id.touchLayer);
            if (touchLayer != null) {
                touchLayer.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return gestureDetector != null && gestureDetector.onTouchEvent(event);
                    }
                });
            }
            // 根布局上也设置一份，作为兜底
            View rootView = (View) surface.getParent();
            if (rootView != null) {
                rootView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return gestureDetector != null && gestureDetector.onTouchEvent(event);
                    }
                });
            }
        }
    }

    /* ==================== 沉浸式全屏 ==================== */

    /** 沉浸式：隐藏顶部状态栏（大黑边）和底部导航栏（小白条） */
    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Android 11+：用 WindowInsetsController 替代已废弃的 SYSTEM_UI_FLAG
            android.view.WindowInsetsController controller =
                    getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
            getWindow().getDecorView().requestLayout();
            // 窗口级标志：部分老设备需要
            getWindow().addFlags(LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 导入弹窗（叠加层）打开时，按键交给 ImportDialog 处理并消费
        if (isImportOpen() && importDialog != null) {
            android.util.Log.d("MainActivity", "dispatchKeyEvent: import overlay open, key=" + event.getKeyCode());
            importDialog.onKey(event.getKeyCode(), event);
            return true;
        }
        int keyCode = event.getKeyCode();
        boolean isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;

        // 设置面板打开时，上下键直接移动选中项（不依赖 ListView 焦点，Android 4.4 兼容）
        if (isSettingsPanelOpen() && !isHelpOpen()
                && event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int pos = settingsSelectedPos;
            int count = settingsList != null && settingsList.getAdapter() != null
                    ? settingsList.getAdapter().getCount() : 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (pos > 0) settingsSelectedPos = pos - 1;
            } else {
                if (pos < count - 1) settingsSelectedPos = pos + 1;
            }
            if (settingsList != null) {
                settingsList.setSelection(settingsSelectedPos);
                settingsList.smoothScrollToPosition(settingsSelectedPos);
                settingsList.invalidateViews();  // 刷新高亮
            }
            // 取消多选项锁定
            if (settingOptionLocked >= 0) {
                settingOptionLocked = -1;
            }
            return true;
        }

        // OK 键拦截：避免被聚焦的 ListView/View 消费导致 onKeyDown 不被调用
        if (isOk) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (isSettingsPanelOpen()) {
                    if (isHelpOpen()) {
                        restoreSettingsPanel();
                        return true;
                    }
                    int pos = settingsList != null ? settingsSelectedPos : -1;
                    if (pos >= 0 && settingsList != null && pos < settingsList.getAdapter().getCount()) {
                        onSettingClick(pos);
                    }
                    return true;
                }
                if (isChannelPanelOpen()) {
                    return onChannelPanelKey(keyCode, event);
                }
                openChannelPanel();
                return true;
            }
            // 消耗 UP 事件，防止 ListView 触发二次 click
            if (isSettingsPanelOpen() || isChannelPanelOpen() || isHelpOpen()) {
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    /* ==================== 分类构建 ==================== */

    /** 从频道列表提取有序分类 */
    private void rebuildCategories() {
        Set<String> set = new LinkedHashSet<String>();
        for (Channel c : channels) {
            String g = c.group;
            if (g == null || g.isEmpty()) g = "其他";
            set.add(g);
        }
        categories.clear();
        categories.addAll(set);
        if (currentCategory >= categories.size()) currentCategory = 0;
    }

    /** 当前分类下的频道子列表 */
    private List<Channel> channelsInCategory(int cat) {
        List<Channel> list = new ArrayList<Channel>();
        if (cat < 0 || cat >= categories.size()) return list;
        String target = categories.get(cat);
        for (Channel c : channels) {
            String g = c.group;
            if (g == null || g.isEmpty()) g = "其他";
            if (g.equals(target)) list.add(c);
        }
        return list;
    }

    /* ==================== 频道列表面板（左侧两列） ==================== */

    /** 设置频道/设置面板宽度为屏幕的 50%（固定横屏一半） */
    private void setPanelWidths() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // 固定横屏 50%
        int w = screenW / 2;
        if (channelPanelContent != null) {
            channelPanelContent.getLayoutParams().width = w;
            channelPanelContent.requestLayout();
        }
        if (settingsPanelContent != null) {
            settingsPanelContent.getLayoutParams().width = w;
            settingsPanelContent.requestLayout();
        }
        // 分类列宽度：面板宽度的 35%，限制 90~220dp
        View categoryCol = findViewById(R.id.categoryColumn);
        if (categoryCol != null) {
            float density = getResources().getDisplayMetrics().density;
            int minDp = (int) (90 * density);
            int maxDp = (int) (220 * density);
            int catW = (int) (w * 0.35f);
            if (catW < minDp) catW = minDp;
            if (catW > maxDp) catW = maxDp;
            categoryCol.getLayoutParams().width = catW;
            categoryCol.requestLayout();
        }
    }

    private void setupChannelPanel() {
        setPanelWidths();
        channelCount.setText(getString(R.string.channel_count, channels.size()));

        enableFocusNavigation(categoryList);
        enableFocusNavigation(channelList);
        enableFocusNavigation(settingsList);

        categoryList.setAdapter(new CategoryAdapter());
        categoryList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectCategory(position);
            }
        });

        channelList.setAdapter(new ChannelAdapter());
        channelList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                List<Channel> sub = channelsInCategory(currentCategory);
                if (position >= 0 && position < sub.size()) {
                    Channel ch = sub.get(position);
                    int index = channels.indexOf(ch);
                    if (index >= 0) {
                        closePanels();
                        switchTo(index, true);
                    }
                }
            }
        });

        refreshChannelList();
    }

    /** 选择分类：刷新左列分类高亮 + 右列频道列表 */
    private void selectCategory(int position) {
        if (position < 0 || position >= categories.size()) return;
        currentCategory = position;
        categoryList.invalidateViews();      // 刷新左列高亮（当前分类=主题色文本）
        channelList.setAdapter(new ChannelAdapter());  // 刷新右列频道
        channelList.setSelection(0);
        channelList.requestFocus();
    }

    private void refreshChannelList() {
        if (categoryCurrent != null) {
            String name = categories.isEmpty() ? "" : categories.get(currentCategory);
            List<Channel> sub = channelsInCategory(currentCategory);
            categoryCurrent.setText(name + "  ·  " + sub.size() + " 个");
        }
        channelList.setAdapter(new ChannelAdapter());
        channelList.setSelection(0);
        channelList.requestFocus();    }

    private void openChannelPanel() {
        if (channelPanel == null || channels.isEmpty()) return;
        closeSettingsPanel();
        channelPanel.setVisibility(View.VISIBLE);

        // 定位当前频道所在分类
        if (currentIndex >= 0 && currentIndex < channels.size()) {
            String group = channels.get(currentIndex).group;
            if (group == null || group.isEmpty()) group = "其他";
            int cat = categories.indexOf(group);
            if (cat >= 0) currentCategory = cat;
        }
        categoryList.setItemChecked(currentCategory, true);
        categoryList.setSelection(currentCategory);
        refreshChannelList();

        // 定位到当前频道
        List<Channel> sub = channelsInCategory(currentCategory);
        int pos = sub.indexOf(channels.get(currentIndex));
        if (pos < 0) pos = 0;
        channelList.setSelection(pos);
        channelList.requestFocus();
    }

    private void closePanels() {
        if (channelPanel != null) channelPanel.setVisibility(View.GONE);
        closeSettingsPanel();
    }

    private void closeSettingsPanel() {
        if (settingsPanel != null) settingsPanel.setVisibility(View.GONE);
        // 重置为设置列表状态（下次打开显示设置列表）
        TextView title = (TextView) findViewById(R.id.settingsTitleText);
        if (title != null) title.setText(R.string.settings_title);
        FrameLayout helpContainer = (FrameLayout) findViewById(R.id.helpContainer);
        if (helpContainer != null) helpContainer.setVisibility(View.GONE);
        if (settingsList != null) settingsList.setVisibility(View.VISIBLE);
        if (settingsVersion != null) settingsVersion.setVisibility(View.GONE);
    }

    private boolean isChannelPanelOpen() {
        return channelPanel != null && channelPanel.getVisibility() == View.VISIBLE;
    }

    private boolean isSettingsPanelOpen() {
        return settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE;
    }

    private boolean isHelpOpen() {
        FrameLayout hc = (FrameLayout) findViewById(R.id.helpContainer);
        return hc != null && hc.getVisibility() == View.VISIBLE;
    }

    private boolean isImportOpen() {
        android.widget.FrameLayout ov = (android.widget.FrameLayout) findViewById(R.id.importOverlay);
        return ov != null && ov.getVisibility() == View.VISIBLE;
    }

    private boolean anyPanelOpen() {
        return isChannelPanelOpen() || isSettingsPanelOpen();
    }

    /* ==================== 设置面板（右侧） ==================== */

    private void openSettingsPanel() {
        if (settingsPanel == null) return;
        closeChannelPanelOnly();
        settingsPanel.setVisibility(View.VISIBLE);
        // 重置为设置列表状态
        TextView title = (TextView) findViewById(R.id.settingsTitleText);
        if (title != null) {
            // 版本号显示在顶部标题（更醒目）
            title.setText(getString(R.string.settings_title) + "  v" + getVersionName());
        }
        FrameLayout helpContainer = (FrameLayout) findViewById(R.id.helpContainer);
        if (helpContainer != null) helpContainer.setVisibility(View.GONE);
        if (settingsList != null) settingsList.setVisibility(View.VISIBLE);
        // 版本号已移到顶部标题显示，底部版本号隐藏
        if (settingsVersion != null) {
            settingsVersion.setVisibility(View.GONE);
        }
        // 显示设备信息（IP + adb 状态 + adb 地址端口）
        updateDeviceInfo();
        settingsList.setAdapter(new SettingsAdapter());
        settingsSelectedPos = 0;
        settingsList.setSelection(0);
        settingsList.requestFocus();
    }

    /**
     * 更新设置页底部的设备信息：本机 IP、adb 开启状态、adb 地址端口。
     */
    private void updateDeviceInfo() {
        if (deviceInfoBar == null) return;
        try {
            String ip = getLocalIpv4();
            String adbPort = getAdbTcpPort();
            boolean adbOn = adbPort != null && !adbPort.isEmpty() && !"0".equals(adbPort);
            // 若反射读端口失败，回退 Socket 探测 5555
            if (adbPort == null) {
                adbOn = isPortOpen(ip, 5555) || isPortOpen("127.0.0.1", 5555);
                adbPort = adbOn ? "5555" : null;
            }
            if (deviceIpText != null) {
                deviceIpText.setText("本机IP " + (ip == null || ip.isEmpty() ? "未知" : ip));
            }
            if (adbStatusText != null) {
                // adb 只显示端口（简洁）
                if (adbOn && adbPort != null && !adbPort.isEmpty()) {
                    adbStatusText.setText("adb " + adbPort);
                } else {
                    adbStatusText.setText("adb 未开启");
                }
            }
            deviceInfoBar.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "设备信息更新失败: " + t);
        }
    }

    /** 获取本机 IPv4 地址（非回环）。 */
    private String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nif = nets.nextElement();
                if (nif == null || !nif.isUp()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取 IP 失败: " + t);
        }
        return null;
    }

    /**
     * 检测 adb over Wi-Fi 是否开启并返回 TCP 端口。
     * 优先反射读 service.adb.tcp.port；失败则扫描 /proc/net/tcp（含 tcp6）找监听端口。
     */
    private String getAdbTcpPort() {
        // 1. 反射读 service.adb.tcp.port
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object port = sp.getMethod("get", String.class).invoke(null, "service.adb.tcp.port");
            if (port != null) {
                String s = String.valueOf(port).trim();
                if (!s.isEmpty() && !"0".equals(s)) return s;
            }
        } catch (Throwable t) {
            // 隐藏 API 限制，走 /proc/net/tcp
        }
        // 2. /proc/net/tcp 找 5555（常见 adb 端口）或其他常见端口监听
        int p = findListeningAdbPort();
        return p > 0 ? String.valueOf(p) : null;
    }

    /** 扫描 /proc/net/tcp、/proc/net/tcp6，返回处于 LISTEN 的 adb 端口（5555 优先）。 */
    private int findListeningAdbPort() {
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6"};
        try {
            for (String f : files) {
                java.io.BufferedReader br =
                        new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    // local_address 形如 0100007F:15B3；状态 0A = LISTEN
                    if (parts.length > 3 && "0A".equalsIgnoreCase(parts[3])) {
                        String addr = parts[1].toUpperCase();
                        int idx = addr.lastIndexOf(':');
                        if (idx >= 0) {
                            try {
                                int port = Integer.parseInt(addr.substring(idx + 1), 16);
                                if (port == 5555) { // adb 默认端口优先
                                    br.close();
                                    return port;
                                }
                            } catch (NumberFormatException e) { /* skip */ }
                        }
                    }
                }
                br.close();
            }
        } catch (Throwable t) {
            // /proc 不可读（SELinux），返回 0
        }
        return 0;
    }

    /** 兼容保留：Socket 探测 TCP 端口是否可连接。 */
    private boolean isPortOpen(String host, int port) {
        if (host == null || host.isEmpty()) return false;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 400);
            s.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    /** 确保 ListView 用标准 D-pad 导航（焦点在列表上），选中变化时刷新高亮 */
    private void enableFocusNavigation(final ListView list) {
        if (list == null) return;
        list.setFocusable(true);
        list.setFocusableInTouchMode(false);
        list.setItemsCanFocus(false);
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshAllLists();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                refreshAllLists();
            }
        });
        // 焦点变化时刷新所有列表，保证两列高亮互斥（只有聚焦列有背景）
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                refreshAllLists();
            }
        });
    }

    /** 刷新所有列表，确保高亮互斥 */
    private void refreshAllLists() {
        if (categoryList != null) categoryList.invalidateViews();
        if (channelList != null) channelList.invalidateViews();
        if (settingsList != null) settingsList.invalidateViews();
    }

    /** 手动设置列表项高亮：聚焦项=实心主题色+深字，否则透明+原色 */
    private void applyItemHighlight(View row, boolean focused, boolean selected, boolean selectedText) {
        if (row == null) return;
        int accent = ThemeManager.accent();
        int darkOnAccent = getResources().getColor(R.color.text_on_accent);
        int tagFg = getResources().getColor(R.color.text_tag_fg);
        if (focused) {
            row.setBackgroundColor(accent);
        } else {
            row.setBackgroundColor(0x00000000);
        }
        // 文字高亮
        setChildTextColor(row, R.id.channelText, focused ? darkOnAccent : (selected ? accent : getResources().getColor(R.color.text_primary)));
        setChildTextColor(row, R.id.channelNumber, focused ? darkOnAccent : tagFg);
        setChildTextColor(row, R.id.channelSources, focused ? darkOnAccent : tagFg);
        setChildTextColor(row, R.id.categoryText, focused ? darkOnAccent : (selectedText ? accent : getResources().getColor(R.color.text_secondary)));
        setChildTextColor(row, R.id.categoryCount, focused ? darkOnAccent : tagFg);
        setChildTextColor(row, R.id.settingTitle, focused ? darkOnAccent : (selected ? accent : getResources().getColor(R.color.text_primary)));
        setChildTextColor(row, R.id.settingValue, focused ? darkOnAccent : getResources().getColor(R.color.text_tertiary));
        setChildTextColor(row, R.id.savedName, focused ? darkOnAccent : (selected ? accent : getResources().getColor(R.color.text_primary)));
        setChildTextColor(row, R.id.savedUrl, focused ? darkOnAccent : getResources().getColor(R.color.text_tertiary));

        // 频道号色块/源标签：始终浅色底；聚焦时行背景已变主题色，色块保持浅色形成对比
        setChildBackgroundRes(row, R.id.channelNumber, R.drawable.bg_channel_num_light);
        setChildBackgroundRes(row, R.id.channelSources, R.drawable.bg_tag_light);
        setChildBackgroundRes(row, R.id.categoryCount, R.drawable.bg_tag_light);
    }

    private void setChildBackground(View row, int id, int color) {
        try {
            View child = row.findViewById(id);
            if (child != null) child.setBackgroundColor(color);
        } catch (Throwable ignored) {
        }
    }

    private void setChildBackgroundRes(View row, int id, int drawableRes) {
        try {
            View child = row.findViewById(id);
            if (child != null) child.setBackgroundResource(drawableRes);
        } catch (Throwable ignored) {
        }
    }

    private void setChildTextColor(View row, int id, int color) {
        try {
            View child = row.findViewById(id);
            if (child instanceof TextView) ((TextView) child).setTextColor(color);
        } catch (Throwable ignored) {
        }
    }

    private void bindSettingsClick() {
        settingsList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onSettingClick(position);
            }
        });
    }

    private void closeChannelPanelOnly() {
        if (channelPanel != null) channelPanel.setVisibility(View.GONE);
    }

    /* ==================== 遥控器按键 ==================== */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isImportOpen()) return true;
        if (isSettingsPanelOpen()) {
            return onSettingsKey(keyCode, event);
        }
        if (isChannelPanelOpen()) {
            return onChannelPanelKey(keyCode, event);
        }
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
                switchSource(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchSource(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 空状态（无频道）时按 OK 直接打开设置，方便导入频道
                if (channels.isEmpty()) {
                    openSettingsPanel();
                } else {
                    openChannelPanel();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                openSettingsPanel();
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

    /** 频道列表面板按键：左列=分类，右列=频道 */
    private boolean onChannelPanelKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                closePanels();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 焦点在左列(分类)时切到右列(频道)
                if (categoryList != null && categoryList.hasFocus()) {
                    channelList.requestFocus();
                    channelList.setSelection(0);
                    return true;
                }
                closePanels();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 焦点在右列(频道)时切到左列(分类)
                if (channelList != null && channelList.hasFocus()) {
                    categoryList.requestFocus();
                    categoryList.invalidateViews(); // 刷新分类高亮
                    return true;
                }
                closePanels();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 选中频道并播放
                if (channelList.hasFocus()) {
                    int pos = channelList.getSelectedItemPosition();
                    List<Channel> sub = channelsInCategory(currentCategory);
                    if (pos >= 0 && pos < sub.size()) {
                        int index = channels.indexOf(sub.get(pos));
                        if (index >= 0) {
                            closePanels();
                            switchTo(index, true);
                            return true;
                        }
                    }
                } else if (categoryList.hasFocus()) {
                    int pos = categoryList.getSelectedItemPosition();
                    if (pos >= 0 && pos < categories.size()) {
                        currentCategory = pos;
                        refreshChannelList();
                        channelList.requestFocus();
                        channelList.setSelection(0);
                        return true;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                closePanels();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** 设置面板按键 */
    private boolean onSettingsKey(int keyCode, KeyEvent event) {
        // 帮助面板打开时：OK 关闭帮助，返回/右/菜单 关闭整个面板
        if (isHelpOpen()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        restoreSettingsPanel();
                        return true;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MENU:
                    closeSettingsPanel();
                    return true;
                default:
                    return false;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                closeSettingsPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 光标在多选项设置上：左键切换上一个选项
                int posLeft = settingsSelectedPos;
                if (posLeft >= 0 && isMultiOption(posLeft)) {
                    cycleSettingOption(posLeft, -1);
                    return true;
                }
                closeSettingsPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 光标在多选项设置上：右键切换下一个选项
                int posRight = settingsSelectedPos;
                if (posRight >= 0 && isMultiOption(posRight)) {
                    cycleSettingOption(posRight, 1);
                    return true;
                }
                closeSettingsPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 上下键导航时取消锁定
                if (settingOptionLocked >= 0) {
                    settingOptionLocked = -1;
                    settingsList.invalidateViews();
                }
                // 手动移动选中项（Android 4.4 上 ListView 可能无焦点，return false 会导致上下键失效）
                if (settingsList != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int p = settingsList.getSelectedItemPosition();
                    if (p < 0) p = 0;
                    int count = settingsList.getAdapter() != null ? settingsList.getAdapter().getCount() : 0;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (p > 0) settingsList.setSelection(p - 1);
                    } else {
                        if (p < count - 1) settingsList.setSelection(p + 1);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                int pos = settingsSelectedPos;
                if (pos >= 0 && settingsList != null && pos < settingsList.getAdapter().getCount()) {
                    onSettingClick(pos);
                }
                return true;
            default:
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
            osdGroup.setVisibility(View.GONE);
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

    /* ==================== 换源 ==================== */

    /** 左右键换源：delta=+1 下一源，-1 上一源 */
    private void switchSource(int delta) {
        if (channels.isEmpty() || currentIndex < 0 || currentIndex >= channels.size()) return;
        Channel ch = channels.get(currentIndex);
        if (!ch.hasMultipleSources()) {
            showToast("该频道只有 1 个源");
            return;
        }
        int count = ch.sourceCount();
        int cur = livePlayer.getSourceIndex();
        int next = cur + delta;
        if (next < 0) next = count - 1;
        if (next >= count) next = 0;
        if (next == cur) {
            showToast("已经是" + (delta > 0 ? "最后一个" : "第一个") + "源（共 " + count + " 个）");
            return;
        }
        // 先跳回源 0，再前进 next 次，使播放器内部状态一致
        livePlayer.play(ch.urls, ch.name);
        for (int i = 0; i < next; i++) {
            if (!livePlayer.nextSource()) break;
        }
        showToast("源 " + (next + 1) + " / " + count);
    }

    /* ==================== 换台 ==================== */

    private void changeChannel(int delta) {
        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel), false);
            return;
        }
        int next = currentIndex < 0 ? 0 : currentIndex + delta;
        switchTo(next, true);
    }

    private void switchTo(int index, boolean showOsdBalloon) {
        if (channels.isEmpty()) {
            showState(getString(R.string.no_channel), false);
            return;
        }
        if (index < 0) index = channels.size() - 1;
        if (index >= channels.size()) index = 0;

        currentIndex = index;
        Channel channel = channels.get(index);
        prefs.edit().putInt(KEY_LAST_INDEX, index).apply();

        Log.i(TAG, "切台: " + channel.number + " " + channel.name + " 源数=" + channel.sourceCount());
        updateOsd(channel);
        if (showOsdBalloon) showOsd();
        showState("正在连接 " + channel.name + "…", true);
        livePlayer.play(channel.urls, channel.name);
    }

    private void updateOsd(Channel channel) {
        if (channel == null) return;
        osdNumber.setText(String.valueOf(channel.number));
        osdName.setText(channel.name);
        osdGroup.setTextColor(ThemeManager.accent());
        if (channel.group != null && !channel.group.isEmpty()) {
            osdGroup.setText(channel.group);
            osdGroup.setVisibility(View.VISIBLE);
        } else {
            osdGroup.setVisibility(View.GONE);
        }
        osdUrl.setText(channel.hasMultipleSources()
                ? channel.primaryUrl() + "  ·  " + channel.sourceCount() + " 个源"
                : channel.primaryUrl());
    }

    /* ==================== 播放回调 ==================== */

    @Override
    public void onStateChanged(int state, String channelName) {
        // 调试信息更新
        updateDebugInfo(state, channelName);
        if (state == LivePlayer.STATE_PLAYING) {
            hideState();
            // 顶部状态栏已移除，频道信息在左上角 OSD 卡片显示
        } else if (state == LivePlayer.STATE_CONNECTING) {
            if (statePanel.getVisibility() != View.VISIBLE) {
                showState("正在连接 " + (channelName == null ? "" : channelName) + "…", true);
            } else if (stateText != null && channelName != null) {
                stateText.setText("正在连接 " + channelName + "…");
            }
        }
    }

    @Override
    public void onSourceChanged(int index, int total) {
        Log.i(TAG, "当前源: " + (index + 1) + "/" + total);
        if (total > 1 && index > 0) {
            showToast(getString(R.string.source_switch, index + 1, total));
        }
        // 设置面板打开时刷新切换源显示
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    @Override
    public void onSourcesExhausted() {
        Log.i(TAG, "当前频道所有源失败，自动切下一个台");
        showToast("当前频道所有源均失败，自动切台");
        changeChannel(1);
    }

    @Override
    public void onError(String message) {
        showState("播放失败：" + message
                + "\n\n按上下键换台，左右键换源，菜单键设置", false);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        if (osdResolution != null) {
            osdResolution.setText(width + "×" + height);
        }
    }

    private void updateDebugInfo(int state, String channelName) {
        if (!isDebuggable()) return;
        TextView dbg = findViewById(R.id.debugInfo);
        if (dbg == null) return;
        // 播放时左上角不自动显示提示，仅当用户通过 show_debug 手动开启后才更新
        if (dbg.getVisibility() != View.VISIBLE) return;
        String stateStr;
        switch (state) {
            case LivePlayer.STATE_PLAYING: stateStr = "播放中"; break;
            case LivePlayer.STATE_CONNECTING: stateStr = "连接中"; break;
            default: stateStr = "状态=" + state; break;
        }
        String audioInfo = "";
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int mode = am.getMode();
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioInfo = " | 音量=" + vol + "/" + maxVol;
                audioInfo += " | 模式=" + mode;
            }
        } catch (Throwable ignored) {}
        dbg.setText("[" + stateStr + "] " + (channelName == null ? "" : channelName) + audioInfo);
        dbg.setVisibility(View.VISIBLE);
    }

    private boolean isDebuggable() {
        try {
            ApplicationInfo info = getApplicationInfo();
            return (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 界面元素 ==================== */

    private void showState(String text, boolean showSpinner) {
        if (stateText != null) stateText.setText(text);
        if (stateSpinner != null) {
            stateSpinner.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
        }
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

    private void showVolumeBar(int current, int max) {
        if (volumePanel == null || volumeBar == null) return;
        int percent = max > 0 ? (int) ((long) current * 100 / max) : 0;
        volumeBar.setProgress(percent);
        if (volumeText != null) volumeText.setText(percent + "%");
        volumePanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(volumeHideRunnable);
        handler.postDelayed(volumeHideRunnable, VOLUME_HIDE_DELAY);
    }

    private void adjustVolume(boolean up) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            showVolumeBar(current, max);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 设置动作 ==================== */

    private void reloadChannels() {
        List<Channel> reloaded = ChannelStore.reload(this);
        channels.clear();
        channels.addAll(reloaded);
        if (channels.isEmpty()) {
            if (livePlayer != null) livePlayer.release();
            showState(getString(R.string.no_channel), false);
            showToast("没有找到频道文件");
            return;
        }
        rebuildCategories();
        setupChannelPanel();
        showToast("已载入 " + channels.size() + " 个频道\n" + ChannelStore.lastSource(this));
        switchTo(0, true);
    }

    private void toggleBufferMode() {
        if (livePlayer == null) return;
        boolean lowLatency = !livePlayer.isLowLatency();
        livePlayer.setLowLatency(lowLatency);
        showToast(lowLatency ? "已切换到低延迟模式（换台更快）" : "已切换到流畅优先模式（更抗卡顿）");
    }

    /** 切换硬解/软解 */
    private void toggleDecodeMode() {
        if (livePlayer == null) return;
        boolean soft = livePlayer.getDecoderMode() == LivePlayer.DECODE_SW;
        int mode = soft ? LivePlayer.DECODE_HW : LivePlayer.DECODE_SW;
        livePlayer.setDecoderMode(mode);
        prefs.edit().putInt(KEY_DECODE_MODE, mode).apply();
        showToast(mode == LivePlayer.DECODE_SW ? "已切换到软解" : "已切换到硬解");
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    private void toggleAutoSource() {
        boolean enabled = !livePlayer.isAutoSource();
        livePlayer.setAutoSource(enabled);
        prefs.edit().putBoolean(KEY_AUTO_SOURCE, enabled).apply();
        showToast(enabled ? "已开启自动换源" : "已关闭自动换源");
    }

    /** 循环切换换源延迟 */
    private void cycleSourceDelay(int direction) {
        int currentSec = (int) (livePlayer.getAutoSourceDelay() / 1000L);
        int idx = 0;
        for (int i = 0; i < AUTO_SOURCE_DELAYS.length; i++) {
            if (AUTO_SOURCE_DELAYS[i] == currentSec) {
                idx = i;
                break;
            }
        }
        int nextIdx = (idx + direction + AUTO_SOURCE_DELAYS.length) % AUTO_SOURCE_DELAYS.length;
        int sec = AUTO_SOURCE_DELAYS[nextIdx];
        livePlayer.setAutoSourceDelay(sec * 1000L);
        prefs.edit().putInt(KEY_AUTO_SOURCE_DELAY, sec).apply();
        showToast("换源延迟：" + AUTO_SOURCE_DELAY_LABELS[nextIdx]);
        // 刷新设置面板当前值
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    /** 当前换源延迟的显示文案 */
    private String delayLabel() {
        int sec = (int) (livePlayer.getAutoSourceDelay() / 1000L);
        for (int i = 0; i < AUTO_SOURCE_DELAYS.length; i++) {
            if (AUTO_SOURCE_DELAYS[i] == sec) return AUTO_SOURCE_DELAY_LABELS[i];
        }
        return sec + " 秒";
    }

    /** 切换画面比例：按比例 ↔ 全屏填充 */
    private void toggleScaleMode() {
        if (livePlayer == null) return;
        boolean fill = livePlayer.getScaleMode() == LivePlayer.SCALE_FILL;
        int mode = fill ? LivePlayer.SCALE_FIT : LivePlayer.SCALE_FILL;
        livePlayer.setScaleMode(mode);
        prefs.edit().putInt(KEY_SCALE_MODE, mode).apply();
        showToast(mode == LivePlayer.SCALE_FILL ? "画面：全屏填充" : "画面：按比例");
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    /** 当前就绪超时显示文案 */
    private String readyTimeoutLabel() {
        int sec = (int) (livePlayer.getReadyTimeout() / 1000L);
        for (int i = 0; i < READY_TIMEOUTS.length; i++) {
            if (READY_TIMEOUTS[i] == sec) return READY_TIMEOUT_LABELS[i];
        }
        return sec + " 秒";
    }

    /** 循环切换就绪超时（最小 5 秒） */
    private void cycleReadyTimeout(int direction) {
        if (livePlayer == null) return;
        int currentSec = (int) (livePlayer.getReadyTimeout() / 1000L);
        int idx = 0;
        for (int i = 0; i < READY_TIMEOUTS.length; i++) {
            if (READY_TIMEOUTS[i] == currentSec) {
                idx = i;
                break;
            }
        }
        int nextIdx = (idx + direction + READY_TIMEOUTS.length) % READY_TIMEOUTS.length;
        int sec = READY_TIMEOUTS[nextIdx];
        livePlayer.setReadyTimeout(sec * 1000L);
        prefs.edit().putInt(KEY_READY_TIMEOUT, sec).apply();
        showToast("超时时间：" + READY_TIMEOUT_LABELS[nextIdx]);
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    /** 循环切换主题色 */
    private void cycleTheme() {
        String name = ThemeManager.cycle(this);
        showToast("主题色：" + name);
        // 刷新所有列表高亮
        refreshAllLists();
        // 刷新设置面板当前值
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
        // 刷新导入弹窗源列表高亮
        if (importDialog != null) importDialog.refresh();
        // 刷新所有引用 @color/accent 的 drawable 背景和文字颜色
        applyThemeDrawables();
    }

    /** 刷新所有引用 @color/accent 的 drawable 背景和文字颜色 */
    private void applyThemeDrawables() {
        // OSD / 音量 / Toast 卡片背景
        setViewBackground(R.id.osdPanel, ThemeManager.cardBg(this));
        setViewBackground(R.id.volumePanel, ThemeManager.cardBg(this));
        setViewBackground(R.id.toastText, ThemeManager.cardBg(this));
        // 状态栏 spinner 颜色（XML indeterminateTint 在 API 21+ 生效，此处无需更新）
        // 音量数字颜色
        TextView volText = (TextView) findViewById(R.id.volumeText);
        if (volText != null) volText.setTextColor(ThemeManager.accent());
        // OSD 台号颜色（跟随主题色）
        if (osdNumber != null) osdNumber.setTextColor(ThemeManager.accent());
        // 频道面板标题文字颜色
        TextView channelTitle = (TextView) findViewById(R.id.channelPanelTitle);
        if (channelTitle != null) channelTitle.setTextColor(ThemeManager.accent());
        // 分类列标题文字颜色（跟随主题）
        TextView categoryHeader = (TextView) findViewById(R.id.categoryHeader);
        if (categoryHeader != null) {
            categoryHeader.setTextColor(ThemeManager.accent());
            android.graphics.drawable.GradientDrawable chBg = new android.graphics.drawable.GradientDrawable();
            chBg.setColor(ThemeManager.accentDim());
            categoryHeader.setBackgroundDrawable(chBg);
        }
        // 频道面板标题栏背景色（跟随主题）
        View channelHeader = findViewById(R.id.channelPanelHeader);
        if (channelHeader != null) {
            android.graphics.drawable.GradientDrawable headerBg = new android.graphics.drawable.GradientDrawable();
            headerBg.setColor(ThemeManager.accentDim());
            channelHeader.setBackgroundDrawable(headerBg);
        }
        // 频道当前分类标题栏背景色（跟随主题）
        TextView categoryCurrent = (TextView) findViewById(R.id.categoryCurrent);
        if (categoryCurrent != null) {
            android.graphics.drawable.GradientDrawable curBg = new android.graphics.drawable.GradientDrawable();
            curBg.setColor(ThemeManager.accentDim());
            categoryCurrent.setBackgroundDrawable(curBg);
            categoryCurrent.setTextColor(ThemeManager.accent());
        }
        // 设置面板标题栏背景色（跟随主题）
        View settingsHeader = findViewById(R.id.settingsPanelHeader);
        if (settingsHeader != null) {
            android.graphics.drawable.GradientDrawable shBg = new android.graphics.drawable.GradientDrawable();
            shBg.setColor(ThemeManager.accentDim());
            settingsHeader.setBackgroundDrawable(shBg);
        }
        // 刷新分类列表，使高亮/文字颜色跟随主题更新
        if (categoryList != null) categoryList.invalidateViews();
        // 设置面板标题文字颜色
        TextView settingsTitle = (TextView) findViewById(R.id.settingsTitleText);
        if (settingsTitle != null) settingsTitle.setTextColor(ThemeManager.accent());
        // 设置面板标题栏圆点
        View titleText = findViewById(R.id.settingsTitleText);
        if (titleText != null && titleText.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup titleBar = (android.view.ViewGroup) titleText.getParent();
            if (titleBar.getChildCount() > 0) {
                View dot = titleBar.getChildAt(0);
                if (dot != null) dot.setBackgroundDrawable(ThemeManager.accentDot(this));
            }
        }
        // 帮助面板（如果打开）
        FrameLayout helpContainer = (FrameLayout) findViewById(R.id.helpContainer);
        if (helpContainer != null && helpContainer.getVisibility() == View.VISIBLE
                && helpContainer.getChildCount() > 0) {
            View help = helpContainer.getChildAt(0);
            // 帮助标题文字颜色
            updateHelpThemeColors(help);
            // 关闭按钮
            Button closeBtn = (Button) help.findViewById(R.id.helpClose);
            if (closeBtn != null) {
                closeBtn.setTextColor(ThemeManager.accent());
                closeBtn.setBackgroundDrawable(ThemeManager.buttonBg(this));
            }
        }
        // 导入弹窗（如果打开）
        if (importDialog != null) importDialog.applyThemeDrawables();
    }

    /** 更新帮助面板的主题色文字 */
    private void updateHelpThemeColors(View help) {
        int accent = ThemeManager.accent();
        float targetSize = 13f * getResources().getDisplayMetrics().scaledDensity;
        updateTextViewsRecursive(help, accent, targetSize);
    }

    private void updateTextViewsRecursive(View view, int accent, float targetSize) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (tv.getId() == View.NO_ID && Math.abs(tv.getTextSize() - targetSize) < 2f) {
                tv.setTextColor(accent);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateTextViewsRecursive(vg.getChildAt(i), accent, targetSize);
            }
        }
    }

    /** 安全设置 View 背景 */
    private void setViewBackground(int id, android.graphics.drawable.Drawable drawable) {
        try {
            View v = findViewById(id);
            if (v != null) v.setBackgroundDrawable(drawable);
        } catch (Throwable ignored) {
        }
    }

    /** 切换换源记忆开关 */
    private void toggleSourceMemory() {
        boolean enabled = !prefs.getBoolean(KEY_SOURCE_MEMORY, true);
        prefs.edit().putBoolean(KEY_SOURCE_MEMORY, enabled).apply();
        livePlayer.setSourceMemory(enabled);
        showToast(enabled ? "已开启换源记忆" : "已关闭换源记忆");
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    /* ==================== 底部按键提示开关 ==================== */

    private boolean isKeyHintVisible() {
        return prefs.getBoolean(KEY_KEY_HINT, true);
    }

    private void toggleKeyHint() {
        boolean visible = !isKeyHintVisible();
        prefs.edit().putBoolean(KEY_KEY_HINT, visible).apply();
        View keyHint = findViewById(R.id.keyHint);
        if (keyHint != null) keyHint.setVisibility(visible ? View.VISIBLE : View.GONE);
        showToast(visible ? "已显示按键提示" : "已隐藏按键提示");
        // 刷新设置面板当前值
        if (isSettingsPanelOpen()) settingsList.invalidateViews();
    }

    private void showHelp() {
        if (settingsPanel == null) return;
        closeChannelPanelOnly();

        // 标题改为「帮助与说明」
        TextView title = (TextView) findViewById(R.id.settingsTitleText);
        if (title != null) title.setText(R.string.help_title);

        // 隐藏设置列表，显示帮助内容
        if (settingsList != null) settingsList.setVisibility(View.GONE);
        if (settingsVersion != null) settingsVersion.setVisibility(View.GONE);
        FrameLayout helpContainer = (FrameLayout) findViewById(R.id.helpContainer);
        if (helpContainer == null) return;
        helpContainer.removeAllViews();
        View help = LayoutInflater.from(this).inflate(R.layout.help_content, helpContainer, false);

        // 填充帮助正文
        setHelpText(help, R.id.helpRemote, "· 上/下、CH+/-：上/下一个台\n"
                + "· 数字键：输入台号直接跳台\n"
                + "· 左/右：切换当前频道播放源\n"
                + "· OK：频道列表（左分类右频道）\n"
                + "· 菜单键：设置\n"
                + "· 返回键：连按两次退出");
        setHelpText(help, R.id.helpSource, "· 同名频道自动合并多个源\n"
                + "· 设置 → 自动换源：播放失败自动切下一源\n"
                + "· 设置 → 切换源：手动强制换源");
        setHelpText(help, R.id.helpAdd, "把 tvlive.m3u 放到内置存储根目录或 Download 目录，然后菜单 → 重新载入频道。"
                + "也可用「频道导入」扫码在电脑/手机后台添加。");
        setHelpText(help, R.id.helpM3u, "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n"
                + "http://example.com/cctv1.m3u8\n\n"
                + "支持 http/https 的 HLS(m3u8)、RTSP 摄像头流、mp4 等。");
        setHelpText(help, R.id.helpSourceInfo, "当前频道来源：" + ChannelStore.lastSource(this));

        helpContainer.addView(help);
        helpContainer.setVisibility(View.VISIBLE);

        // 刷新主题色 drawable
        applyThemeDrawables();

        // 关闭按钮
        Button close = (Button) help.findViewById(R.id.helpClose);
        if (close != null) {
            close.setFocusable(true);
            close.setTextColor(ThemeManager.accent());
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    restoreSettingsPanel();
                }
            });
        }

        settingsPanel.setVisibility(View.VISIBLE);
        // 默认焦点给关闭按钮（有高亮），OK/回车关闭
        if (close != null) {
            close.requestFocus();
            close.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        restoreSettingsPanel();
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    /** 恢复设置面板为「设置」列表状态 */
    private void restoreSettingsPanel() {
        TextView title = (TextView) findViewById(R.id.settingsTitleText);
        if (title != null) title.setText(R.string.settings_title);
        FrameLayout helpContainer = (FrameLayout) findViewById(R.id.helpContainer);
        if (helpContainer != null) helpContainer.setVisibility(View.GONE);
        if (settingsList != null) settingsList.setVisibility(View.VISIBLE);
        // 回到设置列表
        openSettingsPanel();
    }

    private void setHelpText(View root, int id, String text) {
        try {
            TextView tv = (TextView) root.findViewById(id);
            if (tv != null) tv.setText(text);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 分类适配器（左列） ==================== */

    private class CategoryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return categories.size();
        }

        @Override
        public Object getItem(int position) {
            return categories.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_category, parent, false);
            TextView name = (TextView) view.findViewById(R.id.categoryText);
            TextView count = (TextView) view.findViewById(R.id.categoryCount);
            String cat = categories.get(position);
            name.setText(cat);
            count.setText(String.valueOf(channelsInCategory(position).size()));

            boolean focused = categoryList.hasFocus() && position == categoryList.getSelectedItemPosition();
            boolean isCurrent = position == currentCategory;
            applyItemHighlight(view, focused, false, isCurrent);
            return view;
        }
    }

    /* ==================== 频道适配器（右列） ==================== */

    private class ChannelAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return channelsInCategory(currentCategory).size();
        }

        @Override
        public Object getItem(int position) {
            return channelsInCategory(currentCategory).get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_channel, parent, false);
            TextView number = (TextView) view.findViewById(R.id.channelNumber);
            TextView name = (TextView) view.findViewById(R.id.channelText);
            TextView sources = (TextView) view.findViewById(R.id.channelSources);

            Channel ch = channelsInCategory(currentCategory).get(position);
            number.setText(String.valueOf(ch.number));
            name.setText(ch.name);
            if (ch.hasMultipleSources()) {
                sources.setText(ch.sourceCount() + "源");
                sources.setVisibility(View.VISIBLE);
            } else {
                sources.setVisibility(View.GONE);
            }

            boolean focused = channelList.hasFocus() && position == channelList.getSelectedItemPosition();
            boolean playing = channels.indexOf(ch) == currentIndex;
            applyItemHighlight(view, focused, playing, false);
            return view;
        }
    }

    /* ==================== 设置适配器（右侧） ==================== */

    private class SettingsAdapter extends BaseAdapter {

        private final String[] titles = new String[]{
                getString(R.string.settings_switch_source),
                getString(R.string.settings_auto_source),
                getString(R.string.settings_source_delay),
                getString(R.string.settings_scale),
                getString(R.string.settings_timeout),
                getString(R.string.settings_import),
                getString(R.string.settings_reload),
                getString(R.string.settings_decode),
                getString(R.string.settings_buffer),
                getString(R.string.settings_key_hint),
                getString(R.string.settings_theme),
                getString(R.string.settings_source_memory),
                getString(R.string.settings_help),
                getString(R.string.settings_check_update)
        };

        @Override
        public int getCount() {
            return titles.length;
        }

        @Override
        public Object getItem(int position) {
            return titles[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_setting, parent, false);
            TextView title = (TextView) view.findViewById(R.id.settingTitle);
            TextView value = (TextView) view.findViewById(R.id.settingValue);
            TextView arrowLeft = (TextView) view.findViewById(R.id.settingArrowLeft);
            TextView arrowRight = (TextView) view.findViewById(R.id.settingArrowRight);
            title.setText(titles[position]);

            String v = null;
            if (position == AUTO_SOURCE) {
                v = livePlayer.isAutoSource() ? "开" : "关";
            } else if (position == SOURCE_DELAY) {
                v = delayLabel();
            } else if (position == SCALE) {
                v = livePlayer.getScaleMode() == LivePlayer.SCALE_FILL ? "全屏填充" : "按比例";
            } else if (position == TIMEOUT) {
                v = readyTimeoutLabel();
            } else if (position == SWITCH_SOURCE) {
                v = "源 " + (livePlayer.getSourceIndex() + 1) + "/"
                        + Math.max(1, livePlayer.getSourceCount());
            } else if (position == BUFFER) {
                v = livePlayer.isLowLatency() ? "低延迟" : "流畅优先";
            } else if (position == DECODE) {
                v = livePlayer.getDecoderMode() == LivePlayer.DECODE_SW ? "软解" : "硬解";
            } else if (position == KEY_HINT) {
                v = isKeyHintVisible() ? "开" : "关";
            } else if (position == THEME) {
                v = ThemeManager.THEME_NAMES[ThemeManager.currentIndex()];
            } else if (position == SOURCE_MEMORY) {
                v = prefs.getBoolean(KEY_SOURCE_MEMORY, true) ? "开" : "关";
            }
            if (v != null) {
                value.setText(v);
                value.setVisibility(View.VISIBLE);
            } else {
                value.setVisibility(View.GONE);
            }
            // 多选项设置显示左右箭头，点击切换选项
            boolean multi = isMultiOption(position);
            if (arrowLeft != null) {
                arrowLeft.setVisibility(multi ? View.VISIBLE : View.GONE);
                arrowLeft.setTag(position);
                arrowLeft.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v2) {
                        int pos = (Integer) v2.getTag();
                        cycleSettingOption(pos, -1);
                    }
                });
            }
            if (arrowRight != null) {
                arrowRight.setVisibility(multi ? View.VISIBLE : View.GONE);
                arrowRight.setTag(position);
                arrowRight.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v2) {
                        int pos = (Integer) v2.getTag();
                        cycleSettingOption(pos, 1);
                    }
                });
            }
            // 选中高亮：用 settingsSelectedPos 判断（不依赖 ListView 焦点，Android 4.4 兼容）
            boolean focused = position == settingsSelectedPos;
            // 多选项设置被选中时：箭头用深色（配合主题色背景）；未选中时用灰色
            boolean arrowHighlighted = focused && isMultiOption(position);
            int arrowColor = arrowHighlighted ? getResources().getColor(R.color.text_on_accent)
                    : getResources().getColor(R.color.text_tertiary);
            if (arrowLeft != null) {
                arrowLeft.setTextColor(arrowColor);
            }
            if (arrowRight != null) {
                arrowRight.setTextColor(arrowColor);
            }
            applyItemHighlight(view, focused, false, false);
            return view;
        }
    }

    /** 该设置项是否有多个可循环选项 */
    private boolean isMultiOption(int position) {
        return position == SWITCH_SOURCE || position == AUTO_SOURCE
                || position == SOURCE_DELAY || position == SCALE
                || position == TIMEOUT || position == DECODE
                || position == BUFFER || position == KEY_HINT
                || position == THEME || position == SOURCE_MEMORY;
    }

    /** 循环切换多选项设置的值：direction=-1 上一个，direction=1 下一个 */
    private void cycleSettingOption(int position, int direction) {
        settingOptionLocked = position;  // 锁定当前设置
        switch (position) {
            case SWITCH_SOURCE:
                if (channels.isEmpty() || currentIndex < 0) {
                    showToast("当前没有频道");
                    return;
                }
                Channel ch = channels.get(currentIndex);
                if (!ch.hasMultipleSources()) {
                    showToast("该频道只有 1 个源");
                    return;
                }
                if (direction < 0) livePlayer.prevSource();
                else livePlayer.nextSource();
                break;
            case AUTO_SOURCE:
                livePlayer.setAutoSource(!livePlayer.isAutoSource());
                prefs.edit().putBoolean(KEY_AUTO_SOURCE, livePlayer.isAutoSource()).apply();
                showToast("自动换源：" + (livePlayer.isAutoSource() ? "开" : "关"));
                break;
            case SOURCE_DELAY:
                cycleSourceDelay(direction);
                break;
            case SCALE:
                livePlayer.setScaleMode(livePlayer.getScaleMode() == LivePlayer.SCALE_FILL
                        ? LivePlayer.SCALE_FIT : LivePlayer.SCALE_FILL);
                prefs.edit().putInt(KEY_SCALE_MODE, livePlayer.getScaleMode()).apply();
                showToast("画面比例：" + (livePlayer.getScaleMode() == LivePlayer.SCALE_FILL ? "全屏填充" : "按比例"));
                break;
            case TIMEOUT:
                cycleReadyTimeout(direction);
                break;
            case DECODE:
                livePlayer.setDecoderMode(livePlayer.getDecoderMode() == LivePlayer.DECODE_SW
                        ? LivePlayer.DECODE_HW : LivePlayer.DECODE_SW);
                prefs.edit().putInt(KEY_DECODE_MODE, livePlayer.getDecoderMode()).apply();
                showToast("解码方式：" + (livePlayer.getDecoderMode() == LivePlayer.DECODE_SW ? "软解" : "硬解"));
                break;
            case BUFFER:
                livePlayer.setLowLatency(!livePlayer.isLowLatency());
                showToast("缓冲策略：" + (livePlayer.isLowLatency() ? "低延迟" : "流畅优先"));
                break;
            case KEY_HINT:
                toggleKeyHint();
                break;
            case THEME:
                ThemeManager.set(this, (ThemeManager.currentIndex() + direction + ThemeManager.THEMES.length)
                        % ThemeManager.THEMES.length);
                refreshAllLists();
                applyThemeDrawables();
                if (importDialog != null) importDialog.refresh();
                showToast("主题色：" + ThemeManager.THEME_NAMES[ThemeManager.currentIndex()]);
                break;
            case SOURCE_MEMORY:
                boolean sm = !prefs.getBoolean(KEY_SOURCE_MEMORY, true);
                prefs.edit().putBoolean(KEY_SOURCE_MEMORY, sm).apply();
                livePlayer.setSourceMemory(sm);
                showToast("换源记忆：" + (sm ? "开" : "关"));
                break;
        }
        settingsList.invalidateViews();  // 刷新列表（保留选中位置）
    }

    /* ==================== 设置点击处理 ==================== */

    public void onSettingClick(int position) {
        // 多选项设置：OK = 锁定 + 切换下一个选项，不关闭面板
        if (isMultiOption(position)) {
            cycleSettingOption(position, 1);
            return;  // 不关闭面板，方便用左右键继续切换
        }
        switch (position) {
            case 0: // 切换源
                if (channels.isEmpty() || currentIndex < 0) {
                    showToast("当前没有频道");
                    return;
                }
                Channel ch = channels.get(currentIndex);
                if (!ch.hasMultipleSources()) {
                    showToast("该频道只有 1 个源");
                    return;
                }
                livePlayer.nextSource();
                break;
            case 5: // 频道导入
                android.util.Log.d("MainActivity", "onSettingClick: case 5 IMPORT");
                closeSettingsPanel();
                openImportDialog();
                return; // 已关闭面板，不重复关闭
            case 6: // 重新载入
                reloadChannels();
                break;
            case 12: // 帮助
                showHelp();
                return; // 帮助面板不关闭
            case 13: // 检查更新
                checkForUpdate();
                return; // 检查更新不关闭面板
        }
        closeSettingsPanel();
    }

    /* ==================== 检查更新 ==================== */

    /** 获取当前 App 版本名 */
    private String getVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0.0";
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    /** 检查 GitHub Releases 是否有新版本 */
    private void checkForUpdate() {
        showToast(getString(R.string.update_checking));
        android.util.Log.i("UpdateCheck", "开始检查更新，当前版本: " + getVersionName());
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection)
                            new java.net.URL("https://api.github.com/repos/mf9593/subo-tv/releases/latest")
                            .openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "SuboTV/" + getVersionName());
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    int code = conn.getResponseCode();
                    android.util.Log.i("UpdateCheck", "GitHub API 响应码: " + code);
                    if (code == 200) {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l);
                        r.close();
                        String latest = parseTagName(sb.toString());
                        android.util.Log.i("UpdateCheck", "解析到最新版本: " + latest);
                        if (latest != null) {
                            latest = latest.startsWith("v") ? latest.substring(1) : latest;
                            final String latestVer = latest;
                            final String currentVer = getVersionName();
                            // 简单版本比较：字符串不同时认为有更新
                            if (!latestVer.equals(currentVer)) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        showUpdateDialog(latestVer, currentVer);
                                    }
                                });
                            } else {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        showToast(getString(R.string.update_latest));
                                    }
                                });
                            }
                        } else {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showToast(getString(R.string.update_latest));
                                }
                            });
                        }
                    } else {
                        android.util.Log.w("UpdateCheck", "API 非 200: " + code);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                showToast(getString(R.string.update_failed));
                            }
                        });
                    }
                } catch (Throwable t) {
                    android.util.Log.w("UpdateCheck", "检查更新异常: " + t);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showToast(getString(R.string.update_failed));
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** 从 GitHub Release JSON 中提取 tag_name */
    private String parseTagName(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"tag_name\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** 显示更新弹窗 */
    private void showUpdateDialog(String latestVer, String currentVer) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_check_update)
                .setMessage(getString(R.string.update_available, latestVer, currentVer))
                .setPositiveButton(R.string.update_confirm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/mf9593/subo-tv/releases"));
                            startActivity(browserIntent);
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }
}
