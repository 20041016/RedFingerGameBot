import random
import time
import re
import base64
import urllib.parse
from .config import *
from .coordinate import CoordinateMapper
from .frame import Frame


class GameBot:
    """V0.9 migration engine. Original scene keywords and game coordinates retained."""

    def __init__(self, host):
        self.host = host
        game_config = list(host.getGameCoordinateConfig())
        if len(game_config) != 10:
            game_config = [1066, 194, 1231, 503, 960, 360, 660, 360, 50, 300]
        self.run_button = (int(game_config[0]), int(game_config[1]))
        self.extra_button = (int(game_config[2]), int(game_config[3]))
        self.search_swipe_start = (int(game_config[4]), int(game_config[5]))
        self.search_swipe_end = (int(game_config[6]), int(game_config[7]))
        self.dead_zone = max(0, int(game_config[8]))
        self.view_gain = max(0.0, min(2.0, int(game_config[9]) / 1000.0))
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
        # Face-verification diagnostic workflow. This runs before ordinary game OCR.
        self.verification_state = "IDLE"
        self.verification_alert_active = False
        self.verification_absent_since_ms = None
        self.verification_waiting_for_lobby = False
        self.verification_last_swipe_ms = 0
        self.verification_last_url = ""
        self.verification_start_verify_clicked = []
        self.verification_expand_last_tap_ms = 0
        self.verification_network_last_attempt_ms = 0
        self.verification_upload_error_seen = False
        self.verification_url_saved = False
        # Persisted wall-clock deadline for the post-verification 10-minute cooldown.
        self.cooldown_until_epoch_ms = 0
        # Enemy tracking / dataset collection.
        self.track_next_ms = 0
        self.last_collect_ms = 0

    def set_cooldown_until_epoch_ms(self, deadline_ms):
        self.cooldown_until_epoch_ms = max(0, int(deadline_ms or 0))

    def start(self):
        # 每次点击启动都重新识别当前画面；绝不默认大厅。
        self._reset()
        self.rng = random.Random()
        self.unknown_since_ms = None
        self.unknown_last_sample_ms = 0
        self.unknown_samples = []
        self.unknown_log_saved = False
        self.verification_state = "IDLE"
        self.verification_alert_active = False
        self.verification_absent_since_ms = None
        self.verification_waiting_for_lobby = False
        self.verification_last_swipe_ms = 0
        self.verification_last_url = ""
        self.verification_start_verify_clicked = []
        self.verification_expand_last_tap_ms = 0
        self.verification_network_last_attempt_ms = 0
        self.verification_upload_error_seen = False
        self.verification_url_saved = False
        self.cooldown_until_epoch_ms = 0
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

        # A successful verification cycle is followed by a strict 10-minute
        # no-action pause. BotService normally sleeps without calling step;
        # this guard also protects direct/restarted engine invocation.
        wall_now = int(time.time() * 1000)
        if self.cooldown_until_epoch_ms > wall_now:
            remaining = self.cooldown_until_epoch_ms - wall_now
            self.state = "身份验证完成，冷却中"
            self.diagnostic = "暂停游戏操作，剩余约 " + str(max(1, (remaining + 999) // 1000)) + " 秒"
            self.loop_interval_ms = min(1000, max(100, remaining))
            return
        if self.cooldown_until_epoch_ms:
            self.cooldown_until_epoch_ms = 0
            try:
                clear_cooldown = getattr(self.host, "setVerificationCooldownUntil", None)
                if callable(clear_cooldown):
                    clear_cooldown(0)
            except Exception as error:
                self._log("清除身份验证冷却记录失败：" + str(error))
            self._log("身份验证后10分钟冷却结束，恢复普通挂机")

        now = int(time.monotonic() * 1000)
        frame = Frame.from_host(self.host.capture())
        self.mapper = CoordinateMapper(frame.width, frame.height)
        self._maybe_collect(now)

        # Identity-verification flow has priority over all ordinary game actions.
        if self._verification_step(frame, now):
            return
        # The verification helper may have just recognized the lobby and
        # started the cooldown in this same frame. Never run ordinary actions.
        if self.cooldown_until_epoch_ms > int(time.time() * 1000):
            self.state = "身份验证完成，冷却中"
            self.diagnostic = "身份验证流程已完成，暂停游戏操作10分钟"
            self.loop_interval_ms = 1000
            return

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


    def _verification_step(self, frame, now):
        """Run the user-defined verification diagnostic sequence without stopping the game engine."""
        state_words = ("前往验证", "开始验证", "诊断网络", "展开错误详情",
                       "隐藏错误详情", "上传错误")
        state_visible = any(frame.has(word) for word in state_words)
        # Keep the alert session active until the FSM has returned to the actual
        # game lobby. Do not use a timed "verification UI disappeared" grace
        # period: the lobby loop can tap Start Game and immediately trigger a
        # second verification before that timer expires.
        self.verification_alert_active = bool(
            state_visible or self.verification_state != "IDLE"
            or self.verification_waiting_for_lobby
        )

        if self.verification_state == "IDLE":
            if self.verification_waiting_for_lobby:
                # After the FSM has finished handling the URI and pressed Back,
                # passively wait for the lobby's distinctive OCR scene. Do not
                # execute any ordinary lobby/game taps while waiting.
                if self.detect_scene(frame) == "大厅":
                    self.verification_waiting_for_lobby = False
                    self.verification_alert_active = False
                    self.verification_absent_since_ms = None
                    deadline = int(time.time() * 1000) + 10 * 60 * 1000
                    self.cooldown_until_epoch_ms = deadline
                    try:
                        persist_cooldown = getattr(self.host, "setVerificationCooldownUntil", None)
                        if callable(persist_cooldown):
                            persist_cooldown(deadline)
                    except Exception as error:
                        self._log("保存10分钟冷却时间失败：" + str(error))
                    self.state = "身份验证完成，冷却中"
                    self.diagnostic = "已识别大厅特征，暂停所有游戏操作10分钟"
                    self._log("身份验证状态机结束且检测到大厅特征，进入10分钟冷却")
                    self.loop_interval_ms = 1000
                    return True
                self.state = "身份验证完成，等待大厅"
                self.diagnostic = "状态机已结束，等待识别大厅特征；期间不执行游戏操作"
                self.loop_interval_ms = 500
                return True
            if frame.has("前往验证"):
                self.verification_absent_since_ms = None
                self.verification_start_verify_clicked = []
                self.verification_url_saved = False
                self.verification_upload_error_seen = False
                self.verification_last_url = ""
                self._enter("身份验证：点击前往验证")
                self._tap_text(frame, "前往验证")
                self.verification_start_verify_clicked = []
                self.verification_state = "WAIT_START_VERIFY"
                return True
            # Failed/incomplete FSM cycles retain the existing retry/normal flow.
            if self.verification_absent_since_ms is not None:
                if state_visible:
                    self.verification_absent_since_ms = None
                    # A verification-stage label means the state machine is still present.
                    # Restart only when its entry button is visible; otherwise do not run game taps.
                    if frame.has("前往验证"):
                        self.verification_start_verify_clicked = []
                        self.verification_url_saved = False
                        self.verification_upload_error_seen = False
                        self.verification_last_url = ""
                        self._enter("身份验证：再次出现")
                        self._tap_text(frame, "前往验证")
                        self.verification_start_verify_clicked = []
                        self.verification_state = "WAIT_START_VERIFY"
                    return True
                elif now - self.verification_absent_since_ms >= 30_000:
                    self.verification_absent_since_ms = None
                    self.verification_alert_active = False
                    if self.verification_url_saved and self.verification_last_url:
                        # The URI was captured/processed and the verification UI
                        # stayed absent for the grace period: mark the FSM cycle
                        # successful, then pause all game actions for 10 minutes.
                        deadline = int(time.time() * 1000) + 10 * 60 * 1000
                        self.cooldown_until_epoch_ms = deadline
                        try:
                            persist_cooldown = getattr(self.host, "setVerificationCooldownUntil", None)
                            if callable(persist_cooldown):
                                persist_cooldown(deadline)
                        except Exception as error:
                            self._log("保存10分钟冷却时间失败：" + str(error))
                        self.state = "身份验证完成，冷却中"
                        self.diagnostic = "身份验证流程已完成，暂停游戏操作10分钟"
                        self._log("身份验证状态机成功完成，进入10分钟冷却；期间不执行游戏操作")
                    else:
                        self._log("身份验证界面已消失，但未完成验证链接处理；不启动10分钟冷却，恢复原有流程")
                    return False
            return False

        if self.verification_state == "WAIT_START_VERIFY":
            # The verification page contains TWO separate "开始验证" controls.
            # Tap each distinct OCR line once, and do not advance until both were tapped.
            candidates = [item for item in frame.texts
                          if "开始验证" in (item.text or "")]
            candidates.sort(key=lambda item: (item.top, item.left))
            for item in candidates:
                cx, cy = item.center_x, item.center_y
                already_clicked = any(abs(cx - x) < 12 and abs(cy - y) < 12
                                      for x, y in self.verification_start_verify_clicked)
                if not already_clicked:
                    self._tap_screen(cx, cy)
                    self.verification_start_verify_clicked.append((cx, cy))
                    self._log("身份验证：已点击第 " + str(len(self.verification_start_verify_clicked))
                              + "/2 个开始验证按钮")
                    break  # One controlled tap per OCR frame; then re-read the screen.
            if len(self.verification_start_verify_clicked) >= 2:
                self.verification_state = "WAIT_NETWORK_DIAGNOSIS"
                self._enter("身份验证：两个开始验证按钮均已点击，等待诊断网络")
            else:
                self._enter("身份验证：等待并点击两个开始验证按钮（已点击 "
                            + str(len(self.verification_start_verify_clicked)) + "/2）")
            return True

        if self.verification_state == "WAIT_NETWORK_DIAGNOSIS":
            # Accessibility node click is authoritative; never advance on OCR-only detection.
            if now - self.verification_network_last_attempt_ms >= 1200:
                self.verification_network_last_attempt_ms = now
                clicker = getattr(self.host, "clickAccessibilityText", None)
                clicked = bool(clicker("诊断网络")) if callable(clicker) else False
                if clicked:
                    self.verification_state = "WAIT_EXPAND_ERROR_DETAILS"
                    self.verification_expand_last_tap_ms = 0
                    self._enter("身份验证：无障碍已点击诊断网络，等待展开错误详情")
                    self._log("无障碍点击成功：诊断网络")
                else:
                    self._enter("身份验证：等待诊断网络无障碍节点并重试")
            return True

        if self.verification_state == "WAIT_EXPAND_ERROR_DETAILS":
            # Click the actual visible node through Accessibility and wait for the inverse label.
            reader = getattr(self.host, "readAccessibilityText", None)
            visible = str(reader() or "") if callable(reader) else ""
            if "隐藏错误详情" in visible:
                self.verification_state = "SCROLL_TO_UPLOAD_ERROR"
                self.verification_last_swipe_ms = 0
                self.verification_upload_error_seen = False
                self.verification_url_saved = False
                self._enter("身份验证：无障碍确认错误详情已展开，向上查找上传错误")
                return True
            if "展开错误详情" in visible and now - self.verification_expand_last_tap_ms >= 1200:
                self.verification_expand_last_tap_ms = now
                clicker = getattr(self.host, "clickAccessibilityText", None)
                clicked = bool(clicker("展开错误详情")) if callable(clicker) else False
                if clicked:
                    self._log("无障碍点击成功：展开错误详情，等待隐藏错误详情确认")
                else:
                    self._log("无障碍尚未成功点击展开错误详情，将继续等待并重试")
            self._enter("身份验证：等待无障碍识别并点击展开错误详情")
            return True

        if self.verification_state == "SCROLL_TO_UPLOAD_ERROR":
            # OCR can return the URL split across multiple text blocks, and the
            # URL and heading may not be visible in the same frame. Remember
            # seeing the heading, then capture the complete visible URI.
            # Read the URI only from visible Accessibility nodes; OCR is not used for URL extraction.
            if frame.has("上传错误"):
                self.verification_upload_error_seen = True
            reader = getattr(self.host, "readAccessibilityText", None)
            accessible_text = str(reader() or "") if callable(reader) else ""
            if "上传错误" in accessible_text:
                self.verification_upload_error_seen = True
            url = self._find_mqqapi_url_text(accessible_text)
            if self.verification_upload_error_seen and url and not self.verification_url_saved:
                # Keep the original URI path independent from the optional decoder.
                # A decoder or decoded-file error must never block copy/back.
                def safe_log(message):
                    try:
                        self._log(message)
                    except Exception:
                        pass

                saved_path = ""
                try:
                    saver = getattr(self.host, "saveVerificationUrl", None)
                    if callable(saver):
                        saved_path = saver(url) or ""
                    else:
                        safe_log("原始 mqqapi URI 保存接口不可用")
                except Exception as save_error:
                    safe_log("原始 mqqapi URI 保存失败；仍继续复制和返回：" + str(save_error))

                decoded_url = self._decode_mqqapi_url_prefix(url)
                if decoded_url:
                    try:
                        decoded_saver = getattr(self.host, "saveVerificationDecoded", None)
                        if not callable(decoded_saver):
                            raise RuntimeError("saveVerificationDecoded 方法不存在")
                        decoded_path = decoded_saver(url, decoded_url)
                        safe_log("Base64 解码结果已保存：" + str(decoded_path or "内部存储"))
                    except Exception as decode_save_error:
                        safe_log("Base64 解码结果保存失败；原始链接仍继续复制和返回："
                                 + str(decode_save_error))
                else:
                    safe_log("未发现有效的 HTTP(S) url_prefix；原始 URI 保留")

                try:
                    copier = getattr(self.host, "copyText", None)
                    if not callable(copier):
                        raise RuntimeError("宿主未提供剪贴板接口")
                    copied = copier(url)
                    if copied is False:
                        raise RuntimeError("系统剪贴板写入失败")
                    safe_log("原始 mqqapi URI 已复制：" + str(saved_path or "保存状态未知"))
                except Exception as copy_error:
                    safe_log("原始 mqqapi URI 复制失败：" + str(copy_error))

                # Do not retry this URI indefinitely; always attempt the physical back key.
                self.verification_last_url = url
                self.verification_url_saved = True
                try:
                    back = getattr(self.host, "pressBack", None)
                    if not callable(back):
                        raise RuntimeError("宿主未提供实体返回键接口")
                    back()
                    safe_log("已执行物理返回键")
                except Exception as back_error:
                    safe_log("物理返回键执行失败：" + str(back_error))

                self.verification_state = "IDLE"
                self.verification_absent_since_ms = None
                self.verification_waiting_for_lobby = bool(self.verification_url_saved)
                self._enter("身份验证：状态机结束，等待大厅特征")
                return True
            if now - self.verification_last_swipe_ms >= 900:
                x = int(frame.width * 0.5)
                y1 = int(frame.height * 0.78)
                y2 = int(frame.height * 0.30)
                self.host.swipe(x, y1, x, y2, 450)
                self.verification_last_swipe_ms = now
            return True

        return self.verification_state != "IDLE"

    @staticmethod
    def _decode_mqqapi_url_prefix(uri):
        """Decode a visible mqqapi url_prefix into a validated HTTP(S) URL."""
        if not uri:
            return ""
        try:
            parsed = urllib.parse.urlsplit(str(uri))
            if parsed.scheme.lower() != "mqqapi":
                return ""

            # parse_qs identifies the parameter; unquote the raw value below so
            # a literal '+' in standard Base64 is not mistaken for a form-space.
            params = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            values = params.get("url_prefix") or []
            if not values:
                return ""

            encoded = values[0]
            for item in parsed.query.split("&"):
                raw_key, separator, raw_value = item.partition("=")
                if separator and urllib.parse.unquote_plus(raw_key) == "url_prefix":
                    encoded = urllib.parse.unquote(raw_value)
                    break
            encoded = encoded.strip()
            if not encoded:
                return ""

            # Support Base64URL and tolerate whitespace in copied values.
            encoded = encoded.replace("-", "+").replace("_", "/")
            encoded = "".join(encoded.split())
            encoded += "=" * ((4 - len(encoded) % 4) % 4)

            decoded_bytes = base64.b64decode(encoded, validate=True)
            decoded_url = decoded_bytes.decode("utf-8").strip()
            target = urllib.parse.urlsplit(decoded_url)
            if target.scheme.lower() not in ("http", "https") or not target.netloc:
                return ""
            if any(ch in decoded_url for ch in ("\\r", "\\n", "\\x00")):
                return ""
            return decoded_url
        except Exception:
            return ""

    @staticmethod
    def _find_mqqapi_url_text(visible_text):
        """Extract a complete mqqapi URI exposed by visible Accessibility text nodes."""
        if not visible_text:
            return ""
        compact = re.sub(r"\s+", "", str(visible_text))
        match = re.search(r"""(?i)mqqapi://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""", compact)
        if not match:
            return ""
        url = match.group(0).rstrip("。，,;；、)】］}>")
        if not re.fullmatch(r"""(?i)mqqapi://[^\s<>"']+""", url):
            return ""
        return url


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
            # 落地进入寻敌状态，明确重置奔跑状态，后续检测到信号区时重新启动。
            self.is_sprinting = False
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

        # Enemy visible: keep it centred and skip the signal-zone / search logic this frame.
        if self._track_enemy(f, now):
            return

        signal = f.find("信号区")
        exists = signal is not None
        # OCR 偶发漏识别后恢复，不代表游戏中的奔跑状态已停止。
        # 不因信号区重新出现而强制点击奔跑键，避免切换型按键反而停止奔跑。
        self.last_signal_exists = exists

        if exists:
            self._enter("战斗中奔跑")
            self.signal_lost_start_ms = None
            if not self.is_sprinting or self.need_reclick_run:
                self._tap_ref(*self.run_button)
                self.is_sprinting = True
                self.need_reclick_run = False
                self.next_extra_click_ms = now + self.rng.randint(
                    EXTRA_CLICK_MIN_MS, EXTRA_CLICK_MAX_MS)
            elif self.next_extra_click_ms is not None and now >= self.next_extra_click_ms:
                self._tap_ref(*self.extra_button)
                self.next_extra_click_ms = now + self.rng.randint(
                    EXTRA_CLICK_MIN_MS, EXTRA_CLICK_MAX_MS)
            sx, _ = self.mapper.to_reference(signal.center_x, signal.center_y)
            dx = REFERENCE_WIDTH / 2 - sx
            if abs(dx) > self.dead_zone:
                x1, y = REFERENCE_WIDTH * .75, REFERENCE_HEIGHT / 2
                self._swipe_ref(x1, y, max(0, min(REFERENCE_WIDTH, x1 - dx * self.view_gain)), y, 200)
            return

        if self.is_sprinting:
            if self.signal_lost_start_ms is None:
                self.signal_lost_start_ms = now
            elif now - self.signal_lost_start_ms >= SIGNAL_WAIT_MS:
                self._tap_ref(*self.run_button)
                self.is_sprinting = False
                self.signal_lost_start_ms = None
                self._enter("战斗中寻敌")
            return

        self._enter("战斗中寻敌")
        if now >= self.next_search_swipe_ms:
            self._swipe_ref(*self.search_swipe_start, *self.search_swipe_end, SEARCH_SWIPE_DURATION_MS)
            self.next_search_swipe_ms = now + SEARCH_SWIPE_DURATION_MS + SEARCH_SWIPE_PAUSE_MS

    def _track_enemy(self, f, now):
        """Keep the enemy closest to the screen centre horizontally centred.

        Same camera convention as the signal-zone alignment: swiping from x1 to
        x1 - dx * view_gain turns the view toward the target. Returns True when an
        enemy is on screen (the frame is "handled"), False to fall through to the
        normal battle logic.
        """
        if not TRACK_ENEMY_ENABLED or not f.enemies:
            return False
        center = REFERENCE_WIDTH / 2
        best = None
        for e in f.enemies:
            if e.conf < TRACK_MIN_CONF:
                continue
            ex, ey = self.mapper.to_reference(e.cx, e.cy)
            if best is None or abs(ex - center) < abs(best[0] - center):
                best = (ex, ey, e.conf)
        if best is None:
            return False

        dx = center - best[0]
        self.diagnostic = "追踪敌人 偏差=%d conf=%.2f" % (round(-dx), best[2])
        if abs(dx) <= self.dead_zone or now < self.track_next_ms:
            return True
        step = max(-TRACK_MAX_STEP_PX, min(TRACK_MAX_STEP_PX, dx * self.view_gain))
        x1, y = REFERENCE_WIDTH * .75, REFERENCE_HEIGHT / 2
        x2 = max(0, min(REFERENCE_WIDTH, x1 - step))
        self._swipe_ref(x1, y, x2, y, TRACK_SWIPE_MS)
        self.track_next_ms = now + TRACK_SWIPE_MS + TRACK_SETTLE_MS
        return True

    def _maybe_collect(self, now):
        """Optionally save screenshots as detector training data while idling in battle."""
        if DATASET_COLLECT_INTERVAL_MS <= 0 or not self.state.startswith("战斗"):
            return
        if now - self.last_collect_ms < DATASET_COLLECT_INTERVAL_MS:
            return
        self.last_collect_ms = now
        saver = getattr(self.host, "saveTrainingFrame", None)
        if callable(saver):
            try:
                saver()
            except Exception as error:
                self._log("保存训练截图失败：" + str(error))

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
        self.track_next_ms = 0

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
