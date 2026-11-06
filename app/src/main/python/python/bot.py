import random
import time
from .config import *
from .coordinate import CoordinateMapper
from .frame import Frame


class GameBot:
    """V0.9 migration engine. Original scene keywords and game coordinates retained."""

    def __init__(self, host):
        self.host = host
        self.running = False
        self.state = "待命"
        self.diagnostic = "等待识别"
        self.mapper = None
        self.jump_coords = None
        self.jump_appear_ms = None
        self.jump_clicked = False
        self.flight_lost_count = 0
        self.is_sprinting = False
        self.need_reclick_run = False
        self.last_signal_exists = False
        self.signal_lost_start_ms = None
        self.continue_first_seen_ms = None
        self.next_extra_click_ms = None
        self.next_search_swipe_ms = 0
        self.rng = random.Random()
        # 只有连续 5 秒未命中原脚本场景关键词时才保存 OCR 诊断。
        self.unknown_since_ms = None
        self.unknown_last_sample_ms = 0
        self.unknown_samples = []
        self.unknown_log_saved = False

    def start(self):
        # 每次点击启动都重新识别当前画面；绝不默认大厅。
        self._reset()
        self.rng = random.Random()
        self.unknown_since_ms = None
        self.unknown_last_sample_ms = 0
        self.unknown_samples = []
        self.unknown_log_saved = False
        self.diagnostic = "正在获取首帧"
        self.running = True
        self.state = "识别中"
        self._log("挂机启动：开始识别当前游戏场景")

    def stop(self):
        self.running = False
        self.state = "已暂停"
        self.is_sprinting = False
        self._log("挂机已暂停")

    def step(self):
        if not self.running:
            return

        now = int(time.monotonic() * 1000)
        frame = Frame.from_host(self.host.capture())
        self.mapper = CoordinateMapper(frame.width, frame.height)
        # 原脚本全局高优先级文案：即使当前场景尚未识别，也先处理弹窗。
        for word in HIGH_PRIORITY_TEXTS:
            item = frame.find(word)
            if item:
                self._tap_screen(item.center_x, item.center_y)
                self._log("高优先级点击：" + word)
                return

        # 初始识别期间不执行常规游戏点击，避免未知画面误点大厅按钮。
        scene = self.detect_scene(frame)
        related = scene is not None or frame.has("继续") or frame.has("确定") \
            or frame.has("放弃") or frame.has("忽略") or frame.has("重试") \
            or frame.has("开始游戏")
        if not related:
            self._track_unknown_scene(frame, now)
        else:
            self._reset_unknown_scene()
            self.diagnostic = "已识别场景：" + scene if scene else "检测到相关游戏文字"

        if self.state in ("待命", "识别中"):
            if scene is None:
                return
            self._adopt(scene)
            self._log("启动场景识别成功：" + scene)

        if scene == "大厅":
            self._enter("大厅")
            self._tap_text(frame, "开始游戏")
            return

        if scene == "跳伞" and self.state not in ("等待跳伞", "飞行中"):
            self._enter("等待跳伞")
            self.jump_coords = None
            self.jump_appear_ms = None
            self.jump_clicked = False
        if scene == "飞行中" and self.state != "飞行中":
            self._enter("飞行中")
            self.jump_clicked = True
        if scene == "结算过渡":
            self._enter("结算过渡")

        if self.state in ("等待跳伞", "飞行中"):
            self._flight(frame, now)
            return
        if self.state == "结算过渡":
            if self._tap_text(frame, "继续"):
                return
            if self._tap_text(frame, "返回大厅"):
                self._reset()
                self._enter("大厅")
            return
        if self.state == "大厅":
            self._tap_text(frame, "开始游戏")
            return
        if self.state in ("战斗中奔跑", "战斗中寻敌"):
            self._battle(frame, now)

    def _track_unknown_scene(self, frame, now):
        """Buffer OCR snapshots; append one file section after 5s without scene keywords."""
        if self.unknown_since_ms is None:
            self.unknown_since_ms = now
            self.unknown_last_sample_ms = 0
            self.unknown_samples = []
            self.unknown_log_saved = False

        elapsed = now - self.unknown_since_ms
        # Keep one sample per second to make the diagnostic compact but useful.
        if elapsed - self.unknown_last_sample_ms >= 1000 or not self.unknown_samples:
            stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
            lines = [f"[{stamp} +{elapsed}ms] screen={frame.width}x{frame.height}"]
            if frame.texts:
                for item in frame.texts:
                    text = (item.text or "").replace("\n", " ").strip()
                    if text:
                        lines.append(
                            f"  text={text} bounds=({item.left:.0f},{item.top:.0f},"
                            f"{item.right:.0f},{item.bottom:.0f})"
                        )
            else:
                lines.append("  （本帧 OCR 没有返回任何文字块）")
            self.unknown_samples.append("\n".join(lines))
            self.unknown_samples = self.unknown_samples[-8:]
            self.unknown_last_sample_ms = elapsed

        self.diagnostic = "未命中场景关键词；连续计时 " + str(elapsed // 1000) + "/5 秒"
        if elapsed >= 5000 and not self.unknown_log_saved:
            payload = (
                "========== OCR 未识别场景诊断 =========="
                "\n连续未命中场景关键词：5 秒以上"
                "\n说明：这是 OCR 原始文字块采样，不代表游戏一定不在战斗中。"
                "\n" + "\n".join(self.unknown_samples)
            )
            saver = getattr(self.host, "saveOcrLog", None)
            if callable(saver):
                try:
                    path = str(saver(payload))
                    self.diagnostic = "OCR未识别日志已保存（文件管理器查看）"
                    self._log(self.diagnostic)
                    self.unknown_log_saved = True
                except Exception as exc:
                    self.diagnostic = "OCR日志保存失败：" + str(exc)
                    self._log(self.diagnostic)
            else:
                self.diagnostic = "宿主未提供 OCR 日志保存接口"
                self._log(self.diagnostic)

    def _reset_unknown_scene(self):
        self.unknown_since_ms = None
        self.unknown_last_sample_ms = 0
        self.unknown_samples = []
        self.unknown_log_saved = False

    def detect_scene(self, f):
        # 保留原始 609 行脚本 detectScene() 的场景关键词与优先顺序。
        t = f.full_text
        if "离开" in t or "舱内人数" in t:
            return "跳伞"
        if "请勿在现实中模仿" in t:
            return "飞行中"
        if "淘汰详情" in t or "返回大厅" in t:
            return "结算过渡"
        if "信号区" in t:
            return "战斗"
        if "剩余" in t:
            return "战斗"
        if "特惠" in t and "开始游戏" in t:
            return "大厅"
        return None

    def _adopt(self, scene):
        mapping = {
            "跳伞": "等待跳伞",
            "飞行中": "飞行中",
            "战斗": "战斗中寻敌",
            "结算过渡": "结算过渡",
            "大厅": "大厅",
        }
        state = mapping.get(scene)
        if state is None:
            self._enter("识别中")
            return
        self._enter(state)
        if scene == "飞行中":
            self.jump_clicked = True

    def _flight(self, f, now):
        if not self.jump_clicked:
            item = f.find("离开")
            if item and self.jump_coords is None:
                self.jump_coords = (item.center_x, item.center_y)
                self.jump_appear_ms = now
            if self.jump_coords and now - self.jump_appear_ms >= JUMP_WAIT_MS:
                self._tap_screen(*self.jump_coords)
                self.jump_clicked = True
                self.jump_coords = None
                self._enter("飞行中")
            return
        if f.has("请勿在现实中模仿"):
            self.flight_lost_count = 0
            return
        self.flight_lost_count += 1
        if self.flight_lost_count >= FLIGHT_LOST_THRESHOLD:
            self.flight_lost_count = 0
            self.jump_clicked = False
            self.need_reclick_run = True
            self._enter("战斗中寻敌")

    def _battle(self, f, now):
        if f.has("淘汰详情") or f.has("返回大厅"):
            self._enter("结算过渡")
            return
        if f.has("继续"):
            if self.continue_first_seen_ms is None:
                self.continue_first_seen_ms = now
            elif now - self.continue_first_seen_ms >= CONTINUE_CONFIRM_MS:
                self._enter("结算过渡")
                return
        else:
            self.continue_first_seen_ms = None

        signal = f.find("信号区")
        exists = signal is not None
        if exists and not self.last_signal_exists:
            self.need_reclick_run = True
            self.signal_lost_start_ms = None
        self.last_signal_exists = exists

        if exists:
            self._enter("战斗中奔跑")
            self.signal_lost_start_ms = None
            if not self.is_sprinting or self.need_reclick_run:
                self._tap_ref(*RUN_BUTTON)
                self.is_sprinting = True
                self.need_reclick_run = False
                self.next_extra_click_ms = now + self.rng.randint(
                    EXTRA_CLICK_MIN_MS, EXTRA_CLICK_MAX_MS)
            elif self.next_extra_click_ms is not None and now >= self.next_extra_click_ms:
                self._tap_ref(*EXTRA_BUTTON)
                self.next_extra_click_ms = now + self.rng.randint(
                    EXTRA_CLICK_MIN_MS, EXTRA_CLICK_MAX_MS)
            sx, _ = self.mapper.to_reference(signal.center_x, signal.center_y)
            dx = REFERENCE_WIDTH / 2 - sx
            if abs(dx) > DEAD_ZONE_REFERENCE_PX:
                x1, y = REFERENCE_WIDTH * .75, REFERENCE_HEIGHT / 2
                self._swipe_ref(x1, y, max(0, min(REFERENCE_WIDTH, x1 - dx * .3)), y, 200)
            return

        if self.is_sprinting:
            if self.signal_lost_start_ms is None:
                self.signal_lost_start_ms = now
            elif now - self.signal_lost_start_ms >= SIGNAL_WAIT_MS:
                self._tap_ref(*RUN_BUTTON)
                self.is_sprinting = False
                self.signal_lost_start_ms = None
                self._enter("战斗中寻敌")
            return

        self._enter("战斗中寻敌")
        if now >= self.next_search_swipe_ms:
            self._swipe_ref(960, 360, 660, 360, SEARCH_SWIPE_DURATION_MS)
            self.next_search_swipe_ms = now + SEARCH_SWIPE_DURATION_MS + SEARCH_SWIPE_PAUSE_MS

    def _reset(self):
        self.jump_coords = self.jump_appear_ms = None
        self.jump_clicked = False
        self.flight_lost_count = 0
        self.is_sprinting = False
        self.need_reclick_run = False
        self.last_signal_exists = False
        self.signal_lost_start_ms = self.continue_first_seen_ms = None
        self.next_extra_click_ms = None
        self.next_search_swipe_ms = 0

    def _tap_text(self, f, word):
        item = f.find(word)
        if not item:
            return False
        self._tap_screen(item.center_x, item.center_y)
        return True

    def _tap_screen(self, x, y):
        x = max(0, min(self.mapper.screen_width - 1, round(x)))
        y = max(0, min(self.mapper.screen_height - 1, round(y)))
        self.host.tap(x, y)

    def _tap_ref(self, x, y):
        self.mapper.tap(self.host, x, y)

    def _swipe_ref(self, x1, y1, x2, y2, duration):
        self.mapper.swipe(self.host, x1, y1, x2, y2, duration)

    def _enter(self, state):
        if self.state != state:
            self.state = state
            self._log("状态：" + state)

    def _log(self, msg):
        logger = getattr(self.host, "log", None)
        if callable(logger):
            logger(msg)
