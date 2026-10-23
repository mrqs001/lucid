#!/usr/bin/env python3
"""Export one night, complete, as a single JSON file.

    python3 tools/night_json.py                          # latest real night -> output/night-<date>.json
    python3 tools/night_json.py --id 52a7 --hr-offset 15 --out output/night.json

Contents: session metadata and the cueing configuration used, summary, every raw sample (heart
rate, inter-beat intervals, motion, sensor status), every 30 s epoch (features, stage
probabilities, per-feature evidence), cue decisions, battery readings, session events, and the
journal entries linked to the night.

--hr-offset adds a constant to every heart-rate value in bpm (raw samples and epoch means).
Relative features (HR above baseline, HR instability) are unaffected by a constant shift; inter-beat
intervals are left as recorded. Standard library only. Output is personal health data: keep it
out of version control (output/ is git-ignored).
"""
import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUALITY = ["good", "fair", "poor", "no_contact"]
STAGES = ["wake", "light", "deep", "rem"]
STATUS = ["on_wrist", "off_wrist", "sensor_error", "sensor_unavailable"]


def iso(ms):
    return time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(ms / 1000)) if ms else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("db", nargs="?", default=str(ROOT / "data/phone/latest/lucid.db"))
    ap.add_argument("--id", help="session id or prefix (default: latest real night)")
    ap.add_argument("--hr-offset", type=float, default=0.0, help="bpm added to every heart-rate value")
    ap.add_argument("--out", help="output file (default: output/night-<start>.json)")
    a = ap.parse_args()

    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    q = lambda sql, *p: [dict(r) for r in db.execute(sql, p)]

    if a.id:
        s = next((x for x in q("SELECT * FROM session ORDER BY startedAt DESC") if x["id"].startswith(a.id)), None)
    else:
        s = next(iter(q("SELECT * FROM session WHERE origin='REAL_DEVICE' AND status='COMPLETED' ORDER BY (endedAt-startedAt) DESC, startedAt DESC")), None)
    if s is None:
        sys.exit("night not found")
    sid = s["id"]
    off = a.hr_offset
    shift = lambda v: None if v is None else round(v + off, 2)

    samples = q("SELECT t, kind, v1, v2, quality, backfill FROM sample WHERE sessionId=? ORDER BY t, rowId", sid)
    heart_rate, ibi, motion, status = [], [], [], []
    for r in samples:
        if r["kind"] == 0:
            # no-contact readings carry bpm 0: no heart rate to shift
            bpm = r["v1"] if r["quality"] == 3 else shift(r["v1"])
            heart_rate.append({"t": r["t"], "bpm": bpm, "quality": QUALITY[r["quality"]], "backfill": bool(r["backfill"])})
        elif r["kind"] == 1:
            ibi.append({"t": r["t"], "ms": int(r["v1"]), "quality": QUALITY[r["quality"]], "backfill": bool(r["backfill"])})
        elif r["kind"] == 2:
            motion.append({"t": r["t"], "activity_mg": round(r["v1"], 2), "angle_change_deg": round(r["v2"], 2), "backfill": bool(r["backfill"])})
        else:
            status.append({"t": r["t"], "status": STATUS[int(r["v1"])] if int(r["v1"]) < len(STATUS) else int(r["v1"])})

    epochs = []
    for e in q("SELECT * FROM epoch WHERE sessionId=? ORDER BY idx", sid):
        epochs.append({
            "index": e["idx"], "t": e["startMs"], "stage_estimate": STAGES[e["stage"]],
            "p": {"wake": e["pWake"], "light": e["pLight"], "deep": e["pDeep"], "rem": e["pRem"]},
            "confidence": e["confidence"], "quality": e["quality"], "contact_lost": bool(e["contactLost"]),
            "hr_mean": shift(e["hrMean"]), "hr_rel": e["hrRel"], "hr_instability": e["hrInstability"],
            "rmssd_ms": e["rmssd"], "activity_mg": e["activityMg"], "active_seconds": e["activeSeconds"],
            "evidence": json.loads(e["contributionsJson"] or "[]"),
        })

    cues = []
    for c in q("SELECT * FROM cue_event WHERE sessionId=? ORDER BY t", sid):
        c["failures"] = json.loads(c.pop("failuresJson") or "[]")
        c.pop("id"); c.pop("sessionId")
        cues.append(c)

    night = {
        "format": "lucid-night-v1",
        "exported_at": iso(int(time.time() * 1000)),
        "modifications": {"hr_offset_bpm": off, "applies_to": ["heart_rate[].bpm", "epochs[].hr_mean"],
                          "unchanged": "inter-beat intervals, relative features, everything else"} if off else None,
        "session": {
            "id": sid, "start": iso(s["startedAt"]), "end": iso(s["endedAt"]), "start_ms": s["startedAt"], "end_ms": s["endedAt"],
            "status": s["status"], "origin": s["origin"], "device": s["device"], "provider": s["provider"], "link": s["linkKind"],
            "estimator": s["estimatorId"], "policy": s["policyId"], "experiment": s["experimentId"], "arm": s["armLabel"],
            "sleep_onset": iso(s["sleepOnsetAt"]),
            "config": json.loads(s["configJson"] or "{}"),
            "summary": json.loads(s["summaryJson"]) if s["summaryJson"] else None,
        },
        "notes": {"times": "t fields are epoch milliseconds (UTC)", "stages": "estimates from an unvalidated model"},
        "heart_rate": heart_rate,
        "inter_beat_intervals": ibi,
        "motion": motion,
        "sensor_status": status,
        "epochs": epochs,
        "cue_decisions": cues,
        "battery": q("SELECT t, device, pct, charging FROM battery WHERE sessionId=? ORDER BY t", sid),
        "events": q("SELECT t, type, detail FROM session_event WHERE sessionId=? ORDER BY t", sid),
        "journal": q("SELECT * FROM journal WHERE sessionId=?", sid),
    }

    out = Path(a.out) if a.out else ROOT / "output" / f"night-{time.strftime('%Y%m%d-%H%M', time.localtime(s['startedAt'] / 1000))}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(night, ensure_ascii=False, separators=(",", ":")))
    print(out)


if __name__ == "__main__":
    main()
