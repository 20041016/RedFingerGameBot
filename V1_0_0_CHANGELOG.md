# RedFinger GameBot V1.0.0 — 双功能开发版

## 主页
- 改为简洁现代风格。
- 增加游戏挂机、音游辅助两个独立功能入口。
- 展示悬浮窗权限状态和 Root 授权提示。

## 新增音游辅助
- 新增六轨蓝色通道采样引擎。
- 新增 Root Type-B 多点触控控制器，按触摸驱动 ABS 范围缩放坐标。
- 按下/抬起状态独立管理，停止或异常时释放全部触点。
- 不申请无障碍服务权限。

## 运行管理
- 两个引擎独立选择，切换时先停止旧引擎。
- 音游状态通知限频，避免 5ms 循环频繁刷新通知。

## 注意
- 当前多点触控仅支持可识别的 Type-B `/dev/input/event*` 设备，且至少 6 个 slot。
- 触摸坐标按原脚本竖屏 1440x3200 逻辑坐标映射；需要真机校准。
- 未进行 Android APK 构建和真实设备测试。


## Root Type-B 多点触控整合修订
- 将实测通过的首指/末指全局触摸状态管理并入 `RootTouchController`。
- 首个活动 Slot 按下时发送 `BTN_TOUCH` 与 `BTN_TOOL_FINGER` DOWN。
- 仅在最后一个活动 Slot 释放时发送对应 UP；释放单指不会清除其他活动触点。
- `releaseAll()` 统一释放活动 Slot 并清理全局触摸状态。
- 目标设备实测参数：Goodix event7、10 slots、1440×3200、X 0–143999、Y 0–319999。

## OpenCV 音游性能更新
- 新增官方 OpenCV Android AAR（4.10.0），使用 Java 原生库解码 Root 截图 PNG。
- 音游截图改为持久 Root shell 的 `screencap -p` 标准输出，不再每帧创建 PNG 文件、写盘并通过 BitmapFactory 解码。
- OpenCV 仅采样原有六个像素点的 BGR 蓝色通道，保持原六轨坐标、阈值 220 和按键逻辑不变。
- 音游循环不再额外固定 sleep 5ms，循环由截图帧流自然节流。
- Root sendevent 按下/释放事件合并成单次 shell 写入并 flush，减少逐事件刷新开销。
- 悬浮窗诊断增加平均 FPS、截图耗时、OpenCV 解码耗时和单轮耗时。
- 不使用 MediaProjection，不申请屏幕录制授权，不添加 Accessibility Service。


## Raw screenshot performance patch (2026-11-06)
- Replaced per-frame PNG screencap stream with raw `screencap` RGBA/RGBX output over the persistent Root shell.
- Parses Android's 16-byte raw header (width/height/format/dataspace).
- Removed PNG encoding and OpenCV PNG decompression from the music loop.
- Reuses an OpenCV `CV_8UC4` Mat across frames and samples only the six lane pixels.
- Kept the existing six lane coordinates, blue threshold, Root Type-B injection, and no-permission/no-accessibility constraints.
- Requires device-side FPS/latency validation; no APK build or handset benchmark is claimed.

### Root 持续原始帧采集（2026-11-06）
- 音游改用常驻 `screenrecord --output-format=raw-frames` stdout 流，不再逐帧调用 `screencap`。
- 固定 1600x720 RGB888 原始帧，采样坐标按 0.5 缩放；不写临时文件、不做 PNG 或 H.264 解码。
- 保留 OpenCV 六轨采样、原始蓝色阈值、Root Type-B 触摸逻辑。
- 依赖设备 ROM 支持 screenrecord raw-frames；未在目标手机实测帧率，不能预先保证 FPS。

## 实时触控时序优化（坐标锁定）
- 保留音游原始六轨检测坐标：X=[778,1100,1440,1780,2100,2440]，Y=1210。
- 保留触摸逻辑坐标：X=230，Y 使用对应轨道 X 值；未移动任何检测点或触摸点。
- 将每帧最多六次 Python→Java 触摸调用合并为一次 `updateTouches`，Java 侧只写入发生状态变化的触点，并在一个批次中提交一个 SYN_REPORT。
- 保留 Type-B 每指独立 tracking ID、按下/释放语义以及最后一指释放时的 BTN_TOUCH 状态。
- 音游循环线程使用 Android display 优先级，减少调度抖动。
- 悬浮诊断新增“输入批次耗时”和“Java 写入耗时”，用于观察判定到触控写入之间的开销。
- 本次不改变颜色阈值 220、截图分辨率、帧采集方式或六轨映射。

## Root coordinate capture update
- Added one-shot Root `getevent -lt` capture for the coordinate editor. Each Record button waits for the next fresh physical touch after the button press has been released.
- Converts portrait Type-B touchscreen raw axes into the existing landscape detection axis convention; detection Y uses the inverse portrait-X mapping.
- Saves six lane detection X values, detection-line Y, touch X, and optional fixed touch Y in app SharedPreferences. Default touch Y remains linked to each lane's detection X to preserve the original six-lane behavior.
- Coordinate capture is blocked while the automation engine is running to avoid reading the engine's own injected `sendevent` touches.
- No Accessibility Service or MediaProjection was added.

## 身份验证诊断状态机（本次修改）
- 游戏模式新增身份验证弹窗 OCR 流程：前往验证 → 开始验证 → 诊断网络 → 展开错误详情。
- 等待“隐藏错误详情”后，从屏幕中下部向上滑动，直到同时识别到“上传错误”和可见的 `mqqapi://` URI。
- 将识别到的可见 URI 复制到剪贴板，并通过 Root 追加保存到 `/storage/emulated/0/RedFingerGameBot/verification_urls.txt`。
- 保存后模拟实体 Back 键返回游戏；再次监测身份验证状态，连续 30 秒未检测到相关状态文案后退出身份验证状态机。
- 验证流程优先于普通游戏点击，不主动停止挂机引擎；原音乐模式和游戏模式常规逻辑未改动。
- 当前仅完成源码级 Python 语法与状态机模拟测试；尚未在 Android SDK/设备上完成 Gradle 构建和实机验证。
