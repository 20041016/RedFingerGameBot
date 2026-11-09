from dataclasses import dataclass
from math import isfinite

@dataclass(frozen=True)
class CoordinateMapper:
    screen_width: int
    screen_height: int
    reference_width: int = 1280
    reference_height: int = 720
    aspect_tolerance: float = 0.035

    def __post_init__(self):
        if min(self.screen_width, self.screen_height,
               self.reference_width, self.reference_height) <= 0:
            raise ValueError("宽高必须大于 0")

    @property
    def scale_x(self):
        return self.screen_width / self.reference_width

    @property
    def scale_y(self):
        return self.screen_height / self.reference_height

    @property
    def aspect_warning(self):
        current = self.screen_width / self.screen_height
        reference = self.reference_width / self.reference_height
        return abs(current / reference - 1.0) > self.aspect_tolerance

    def to_screen(self, x, y):
        if not (isfinite(float(x)) and isfinite(float(y))):
            raise ValueError("坐标必须是有限数值")
        return (
            max(0, min(self.screen_width - 1, round(float(x) * self.scale_x))),
            max(0, min(self.screen_height - 1, round(float(y) * self.scale_y))),
        )

    def to_reference(self, x, y):
        return float(x) / self.scale_x, float(y) / self.scale_y

    def tap(self, host, x, y):
        host.tap(*self.to_screen(x, y))

    def swipe(self, host, x1, y1, x2, y2, duration_ms):
        a, b = self.to_screen(x1, y1), self.to_screen(x2, y2)
        host.swipe(a[0], a[1], b[0], b[1], int(duration_ms))
