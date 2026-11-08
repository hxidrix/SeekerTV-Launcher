#!/usr/bin/env bash
# Broken / black / looping home screen: put Google TV's launcher back in charge and reboot.

# TV_IP and ADB come from .env at the repo root (copy .env.example to .env and edit it).
KIT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$KIT/../.env" ] && . "$KIT/../.env"
TV=${TV_IP:?Set TV_IP in .env (copy .env.example to .env first)}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB=adb

"$ADB" connect "$TV"
"$ADB" devices

run() { echo ">> adb shell $1"; "$ADB" -s "$TV" shell "$1"; }

run "pm enable com.google.android.apps.tv.launcherx"
run "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
run "pm enable com.hisense.tv.customerlauncher"
# Launcher swap attempt 2 (2026-10-21) disabled setupwraith's RecoveryActivity guard; turn it back on.
run "pm enable com.google.android.tungsten.setupwraith/.RecoveryActivity"
run "pm enable com.google.android.tungsten.setupwraith"
run "settings put secure enabled_accessibility_services ''"
run "input keyevent KEYCODE_HOME"

# PackageManager saves enable/HOME changes to disk ~10 s later. Rebooting sooner loses them
# (happened 2026-10-21: TV came back with launcherx still disabled).
echo ">> waiting 15 s for the TV to save the changes"
sleep 15
"$ADB" -s "$TV" shell dumpsys activity activities | grep -m1 ResumedActivity

echo ">> adb reboot"
"$ADB" -s "$TV" reboot
