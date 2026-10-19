#!/bin/bash
# Build the launcher (debug APK), install it on the TV over ADB and make it the
# default home screen.
#   scripts/build-and-install.sh                 build + install + set as home
#   scripts/build-and-install.sh --disable-stock also disable the Google TV launcher
#   scripts/build-and-install.sh --no-build      reinstall the last build
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TV="$ROOT/scripts/tv"
PKG="be.kiwazo.tvhome"
HOME_ACTIVITY="$PKG/.HomeActivity"
STOCK_LAUNCHER="com.google.android.apps.tv.launcherx"
# Google TV's setup wizard also claims HOME at a higher priority than any
# third-party launcher; it must go first. Disabling the stock launcher while it
# is still enabled makes its RecoveryActivity loop and crashes system_server.
SETUP_WIZARD="com.google.android.tungsten.setupwraith"
APK="$HOME/.cache/tvhome-build/app/outputs/apk/debug/app-debug.apk"

BUILD=1
DISABLE_STOCK=0
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --disable-stock) DISABLE_STOCK=1 ;;
    *) echo "unknown option: $a"; exit 1 ;;
  esac
done

if [ $BUILD -eq 1 ]; then
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  [ -x "$JAVA_HOME/bin/java" ] || { echo "JDK 17+ not found at $JAVA_HOME (brew install openjdk@21)"; exit 1; }
  [ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "Android SDK platform 34 missing in $ANDROID_HOME"; exit 1; }
  cd "$ROOT/launcher"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew --quiet --project-cache-dir "$HOME/.cache/tvhome-build/gradle-project" assembleDebug
fi

[ -f "$APK" ] || { echo "No APK at $APK"; exit 1; }
echo "Installing $(du -h "$APK" | cut -f1) APK..."
"$TV" install "$APK"

# Android only hands the Wi-Fi name to apps with location access.
"$TV" shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" || true

if [ $DISABLE_STOCK -eq 1 ]; then
  "$ROOT/scripts/disable.sh" "$SETUP_WIZARD"     # order matters, see above
  "$ROOT/scripts/disable.sh" "$STOCK_LAUNCHER"
fi
# The settings key -> Quick panel service (KeyService). Appended, so other
# accessibility services you enable stay on.
# TCL's auto-start manager (TclAppBoot) blocks binding it unless AUTO_START is allowed.
SVC="$PKG/$PKG.KeyService"
"$TV" shell "appops set $PKG AUTO_START allow"
# TCL resets AUTO_START at boot; this lets the launcher re-bind the service itself.
"$TV" shell "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
# Usage access, so the ⚙ key knows which app is in front.
"$TV" shell "appops set $PKG GET_USAGE_STATS allow"
cur="$("$TV" shell "settings get secure enabled_accessibility_services" | tr -d '\r\n' | tr ':' '\n' | { grep -v -e "^$SVC$" -e '^null$' || true; } | paste -sd: -)"
# Rewriting the list (without, then with us) makes Android bind the freshly installed service.
"$TV" shell "settings put secure enabled_accessibility_services '$cur'"
"$TV" shell "settings put secure enabled_accessibility_services '${cur:+$cur:}$SVC'"
"$TV" shell "settings put secure accessibility_enabled 1"

# Set the default after the candidate list is final.
"$TV" shell "cmd package set-home-activity --user 0 $HOME_ACTIVITY"

"$TV" shell "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null
echo "Home is now: $("$TV" shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tail -1)"
