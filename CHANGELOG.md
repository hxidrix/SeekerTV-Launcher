# Changelog

## v1.0 (2026-11-08)

Customized Seeker v1.0 TV wheel release.

- Updated app identity and Android package version to Seeker v1.0.
- Added customizable app titles and colors, wallpaper controls, gradient controls, and the infinite-scroll toggle.
- Focused the documented build and exported APK on the TV wheel flavor.

## v0.9.0 (2026-10-22)

First public release.

- **Modes:** WHEEL (dot-matrix app wheel with greeting, clock, date and weather), FLAP (fly through an app's tube to open it), INVADERS (shoot an app to open it), HUNT (8-bit duck hunt: tap OK to shoot, 1 in 4 hits; hold OK to miss).
- **Flavors:** `wheel`, `flap`, `invaders`, `hunt` and `all`. Each compiles only its game modes; HOME MODE lists only what's built in. Same app id, so flavors install over each other.
- **New settings:** NAME (greeting HELLO &lt;NAME&gt;, default SEEKER) and WEATHER LOCATION (any city via Open-Meteo's geocoder, default Denver). Both can also be set with the `com.seeker.tv.SET` broadcast.
- Clock and weather follow the TV's time zone.
- Release APKs are signed with the project's release key (`seeker-<flavor>-0.9.0.apk`).
- Restore kit reads the TV address from `.env`; new read-only `snapshot.sh`.
- The README documents building, exporting, and installing the APK.
