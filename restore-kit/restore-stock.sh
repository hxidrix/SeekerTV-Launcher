#!/usr/bin/env bash
# Undo every change the Seeker install makes (and the optional debloat in expected-state.txt), newest first, then reboot.
# Safe to re-run: enabling an already-enabled package or re-putting a setting is harmless.
# A failed step is reported and the script carries on with the next one.

# TV_IP and ADB come from .env at the repo root (copy .env.example to .env and edit it).
KIT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$KIT/../.env" ] && . "$KIT/../.env"
TV=${TV_IP:?Set TV_IP in .env (copy .env.example to .env first)}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB=adb

failed=0
step() { # description, then the adb shell command as one string
  echo
  echo ">> $1"
  echo "   adb shell $2"
  if ! "$ADB" -s "$TV" shell "$2"; then
    echo "   !! step failed, continuing"
    failed=$((failed + 1))
  fi
}

"$ADB" connect "$TV"
if ! "$ADB" devices | grep -q "^$TV[[:space:]]*device$"; then
  echo "ERROR: $TV is not listed as 'device' in adb devices."
  echo "Check the TV is on, on Wi-Fi, and USB debugging is still enabled."
  exit 1
fi
echo "Connected to $TV. Restoring stock state..."

# --- 2026-10-21 16:41 launcher swap attempt 2 (launcherx + setupwraith disabled, HOME = Projectivy)
step "Enable Google TV launcher" "pm enable com.google.android.apps.tv.launcherx"
step "Make Google TV launcher HOME again" "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
step "Enable setup wizard (setupwraith)" "pm enable com.google.android.tungsten.setupwraith"

# --- 2026-10-20 01:42 Projectivy accessibility override (was "" and 0 before)
step "Turn accessibility back off" "settings put secure accessibility_enabled 0"
step "Clear accessibility services (was an empty string)" "settings put secure enabled_accessibility_services ''"
# Projectivy itself (com.spocky.projengmenu) was installed from Play, not in the change log.
# To remove it too, uncomment:
# step "Uninstall Projectivy" "pm uninstall com.spocky.projengmenu"

# --- Batch 4 (01:25)
step "Bring back Smartsense for user 0" "cmd package install-existing com.hisense.tv.smartsense"
step "Enable Smartsense" "pm enable com.hisense.tv.smartsense"

# --- Batch 3 (01:08-01:09), reverse order
step "Remove AdGuard Private DNS hostname" "settings delete global private_dns_specifier"
step "Private DNS back to automatic" "settings put global private_dns_mode opportunistic"
step "Bring back HomeKit bridge for user 0" "cmd package install-existing com.vt.tv.homekitproxy"
step "Enable HomeKit bridge" "pm enable com.vt.tv.homekitproxy"
step "Bring back DLNA for user 0" "cmd package install-existing com.hisense.tv.dlna"
step "Enable DLNA" "pm enable com.hisense.tv.dlna"
step "Enable Google TV / Play Movies" "pm enable com.google.android.videos"
step "Enable MStar disclaimer" "pm enable com.mstar.android.tv.disclaimercustomization"
step "Enable Google Calendar sync" "pm enable com.google.android.syncadapters.calendar"
step "Enable Smartsense UI" "pm enable com.vt.tv.smartsense.ui"
step "Enable Smartsense service" "pm enable com.hisense.tv.smartservice"
# (com.hisense.tv.smartsense was enabled above in the batch 4 undo)

# --- Batch 2 (00:54-00:55), reverse order
step "Enable Play Auto Installs config" "pm enable android.autoinstalls.config.google.gtvpai"
# (homekitproxy and dlna were enabled above in the batch 3 undo)
step "Enable Miracast" "pm enable com.vt.miracast"
step "Enable Ambient Mode (dreamx)" "pm enable com.google.android.apps.tv.dreamx"
step "Screensaver default back to Ambient Mode" "settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop"
step "Screensaver back to Ambient Mode" "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop"
step "Enable YouTube Music" "pm enable com.google.android.youtube.tvmusic"
# Fully uninstalled Play apps. adb can't bring these back:
#   YouTube TV (com.google.android.youtube.tvunplugged) - reinstall from Play Store
#   Spotify    (com.spotify.tv.android)                 - reinstall from Play Store
#   ESPN       (com.espn.score_center)                  - reinstall from Play Store
echo
echo ">> ESPN, Spotify and YouTube TV were fully uninstalled: reinstall them from the Play Store if wanted."

# --- Batch 1 (00:30-00:32), reverse order
step "Animator duration scale back to 1x" "settings put global animator_duration_scale 1.0"
step "Transition animation scale back to 1x" "settings put global transition_animation_scale 1.0"
step "Window animation scale back to 1x" "settings put global window_animation_scale 1.0"
step "Enable VIDAA home-screen feed" "pm enable com.vidaa.oemtabcontentprovider"
step "Enable VIDAA channels app" "pm enable net.vidaatv.hisense.android.tv"
step "Enable Odin browser" "pm enable com.hisense.hibrowser"
step "Enable Play Games" "pm enable com.google.android.play.games"
step "Enable print spooler" "pm enable com.android.printspooler"
step "Enable Hisense ACR" "pm enable com.hisense.evservice"
step "Enable retail demo" "pm enable fusion.android.tv.demo"
step "Enable Google feedback" "pm enable com.google.android.feedback"

echo
if [ "$failed" -gt 0 ]; then
  echo "$failed step(s) failed (see '!!' above). Rebooting anyway."
else
  echo "All steps OK."
fi
# PackageManager saves enable/HOME changes to disk ~10 s later. Rebooting sooner loses them.
echo ">> waiting 15 s for the TV to save the changes"
sleep 15
echo ">> Rebooting the TV"
"$ADB" -s "$TV" reboot
