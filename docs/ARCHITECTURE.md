# Architecture

## Modules and the one rule that matters

```
core/model   measurement types, vibration patterns, wire protocol          (pure Kotlin)
core/engine  validation -> epochs -> features -> estimator -> cue policy    (depends on model only)
core/sim     scenarios, physiology generator, simulated watch, pacing       (depends on model only)
core/eval    replays simulated nights through the engine (CLI + tests)      (engine + sim)
app          Android phone app                                              (engine + sim)
wear         Wear OS watch app                                              (sim, for watch-side simulation)
```

**The engine cannot see simulator ground truth.** `core/engine` does not depend on `core/sim`, so
the estimator physically cannot read a scripted stage. Truth travels on a separate channel
(`SimFrame.truth` → `SimulatedWatchDevice.truth` → the `sim_truth` table) and is only used for
evaluation and the "Sim truth" strip under the hypnogram. A simulated night is a test of the
software, never evidence that REM detection works.

## Data flow

```
watch (SensorSource: simulated | Android sensors | Samsung Health Sensor SDK)
  -> Uplink: 1 Hz HR, every IBI, 1 Hz motion summaries; batches, in-flight until acked
  -> PhoneLink (dev bridge TCP | Wear Data Layer)           == same JSON WireMessages ==
phone WatchLink (in-app simulator | dev bridge | Data Layer)
  -> SessionManager -> SessionProcessor (synchronous, deterministic)
       SampleValidator   range checks, no-contact, per-channel strictly increasing timestamps
       EpochAccumulator  30 s epochs aligned to session start, 3 s lateness, gaps become empty epochs
       FeatureExtractor  HR vs tonight's baseline, HR instability (relative), RMSSD, movement, stillness, quality
       StageEstimator    HmmStageEstimator: forward filter over Wake/Light/Deep/REM
       CuePolicy         TlrCuePolicy: gates, titration, arousal backoff
  -> CueCommand -> watch CueExecutor (VibrationEffect waveform) -> CueAck
  -> Room (raw samples, epochs, cue decisions, sim truth, events) -> UI
```

Time: every component uses sample timestamps. "Now" comes from a `Clock`: the simulator's virtual
clock in-app, the watch-reported `sentAt` for a watch-side simulation, wall time for real
sensors. That is what makes 240x replay behave exactly like a real night.

## Estimator (`HmmStageEstimator`, id `hmm-walch-v1`)

