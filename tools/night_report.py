#!/usr/bin/env python3
"""Readable (or JSON) report of recorded nights from a Lucid database.

    python3 tools/night_report.py --list                 # all nights
    python3 tools/night_report.py                        # latest night, text
    python3 tools/night_report.py --night 2 --json       # second-latest night as JSON
    python3 tools/night_report.py --id <session-id> path/to/lucid.db

Default database: data/phone/latest/lucid.db (see scripts/lucid-pull.sh). Standard library only.
All times are local. Stage estimates come from an unvalidated model; simulated nights are labelled.
"""
import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

STAGES = ["wake", "light", "deep", "rem"]
HR, IBI, MOTION, STATUS = 0, 1, 2, 3
QUALITY = ["good", "fair", "poor", "no_contact"]


def hhmm(ms):
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000)) if ms else None


def day(ms):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ms / 1000)) if ms else None


def gaps(ts, min_gap_ms):
    """Stretches longer than min_gap_ms without a sample."""
    out = []
    for a, b in zip(ts, ts[1:]):
        if b - a > min_gap_ms:
            out.append({"from": hhmm(a), "to": hhmm(b), "seconds": round((b - a) / 1000)})
    return out


def runs(values, pred, min_len):
    """Index runs [start, end) where pred holds for at least min_len items."""
    out, start = [], None
    for i, v in enumerate(values + [None]):
        ok = v is not None and pred(v)
        if ok and start is None:
            start = i
        elif not ok and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    return out


def drain(rows, device):
    rows = [r for r in rows if r["device"] == device]
    if len(rows) < 2:
        return None
    last_charge = max((i for i, r in enumerate(rows) if r["charging"] == 1), default=-1)
    rows = rows[last_charge + 1:]
    if len(rows) < 2:
        return None
    hours = (rows[-1]["t"] - rows[0]["t"]) / 3.6e6
    if hours < 0.1:
        return None
    used = rows[0]["pct"] - rows[-1]["pct"]
    return {"start_pct": rows[0]["pct"], "end_pct": rows[-1]["pct"], "hours": round(hours, 2), "pct_per_hour": round(used / hours, 2)}


