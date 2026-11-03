# ⚡ 速播

> 极速、纯净的安卓电视直播播放器

[![Platform](https://img.shields.io/badge/Platform-Android-lightgrey?logo=android&logoColor=white)](#)
[![MinSDK](https://img.shields.io/badge/MinSDK-19-blue?logo=android&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-MIT-green)](#)

速播是一款专为 Android TV / 电视盒子设计的轻量级直播播放器。**不内置任何频道数据**，你只需导入自己的直播源地址，即可享受流畅的观看体验。

---

## ✨ 特性

- **空壳设计** — 不内置频道，你的源由你掌控
- **多格式支持** — 支持 m3u / m3u8 / txt 直播源
- **Web 后台** — 电视上启动后台服务，手机浏览器扫码即可导入频道
- **自动换源** — 当前源失效时自动切换备用源
- **主题跟随** — 自动适配系统深浅色主题
- **遥控器友好** — 专为 Android TV 遥控器交互优化
- **低版本兼容** — 最低支持 Android 4.4.2 (API 19)
- **纯 Java** — 无 Kotlin 依赖，代码简洁易维护
- **ExoPlayer** — 基于 ExoPlayer 2.19.1，播放稳定流畅

---

## 📸 截图

<p align="center">
  <em>主界面 / 频道列表 / 播放画面 / Web 后台</em>
</p>

---

## 📲 安装

### 方式一：直接安装 APK

下载 `app-debug.apk`，通过 U 盘或 ADB 安装到电视盒子：

```bash
adb install -r app-debug.apk
```

### 方式二：从源码构建

```bash
# 克隆仓库
git clone https://github.com/mf9593/subo-tv.git
cd subo-tv

# 构建
sh gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| Android SDK | 34+ |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.7.3 |
| minSdkVersion | 19 |
| targetSdkVersion | 34 |

---

## 🚀 使用说明

### 1. 导入直播源

速播提供两种导入方式：

**方式 A：Web 后台导入（推荐）**

1. 在电视上打开速播，按菜单键 → 选择「频道导入」
2. 电视屏幕会显示二维码和本机 IP 地址
3. 用手机浏览器访问该 IP 地址
4. 在网页中输入直播源 URL 或上传文件
5. 点击「获取」预览频道列表
6. 确认后点击「添加为当前源」

**方式 B：手动输入**

1. 菜单 → 频道导入 → 手动输入直播源地址

### 2. 播放频道

1. 返回主界面，选择频道
2. 按 OK 键开始播放
3. 使用遥控器方向键切换频道

### 3. 设置

菜单 → 设置：
- 自动换源开关
- 主题切换（跟随系统 / 深色 / 浅色）
- 多选项箭头导航

---

## 🏗️ 项目结构

```
subo-tv/
├── app/
│   ├── src/main/
│   │   ├── java/com/tvlive/app/
│   │   │   ├── MainActivity.java        # 主界面
│   │   │   ├── ChannelStore.java       # 频道存储
│   │   │   ├── SourceStore.java        # 源存储
│   │   │   ├── server/
│   │   │   │   └── LocalWebServer.java # Web 后台服务
│   │   │   └── player/
│   │   │       └── LivePlayer.java     # 播放器
│   │   ├── res/
│   │   │   ├── drawable/               # 图标和背景
│   │   │   ├── layout/                 # 布局文件
│   │   │   └── values/                 # 字符串和主题
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
└── README.md
```

---

## 🔧 技术栈

| 技术 | 用途 |
|------|------|
| Java | 开发语言 |
| ExoPlayer 2.19.1 | 视频播放器 |
| NanoHTTPD | Web 后台服务 |
| Gradle 8.13 / AGP 8.7.3 | 构建工具 |
| Android Vector Drawable | 图标设计 |

---

## 📜 License

本项目采用 [MIT License](LICENSE) 开源协议。

---

## ⭐ 如果觉得有用

欢迎 Star ⭐ 支持一下！
