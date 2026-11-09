# RedFinger GameBot V1.0.0 — OpenCV 音游性能版

本源码包是在 V1.0.0 双功能项目上继续更新的版本，包含原有游戏挂机入口和六轨音游辅助。

## 音游架构
- **屏幕采集：** Root 持续 Shell 执行 `screencap -p`，PNG 通过标准输出传递，不写入临时 PNG 文件。
- **图像解码与采样：** Java 层 OpenCV 4.10.0 原生库；直接读取原脚本六个像素点的 BGR 蓝色通道。
- **触摸注入：** Java `RootTouchController`，Root `sendevent` Type-B 多点触控，按下/释放命令批量写入。
- **音符逻辑：** 继续使用原 `MusicBot` 六轨状态机。

## 保持的原始参数
- 检测 X：`778, 1100, 1440, 1780, 2100, 2440`
- 检测 Y：`1210`
- 蓝色通道判定：`blue < 220`
- 触摸逻辑坐标 X：`230`
- 触摸逻辑坐标 Y：各轨道检测 X
- 触点 ID：`0..5`
- 逻辑屏幕：`1440 × 3200`

## 隐私与权限约束
- 不使用 Android MediaProjection。
- 不弹出屏幕录制/投射授权窗口。
- 不增加 Accessibility Service 或无障碍权限。
- 保持纯 Root 截图与 Root `sendevent` 注入。

## 构建要求
- Android Studio / Gradle
- 项目现有 Android Gradle Plugin 8.13.2、Chaquopy 17.2.0
- Gradle 需能访问 Google Maven 与 Maven Central，以下载 `org.opencv:opencv:4.10.0` AAR
- 当前项目 `minSdk 24`，ABI 保留 `arm64-v8a` 和 `armeabi-v7a`

在 Android Studio 中打开项目根目录，执行 Sync，再构建 APK。OpenCV 官方文档说明 Android AAR 可从 Maven Central 获取（OpenCV 4.9.0 起），并通过 `OpenCVLoader.initLocal()` 加载。

## 首次运行验证
1. 安装并授予原有 Root 权限。
2. 进入音游画面后再启动辅助，确认触摸位置。
3. 观察悬浮窗诊断：帧数、触发数、平均 FPS、截图耗时、OpenCV 解码耗时、单轮耗时和六个蓝色采样值。
4. 如果仍然漏按，请把悬浮窗诊断完整文字和对应游戏画面发回，以判断瓶颈在截图、解码、采样还是触摸注入。

## 验证边界
本次完成源码集成、Python 语法检查、两项 Python 引擎烟雾测试，以及新增 Java 类的语法级编译检查。当前环境没有 Android SDK/Gradle 安装，**未能在此处构建 APK，也未在你的手机上测得实际 FPS**。性能收益需要以设备实测为准。


### Music capture performance revision

The music engine uses persistent-Root-shell raw `screencap` output (RGBA/RGBX) instead of PNG. It skips PNG encoding/decoding and temporary-file I/O; OpenCV writes each frame into a reusable native Mat and samples the six lane coordinates. The raw stream parser expects Android's 16-byte header (width, height, pixel format, dataspace). On-device FPS is not guaranteed and must be measured.

### 音游采集性能实验版
音游识别现在通过 Root 常驻 `screenrecord --output-format=raw-frames --size 1600x720 -` 读取 RGB888 连续帧，避免每轮启动 `screencap`。检测坐标按 0.5 缩放，触摸坐标与 Root Type-B 注入保持原样。若设备 ROM 不支持 `raw-frames`，会在启动时显示 screenrecord 错误。该路径无需 MediaProjection 授权，但必须在目标设备实测帧率与采样位置。

### V1.1.0 专辑补难度（新增第三个功能入口）

新增「专辑补难度」模式：在「千音雅集」专辑目录下，按配置把固定 12 首的指定难度补到
「大音天籁」（100.0%），打完自动切下一首。打歌部分直接复用本项目的六轨 `MusicBot`，
未改动其内部逻辑；页面识别复用 `RootHost.capture()` 的全屏 OCR，点击复用 `RootHost.tap()`，
异常提醒复用现有 SMTP 设置。

新增文件：
- `app/src/main/python/python/album_bot.py`（`AlbumBot` 状态机）
- `ALBUM_SCRIPT_SMOKE_TEST.py`（离线模拟测试）
- `ALBUM_AUTOMATION_NOTES.md`（配置、待确认项取舍、验收对照、已知边界）

两个固定坐标（下一首、已完成返回）在悬浮窗「坐标设置 → 专辑补难度」里现场记录，
未记录时脚本拒绝启动。
