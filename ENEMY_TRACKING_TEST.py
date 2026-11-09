"""Offline test for enemy tracking in GameBot._battle (no Android needed)."""
import sys
sys.path.insert(0, "app/src/main/python")
from python.bot import GameBot


class FakeHost:
    def __init__(self):
        self.enemies = []
        self.texts = [("信号区", 600, 300)]
        self.swipes, self.taps, self.collected = [], [], 0

    def getGameCoordinateConfig(self):
        return [1066, 194, 1231, 503, 960, 360, 660, 360, 50, 300]

    def capture(self):
        return {"width": 1280, "height": 720, "enemies": self.enemies,
                "texts": [{"text": t, "left": x, "top": y, "right": x + 100, "bottom": y + 30}
                          for t, x, y in self.texts]}

    def tap(self, x, y): self.taps.append((x, y))
    def swipe(self, x1, y1, x2, y2, d): self.swipes.append((x1, y1, x2, y2, d))
    def log(self, m): pass
    def saveTrainingFrame(self): self.collected += 1


def enemy(cx, conf=0.9):
    return {"cx": cx, "cy": 360, "w": 80, "h": 160, "conf": conf}


def make():
    h = FakeHost(); b = GameBot(h); b.start(); b.step()  # adopt battle scene
    h.swipes.clear(); h.taps.clear(); b.track_next_ms = 0
    return h, b


# 1) enemy on the right of centre -> swipe turns toward it, signal logic skipped
h, b = make()
h.enemies = [enemy(1000)]
b.step()
assert len(h.swipes) == 1 and not h.taps, (h.swipes, h.taps)
x1, y1, x2, y2, d = h.swipes[0]
assert x2 > x1  # enemy right of centre -> positive swipe delta (same convention as signal zone)
assert abs(x2 - x1) <= 240 + 1e-6 and y1 == y2 and d == 150
assert "追踪敌人" in b.diagnostic

# 2) opposite side gives opposite direction
h2, b2 = make(); h2.enemies = [enemy(200)]; b2.step()
assert (h.swipes[0][2] - h.swipes[0][0]) * (h2.swipes[0][2] - h2.swipes[0][0]) < 0

# 3) pacing: no second swipe until settle time has passed
h.enemies = [enemy(1000)]; b.step()
assert len(h.swipes) == 1
b.track_next_ms = 0; b.step()
assert len(h.swipes) == 2

# 4) enemy already centred (inside dead zone) -> no swipe, but frame still 'handled'
h3, b3 = make(); h3.enemies = [enemy(640 + 20)]; b3.step()
assert not h3.swipes and not h3.taps

# 5) low confidence ignored -> falls back to signal-zone logic (sprint tap)
h4, b4 = make(); h4.texts = [("信号区", 100, 300)]; h4.enemies = [enemy(1000, conf=0.2)]; b4.step()
assert len(h4.swipes) == 1 and h4.swipes[0][4] == 200, h4.swipes  # signal-zone alignment, not tracking

# 6) several enemies -> the one nearest the centre is followed
h5, b5 = make(); h5.enemies = [enemy(1200), enemy(700), enemy(100)]; b5.step()
assert len(h5.swipes) == 1 and h5.swipes[0][2] > h5.swipes[0][0]  # followed the one at x=700

# 7) no enemies -> normal signal-zone behaviour untouched
h6, b6 = make(); h6.texts = [("信号区", 100, 300)]; b6.step()
assert len(h6.swipes) == 1 and h6.swipes[0][4] == 200

# 8) dataset collection obeys the interval and only runs in battle states
import python.bot as botmod
botmod.DATASET_COLLECT_INTERVAL_MS = 1000
h7, b7 = make(); b7.last_collect_ms = -10**9
b7.step(); assert h7.collected == 1
b7.step(); assert h7.collected == 1  # interval not elapsed
print("PASS: enemy tracking (direction, pacing, dead zone, confidence, target choice, fallback, dataset)")
