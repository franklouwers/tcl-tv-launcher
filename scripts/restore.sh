#!/bin/bash
# Undo everything: re-enable every package listed in backup/disabled-by-us.txt,
# make the stock Google TV launcher the default home again, and optionally
# remove the custom launcher.
#   scripts/restore.sh                      re-enable all + stock home
#   scripts/restore.sh --uninstall-launcher also uninstall the custom launcher
#   scripts/restore.sh com.tcl.gallery      re-enable just these packages
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/backup/disabled-by-us.txt"
TV="$ROOT/scripts/tv"
STOCK_LAUNCHER="com.google.android.apps.tv.launcherx"
STOCK_HOME="$STOCK_LAUNCHER/.home.HomeActivity"
SETUP_WIZARD="com.google.android.tungsten.setupwraith"
LAUNCHER_PKG="be.kiwazo.tvhome"

UNINSTALL=0
PKGS=()
for a in "$@"; do
  case "$a" in
    --uninstall-launcher) UNINSTALL=1 ;;
    *) PKGS+=("$a") ;;
  esac
done

# Never re-enable the setup wizard without the stock launcher: its
# RecoveryActivity then loops as HOME and crashes system_server.
for pkg in "${PKGS[@]}"; do
  if [ "$pkg" = "$SETUP_WIZARD" ]; then
    PKGS=("$STOCK_LAUNCHER" "${PKGS[@]}")
    break
  fi
done

if [ ${#PKGS[@]} -gt 0 ]; then
  for pkg in "${PKGS[@]}"; do
    echo "$pkg: $("$TV" shell "pm enable --user 0 $pkg" 2>&1 | tr -d '\n')"
  done
  exit 0
fi

[ -f "$LOG" ] || { echo "Nothing to restore: $LOG not found"; exit 0; }

# Stock launcher first (see above), then everything else.
# One pm call per package; chained pm commands can silently stop early.
echo "$STOCK_LAUNCHER: $("$TV" shell "pm enable --user 0 $STOCK_LAUNCHER" 2>&1 | tr -d '\n')"
while read -r pkg; do
  [ "$pkg" = "$STOCK_LAUNCHER" ] && continue
  [ -z "$pkg" ] && continue
  echo "$pkg: $("$TV" shell "pm enable --user 0 $pkg" 2>&1 | tr -d '\n')"
done < "$LOG"

# Drop our ⚙-key service from the accessibility list, keep any others.
"$TV" shell "appops set $LAUNCHER_PKG AUTO_START default" >/dev/null
SVC="$LAUNCHER_PKG/$LAUNCHER_PKG.KeyService"
cur="$("$TV" shell "settings get secure enabled_accessibility_services" | tr -d '\r\n')"
new="$(echo "$cur" | tr ':' '\n' | grep -v "^$SVC$" | grep -v '^null$' | paste -sd: -)"
if [ -z "$new" ]; then
  "$TV" shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0" >/dev/null
else
  "$TV" shell "settings put secure enabled_accessibility_services '$new'" >/dev/null
fi

echo "home: $("$TV" shell "cmd package set-home-activity --user 0 $STOCK_HOME" 2>&1 | tr -d '\n')"

if [ $UNINSTALL -eq 1 ]; then
  echo "uninstall $LAUNCHER_PKG: $("$TV" shell "pm uninstall $LAUNCHER_PKG" 2>&1 | tr -d '\n')"
fi

"$TV" shell "input keyevent KEYCODE_HOME" >/dev/null
echo "Done. Packages that were already disabled before we started are listed in backup/packages-disabled-before.txt and were left as they were."
