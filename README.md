# 电视直播（TV Live）

面向 **Android 4.4（API 19）及以上** 电视盒子/电视的直播播放器，**纯遥控器操作**。

- 播放内核：ExoPlayer 2.19.1（最后一个支持 API 16+ 的版本，Media3 要求 API 21）
- 支持协议：HLS（m3u8）、RTSP（摄像头/推流）、HTTP 渐进式（mp4 等）
- 频道来源：内置 `assets/channels.m3u` + 外部 `tvlive.m3u` 文件
- 无广告、无后台服务、不联网上报任何数据

## 遥控器按键

| 按键 | 作用 |
|---|---|
| 上 / 下、CH+ / CH-、翻页 | 上/下一个台（循环） |
| 数字键 0-9 | 输入台号直接跳台（1.5 秒后自动切换） |
| 方向盘中间键 / OK / 回车 | 频道列表（可上下选择、OK 确认） |
| 左 / 右 | 调音量 |
| 菜单键 | 菜单：频道列表 / 重新载入频道 / 缓冲策略 / 帮助 |
| 信息键 | 重新显示台标信息 |
| 返回键 | 连按两次退出 |

## 添加自己的频道

方式一（推荐）：把 `tvlive.m3u` 放到盒子内置存储根目录，或 `Download` 目录下，
然后在电视上按 **菜单 → 重新载入频道**。

方式二：替换 `app/src/main/assets/channels.m3u` 后重新打包。

M3U 写法：

```
#EXTINF:-1 group-title="央视",CCTV-1 综合
http://example.com/cctv1.m3u8

#EXTINF:-1 group-title="卫视",湖南卫视
rtsp://192.168.1.100:554/Streaming/Channels/101
```

也支持简写（一行一个）：

```
CCTV-1 综合,http://example.com/cctv1.m3u8
```

台号按文件里的顺序自动编号（1、2、3…）。

## 构建

```bash
sh gradlew assembleDebug      # 产出 app/build/outputs/apk/debug/app-debug.apk
```

> 本机 `gradle` 不在 PATH，用 `sh gradlew`。
> 首次构建约 5 分钟（要下载 ExoPlayer 依赖），之后增量构建几秒。

## 安装到电视

```bash
# 1. 电视开启「开发者选项 → USB 调试 / 网络调试」
adb connect 电视IP:5555
adb install -r app-debug.apk

# 2. 或者把 apk 拷到 U 盘，用电视上的文件管理器安装
```

> 后续升级必须用同一份 debug keystore 签名（本机 `~/.android/debug.keystore`，
> 备份在 `/storage/emulated/0/Download/code/debug.keystore.backup`），否则要卸载重装。

## Android 4.4 的限制

以下几点是老盒子的硬件/系统限制，不是 App 的问题：

1. **H.265/HEVC 频道**：4.4 盒子普遍没有 H.265 硬解，ExoPlayer 也不带软解扩展，这类流会提示「解码失败」。
   选 H.264 的源即可。
2. **HTTPS 流**：4.4 的 TLS 栈较老，部分站点会握手失败。优先用 `http://` 的源。
3. **AV1 / VP9**：老盒子基本不支持。
4. **64 位/新编码器**：部分盒子解码器对非标准 SPS/PPS 兼容性差，失败时会自动重试 2 次并提示原因。

## 项目结构

```
app/src/main/java/com/tvlive/app/
├── MainActivity.java          主界面：OSD、遥控器按键、菜单、弹窗
├── data/
│   ├── Channel.java           频道模型（台号/名称/地址/分组）
│   ├── ChannelStore.java      频道读取与缓存（外部 M3U > 内置 assets）
│   └── M3uParser.java         极简 M3U 解析器
├── player/
│   └── LivePlayer.java        ExoPlayer 封装：协议分流、重试、HLS 兜底、错误中文化
└── res/                       布局、颜色、图标
```

## 调试

```bash
adb logcat -s TvLive:I TvLivePlayer:I TvLivePlayer:W
```

会输出：频道载入数量与来源、每次切台、开始播放的地址、播放就绪、失败原因与错误码。
