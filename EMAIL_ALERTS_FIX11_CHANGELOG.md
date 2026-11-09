# Email alerts Fix11

- Test email now reports actual SMTP success/failure in the settings screen instead of only saying settings were saved.
- SMTP exceptions are logged with details; supports implicit SSL on port 465 and STARTTLS on other configured ports (commonly 587).
- Identity alert timestamps are recorded only after successful delivery. Failed deliveries are retried after a 60-second cooldown, not incorrectly treated as sent.
- The first identity alert still waits until the current session's visible original verification URI has been captured, so the email contains the correct current link. Previous-session links are cleared when a new identity-verification session begins.
- Repeated identity alerts are spaced 30 minutes from the last successful delivery.
- Stuck-state alerts are also marked sent only after successful delivery and retry after a 60-second cooldown on failure.
- Existing game, music, verification flow, link file format, and Gradle duplicate-resource exclusions are retained.

Build/device SMTP testing is still required.
