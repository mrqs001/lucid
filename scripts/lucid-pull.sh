#!/usr/bin/env bash
# Copy the Lucid database (and settings) from the phone to data/phone/ for analysis.
# Read-only on the phone. Works with debug builds (uses `run-as`); release builds: use in-app Export.
#
#   scripts/lucid-pull.sh                    # first connected non-emulator device, or the last address used
#   scripts/lucid-pull.sh 192.168.1.50      # IP: tries the saved port, then scans for the adb port
#   scripts/lucid-pull.sh 192.168.1.50:37123
set -euo pipefail
source "$(dirname "$0")/env.sh"
PKG=io.github.mrqs001.lucid
OUT="$LUCID_ROOT/data/phone"
mkdir -p "$OUT"
saved="$OUT/.address"

connected() { adb devices | awk 'NR>1 && $2=="device" {print $1}'; }
try_connect() { timeout 10 adb connect "$1" 2>&1 | grep -q "connected to" && echo "$1"; }

target="${1:-}"
serial=""
if [ -n "$target" ] && connected | grep -qx "$target"; then
  serial="$target"
elif [ -z "$target" ]; then
  serial=$(connected | grep -v '^emulator-' | head -1 || true)
  [ -z "$serial" ] && [ -f "$saved" ] && serial=$(try_connect "$(cat "$saved")" || true)
elif [[ "$target" == *:* ]]; then
  serial=$(try_connect "$target" || true)
else
  # IP only: the wireless-debugging port changes whenever it is toggled; find it.
  [ -f "$saved" ] && [[ "$(cat "$saved")" == "$target":* ]] && serial=$(try_connect "$(cat "$saved")" || true)
  if [ -z "$serial" ] && command -v nmap >/dev/null; then
    for port in $(nmap -p 30000-65535 --min-rate 3000 -T4 -Pn "$target" 2>/dev/null | awk -F/ '/open/ {print $1}'); do
      serial=$(try_connect "$target:$port" || true)
      [ -n "$serial" ] && break
    done
  fi
fi
[ -z "$serial" ] && { echo "No phone reachable. Enable Wireless debugging on the phone (and pair once with: adb pair IP:PORT CODE)." >&2; exit 1; }
[[ "$serial" == *:* ]] && echo "$serial" > "$saved"

stamp=$(date +%Y%m%d-%H%M%S)
dir="$OUT/$stamp"
mkdir -p "$dir"
# The app may be writing (a night in progress). Snapshot the database files on the phone in one
# quick step, copy the snapshot, check integrity, and retry if a checkpoint raced the copy.
ok=""
for attempt in 1 2 3 4 5; do
  adb -s "$serial" shell "run-as $PKG sh -c 'rm -rf cache/pull && mkdir -p cache/pull && cp databases/lucid.db* cache/pull/'" 2>/dev/null
  rm -f "$dir"/lucid.db*
  for f in lucid.db lucid.db-wal; do
    adb -s "$serial" exec-out run-as "$PKG" cat "cache/pull/$f" > "$dir/$f" 2>/dev/null || rm -f "$dir/$f"
  done
  if python3 - "$dir/lucid.db" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
ok = c.execute("PRAGMA quick_check").fetchone()[0] == "ok"
if ok:
    c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
c.close()
sys.exit(0 if ok else 1)
PY
  then ok=1; break; fi
  sleep 1
done
adb -s "$serial" shell "run-as $PKG rm -rf cache/pull" 2>/dev/null || true
[ -z "$ok" ] && { echo "Could not get a consistent copy of the database (app busy writing); try again." >&2; exit 1; }
adb -s "$serial" exec-out run-as "$PKG" cat files/datastore/settings.json > "$dir/settings.json" 2>/dev/null || true
rm -f "$dir/lucid.db-wal" "$dir/lucid.db-shm"
ln -sfn "$stamp" "$OUT/latest"
echo "$dir/lucid.db"
