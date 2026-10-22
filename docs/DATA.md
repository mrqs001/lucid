# Getting at the data

Everything a night produces lives in one SQLite database on the phone
(`/data/data/io.github.mrqs001.lucid/databases/lucid.db`). Nothing is uploaded.

## Pull and read (for people and AI assistants)

```bash
scripts/lucid-pull.sh                      # copy DB + settings from the phone → data/phone/<time>/, data/phone/latest
scripts/lucid-pull.sh 192.168.1.50        # by IP; finds the wireless-debugging port if it changed
python3 tools/night_report.py --list       # every night
python3 tools/night_report.py              # latest night: data coverage, gaps, battery, REM-like periods,
                                           # every cue with its reason and the watch's response, hourly table, events
python3 tools/night_report.py --night 2 --json   # machine-readable
sqlite3 data/phone/latest/lucid.db         # or query directly
```

Requirements: Wireless debugging on the phone, paired once (`adb pair IP:PORT CODE`), and a debug
build of the app (the pull uses `run-as`). For a release build use Settings → Your data → Export.
`data/` is git-ignored: it is personal health data.

## Tables

| Table | One row per | Key columns |
|-------|-------------|-------------|
| `session` | night | `id`, `startedAt`/`endedAt` (epoch ms), `origin` (REAL_DEVICE / SIMULATED), `device`, `provider` (samsung-health-sensor / android-sensors / sim), `configJson` (exact cueing config used), `summaryJson`, `sleepOnsetAt`, `estSleepMin`, `estRemMin`, `cuesDelivered`, `suspectedArousals`, `experimentId`, `armId` |
| `sample` | raw measurement as received | `t` (ms), `kind` (0 HR bpm, 1 IBI ms, 2 motion: `v1` activity mg / `v2` angle change °, 3 sensor status), `quality` (0 good, 1 fair, 2 poor, 3 no contact), `backfill` (delivered late after a disconnection) |
| `epoch` | 30 s window | features (`hrMean`, `hrRel`, `hrInstability`, `rmssd`, `activityMg`, `activeSeconds`, `quality`, `contactLost`), probabilities `pWake`/`pLight`/`pDeep`/`pRem`, `confidence`, `stage` (0 wake, 1 light, 2 deep, 3 REM), `contributionsJson` (each feature's evidence for/against REM) |
| `cue_event` | cue candidate (p(REM) ≥ threshold) | `action` (DELIVER / SUPPRESS / WOULD_DELIVER), `failuresJson` (every rule that blocked it), `explanation`, `intensity`, `outcome` (watch: VIBRATED, EXPIRED, …), `response` (NO_REACTION / AROUSAL_SUSPECTED) |
| `battery` | battery reading | `device` (watch/phone), `pct`, `charging`, `t` (wall time) |
| `session_event` | night event | `type`: start, stop, link (every connect/drop), watch_status, backfill, foreign_batch, hello, sleep_onset, error |
| `sim_truth` | 30 s window (simulated nights only) | scripted stage, for evaluation |
| `journal` | dream report | `sessionId`, `lucid`, `lucidity`, `recall`, `cueRecognition`, `vividness`, `control`, `sleepQuality`, `awakenings`, `tags`, text |
| `training` | practice event | `type` (REALITY_CHECK, PRESLEEP_SESSION, REMINDER_SHOWN, MILD_INTENTION), `t`, `durationSec` |
| `experiment` | experiment | `armsJson` (each arm's full cueing config), `assignment`, `blinded` |

Settings are in `settings.json` next to the pulled database (same shape as `AppSettings`).

## Watch-side logs

The watch keeps no database: it streams everything to the phone and buffers in memory during
disconnections. For watch diagnostics, connect its wireless debugging and run
`adb -s <watch> logcat -s WatchController SamsungHealth DevBridge` (Samsung's service logs under
`SHS#`). Disconnect afterwards: the watch shows a high-priority "debugging connected" alert on
every reconnect.
