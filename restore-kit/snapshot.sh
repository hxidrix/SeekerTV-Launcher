#!/usr/bin/env bash
# Read-only: saves the TV's current state to snapshot-<date>/ next to this script, before you change anything.
# Keep the folder. It is what "stock" looked like on YOUR TV, and it is git-ignored.

# TV_IP and ADB come from .env at the repo root (copy .env.example to .env and edit it).
KIT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$KIT/../.env" ] && . "$KIT/../.env"
TV=${TV_IP:?Set TV_IP in .env (copy .env.example to .env first)}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB=adb

"$ADB" connect "$TV" >/dev/null
if ! "$ADB" devices | grep -q "^$TV[[:space:]]*device$"; then
  echo "ERROR: $TV is not listed as 'device' in adb devices. Is USB debugging on, and did you allow this computer on the TV?"
  exit 2
fi

OUT="$KIT/snapshot-$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
sh_adb() { "$ADB" -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

sh_adb getprop ro.product.model            > "$OUT/model.txt"
sh_adb getprop ro.build.fingerprint        > "$OUT/build.txt"
sh_adb pm list packages                    > "$OUT/packages.txt"
sh_adb pm list packages -d                 > "$OUT/packages-disabled.txt"
sh_adb pm list packages -u                 > "$OUT/packages-all-users.txt"
sh_adb cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home.txt"
sh_adb cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-candidates.txt"
for k in "global private_dns_mode" "global window_animation_scale" "global transition_animation_scale" \
         "global animator_duration_scale" "secure enabled_accessibility_services" "secure accessibility_enabled"; do
  echo "$k = $(sh_adb settings get $k)"
done                                       > "$OUT/settings.txt"
sh_adb free -m                             > "$OUT/free.txt"

echo "Saved to $OUT"
echo "Current HOME: $(tail -n 1 "$OUT/home.txt")"
echo "Disabled packages: $(wc -l < "$OUT/packages-disabled.txt" | tr -d ' ')"
