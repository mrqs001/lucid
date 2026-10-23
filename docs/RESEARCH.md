# Research notes

Compiled 2026-10-22 from official documentation and primary literature (web review). Numbers are
as reported by the sources; items marked *unverified* could not be confirmed from primary text.

## 1. Platform facts

### Samsung Health Sensor SDK (on-watch)
- Latest v1.4.1 (2025-08-20); v1.4.0 added `EDA_CONTINUOUS` (Watch 8+) and the Android 16
  permission model. Distributed as a ZIP containing `samsung-health-sensor-api.aar`; not on Maven.
- Continuous trackers: `HEART_RATE_CONTINUOUS` (1 Hz, with IBI), `ACCELEROMETER_CONTINUOUS`
  (raw x/y/z, 25 Hz; m/s² = value × 9.81 / (16383.75 / 4)), `PPG_CONTINUOUS` (25 Hz),
  `SKIN_TEMPERATURE_CONTINUOUS`. On-demand: ECG, BIA, SpO2, PPG (foreground, ~30 s, one at a time).
- Screen off → batched delivery (docs example: 600 HR samples every 10 min). `flush()` forces
  delivery. In a batch, all IBIs sit in the first data point.
- `HeartRateSet`: `HEART_RATE`, `HEART_RATE_STATUS` (1 ok, 0 initial, −2 movement, −3 detached,
  −8 weak/moved, −10 too weak, −999 higher-priority sensor), `IBI_LIST`, `IBI_STATUS_LIST` (0 ok, −1 error).
- Dev use without partner approval: Health Platform developer mode (HARDWARE.md). Not supported on emulators.
- Permissions at targetSdk 36: HR → `health.READ_HEART_RATE`; accelerometer → `ACTIVITY_RECOGNITION`;
  PPG/ECG/BIA/EDA → `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA`.
- Sources: developer.samsung.com/health/sensor (release-note, guide/data-specifications,
  guide/tracking-data, guide/permission-request, guide/developer-mode, api-reference ValueKey.HeartRateSet).

### Wear OS 6 / Android 16
- `BODY_SENSORS` → `android.permission.health.READ_HEART_RATE`; `BODY_SENSORS_BACKGROUND` →
  `READ_HEALTH_DATA_IN_BACKGROUND`. Applies to `Sensor.TYPE_HEART_RATE`, Health Services and the
  `health` FGS type. (developer.android.com/training/wearables/versions/6/changes)
- No platform API exposes IBI/RR; Health Services has BPM only. IBI → Samsung SDK only.
- `health` FGS: no time limit (unlike `dataSync`, 6 h/24 h on Android 15+). Prerequisite:
  `HIGH_SAMPLING_RATE_SENSORS` or a body-sensor permission.
- Galaxy Watch pauses apps ~60 s after screen-off without an FGS (Samsung dev blog, Apr 2026).
- Non-wake-up sensor FIFOs overwrite data while the CPU sleeps: prefer wake-up sensors.
- Wear Data Layer: phone and watch apps need the same package name and signing key;
  `MessageClient` payloads ≲ 100 KB, best effort.

### Emulators
- Wear OS emulator: heart rate via `adb emu sensor set heart-rate <bpm>` (from emulator source);
  accelerometer via `adb emu sensor set acceleration x:y:z`; Health Services synthetic data via
  Android Studio's panel only (Wear OS 4+). Vibrator: example HAL, accepts amplitude waveforms,
  nothing physical. *Verified here:* `dumpsys vibrator_manager` records the exact waveform.
- Pairing phone + watch emulators over the Data Layer needs the Pixel Watch app (Play) and a
  Google account → this project uses its own TCP dev bridge on emulators.
- Samsung Remote Test Lab lists Galaxy Watch 7 units (adb access; no wearer).

## 2. Targeted lucidity reactivation (TLR) and cueing

