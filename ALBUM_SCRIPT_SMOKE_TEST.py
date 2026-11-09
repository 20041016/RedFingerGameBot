"""Host-side smoke test for the album automation state machine.

It replaces the phone with a tiny simulated game: pages are plain text frames,
taps on those frames change the page, and the six-lane sampler is fed a short
note stream. This validates the album flow (directory ratings -> 前往演奏 ->
difficulty cards -> play -> settlement -> next song) without Android, Root or a
real device.

Run: python ALBUM_SCRIPT_SMOKE_TEST.py
"""
import sys
import time

from app.src.main.python.python.album_bot import AlbumBot

WIDTH, HEIGHT = 1280, 720
NEXT_SONG = (1200, 660)
DONE_RETURN = (100, 660)

# 曲目页：四行只有评级文字（暂无评级/高遏行云/大音天籁），点它不会选难度。
DIFFICULTY_ROWS = {"normal": 296, "hard": 369, "master": 440, "legend": 512}
ROW_TAP_X = 384

# 难度卡片页（点“前往演奏”之后）：卡片上有“普通/困难/大师/传说”，底部有“开始演奏”。
CARD_Y = 231                      # 0.321 * 720（卡片上的难度名）
CARD_RATING_Y = 439               # 0.61 * 720（卡片下方的评级条）
CARD_X = {"normal": 338, "hard": 532, "master": 727, "legend": 948}
CARD_NAMES = {"normal": "普通", "hard": "困难", "master": "大师", "legend": "传说"}
START_PLAY = (920, 650)
MULTI_PLAY = (520, 650)


def item(text, x, y):
    return {"text": text, "left": x - 45, "top": y - 16, "right": x + 45, "bottom": y + 16}


