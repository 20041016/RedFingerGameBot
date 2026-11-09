"""OCR frame adapter compatible with Python containers and Chaquopy Java objects."""
from dataclasses import dataclass


def _map_get(source, key, default=None):
    """Read a mapping value from either a Python dict or java.util.Map.

    Chaquopy may expose java.util.HashMap as a Java object rather than converting
    it to a Python dict. Java Map.get(key) is supported, but [] and the Python
    dict.get(key, default) calling convention are not guaranteed.
    """
    if source is None:
        return default
    if isinstance(source, dict):
        return source.get(key, default)

    getter = getattr(source, "get", None)
    if callable(getter):
        try:
            value = getter(key)
            if value is not None:
                return value
            contains_key = getattr(source, "containsKey", None)
            if callable(contains_key) and contains_key(key):
                return None
        except Exception:
            pass
    return default


def _as_sequence(value):
    """Convert Python or Java list/array-like values into an iterable."""
    if value is None:
        return ()
    if isinstance(value, (list, tuple)):
        return value

    to_array = getattr(value, "toArray", None)
    if callable(to_array):
        try:
            return to_array()
        except Exception:
            pass

    try:
        return iter(value)
    except TypeError:
        iterator = getattr(value, "iterator", None)
        if callable(iterator):
            result = []
            it = iterator()
            while it.hasNext():
                result.append(it.next())
            return result
        raise TypeError("OCR texts 必须是 Python 序列或 Java List")


@dataclass(frozen=True)
class TextItem:
    text: str
    left: float = 0
    top: float = 0
    right: float = 0
    bottom: float = 0

    @property
    def center_x(self):
        return (self.left + self.right) / 2

    @property
    def center_y(self):
        return (self.top + self.bottom) / 2


@dataclass(frozen=True)
class Enemy:
    """Detected enemy box centre/size in screenshot pixels."""
    cx: float
    cy: float
    w: float
    h: float
    conf: float


@dataclass(frozen=True)
class Frame:
    width: int
    height: int
    texts: tuple
    enemies: tuple = ()

    @classmethod
    def from_host(cls, raw):
        if raw is None:
            raise ValueError("截图接口返回了空数据")

        width_value = _map_get(raw, "width")
        height_value = _map_get(raw, "height")
        if width_value is None or height_value is None:
            raise ValueError("截图数据缺少 width 或 height")
        width, height = int(width_value), int(height_value)
        if width <= 0 or height <= 0:
            raise ValueError("截图宽高必须大于 0")

        items = []
        raw_texts = _as_sequence(_map_get(raw, "texts", ()))
        for item in raw_texts:
            bounds = _map_get(item, "bounds", item)
            text = _map_get(item, "text", "")
            items.append(TextItem(
                str(text or ""),
                float(_map_get(bounds, "left", 0) or 0),
                float(_map_get(bounds, "top", 0) or 0),
                float(_map_get(bounds, "right", 0) or 0),
                float(_map_get(bounds, "bottom", 0) or 0),
            ))

        enemies = []
        for e in _as_sequence(_map_get(raw, "enemies", ())):
            enemies.append(Enemy(
                float(_map_get(e, "cx", 0) or 0),
                float(_map_get(e, "cy", 0) or 0),
                float(_map_get(e, "w", 0) or 0),
                float(_map_get(e, "h", 0) or 0),
                float(_map_get(e, "conf", 0) or 0),
            ))
        return cls(width, height, tuple(items), tuple(enemies))

    @property
    def full_text(self):
        return " ".join(x.text for x in self.texts)

    def find(self, needle):
        return next((x for x in self.texts if needle in x.text), None)

    def has(self, needle):
        return self.find(needle) is not None
