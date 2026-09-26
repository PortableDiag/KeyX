# KeyX

An offline Android keyboard to replace SwiftKey, with its feature set and its
**Material Phosphor Green** look — and **no `INTERNET` permission**. The only
permission is `VIBRATE`; nothing typed here can leave the phone through it.

## What it does

| | |
|---|---|
| Autocorrect | on space and punctuation; neighboring-key slips, doubled letters, missing apostrophes and accents are cheap edits. Backspace straight after a correction puts the typed word back and teaches it |
| Predictions | completions while typing, next-word predictions after; the strip is SwiftKey's shape — center is what space commits, left shows the word as typed when a correction is pending |
| Emoji | dedicated emoji key and panel (Emoji ≤14, recents); emoji predictions ("pizza" → 🍕) |
| Swipe typing | shape-matching decoder over the dictionary; swiped words get spaces automatically, backspace removes one whole |
| Space bar | flick left/right = switch language; long-press then drag = cursor control; shows the language name |
| Rows | number row; **arrow-key row** below the keyboard in SwiftKey's order: ↑ ↓ ← → |
| Backspace | hold to repeat (whole words after a while); swipe left deletes a word |
| Long-press | the symbol hint on each key, plus every accent |
| Voice | long-press comma (or toolbar) hands over to the system voice keyboard — KeyX has no microphone |
| Clipboard | a fresh copy is offered in the strip; the panel holds this session's clips in memory only — **ClipX** keeps the history (open / save-to buttons) |
| Themes | **Phosphor Green** (default), plus the Trellis Android themes — Ocean, Terminal, Trellis, Sticky Notes, Futuristic, SynthWave, Blueprint, Silkscreen, Phosphor P31 — and Amber Terminal, Graphite. The settings app wears whichever theme the keyboard does |
| Also | auto-caps, double-space period, smart punctuation, key-press popup, keyboard height, vibration strength |

**Languages:** en_US, en_GB (British spelling; both Englishes share one learned vocabulary), de_DE (QWERTZ, Ü Ö Ä), ru_RU (ЙЦУКЕН). Each is a
layout (`assets/layouts/<id>.json`) plus a dictionary pack (`assets/dicts/`).
Layouts and themes are **data**: Settings edits a layout's JSON (validated
before it is saved) and makes a custom theme from the current one.

**Learning** is a per-language word and word-pair model, sealed with AES-GCM
under an Android Keystore key (`learned.sealed`). Settings → *Import words*
(plain word list, optional counts), *Export*, *Reset learning* — reset deletes
the model, the file, the key and the emoji recents. Password fields and
`IME_FLAG_NO_PERSONALIZED_LEARNING` fields are never learned from; password
fields get no suggestions. Backup and device transfer are excluded.

## Known gaps

- **SwiftKey import via root** is not built — its learned-model format was not
  available to test against. *Import words* takes a plain word list.
- **German noun case** is partly data-limited: the source text is lowercase and
  hunspell's flags cannot always tell a noun from a nominalized adjective, so a
  few words (`mädchen` before `Mädchen`) rank in the wrong case until typing
  teaches them.
- **Not verified on a real device yet:** voice hand-off, SMS one-time codes
  surfacing with a third-party IME, the ClipX buttons, and whether the fast-typing
  fixes cure the jam seen on the phone (the emulator could not reproduce it).

## Layout of the code

| | |
|---|---|
| `predict/` | dictionary, suggester (autocorrect/prediction), learned model, gesture decoder — pure Kotlin |
| `ime/InputEngine` | every editing decision, against an `Editor` interface — tested on the JVM with a fake editor |
| `ime/KeyXService`, `ime/KeyboardView` | the input method and its one Canvas view (strip, keys, arrow row, panels) |
| `layout/`, `theme/` | JSON → keys and colors |
| `data/` | settings, Keystore sealing, the shared per-process repository |
| `tools/` | `build_dicts.py`, `build_emoji.py` — rebuild the bundled data |

Dictionary data: [FrequencyWords](https://github.com/hermitdave/FrequencyWords)
(OpenSubtitles 2018, CC BY-SA 4.0), filtered against the system word lists and,
for German, LibreOffice's hunspell `de_DE_frami` to restore umlauts and noun case.
Emoji: Unicode `emoji-test.txt` 15.1.

## Toolchain

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