class FakeGame:
    """Simulated album: page text, difficulty ratings, a short note stream, tap routing."""

    def __init__(self, mode, done_by_song=None, first_attempt_rate=98.0, directory_delay=2):
        self.mode = mode
        self.done = {song: set(values) for song, values in (done_by_song or {}).items()}
        self.first_attempt_rate = first_attempt_rate
        self.directory_delay = directory_delay
        self.song = 1
        self.page = "album"
        self.pending_page = None
        self.pending_delay = 0
        self.selected = None
        self.attempts = 0
        self.notes = 0
        self.rate = 100.0
        self.page_during_next_tap = None
        self.tap_log = []
        self.violations = []

    # ---------- album config ----------
    def getAlbumConfig(self):
        return [NEXT_SONG[0], NEXT_SONG[1], DONE_RETURN[0], DONE_RETURN[1],
                1 if self.mode == "all" else 0, 12, 5000, 200, 300, 0]

    def getMusicCoordinateConfig(self):
        return [778, 1100, 1440, 1780, 2100, 2440, 1210, 230, -1]

    def isStopRequested(self):
        return False

    def sendAlbumAlert(self, subject, body):
        self.alerts = getattr(self, "alerts", []) + [subject]
        return True

    def log(self, message):
        pass

    # ---------- page frames ----------
    def _switch(self, page, delay=0):
        self.pending_page = page
        self.pending_delay = delay

    def _texts(self):
        if self.page == "album":
            texts = [item("干音雅集 / 魔导·绮想交奏之诗", 639, 79),      # OCR 把“千”读成“干”
                     item("前往濱奏", 1000, 620)]                        # OCR 把“演”读成“濱”
            for key, y in DIFFICULTY_ROWS.items():
                label = "大音天籁" if key in self.done.get(self.song, ()) else "暂无评级"
                texts.append(item(label, 360, y))
            return texts
        if self.page == "cards":
            texts = [item(CARD_NAMES[key], CARD_X[key], CARD_Y) for key in CARD_NAMES]
            for key in CARD_NAMES:
                rating = "大音天籁" if key in self.done.get(self.song, ()) else "暂无评级"
                texts.append(item(rating, CARD_X[key], CARD_RATING_Y))
            texts.append(item("多人演奏", MULTI_PLAY[0], MULTI_PLAY[1]))
            texts.append(item("开始演奏", START_PLAY[0], START_PLAY[1]))
            return texts
        if self.page == "settlement":
            return [item("演奏完成率", 640, 300),
                    item("%.1f%%" % self.rate, 640, 340),
                    item("再次演奏", 480, 620), item("选择乐曲", 800, 620)]
        return []

    def capture(self):
        if self.pending_page is not None:
            if self.pending_delay > 0:
                self.pending_delay -= 1
            else:
                self.page = self.pending_page
                self.pending_page = None
        return {"width": WIDTH, "height": HEIGHT, "texts": self._texts(), "enemies": []}

    # ---------- taps ----------
    def tap(self, x, y):
        if self.page == "cards" and abs(x - DONE_RETURN[0]) < 30 and abs(y - DONE_RETURN[1]) < 30:
            self.tap_log.append(("coord", "done_return"))
            self._switch("album", 1)
            return
        if self.page == "album":
            if abs(x - NEXT_SONG[0]) < 30 and abs(y - NEXT_SONG[1]) < 30:
                self.page_during_next_tap = self.page
                self.tap_log.append(("coord", "next_song"))
                if len(self.done.get(self.song, ())) < (4 if self.mode == "all" else 1):
                    self.violations.append("第 %d 首未补完就点了下一首" % self.song)
                self.song += 1
                return
            if abs(x - DONE_RETURN[0]) < 30 and abs(y - DONE_RETURN[1]) < 30:
                self.tap_log.append(("coord", "done_return"))
                return
            # 曲目页的四行只是评级展示，点上去没有任何反应（真实界面就是这样）。
            if x < 0.45 * WIDTH:
                for key, row_y in DIFFICULTY_ROWS.items():
                    if abs(y - row_y) <= 24:
                        self.tap_log.append(("row", key))
                        return

        for text in self._texts():
            if text["left"] <= x <= text["right"] and text["top"] <= y <= text["bottom"]:
                self._tap_text(text["text"])
                return
        self.tap_log.append(("miss", (x, y)))

    def _tap_text(self, text):
        self.tap_log.append(("text", text))
        if text.startswith("前往"):
            self._switch("cards", 1)
        elif text in CARD_NAMES.values():
            self.selected = next(key for key, name in CARD_NAMES.items() if name == text)
            self.tap_log.append(("card", self.selected))
        elif text == "开始演奏":
            self.attempts += 1
            self.rate = self.first_attempt_rate if self.attempts == 1 else 100.0
            self.notes = 24
            self.page = "playing"
        elif text == "再次演奏":
            self.attempts += 1
            self.rate = 100.0
            self.notes = 12
            self.page = "playing"
        elif text == "选择乐曲":
            if self.rate >= 99.95 and self.selected:
                self.done.setdefault(self.song, set()).add(self.selected)
            self._switch("album", self.directory_delay)

    # ---------- six-lane sampler ----------
    def initializeMultiTouch(self, width, height):
        return "fake-type-b"

    def captureBlueValues(self, xs, y):
        if self.notes > 0:
            self.notes -= 1
            lane = self.notes % 2
            blue = [255] * 6
            blue[lane] = 90
            return {"blue": blue, "captureMs": 0, "decodeMs": 0}
        if self.page == "playing":
            self.page = "settlement"
        return {"blue": [255] * 6, "captureMs": 0, "decodeMs": 0}

    def updateTouches(self, states, xs, ys):
        return 0

    def releaseAllTouches(self):
        pass


def run(bot, limit=200000):
    bot.start()
    # Page-mode timeouts stay generous; only the play-loop quiet window is shortened.
    bot._quiet_ms = 200
    bot._first_note_grace_ms = 300
    bot.MIN_PLAY_SECONDS = 0.1
    bot.MAX_SONG_MS = 15000
    start = time.monotonic()
    steps = 0
    while bot.running and steps < limit:
        bot.step()
        steps += 1
        if time.monotonic() - start > 240:
            raise AssertionError("模拟运行超时，可能状态机卡住：state=%s diag=%s"
                                 % (bot.state, bot.diagnostic))
    bot.stop()
    return steps


