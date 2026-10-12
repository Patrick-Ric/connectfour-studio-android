# ConnectFour Studio for Android

*Open-source Connect Four with 15 levels, 20 boards, match mode, session score
and perfect real-time analysis – the Android version of ConnectFour Studio.*

**Download:** [ConnectFourStudio-1.0.0.apk](https://github.com/Patrick-Ric/connectfour-studio-android/releases/latest) (Android 5.0+, 6.4 MB) – latest release with checksum; F-Droid inclusion pending.

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_analysis_dark.png" width="260" alt="Analysis">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_walnut_amber_ruby_no_analysis.png" width="260" alt="Stone set Walnut-Amber-Ruby, analysis off">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_ochre_light.png" width="260" alt="Stone set Ochre">
</p>

- **Engine:** Kotlin port of BitBully by Markus Thill (bitbully 0.0.79, C++
  core) with the 12-ply-dist opening book – same scores *and* node counts as
  the original engine (checked by the unit tests)
- **App:** Kotlin, Android framework only (no AndroidX, no Material library,
  no other runtime dependencies), minSdk 21 (Android 5.0), targetSdk 37
- **Languages:** English, German, French, Spanish, Dutch, Italian (in-app choice)
- **Permissions:** none – no internet, no tracking, no ads
- **License:** GNU AGPL v3 (`LICENSE`); assets see `ASSETS.md`

Feature comparison with the desktop (Qt) version: `FEATURES.md`.
Design decisions and placeholders to adjust: `DECISIONS.md`.

## Project layout

- `core/` – pure Kotlin/JVM module (no Android): game and `.4gp` format,
  BitBully engine port (`engine/`), levels (p, s, w), engine move choice,
  match and session score; JUnit tests incl. comparison with the Python engine
- `app/` – Android app: `Controller` (game flow, port of the Qt `MainWindow`),
  `BoardView` (Canvas), `MainActivity`, `MatchActivity`, `HelpActivity`
- `app/src/main/res/values*/strings.xml`, `res/raw*/help.json`,
  `assets/sets/`, `assets/book/` – generated from the desktop sources (see
  `scripts/`), committed so that the build needs no Python
- `scripts/` – generators: `gen_resources.py` (texts, help, stone sets),
  `encode_book.py` (opening book), `gen_engine_reference.py` (test reference
  data from the Python engine), `gen_icons.py` (PNG icons)
- `fastlane/` – F-Droid metadata (en-US, de-DE), `docs/fdroid-metadata.yml` –
  template for the fdroiddata recipe

## Build

Requirements: Android SDK with platform `android-37` and build-tools
`36.0.0`, a JDK 21 (Gradle selects it via `gradle/gradle-daemon-jvm.properties`;
the wrapper itself may start on any JDK ≥ 17).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

```bash
./gradlew test lint assembleDebug assembleRelease
```

Results:

- `app/build/outputs/apk/debug/app-debug.apk` – debug build (debug-signed)
- `app/build/outputs/apk/release/app-release-unsigned.apk` – release build,
  unsigned, minified with R8 (F-Droid signs it or verifies a developer build)
- Test report: `core/build/reports/tests/test/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

To sign a release yourself (GitHub releases / reproducible F-Droid builds),
sign the unsigned APK **directly** with `--alignment-preserved` – no
`zipalign` step. Otherwise apksigner re-aligns the uncompressed entries and
F-Droid can no longer copy the signature onto its own (bit-identical) build:

```bash
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH ~/Android/Sdk/build-tools/36.0.0/apksigner sign --alignment-preserved --ks ~/connectfour-release.jks --ks-key-alias cfs --out ConnectFourStudio-1.0.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Signing certificate SHA-256 (also in `docs/fdroid-metadata.yml`):
`0290aabc0607b5ba98d1fbf67e8f03b166d04f2d6545634f99030278c60546a1`

## Install

1. Download `ConnectFourStudio-<version>.apk` from the
   [latest release](https://github.com/Patrick-Ric/connectfour-studio-android/releases/latest)
   on your phone (or copy it there).
2. Open it and allow installing apps from this source when Android asks.

Android 5.0 or newer; no permissions needed. On the very first start the
opening book is unpacked once ("Preparing the opening book …", about a second).

For developers: install via adb, unit tests and emulator setup are described
in `docs/TESTING.md`.

## F-Droid

Inclusion in F-Droid is in review:
https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51839 (recipe:
`docs/fdroid-metadata.yml`, reproducible build with the developer signature).
Store texts, icon and screenshots come from `fastlane/metadata/android/`.
How to publish a new version: `docs/RELEASING.md`.

## Credits

- Engine: **BitBully by Markus Thill** – https://github.com/MarkusThill/BitBully (AGPL v3)
- Opening book: **bitbully-databases** (`12-ply-dist`) by Markus Thill – MIT
- Desktop program, stone sets, texts and help: ConnectFour Studio (Patrick Götz)
