import json, argparse, random
from pathlib import Path
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
DEF_SRC = ROOT / "data" / "kaggle_pii" / "raw"
DEF_OUT = ROOT / "data" / "kaggle_pii" / "pii_hf"

# Map single-letter codes + verbose labels to your schema
LABEL_MAP = {
    "N": "PERSON", "E": "EMAIL", "P": "PHONE", "S": "ADDRESS", "I":"ID_GENERIC","U":"ID_GENERIC",
    "PERSON":"PERSON","NAME_STUDENT":"PERSON","NAME":"PERSON",
    "EMAIL":"EMAIL","E-MAIL":"EMAIL",
    "PHONE":"PHONE","PHONE_NUMBER":"PHONE","MOBILE":"PHONE",
    "ADDRESS":"ADDRESS","STREET":"ADDRESS","CITY":"ADDRESS","STATE":"ADDRESS","ZIP":"ADDRESS",
    "ORG":"ORG","ORGANIZATION":"ORG","COMPANY":"ORG","SCHOOL":"ORG",
    "SSN":"ID_SSN","TIN":"ID_TIN","EIN":"ID_TIN","ID":"ID_GENERIC","USERNAME":"ID_GENERIC"
}

ap = argparse.ArgumentParser()
ap.add_argument("--src", type=Path, default=DEF_SRC, help="Folder with train.json/test.json")
ap.add_argument("--out", type=Path, default=DEF_OUT, help="Output folder for HF JSONL")
ap.add_argument("--debug", action="store_true")
args = ap.parse_args()
args.out.mkdir(parents=True, exist_ok=True)

def load_any(path: Path):
    with path.open("r", encoding="utf-8") as f:
        first = f.read(1); f.seek(0)
        if first == "[":
            data = json.load(f)
            for r in data: yield r
        else:
            for line in f:
                line=line.strip()
                if line: yield json.loads(line)

def map_label(raw):
    return LABEL_MAP.get(str(raw).upper())

def detokenize(tokens, trailing_ws):
    # Build text using trailing_whitespace flags
    # trailing_ws may be list[bool] or list[int/0/1] with same length as tokens
    pieces = []
    for tok, ws in zip(tokens, trailing_ws):
        pieces.append(tok)
        if ws: pieces.append(" ")
    return "".join(pieces)

def bio_to_char_spans(tokens, trailing_ws, tags):
    text = detokenize(tokens, trailing_ws)
    spans = []
    pos = 0
    starts = []
    # Precompute start/end for each token in the detok string
    tok_offsets = []
    for tok, ws in zip(tokens, trailing_ws):
        start = pos
        end = start + len(tok)
        tok_offsets.append((start, end))
        pos = end + (1 if ws else 0)

    open_start = None
    open_lab = None
    prev_end = None

    for (start, end), tag in zip(tok_offsets, tags):
        if not tag or tag == "O":
            if open_start is not None and prev_end is not None:
                spans.append({"start": open_start, "end": prev_end, "label": open_lab})
                open_start = None; open_lab = None
            continue
        pref, sep, code = tag.partition("-")
        mapped = map_label(code if code else tag)  # handle plain 'N' etc (rare)
        if not mapped:
            # Close any open span if label unmapped
            if open_start is not None and prev_end is not None:
                spans.append({"start": open_start, "end": prev_end, "label": open_lab})
                open_start = None; open_lab = None
            continue
        if pref == "B" or (open_lab and mapped != open_lab):
            if open_start is not None and prev_end is not None:
                spans.append({"start": open_start, "end": prev_end, "label": open_lab})
            open_start, open_lab = start, mapped
        else:  # I-*
            if open_start is None:
                open_start, open_lab = start, mapped
        prev_end = end

    if open_start is not None and prev_end is not None:
        spans.append({"start": open_start, "end": prev_end, "label": open_lab})

    return text, spans

def normalize_record(rec):
    # Your file has: tokens, trailing_whitespace, labels (BIO per token)
    tokens = rec.get("tokens")
    trailing_ws = rec.get("trailing_whitespace") or rec.get("trailing_whitespaces") or []
    tags = rec.get("labels") or rec.get("tags") or []
    if isinstance(tokens, list) and isinstance(trailing_ws, list) and isinstance(tags, list):
        if len(tokens)==len(trailing_ws)==len(tags) and tokens:
            text, ents = bio_to_char_spans(tokens, trailing_ws, tags)
            return {"id": rec.get("id") or str(hash(text)), "text": text, "entities": ents}

    # Fallbacks (other formats)
    text = rec.get("text") or rec.get("full_text") or rec.get("document") or ""
    ents = []
    list_like = rec.get("entities") or rec.get("spans") or rec.get("annotations")
    if isinstance(list_like, list):
        for e in list_like:
            lab = map_label(e.get("label"))
            if not lab: continue
            try:
                s, t = int(e["start"]), int(e["end"])
                if 0 <= s < t <= len(text):
                    ents.append({"start": s, "end": t, "label": lab})
            except: pass
    if isinstance(rec.get("labels"), list) and rec["labels"]:
        if isinstance(rec["labels"][0], list):
            for s,t,raw in rec["labels"]:
                lab = map_label(raw)
                if lab and 0 <= s < t <= len(text):
                    ents.append({"start": s, "end": t, "label": lab})
        elif isinstance(rec["labels"][0], dict):
            for e in rec["labels"]:
                lab = map_label(e.get("label"))
                if not lab: continue
                s, t = int(e["start"]), int(e["end"])
                if 0 <= s < t <= len(text):
                    ents.append({"start": s, "end": t, "label": lab})
    if text.strip():
        return {"id": rec.get("id") or str(hash(text)), "text": text, "entities": ents}
    return None

# Collect all .json/.jsonl
raw_files = sorted(list(args.src.glob("*.json")) + list(args.src.glob("*.jsonl")))
if args.debug: print("FILES:", [f.name for f in raw_files])

converted = []
for fp in raw_files:
    for r in load_any(fp):
        ex = normalize_record(r)
        if ex: converted.append(ex)

random.seed(42); random.shuffle(converted)
n = len(converted); n_tr = int(0.8*n); n_dev = int(0.1*n)
splits = {
    "train.jsonl": converted[:n_tr],
    "dev.jsonl":   converted[n_tr:n_tr+n_dev],
    "test.jsonl":  converted[n_tr+n_dev:]
}
for name, data in splits.items():
    with (args.out / name).open("w", encoding="utf-8") as w:
        for ex in data:
            w.write(json.dumps(ex, ensure_ascii=False) + "\n")

labels = sorted({e["label"] for ex in converted for e in ex["entities"]})
(args.out / "labels.json").write_text(json.dumps(labels, indent=2), encoding="utf-8")
cnt = Counter(e["label"] for ex in converted for e in ex["entities"])
(args.out / "label_stats.json").write_text(json.dumps(cnt.most_common(), indent=2), encoding="utf-8")

print("SRC:", args.src.resolve())
print("OUT:", args.out.resolve())
print("Files:", len(raw_files), "Records:", n)
print("train/dev/test:", len(splits["train.jsonl"]), len(splits["dev.jsonl"]), len(splits["test.jsonl"]))
print("Labels:", labels)
