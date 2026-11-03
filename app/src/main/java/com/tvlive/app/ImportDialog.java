package com.tvlive.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tvlive.app.data.Channel;
import com.tvlive.app.data.M3uParser;
import com.tvlive.app.data.SourceStore;
import com.tvlive.app.util.QrUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道导入弹窗：
 * 二维码(Web 后台) + 网络地址输入 + 检验/获取/保存 + 已存源列表(单选激活/删除)。
 */
public class ImportDialog {

    public interface Listener {
        /** 源列表发生变化（新增/切换/删除）后调用 */
        void onSourcesChanged();

        /** 导入弹窗关闭后调用 */
        void onImportClosed();
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<SourceStore.Source> sources = new ArrayList<SourceStore.Source>();

    private android.widget.FrameLayout overlay;
    private View rootView;
    private EditText input;
    private TextView result;
    private ListView sourceList;
    private SavedAdapter adapter;
    private String fetchedUrl = null;
    private String fetchedText = null;
    private long okDownTime = 0L;

    public ImportDialog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** 初始化叠加层容器（替代 Dialog） */
    public void init(android.widget.FrameLayout overlay) {
        this.overlay = overlay;
    }

    public void show(String webUrl) {
        if (overlay == null) { android.util.Log.e("ImportDialog", "show: overlay is null, abort"); return; }
        android.util.Log.d("ImportDialog", "show: called, webUrl=" + webUrl);
        adapter = null;
        rootView = LayoutInflater.from(context).inflate(R.layout.dialog_import, overlay, false);
        View view = rootView;
        input = (EditText) view.findViewById(R.id.importInput);
        result = (TextView) view.findViewById(R.id.importResult);
        sourceList = (ListView) view.findViewById(R.id.importSourceList);
        // 二维码 + Web 地址（后台线程生成，避免卡顿）
        final ImageView qr = (ImageView) view.findViewById(R.id.importQr);
        final TextView urlText = (TextView) view.findViewById(R.id.importUrl);
        final String finalUrl = webUrl;
        if (webUrl != null && !webUrl.isEmpty()) {
            urlText.setText(webUrl);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bmp = QrUtils.encode(finalUrl, 320);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (bmp != null && qr != null) qr.setImageBitmap(bmp);
                        }
                    });
                }
            }).start();
        }

        Button check = (Button) view.findViewById(R.id.importCheck);
        Button fetch = (Button) view.findViewById(R.id.importFetch);
        Button save = (Button) view.findViewById(R.id.importSave);

        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUrl();
            }
        });
        fetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchUrl();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFetched();
            }
        });

        Button clean = (Button) view.findViewById(R.id.importClean);
        if (clean != null) {
            clean.setFocusable(true);
            clean.setBackgroundDrawable(ThemeManager.buttonBg(context));
            clean.setTextColor(ThemeManager.accent());
            clean.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cleanInvalidSources();
                }
            });
        }

        // 添加到叠加层并显示
        overlay.removeAllViews();
        overlay.addView(rootView);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(false);
        overlay.setClickable(true);
        overlay.setVisibility(View.VISIBLE);
        android.util.Log.d("ImportDialog", "show: overlay set VISIBLE");

        // 先让 overlay 拿焦点，再交给子视图（高版本 Android 需要先父后子）
        overlay.requestFocus();

        // 默认聚焦输入框（延迟确保叠加层已显示 + 已布局）
        final View treeView = rootView;
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    boolean focused = false;
                    @Override
                    public void onGlobalLayout() {
                        if (focused) return;
                        if (treeView.getWidth() > 0 && treeView.getHeight() > 0) {
                            focused = true;
                            treeView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            // 先尝试 overlay.post，再用 requestFocus
                            overlay.post(new Runnable() {
                                @Override
                                public void run() {
                                    input.requestFocus();
                                    android.util.Log.d("ImportDialog", "show: input.requestFocus done");
                                }
                            });
                        }
                    }
                });

        // 自定义关闭按钮
        Button closeBtn = (Button) view.findViewById(R.id.importClose);
        if (closeBtn != null) {
            closeBtn.setFocusable(true);
            closeBtn.setBackgroundDrawable(ThemeManager.buttonBg(context));
            closeBtn.setTextColor(ThemeManager.accent());
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }

        // 源列表 D-pad 导航
        sourceList.setFocusable(true);
        sourceList.setFocusableInTouchMode(false);
        sourceList.setItemsCanFocus(false);
        sourceList.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sourceList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                sourceList.invalidateViews();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                sourceList.invalidateViews();
            }
        });
        sourceList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    if (sourceList.getSelectedItemPosition() < 0 && !sources.isEmpty()) {
                        sourceList.setSelection(0);
                    }
                }
                sourceList.invalidateViews();
            }
        });

        // 按钮/输入框可聚焦
        check.setFocusable(true);
        fetch.setFocusable(true);
        save.setFocusable(true);
        input.setFocusable(true);
        int accent = ThemeManager.accent();
        TextView title = (TextView) view.findViewById(R.id.importTitle);
        if (title != null) title.setTextColor(accent);
        check.setTextColor(accent);
        fetch.setTextColor(accent);
        save.setTextColor(accent);
        check.setBackgroundDrawable(ThemeManager.buttonBg(context));
        fetch.setBackgroundDrawable(ThemeManager.buttonBg(context));
        save.setBackgroundDrawable(ThemeManager.buttonBg(context));
        input.setBackgroundDrawable(ThemeManager.buttonBg(context));

        LinearLayout qrCard = (LinearLayout) view.findViewById(R.id.importQr).getParent();
        if (qrCard != null) qrCard.setBackgroundDrawable(ThemeManager.cardBg(context));
        if (qrCard != null) {
            for (int i = 0; i < qrCard.getChildCount(); i++) {
                View child = qrCard.getChildAt(i);
                if (child instanceof TextView) {
                    String hint = context.getString(R.string.import_qr_hint);
                    String text = ((TextView) child).getText().toString();
                    if (text.equals(hint)) {
                        ((TextView) child).setTextColor(accent);
                    }
                }
            }
        }
        result.setTextColor(accent);

        final Button close = (Button) rootView.findViewById(R.id.importClose);
        if (close != null) {
            close.setBackgroundDrawable(ThemeManager.buttonBg(context));
            close.setTextColor(ThemeManager.accent());
            close.setFocusable(true);
        }

        // 默认聚焦输入框（延迟确保叠加层已显示）
        rootView.post(new Runnable() {
            @Override
            public void run() {
                input.requestFocus();
            }
        });

        refreshSources();
    }

    public void dismiss() {
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }
        if (listener != null) listener.onImportClosed();
    }

    public boolean isShowing() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    /** 外部（如 Web 后台添加源后）刷新源列表 */
    public void refresh() {
        if (!isShowing()) return;
        refreshSources();
    }

    /** 按键处理：由 MainActivity.dispatchKeyEvent 调用 */
    public boolean onKey(int keyCode, android.view.KeyEvent event) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (isOnSourceList()) return false;
                moveFocusDown();
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (isOnSourceList()) return false;
                moveFocusUp();
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (sourceList != null && sourceList.hasFocus()) {
                    int pos = sourceList.getSelectedItemPosition();
                    if (pos < 0 || pos >= sources.size()) return true;
                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        if (event.getRepeatCount() > 0) {
                            confirmDelete(sources.get(pos));
                            return true;
                        }
                        okDownTime = SystemClock.uptimeMillis();
                        return true;
                    } else if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                        if (SystemClock.uptimeMillis() - okDownTime < 500) {
                            SourceStore.Source s = sources.get(pos);
                            SourceStore.setActive(context, s.id);
                            refreshSources();
                            if (listener != null) listener.onSourcesChanged();
                            setResult("已切换为：" + s.name);
                        }
                        return true;
                    }
                }
                View cur = rootView.findFocus();
                if (cur != null && cur.getId() == R.id.importClose) {
                    dismiss();
                    return true;
                }
                if (cur instanceof Button) {
                    ((Button) cur).callOnClick();
                    return true;
                }
                return false;
            case android.view.KeyEvent.KEYCODE_MENU:
                if (isOnSourceList()) {
                    int pos = sourceList.getSelectedItemPosition();
                    if (pos >= 0 && pos < sources.size()) {
                        confirmDelete(sources.get(pos));
                        return true;
                    }
                }
                return false;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (!isOnSourceList()) {
                    focusSourceList();
                    return true;
                }
                return false;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (isOnSourceList()) {
                    View clean = rootView.findViewById(R.id.importClean);
                    if (clean != null) clean.requestFocus();
                    return true;
                }
                return false;
            case android.view.KeyEvent.KEYCODE_BACK:
                dismiss();
                return true;
            default:
                return false;
        }
    }

    /** 当前焦点是否在源列表上 */
    private boolean isOnSourceList() {
        View cur = rootView != null ? rootView.findFocus() : null;
        return cur == sourceList || (cur != null && cur.getId() == R.id.importSourceList);
    }

    private void moveFocusDown() {
        View current = rootView != null ? rootView.findFocus() : null;
        if (current == null) {
            input.requestFocus();
            return;
        }
        if (current.getId() == R.id.importInput) {
            View check = rootView.findViewById(R.id.importCheck);
            if (check != null) check.requestFocus();
        } else if (current.getId() == R.id.importCheck) {
            View fetch = rootView.findViewById(R.id.importFetch);
            if (fetch != null) fetch.requestFocus();
        } else if (current.getId() == R.id.importFetch) {
            View save = rootView.findViewById(R.id.importSave);
            if (save != null) save.requestFocus();
        } else if (current.getId() == R.id.importSave) {
            View clean = rootView.findViewById(R.id.importClean);
            if (clean != null) clean.requestFocus();
        } else if (current.getId() == R.id.importClean) {
            focusSourceList();
        } else if (current == sourceList) {
            View close = rootView.findViewById(R.id.importClose);
            if (close != null) close.requestFocus();
        }
    }

    /** 请求焦点给源列表（延迟确保可获焦，并选中第一项） */
    private void focusSourceList() {
        sourceList.post(new Runnable() {
            @Override
            public void run() {
                boolean ok = sourceList.requestFocus();
                if (!sources.isEmpty() && sourceList.getSelectedItemPosition() < 0) {
                    sourceList.setSelection(0);
                }
                sourceList.invalidateViews();
            }
        });
    }

    private void moveFocusUp() {
        View current = rootView != null ? rootView.findFocus() : null;
        if (current == null) {
            input.requestFocus();
            return;
        }
        if (current == sourceList) {
            View clean = rootView.findViewById(R.id.importClean);
            if (clean != null) clean.requestFocus();
        } else if (current.getId() == R.id.importClose) {
            focusSourceList();
            focusSourceList();
        } else if (current.getId() == R.id.importClean) {
            View save = rootView.findViewById(R.id.importSave);
            if (save != null) save.requestFocus();
        } else if (current.getId() == R.id.importSave) {
            View fetch = rootView.findViewById(R.id.importFetch);
            if (fetch != null) fetch.requestFocus();
        } else if (current.getId() == R.id.importFetch) {
            View check = rootView.findViewById(R.id.importCheck);
            if (check != null) check.requestFocus();
        } else {
            input.requestFocus();
        }
    }

    /* ==================== 检验 / 获取 / 保存 ==================== */

    private String currentUrl() {
        String url = input.getText().toString().trim();
        if (url.isEmpty() && fetchedUrl != null) url = fetchedUrl;
        return url;
    }

    private void checkUrl() {
        final String url = currentUrl();
        if (url.isEmpty()) {
            setResult(context.getString(R.string.import_fail, "请先输入地址"));
            return;
        }
        hideKeyboard();
        setResult(context.getString(R.string.import_checking));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int code = SourceStore.check(url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (code >= 200 && code < 300) {
                            setResult(context.getString(R.string.import_ok, code));
                        } else {
                            setResult(context.getString(R.string.import_fail, code < 0 ? "无法连接" : "HTTP " + code));
                        }
                    }
                });
            }
        }, "tv-check").start();
    }

    private void fetchUrl() {
        final String url = currentUrl();
        if (url.isEmpty()) {
            setResult(context.getString(R.string.import_fail, "请先输入地址"));
            return;
        }
        hideKeyboard();
        setResult(context.getString(R.string.import_fetching));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = SourceStore.fetch(context, url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            setResult(context.getString(R.string.import_fetch_fail));
                            return;
                        }
                        int count = M3uParser.parse(text).size();
                        fetchedUrl = url;
                        fetchedText = text;
                        setResult(context.getString(R.string.import_fetch_ok, count));
                    }
                });
            }
        }, "tv-fetch").start();
    }

    private void saveFetched() {
        if (fetchedText == null || fetchedText.trim().isEmpty()) {
            setResult(context.getString(R.string.import_fetch_fail));
            return;
        }
        String url = fetchedUrl;
        String name = nameFromUrl(url);
        SourceStore.Source s = SourceStore.add(context, name, url, fetchedText);
        if (s != null) {
            setResult(context.getString(R.string.import_saved_ok));
            fetchedUrl = null;
            fetchedText = null;
            refreshSources();
            if (listener != null) listener.onSourcesChanged();
        } else {
            setResult(context.getString(R.string.import_saved_fail));
        }
    }

    private String nameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "网络源";
        String name = url;
        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash < url.length() - 1) {
            name = url.substring(slash + 1);
        }
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (name.length() > 40) name = name.substring(0, 40);
        return name;
    }

    /* ==================== 一键清理无效源 ==================== */

    /**
     * 检测当前激活源的频道地址可达性。
     * 将存活频道写入新源文件（保留原源不删除）。
     * 只清理当前激活源，避免旧的坏源（如"频道 N"命名）混入新源。
     */
    private void cleanInvalidSources() {
        SourceStore.Source active = SourceStore.active(context);
        if (active == null) {
            setResult(context.getString(R.string.import_empty));
            return;
        }
        setResult(context.getString(R.string.import_cleaning));

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 收集存活频道（name + validUrls + group）
                java.util.List<String> groups = new java.util.ArrayList<String>();
                java.util.List<String> names = new java.util.ArrayList<String>();
                java.util.List<java.util.List<String>> validUrls = new java.util.ArrayList<java.util.List<String>>();
                java.util.List<String> groupNames = new java.util.ArrayList<String>();

                List<Channel> channels = M3uParser.parse(active.text);
                int totalChannels = channels.size();
                int validChannels = 0;

                for (Channel ch : channels) {
                    // 检查该频道的每个地址
                    java.util.List<String> okUrls = new java.util.ArrayList<String>();
                    for (String url : ch.urls) {
                        int code = SourceStore.check(url);
                        if (code >= 200 && code < 300) {
                            okUrls.add(url);
                        }
                    }
                    if (!okUrls.isEmpty()) {
                        validChannels++;
                        groups.add(ch.group);
                        names.add(ch.name);
                        validUrls.add(okUrls);
                        groupNames.add(ch.group != null ? ch.group : "");
                    }
                }

                final int removed = totalChannels - validChannels;
                final int validCount = validChannels;
                final String text = buildPlaylist(groupNames, names, validUrls);

                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (removed <= 0) {
                            setResult(context.getString(R.string.import_clean_none));
                            return;
                        }
                        // 弹确认框，用户确认后才保存新源
                        showCleanConfirm(removed, validCount, text);
                    }
                });
            }
        }, "tv-clean").start();
    }

    /** 根据存活频道重建播放列表文本（#genre# 分组格式） */
    private String buildPlaylist(
            List<String> groupNames, List<String> names, List<List<String>> validUrls) {
        StringBuilder sb = new StringBuilder();
        String currentGroup = null;
        for (int i = 0; i < names.size(); i++) {
            String group = groupNames.get(i);
            // 去掉分组名末尾的逗号（旧数据可能带逗号）
            if (group != null) group = group.replaceAll(",+$", "").trim();
            if (group != null && !group.isEmpty() && !group.equals(currentGroup)) {
                sb.append(group).append(",#genre#\n");
                currentGroup = group;
            } else if (currentGroup == null) {
                sb.append("频道,#genre#\n");
                currentGroup = "频道";
            }
            // 每个地址单独一行带名称，M3uParser 会自动合并同名频道为多源
            for (int j = 0; j < validUrls.get(i).size(); j++) {
                sb.append(names.get(i)).append(",")
                  .append(validUrls.get(i).get(j)).append("\n");
            }
        }
        return sb.toString();
    }

    /** 清理确认弹窗：用户确认后才保存新源 */
    private void showCleanConfirm(final int removed, final int validCount, final String text) {
        String msg = context.getString(R.string.import_clean_confirm, removed, validCount);
        final AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("清理无效源")
                .setMessage(msg)
                .setPositiveButton("确认清理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = "清理后-" + new java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.US)
                                .format(new java.util.Date());
                        SourceStore.Source s = SourceStore.add(context, name, "", text);
                        if (s != null) {
                            setResult(context.getString(R.string.import_clean_ok)
                                    + "（移除 " + removed + " 个无效频道，保留 " + validCount + " 个）");
                            refreshSources();
                            if (listener != null) listener.onSourcesChanged();
                        } else {
                            setResult(context.getString(R.string.import_saved_fail));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        try {
            dlg.show();
        } catch (Throwable t) {
            android.util.Log.w("ImportDialog", "showCleanConfirm: " + t);
        }
        // 按钮焦点高亮
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                Button neg = dlg.getButton(DialogInterface.BUTTON_NEGATIVE);
                Button pos = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
                int accent = ThemeManager.accent();
                if (neg != null) {
                    neg.setBackgroundDrawable(ThemeManager.buttonBg(context));
                    neg.setTextColor(accent);
                    neg.setFocusable(true);
                }
                if (pos != null) {
                    pos.setBackgroundDrawable(ThemeManager.buttonBg(context));
                    pos.setTextColor(accent);
                    pos.setFocusable(true);
                }
                if (neg != null) neg.requestFocus();
            }
        });
    }

    /* ==================== 源列表 ==================== */

    private void refreshSources() {
        sources.clear();
        sources.addAll(SourceStore.list(context));
        if (adapter == null) {
            adapter = new SavedAdapter();
            sourceList.setAdapter(adapter);
            sourceList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    SourceStore.Source s = sources.get(position);
                    SourceStore.setActive(context, s.id);
                    refreshSources();
                    if (listener != null) listener.onSourcesChanged();
                    setResult("已切换为：" + s.name);
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
        if (sources.isEmpty()) {
            setResult(context.getString(R.string.import_empty));
        }
    }

    private class SavedAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return sources.size();
        }

        @Override
        public Object getItem(int position) {
            return sources.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(context).inflate(R.layout.item_saved_source, parent, false);
            SourceStore.Source s = sources.get(position);
            TextView name = (TextView) view.findViewById(R.id.savedName);
            TextView url = (TextView) view.findViewById(R.id.savedUrl);
            TextView delete = (TextView) view.findViewById(R.id.savedDelete);
            TextView check = (TextView) view.findViewById(R.id.savedCheck);

            name.setText(s.name);
            url.setText(s.url == null || s.url.isEmpty() ? "本地内容 · " + s.text.length() + " 字节" : s.url);

            // 激活源：打钩标记
            SourceStore.Source active = SourceStore.active(context);
            boolean isActive = active != null && active.id.equals(s.id);
            check.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);

            // 手动高亮：聚焦=实心主题色+深字，激活=主题色文字
            int accent = ThemeManager.accent();
            boolean focused = sourceList.hasFocus() && position == sourceList.getSelectedItemPosition();
            view.setBackgroundColor(focused ? accent : 0x00000000);
            name.setTextColor(focused ? 0xFF05110F : (isActive ? accent : 0xFFFFFFFF));
            url.setTextColor(focused ? 0xFF05110F : 0x66FFFFFF);
            check.setTextColor(focused ? 0xFF05110F : accent);
            delete.setTextColor(focused ? 0xFF05110F : 0xFFFF5C5C);

            // 删除：长按触发，防误触
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setResult("长按「删除」确认删除该源");
                }
            });
            delete.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(s);
                    return true;
                }
            });
            // 整行长按删除（遥控器友好）
            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(s);
                    return true;
                }
            });
            return view;
        }
    }

    private void confirmDelete(final SourceStore.Source s) {
        final AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("删除源")
                .setMessage("确定删除「" + s.name + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        SourceStore.remove(context, s.id);
                        refreshSources();
                        if (listener != null) listener.onSourcesChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        try {
            dlg.show();
        } catch (Throwable t) {
            android.util.Log.w("ImportDialog", "confirmDelete show: " + t);
        }
        // 按钮焦点高亮
        android.content.DialogInterface.OnShowListener onShow = new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                Button neg = dlg.getButton(DialogInterface.BUTTON_NEGATIVE);
                Button pos = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
                int accent = ThemeManager.accent();
                if (neg != null) {
                    neg.setBackgroundDrawable(ThemeManager.buttonBg(context));
                    neg.setTextColor(accent);
                    neg.setFocusable(true);
                }
                if (pos != null) {
                    pos.setBackgroundDrawable(ThemeManager.buttonBg(context));
                    pos.setTextColor(accent);
                    pos.setFocusable(true);
                }
                // 默认聚焦取消按钮（防误触）
                if (neg != null) neg.requestFocus();
            }
        };
        dlg.setOnShowListener(onShow);
    }

    /* ==================== 工具 ==================== */

    private void setResult(String text) {
        if (result != null) {
            result.setText(text);
            result.setTextColor(ThemeManager.accent());
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 刷新所有引用 @color/accent 的 drawable 背景和文字颜色 */
    public void applyThemeDrawables() {
        if (rootView == null) return;
        int accent = ThemeManager.accent();
        // 标题文字颜色（跟随主题）
        TextView title = (TextView) rootView.findViewById(R.id.importTitle);
        if (title != null) title.setTextColor(accent);
        // 二维码卡片背景
        ImageView qr = (ImageView) rootView.findViewById(R.id.importQr);
        if (qr != null && qr.getParent() instanceof LinearLayout) {
            ((LinearLayout) qr.getParent()).setBackgroundDrawable(ThemeManager.cardBg(context));
        }
        // 按钮背景 + 文字颜色
        Button check = (Button) rootView.findViewById(R.id.importCheck);
        Button fetch = (Button) rootView.findViewById(R.id.importFetch);
        Button save = (Button) rootView.findViewById(R.id.importSave);
        if (check != null) {
            check.setBackgroundDrawable(ThemeManager.buttonBg(context));
            check.setTextColor(accent);
        }
        if (fetch != null) {
            fetch.setBackgroundDrawable(ThemeManager.buttonBg(context));
            fetch.setTextColor(accent);
        }
        if (save != null) {
            save.setBackgroundDrawable(ThemeManager.buttonBg(context));
            save.setTextColor(accent);
        }
        // 输入框背景
        if (input != null) {
            input.setBackgroundDrawable(ThemeManager.buttonBg(context));
        }
        // 清理按钮
        Button clean = (Button) rootView.findViewById(R.id.importClean);
        if (clean != null) {
            clean.setBackgroundDrawable(ThemeManager.buttonBg(context));
            clean.setTextColor(accent);
        }
        // 结果文字颜色
        if (result != null) {
            result.setTextColor(accent);
        }
        // QR 提示文字颜色（卡片内二维码下方的提示文字）
        if (qr != null && qr.getParent() instanceof LinearLayout) {
            LinearLayout card = (LinearLayout) qr.getParent();
            String hint = context.getString(R.string.import_qr_hint);
            for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                if (child instanceof TextView) {
                    if (((TextView) child).getText().toString().equals(hint)) {
                        ((TextView) child).setTextColor(accent);
                    }
                }
            }
        }
        // 关闭按钮
        Button close = (Button) rootView.findViewById(R.id.importClose);
        if (close != null) {
            close.setBackgroundDrawable(ThemeManager.buttonBg(context));
            close.setTextColor(accent);
        }
    }
}
