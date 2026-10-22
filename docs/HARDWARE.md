# Bringing up the Galaxy Watch 7

Checklist for the day the watch arrives. Every item is something the emulator could not verify.
Platform facts and sources are in RESEARCH.md §1.

## 1. Developer setup on the watch

1. Settings → About watch → Software → tap *Software version* 5× (developer options). Enable ADB
   debugging and debug over Wi-Fi; connect with `adb connect <ip>:<port>`.
2. Enable **Health Platform developer mode** (needed for the Samsung Health Sensor SDK without
   partner registration). On One UI 8 Watch the generic *Settings → Apps → Health Platform* page
   does **not** react to title taps. Open Health Platform's own settings screen and tap its title
   about 10 times until "[Dev mode]" appears under it:
   `adb shell am start -n com.samsung.android.service.health/.wear.settings.home.SettingsMainActivity`
   Without it the SDK connects but every tracker fails with `SDK_POLICY_ERROR`; the service log
   says `SHS#TrackerRequestHandler: isDeveloperMode false`.
3. Pair the watch with the phone in Galaxy Wearable. The phone and watch apps must be installed
   with the **same application id and signing key** (both use `io.github.mrqs001.lucid`; debug
   builds share the debug key in `.toolchain/android-user/`).

## 2. Enable the Samsung Health Sensor SDK adapter

1. Download the SDK (v1.4.1, Aug 2025) from developer.samsung.com/health/sensor. It is a ZIP with
   `samsung-health-sensor-api-*.aar`, not on Maven and not redistributed here.
2. Copy the AAR to `wear/libs/` (the download needs a Samsung account sign-in). The build then
   compiles `wear/src/samsung/` instead of the stub in `wear/src/nosamsung/`. Verified to compile
   against v1.4.1.
3. Build and install: `./gradlew :wear:assembleDebug && adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk`.

Without the AAR, "real sensors" mode uses Android's `Sensor.TYPE_HEART_RATE` + accelerometer:
heart rate and movement, **no inter-beat intervals** (the RMSSD feature then drops out).

## 3. Permissions at targetSdk 36 (Wear OS 6)

Declared in `wear/src/main/AndroidManifest.xml`; granted on first "Start sensors":

| Need | Permission |
|------|------------|
| Heart rate (Samsung SDK, platform sensor) | `android.permission.health.READ_HEART_RATE` (≤ API 35: `BODY_SENSORS`) |
| Samsung accelerometer | `ACTIVITY_RECOGNITION` |
| Foreground service type `health` | `FOREGROUND_SERVICE_HEALTH` + `HIGH_SAMPLING_RATE_SENSORS` |
| Starting from background with body sensors | possibly `READ_HEALTH_DATA_IN_BACKGROUND`: verify |

## 3b. Verified on the Galaxy Watch7 SM-L300 (2026-10-22)

- One UI 8 Watch, Android 16 / API 36. Watch ADB over Wi-Fi: pair with the code dialog's port,
  then connect to the main wireless-debugging port (if mDNS discovery doesn't work on your
  network, `nmap -p 30000-65535 <watch-ip>` finds it).
- Vibrator: `capabilities = [AMPLITUDE_CONTROL]`, `supportedPrimitives = []`.
- Heart rate: "Samsung HR Batch Sensor" (type 21, wake-up, permission `health.READ_HEART_RATE`),
  1 Hz; "no contact" for ~5 s after start. Accelerometer LSM6DSV, non-wake-up only.
- Data Layer: each phone message arrives twice while the app runs (live listener + listener
  service); the watch de-duplicates cue commands.
- Galaxy Wearable + "watch7plugin" on the phone handle pairing; nothing extra needed for the Data Layer.

## 4. First-night verification checklist

- [ ] Health Sensor SDK connects; log line lists supported trackers (`adb logcat -s SamsungHealth`).
- [ ] HR status codes and IBI lists arrive; IBI timestamps rebuilt from batches look sane
      (export a night, compare IBI sums with the HR series).
- [ ] With the screen off, data still arrives roughly every 60 s (`flush()` loop). If not,
      check whether the partial wake lock is held and whether Samsung pauses the app.
- [x] Phone ↔ watch Data Layer: Live tab shows "REAL WATCH", samples counting, battery.
- [x] `adb shell dumpsys vibrator_manager`: amplitude control yes.
- [ ] Bedtime mode / do-not-disturb: does an `USAGE_ALARM` vibration still play? (Test cue from the phone.)
- [ ] Perception threshold: lie in bed awake, use Settings → "Test on watch" at increasing
      intensity; set start intensity just above where you can feel it.
- [ ] Overnight battery: the night report now shows watch and phone drain per hour.
- [ ] Wear the watch for 3-5 nights in **Baseline** mode (no cues) before any cueing: this
      collects physiology to calibrate the estimator and measures how often "would cue" fires.

## 5. Calibrating the estimator on your own data

Recorded watch data has no ground-truth stages, so it cannot prove the estimator right. Options,
best first: (1) a night with a validated reference (sleep lab PSG, or an EEG headband such as
Dreem / ZMax) worn at the same time, exported and aligned by timestamp; (2) fit emission
parameters on a public PSG-labelled wrist dataset (RESEARCH.md §5), then personalise baselines
from your baseline nights. Until then the estimator stays labelled "uncalibrated" in the UI.

## 6. Samsung Remote Test Lab

Galaxy Watch 7 units are listed. Useful for: does the SDK connect, are permissions right, what
does the vibrator report, does the app survive screen-off. Not useful for physiology (nobody is
wearing it) or for feeling vibration.