def test_legend_only_retries_and_advances():
    game = FakeGame("legend")
    bot = AlbumBot(game)
    run(bot)
    assert not game.violations, game.violations
    assert bot.state == bot.ST_DONE, (bot.state, bot.diagnostic)
    # 第 12 首处理完直接收尾，不再点下一首，所以只切了 11 次。
    assert game.song == 12, game.song
    # 第 1 首第一次 98% 会“再次演奏”，第二次 100% 才切下一首。
    assert ("text", "再次演奏") in game.tap_log
    assert game.tap_log.count(("coord", "next_song")) == 11
    # legend_only 每首只点一次传说卡片、点一次“开始演奏”；重试走“再次演奏”。
    starts = [entry for entry in game.tap_log if entry == ("text", "开始演奏")]
    assert len(starts) == 12, len(starts)
    assert len([e for e in game.tap_log if e == ("card", "legend")]) == 12
    # 曲目页那四行评级只是展示，绝不能在目录页点它们。
    assert not [e for e in game.tap_log if e[0] == "row"], game.tap_log
    print("legend_only: 前往演奏 -> 读卡片评级 -> 点传说卡片 -> 98% 重试 -> 100% 通过")


def test_all_difficulties_priority_and_no_early_advance():
    # 第 1 首的传说已经是大音天籁，其余三个难度都要补。
    game = FakeGame("all", done_by_song={1: {"legend"}}, first_attempt_rate=100.0)
    bot = AlbumBot(game)
    run(bot)
    assert not game.violations, game.violations
    assert bot.state == bot.ST_DONE, (bot.state, bot.diagnostic)
    assert game.song == 12, game.song
    assert len(game.done[1]) == 4, game.done[1]
    assert game.done[1] == {"legend", "master", "hard", "normal"}, game.done[1]
    order = [entry[1] for entry in game.tap_log if entry[0] == "card"]
    assert order[:3] == ["master", "hard", "normal"], order[:3]
    assert game.tap_log.count(("coord", "next_song")) == 11
    assert not [e for e in game.tap_log if e[0] == "row"], game.tap_log
    print("all_difficulties: 传说>大师>困难>普通 顺序补齐、四难度齐全后才切歌")


def test_catalog_rows_are_display_only():
    """曲目页的第一下必须点“前往演奏”，不能去点那四行评级。"""
    game = FakeGame("legend")
    bot = AlbumBot(game)
    bot.start()
    bot.step()
    first = game.tap_log[0]
    assert first[0] == "text" and first[1].startswith("前往"), game.tap_log
    assert not [e for e in game.tap_log if e[0] == "row"], game.tap_log
    assert bot.state == bot.ST_DIFFICULTY, (bot.state, bot.diagnostic)
    print("曲目页: 四行只读评级，第一下点击是“前往演奏”")


def test_difficulty_card_is_tapped_by_name():
    game = FakeGame("legend")
    bot = AlbumBot(game)
    bot.start()
    bot.step()                    # 目录 -> 前往演奏
    game.capture()                # 让页面切换到难度卡片页
    bot.step()                    # 点目标难度卡片
    assert ("card", "legend") in game.tap_log, game.tap_log
    assert bot.state == bot.ST_START_BUTTON, (bot.state, bot.diagnostic)
    print("难度卡片页: 按卡片上的“传说”文字点到对应卡片")


