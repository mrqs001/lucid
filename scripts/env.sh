# Source this file: `source scripts/env.sh`
# Keeps the Android SDK, Gradle cache and emulator AVDs inside the repo (.toolchain/, git-ignored).
LUCID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LUCID_ROOT
export JAVA_HOME="${LUCID_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="$LUCID_ROOT/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$LUCID_ROOT/.toolchain/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export GRADLE_USER_HOME="$LUCID_ROOT/.toolchain/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
