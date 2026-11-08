# Contributing

Issues and pull requests are welcome.

- **Bugs:** say which TV (model, Android version), which APK flavor and version, and what you pressed. `adb logcat -s SeekerTV` output helps a lot.
- **Code:** Kotlin, no extra libraries, everything drawn on a Canvas. Match the style of the file you're in. Shared code goes in `launcher/app/src/main`; a game mode lives in its own `src/mode<Name>` source set and is listed in each flavor's `BuiltModes.kt`.
- **Design first:** new modes or big visual changes start as an HTML mockup in `design/` that runs in a browser. The Android code ports the approved mockup.
- **Art and sound** must be original. No ripped sprites, logos, fonts or samples.
- **Never commit** keystores, `.env`, `local.properties`, snapshots, or anything with your TV's IP address or your network details.
- Test on a real TV if you can, and say in the PR what you tested on.
