"""Six-lane color-sampling rhythm helper using Root screenshots and raw multitouch."""
from .frame import _map_get, _as_sequence


class MusicBot:
    # Values from the supplied Auto.js script. Detection uses landscape screenshot
    # coordinates; touch coordinates intentionally use its portrait-axis mapping.
    DETECT_XS = [778, 1100, 1440, 1780, 2100, 2440]
    DETECT_Y = 1210
    TOUCH_X = 230
    BLUE_THRESHOLD = 220
    LOOP_INTERVAL_MS = 5
    LOGICAL_TOUCH_WIDTH = 1440
    LOGICAL_TOUCH_HEIGHT = 3200

    def __init__(self, host):
        self.host = host
        config = list(host.getMusicCoordinateConfig())
        if len(config) != 9:
            config = [778, 1100, 1440, 1780, 2100, 2440, 1210, 230, -1]
        self.detect_xs = [int(v) for v in config[:6]]
        self.detect_y = int(config[6])
        self.touch_x = int(config[7])
        self.touch_ys = list(self.detect_xs) if int(config[8]) < 0 else [int(config[8])] * 6
        self.running = False
        self.state = "音游待命"
        self.diagnostic = "等待启动"
        self.loop_interval_ms = self.LOOP_INTERVAL_MS
        self.track_pressed = [False] * 6
        self.last_blue = [0] * 6
        self.frame_count = 0
        self.press_count = 0
        self.touch_device = "尚未初始化"
        self.total_loop_ms = 0.0
        self.last_capture_ms = 0
        self.last_decode_ms = 0
        self.last_fps = 0.0
        self.last_touch_ms = 0.0
        self.last_decision_to_write_ms = 0.0

    def start(self):
        self.stop()
        self.track_pressed = [False] * 6
        self.frame_count = 0
        self.press_count = 0
        self.total_loop_ms = 0.0
        self.last_capture_ms = 0
        self.last_decode_ms = 0
        self.last_fps = 0.0
        self.last_touch_ms = 0.0
        self.last_decision_to_write_ms = 0.0
        self.diagnostic = "正在初始化 Root 多点触控"
        self.touch_device = str(self.host.initializeMultiTouch(
            self.LOGICAL_TOUCH_WIDTH, self.LOGICAL_TOUCH_HEIGHT))
        self.running = True
        self.state = "音游运行中"
        self.diagnostic = "六轨识别已启动；触控设备：" + self.touch_device
        self._log(self.diagnostic)

    def step(self):
        if not self.running:
            return
        try:
            import time
            loop_start = time.perf_counter()
            result = self.host.captureBlueValues(self.detect_xs, self.detect_y)
            self.last_capture_ms = int(_map_get(result, "captureMs", 0))
            self.last_decode_ms = int(_map_get(result, "decodeMs", 0))
            blue = list(_as_sequence(_map_get(result, "blue", [])))
            if len(blue) != 6:
                raise RuntimeError("颜色采样返回值数量不是六条轨道")
            self.last_blue = [int(v) for v in blue]
            self.frame_count += 1
            # Keep every original detection/touch coordinate unchanged. Compute all
            # six lane transitions first, then send them in ONE Root pipe write and
            # ONE SYN_REPORT to reduce Python/Java crossings and input batching jitter.
            desired = [1 if value < self.BLUE_THRESHOLD else 0 for value in self.last_blue]
            changed = any(bool(desired[i]) != self.track_pressed[i] for i in range(6))
            if changed:
                touch_start = time.perf_counter()
                states = [int(v) for v in desired]
                touch_xs = [self.touch_x] * 6
                touch_ys = list(self.touch_ys)
                java_ms = self.host.updateTouches(states, touch_xs, touch_ys)
                self.last_touch_ms = float(java_ms)
                self.last_decision_to_write_ms = (time.perf_counter() - touch_start) * 1000.0
                self.press_count += sum(1 for i in range(6)
                                        if desired[i] and not self.track_pressed[i])
                self.track_pressed = [bool(v) for v in desired]
            else:
                self.last_touch_ms = 0.0
                self.last_decision_to_write_ms = 0.0
            loop_ms = (time.perf_counter() - loop_start) * 1000.0
            self.total_loop_ms += loop_ms
            avg_ms = self.total_loop_ms / max(1, self.frame_count)
            self.last_fps = 1000.0 / max(0.1, avg_ms)
            self.diagnostic = (
                "帧%d 触发%d %.1fFPS\n原始帧%dms CV处理%dms 循环%.1fms\n输入批次%.1fms Java写入%dms\n蓝值:%s" %
                (self.frame_count, self.press_count, self.last_fps,
                 self.last_capture_ms, self.last_decode_ms, loop_ms,
                 self.last_decision_to_write_ms, int(self.last_touch_ms),
                 "/".join(map(str, self.last_blue)))
            )
        except Exception as exc:
            self._release_all()
            self.running = False
            self.state = "音游运行异常"
            self.diagnostic = str(exc)
            self._log("音游引擎异常：" + self.diagnostic)
            raise

    def stop(self):
        self.running = False
        self._release_all()
        self.state = "音游已停止"

    def _release_all(self):
        try:
            release = getattr(self.host, "releaseAllTouches", None)
            if callable(release):
                release()
            else:
                for i, pressed in enumerate(self.track_pressed):
                    if pressed:
                        self.host.touchUp(i)
        except Exception as exc:
            self._log("释放触点时发生异常：" + str(exc))
        self.track_pressed = [False] * 6

    def _log(self, message):
        try:
            self.host.log(message)
        except Exception:
            pass