def test_card_rating_miss_still_skips_done_difficulty():
    """卡片评级首帧漏识别“大音天籁”时，多帧并集要能补回来，别重复演奏。"""

    class FlakyCards(FakeGame):
        misses = 1

        def _texts(self):
            texts = super()._texts()
            if self.page == "cards" and self.misses > 0:
                self.misses -= 1
                for entry in texts:
                    if (entry["text"] == "大音天籁"
                            and abs(entry["left"] + 45 - CARD_X["hard"]) < 20):
                        entry["text"] = "暂无评级"
            return texts

    game = FlakyCards("all", done_by_song={1: {"legend", "master", "hard"}},
                      first_attempt_rate=100.0)
    bot = AlbumBot(game)
    run(bot)
    assert not game.violations, game.violations
    first_song = game.tap_log[:game.tap_log.index(("text", "开始演奏"))]
    assert ("card", "hard") not in first_song, "困难已达标，不该再点它的卡片"
    assert ("card", "normal") in first_song, first_song
    assert "hard" in game.done.get(1, set())
    print("难度卡片: 首帧漏识别“大音天籁”也能靠多帧补回，跳过已达标难度")


def test_startup_requires_album_page():
    class NotAlbum(FakeGame):
        def capture(self):
            return {"width": WIDTH, "height": HEIGHT, "texts": [item("大厅", 640, 60)]}

    game = NotAlbum("legend")
    bot = AlbumBot(game)
    bot.start()
    assert bot.running is False
    assert bot.state == bot.ST_STOPPED
    assert "千音雅集" in bot.diagnostic
    print("启动检查: 未识别到“千音雅集”直接停止")


def test_missing_coordinates_blocks_start():
    class NoCoords(FakeGame):
        def getAlbumConfig(self):
            values = super().getAlbumConfig()
            values[0] = values[1] = -1
            return values

    bot = AlbumBot(NoCoords("legend"))
    bot.start()
    assert bot.running is False
    assert "坐标" in bot.diagnostic
    print("坐标未设置: 脚本拒绝启动并提示先记录坐标")


def test_all_difficulties_already_complete_advances_without_playing():
    game = FakeGame("all", done_by_song={1: {"legend", "master", "hard", "normal"}},
                    first_attempt_rate=100.0)
    bot = AlbumBot(game)
    run(bot)
    assert not game.violations, game.violations
    first_next = game.tap_log.index(("coord", "next_song"))
    first_start = game.tap_log.index(("text", "开始演奏"))
    assert first_start > first_next, "第 1 首四难度已达标时不应再演奏"
    assert game.done[1] == {"legend", "master", "hard", "normal"}
    print("all_difficulties: 四难度已完成时直接从目录切歌，不重复演奏")


def make_frame(entries):
    """entries: (text, x, y) -> an OCR frame in the album's landscape space."""
    from app.src.main.python.python.frame import Frame
    texts = [{"text": text, "left": x - 45, "top": y - 16, "right": x + 45, "bottom": y + 16}
             for text, x, y in entries]
    return Frame.from_host({"width": WIDTH, "height": HEIGHT, "texts": texts, "enemies": []})


def test_read_card_done_maps_per_column():
    bot = AlbumBot(FakeGame("all"))
    frame = make_frame([
        ("普通", CARD_X["normal"], CARD_Y), ("困难", CARD_X["hard"], CARD_Y),
        ("大师", CARD_X["master"], CARD_Y), ("传说", CARD_X["legend"], CARD_Y),
        ("暂无评级", CARD_X["normal"], CARD_RATING_Y),
        ("大音天籁", CARD_X["master"], CARD_RATING_Y),
        ("大音天籁", CARD_X["legend"], CARD_RATING_Y),
        ("开始演奏", START_PLAY[0], START_PLAY[1]),
    ])
    done = bot._read_card_done(frame)
    assert done == {"normal": False, "hard": False, "master": True, "legend": True}, done
    print("难度卡片: 每张卡只读自己那一列的评级，普通/困难未达标、大师/传说已达标")


