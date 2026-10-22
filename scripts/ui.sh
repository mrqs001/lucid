#!/usr/bin/env bash
# Tiny emulator UI driver for manual/automated checks.
#   scripts/ui.sh <serial> tap "<visible text>"   tap the first node whose text contains it
#   scripts/ui.sh <serial> shot <file.png>         screenshot
#   scripts/ui.sh <serial> texts                   list visible texts
set -euo pipefail
source "$(dirname "$0")/env.sh"
serial="$1"; cmd="$2"; arg="${3:-}"
dump() { adb -s "$serial" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s "$serial" shell cat /sdcard/ui.xml; }
case "$cmd" in
  tap)
    xml="$(dump)"
    bounds=$(python3 -c '
import re,sys
xml=sys.stdin.read(); want=sys.argv[1]
nodes=[]
for m in re.finditer(r"<node [^>]*>", xml):
    n=m.group(0)
    t=re.search(r"text=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n)
    label=(t.group(1) if t else "") or (d.group(1) if d else "")
    b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and label: nodes.append((label, b))
# exact match first, then substring
for label, b in [x for x in nodes if x[0] == want] + [x for x in nodes if want in x[0]]:
    x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
' "$arg" <<< "$xml")
    [ -z "$bounds" ] && { echo "not found: $arg" >&2; exit 1; }
    adb -s "$serial" shell input tap $bounds ;;
  shot) adb -s "$serial" exec-out screencap -p > "$arg" ;;
  texts) dump | grep -o 'text="[^"]\+"' | sed 's/text=//' ;;
esac
