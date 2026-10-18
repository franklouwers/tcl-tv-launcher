#!/bin/bash
# Describe what's connected to each input in the Inputs dropdown.
#   scripts/set-inputs.sh "HDMI 1 = PlayStation 5" "HDMI 2 = Soundbar" "AV = Wii"
#   scripts/set-inputs.sh --none
# Without this, names announced over HDMI-CEC or set in the TV's input settings are used.
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,5p' "$0"; exit 1; }
if [ "$1" = "--none" ]; then remove_config inputs.txt; else
  tmp="$(mktemp)"; printf '%s\n' "$@" > "$tmp"; push_config inputs.txt "$tmp"; rm -f "$tmp"
fi
restart_launcher
