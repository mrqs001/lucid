# Status (2026-10-22)

Four buckets, as the brief asked. "Verified" means I ran it and checked the result, on this
machine, today.

## 1. Working in the emulators (verified)

| What | How it was checked |
|------|--------------------|
| Phone app builds, installs and runs on the Android 16 emulator; all screens open (Tonight, Live, Journal + editor, Train + pre-sleep session, Insights, night report, Experiments, Settings) | Screenshots and UI dumps, no crashes in logcat |
| Watch app builds, installs and runs on the Wear OS 6 emulator | Screenshots |
| Whole simulated night in the phone (240x, about 2 min): live charts, cue decisions, stored session, morning report | Night of 7h59m, 9 cues, 2 suspected arousals, same numbers as the JVM replay of the same scenario and seed |
| Phone ↔ watch emulator over the dev bridge: watch-side simulation streams to the phone, phone sends cues back | Samples, epochs and cues flowed; acks came back as "emulator vibrator call" |
| Cue titration across the real transport | Cue #2 at 40% got no reaction, cue #3 went out at 50%, then the watch-side simulated body moved, the phone flagged an arousal and backed off |
| Vibration API on the watch | `dumpsys vibrator_manager` shows the exact waveform (3 × 200 ms at amplitude 0.30/0.40/0.50, usage ALARM) |
| Watch network outage and recovery | Airplane mode on the watch for 35 s: the watch buffered 6,747 samples, reconnected, backfilled; phone sample count went from 3,177 to 17,323 |
| Journal entry saved and linked to a night; experiment created (cues vs baseline, random, blinded); insights with and without simulated nights | UI walkthrough |
| Room database (sessions, samples, epochs, cue outcomes, journal, experiment arm assignment) | 3 instrumented tests on the phone emulator |
| Night ends by itself when the watch stops monitoring | Bridged night at 60x: watch finished the script, went idle, phone closed the session (959 epochs, 9 cues, identical to the in-app run of the same seed) |
| Phone app on real hardware | An Android 16 phone, over wireless debugging |

## 1b. Working on the real Galaxy Watch7 (SM-L300, One UI 8 Watch, Android 16)

| What | Result |
|------|--------|
| Watch app installs, finds the phone through the Data Layer capability | The watch shows the paired phone as connected |
| Data Layer transport, watch-side simulated night | Phone showed "Connected to Galaxy Watch7", samples and epochs flowed over Bluetooth |
| **Real sensors** (Android `TYPE_HEART_RATE` + accelerometer, no Samsung SDK yet) | Heart rate at exactly 1 Hz (median gap 1,002 ms), motion summaries at 1 Hz, sensor data quality 100% |
| **Real vibration cue** from the phone | Watch played 3 × 200 ms at 30% amplitude, usage ALARM, 2.1 s (`dumpsys vibrator_manager`) |
| Battery timeline per night (watch + phone) | Rows stored, drain per hour in the night report, dashboard and Insights |
| Database migration v1 → v2 on a phone with existing data | No crash, data kept |
| **Samsung Health Sensor SDK v1.4.1** (Health Platform developer mode on) | Heart rate 1 Hz, inter-beat intervals consistent with the heart rate (a missed-beat artefact was removed by the cleaner, a few intervals flagged poor by the watch were excluded), accelerometer 25 Hz → 1 s summaries; RMSSD computed from the first full epoch at quality 1.0. Phone shows "IBI yes" |
| Fallback when developer mode was off | SDK returned `SDK_POLICY_ERROR`; the watch switched to Android sensors mid-session without a gap |
| Cue played exactly once after the de-duplication fix | One 2.1 s vibration in `dumpsys vibrator_manager` |

Hardware facts learned: the vibrator **has amplitude control** (no composition primitives); heart
rate comes from "Samsung HR Batch Sensor", a wake-up sensor behind `health.READ_HEART_RATE`; the
accelerometer (LSM6DSV) has **no wake-up variant**, so the service's wake lock is needed; the heart
-rate sensor reports "no contact" for about 5 s after starting.

Bugs found only on real hardware, all fixed and covered by tests:
1. **Simulated data leaked into a real night.** Unacknowledged batches from a watch-side
   simulation were resent into the next (real) session; their future timestamps then made every
   real sample look like a duplicate (sensor quality 0%). Fix: batches carry their session id, the
   watch uses one uplink per session, the phone drops other sessions' batches, and samples more
   than 2 min in the future are rejected without moving epoch time.
2. **Every cue played twice.** On a real watch the Data Layer delivers each message to both the
   live listener and the background listener service. Fix: cue commands are de-duplicated by id.
3. **Heart rate dropped as "late".** The watch batches heart rate (up to 10 s) while motion goes
   out every second, so epochs closed before the heart rate arrived. Fix: an epoch closes only when
   every active channel has passed it (a channel silent for 2 min stops blocking).
4. **Old commands vibrated minutes later.** A delayed `StartMonitoring` (Data Layer relay while
   Bluetooth was down) restarted a finished session on the watch, then four expired test cues played.
   Fix: nearby-only Data Layer, wall-clock age limits on commands, session checks, orphan timeout.
   Verified on the phone and the watch.

## 2. Working, but only on synthetic data (verified as software, says nothing about sleep)

- **REM estimator.** Synthetic agreement per scenario is in `./gradlew :core:eval:run --args="--scenario all"`.
  REM sensitivity is 0.70 to 0.91 and kappa 0.39 to 0.72 against the simulator's script. That only
  proves the plumbing. The simulator and the estimator share assumptions, so these numbers would
  look good even if the estimator were wrong about real people.
- **Confounders work as designed.** On the "REM-like signals outside REM" night, REM precision drops
  to 0.43 and 14% of cues land outside REM. That is the realistic failure mode for wrist sensors,
  and the reason for the conservative gates.
