# Fix8 - Android Release packaging conflict

- Fixes the release build failure at `:app:mergeReleaseJavaResource` caused by the duplicate `META-INF/NOTICE.md` resource present in both `com.sun.mail:android-mail:1.6.7` and `com.sun.mail:android-activation:1.6.7`.
- Adds an Android Gradle resource packaging exclusion for `META-INF/NOTICE.md`.
- No email reminder logic, SMTP configuration, verification state machine, game automation, music engine, or URL persistence behavior was intentionally changed.

Build validation: ZIP integrity and source/configuration inspection only. A full Android Gradle release build still needs to be run in the user's build environment.
