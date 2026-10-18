#!/bin/bash
# Hide apps from the grid (they stay installed and enabled).
#   scripts/hide-apps.sh com.tcl.suspension com.android.tv.settings
#   scripts/hide-apps.sh --none        show everything again
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,4p' "$0"; exit 1; }
if [ "$1" = "--none" ]; then remove_config hidden.txt; else
  tmp="$(mktemp)"; printf '%s\n' "$PKG" "$@" > "$tmp"; push_config hidden.txt "$tmp"; rm -f "$tmp"
fi
restart_launcher
