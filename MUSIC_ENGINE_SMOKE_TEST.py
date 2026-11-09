"""Host-side smoke test for the six-lane Python engine (does not test Android sendevent)."""
from app.src.main.python.python.music_bot import MusicBot


class FakeHost:
    def __init__(self):
        self.blue = [255] * 6
        self.down = []
        self.up = []
        self.released = False
        self.active = set()

    def getMusicCoordinateConfig(self):
        return [778, 1100, 1440, 1780, 2100, 2440, 1210, 230, -1]

    def initializeMultiTouch(self, width, height):
        assert (width, height) == (1440, 3200)
        return "fake-type-b-device"

    def captureBlueValues(self, xs, y):
        assert xs == [778, 1100, 1440, 1780, 2100, 2440]
        assert y == 1210
        return {"blue": self.blue}

    def updateTouches(self, states, xs, ys):
        assert len(states) == len(xs) == len(ys) == 6
        for i, state in enumerate(states):
            if state and i not in self.active:
                self.down.append((xs[i], ys[i], i))
            elif not state and i in self.active:
                self.up.append(i)
        self.active = {i for i, state in enumerate(states) if state}
        return 0

    def releaseAllTouches(self):
        self.released = True

    def log(self, message):
        pass


host = FakeHost()
bot = MusicBot(host)
bot.start()
host.blue = [100, 100, 255, 100, 255, 100]
bot.step()
assert [x[2] for x in host.down] == [0, 1, 3, 5]
assert [x[1] for x in host.down] == [778, 1100, 1780, 2440]
host.blue = [255] * 6
bot.step()
assert sorted(host.up) == [0, 1, 3, 5]
bot.stop()
assert host.released
print("Music engine smoke test passed")
