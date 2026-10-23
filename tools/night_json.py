#!/usr/bin/env python3
"""Export one night, complete, as a single JSON file.

    python3 tools/night_json.py                          # latest real night -> output/night-<date>.json
    python3 tools/night_json.py --id 52a7 --hr-offset 15 --out output/night.json

Contents: session metadata and the cueing configuration used, summary, every raw sample (heart
rate, inter-beat intervals, motion, sensor status), every 30 s epoch (features, stage
probabilities, per-feature evidence), cue decisions, battery readings, session events, and the
journal entries linked to the night.

--hr-offset N shifts the night's heart rate by N bpm and keeps everything mathematically
consistent:
  * heart-rate samples: bpm + N (no-contact readings stay 0);
  * beats: the beat sequence is rebuilt so the instantaneous rate 60000/IBI is the original rate + N
    at every moment (more beats per minute, same rhythm pattern, same contact gaps), with each beat
    inheriting the quality flag of the original interval it falls in;
  * everything derived (epochs, RMSSD, stage probabilities, cue decisions, summary) is recomputed by
    replaying the modified samples through the real pipeline (core/eval `replay`).
Standard library only (the replay runs through Gradle). Output is personal health data: keep it out
of version control (output/ is git-ignored).
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


def shift_beats(ibis, delta_bpm, gap_tolerance_ms=60):
    """Rebuild a beat sequence whose instantaneous rate is the original + delta_bpm.

    ibis: [(t_ms_of_beat, ibi_ms, quality)] sorted by t. Consecutive beats form a run while
    t[k] - ibi[k] matches t[k-1]; runs are processed separately so contact gaps stay gaps.
    Within a run, beat phase grows linearly inside each original interval with slope
    (rate + delta)/60000 per ms; a new beat is placed at every whole phase value.
    """
    out = []
    runs, cur = [], []
    for b in ibis:
        if cur and abs((b[0] - b[1]) - cur[-1][0]) > gap_tolerance_ms:
            runs.append(cur)
            cur = []
        cur.append(b)
    if cur:
        runs.append(cur)
    for run in runs:
        start = run[0][0] - run[0][1]  # the beat before the first interval
        prev_edge, phase, last_beat = start, 0.0, float(start)
        for t, ibi, q in run:
            rate = 60000.0 / ibi + delta_bpm  # beats per minute within this original interval
            if rate <= 0:
                prev_edge = t
                continue
            ms_per_beat = 60000.0 / rate
            end_phase = phase + (t - prev_edge) / ms_per_beat
            n = int(phase) + 1
            while n <= end_phase + 1e-9:
                beat = prev_edge + (n - phase) * ms_per_beat
                interval = round(beat - last_beat)
                if interval > 0:
                    out.append((round(beat), interval, q))
                    last_beat = beat
                n += 1
            phase, prev_edge = end_phase, t
    return out


def run_replay(session_start, config, samples):
    """Recompute epochs, decisions and summary with the project's own pipeline."""
    import subprocess, tempfile
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "in.json", Path(tmp) / "out.json"
        src.write_text(json.dumps({"sessionStartMs": session_start, "config": config, "samples": samples}))
        cmd = f'source "{ROOT}/scripts/env.sh" && cd "{ROOT}" && ./gradlew -q :core:eval:run --args="replay {src} {dst}"'
        subprocess.run(["bash", "-c", cmd], check=True, stdout=subprocess.DEVNULL)
        return json.loads(dst.read_text())


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

    config = json.loads(s["configJson"] or "{}")
    if off:
        backfilled = {x["t"] for x in ibi if x["backfill"]}
        beats = shift_beats([(x["t"], x["ms"], x["quality"]) for x in ibi], off)
        ibi = [{"t": t, "ms": ms, "quality": qual, "backfill": t in backfilled} for t, ms, qual in beats]
        wire_q = {k: k.upper() for k in QUALITY}
        wire = ([{"type": "hr", "t": h["t"], "bpm": h["bpm"], "quality": wire_q[h["quality"]]} for h in heart_rate] +
                [{"type": "ibi", "t": b["t"], "ibiMs": b["ms"], "quality": wire_q[b["quality"]]} for b in ibi] +
                [{"type": "mot", "t": m["t"], "activityMg": m["activity_mg"], "angleChangeDeg": m["angle_change_deg"]} for m in motion] +
                [{"type": "st", "t": x["t"], "status": str(x["status"]).upper()} for x in status])
        replayed = run_replay(s["startedAt"], config, wire)

    epochs = []
    if off:
        for e in replayed["epochs"]:
            f, p = e["features"], e["prediction"]
            pr = p["probabilities"]
            epochs.append({
                "index": f["index"], "t": f["startMs"], "stage_estimate": STAGES[max(range(4), key=lambda i: pr[i])],
                "p": dict(zip(STAGES, pr)), "confidence": p["confidence"], "quality": f["quality"],
                "contact_lost": f["contactLost"], "hr_mean": f["hrMean"], "hr_rel": f["hrRel"],
                "hr_instability": f["hrInstability"], "rmssd_ms": f["rmssdMs"], "activity_mg": f["activityMg"],
                "active_seconds": f["activeSeconds"], "evidence": p["contributions"],
            })
    else:
        for e in q("SELECT * FROM epoch WHERE sessionId=? ORDER BY idx", sid):
            epochs.append({
                "index": e["idx"], "t": e["startMs"], "stage_estimate": STAGES[e["stage"]],
                "p": {"wake": e["pWake"], "light": e["pLight"], "deep": e["pDeep"], "rem": e["pRem"]},
                "confidence": e["confidence"], "quality": e["quality"], "contact_lost": bool(e["contactLost"]),
                "hr_mean": e["hrMean"], "hr_rel": e["hrRel"], "hr_instability": e["hrInstability"],
                "rmssd_ms": e["rmssd"], "activity_mg": e["activityMg"], "active_seconds": e["activeSeconds"],
                "evidence": json.loads(e["contributionsJson"] or "[]"),
            })

    if off:
        cues = replayed["decisions"]
    else:
        cues = []
        for c in q("SELECT * FROM cue_event WHERE sessionId=? ORDER BY t", sid):
            c["failures"] = json.loads(c.pop("failuresJson") or "[]")
            c.pop("id"); c.pop("sessionId")
            cues.append(c)
    summary = replayed["summary"] if off else (json.loads(s["summaryJson"]) if s["summaryJson"] else None)
    onset = replayed["sleepOnsetMs"] if off else s["sleepOnsetAt"]

    night = {
        "format": "lucid-night-v1",
        "exported_at": iso(int(time.time() * 1000)),
        "modifications": {
            "hr_offset_bpm": off,
            "heart_rate": "every bpm value + offset (no-contact readings stay 0)",
            "inter_beat_intervals": "beat sequence rebuilt so 60000/IBI equals the original instantaneous rate + offset at every moment",
            "derived": "epochs, stage probabilities, cue decisions and summary recomputed from the modified samples with the project's pipeline",
            "unchanged": "motion, sensor status, battery, events, session metadata",
        } if off else None,
        "session": {
            "id": sid, "start": iso(s["startedAt"]), "end": iso(s["endedAt"]), "start_ms": s["startedAt"], "end_ms": s["endedAt"],
            "status": s["status"], "origin": s["origin"], "device": s["device"], "provider": s["provider"], "link": s["linkKind"],
            "estimator": s["estimatorId"], "policy": s["policyId"], "experiment": s["experimentId"], "arm": s["armLabel"],
            "sleep_onset": iso(onset),
            "config": config,
            "summary": summary,
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