def report(db, s):
    q = lambda sql, *a: [dict(r) for r in db.execute(sql, a)]
    sid = s["id"]
    config = json.loads(s["configJson"] or "{}")
    cueing = config.get("cueing", {})
    threshold = cueing.get("remThreshold", 0.7)
    summary = json.loads(s["summaryJson"]) if s["summaryJson"] else None
    end = s["endedAt"]

    # raw coverage
    coverage = {}
    for kind, name in ((HR, "heart_rate"), (IBI, "inter_beat_intervals"), (MOTION, "motion")):
        rows = q("SELECT t, quality, backfill FROM sample WHERE sessionId=? AND kind=? ORDER BY t", sid, kind)
        ts = [r["t"] for r in rows]
        entry = {"count": len(rows), "first": hhmm(ts[0]) if ts else None, "last": hhmm(ts[-1]) if ts else None,
                 "backfilled": sum(1 for r in rows if r["backfill"])}
        if kind == HR:
            span = (ts[-1] - ts[0]) / 1000 if len(ts) > 1 else 0
            entry["per_second"] = round(len(ts) / span, 2) if span else None
            entry["by_quality"] = {QUALITY[k]: sum(1 for r in rows if r["quality"] == k) for k in range(4)}
        if kind != IBI:
            entry["gaps_over_60s"] = gaps(ts, 60_000)
        coverage[name] = entry

    epochs = q("SELECT * FROM epoch WHERE sessionId=? ORDER BY idx", sid)
    prem = [e["pRem"] for e in epochs]
    rem_periods = []
    for a, b in runs(prem, lambda p: p >= threshold, 2):
        rem_periods.append({"from": hhmm(epochs[a]["startMs"]), "to": hhmm(epochs[b - 1]["startMs"] + 30_000),
                            "minutes": (b - a) / 2, "max_p_rem": round(max(prem[a:b]), 2)})

    hourly = []
    for e in epochs:
        h = (e["startMs"] - s["startedAt"]) // 3_600_000
        while len(hourly) <= h:
            hourly.append([])
        hourly[h].append(e)
    hour_rows = []
    for h, es in enumerate(hourly):
        if not es:
            continue
        hrs = [e["hrMean"] for e in es if e["hrMean"] is not None]
        hour_rows.append({
            "hour": h + 1, "from": hhmm(es[0]["startMs"]),
            "hr_mean": round(sum(hrs) / len(hrs), 1) if hrs else None,
            "quality": round(sum(e["quality"] for e in es) / len(es), 2),
            "est_minutes": {st: sum(1 for e in es if e["stage"] == i) / 2 for i, st in enumerate(STAGES)},
            "moving_seconds": sum(e["activeSeconds"] for e in es),
            "rmssd_median": round(sorted(e["rmssd"] for e in es if e["rmssd"] is not None)[len([e for e in es if e["rmssd"] is not None]) // 2], 1)
            if any(e["rmssd"] is not None for e in es) else None,
        })

    cues = q("SELECT * FROM cue_event WHERE sessionId=? ORDER BY t", sid)
    suppressed = {}
    for c in cues:
        if c["action"] == "SUPPRESS":
            for f in json.loads(c["failuresJson"] or "[]"):
                suppressed[f["gate"]] = suppressed.get(f["gate"], 0) + 1
    sent = [{"time": hhmm(c["t"]), "action": c["action"], "number": c["cueNumber"], "intensity": c["intensity"],
             "p_rem": round(c["pRem"], 2), "watch": c["outcome"], "watch_detail": c["ackDetail"],
             "response": c["response"], "response_detail": c["responseDetail"], "why": c["explanation"]}
            for c in cues if c["action"] != "SUPPRESS"]

    battery = q("SELECT t, device, pct, charging FROM battery WHERE sessionId=? ORDER BY t", sid)
    events = [{"time": hhmm(e["t"]), "type": e["type"], "detail": e["detail"]}
              for e in q("SELECT t, type, detail FROM session_event WHERE sessionId=? ORDER BY t", sid)]
    truth = q("SELECT idx, stage FROM sim_truth WHERE sessionId=?", sid)
    journal = q("SELECT createdAt, title, body, recall, lucid, lucidity, cueRecognition, vividness, control, sleepQuality, awakenings, tags "
                "FROM journal WHERE sessionId=?", sid)

    return {
        "night": {
            "id": sid, "started": day(s["startedAt"]), "ended": day(end), "status": s["status"],
            "recorded_hours": round((end - s["startedAt"]) / 3.6e6, 2) if end else None,
            "origin": s["origin"], "simulated": s["origin"] == "SIMULATED", "device": s["device"], "provider": s["provider"],
            "link": s["linkKind"], "scenario": s["scenarioId"], "seed": s["seed"], "estimator": s["estimatorId"],
            "policy": s["policyId"], "experiment": s["experimentId"], "arm": s["armLabel"],
            "cue_mode": cueing.get("mode"), "rem_threshold": threshold,
            "cue_strength": cueing.get("startIntensity"), "no_cues_before_hours": (cueing.get("minMinutesAfterSleepOnset") or 0) / 60,
        },
        "sleep": {
            "onset": hhmm(s["sleepOnsetAt"]), "est_sleep_minutes": s["estSleepMin"], "est_rem_minutes": s["estRemMin"],
            "est_minutes_by_stage": summary.get("estimatedMin") if summary else None,
            "mean_quality": round(s["meanQuality"], 2) if s["meanQuality"] is not None else None, "rem_periods": rem_periods,
            "note": "Stage estimates come from an unvalidated model.",
        },
        "data": coverage,
        "battery": {"watch": drain(battery, "watch"), "phone": drain(battery, "phone"), "readings": len(battery)},
        "cues": {"delivered": s["cuesDelivered"], "would_deliver": s["cuesWouldDeliver"],
                 "suspected_arousals": s["suspectedArousals"], "sent": sent, "suppressed_by_rule": suppressed},
        "events": events,
        "hourly": hour_rows,
        "journal": journal,
        "simulator_truth_epochs": len(truth),
    }


def print_text(r):
    n, sl, d = r["night"], r["sleep"], r["data"]
    tag = " [SIMULATED]" if n["simulated"] else ""
    print(f"Night {n['id'][:8]}{tag}  {n['started']} -> {n['ended']}  ({n['recorded_hours']} h, {n['status']})")
    print(f"  {n['device']} via {n['provider']} / {n['link']}; cues {n['cue_mode']} (threshold {n['rem_threshold']}, "
          f"strength {n['cue_strength']}, not before {n['no_cues_before_hours']:.1f} h); estimator {n['estimator']}")
    if n["experiment"]:
        print(f"  experiment arm: {n['arm']}")
    print("\nData")
    for k, v in d.items():
        extra = ""
        if "per_second" in v:
            extra += f", {v['per_second']}/s, quality {v['by_quality']}"
        if v.get("gaps_over_60s"):
            extra += f", {len(v['gaps_over_60s'])} gaps > 60 s: " + ", ".join(f"{g['from']}-{g['to']} ({g['seconds']} s)" for g in v["gaps_over_60s"][:8])
        print(f"  {k}: {v['count']} samples {v['first']}..{v['last']}, backfilled {v['backfilled']}{extra}")
    b = r["battery"]
    print(f"\nBattery  watch: {b['watch'] or 'n/a'}  phone: {b['phone'] or 'n/a'}")
    print(f"\nSleep (estimate)  onset {sl['onset']}, sleep {sl['est_sleep_minutes']} min, REM {sl['est_rem_minutes']} min, mean quality {sl['mean_quality']}")
    for p in sl["rem_periods"]:
        print(f"  REM-like {p['from']}-{p['to']} ({p['minutes']} min, max p {p['max_p_rem']})")
    c = r["cues"]
    print(f"\nCues  delivered {c['delivered']}, would-deliver {c['would_deliver']}, suspected arousals {c['suspected_arousals']}")
    for x in c["sent"]:
        print(f"  {x['time']} {x['action']} #{x['number']} at {x['intensity']:.0%}: watch {x['watch']}, response {x['response']}")
        print(f"      {x['why']}")
    if c["suppressed_by_rule"]:
        print("  suppressed candidates by rule: " + ", ".join(f"{k}={v}" for k, v in sorted(c["suppressed_by_rule"].items(), key=lambda kv: -kv[1])))
    print("\nHourly")
    print("  hour from      HR    qual  wake light deep  rem  moving_s  rmssd")
    for h in r["hourly"]:
        m = h["est_minutes"]
        print(f"  {h['hour']:>4} {h['from']}  {h['hr_mean'] or '-':>5}  {h['quality']:.2f}  {m['wake']:>4} {m['light']:>5} {m['deep']:>4} {m['rem']:>4}  {h['moving_seconds']:>8}  {h['rmssd_median'] or '-'}")
    print("\nEvents")
    for e in r["events"]:
        print(f"  {e['time']} {e['type']}: {e['detail']}")
    for j in r["journal"]:
        print(f"\nJournal: lucid={bool(j['lucid'])} recall={j['recall']} cue={j['cueRecognition']} sleep quality={j['sleepQuality']}: {j['title']} {j['body'][:200]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("db", nargs="?", default=str(Path(__file__).resolve().parent.parent / "data/phone/latest/lucid.db"))
    ap.add_argument("--list", action="store_true", help="list all nights")
    ap.add_argument("--night", type=int, default=1, help="1 = latest, 2 = the one before, ...")
    ap.add_argument("--id", help="session id (prefix is enough)")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    if not Path(a.db).exists():
        sys.exit(f"no database at {a.db}: run scripts/lucid-pull.sh first")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    nights = [dict(r) for r in db.execute("SELECT * FROM session ORDER BY startedAt DESC")]
    if a.list:
        for i, s in enumerate(nights, 1):
            print(f"{i:>3}  {s['id'][:8]}  {day(s['startedAt'])}  {s['status']:<9} {s['origin']:<11} "
                  f"sleep {s['estSleepMin'] or 0:>5.0f} min  REM {s['estRemMin'] or 0:>4.0f}  cues {s['cuesDelivered']}")
        return
    if not nights:
        sys.exit("no nights recorded yet")
    s = next((x for x in nights if x["id"].startswith(a.id)), None) if a.id else (nights[a.night - 1] if a.night <= len(nights) else None)
    if s is None:
        sys.exit("night not found")
    r = report(db, s)
    if a.json:
        json.dump(r, sys.stdout, indent=2)
        print()
    else:
        print_text(r)


if __name__ == "__main__":
    main()
