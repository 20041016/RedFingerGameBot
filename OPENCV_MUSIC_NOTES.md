# OpenCV 音游识别实现说明

## 保持不变
- 纯 Root；无 MediaProjection 屏幕录制授权、无 Accessibility Service。
- Root Type-B `sendevent` 多点触控。
- 六轨检测坐标：778、1100、1440、1780、2100、2440；Y=1210。
- 触摸坐标与原六个 pointer ID 0..5 不变。
- 蓝色通道阈值仍为 220，判定仍为 `blue < 220`。

## 新截图与处理链路
1. `RootScreenCapture` 启动一个持续 Root shell。
2. 每轮发送 `screencap -p`，从 shell 标准输出读取完整 PNG chunk，不创建临时 PNG 文件。
3. `OpenCvFrameProcessor` 使用官方 OpenCV Android 原生库 `Imgcodecs.imdecode` 解码。
4. 直接读取六个像素点的 BGR 第 0 通道（蓝色），不做整图 HSV 转换，避免改变旧脚本颜色语义。
5. 采样结果回传原 `MusicBot`，触摸仍由 `RootTouchController` 处理。

## 性能诊断
悬浮窗诊断文本提供帧数、触发数、平均 FPS、截图耗时、OpenCV 解码耗时和本轮耗时。请以实际手机运行数值为准；本次源码修改尚未在用户手机上实测。

## 构建
依赖 `org.opencv:opencv:4.10.0`，需 Android Studio/Gradle 联网从 Maven Central 下载 AAR。官方文档说明 OpenCV Android AAR 从 4.9.0 起可通过 Maven Central 使用，且初始化方式为 `OpenCVLoader.initLocal()`。


## 2026-11-06 raw screencap latency revision

The music path no longer requests PNG (`screencap -p`). It invokes raw `screencap` in the already-running Root shell and reads Android's binary header plus RGBA/RGBX pixels. This avoids PNG compression and OpenCV PNG decompression on every polling cycle. OpenCV receives the raw frame in a reusable `CV_8UC4` Mat and samples only the six configured coordinates. The shell remains persistent; no temporary image files, MediaProjection prompt, or Accessibility Service are used.

Current Android screencap raw output includes width, height, pixel format, and dataspace words before pixel bytes; the parser consumes the 16-byte header. Only pixel formats 1 (RGBA_8888) and 2 (RGBX_8888) are accepted. If a device reports another format, startup fails clearly instead of sampling incorrect colors.

This is a source-level optimization. Capture latency and FPS must still be measured on the target handset; raw capture removes PNG work but does not eliminate SurfaceFlinger capture and pipe-transfer costs.

## 2026-11-06 Root 持续原始帧采集优化

- 音游采集从逐帧 `screencap` 改为一次启动常驻 `screenrecord --output-format=raw-frames`。
- 使用 1600x720 RGB888 原始帧流（按原 3200x1440 横屏检测坐标缩放 0.5），不做 PNG 编码、H.264 编码/解码，也不写临时文件。
- AOSP `screenrecord` raw-frames 模式由 FrameOutput 使用 EGL/GL 读取 RGB 帧，并将紧凑 RGB888 数据直接写入 stdout；每帧无头部，尺寸固定，因此 Java 侧按固定帧长度读取。
- 这条路径不调用 MediaProjection，不应弹出系统录屏授权框；仍需要 Root 执行 screenrecord。
- 兼容性依赖手机系统自带 screenrecord 是否包含 `raw-frames` 输出格式。若 OEM 构建移除了该格式，会在启动时报告 stderr 错误。
- 目标是消除每帧启动进程与 PNG/解码开销；实际帧率仍受 SurfaceFlinger、GL readback、管道吞吐和设备调度影响，必须在用户设备实测。
