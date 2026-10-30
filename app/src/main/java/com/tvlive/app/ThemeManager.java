package com.tvlive.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.TypedValue;

/**
 * 主题色管理器：支持多套强调色切换。
 *
 * <p>存储于 SharedPreferences，切换后需刷新可见列表（invalidateViews）
 * 以及重建引用 @color/accent 的 drawable 背景。</p>
 */
public final class ThemeManager {

    private static final String PREFS = "tvlive";
    private static final String KEY_THEME = "theme_accent";

    /** 预设主题：每项为 {accent, accentDim, accentDark} */
    public static final int[][] THEMES = {
            {0xFF00DAC5, 0x4D00DAC5, 0xFF00A89A}, // 青绿（默认）
            {0xFFFF6B6B, 0x4DFF6B6B, 0xFFE84D4D}, // 珊瑚红
            {0xFFFFD93D, 0x4DFFD93D, 0xFFE8B83D}, // 琥珀金
            {0xFF6BCBFF, 0x4D6BCBFF, 0xFF4DA8E8}, // 天青蓝
            {0xFFB388FF, 0x4DB388FF, 0xFF9161E8}, // 幻紫
            {0xFF6BFF9E, 0x4D6BFF9E, 0xFF4DE87D}, // 翠绿
            {0xFFFF8A65, 0x4DFF8A65, 0xFFE86A4D}, // 暖橙
    };

    public static final String[] THEME_NAMES = {
            "青绿", "珊瑚红", "琥珀金", "天青蓝", "幻紫", "翠绿", "暖橙",
    };

    private static int accent = 0xFF00DAC5;
    private static int accentDim = 0x4D00DAC5;
    private static int accentDark = 0xFF00A89A;

    private ThemeManager() {
    }

    /** App 启动时初始化 */
    public static void init(Context context) {
        int idx = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, 0);
        apply(idx);
    }

    public static void apply(int idx) {
        if (idx < 0 || idx >= THEMES.length) idx = 0;
        accent = THEMES[idx][0];
        accentDim = THEMES[idx][1];
        accentDark = THEMES[idx][2];
    }

    public static int accent() {
        return accent;
    }

    public static int accentDim() {
        return accentDim;
    }

    public static int accentDark() {
        return accentDark;
    }

    public static int currentIndex() {
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i][0] == accent) return i;
        }
        return 0;
    }

    /** 切换主题并持久化 */
    public static void set(Context context, int idx) {
        apply(idx);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, idx).apply();
    }

    /** 循环切换到下一个主题，返回新主题名 */
    public static String cycle(Context context) {
        int next = (currentIndex() + 1) % THEMES.length;
        set(context, next);
        return THEME_NAMES[next];
    }

    /* ==================== 程序化 Drawable 构建 ==================== */

    /** dp 转 px */
    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** 按钮/输入框背景（可聚焦）：聚焦=主题色边框+淡色底 */
    public static GradientDrawable focusableBg(Context context, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 10));
        if (focused) {
            bg.setColor(accentDim);
            bg.setStroke((int) dp(context, 2), accent);
        } else {
            bg.setColor(0xFF1B2431);
            bg.setStroke((int) dp(context, 1), 0xFF232C3A);
        }
        return bg;
    }

    /** OSD/卡片背景：深色 + 左侧主题色条 */
    public static LayerDrawable cardBg(Context context) {
        GradientDrawable accentBar = new GradientDrawable();
        accentBar.setCornerRadius(dp(context, 14));
        accentBar.setColor(accent);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(context, 14));
        panelBg.setColor(0xE6100F14);
        panelBg.setStroke((int) dp(context, 1), 0xFF232C3A);

        LayerDrawable layer = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                accentBar, panelBg
        });
        layer.setLayerInset(1, (int) dp(context, 5), 0, 0, 0);
        return layer;
    }

    /** 主按钮背景（实心主题色） */
    public static GradientDrawable solidButtonBgDrawable(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 10));
        bg.setColor(accent);
        return bg;
    }

    /** 次级按钮/输入框背景（StateListDrawable）：聚焦=主题色边框+淡色底 */
    public static android.graphics.drawable.StateListDrawable buttonBg(Context context) {
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = focusableBg(context, true);
        GradientDrawable def = focusableBg(context, false);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, focused);
        bg.addState(new int[]{}, def);
        return bg;
    }

    /** 主按钮背景（StateListDrawable）：聚焦=主题色+白边 */
    public static android.graphics.drawable.StateListDrawable solidButtonBg(Context context) {
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(context, 10));
        focused.setColor(accent);
        focused.setStroke((int) dp(context, 2), 0xFFFFFFFF);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(context, 10));
        pressed.setColor(accentDark);
        GradientDrawable def = solidButtonBgDrawable(context);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, def);
        return bg;
    }

    /** 主题色圆点 */
    public static ShapeDrawable accentDot(Context context) {
        ShapeDrawable dot = new ShapeDrawable(new OvalShape());
        dot.getPaint().setColor(accent);
        return dot;
    }

    /** 频道号色块背景 */
    public static GradientDrawable channelNumBg(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 10));
        bg.setColor(0xFF1B2431);
        bg.setStroke((int) dp(context, 1.5f), accentDim);
        return bg;
    }
}
