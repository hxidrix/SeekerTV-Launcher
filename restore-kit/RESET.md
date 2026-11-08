# TV reset guide

Written on the reference TV (Hisense 55A53FUA, Google TV, Android 11). Every script here talks to the TV over Wi-Fi with adb from your computer, at the address in `.env` (`TV_IP`, see `.env.example`). The TV has to be on and connected to the home network.

## (a) Which script when

| Situation | Use |
|---|---|
| A firmware update or Play Store update just happened, or something "came back" | `post-update-check.sh`. Read-only. It prints PASS or DRIFT for each item, and a fix command for each DRIFT |
| Home screen is black, looping, or keeps crashing (for example after a launcher swap) | `panic-home.sh`. Puts Google TV's own home screen back in charge, turns off accessibility overrides, and reboots |
| An app or feature broke and you want everything back the way Hisense shipped it | `restore-stock.sh`. Undoes every change in `expected-state.txt` (the reference debloat plus the Seeker install) and reboots. ESPN, Spotify and YouTube TV have to be reinstalled from the Play Store by hand |
| Nothing works, adb can't connect, or you want a clean slate | Factory reset, (c) or (d) below |

If only a streaming app stalls at ad breaks or won't sign in, try undoing the AdGuard DNS first, before running anything bigger:
`adb shell settings put global private_dns_mode opportunistic`

## (b) Running them

Open a terminal in the repo folder (on a Mac: Cmd+Space, type Terminal, press Return, then `cd` to the repo), then paste one of these:

```
cd restore-kit
./post-update-check.sh
```

```
cd restore-kit
./panic-home.sh
```

```
cd restore-kit
./restore-stock.sh
```

All three read `TV_IP` from `.env` and connect by themselves. If a script says the TV isn't listed as `device`:

```
adb disconnect
adb connect <TV_IP>:5555
adb devices
```

If it says `unauthorized`, look at the TV: an "Allow USB debugging?" prompt is waiting. Tick "Always allow" and press OK. If it says `connection refused`, USB debugging is off (see (e)). If the TV got a new IP address from the router, update `TV_IP` in `.env`.

After `panic-home.sh` or `restore-stock.sh`, the TV reboots. Give it about a minute.

## (c) Factory reset from the TV menu

On this build: **Settings (gear) > System > About > Factory reset**, then confirm with **Factory reset** and **Erase everything**.

How this was checked: `dumpsys package com.android.tv.settings` shows the build has the standard Google TV reset screens (`com.android.tv.settings/.device.StorageResetActivity`, and the Hisense entry point `.oemlink.FactoryResetActivity` for `android.settings.FACTORY_RESET_TV_OEM_LINK`). Those are the screens behind System > About > Factory reset on Google TV. The menu text wasn't looked at on screen, because this session was read-only. If the item isn't under About, look under **System > Reset** or **Device Preferences > Reset**.

The TV may ask for the PIN if one is set.

## (d) Factory reset if the screen is unusable

1. **Open Settings over adb**, then use the remote to work through (c):
   ```
   adb connect <TV_IP>:5555
   adb shell am start -a android.settings.SETTINGS
   ```
   If the home screen is the problem but Settings draws fine, run `panic-home.sh` first. It's far less drastic than a reset.

2. **Reboot into recovery mode**:
   ```
   adb reboot recovery
   ```
   On the recovery screen, use the remote's **Up/Down** arrows to highlight **Wipe data/factory reset**, press **OK**, confirm (**Yes**), and when it finishes choose **Reboot system now**.
   **Not verified on this TV.** The Hisense recovery menu and its key sequence weren't tested here (that would mean rebooting into it). Some Hisense models ignore the Bluetooth remote in recovery. In that case use the joystick/power button under the TV's front edge: a short press moves down, a long press selects.

3. **If adb can't connect at all**, get into recovery with the TV's own button. Reported for Hisense Android/Google TVs but not tested on this model: unplug the TV, press and hold the power button on the TV itself, plug it back in while still holding, and let go when the recovery menu or the Hisense logo shows. Then do the same as step 2.

## (e) After a factory reset

- **What comes back:** everything, as stock. All 17 disabled packages are enabled again. DLNA, HomeKit and Smartsense are back. Private DNS returns to automatic, the screensaver to Ambient Mode, animations to 1x. Projectivy and its accessibility override are gone. Netflix, Hulu, Max etc. return only if Google restores them during setup. Otherwise reinstall them from the Play Store. ESPN, Spotify and YouTube TV may come back through Google's setup auto-installs.
- **What you'll need:** your Google account password (or your phone to approve sign-in), the Wi-Fi password, and logins for every streaming app (Netflix, Hulu, Max, Disney+, Prime Video, Peacock, Apple TV+, YouTube).
- **adb won't work again until you turn it back on:**
  1. Settings > System > About > scroll to **Android TV OS build** and press OK on it 7 times, until it says you're a developer.
  2. Settings > System > **Developer options** > turn on **USB debugging** (and **Network debugging / ADB over network** if it's listed).
  3. Run `adb connect <TV_IP>:5555`, then approve the prompt on the TV with "Always allow".
  4. Check the TV's IP address (Settings > Network) still matches `TV_IP` in `.env`.
- After that, the TV is a clean slate. To install Seeker again, follow the install steps in the project README.
