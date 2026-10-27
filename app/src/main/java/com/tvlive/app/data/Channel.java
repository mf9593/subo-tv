package com.tvlive.app.data;

/**
 * 一个电视频道。
 *
 * <p>台号从 1 开始，供遥控器数字键直接跳转。</p>
 */
public class Channel {

    /** 台号，从 1 开始 */
    public final int number;
    /** 频道名称 */
    public final String name;
    /** 播放地址（http/https/rtsp/rtmp） */
    public final String url;
    /** 分组名称，可为 null */
    public final String group;

    public Channel(int number, String name, String url, String group) {
        this.number = number;
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.group = group;
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
