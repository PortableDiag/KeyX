#!/usr/bin/env python3
"""Builds app/src/main/assets/dicts/*.tsv from frequency lists.

Inputs (not committed — fetched once, they are large):
  <src>/{en,de,ru}_50k.txt   hermitdave/FrequencyWords 2018 (CC-BY-SA-4.0 content)
  <src>/de_DE.dic            LibreOffice de_DE_frami hunspell, used only to restore
                             German noun capitalisation (subtitle text is lowercased)
  /usr/share/dict/american-english -> en.tsv, british-english -> en_GB.tsv: each
                             drops non-words and the other variety's spellings,
                             and restores proper-noun case

Output: one `word<TAB>count` per line, most frequent first.
"""
import os, re, sys

src, out = sys.argv[1], sys.argv[2]
LIMIT = 40000

def read_freq(lang):
    for line in open(os.path.join(src, f"{lang}_50k.txt"), encoding="utf-8"):
        parts = line.split()
        if len(parts) == 2:
            yield parts[0], int(parts[1])

def words(path, encoding="utf-8"):
    try:
        return [w.strip() for w in open(path, encoding=encoding, errors="ignore") if w.strip()]
    except FileNotFoundError:
        return []

def write(name, rows):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, f"{name}.tsv"), "w", encoding="utf-8") as f:
        for w, c in rows[:LIMIT]:
            f.write(f"{w}\t{c}\n")
    print(name, min(len(rows), LIMIT))

# English: one pack per spelling. A word is kept only if that variety's system
# word list knows it, in the case the list gives it — so en_US has "color" and
# not "colour", en_GB the reverse.
freq = dict(read_freq("en"))
FRAGMENT = {"don": "don't", "didn": "didn't", "doesn": "doesn't", "isn": "isn't",
            "wasn": "wasn't", "aren": "aren't", "weren": "weren't", "couldn": "couldn't",
            "wouldn": "wouldn't", "shouldn": "shouldn't", "hasn": "hasn't",
            "haven": "haven't", "hadn": "hadn't", "ain": "ain't", "mustn": "mustn't"}
# The source tokenises "don't" as "don" + "'t", so contractions are rebuilt:
# a fragment that is not a word on its own ("don", "isn") carries the count of
# its contraction; the rest take a share of their suffix token's count.
SHARE = {"I'm": ("'m", 1.0), "it's": ("'s", .25), "that's": ("'s", .18),
         "what's": ("'s", .08), "let's": ("'s", .06), "he's": ("'s", .07),
         "she's": ("'s", .05), "there's": ("'s", .05), "can't": ("'t", .12),
         "won't": ("'t", .06), "you're": ("'re", .5), "we're": ("'re", .25),
         "they're": ("'re", .2), "I'll": ("'ll", .4), "you'll": ("'ll", .15),
         "we'll": ("'ll", .15), "he'll": ("'ll", .05), "she'll": ("'ll", .03),
         "they'll": ("'ll", .05), "it'll": ("'ll", .05), "I've": ("'ve", .45),
         "you've": ("'ve", .2), "we've": ("'ve", .15), "they've": ("'ve", .08),
         "I'd": ("'d", .4), "you'd": ("'d", .2), "he'd": ("'d", .08),
         "she'd": ("'d", .05), "we'd": ("'d", .08), "they'd": ("'d", .06)}
contractions = [(c, freq.get(f, 0)) for f, c in FRAGMENT.items()]
contractions += [(c, int(freq.get(tok, 0) * share)) for c, (tok, share) in SHARE.items()]

def english(wordlist):
    forms = {}
    for w in words(wordlist):
        lw = w.lower()
        if lw not in forms or w == lw:
            forms[lw] = w  # prefer the lowercase form when both exist
    rows, seen = [], set()
    for w, c in read_freq("en"):
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) or w in FRAGMENT:
            continue
        if len(w) == 1 and w not in ("a", "i"):
            continue
        form = "I" if w == "i" else ("I" + w[1:] if w.startswith("i'") else forms.get(w))
        if form is None or form in seen:
            continue
        seen.add(form)
        rows.append((form, c))
    return sorted(rows + [e for e in contractions if e[1] > 0], key=lambda r: -r[1])

write("en", english("/usr/share/dict/american-english"))
write("en_GB", english("/usr/share/dict/british-english"))

