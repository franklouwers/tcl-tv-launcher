#!/bin/bash
# Show the next 3 events of a published calendar under the clock.
# iCloud: Calendar app > share calendar > Public Calendar > copy the webcal:// link.
#   scripts/set-calendar.sh 'webcal://p00-caldav.icloud.com/published/2/…'
#   scripts/set-calendar.sh --none
# The link is stored only on the TV (anyone with it can read the calendar).
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,6p' "$0"; exit 1; }
if [ "$1" = "--none" ]; then remove_config calendar.txt; else
  case "$1" in webcal://*|https://*) ;; *) echo "Expected a webcal:// or https:// link."; exit 1 ;; esac
  tmp="$(mktemp)"; printf '%s\n' "$1" > "$tmp"; push_config calendar.txt "$tmp"; rm -f "$tmp"
fi
restart_launcher
