#!/usr/bin/env bash
# Start the phone and watch emulators (headless by default; pass --window to see them),
# wait for boot, and wire up the dev bridge: phone listens on 47100, the watch dials 10.0.2.2:47100.
set -euo pipefail
source "$(dirname "$0")/env.sh"
WINDOW_FLAGS="-no-window"
[ "${1:-}" = "--window" ] && WINDOW_FLAGS=""
PHONE_PORT=5554
WATCH_PORT=5556
LOGDIR="$LUCID_ROOT/.toolchain/logs"
mkdir -p "$LOGDIR"

start() { # name port
  if adb -s "emulator-$2" get-state >/dev/null 2>&1; then echo "$1 already running"; return; fi
  nohup emulator -avd "$1" -port "$2" $WINDOW_FLAGS -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect \
    > "$LOGDIR/$1.log" 2>&1 &
  echo "started $1 on emulator-$2 (log: $LOGDIR/$1.log)"
}

wait_boot() { # serial
  adb -s "$1" wait-for-device
  until [ "$(adb -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  echo "$1 booted"
}

case "${LUCID_EMULATORS:-both}" in
  phone) start lucid_phone $PHONE_PORT; wait_boot emulator-$PHONE_PORT ;;
  watch) start lucid_watch $WATCH_PORT; wait_boot emulator-$WATCH_PORT ;;
  *) start lucid_phone $PHONE_PORT; start lucid_watch $WATCH_PORT
     wait_boot emulator-$PHONE_PORT; wait_boot emulator-$WATCH_PORT ;;
esac

# Dev bridge: host:47100 -> phone emulator:47100. The watch emulator reaches the host as 10.0.2.2.
if adb -s emulator-$PHONE_PORT get-state >/dev/null 2>&1; then
  adb -s emulator-$PHONE_PORT forward tcp:47100 tcp:47100
  echo "dev bridge forward ready (host 47100 -> phone 47100)"
fi
