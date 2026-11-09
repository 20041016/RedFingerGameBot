# Floating controller v2

New files: `FloatingBallView.java` (ball icon), `GlyphButton.java` (canvas-drawn icon buttons + resize grip),
`ScaleLayout.java` (uniform scaling container). `BotService.java`: showOverlay / drag / resize / status styling.

- Look: crosshair-emblem ball (grey idle, green + pulsing ring while running), dark glass pill with soft shadow,
  canvas-drawn play/pause/target/chevron/close icons, bold state line + dimmed diagnostic line.
- Drag: the whole controller is draggable (not just the dot); touch-slop, frame-coalesced updates (Choreographer),
  clamped to the screen, collapsed ball snaps to the nearest side edge, position is remembered.
  Main cause of the old lag: service-created overlay windows are not hardware accelerated unless
  FLAG_HARDWARE_ACCELERATED is set (now set on all three overlay windows).
- Resize: drag the grip at the right end of the expanded bar. Scale 0.6x-1.8x (also limited to screen width),
  applied live without rebuilding views, remembered. The coordinate-settings windows keep their own fixed size.
- Not compiled with the Android SDK here (parse + stub type-check only); no device test.
