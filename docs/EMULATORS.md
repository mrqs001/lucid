# Developing without the watch

Everything here runs on Linux x86_64 with KVM and JDK 17, using the official emulator from the
Android SDK. Nothing is installed outside the repository.

## 1. Toolchain

```bash
LUCID_WITH_IMAGES=1 ./scripts/setup-toolchain.sh
source scripts/env.sh     # sets ANDROID_HOME, GRADLE_USER_HOME, ANDROID_AVD_HOME, JAVA_HOME into .toolchain/
```

Installed packages: `platform-tools`, `platforms;android-37.0` (current AndroidX needs compileSdk
37), `build-tools;37.0.0`, `emulator`, and two system images:

| AVD | Image | Why |
|-----|-------|-----|
| `lucid_phone` (Pixel 7) | `android-36;google_apis_playstore;x86_64` | Android 16 phone with Play services (Data Layer needs them) |
| `lucid_watch` (large round) | `android-36;android-wear-signed;x86_64` | Wear OS 6, the version the Galaxy Watch 7 runs (One UI 8 Watch) |

Create the AVDs (done once):

```bash
avdmanager create avd -n lucid_phone -k "system-images;android-36;google_apis_playstore;x86_64" -d pixel_7
avdmanager create avd -n lucid_watch -k "system-images;android-36;android-wear-signed;x86_64" -d wearos_large_round
```

## 2. Three ways to run a night

### a) Phone only: in-app simulator (fastest)

Settings → About → turn on **Developer tools**, then Settings → Developer: data source → **In-app simulator**. Pick a scenario and speed (1x … max) on the Live tab
or in Settings, then **Start simulated night**. The simulated watch lives inside the phone process
and feeds the exact pipeline a real watch feeds. At 240x a night takes 2 minutes; at "max" it is
bounded by database writes (well under a minute).

### b) Phone emulator + watch emulator over the dev bridge

```bash
./scripts/emulators.sh --window    # boots both, waits, runs: adb -s emulator-5554 forward tcp:47100 tcp:47100
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r wear/build/outputs/apk/debug/wear-debug.apk
```

On the phone: Settings → Data source → **Watch emulator (dev bridge)**, keep "Watch generates
simulated data" on, start the night. Open Lucid on the watch emulator. The watch dials
`10.0.2.2:47100` (the host), `adb forward` hands the connection to the phone, and the phone sends
`StartMonitoring`. The watch then runs the scenario itself (capped at 60x so cue commands don't
expire in transit), and streams batches through the real uplink. The phone sends cue commands
back, and the watch calls the real vibrator API.

The dev bridge is newline-delimited JSON over TCP carrying the same messages as the Data Layer.
It exists because pairing emulators over the Data Layer needs the Pixel Watch app and a Google
sign-in on the phone emulator. It only works in debug builds: release builds have no INTERNET
permission.

### c) Wear Data Layer between emulators (optional, manual)

Real-device path, needs Android Studio's pairing assistant, the Pixel Watch app from Play and a
Google account on the phone emulator. Not automated here. Select **Watch (Wear Data Layer)** on the
phone and switch the watch to "Link: Data Layer".

## 3. Useful commands

```bash
scripts/ui.sh emulator-5554 tap "Start simulated night"     # tap by visible text
scripts/ui.sh emulator-5554 shot /tmp/phone.png             # screenshot
scripts/ui.sh emulator-5556 texts                           # what the watch shows

# Watch sensors in "Start sensors" (real) mode on the emulator:
adb -s emulator-5556 emu sensor set heart-rate 58
adb -s emulator-5556 emu sensor set acceleration 0:0:9.81

# What the vibrator actually received (pattern, amplitudes, usage):
adb -s emulator-5556 shell dumpsys vibrator_manager | grep io.github.mrqs001.lucid

# Simulate a watch network outage (tests buffering + acknowledged backfill):
adb -s emulator-5556 shell cmd connectivity airplane-mode enable
adb -s emulator-5556 shell cmd connectivity airplane-mode disable
```

## 4. Tests

```bash
./gradlew test                                   # all JVM tests (core, phone, watch)
./gradlew :core:eval:run --args="--scenario all"
./gradlew :core:eval:run --args="--scenario long_rem --seed 9 --csv /tmp/night.csv"
./gradlew :app:connectedDebugAndroidTest         # Room database tests on the running phone emulator
                                                 # (this uninstalls the app afterwards: data is wiped)
```

## 5. What the emulator cannot tell you

- Real PPG, heart-rate quality, inter-beat intervals: the Samsung Health Sensor SDK does not run on
  emulators at all.
- Whether a vibration is felt, wakes you, or is suppressed by bedtime mode.
- Overnight battery use, Galaxy-specific background limits, Bluetooth reliability.
- The Data Layer between a real phone and a real Galaxy Watch.

## 6. Problems already met (and fixed)

- **Watch app could not connect, "port 0" in the error.** A Kotlin scoping trap:
  `Socket().apply { connect(InetSocketAddress(host, port)) }` resolves `port` to `Socket.port`
  (0 before connecting). The connect call now sits outside `apply`.
- **Data silently lost after a network drop.** Without a TCP close, both ends kept half-open
  sockets and the watch's writes "succeeded". Fixed with per-batch acknowledgements (unacked data
  is resent after 15 s), phone-side silence detection (45 s), and duplicate-proof ingestion.
- **AndroidX now requires compileSdk 37.** targetSdk stays 36 (Wear OS 6 behaviour).
