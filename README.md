# DanmuReader · 弹幕朗读助手

面向视障用户的 Android 抖音直播弹幕朗读工具。通过无障碍服务读取直播间的可见弹幕，交由系统 TTS 引擎朗读，并提供悬浮窗控制。

作者：**aojiangQAQ（鳌江）**

## 功能

- 监听抖音应用 `com.ss.android.ugc.aweme` 的界面变化，识别带用户名的弹幕文本。
- 去重并过滤礼物、系统通知和部分界面文字，支持自定义屏蔽词与刷屏过滤开关。
- 悬浮窗提供暂停、恢复、语速调整、跳过积压、历史重播及折叠控制。
- 检测中文 TTS 引擎，提供朗读测试和引擎设置入口。
- 查看运行日志，并通过系统分享导出诊断文件。

弹幕识别依赖抖音的无障碍节点结构和部分视图 ID，不同抖音版本、直播间界面或设备上的效果可能不同。

## 安装与使用

1. 在 Android 7.0（API 24）或更高版本设备上安装构建出的 APK。
2. 打开应用，检测系统 TTS 引擎；确保默认引擎已安装中文语音数据。
3. 在应用中授予悬浮窗权限，并在系统无障碍设置中启用“弹幕朗读助手”。
4. 打开抖音直播间，通过悬浮窗控制朗读。
5. 在设置页维护自定义屏蔽词和刷屏过滤开关。不使用时可关闭无障碍服务。

## 构建

当前构建配置：

| 项目 | 配置 |
| --- | --- |
| JDK / Kotlin JVM 目标 | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.2.2 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 35 |
| Build Tools | 35.0.1 |
| minSdk | 24 |

使用 Android Studio 打开仓库根目录，配置 Android SDK 后同步 Gradle。命令行构建需要先配置 `ANDROID_HOME` 或本机 `local.properties` 中的 `sdk.dir`；`local.properties` 不提交到仓库。

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```sh
sh ./gradlew assembleDebug
```

首次构建需要下载 Gradle 和依赖。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式发布需要自行配置签名，不应提交签名密钥或签名密码。

## 目录

```text
app/src/main/java/com/danmureader/
  DanmuAccessibilityService.kt  弹幕识别与过滤
  DanmuQueue.kt                 近期弹幕去重
  TtsManager.kt                 语音引擎与朗读队列
  FloatingWindowManager.kt      悬浮窗控制
  SettingsManager.kt            本地设置
  AppLogger.kt                  运行日志
app/src/main/res/               布局、文案和无障碍服务配置
```

## 数据与反馈

应用本身未声明网络权限，设置保存在应用的本地 `SharedPreferences`。实际语音处理由选用的 TTS 引擎负责，其网络行为取决于引擎设置。

诊断日志可能包含弹幕用户名、文本、自定义屏蔽词及设备型号。提交 Issue、截图或日志前，应删除与问题无关的个人信息；导出的 `danmu_log_*.txt` 已加入 `.gitignore`。

问题反馈：<https://github.com/aojiangQAQ/DanmuReader/issues>。
