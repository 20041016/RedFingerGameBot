# Fix15 — Retry identity-verification email until successful

- Based on the newly uploaded `source.zip` project, preserving its game, verification FSM, decoded-link, lobby-detection, and 10-minute cooldown behavior.
- Identity-verification alert delivery now uses one dedicated retry worker per active verification session.
- The worker waits for the decoded HTTP(S) verification URL, then attempts SMTP delivery.
- If SMTP fails, it retries indefinitely at 60-second intervals while the verification session remains active. There is no three-attempt limit for identity alerts.
- A session is marked as alerted only after `Transport.send` returns successfully and the same verification session is still active.
- A session token prevents a late result from an older verification attempt from marking a newer session as alerted.
- If a new verification session starts during the previous session's retry delay, the worker wakes early and handles the new session without waiting the full minute.
- Once one email succeeds, no more identity-alert emails are sent for that verification session. A later new verification session gets its own one-time alert.
- Generic test-email and stuck-state email retry behavior remains unchanged.

Validation: source inspection, Java structural checks, Python syntax compilation, and ZIP CRC check. Android Gradle release build and device SMTP test were not performed.
