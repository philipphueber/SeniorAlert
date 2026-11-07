# Contributing

How SeniorAlert is built and what a change has to respect. How the code fits
together is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Ground rules

- **Simplicity is the product.** The app is one screen and one ringing screen.
  Alarms are a time plus ON/OFF, they ring once and stop after 2 minutes. No
  snooze, labels, weekdays, repeat, sound choice or settings screen. A pull
  request that adds a feature will most likely be declined; one that removes
  complexity is welcome.
- **No dependencies.** Plain Java against the Android platform APIs. No
  AndroidX, Kotlin, Compose or third-party libraries. `testImplementation` of
  JUnit is the only exception.
- **A ringing alarm can always be switched off.** Every change to
  `RingService`, `RingActivity`, `StopReceiver`, `AlarmReceiver` or the banner
  in `MainActivity` must keep every stop path working, none of them behind the
  PIN. See "The stop-path invariant" in ARCHITECTURE.md.
- **English and German.** Every user-visible string goes in both
  `res/values/strings.xml` and `res/values-de/strings.xml` (formal "Sie").
- **Big and plain.** Large text, large touch targets, high contrast, no
  gestures, no swipe, and no long-press, with one deliberate exception: Unlock
  needs a long press, so a senior cannot open the PIN prompt by accident.

## Building

Needs JDK 17 and the Android SDK (API 35). Point Gradle at the SDK with a
gitignored `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

    ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew assembleRelease   # signed only if signing is configured, see below
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## Testing

Unit tests carry the weight; UI testing is kept minimal.

    ./gradlew test        # JVM unit tests (Alarm, AlarmCodec, NextTrigger, Pin)
    ./gradlew lint
    scripts/smoke.sh      # one on-device check: an alarm rings and Turn off stops it

Logic that can be tested without a device belongs in a pure Java class (no
`android.*` imports) with a JVM test, like `NextTrigger` and `AlarmCodec`.

## Release signing

Maintainers only. Run once:

    scripts/setup-signing.sh            # random password, straight into the keyring
    scripts/setup-signing.sh --prompt   # or type your own

It creates `~/keys/senioralert-release.jks` (outside the repo), stores the
password in the system keyring (`secret-tool lookup service senioralert key
release-keystore`), and writes the gitignored `keystore.properties`. It refuses
to replace an existing key: Android only accepts an update signed by the same
key, so a new key would strand everyone who installed the app.

On a machine without a keyring, add a `storePassword=...` line to
`keystore.properties`. Without any of this the release build is unsigned;
debug builds and tests are unaffected.

## Releasing

1. Bump `appVersionName` and `appVersionCode` in `app/build.gradle.kts`.
2. `./gradlew test lint assembleRelease`
3. Check that the output is `app/build/outputs/apk/release/SeniorAlert-<version>-release.apk`.
   An `-unsigned` name means signing was not configured.
4. Verify the signature:
   `$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs <apk>`.
   The SHA-256 must be
   `A8:AD:AB:92:E2:7F:5F:9C:CD:1D:1A:18:E1:DD:CE:D6:4E:EA:F3:53:7D:40:36:95:98:99:56:15:D8:24:A9:28`.
5. Tag `v<version>` and attach the APK to a GitHub release, renamed to
   `SeniorAlert-<version>.apk`. Versions are 0.0.x for now and are published as
   pre-releases.
