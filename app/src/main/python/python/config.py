REFERENCE_WIDTH = 1280
REFERENCE_HEIGHT = 720
RUN_BUTTON = (1066, 194)
EXTRA_BUTTON = (1231, 503)
JUMP_WAIT_MS = 20_000
FLIGHT_LOST_THRESHOLD = 5
SIGNAL_WAIT_MS = 3_000
CONTINUE_CONFIRM_MS = 5_000
EXTRA_CLICK_MIN_MS = 5_000
EXTRA_CLICK_MAX_MS = 15_000
DEAD_ZONE_REFERENCE_PX = 50
SEARCH_SWIPE_DURATION_MS = 400
SEARCH_SWIPE_PAUSE_MS = 600
HIGH_PRIORITY_TEXTS = ("确定", "继续", "放弃", "忽略", "重试")

# Enemy tracking (keep the nearest enemy horizontally centred).
TRACK_ENEMY_ENABLED = True
TRACK_MIN_CONF = 0.45          # ignore detections below this confidence
TRACK_SWIPE_MS = 150           # duration of one correction swipe
TRACK_SETTLE_MS = 250          # wait after a swipe so the camera settles before the next one
TRACK_MAX_STEP_PX = 240        # max swipe length in reference pixels (anti-overshoot)
# Training-data collection while the bot runs (0 = off). Saves a JPEG every N ms in battle scenes.
DATASET_COLLECT_INTERVAL_MS = 0

# Game-mode coordinate defaults are loaded from the separate game_coordinates preference store.
