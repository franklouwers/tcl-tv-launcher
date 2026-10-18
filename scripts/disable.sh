#!/bin/bash
# Disable packages for user 0, one `pm` call per package, and log each one to
# backup/disabled-by-us.txt BEFORE touching it so restore.sh can always undo it.
#   scripts/disable.sh com.tcl.logkit com.tcl.guard ...
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/backup/disabled-by-us.txt"
TV="$ROOT/scripts/tv"
touch "$LOG"
STOCK_LAUNCHER="com.google.android.apps.tv.launcherx"
SETUP_WIZARD="com.google.android.tungsten.setupwraith"

# Never leave Google TV's setup wizard as the only HOME candidate: with the stock
# launcher disabled its RecoveryActivity loops and crashes system_server. So the
# wizard always goes first.
PKGS=()
for pkg in "$@"; do
  if [ "$pkg" = "$STOCK_LAUNCHER" ] && ! printf '%s\n' "$@" | grep -qx "$SETUP_WIZARD" \
     && ! "$TV" shell "pm list packages -d" | grep -qx "package:$SETUP_WIZARD"; then
    PKGS+=("$SETUP_WIZARD")
  fi
done
for pkg in "$@"; do [ "$pkg" = "$STOCK_LAUNCHER" ] || PKGS+=("$pkg"); done
for pkg in "$@"; do [ "$pkg" = "$STOCK_LAUNCHER" ] && PKGS+=("$pkg"); done
# Wizard before launcher even when both were passed in the wrong order.
if printf '%s\n' "${PKGS[@]}" | grep -qx "$SETUP_WIZARD"; then
  PKGS=("$SETUP_WIZARD" $(printf '%s\n' "${PKGS[@]}" | grep -vx "$SETUP_WIZARD"))
fi

for pkg in "${PKGS[@]}"; do
  grep -qx "$pkg" "$LOG" || echo "$pkg" >> "$LOG"
  out="$("$TV" shell "pm disable-user --user 0 $pkg" 2>&1)"
  if echo "$out" | grep -q "new state: disabled-user"; then
    echo "OK    $pkg"
  else
    echo "FAIL  $pkg  -> $out"
  fi
done
