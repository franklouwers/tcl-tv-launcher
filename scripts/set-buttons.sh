#!/bin/bash
# Choose what the remote's app buttons open.
#   scripts/set-buttons.sh "WEB = com.spotify.tv.android" "TCL_HOME = be.vrt.vrtnu"
#   scripts/set-buttons.sh "TCL_CHANNEL = none"     leave a button to the TV itself
#   scripts/set-buttons.sh --default                 back to the defaults
# Buttons: NETFLIX, PRIME, YOUTUBE, TCL_CHANNEL, TCL_HOME, WEB.
# Defaults: Netflix, Prime Video, YouTube, and BASE TV for the three TCL buttons.
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,7p' "$0"; exit 1; }
if [ "$1" = "--default" ]; then remove_config buttons.txt; else
  tmp="$(mktemp)"; printf '%s\n' "$@" > "$tmp"; push_config buttons.txt "$tmp"; rm -f "$tmp"
fi
