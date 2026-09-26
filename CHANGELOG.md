# Changelog

## 0.2.0 — 2026-09-26

First release. Signed by keystore-manager with the `KeyX` record (certificate
SHA-256 `ED:C1:80:A6:78:73:49:32:74:F4:58:69:BC:4A:AD:9E:C2:D2:F3:32:06:40:47:78:DF:B9:10:42:98:C8:D5:88`).

- An offline `InputMethodService` replacing SwiftKey. The only permission is `VIBRATE`.
- Autocorrect with undo on backspace; completions and next-word predictions in
  SwiftKey's three-slot strip; swipe typing; emoji panel and emoji predictions.
- Number row, long-press symbols and all accents, key-press popup, arrow row in
  SwiftKey's order (↑ ↓ ← →), swipe-left backspace deletes a word.
- Space bar: flick switches language, long-press then drag moves the cursor.
- Languages en_US, en_GB, de_DE, ru_RU. Layouts and themes are JSON, editable in settings.
- Themes: Phosphor Green (default), the Trellis Android themes (Ocean, Terminal,
  Trellis, Sticky Notes, Futuristic, SynthWave, Blueprint, Silkscreen,
  Phosphor P31), Amber Terminal, Graphite. The settings app follows the keyboard theme.
- Learned words sealed with AES-GCM under an Android Keystore key; import,
  export, and a reset that also deletes the key. Password and incognito fields
  are never learned from.
- App text in American English.

## 0.1.1 — 2026-09-26 (debug build, not released)

- Fast typing: per-keystroke suggestion cost cut ~15x (≤1.4 ms on a desktop
  JVM); late cursor reports no longer abandon the word being typed; a second
  finger landing on space or backspace no longer drops a key.
- English (India) replaced with English (UK); US and UK dictionaries separated
  by spelling.

## 0.1.0 — 2026-09-26 (debug build, not released)

- First working keyboard, tested on the emulator.
