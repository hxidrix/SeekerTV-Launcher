#!/usr/bin/env bash
# Read-only check: compares the TV against expected-state.txt and prints PASS/DRIFT.
# Run after a firmware OTA, a Play Store update, or any time the TV acts differently.
# It changes nothing on the TV. Each DRIFT line shows the command that re-applies it.

# TV_IP and ADB come from .env at the repo root (copy .env.example to .env and edit it).
KIT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$KIT/../.env" ] && . "$KIT/../.env"
TV=${TV_IP:?Set TV_IP in .env (copy .env.example to .env first)}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
DIR="$(cd "$(dirname "$0")" && pwd)"
EXPECTED="$DIR/expected-state.txt"

sh_adb() { "$ADB" -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

echo "== Connecting to $TV"
"$ADB" connect "$TV" >/dev/null
if ! "$ADB" devices | grep -q "^$TV[[:space:]]*device$"; then
  echo "ERROR: $TV is not listed as 'device' in adb devices."
  echo "Check the TV is on, on Wi-Fi, and USB debugging is still enabled."
  exit 2
fi

echo "== Reading TV state (read-only)"
DISABLED=$(sh_adb pm list packages -d | sed 's/^package://')
INSTALLED=$(sh_adb pm list packages | sed 's/^package://')
ALLUSERS=$(sh_adb pm list packages -u | sed 's/^package://')
PS=$(sh_adb ps -A)
HOME_NOW=$(sh_adb cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)

pass=0
drift=0
report() { # status label detail fix
  if [ "$1" = PASS ]; then
    pass=$((pass + 1))
    printf 'PASS   %s\n' "$2"
  else
    drift=$((drift + 1))
    printf 'DRIFT  %s (%s)\n       fix: %s\n' "$2" "$3" "$4"
  fi
}
has() { grep -qxF "$2" <<<"$1"; }

while IFS='|' read -r kind key want fix; do
  case "$kind" in ''|\#*) continue ;; esac
  case "$kind" in
    disabled)
      if has "$DISABLED" "$key"; then report PASS "disabled: $key"
      elif has "$INSTALLED" "$key"; then report DRIFT "disabled: $key" "enabled again" "$fix"
      else report DRIFT "disabled: $key" "missing from the package list" "adb shell cmd package install-existing $key && $fix"
      fi ;;
    removed_user0)
      if has "$INSTALLED" "$key"; then report DRIFT "removed for user 0: $key" "reinstalled for user 0" "$fix"
      else report PASS "removed for user 0: $key"
      fi ;;
    removed)
      if has "$ALLUSERS" "$key"; then report DRIFT "uninstalled: $key" "installed again" "$fix"
      else report PASS "uninstalled: $key"
      fi ;;
    setting)
      # shellcheck disable=SC2086
      got=$(sh_adb settings get $key)
      if [ "$got" = "$want" ]; then report PASS "setting: $key = $want"
      else report DRIFT "setting: $key" "is '$got', expected '$want'" "$fix"
      fi ;;
    accepted_setting)
      # shellcheck disable=SC2086
      got=$(sh_adb settings get $key)
      if [ "$got" = "$want" ]; then report PASS "setting: $key = $want"
      else report PASS "setting: $key = $got (accepted drift, target $want)"
      fi ;;
    no_process)
      hits=$(grep -i -- "$key" <<<"$PS" | awk '{print $NF}' | sort -u | tr '\n' ' ')
      if [ -z "$hits" ]; then report PASS "not running: *$key*"
      else report DRIFT "not running: *$key*" "running: $hits" "$fix"
      fi ;;
    home)
      if [ "$HOME_NOW" = "$want" ]; then report PASS "HOME resolves to $want"
      else report DRIFT "HOME resolution" "is '$HOME_NOW', expected '$want'" "$fix"
      fi ;;
  esac
done <"$EXPECTED"

echo
echo "== $pass PASS, $drift DRIFT"
[ "$drift" -eq 0 ] && echo "Everything matches the workbench." || echo "Run the 'fix' lines above (one at a time), then adb reboot and re-run this check."
exit $(( drift > 0 ? 1 : 0 ))
