package com.tvlive.app.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个电视频道。
 *
 * <p>台号从 1 开始，供遥控器数字键直接跳转。
 * 一个频道可包含多个播放源（同名称的多条地址），播放失败时可按顺序自动换源。</p>
 */
public class Channel {

    /** 台号，从 1 开始 */
    public final int number;
    /** 频道名称 */
    public final String name;
    /** 播放地址列表（http/https/rtsp/rtmp），至少一个；按优先级排序 */
    public final List<String> urls;
    /** 分组名称，可为 null */
    public final String group;

    public Channel(int number, String name, List<String> urls, String group) {
        this.number = number;
        this.name = name == null ? "" : name;
        this.urls = new ArrayList<String>();
        if (urls != null) {
            for (String u : urls) {
                if (u != null && !u.trim().isEmpty()) {
                    this.urls.add(u.trim());
                }
            }
        }
        this.group = group;
    }

    /** 主地址（第一个源） */
    public String primaryUrl() {
        return urls.isEmpty() ? "" : urls.get(0);
    }

    /** 是否有多源 */
    public boolean hasMultipleSources() {
        return urls.size() > 1;
    }

    public int sourceCount() {
        return urls.size();
    }

    /** 列表里显示的一行文字，例如「12  CCTV-5 体育」 */
    public String displayTitle() {
        return number + "  " + name;
    }

    @Override
    public String toString() {
        return displayTitle();
    }
}