- **Movement restarts the sustained-REM timer.** I added this after the simulator showed cues landing
  in light sleep: REM ended with a movement, the estimate lagged by about 4 minutes, and the lockout
  had already expired. With the rule, 100% of cues land in simulated REM on 6 of 7 scenarios.
- Cue policy, intensity titration, arousal backoff, baseline ("would cue") nights, stale-data and
  disconnection gates: 10 policy tests and 7 closed-loop tests.
- 43 JVM tests (core 36, phone 2, watch 5) and 3 on-device database tests, all passing.

## 3. Implemented, waiting for the physical watch

- **Samsung Health Sensor SDK adapter**: now **verified on the watch** (see 1b). Still to check:
  the 60 s `flush()` cadence with the screen off for hours.
- SDK audit (v1.4.1 AAR): no permissions in its manifest, no networking or file code in its 51
  classes; it only binds to Samsung's on-watch health service over local IPC and links to the
  Play Store page for that service. Release builds of both apps have no INTERNET permission.
- A full overnight run on the real watch: battery use per hour, Galaxy background limits with the
  screen off for hours, Bluetooth drops during the night.
- Perception threshold (you need to feel the test cues) and whether bedtime mode lets the
  `USAGE_ALARM` vibration through.

## 4. Cannot be done without specific hardware, data or permissions

- **Validating REM detection on the Galaxy Watch.** Needs PSG or a validated EEG headband worn
  alongside the watch. The model is now fitted and cross-validated on the open Walch sleep-accel
  set (Apple Watch, see [MODEL.md](MODEL.md)); DREAMT and MESA would add more nights but need
  data use agreements.
- Real PPG quality, IBI artefact rates, Samsung batching behaviour, Galaxy background limits.
- Whether vibration cues reach dreams. The only wrist-vibration study found none at the wrist and
  frequent awakenings (Paul et al. 2014). This project is an experiment, and the app is built to
  measure it honestly (baseline arms, blinding, arousal stats).

## App for everyday use (2026-10-22 evening)

- Four tabs: Tonight (start/stop, recording card, last night), Journal, Train, Insights. The live
  view opens from the recording card.
- Settings: night cues on/off (baseline), cue strength with "Try on watch", earliest cue time,
  an "Advanced" fold-out for thresholds and limits, export, delete all nights, about. Simulator and
  data-source choices only appear with Developer tools on. New installs default to the real watch.
- Reality-check reminders, "Reality check now" and pre-sleep training vibrate the cue on the
  watch (Data Layer, nearby only, no night needed); the phone notification is not mirrored to the
  watch, and the phone vibrates only when no watch is in reach.

## First real night and battery work (2026-10-23)

- First baseline night on the Galaxy Watch7: 5.3 h recorded without gaps (heart rate 1 Hz, beat
  intervals, motion), link up all night, no backfill. Stage estimates plausible in timing (REM-like
  blocks ~80-100 min after onset and later), but light vs deep barely separate. Not validated.
- Crash fixed: the report screens threw as soon as a night had a watch battery drain to show.
- Watch battery: 7.9 %/h overall, of which the app was ~3.8 %/h (batterystats). A simpleperf profile
  showed ~70% of the app's CPU in the interpreter and JNI checks of the debug build, and the
  SDK's 25 Hz accelerometer stream as the main app-code cost. Now: release build on the watch
  (same signing key), accelerometer from the sensor hub in batches, data every 30 s, status every
  5 min. Measured CPU: 5.2 → 1.25 CPU-s per minute.
- The phone resumes the watch after a watch-app restart (hello mid-night) and, via a 2-minute
  silence watchdog, after a crash. Verified with `am crash` on the watch: back recording in ~3 min.
- Watch settings matter: always-on display and raise-to-wake were on. Bedtime mode turns both off
  and still lets alarm-type vibrations (the cues) through.

## Model fitted on sleep-lab data (2026-10-23)

- The stage model is now fitted on 31 PSG-scored nights with wrist heart rate and motion (Walch et
  al. 2019) and tested leave-one-subject-out through the real pipeline: kappa 0.22 → 0.40, REM
  calls right 45 % → 57 %, would-be cues in REM 65 % → 85 %, probabilities calibrated instead of
  wildly overconfident. Shipped as `hmm-walch-v1`. See [MODEL.md](MODEL.md).
- Earliest cue time lowered from 5 h to 3.5 h after sleep onset: detection is already good then, and
  5 h left almost no cue time on short nights.
- Cue rules tuned on the lab nights: REM must be sustained 3 min instead of 5, and movement blocks
  cues for 4 min instead of 2. Late REM periods reached 35 % → 46 % (held out), wrong cues per night
  0.87 → 1.10.
- Nights recorded with an older model are re-estimated from their stored samples when the app
  starts, so reports always show the current model. Nights with delivered cues stay as recorded.
- Fixed along the way: accelerometer offset counted as movement (the Galaxy Watch7 reads 4-47 mg
  when still, by wrist position); motion evidence ignored whenever the pulse signal dropped out;
  REM carried through epochs without heart-rate data counted towards the 5 min sustain rule.
- `core/eval replay` takes an optional model spec, so any recorded night can be re-scored with
  any model.

## Known gaps and next steps

1. Validate the stage model on the Galaxy Watch: a night with an EEG headband or PSG worn at the
   same time. Samsung Health's own stages would be a weaker comparison.
2. More lab nights to fit on (MESA, DREAMT; data use agreements needed). With hundreds of nights a
   small live neural network becomes worth trying.
3. The watch backlog lives in memory only. If the watch app process dies during an outage, buffered
   data is lost. Persisting it to a file is a small change.
4. Voice notes for the journal: the column exists, recording and transcription do not.
5. Licensed MIT.
