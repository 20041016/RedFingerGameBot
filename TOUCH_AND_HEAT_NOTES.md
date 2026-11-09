# Rhythm mode: touch latency, heat, and removal of the prediction feature

- Prediction feature removed (Java, Python, settings window). `music_bot.py` is back to the original reactive logic
  (press while judge-pixel blue < 220), i.e. the same logic as the Auto.js script.
- Touch: `RootTouchController` writes binary input events to the device node through a persistent root `cat`
  (24 B events on 64-bit, 16 B on 32-bit, chosen by a self-test) instead of spawning one `sendevent` process per
  event; automatic fallback to `sendevent`. Status line shows `模式=直写24B/16B` or `模式=sendevent(...)`.
- Heat: the six lane pixels were read from a 1600x720 @60 FPS stream that was
  (a) allocated as a new 3.4 MB array every frame (GC churn), (b) copied again into an OpenCV Mat just to read six pixels.
  Now: 960x432 stream (about 1/3 of the data, aspect exactly 3200x1440, STREAM_SCALE = 0.3), one reused frame buffer,
  pixels read directly from the byte array, and the floating ball no longer runs a continuous animation.
  To go back to the old stream size change STREAM_WIDTH/STREAM_HEIGHT in `RootScreenCapture.java`
  (the sampling scale follows automatically).
- Tested offline: binary event stream (24/16 B, fallback), fake raw frame stream (buffer reuse, pixel sampling).
  Not run on a device.
