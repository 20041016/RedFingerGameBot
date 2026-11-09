# Enemy tracking (centre the nearest enemy)

Flow: `RootHost.capture()` -> `EnemyDetector` (YOLOv8n ONNX on OpenCV DNN) -> `frame.enemies`
-> `GameBot._track_enemy()` in `_battle()` -> horizontal correction swipe (same convention and
`view_gain` as the signal-zone alignment).

## Files
- `EnemyDetector.java` (new), `RootHost.java` (detectEnemies / saveTrainingFrame hooks)
- `python/frame.py` (Enemy + Frame.enemies), `python/config.py` (TRACK_* constants), `python/bot.py`
- `tools/train_enemy.py` (PC training/export), `ENEMY_TRACKING_TEST.py` (offline logic test)

## Workflow
1. Set `DATASET_COLLECT_INTERVAL_MS = 1500` in `config.py`, let the bot run in battle scenes.
2. Pull `/sdcard/Android/data/com.redfinger.gamebot/files/dataset/*.jpg`, label enemies (YOLO format).
3. Run `tools/train_enemy.py`; push `best.onnx` as `.../files/enemy.onnx`.
4. Without that file the detector stays off and behaviour is unchanged (logcat tag `RFG-RootHost`).

## Tuning
- `view_gain` (existing game setting) and `TRACK_MAX_STEP_PX`: lower them if the view oscillates.
- `TRACK_SETTLE_MS`: raise if the camera is still moving when the next frame is analysed.
- `TRACK_MIN_CONF` / `ENEMY_CONF` (Java): raise to reduce false positives.
- `ENEMY_INPUT_SIZE` (Java) must equal the training `IMGSZ`.

## Not verified
Java code was not compiled and nothing was run on a device; only the Python logic has an offline test.
Detection runs in the same loop as OCR, so loop rate = screencap + OCR + detection time.
