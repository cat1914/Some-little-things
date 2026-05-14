
# Snapdragon Screen Recorder

针对骁龙 7 Gen 1 芯片优化的低占用 Android 屏幕录制应用，支持 Android 15+ 系统。

## 功能特性

- **H.264 硬件编码**：充分利用骁龙芯片的硬件编码能力
- **低资源占用**：针对性能和功耗进行优化
- **独立音频采集**：麦克风和系统声音可独立开关
- **悬浮窗控制**：录制时提供浮动控制面板
- **多种分辨率**：支持 1080p 和 720p 录制

## 技术特点

- 使用 Android MediaCodec 进行 H.264 硬件编码
- MediaProjection API 进行屏幕捕获
- MediaMuxer 封装 MP4 文件
- 前台服务确保录制稳定
- 支持悬浮窗权限

## 编译要求

- Android Studio Hedgehog 或更高版本
- Gradle 8.7+
- JDK 17
- Android SDK 35 (Android 15)

## 构建项目

### 使用 GitHub Actions

1. 将代码推送到 GitHub 仓库
2. Actions 会自动触发构建流程
3. 从 Releases 或 Artifacts 下载 APK

### 本地构建

```bash
# 克隆项目
git clone &lt;repo-url&gt;
cd SnapdragonScreenRecorder

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

## 权限说明

应用需要以下权限：
- `SYSTEM_ALERT_WINDOW`：悬浮窗显示
- `RECORD_AUDIO`：音频录制
- `FOREGROUND_SERVICE` &amp; `FOREGROUND_SERVICE_MEDIA_PROJECTION`：前台服务
- `POST_NOTIFICATIONS`：显示录制通知

## 使用说明

1. 启动应用，授予所需权限
2. 选择是否录制麦克风和系统声音
3. 选择录制分辨率
4. 点击"开始录制"
5. 使用悬浮窗或通知栏停止录制
6. 录制的视频保存在应用私有目录

## 项目结构

```
SnapdragonScreenRecorder/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/snapdragon/screenrecorder/
│   │       │   ├── MainActivity.kt          # 主界面
│   │       │   └── ScreenRecordService.kt   # 录屏服务（核心逻辑）
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_main.xml   # 主界面布局
│   │       │   │   └── floating_control.xml # 悬浮窗布局
│   │       │   ├── values/
│   │       │   └── xml/
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
├── .github/workflows/
│   └── build.yml                            # GitHub Actions 工作流
└── README.md
```

## 注意事项

- 需要添加应用图标资源 (ic_launcher) 才能正常构建完整项目
- Release 版本需要配置签名
- 部分设备可能需要特殊权限配置
- 对于更好的骁龙专属优化，可集成 Qualcomm Snapdragon SDK

## 许可证

本项目仅供学习和研究使用。
