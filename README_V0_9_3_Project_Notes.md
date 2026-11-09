# RedFinger GameBot V0.9.3

基于 GitHub 仓库 V0.9.2 工程源码恢复并更新。目标环境为 Android 10+ 红手指 Root 云手机；使用 Root 截图、点击、滑动和 ML Kit 中文 OCR。

## 控制方式
1. 打开 APK 后点击“打开悬浮控制面板”，只显示面板，不会自动挂机。
2. 点击 29dp 小圆点展开控制栏。
3. 控制栏包含三个 32dp 圆形图标按钮：启动/暂停、收起、关闭。
4. 暂停只停止引擎，悬浮窗保留；点击关闭按钮才会移除悬浮窗并结束服务。
5. 每次启动重新截图识别当前画面；未识别场景不会默认大厅，也不会误点开始游戏。

## 自动化逻辑
- 全局高优先级点击文案：确定、继续、放弃、忽略、重试。
- 场景识别词：信号区、剩余、离开、舱内人数、请勿在现实中模仿、淘汰详情、返回大厅、特惠、开始游戏。
- 连续 5 秒未命中场景关键词后，将 OCR 文本块、坐标和屏幕尺寸追加保存到 `ocr_unrecognized.log`。
- 原始坐标不变：奔跑按钮 `(1066, 194)`，额外按钮 `(1231, 503)`；参考坐标系 `1280×720`。

## 构建
将工程根目录内容打包为 `source.zip`，替换仓库根目录同名文件，触发仓库已有 GitHub Actions 工作流。构建成功后从 Actions Artifacts 下载 APK。

## V1.0.0 双功能版本（开发中）

- 主页改为简洁现代风格，提供「游戏挂机」与「音游辅助」两个独立入口。
- 原游戏挂机模块继续使用 `python.bot.GameBot`，原场景识别与操作逻辑保留。
- 新增 `python.music_bot.MusicBot`：按上传的 Auto.js 参数采样六个轨道点的蓝色通道，并维护六个独立触点状态。
- 音游触控采用纯 Root `getevent -lp` 发现触摸设备、`sendevent` 注入 Type-B 多点触控，不申请无障碍服务权限。
- 切换功能时先停止当前引擎；停止/异常/服务关闭时释放所有已按下的触点。

### 音游 Root 触控兼容性说明

当前实现要求触摸屏驱动暴露 Type-B 多点触控事件：`ABS_MT_SLOT`、`ABS_MT_TRACKING_ID`、`ABS_MT_POSITION_X`、`ABS_MT_POSITION_Y`，并至少支持 6 个 slot。设备必须允许 Root 对对应 `/dev/input/event*` 节点执行 `sendevent`。

本次设备实测补充（2026-11-06）：Goodix `event7` 为 Type-B，`ABS_MT_SLOT` 范围 0–9，X 轴 0–143999，Y 轴 0–319999；屏幕物理分辨率 1440×3200。已用 `sendevent` 在桌面验证双指同时按下、单独释放其中一指、另一指继续保持。`RootTouchController` 已同步补齐首指按下时的 `BTN_TOUCH/BTN_TOOL_FINGER DOWN`、末指释放时的对应 `UP`，并在 `releaseAll()` 中统一清理全部活动 Slot。此验证不等同于音游实机长时间稳定性验证。

当前使用上传脚本中的固定参数：检测 X=`778,1100,1440,1780,2100,2440`、检测 Y=`1210`；触摸逻辑坐标 X=`230`、Y 对应各轨道检测 X，逻辑屏幕按竖屏 `1440x3200` 映射。若设备原生触摸轴方向、范围或屏幕分辨率不同，需要先校准，不能保证开箱即用。

`sleep(5)` 只是循环目标间隔，不代表真实采样频率能达到 200 FPS。实际速度受 `screencap`、PNG 解码、Root 命令和设备触摸驱动限制。首次运行请在安全场景测试，确认六条轨道触点位置和按键释放行为。

### 本地烟雾测试

```bash
python3 -m py_compile app/src/main/python/python/*.py
python3 MUSIC_ENGINE_SMOKE_TEST.py
PYTHONPATH=app/src/main/python python3 PYTHON_ENGINE_SMOKE_TEST.py
```

这些测试只验证 Python 逻辑，不会验证 Android `sendevent`、Root 授权或真实设备触摸行为。
