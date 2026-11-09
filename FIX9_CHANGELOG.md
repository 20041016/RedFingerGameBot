Fix9 - Java resource packaging conflict fix

- Retains the Fix8 exclusion for META-INF/NOTICE.md.
- Adds META-INF/LICENSE.md to Android Java resource packaging exclusions because android-mail:1.6.7 and android-activation:1.6.7 both contain that metadata path.
- No mail dependencies or mail reminder behavior changed.
- Android Release build and device SMTP testing must still be run in the user's build environment.