# German: restore noun capitalisation from hunspell stems.
de_lower, de_cap, de_noun = set(), set(), set()
for line in words(os.path.join(src, "de_DE.dic"), "latin-1")[1:]:
    stem, _, flags = line.partition("/")
    stem = stem.strip()
    if not stem or stem.startswith("#"):
        continue
    if stem[0].isupper():
        de_cap.add(stem.lower())
        # Plural/case flags mark a real noun; S/J/h/i/j alone mark a nominalised
        # verb or adjective ("das Finden", "das Schön").
        if any(f in flags for f in "PENMFTR"):
            de_noun.add(stem.lower())
    elif "o" not in flags:
        # "frau/MPozm" is the lowercase tail of a compound ("Ehefrau"), not a
        # lowercase word in its own right.
        de_lower.add(stem)
DE_SUFFIXES = ("en", "n", "e", "s", "es", "er", "ern", "ns", "em", "es", "st", "t", "te", "ten")
# Function words hunspell also lists as capitalised proper nouns or symbols.
# and closed-class words, which are never nouns however hunspell lists them.
DE_LOWER = set("""
eure euer eurer euren eurem eures he ihr ihre ihren ihrem ihrer ihres sie er es ich du wir
mich mir dich dir uns euch ihn ihm ihnen sich mein meine meiner meinen meinem meines dein
deine deiner deinen deinem deines sein seine seiner seinen seinem seines unser unsere
unserer unseren unserem der die das den dem des ein eine einer einen einem eines kein
keine keiner keinen keinem keines dieser diese dieses diesen diesem jener jene jenes
welcher welche welches welchen welchem man nicht nichts auch noch schon nur doch mal ja
nein so wie was wer wo wann warum wieso weshalb woher wohin hier da dort jetzt dann
immer nie oft sehr gar ganz gut mehr viel viele wenig alle alles allem allen aller
jeder jede jedes jeden jedem etwas und oder aber denn sondern weil wenn dass ob als
bevor nachdem während obwohl damit also sonst trotzdem mit ohne für gegen um durch
bis seit von vom zu zum zur bei beim nach aus in im an am auf über unter vor hinter
neben zwischen wegen statt sein bin bist ist sind seid war warst waren wart gewesen
haben habe hast hat habt hatte hattest hatten hattet gehabt werden werde wirst wird
werdet wurde wurden geworden kann kannst können könnt konnte könnte muss musst müssen
müsst musste müsste soll sollst sollen sollt sollte will willst wollen wollt wollte
darf darfst dürfen durfte mag magst mögen möchte möchtest gehen geht ging kommen kommt
kam machen macht machte sagen sagt sagte sehen sieht sah wissen weiß wusste lassen
lass lässt ließ tun tut tat geben gibt gab nehmen nimmt nahm leben lebt lebte
""".split())
def de_forms(w):
    """[(form, share)] — both cases when German has both (leben / Leben)."""
    cap = w[0].upper() + w[1:]
    if w in DE_LOWER:
        return [(w, 1.0)]
    stems = [w[: -len(s)] for s in DE_SUFFIXES if w.endswith(s) and len(w) - len(s) >= 2]
    is_cap = w in de_cap or any(st in de_cap and len(st) >= 4 for st in stems)
    is_low = (w in de_lower or any(st in de_lower for st in stems)
              or w + "n" in de_lower or w + "en" in de_lower)  # bitte <- bitten
    if is_cap and is_low:
        # The engine prefers whichever case the user typed when both exist, so
        # this split only orders the two forms for a bare prediction.
        return [(cap, 0.6), (w, 0.4)] if w in de_noun else [(w, 0.8), (cap, 0.2)]
    return [(cap, 1.0)] if is_cap else [(w, 1.0)]
# Subtitle text often drops umlauts ("fur", "madchen"). Such a word goes when a
# form with ä/ö/ü/ß is at least ten times as common and hunspell does not know
# the plain one.
de_freq = dict(read_freq("de"))
UMLAUT = {"a": "ä", "o": "ö", "u": "ü"}
def umlaut_variants(w):
    out = {w.replace("ss", "ß")} if "ss" in w else set()
    for i, ch in enumerate(w):
        if ch in UMLAUT:
            out.add(w[:i] + UMLAUT[ch] + w[i + 1:])
    return out
def de_misspelt(w, c):
    if w in de_lower or w in de_cap:
        return False
    return any(de_freq.get(v, 0) >= 10 * c for v in umlaut_variants(w))

de_rows, seen = [], set()
for w, c in read_freq("de"):
    if de_misspelt(w, c):
        continue
    if not re.fullmatch(r"[a-zäöüß]+", w) or (len(w) == 1 and w not in ("a",)):
        continue
    for form, share in de_forms(w):
        if form not in seen:
            seen.add(form)
            de_rows.append((form, int(c * share)))
de_rows.sort(key=lambda r: -r[1])
write("de", de_rows)

ru_rows = [(w, c) for w, c in read_freq("ru")
           if re.fullmatch(r"[а-яё]+", w) and (len(w) > 1 or w in "явиаоску")]
write("ru", ru_rows)
