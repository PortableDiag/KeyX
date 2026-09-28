# Changelog

## 0.2.3 — 2026-09-28

- Erasing part of a word and typing on extends that word: "Boating" erased to
  "Boa" plus "ring" is "Boaring", and suggestions are for "Boaring" — not for
  "ring". Backspacing into a word used to delete and re-set it; the phone
  reported those edits late, KeyX took the late report for a cursor move and
  dropped the word. Now the word is marked in place (nothing to report), and
  letters typed onto the end of a word always join it.
- README: the smoke gate runs on KeyX's own emulator (`pf emulator keyx_api35`),
  never a shared one.

## 0.2.2 — 2026-09-28

- Two themes after SwiftKey's glowing outlines: **Neon** (cyan letter keys,
  violet function keys) and **White Glow**.
- Themes take three optional keys: `glow`, `keyEdge` and `functionBorder`.
  Existing themes don't set them and look the same as before.

## 0.2.1 — 2026-09-26

- Period key works like SwiftKey's quick punctuation: a quick slide right types
  `?`, a quick slide left types `!`.
- Long-press on period opens with `.` directly over the key, `?` one step to the
  right and `!` one step to the left. It used to open on `,` and get pushed
  left against the screen edge, which put `?` far to the left.

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
