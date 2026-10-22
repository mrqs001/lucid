#!/usr/bin/env bash
# Downloads the Android command-line tools and the SDK packages this project needs into .toolchain/.
set -euo pipefail
source "$(dirname "$0")/env.sh"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
mkdir -p "$ANDROID_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fL --retry 3 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
yes | sdkmanager --licenses > /dev/null || true
sdkmanager --install \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "emulator" \
  ${LUCID_WITH_IMAGES:+"system-images;android-36;google_apis_playstore;x86_64" "system-images;android-36;android-wear-signed;x86_64"}
