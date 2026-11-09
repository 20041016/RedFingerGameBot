"""千音雅集专辑自动补难度脚本。

本模块只做“页面层”的事情：识别目录/难度/结算页、按配置选难度、开始演奏、
判定完成率、重试、切下一首、异常邮件。真正打歌完全复用已有的六轨打歌模块
``MusicBot``（屏幕采样 + Root 多点触控），不复制、不修改它的内部逻辑。

页面推进方式遵循规格里的“点击后等待下一个目标出现”：每次 step() 只做一次
截图识别 + 一个动作，动作之后不睡眠等固定时间，而是回到 step() 重新识别目标，
目标出现才继续。只有打歌阶段是阻塞的紧循环（由 MusicBot 的帧流驱动）。

退出检测说明：打歌期间不能持续跑 OCR，否则会漏音符。这里用“按键事件静音”
（连续 N 毫秒没有新的按键）作为候选时刻，才做一次 OCR 确认是否出现
“演奏完成率”。若只是歌曲间隙，OCR 结束后立刻恢复打歌。
"""

import re
import time

from .coordinate import CoordinateMapper
from .frame import Frame
from .music_bot import MusicBot


class AlbumBot:
    # ---------- 规格固定项 ----------
    # 补难度优先级：传说 > 大师 > 困难 > 普通
    DIFFICULTY_ORDER = ("legend", "master", "hard", "normal")
    # 难度选择页从上到下的显示顺序（仅用于按 Y 坐标把“大音天籁”映射到难度）
    DISPLAY_ORDER = ("normal", "hard", "master", "legend")
    DIFFICULTY_LABELS = {
        "legend": "传说",
        "master": "大师",
        "hard": "困难",
        "normal": "普通",
    }
    RATING_ALIASES = ("大音天籁", "大音天籟")
    # 难度名只在“前往演奏”之后的难度卡片页出现；曲目页那四行只是评级展示。
    DIFFICULTY_ALIASES = {
        "normal": ("普通",),
        "hard": ("困难", "困難"),
        "master": ("大师", "大師"),
        "legend": ("传说", "傳說"),
    }
    TITLE_ALIASES = ("千音雅集",)
    TEXT_ENTER_PLAY = "前往演奏"
    TEXT_START_PLAY = "开始演奏"
    TEXT_SELECT_TRACK = ("选择乐曲", "选择曲目")
    TEXT_RETRY_PLAY = "再次演奏"
    TEXT_RESULT_MARK = "演奏完成率"

    # 只自动点击“重试/确定”这类明确按钮，绝不点“取消/放弃”，避免误退关卡。
    POPUP_WORDS = ("网络异常", "网络错误", "连接失败", "连接超时", "加载失败",
                   "服务器繁忙", "数据异常", "系统提示", "重新连接", "连接服务器",
                   "即将演奏结束")
    POPUP_BUTTONS = ("重新连接服务器", "重新连接", "重新加载", "重试", "确定", "确认",
                     "知道了", "关闭")

    MODE_LEGEND_ONLY = 0
    MODE_ALL_DIFFICULTIES = 1

    # ---------- 配置默认值（与 RootHost.getAlbumConfig 的顺序一致） ----------
    CONFIG_DEFAULTS = (-1, -1, -1, -1, MODE_LEGEND_ONLY, 12, 90_000, 5_000, 30_000, 1)
    TARGET_RATE = 100.0
    RATE_EPSILON = 0.05          # 显示四舍五入容差：>= 99.95 视为 100.0%
    MAX_SONG_MS = 8 * 60 * 1000  # 单次演奏硬上限（防止结算页永远不出现）
    # 开局这段时间绝不做 OCR：加载画面上的杂色会被当成“音符”，
    # 一旦据此暂停录屏流复核，就会漏掉开头音符（本游戏“不容错”直接判 0%）。
    MIN_PLAY_SECONDS = 20.0
    QUEUE_INTERVAL_MS = 300      # 页面态的外层轮询间隔
    PLAY_INTERVAL_MS = 5         # 打歌态由帧流驱动，不额外 sleep

    # ---------- 状态 ----------
    ST_START = "启动检查"
    ST_ENTER = "进入演奏"
    ST_DIFFICULTY = "识别难度"
    ST_START_BUTTON = "等待开始演奏"
    ST_PLAYING = "演奏中"
    ST_SETTLEMENT = "结算判定"
    ST_AFTER_SELECT = "返回目录"
    ST_NEXT = "切换下一首"
    ST_DONE = "全部完成"
    ST_STOPPED = "已停止"

    def __init__(self, host):
        self.host = host
        self._music = MusicBot(host)
        self._mapper = None
        self._load_config()

        self.running = False
        self.state = "专辑待命"
        self.diagnostic = "等待启动"
        self.loop_interval_ms = self.QUEUE_INTERVAL_MS

        self._song_index = 1
        self._song_done = set()
        self._target = None
        self._retry_in_song = 0
        self._total_retries = 0
        self._completed_difficulties = 0
        self._state_since = time.monotonic()
        self._capture_failures = 0
        self._error_count = 0
        self._last_alert_signature = ""
        self._last_alert_at = 0.0
        self._recent_logs = []
        self._last_frame = None
        self._last_tap_at = {}
        self._return_stuck_at = None
        self._no_note_alerted = False
        self._interrupts_in_song = 0
        self._last_overlay_bounds = None
        self._overlay_drop_logged = False

    # ------------------------------------------------------------------ 配置
    def _load_config(self):
        values = list(self.host.getAlbumConfig())
        if len(values) < len(self.CONFIG_DEFAULTS):
            values = list(self.CONFIG_DEFAULTS)
        self._next_song = (int(values[0]), int(values[1]))
        self._done_return = (int(values[2]), int(values[3]))
        self._mode = self.MODE_ALL_DIFFICULTIES if int(values[4]) == 1 else self.MODE_LEGEND_ONLY
        self._song_count = max(1, int(values[5]))
        self._page_timeout_ms = max(10_000, int(values[6]))
        self._quiet_ms = max(1_500, int(values[7]))
        self._first_note_grace_ms = max(10_000, int(values[8]))
        self._send_complete_email = int(values[9]) != 0

    def _coordinates_ready(self):
        # 旧版本用 0,0 表示“还没记录”，这里一并按未设置处理。
        for x, y in (self._next_song, self._done_return):
            if x <= 0 or y <= 0:
                return False
        return True

    def _mode_name(self):
        return "只补传说" if self._mode == self.MODE_LEGEND_ONLY else "四难度全补"

    # ------------------------------------------------------------------ 生命周期
    def start(self):
        self.running = False
        self.state = self.ST_START
        self.diagnostic = "正在识别“千音雅集”目录"
        self._load_config()
        self._song_index = 1
        self._song_done = set()
        self._target = None
        self._retry_in_song = 0
        self._total_retries = 0
        self._completed_difficulties = 0
        self._capture_failures = 0
        self._error_count = 0
        self._music = MusicBot(self.host)

        if not self._coordinates_ready():
            self.state = "未启动"
            self.diagnostic = "请先在“坐标设置”中记录“下一首”和“已完成返回”两个坐标"
            self._log(self.diagnostic)
            return

        checker = getattr(self.host, "isAlbumAlertConfigured", None)
        if callable(checker):
            try:
                if not checker():
                    self._log("邮件提醒未配置：出现异常时只写日志，不会发邮件")
            except Exception:
                pass

        # 规格 4.1：启动时只检查一次“千音雅集”，识别不到直接停止。
        frame = self._capture()
        if frame is None or self._find(frame, self.TITLE_ALIASES) is None:
            detail = "启动时未识别到“千音雅集”，脚本不启动"
            if frame is not None:
                detail += "（截图 %dx%d，本帧文字 %d 条：%s）" % (
                    frame.width, frame.height, len(frame.texts),
                    self._screen_dump(frame, 30))
            else:
                detail += "（本帧截图/OCR 失败）"
            dumper = getattr(self.host, "saveDebugFrame", None)
            if callable(dumper):
                try:
                    path = str(dumper("album_start") or "")
                    if path:
                        detail += "；诊断帧：" + path
                except Exception as exc:
                    detail += "；诊断帧保存失败：" + str(exc)
            self._log(detail)
            self.state = self.ST_STOPPED
            self.diagnostic = "启动时未识别到“千音雅集”，脚本不启动"
            return

        self.running = True
        self._enter(self.ST_ENTER, "已确认目录，开始处理第 1/%d 首" % self._song_count)
        self._log("专辑脚本启动：%s，目标完成率 %.1f%%"
                  % (self._mode_name(), self.TARGET_RATE))

    def stop(self):
        self.running = False
        self._release_touches()
        if self.state not in (self.ST_DONE, self.ST_STOPPED, "未启动"):
            self.state = self.ST_STOPPED
            self.diagnostic = "脚本已停止"

    def step(self):
        if not self.running:
            return
        self.loop_interval_ms = self.QUEUE_INTERVAL_MS
        try:
            if self._stop_requested():
                self._log("收到停止请求，结束专辑脚本")
                self.state = self.ST_STOPPED
                self.diagnostic = "收到停止请求"
                self.running = False
                self._release_touches()
                return
            handler = {
                self.ST_ENTER: self._step_enter,
                self.ST_DIFFICULTY: self._step_difficulty,
                self.ST_START_BUTTON: self._step_start_button,
                self.ST_AFTER_SELECT: self._step_after_select,
                self.ST_NEXT: self._step_next_song,
                self.ST_SETTLEMENT: self._step_settlement_page,
            }.get(self.state)
            if handler is None:
                self._fatal("内部状态异常：" + str(self.state))
                return
            handler()
        except Exception as exc:  # 页面/OCR 抖不动时不要把整条视频流程打死
            self._error_count += 1
            self.diagnostic = "异常（%d/3）：%s" % (self._error_count, exc)
            self._log("专辑脚本异常：" + repr(exc))
            if self._error_count >= 3:
                self._fatal("连续异常，已停止：%s" % exc)

    # ------------------------------------------------------------------ 页面步骤
    def _step_enter(self):
        """“千音雅集”曲目页：这四行只是评级展示，点它不会选难度，直接进难度卡片页。"""
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            self._play_and_settle(frame)
            return
        kind = self._page_kind(frame)
        if kind == "cards":
            # 刚点过“已完成返回”，页面还在切换；真卡住就再点一次返回，绝不在这儿重选难度。
            if self._state_elapsed_ms() >= 1500 and self._may_tap("done_return", 1500, frame):
                self._tap_point(self._done_return, "已完成返回")
            self.diagnostic = self._progress_text("等待回到曲目页")
            return
        if kind != "catalog":
            self.loop_interval_ms = 400
            self._wait_or_alert("“千音雅集”曲目页", frame)
            return

        item = self._find(frame, (self.TEXT_ENTER_PLAY,))
        if item is None:
            self.loop_interval_ms = 400
            self._wait_or_alert("“前往演奏”按钮", frame)
            return
        if not self._may_tap("enter_play", 1500, frame):
            self.diagnostic = self._progress_text("等待“前往演奏”可点击")
            return
        self._tap_item(item, "前往演奏")
        self._enter(self.ST_DIFFICULTY, "已点击“前往演奏”，等待难度卡片页")

    def _pick_target(self, done):
        """按难度卡片页读到的评级决定要补的难度；返回 None 表示本首已达标。"""
        done = {key: bool(done.get(key)) or key in self._song_done
                for key in self.DIFFICULTY_ORDER}
        self._log("难度状态：" + "、".join(
            "%s=%s" % (self.DIFFICULTY_LABELS[key],
                       "大音天籁" if done[key] else "未达标")
            for key in self.DISPLAY_ORDER))
        self.diagnostic = self._progress_text(
            "已达标 " + "/".join("1" if done[k] else "0" for k in self.DISPLAY_ORDER))
        if self._mode == self.MODE_LEGEND_ONLY:
            return None if done["legend"] else "legend"
        for key in self.DIFFICULTY_ORDER:
            if not done[key]:
                return key
        return None

    def _step_difficulty(self):
        """难度卡片页（点“前往演奏”之后）：卡片上写着“普通/困难/大师/传说”，点目标卡片。"""
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            self._play_and_settle(frame)
            return
        kind = self._page_kind(frame)
        if kind == "catalog":
            # 还停在曲目页：再点一次“前往演奏”。
            enter = self._find(frame, (self.TEXT_ENTER_PLAY,))
            if enter is not None and self._may_tap("enter_play", 1500, frame):
                self._tap_item(enter, "前往演奏")
                self.diagnostic = self._progress_text("已点击“前往演奏”，等待难度卡片页")
            else:
                self.diagnostic = self._progress_text("等待难度卡片页")
            return
        if kind != "cards":
            self._wait_or_alert("难度卡片页", frame)
            return
        done, frame = self._read_card_done_stable(frame)
        if done is None:
            # 卡片名/评级条都没认全，再等等（不做猜测，避免打错难度）。
            self._wait_or_alert("难度卡片上的评级", frame)
            return
        target = self._pick_target(done)
        if target is None:
            self._advance_song("本首已无需要补的难度")
            return
        self._target = target
        label = self.DIFFICULTY_LABELS[target]
        if not self._may_tap("difficulty_card", 1200, frame):
            self.diagnostic = self._progress_text("等待难度卡片稳定")
            return
        x, y, estimated = self._difficulty_card_point(frame, target)
        if estimated:
            # 卡片名被 OCR 漏掉时按网格外推；把认到的名字打进日志便于排查。
            names = [item.text for item in self._difficulty_card_items(frame).values()]
            self._log("难度卡片名只认出：%s，按卡片网格估算“%s”位置"
                      % ("、".join(names) or "无", label))
        self._tap_xy(x, y, "难度·%s%s" % (label, "（按卡片位置）" if estimated else ""))
        self._enter(self.ST_START_BUTTON, "已选择“%s”难度，等待“开始演奏”" % label)

    def _step_start_button(self):
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            self._play_and_settle(frame)
            return
        item = self._find(frame, (self.TEXT_START_PLAY,))
        if item is not None:
            self._tap_item(item, "开始演奏")
            self._enter(self.ST_PLAYING, "已点击“开始演奏”，调用打歌模块")
            self._play_and_settle()
            return
        kind = self._page_kind(frame)
        if kind == "catalog":
            self._enter(self.ST_ENTER, "已退回曲目页，重新读评级")
            return
        if kind == "cards" and self._target and self._state_elapsed_ms() >= 1200:
            # 还停在卡片页又找不到“开始演奏”：回去再确认一次难度卡片是否选中。
            self._enter(self.ST_DIFFICULTY, "未看到“开始演奏”，重新确认难度卡片")
            return
        self._wait_or_alert("“开始演奏”按钮", frame)

    def _step_after_select(self):
        """点击“选择曲目”后等待回到“千音雅集”目录。"""
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        kind = self._page_kind(frame)
        if kind == "settlement":
            # 刚点过“选择曲目”，给页面一点时间关闭；超过 2.5 秒还停在结算页才重新处理。
            if self._may_tap("select_track", 2500, frame):
                self._play_and_settle(frame)
            else:
                self.diagnostic = "等待结算页关闭"
            return
        if kind == "cards":
            if self._target:
                self._enter(self.ST_DIFFICULTY, "已回到难度卡片页，继续选难度")
            else:
                self._advance_song("已离开结算页")
            return
        if kind == "catalog":
            self._enter(self.ST_ENTER, "已回到曲目页，重新读评级决定下一步")
            return
        self._wait_or_alert("“千音雅集”目录", frame)

    def _step_next_song(self):
        """规格 4.2：点击下一首之前必须全屏识别到“千音雅集”。"""
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        if self._find(frame, self.TITLE_ALIASES) is None:
            if self._page_kind(frame) == "cards":
                # 上一首结束时若还停在难度卡片页，先按“已完成返回”回目录。
                if self._may_tap("done_return", 1500, frame):
                    self._tap_point(self._done_return, "已完成返回")
                    self._enter(self.ST_NEXT, "停留在难度卡片页，先返回目录")
                # 坐标录错时这里会一直点不动，90 秒提醒一次（仍然不点下一首）。
                if self._return_stuck_at is None:
                    self._return_stuck_at = time.monotonic()
                elif time.monotonic() - self._return_stuck_at >= 90:
                    self._return_stuck_at = time.monotonic()
                    self._alert("返回目录按钮可能未生效",
                                "在难度选择页反复点击“已完成返回”仍未回到目录，"
                                "请检查该固定坐标。\n" + self._context(frame))
                return
            # 未识别到目录属于“等待”，不点击、不发邮件、无限等（规格 4.2）。
            self.loop_interval_ms = 500
            self.diagnostic = self._progress_text(
                "等待目录出现（已等待 %d 秒，不会点击下一首）"
                % (self._state_elapsed_ms() // 1000))
            return
        if self._song_index >= self._song_count:
            self._finish_all()
            return
        self._return_stuck_at = None
        self._tap_point(self._next_song, "下一首")
        self._song_index += 1
        self._song_done = set()
        self._target = None
        self._retry_in_song = 0
        self._interrupts_in_song = 0
        self._enter(self.ST_ENTER,
                    "已切换至第 %d/%d 首，等待“前往演奏”"
                    % (self._song_index, self._song_count))

    def _step_settlement_page(self):
        frame = self._capture()
        if frame is None:
            return
        if self._handle_popup(frame):
            return
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            self._play_and_settle(frame)
            return
        self._wait_or_alert("结算页“演奏完成率”", frame)

    # ------------------------------------------------------------------ 难度
    # ---------- 难度卡片页（点“前往演奏”之后） ----------
    # 实测 3200x1440：卡片上难度名的中心在 0.321 屏高，四张卡片 X 中心如下。
    # 名字被 OCR 漏掉时按这个网格估算，仍在同一张卡片内。
    CARD_NAME_Y_FRACTION = 0.321
    CARD_NAME_X_FRACTIONS = (0.264, 0.416, 0.568, 0.741)

    @staticmethod
    def _fit_grid(points):
        """按 (序号, 坐标) 做最小二乘拟合，返回 4 个序号对应的坐标。"""
        count = len(points)
        mean_index = sum(point[0] for point in points) / count
        mean_value = sum(point[1] for point in points) / count
        denominator = sum((point[0] - mean_index) ** 2 for point in points)
        slope = (sum((point[0] - mean_index) * (point[1] - mean_value) for point in points)
                 / denominator if denominator else 0.0)
        intercept = mean_value - slope * mean_index
        return [intercept + slope * index for index in range(4)]

    def _find_card_name(self, frame, key):
        """只认 2~3 个字的难度名，避免把“大音天籁”里的字当成难度名。"""
        aliases = self.DIFFICULTY_ALIASES[key]
        loose = None
        for item in frame.texts:
            text = self._compact(item.text)
            if not text or len(text) > 3:
                continue
            if text in aliases:
                return item
            if loose is None and any(alias in text for alias in aliases):
                loose = item
        return loose

    def _difficulty_card_items(self, frame):
        found = {}
        for index, key in enumerate(self.DISPLAY_ORDER):
            item = self._find_card_name(frame, key)
            if item is not None:
                found[index] = item
        return found

    def _difficulty_card_point(self, frame, key):
        """返回 (x, y, 是否按卡片网格估算)；优先点卡片上的难度名。"""
        index = self.DISPLAY_ORDER.index(key)
        found = self._difficulty_card_items(frame)
        if index in found:
            return found[index].center_x, found[index].center_y, False
        if len(found) >= 2:
            xs = self._fit_grid(sorted((i, item.center_x) for i, item in found.items()))
            ys = sorted(item.center_y for item in found.values())
            return xs[index], ys[len(ys) // 2], True
        return (self.CARD_NAME_X_FRACTIONS[index] * frame.width,
                self.CARD_NAME_Y_FRACTION * frame.height, True)

    # 卡片下方评级条（暂无评级/大音天籁）实测在 0.61 屏高附近。
    CARD_RATING_Y_RANGE = (0.50, 0.72)

    def _rating_items(self, frame):
        """带“大音天籁”字样的评级标签（曲目页和卡片页都用它表示已达标）。"""
        return [item for item in frame.texts
                if self._matches(item.text, self.RATING_ALIASES)]

    def _card_shows_done(self, frame, key):
        """难度卡片页：目标卡片上是否已经写着“大音天籁”。"""
        index = self.DISPLAY_ORDER.index(key)
        found = self._difficulty_card_items(frame)
        center_x = (found[index].center_x if index in found
                    else self.CARD_NAME_X_FRACTIONS[index] * frame.width)
        half = 0.06 * frame.width
        top, bottom = (fraction * frame.height for fraction in self.CARD_RATING_Y_RANGE)
        for item in self._rating_items(frame):
            if abs(item.center_x - center_x) <= half and top <= item.center_y <= bottom:
                return True
        return False

    def _read_card_done(self, frame):
        """返回 {难度: 是否“大音天籁”}；卡片名都没认出来时返回 None。"""
        if len(self._difficulty_card_items(frame)) < 2:
            return None
        return {key: self._card_shows_done(frame, key) for key in self.DISPLAY_ORDER}

    def _read_card_done_stable(self, frame, attempts=3):
        """多帧取并集：某张卡的评级文字偶尔漏识别，别因此把已达标难度重打一遍。"""
        merged = self._read_card_done(frame)
        last = frame
        for _ in range(max(0, attempts - 1)):
            if merged and all(merged.get(key) for key in self.DIFFICULTY_ORDER):
                break
            time.sleep(0.5)
            retry = self._capture()
            if retry is None:
                continue
            last = retry
            part = self._read_card_done(retry)
            if not part:
                continue
            merged = {key: bool((merged or {}).get(key)) or bool(part.get(key))
                      for key in self.DISPLAY_ORDER}
        if not merged or len(merged) < 4:
            return None, last
        return merged, last

    def _back_to_catalog(self, reason):
        """从难度卡片页退回曲目页，重新读评级（不切歌）。"""
        self._may_tap("done_return", 0, self._last_frame)
        self._tap_point(self._done_return, "已完成返回")
        self._enter(self.ST_ENTER, reason + "，返回曲目页重新读评级")

    def _advance_song(self, reason):
        """本首不用再打：已经在目录页就直接切下一首，否则先按“已完成返回”。"""
        frame = self._last_frame
        if frame is not None and self._page_kind(frame) == "catalog":
            self._enter(self.ST_NEXT, reason + "，直接切换下一首")
            return
        self._may_tap("done_return", 0, frame)  # 记下这次点击，避免页面未切走时又补一刀
        self._tap_point(self._done_return, "已完成返回")
        self._enter(self.ST_NEXT, reason + "，返回目录后切换下一首")

    # ------------------------------------------------------------------ 打歌 + 结算
    def _play_and_settle(self, frame=None):
        """打歌 -> 结算 -> 未达标就再次演奏，直到 100.0%。"""
        while True:
            if self._stop_requested():
                self.state = self.ST_STOPPED
                self.diagnostic = "收到停止请求"
                self.running = False
                self._release_touches()
                return
            if frame is None:
                result = self._run_song()
                kind = result[0]
                if kind == "stop":
                    self.state = self.ST_STOPPED
                    self.diagnostic = "收到停止请求"
                    self.running = False
                    self._release_touches()
                    return
                if kind == "not_started":
                    self._enter(self.ST_START_BUTTON,
                                "未检测到演奏开始，重新等待“开始演奏”")
                    return
                if kind == "interrupted":
                    self._interrupts_in_song += 1
                    self._log("演奏被打断（第 %d 次）：%s"
                              % (self._interrupts_in_song, result[1]))
                    self._alert("演奏被打断", result[1] + "\n"
                                + self._context(self._last_frame))
                    if self._interrupts_in_song >= 3:
                        self._fatal("同一首连续 3 次被断线/弹窗打断，已停止")
                        return
                    self._enter(self.ST_ENTER, "演奏被打断，回到曲目页重试")
                    return
                if kind == "error":
                    self._fatal(result[1], self._last_frame)
                    return
                frame = result[1]

            rate, frame = self._read_rate_with_retry(frame)
            if rate is None:
                self._fatal("结算页已出现，但读不到“演奏完成率”的数值", frame)
                return

            if rate >= self.TARGET_RATE - self.RATE_EPSILON:
                self._on_song_cleared(rate, frame)
                return

            self._retry_in_song += 1
            self._total_retries += 1
            self._enter(self.ST_SETTLEMENT,
                        self._progress_text("完成率 %.1f%%，未达 100.0%%，再次演奏"
                                            % rate))
            self._log("第 %d 首 %s 完成率 %.1f%%，第 %d 次“再次演奏”"
                      % (self._song_index, self._target_label(), rate,
                         self._retry_in_song))
            if self._retry_in_song in (10, 30, 60):
                self._alert("同一首已重试 %d 次仍未达到 100%%" % self._retry_in_song,
                            "脚本会继续无限重试。\n" + self._context(frame))
            item = self._find(frame, (self.TEXT_RETRY_PLAY,))
            if item is None:
                self._fatal("结算未达标，但界面上找不到“再次演奏”", frame)
                return
            self._tap_item(item, "再次演奏")
            # 不重新选曲、不重新选难度，直接再次调用打歌模块。
            frame = None

    def _on_song_cleared(self, rate, frame):
        if self._target and self._target not in self._song_done:
            self._song_done.add(self._target)
            self._completed_difficulties += 1
        self._retry_in_song = 0
        self._enter(self.ST_SETTLEMENT,
                    self._progress_text("本次完成率 %.1f%%" % rate))
        self._log("第 %d 首 %s 达成 %.1f%%"
                  % (self._song_index, self._target_label(), rate))
        item = self._find(frame, self.TEXT_SELECT_TRACK)
        if item is None:
            self._alert("结算页找不到“选择曲目”",
                        "已完成 100.0%，但无法点击“选择曲目”。\n" + self._context(frame))
            self._enter(self.ST_AFTER_SELECT, "等待“选择曲目”")
            return
        self._may_tap("select_track", 0, frame)  # 记下这次点击，避免页面未切走时又补一刀
        self._tap_item(item, "选择曲目")
        self._enter(self.ST_AFTER_SELECT, "已点击“选择曲目”，等待返回目录")

    def _run_song(self):
        """阻塞打歌，直到出现结算页 / 需要重新点开始 / 出错 / 被要求停止。"""
        music = self._music
        try:
            music.start()
        except Exception as exc:
            return ("error", "打歌模块初始化失败：%s" % exc)

        self._enter(self.ST_PLAYING, self._progress_text("六轨打歌中"))
        self.loop_interval_ms = self.PLAY_INTERVAL_MS
        started = time.monotonic()
        first_note = None
        last_note = 0.0
        last_check = started
        presses = 0
        errors = 0
        self._no_note_alerted = False
        quiet_seconds = self._quiet_ms / 1000.0
        grace_seconds = self._first_note_grace_ms / 1000.0
        max_seconds = self.MAX_SONG_MS / 1000.0

        while True:
            if self._stop_requested():
                self._release_touches()
                return ("stop",)
            try:
                music.step()
            except Exception as exc:
                errors += 1
                self._release_touches()
                self._log("打歌模块异常（%d/3）：%s" % (errors, exc))
                if errors >= 3:
                    return ("error", "打歌模块连续异常：%s" % exc)
                time.sleep(0.8)
                try:
                    music = MusicBot(self.host)
                    music.start()
                    self._music = music
                    presses = 0
                except Exception as inner:
                    return ("error", "打歌模块无法恢复：%s" % inner)
                continue

            now = time.monotonic()
            # 用“是否产生新的按键”作为音符活动信号：静态色块只会在第一帧触发一次，
            # 不会把非演奏页误判成一直在打歌。
            current = int(getattr(music, "press_count", 0) or 0)
            if current > presses:
                presses = current
                if first_note is None:
                    first_note = now
                    self._log("检测到第一个音符：第 %.1f 秒" % (now - started))
                last_note = now
            elapsed = now - started
            if music.frame_count and music.frame_count % 300 == 0:
                self._log("打歌中：帧%d 触发%d 蓝值%s"
                          % (music.frame_count, current,
                             "/".join(str(v) for v in music.last_blue)))

            if first_note is None:
                # 迟迟没有按键：可能是还没进演奏、卡在加载页或其实已经结算。
                if elapsed < grace_seconds or (now - last_check) < 15.0:
                    continue
                last_check = now
                self._release_touches()
                self._pause_play_capture()
                frame = self._capture()
                if frame is not None:
                    interrupt = self._check_play_interrupt(frame)
                    if interrupt is not None:
                        return interrupt
                    if (self._page_kind(frame) == "cards"
                            or self._find(frame, (self.TEXT_ENTER_PLAY,)) is not None
                            or self._find(frame, self.TITLE_ALIASES) is not None):
                        return ("not_started",)
                if elapsed >= 60 and not self._no_note_alerted:
                    self._no_note_alerted = True
                    self._log("开始演奏 %d 秒仍没有检测到音符" % int(elapsed))
                    self._alert("开始演奏后长时间没有音符",
                                "已等待 %d 秒仍未检测到任何按键，请确认打歌模块是否正常。\n%s"
                                % (int(elapsed), self._context(frame)))
                if elapsed >= max_seconds:
                    return ("error", "长时间没有检测到音符，也未出现结算页")
                continue

            hard_limit = elapsed >= max_seconds
            if elapsed < self.MIN_PLAY_SECONDS and not hard_limit:
                continue
            if (now - max(last_note, last_check, first_note)) < quiet_seconds and not hard_limit:
                continue

            # 静音候选时刻：先松开触点并停掉录屏流，再 OCR，避免两个抓屏通道打架。
            self._release_touches()
            self._pause_play_capture()
            frame = self._capture()
            last_check = now
            if frame is not None:
                interrupt = self._check_play_interrupt(frame)
                if interrupt is not None:
                    return interrupt
            if hard_limit:
                return ("error", "单次演奏超过 %d 分钟仍未出现结算页"
                        % (self.MAX_SONG_MS // 60000))
            self.diagnostic = self._progress_text("打歌中（静音复核）")

    # ------------------------------------------------------------------ 结算数值
    def _check_play_interrupt(self, frame):
        """演奏中 OCR 到结算页/断线弹窗时的处置；None 表示继续打歌。"""
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            return ("settlement", frame)
        if not self._handle_popup(frame):
            return None
        # 断线/提示弹窗会顶掉结算页：先关掉，再看是否回到结算。
        self._log("演奏中出现断线或弹窗提示，尝试关闭后重新判断")
        time.sleep(2.0)
        after = self._capture()
        if after is not None and self._find(after, (self.TEXT_RESULT_MARK,)) is not None:
            return ("settlement", after)
        return ("interrupted", "演奏被断线/弹窗打断，且未出现结算页")

    def _read_rate_with_retry(self, frame):
        rate = self._read_rate(frame)
        tries = 0
        while rate is None and tries < 6:
            tries += 1
            time.sleep(1.0)
            retry = self._capture()
            if retry is None:
                continue
            frame = retry
            if self._handle_popup(frame):
                continue
            rate = self._read_rate(frame)
        return rate, frame

    def _read_rate(self, frame):
        mark = None
        for item in frame.texts:
            if self._matches(item.text, (self.TEXT_RESULT_MARK,)):
                mark = item
                break

        candidates = []
        for item in frame.texts:
            text = str(item.text or "").replace("％", "%")
            for match in re.finditer(r"(\d{1,3}(?:[.,]\d{1,2})?)\s*%", text):
                try:
                    candidates.append((item, float(match.group(1).replace(",", "."))))
                except ValueError:
                    continue
        if not candidates:
            # 个别机型/字号会把“%”识别丢，退化为“完成率标签同高度附近的 x.y”。
            if mark is not None:
                for item in frame.texts:
                    if abs(item.center_y - mark.center_y) > 0.05 * frame.height:
                        continue
                    match = re.search(r"(\d{1,3}\.\d)", str(item.text or ""))
                    if match:
                        self._log("百分比符号缺失，按同高度的数值解析")
                        return float(match.group(1))
            return None
        if mark is not None:
            for item, value in candidates:
                if item is mark:
                    return value
            nearest = min(candidates, key=lambda pair: abs(pair[0].center_y - mark.center_y))
            return nearest[1]
        # 标签漏识别时只在全屏仅有一个百分比时采信，避免读到“最高完成率”等旁值。
        if len(candidates) == 1:
            self._log("未识别到“演奏完成率”标签，按全屏唯一百分比处理")
            return candidates[0][1]
        return None

    # ------------------------------------------------------------------ 页面辅助
    def _capture(self):
        try:
            frame = Frame.from_host(self.host.capture())
        except Exception as exc:
            self._capture_failures += 1
            self.diagnostic = "截图/文字识别失败（%d/5）" % self._capture_failures
            self._log("截图或 OCR 失败：%s" % exc)
            if self._capture_failures >= 5:
                self._fatal("连续 5 次截图/OCR 失败，已停止")
            return None
        self._capture_failures = 0
        self._error_count = 0
        frame = self._drop_overlay_text(frame)
        self._last_frame = frame
        self._mapper = CoordinateMapper(frame.width, frame.height)
        return frame

    def _page_kind(self, frame):
        """settlement / cards（难度卡片页）/ catalog（曲目页）/ other。

        注意：曲目页那四行只是“评级展示”，不能当成难度选择页——真正的难度选择在
        点“前往演奏”之后，那一页有“开始演奏”和“普通/困难/大师/传说”四张卡片。
        """
        if self._find(frame, (self.TEXT_RESULT_MARK,)) is not None:
            return "settlement"
        if self._find(frame, (self.TEXT_START_PLAY,)) is not None:
            return "cards"
        if self._find(frame, (self.TEXT_ENTER_PLAY,)) is not None:
            return "catalog"
        if self._find(frame, self.TITLE_ALIASES) is not None:
            return "catalog"
        # “开始演奏”漏识别时，用卡片上的难度名兜底（曲目页没有这些字）。
        if len(self._difficulty_card_items(frame)) >= 2:
            return "cards"
        return "other"

    def _handle_popup(self, frame):
        """弹窗/网络异常：先邮件提醒，再尝试点“重试/确定”这类安全按钮。"""
        word = None
        for candidate in self.POPUP_WORDS:
            if self._find(frame, (candidate,)) is not None:
                word = candidate
                break
        if word is None:
            return False
        body = "检测到可能阻断流程的提示：%s\n%s" % (word, self._context(frame))
        button = None
        label = ""
        for candidate in self.POPUP_BUTTONS:
            button = self._find(frame, (candidate,))
            if button is not None:
                label = candidate
                break
        if button is None:
            self._alert("检测到弹窗（未找到可点击按钮）", body)
            return False
        self._alert("检测到弹窗，已尝试关闭", body)
        self._tap_item(button, "弹窗·" + label)
        self._log("已点击弹窗按钮：" + label)
        return True

    def _wait_or_alert(self, target, frame=None):
        elapsed = self._state_elapsed_ms()
        self.diagnostic = self._progress_text("等待%s（%d 秒）" % (target, elapsed // 1000))
        if elapsed >= self._page_timeout_ms:
            self._alert("等待界面超时：%s" % target,
                        "等待%s超过 %d 秒。脚本会继续等待，目标界面出现后自动继续。\n%s"
                        % (target, self._page_timeout_ms // 1000,
                           self._context(frame or self._last_frame)))

    # ------------------------------------------------------------------ 动作
    def _may_tap(self, key, gap_ms, frame=None):
        """同一动作在页面还没切走时不要连点；页面已经变了就立刻允许。"""
        now = time.monotonic()
        signature = self._page_signature(frame) if frame is not None else None
        last = self._last_tap_at.get(key)
        if last is not None and gap_ms > 0:
            last_time, last_signature = last
            same_page = signature is None or signature == last_signature
            if same_page and now - last_time < gap_ms / 1000.0:
                return False
        self._last_tap_at[key] = (now, signature)
        return True

    @staticmethod
    def _page_signature(frame):
        if frame is None:
            return ""
        return "|".join(str(item.text) for item in frame.texts[:6])

    def _tap_item(self, item, label=""):
        self._tap_xy(item.center_x, item.center_y, label)
    def _overlay_bounds(self):
        """悬浮窗在屏幕上的矩形（左,上,右,下）；隐藏时返回 None。"""
        getter = getattr(self.host, "getOverlayBounds", None)
        if not callable(getter):
            return None
        try:
            values = getter()
            if values is None:
                return None
            bounds = [int(value) for value in values]
            return bounds if len(bounds) == 4 and bounds[2] > bounds[0] else None
        except Exception:
            return None

    def _inside_overlay(self, x, y):
        bounds = self._overlay_bounds()
        if bounds is None:
            return False
        return bounds[0] <= x <= bounds[2] and bounds[1] <= y <= bounds[3]

    def _drop_overlay_text(self, frame):
        """悬浮窗会被截图一起拍进去，其状态文字里带“千音雅集/前往演奏”等词，必须剔除。"""
        bounds = self._overlay_bounds()
        if bounds != self._last_overlay_bounds:
            self._last_overlay_bounds = bounds
            self._log("悬浮窗区域：%s" % ("未显示" if bounds is None else
                                         "%d,%d-%d,%d" % tuple(bounds)))
        if bounds is None:
            return frame
        left, top, right, bottom = bounds
        kept = tuple(item for item in frame.texts
                     if not (left <= item.center_x <= right
                             and top <= item.center_y <= bottom))
        if len(kept) == len(frame.texts):
            return frame
        if not self._overlay_drop_logged:
            self._overlay_drop_logged = True
            self._log("已忽略悬浮窗区域内的 %d 条文字" % (len(frame.texts) - len(kept)))
        return Frame(frame.width, frame.height, kept, frame.enemies)

    def _tap_xy(self, x, y, label=""):
        if self._inside_overlay(x, y):
            self._log("跳过落在悬浮窗内的点击：%s @(%d,%d)" % (label, int(x), int(y)))
            return
        try:
            self.host.tap(int(round(x)), int(round(y)))
            self._log("点击%s @(%d,%d)" % (label or "文字目标", int(round(x)), int(round(y))))
        except Exception as exc:
            self._log("点击%s失败：%s" % (label, exc))

    def _tap_point(self, point, label):
        if self._mapper is None:
            self._log("尚未取得屏幕尺寸，无法点击" + label)
            return
        # 坐标记录在 1280x720 横屏参考系里，这里按当前截图尺寸换算成屏幕像素。
        screen_x, screen_y = self._mapper.to_screen(point[0], point[1])
        self._tap_xy(screen_x, screen_y, label)

    def _release_touches(self):
        music = self._music
        release = getattr(music, "_release_all", None)
        try:
            if callable(release):
                release()
            else:
                self.host.releaseAllTouches()
        except Exception as exc:
            self._log("释放触点失败：%s" % exc)

    def _pause_play_capture(self):
        """打歌期间抓屏通道必须串行：OCR 之前先关掉常驻录屏流，否则游戏会卡死。"""
        pause = getattr(self.host, "pauseMusicCapture", None)
        if not callable(pause):
            return
        try:
            pause()
        except Exception as exc:
            self._log("暂停打歌取帧失败：%s" % exc)

    def _stop_requested(self):
        check = getattr(self.host, "isStopRequested", None)
        if not callable(check):
            return False
        try:
            return bool(check())
        except Exception:
            return False

    # ------------------------------------------------------------------ 状态/日志/邮件
    def _enter(self, state, diagnostic=""):
        self.state = state
        self._state_since = time.monotonic()
        if diagnostic:
            self.diagnostic = diagnostic
        self.loop_interval_ms = self.PLAY_INTERVAL_MS if state == self.ST_PLAYING \
            else self.QUEUE_INTERVAL_MS

    def _state_elapsed_ms(self):
        return int((time.monotonic() - self._state_since) * 1000)

    def _target_label(self):
        return self.DIFFICULTY_LABELS.get(self._target, "—")

    def _progress_text(self, detail):
        prefix = "第%d/%d首 · %s" % (self._song_index, self._song_count, self._target_label())
        return prefix + (" · " + detail if detail else "")

    def _compact(self, text):
        return re.sub(r"\s+", "", str(text or ""))

    def _matches(self, text, aliases):
        compact = self._compact(text)
        for alias in aliases:
            if alias in compact:
                return True
            # 游戏字体经常被 OCR 认错一个字（千→干、演→濱），四字以上允许一处不同。
            if len(alias) >= 4 and self._fuzzy_contains(compact, alias):
                return True
        return False

    @staticmethod
    def _fuzzy_contains(text, needle):
        window = len(needle)
        if len(text) < window:
            return False
        for start in range(len(text) - window + 1):
            diff = 0
            for offset in range(window):
                if text[start + offset] != needle[offset]:
                    diff += 1
                    if diff > 1:
                        break
            if diff <= 1:
                return True
        return False

    def _find(self, frame, aliases):
        """精确包含优先；全都没有时才用“允许一个字不同”的模糊匹配。"""
        if frame is None:
            return None
        for alias in aliases:
            for item in frame.texts:
                if alias in self._compact(item.text):
                    return item
        for alias in aliases:
            if len(alias) < 4:
                continue
            for item in frame.texts:
                if self._fuzzy_contains(self._compact(item.text), alias):
                    return item
        return None

    def _text_preview(self, frame, limit=12, max_chars=300):
        parts = [self._compact(item.text) for item in frame.texts[:limit]]
        joined = " | ".join(part for part in parts if part)
        return joined[:max_chars] if joined else "（无文字）"

    def _screen_dump(self, frame, limit=28):
        if frame is None:
            return "（无截图）"
        parts = []
        for item in frame.texts[:limit]:
            text = self._compact(item.text)
            if text:
                parts.append("%s@(%d,%d)" % (text, item.center_x, item.center_y))
        return " | ".join(parts) if parts else "（本帧 OCR 没有文字）"

    def _context(self, frame):
        logs = " / ".join(self._recent_logs[-6:])
        return ("状态：%s\n歌曲：第 %d/%d 首\n模式：%s\n目标难度：%s\n已重试：%d 次\n"
                "画面文字：%s\n最近日志：%s" % (self.state, self._song_index, self._song_count,
                                          self._mode_name(), self._target_label(),
                                          self._retry_in_song, self._screen_dump(frame), logs))

    def _alert(self, subject, body):
        """同一现象 5 分钟内只发一封，避免无限重试刷爆邮箱。"""
        signature = subject + "|" + self._compact(body)[:160]
        now = time.monotonic()
        if signature == self._last_alert_signature and now - self._last_alert_at < 300:
            return False
        self._last_alert_signature = signature
        self._last_alert_at = now
        full = "%s\n\n%s\n\n时间：%s" % (
            body, self._progress_text(""), time.strftime("%Y-%m-%d %H:%M:%S"))
        sender = getattr(self.host, "sendAlbumAlert", None)
        queued = False
        if callable(sender):
            try:
                queued = bool(sender("专辑脚本 · " + subject, full))
            except Exception as exc:
                self._log("发送邮件失败：%s" % exc)
        self._log("邮件提醒：%s（%s）" % (subject, "已提交" if queued else "未提交"))
        return queued

    def _fatal(self, reason, frame=None):
        self._alert("脚本停止，需要人工处理", reason + "\n" + self._context(frame))
        self.state = self.ST_STOPPED
        self.diagnostic = reason
        self._log("脚本停止：" + reason)
        self.running = False
        self._release_touches()

    def _finish_all(self):
        self.state = self.ST_DONE
        self.diagnostic = "%d 首全部完成，补完 %d 个难度，重试 %d 次" % (
            self._song_count, self._completed_difficulties, self._total_retries)
        self._log(self.diagnostic)
        if self._send_complete_email:
            self._alert("专辑处理完成",
                        "专辑 %d 首已全部按“%s”处理完成。\n补完难度：%d 个\n累计重试：%d 次"
                        % (self._song_count, self._mode_name(),
                           self._completed_difficulties, self._total_retries))
        self.running = False
        self._release_touches()

    def _log(self, message):
        line = str(message)
        self._recent_logs.append(line)
        self._recent_logs = self._recent_logs[-20:]
        logger = getattr(self.host, "log", None)
        if callable(logger):
            try:
                logger(line)
            except Exception:
                pass
