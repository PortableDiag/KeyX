# KeyX

Android + Compose, on the toolchain every Android project on this machine
already builds with: **AGP 8.2.2, Kotlin 1.9.24, Gradle 8.11.1, JDK 17**, Compose
BOM 2024.06.00, `minSdk 26 / targetSdk 34`.

## Signing

There is **no signing config in Gradle and no keystore in this repo**, by
design. Release artifacts are signed by keystore-manager, which never hands out
a key:

```
./gradlew assembleRelease
pf sign app/build/outputs/apk/release/app-release-unsigned.apk --key KeyX
```

`pf sign` sends the artifact to the vault, gets it back signed, writes it beside
the original and then **verifies the signature locally with `apksigner`** — a
signature nothing has checked is a signature taken on trust.

A Gradle signing config means a keystore and its passwords have to live
somewhere a build can read them, and "somewhere a build can read them" has meant
committed to the repo more than once.

## Build

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

`local.properties` points Gradle at the SDK. It is gitignored, because it is a
path on one machine.

## Gates

`pf gate .` runs all four. The one that matters is smoke:

| gate | what it does |
|---|---|
| build | `./gradlew assembleDebug` |
| test | `./gradlew testDebugUnitTest` |
| lint | `./gradlew lintDebug` |
| smoke | installs the APK on a device, launches it, **checks the process is still alive four seconds later**, and screencaps it to `pf-smoke.png` |

`adb install` prints `Failure` and exits 0, and `monkey` exits 0 for an app that
crashed on start. Both are checked explicitly, because both have looked like
success on this machine before.

The smoke gate needs a device or emulator attached:

```
$ANDROID_HOME/emulator/emulator -avd pixel_api35 -no-window -no-audio &
adb wait-for-device
```