def test_card_point_falls_back_to_grid():
    bot = AlbumBot(FakeGame("all"))
    # “大师”被 OCR 漏掉：用其余三张卡片拟合出第 4 张的位置，仍落在卡片上。
    frame = make_frame([
        ("普通", CARD_X["normal"], CARD_Y), ("困难", CARD_X["hard"], CARD_Y),
        ("传说", CARD_X["legend"], CARD_Y), ("开始演奏", START_PLAY[0], START_PLAY[1]),
    ])
    x, y, estimated = bot._difficulty_card_point(frame, "master")
    assert estimated is True
    assert abs(x - CARD_X["master"]) < 40, (x, CARD_X["master"])
    assert abs(y - CARD_Y) < 10, (y, CARD_Y)
    # “大音天籁”是评级文字，不能被当成“大师”卡片名。
    frame2 = make_frame([("大音天籁", CARD_X["legend"], CARD_Y + 40),
                         ("开始演奏", START_PLAY[0], START_PLAY[1])])
    assert bot._find_card_name(frame2, "master") is None
    print("难度卡片: 名称漏识别时按网格外推，且不会把“大音天籁”当成“大师”")


def test_rate_parsing():
    bot = AlbumBot(FakeGame("legend"))
    assert bot._read_rate(make_frame([("演奏完成率", 640, 300), ("100.0%", 640, 340)])) == 100.0
    assert bot._read_rate(make_frame([("演奏完成率 98.5%", 640, 300)])) == 98.5
    assert bot._read_rate(make_frame([("演奏完成率", 640, 300), ("100％", 640, 340)])) == 100.0
    # 没有“演奏完成率”标签、又存在多个百分比时必须读不出来，而不是瞎猜。
    assert bot._read_rate(make_frame([("最高完成率 100.0%", 640, 200), ("本次 97.0%", 640, 400)])) is None
    print("完成率解析: 标签+数值、同行、百分号缺失兜底与多值拒绝均正确")


def test_next_song_waits_for_directory_without_alert():
    class NoTitle(FakeGame):
        def capture(self):
            # 目录还在加载：既没有“千音雅集”，也没有别的可点目标。
            return {"width": WIDTH, "height": HEIGHT, "texts": [item("加载中", 640, 300)]}

    game = NoTitle("legend")
    bot = AlbumBot(game)
    bot.running = True
    bot.state = bot.ST_NEXT
    bot._song_index = 3
    for _ in range(30):
        bot.step()
    assert game.tap_log == [], game.tap_log
    assert getattr(game, "alerts", []) == [], "缺少目录时不应发邮件"
    assert bot.state == bot.ST_NEXT
    print("目录检查: 未识别到“千音雅集”时只等待，不点下一首、不发邮件")


def test_overlay_text_is_ignored():
    class OverlayGame(FakeGame):
        def getOverlayBounds(self):
            return [0, 0, 300, 160]

        def capture(self):
            frame = super().capture()
            frame["texts"] = list(frame["texts"]) + [
                item("第1/12首 · 传说 · 已点击“前往演奏”，等待“开始演奏”", 150, 80)]
            return frame

    game = OverlayGame("legend")
    bot = AlbumBot(game)
    bot.start()
    frame = bot._capture()
    assert all("已点击" not in (t.text or "") for t in frame.texts), \
        [t.text for t in frame.texts]
    before = len(game.tap_log)
    bot._tap_xy(150, 80, "悬浮窗内文字")
    assert len(game.tap_log) == before, game.tap_log[-3:]
    print("悬浮窗: 窗内文字被剔除，点击也不会落到悬浮窗上")


if __name__ == "__main__":
    test_startup_requires_album_page()
    test_missing_coordinates_blocks_start()
    test_read_card_done_maps_per_column()
    test_card_point_falls_back_to_grid()
    test_rate_parsing()
    test_next_song_waits_for_directory_without_alert()
    test_overlay_text_is_ignored()
    test_catalog_rows_are_display_only()
    test_card_rating_miss_still_skips_done_difficulty()
    test_difficulty_card_is_tapped_by_name()
    test_legend_only_retries_and_advances()
    test_all_difficulties_priority_and_no_early_advance()
    test_all_difficulties_already_complete_advances_without_playing()
    print("Album automation smoke test passed")
    sys.exit(0)
