#!/usr/bin/env python3
"""Builds app/src/main/assets/pairs/{en,de,ru}.tsv: which words follow which.

Input (not committed): <src>/{eng,deu,rus}.tsv, Tatoeba per-language sentence
exports (CC BY 2.0), `id<TAB>lang<TAB>text`. Every 20th sentence is held out, so
accuracy can be measured on text the pairs have not seen.

Output: `previous<TAB>word:lift word:lift ...`, most frequent next word first.
lift is 10 * ln(P(word|previous) / P(word)), floored at 0.
"""
import collections, math, os, re, sys
src, out = sys.argv[1], sys.argv[2]
MIN, PER, TOTAL = 4, 60, 40000
# Tatoeba's stock characters; they say nothing about how anyone writes.
SKIP = {"tom", "mary", "maria", "tom's", "mary's", "том", "тома", "тому", "томом", "мэри"}
os.makedirs(out, exist_ok=True)
for code, name in (("eng", "en"), ("deu", "de"), ("rus", "ru")):
    uni, pairs = collections.Counter(), collections.Counter()
    for line in open(os.path.join(src, code + ".tsv"), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 3 or int(p[0]) % 20 == 0:
            continue
        for run in re.split(r"[.!?;:,\"()]+", p[2].lower().replace("’", "'")):
            ws = re.findall(r"[^\W\d_]+(?:'[^\W\d_]+)*", run)
            uni.update(ws)
            pairs.update((a, b) for a, b in zip(ws, ws[1:]) if a not in SKIP and b not in SKIP)
    n = sum(uni.values())
    kept, per = collections.defaultdict(list), collections.Counter()
    total = 0
    for (a, b), c in pairs.most_common():
        if c < MIN or total >= TOTAL:
            break
        if per[a] >= PER:
            continue
        per[a] += 1; total += 1
        kept[a].append((b, max(0, round(10 * math.log(c / uni[a] / (uni[b] / n))))))
    with open(os.path.join(out, name + ".tsv"), "w", encoding="utf-8") as f:
        for a in kept:
            f.write(a + "\t" + " ".join(f"{b}:{l}" for b, l in kept[a]) + "\n")
    print(name, total, "pairs", len(kept), "words")
