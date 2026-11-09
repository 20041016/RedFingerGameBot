# Fix14 — Lobby-confirmed post-verification cooldown

- Removed the 30-second "verification UI disappeared" completion rule.
- After the verification FSM handles the URI and attempts the physical Back key, it enters a passive wait-for-lobby phase.
- While waiting, the bot does not execute ordinary lobby/game actions (including tapping Start Game), preventing an immediate re-trigger of verification and duplicate email alerts.
- The 10-minute cooldown starts only when the existing OCR scene detector recognizes the lobby signature (`特惠` and `开始游戏`).
- The cooldown deadline remains persisted and BotService continues to avoid screenshots, OCR, taps, swipes, and game logic during the 10-minute cooldown.
- If URI handling was not successful, the wait-for-lobby phase is not entered and the original failure/retry path remains in effect; no cooldown is started.
- Identity-verification email remains one successful alert per verification session; the session stays active until lobby recognition.
- Updated the email settings help text to reflect the current behavior.

Validation: Python syntax compilation, offline Python engine smoke test, and ZIP CRC check. Android Gradle build/device test not performed.
