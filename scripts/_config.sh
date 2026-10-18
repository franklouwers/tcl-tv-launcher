#!/bin/bash
# Shared helpers for the set-*.sh scripts: launcher config lives in the app's
# files dir on the TV, so changes need no rebuild.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TV="$ROOT/scripts/tv"
PKG="be.kiwazo.tvhome"
FILES="/sdcard/Android/data/$PKG/files"

# push_config <name on TV> <local file>
push_config() {
  "$TV" shell "mkdir -p $FILES" >/dev/null
  "$TV" push "$2" "$FILES/$1" >/dev/null && echo "Updated $1 on the TV."
}

# remove_config <name on TV>
remove_config() {
  "$TV" shell "rm -f $FILES/$1" >/dev/null && echo "Removed $1 from the TV."
}

# Ask the running launcher to re-read its config. (Not force-stop: Android
# would then switch off the launcher's settings-key accessibility service.)
restart_launcher() {
  "$TV" shell "am broadcast -a $PKG.RELOAD -p $PKG" >/dev/null
}
