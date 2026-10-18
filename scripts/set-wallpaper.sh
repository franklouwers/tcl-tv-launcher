#!/bin/bash
# Put a photo behind the home screen (dimmed at the bottom so the dock stays readable).
#   scripts/set-wallpaper.sh ~/Pictures/beach.jpg     (.jpg, .png or .webp; 1920x1080 is plenty)
#   scripts/set-wallpaper.sh --none                    back to pitch black
source "$(dirname "$0")/_config.sh"
[ $# -gt 0 ] || { sed -n '2,4p' "$0"; exit 1; }
"$TV" shell "rm -f $FILES/wallpaper.jpg $FILES/wallpaper.png $FILES/wallpaper.webp" >/dev/null
if [ "$1" = "--none" ]; then echo "Wallpaper removed."; else
  ext="$(echo "${1##*.}" | tr 'A-Z' 'a-z')"; [ "$ext" = "jpeg" ] && ext=jpg
  case "$ext" in jpg|png|webp) ;; *) echo "Use a .jpg, .png or .webp image."; exit 1 ;; esac
  push_config "wallpaper.$ext" "$1"
fi
restart_launcher
