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


# Short two-character words are excluded from fuzzy matching: allowing one error
# there would mean "half the word is wrong" and could tap unrelated UI.
FUZZY_MIN_LEN = 3


def _normalize(text):
    """Drop every whitespace character so OCR spacing/line breaks cannot break matching."""
    if not text:
        return ""
    return "".join(ch for ch in str(text) if not ch.isspace())


def _within_one_edit(left, right):
    """True when the Levenshtein distance between the two strings is at most one."""
    if left == right:
        return True
    if abs(len(left) - len(right)) > 1:
        return False
    if len(left) == len(right):
        misses = 0
        for first, second in zip(left, right):
            if first != second:
                misses += 1
                if misses > 1:
                    return False
        return True
    if len(left) > len(right):
        left, right = right, left
    # right is exactly one character longer than left.
    index = 0
    while index < len(left) and left[index] == right[index]:
        index += 1
    return left[index:] == right[index + 1:]


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

    @property
    def compact_text(self):
        """All OCR text with whitespace removed (tolerates split/spaced labels)."""
        return _normalize(self.full_text)

    def _segments(self):
        segments = []
        for item in self.texts:
            normalized = _normalize(item.text)
            if normalized:
                segments.append((normalized, item))
        return segments

    @staticmethod
    def _same_row(first, second):
        """Only fragments on the same visual line may be joined into one label."""
        first_height = max(1.0, first.bottom - first.top)
        second_height = max(1.0, second.bottom - second.top)
        overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        return overlap >= 0.25 * min(first_height, second_height)

    def _merged_item(self, segments, start, end, label):
        if start > end:
            return None
        if start == end:
            return segments[start][1]
        for index in range(start, end):
            if not self._same_row(segments[index][1], segments[index + 1][1]):
                return None
        items = [segments[index][1] for index in range(start, end + 1)]
        return TextItem(
            label,
            min(item.left for item in items),
            min(item.top for item in items),
            max(item.right for item in items),
            max(item.bottom for item in items),
        )

    @staticmethod
    def _locate(segments, index, length):
        """Map a span of the concatenated text back to its OCR segments."""
        cursor = 0
        start = end = None
        for segment_index, (text, _item) in enumerate(segments):
            segment_end = cursor + len(text)
            if start is None and index < segment_end:
                start = segment_index
            if index + length - 1 < segment_end:
                end = segment_index
                break
            cursor = segment_end
        return start, end

    def _find_merged(self, normalized_needle):
        segments = self._segments()
        if not segments:
            return None
        merged_text = "".join(text for text, _item in segments)
        index = merged_text.find(normalized_needle)
        while index >= 0:
            start, end = self._locate(segments, index, len(normalized_needle))
            if start is not None and end is not None:
                item = self._merged_item(segments, start, end, normalized_needle)
                if item is not None:
                    return item
            index = merged_text.find(normalized_needle, index + 1)
        return None

    def _find_fuzzy(self, normalized_needle):
        segments = self._segments()
        if not segments:
            return None
        merged_text = "".join(text for text, _item in segments)
        target_length = len(normalized_needle)
        # Same length first (one replaced character), then one dropped/extra character.
        for length in (target_length, target_length - 1, target_length + 1):
            if length <= 0:
                continue
            for index in range(len(merged_text) - length + 1):
                candidate = merged_text[index:index + length]
                if not _within_one_edit(candidate, normalized_needle):
                    continue
                start, end = self._locate(segments, index, length)
                if start is None or end is None:
                    continue
                item = self._merged_item(segments, start, end, normalized_needle)
                if item is not None:
                    return item
        return None

    def find(self, needle):
        """Locate text while tolerating OCR spacing, line splits and one wrong character.

        Priority: exact line match -> same line ignoring whitespace -> fragments
        split across lines -> long labels with one mis-recognised character.
        """
        text = str(needle or "")
        if not text:
            return None
        # 1) Keep the original exact behaviour first (precision and tap point).
        for item in self.texts:
            if text in item.text:
                return item
        normalized = _normalize(text)
        if not normalized:
            return None
        # 2) Same line, but ML Kit inserted spaces between Chinese characters.
        for item in self.texts:
            if normalized in _normalize(item.text):
                return item
        # 3) The label was split into several OCR lines (e.g. "返回" + "大厅").
        merged = self._find_merged(normalized)
        if merged is not None:
            return merged
        # 4) Long labels: allow one dropped/replaced character.
        if len(normalized) >= FUZZY_MIN_LEN:
            return self._find_fuzzy(normalized)
        return None

    def has(self, needle):
        return self.find(needle) is not None
