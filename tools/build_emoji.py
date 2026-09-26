#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji/{emoji,predict}.tsv from Unicode's emoji-test.txt.

emoji.tsv:   group<TAB>emoji<TAB>name   fully-qualified, Emoji 14.0 or older (what
             Android 12+ fonts draw), no skin-tone variants — the panel stays one
             screen per group instead of six.
predict.tsv: word<TAB>emoji             English words that suggest an emoji: every
             one-word emoji name, plus the curated list below.
"""
import re, sys, os

src, out = sys.argv[1], sys.argv[2]
MAX_VERSION = 14.0
GROUPS = {"Smileys & Emotion": "smileys", "People & Body": "people",
          "Animals & Nature": "nature", "Food & Drink": "food",
          "Travel & Places": "travel", "Activities": "activities",
          "Objects": "objects", "Symbols": "symbols", "Flags": "flags"}
CURATED = {
    "love": "❤️", "heart": "❤️", "lol": "😂", "haha": "😂", "funny": "😂",
    "happy": "😊", "smile": "😊", "sad": "😢", "cry": "😭", "ok": "👍", "okay": "👍",
    "yes": "👍", "good": "👍", "great": "👍", "thanks": "🙏", "thank": "🙏", "please": "🙏",
    "sorry": "😔", "wow": "😮", "cool": "😎", "hot": "🔥", "fire": "🔥", "lit": "🔥",
    "party": "🎉", "congrats": "🎉", "congratulations": "🎉", "birthday": "🎂",
    "kiss": "😘", "angry": "😠", "mad": "😠", "sleep": "😴", "tired": "😴",
    "think": "🤔", "hmm": "🤔", "idk": "🤷", "shrug": "🤷", "hi": "👋", "hello": "👋",
    "bye": "👋", "coffee": "☕", "beer": "🍺", "wine": "🍷", "money": "💰", "cash": "💵",
    "sun": "☀️", "sunny": "☀️", "rain": "🌧️", "snow": "❄️", "cold": "🥶", "sick": "🤒",
    "car": "🚗", "home": "🏠", "house": "🏠", "work": "💼", "phone": "📱", "music": "🎵",
    "food": "🍽️", "hungry": "😋", "yum": "😋", "strong": "💪", "clap": "👏",
    "done": "✅", "check": "✅", "no": "❌", "wrong": "❌", "warning": "⚠️",
    "eyes": "👀", "look": "👀", "star": "⭐", "night": "🌙", "morning": "🌅",
    "christmas": "🎄", "gift": "🎁", "rocket": "🚀", "bug": "🐛", "ghost": "👻",
    "skull": "💀", "dead": "💀", "100": "💯", "perfect": "💯", "muscle": "💪",
}

rows, predict = [], {}
group = None
for line in open(os.path.join(src, "emoji-test.txt"), encoding="utf-8"):
    if line.startswith("# group:"):
        group = GROUPS.get(line.split(":", 1)[1].strip())
        continue
    m = re.match(r"^[0-9A-F ]+;\s*fully-qualified\s*#\s*(\S+)\s+E(\d+\.\d+)\s+(.+)$", line)
    if not m or group is None:
        continue
    emoji, version, name = m.group(1), float(m.group(2)), m.group(3).strip()
    if version > MAX_VERSION or "skin tone" in name:
        continue
    rows.append((group, emoji, name))
    if re.fullmatch(r"[a-z]+", name) and name not in predict:
        predict[name] = emoji
predict.update(CURATED)

os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "emoji.tsv"), "w", encoding="utf-8") as f:
    for r in rows:
        f.write("\t".join(r) + "\n")
with open(os.path.join(out, "predict.tsv"), "w", encoding="utf-8") as f:
    for w in sorted(predict):
        f.write(f"{w}\t{predict[w]}\n")
print(len(rows), "emoji,", len(predict), "prediction words")