- **Konkoly et al. 2024**, *Consciousness and Cognition* 125:103759 (PMC11542932). Home Android-app
  studies, self-report. Pre-sleep training ~15 min: cue (tones / melody) followed by guidance ("as
  you notice the signal, you become lucid… notice thoughts, body, breath… how is this different
  from waking"), 4 guided cues with 45 s pauses, then 12 unguided cues at 45-120 s while falling
  asleep. Night cues by **time** (from 6 h after training), not REM detection; intensity raised
  slowly from a waking threshold until arousal; movement paused cueing. Exp 1 (n=19): 2.11 vs
  0.74 lucid dreams/week. Exp 2 (n=120 analysed): TLR cue > untrained cue (p=.03) but **TLR vs no
  cue n.s. (p=.77)**. Cues often woke participants; authors recommend REM-gating with wrist HR and
  movement, which is what this app does.
- **Carr et al. 2020** (lab nap, PSG): tones + red light paired in 20 min training, replayed in
  REM: signal-verified lucid dreams 50% cued vs 17% uncued (*details unverified*, full text unavailable).
- **Konkoly et al. 2021**, *Current Biology*: cues ~40-45 dB every 10-30 s in stable REM, paused
  on EEG arousal, ≤10 per REM period; 26% of sessions with verified lucidity.
- **Paul, Schädlich & Erlacher 2014**: vibration (phone motor ≤2 s) on finger or wrist/ankle in
  REM (3rd REM period after 5 min; later after 10 min), 5 stimuli 1 min apart. Verified lucid
  dreams: **wrist 0/16**, ankle 2/11, finger 0/21. Stimuli incorporated into dreams 39-48%, but
  **woke people on 42-54% of trials**. This is the most important caution for a wrist device.
- **Esfahani et al. (bioRxiv, v2 2026)**: 60 lab naps, headband REM detection, rotating light /
  sound / vibration cues every ~20 s, +1 step per cycle, step down after micro-arousal. Lucid
  38.3% cued vs 28.3% sham (n.s.); cues perceived in 71% of cued REM periods; sleep disrupted in
  23.7%. Vibration = 200 ms on / 500 ms off, 1-3 repeats (→ the default `pulse-200` pattern).
- No published results yet for home TLR gated by a wearable REM detector.

## 3. Non-device induction (combine with cueing)

- **Aspy 2020 (ILDIS, n=355)**: WBTB alarm 5 h after bedtime + MILD or SSILD: 5.3% → 15.8% lucid
  mornings; falling asleep within 10 min of the technique: 18.3% vs 11.1%. Daytime reality testing
  added nothing (→ reminders are optional in the app).
- **Aspy 2017**: RT + WBTB + MILD 9.4% → 17.4%; 45.8% when sleep came within 5 min of MILD.
- **Erlacher & Stumbrys 2020**: woken from REM ~6 h after onset, 60 min awake + MILD: 53-55%
  lucid (27% verified); 30 min awake: 36%. **Erlacher 2022**: the same at 4.5 h: 14%. Later is better.

## 4. Physiology priors

- Mean HR: REM ≈ wake > N2 > N3 (Somers 1993: N3 −8% vs wake). Herzig 2018 (5-min windows):
  HR N2 51.6 / N3 51.5 / REM 53.6 bpm; RMSSD 70.7 / 67.3 / 79.7 ms; **SDNN 68.5 / 53.8 / 105.5 ms**;
  LF/HF 1.11 / 0.51 / 2.02. → HR instability is the best REM feature; mean HR adds little; RMSSD
  direction is inconsistent across studies.
- Movement: wake > N1 > N2 > REM > N3; ~10 posture changes per night.
- Timing: first REM 80-100 min after onset; cycles ~90-96 min; REM periods lengthen across the
  night (~16 → 27-31 min); arousal thresholds in REM ≈ N2, lowest late in the night.

## 5. Wrist staging accuracy and datasets

- Walch 2019 (Apple Watch, motion + HR + clock): REM recall 0.62-0.65, κ 0.24-0.30 (0.40 on MESA).
- Robbins 2024: REM recall/precision Apple S8 83/78%, Oura 76/79%, Fitbit 67/73%; 4-stage κ 0.55-0.65.
- Galaxy Watch 3 (Kim 2023): REM recall 0.60, precision 0.47.
- Realistic target for HR + accelerometry: REM recall 0.6-0.8, precision 0.5-0.8 → tune for
  precision (fewer misplaced cues), as the default gates do.
- Datasets for fitting/validation: **Walch sleep-accel** (PhysioNet, open: 31 subjects, raw accel
  ~50 Hz, HR, PSG labels); **DREAMT** (PhysioNet, restricted DUA: 100 patients, Empatica E4 BVP 64 Hz,
  IBI, accel 32 Hz, PSG); **MESA** (NSRR DUA: 2,237 PSGs with ECG/PPG, actigraphy counts);
  Newcastle (Zenodo, open, wrist accel + PSG, no HR); BIDSLab SLAMSS (Apple Watch, Dreem labels).
  Per project convention, bulk dataset parsing/alignment should be written in Rust; Python only
  for model fitting.

## 6. Implications already built in

- Cue only after the first sleep cycles (≥ 3.5 h after onset, see MODEL.md), in **sustained** probable REM (3 min, reset by movement; tuned on lab nights, see MODEL.md),
  never right after movement, spaced ≥ 60 s, ≤ 6 per REM period, start just above perception
  and back off on any sign of arousal.
- Train the cue before sleep the way Konkoly et al. did; support WBTB + MILD as the
  best-evidenced companion technique.
- Treat every result as an n-of-1 experiment: baseline (no-cue) nights with the same logging,
  randomised arms, optional blinding until the journal entry is written.