- Four hidden states, 30 s epochs, causal forward filter (live decisions can't use the future).
- Transition matrix changes with hours since estimated sleep onset: deep sleep likely early, REM
  entry likelier and REM periods longer late in the night.
- Emissions: naive-Bayes product of per-feature histograms: `hrRel` (bpm above tonight's quiet
  baseline), `hrInstability` (ln ratio of 2.5-min HR SD to tonight's quiet median; the strongest
  signal in the lab data), movement (seconds above the wrist's resting level), stillness (minutes
  since the last movement). `lnRmssdRel` is supported but unused: the fitting data has no beat
  intervals.
- Each feature has a fitted weight (the heart-rate features overlap in time, so their evidence is
  down-weighted) and a bound of ±4 log units. Heart-rate features are also scaled by signal
  quality; motion features count fully unless the watch is off the wrist. Missing features drop
  out, so gaps drift the estimate back to the prior instead of inventing evidence.
- Output per epoch: four probabilities, a confidence (data support × decisiveness), and each
  feature's evidence for/against REM, shown in the night report's epoch inspector.
- **Fitted on 31 sleep-lab nights** (Walch et al. 2019, Apple Watch) and tested leave-one-subject-out:
  kappa 0.40, 85 % of would-be cues in REM. Not validated on a Galaxy Watch. Details, charts and
  how to refit: [MODEL.md](MODEL.md). The parameters live in a JSON spec
  (`core/engine/src/main/resources/lucid/core/engine/hmm-walch-v1.json`); the original hand-set
  model remains as `HmmSpec.HandSet`. `StageEstimator` is an interface, so a gradient-boosted
  model or an on-device neural net can be dropped in.

Sleep onset = start of the first 10 minutes in which every epoch has p(wake) < 0.5.

## Cue policy (`TlrCuePolicy`, id `tlr-v1`)

An epoch with p(REM) ≥ threshold is a **candidate**. Every candidate is stored with every rule it
failed, or delivered / "would deliver" (baseline nights). Rules:

| Gate | Default | Why |
|------|---------|-----|
| Data fresh (epoch end vs now) | 90 s | Never cue on backfilled data |
| Watch reachable | heard in last 45 s | Half-open links look connected |
| Sleep onset known, hours since onset | ≥ 3.5 h | The first REM period is short and early estimates are least precise (MODEL.md) |
| Confidence | ≥ 0.45 | |
| Signal quality (last 2 epochs) | ≥ 0.5 | |
| REM sustained | ≥ 3 min, **restarted by any movement or an epoch without usable heart rate** | Estimates lag; movements mark stage transitions; without fresh data the filter only repeats its last state |
| p(wake) | ≤ 0.20 | |
| No movement for | 4 min | Movements cluster where REM ends |
| Pause after suspected arousal | 15 min | |
| Spacing | 60 s | |
| Caps | 6 per REM period, 20 per night | |

Titration: start at 30% intensity, +10% after a cue with no reaction, −20% and a pause after a
suspected arousal (movement ≥ 2 s, p(wake) ≥ 0.5, or HR +8 bpm within 60 s). Watches without
amplitude control express intensity as a longer cue (more pulse repeats).

## Simulator

Seven scenarios (normal, restless, interrupted, poor contact, long REM, REM-like confounders,
disconnection), each seeded and deterministic. The generator is deliberately not the estimator
run backwards: HR is an AR(1) process pulled to stage targets with REM phasic surges, beats are
placed one by one with respiratory sinus arrhythmia and jitter, the watch's reported HR is a
smoothed beat rate, artefacts (ectopic beats, missed detections, contact loss) and movements are
scripted. Cues delivered to a simulated watch can cause arousals or awakenings depending on stage
and intensity, so the closed loop (titration, backoff) is exercised too.

## Transport and reliability

- One JSON message set (`WireMessage`) for every transport.
- Watch → phone: `Hello`, `SampleBatch(seq, sentAt, samples, backfill)`, `WatchStatus`, `CueAck`.
- Phone → watch: `StartMonitoring`, `StopMonitoring`, `CueCommand(expiresAt)`, `BatchAck(seq)`.
- Data Layer traffic goes only to a **nearby** node (direct Bluetooth/Wi-Fi). Google's cloud relay
  for out-of-range devices is never used: it would move physiological data off the devices and it
  delivers commands minutes late. Out of range counts as disconnected; the watch buffers.
- Start and cue commands carry the phone's wall-clock send time; the watch ignores starts older
  than 60 s and cues older than 30 s, and cues for a session it is not running. A watch session the
  phone has not acknowledged for 5 minutes while sends succeed ends itself.
- Batches stay in flight on the watch until acked. No ack within 15 s → reconnect and resend as
  backfill. The phone ignores any sample not newer than the last one on its channel, so resends
  are harmless. Cue commands expire after 20 s, so a stale command never vibrates.

## Storage (Room, `lucid.db`)

`session` (config snapshot as JSON, origin, experiment arm, summary), `sample` (raw), `epoch`
(features + probabilities + per-feature evidence), `cue_event` (every candidate with reasons,
watch outcome, assessed response), `sim_truth`, `session_event`, `journal`, `training`,
`experiment`. Settings: DataStore (JSON). Export: per-night CSVs and a JSON of everything else,
via the share sheet.

## Phone app screens

Tonight (start night, protocol, last-night report), Live (sim controls, physiology, cueing
state, recent events), Journal (list + editor: recall, lucidity level, control, vividness, cue
recognition, sleep quality, awakenings, tags, linked night), Train (pre-sleep pairing session,
reality check, optional daytime cue reminders, history), Insights (real-only by default, recall
and lucidity by week, cues and suspected arousals per night, experiment arm comparison, all
nights), night detail (synchronised timelines with an epoch inspector and the full decision log),
Experiments (cues vs baseline or pattern A vs B, random or alternating assignment, blinding),
Settings (source, simulation, every cueing and sleep-protection parameter, export, limitations).

## Watch app

Status screen (monitoring, source, HR, link, battery, buffered samples, cue log), stand-alone
simulated/sensor start for testing, test vibration, transport switch. A `health` foreground
service plus a partial wake lock keep collection alive with the screen off.
