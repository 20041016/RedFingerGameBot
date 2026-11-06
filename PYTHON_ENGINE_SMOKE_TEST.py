"""Small offline smoke test for safety-critical V0.9.3 engine behavior."""
from python.bot import GameBot

class FakeHost:
    def __init__(self, texts):
        self.texts = texts
        self.taps = []
        self.logs = []
        self.saved = []

    def capture(self):
        return {
            "width": 1280,
            "height": 720,
            "texts": [
                {"text": t, "left": x, "top": y, "right": x + 100, "bottom": y + 30}
                for t, x, y in self.texts
            ],
        }

    def tap(self, x, y):
        self.taps.append((x, y))

    def swipe(self, *args):
        pass

    def log(self, message):
        self.logs.append(message)

    def saveOcrLog(self, content):
        self.saved.append(content)
        return "/tmp/ocr_unrecognized.log"


def run():
    host = FakeHost([("未知页面", 100, 100)])
    bot = GameBot(host)
    bot.start()
    bot.step()
    assert not host.taps
    assert bot.state == "识别中"

    host = FakeHost([("继续", 400, 300)])
    bot = GameBot(host)
    bot.start()
    bot.step()
    assert host.taps == [(450, 315)]
    assert any("高优先级点击：继续" in line for line in host.logs)

    host = FakeHost([("特惠", 100, 100), ("开始游戏", 500, 400)])
    bot = GameBot(host)
    bot.start()
    bot.step()
    assert host.taps == [(550, 415)]
    assert bot.state == "大厅"
    print("PASS: unknown-scene safety, global Continue priority, lobby recognition")


if __name__ == "__main__":
    run()
