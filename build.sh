#!/usr/bin/env bash
# Build the Game of Life watch face, and install it if asked.
#
#   ./build.sh              build only
#   ./build.sh --test       build and run the simulation unit tests
#   ./build.sh --install    build, then install to the connected watch
#
# Installing is opt-in on purpose: adb talks to one watch at a time, and this face
# is meant to live alongside whatever else you have side-loaded (its application
# id is distinct, so it installs next to another face rather than replacing it).

set -e
cd "$(dirname "$0")"

# Android Studio ships a JDK and most machines have no system Java. Find one.
if [ -z "$JAVA_HOME" ]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/usr/lib/jvm/default-java" ; do
    [ -x "$candidate/bin/java" ] && export JAVA_HOME="$candidate" && break
  done
fi
if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
  echo "ERROR: No Java found. Install Android Studio, or set JAVA_HOME." >&2
  exit 1
fi

# The SDK path is machine-specific, so it is not committed. Derive it if missing.
if [ ! -f local.properties ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  [ -d "$SDK" ] || SDK="$HOME/Android/Sdk"
  if [ ! -d "$SDK" ]; then
    echo "ERROR: Android SDK not found. Set ANDROID_HOME, or install Android Studio." >&2
    exit 1
  fi
  echo "sdk.dir=$SDK" > local.properties
  echo "Wrote local.properties -> $SDK"
fi

[ "$1" = "--test" ] && ./gradlew testDebugUnitTest

./gradlew assembleDebug
APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "Built: $APK"

if [ "$1" = "--install" ]; then
  DEV=$(adb devices | awk '/\tdevice$/{print $1; exit}')
  if [ -z "$DEV" ]; then
    echo "No watch connected over adb. See the README for pairing." >&2
    exit 1
  fi
  echo "Installing to $DEV ..."
  adb -s "$DEV" install -r "$APK"
  echo "Done. Long-press the current face, swipe to GAME OF LIFE, tap it."
fi
