#!/bin/bash
# Set the dock apps, left to right (max 6). Package names: scripts/tv shell "pm list packages -3"
#   scripts/set-dock.sh be.base.tv com.netflix.ninja com.amazon.amazonvideo.livingroom ...
#   scripts/set-dock.sh --default      back to res/values/dock.xml
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,4p' "$0"; exit 1; }
if [ "$1" = "--default" ]; then remove_config dock.txt; else
  tmp="$(mktemp)"; printf '%s\n' "$@" > "$tmp"; push_config dock.txt "$tmp"; rm -f "$tmp"
fi
restart_launcher
