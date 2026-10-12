# Testing (developers)

## Install via adb

On a device or emulator with USB/adb debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell am start -n io.github.patrickric.connectfourstudio/.MainActivity
```

The unsigned release APK cannot be installed directly; sign it first (see `docs/RELEASING.md`) or use the debug APK.

On the very first start the opening book (5.4 MB compressed) is unpacked once
into the app's private storage ("Preparing the opening book …"), which takes
about a second on a phone.

## Unit tests and emulator

Unit tests (JVM, no device needed):

```bash
./gradlew test
```

They cover the game rules, `.4gp` compatibility (files from the desktop
tests), levels, guards, match/Elo/session score and compare the engine with
reference data from the Python engine
(`core/src/test/resources/engine_reference.json`).

Emulator with hardware acceleration (KVM), e.g. API 35:

```bash
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-35;google_apis;x86_64" "emulator"
```

```bash
~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n cfs35 -k "system-images;android-35;google_apis;x86_64" -d pixel
```

```bash
~/Android/Sdk/emulator/emulator -avd cfs35 -no-window -no-audio -no-snapshot &
```

```bash
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
```

Then install (see above) and drive the app, for example:

```bash
adb shell input tap 360 400
```

```bash
adb exec-out screencap -p > screen.png
```

```bash
adb logcat -d | grep -E "AndroidRuntime|FATAL"
```

Useful key events (hardware-keyboard shortcuts of the app): `adb shell input
keyevent 11` (key 4 = column 4), `131` F1 help, `133`/`134` F3/F4 quick
save/load, `135` F5 engine move, `137` F7 permanent
analysis. Rotation: `adb shell settings put system accelerometer_rotation 0`
and `adb shell settings put system user_rotation 1` (landscape) / `0`.

Without KVM (no VT-x/AMD-V, as on the development machine of this port) x86
images do not run. ARM images (armeabi-v7a, API 21 and API 24 "default") still
work in pure software emulation with the older emulator 28.0.23
(`-engine classic` for API 21); they are very slow (engine moves take seconds
to minutes instead of milliseconds) but fine for functional tests. See
`DECISIONS.md` (Tests) for the test protocol.
