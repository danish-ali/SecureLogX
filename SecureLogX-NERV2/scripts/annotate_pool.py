#!/usr/bin/env python3
# SecureLogX minimal CLI annotator (MODEL-ONLY prelabels; no regex)
# Input : pool.jsonl  lines like {"id","text","meta",...}
# Output: labeled_pool.jsonl lines like {"text","spans":[{"start","end","label"}]}

import json, sys
from pathlib import Path

import torch
from transformers import AutoTokenizer, AutoModelForTokenClassification

# ---- Canonical label normalization (no credit card) ----
LABEL_MAP = {
    "N":"PERSON","E":"EMAIL","P":"PHONE","S":"ADDRESS","I":"ID_GENERIC","U":"ID_GENERIC",
    "PERSON":"PERSON","NAME_STUDENT":"PERSON","NAME":"PERSON",
    "EMAIL":"EMAIL","E-MAIL":"EMAIL",
    "PHONE":"PHONE","PHONE_NUMBER":"PHONE","MOBILE":"PHONE",
    "ADDRESS":"ADDRESS","STREET":"ADDRESS","CITY":"ADDRESS","STATE":"ADDRESS","ZIP":"ADDRESS",
    "ORG":"ORG","ORGANIZATION":"ORG","COMPANY":"ORG","SCHOOL":"ORG",
    "SSN":"ID_SSN","TIN":"ID_TIN","EIN":"ID_TIN","ID":"ID_GENERIC","USERNAME":"ID_GENERIC"
}
ALLOWED = {"PERSON","EMAIL","PHONE","ADDRESS","ORG","ID_SSN","ID_TIN","ID_GENERIC"}

HELP = """
Commands:
  a                     Accept current prelabels
  s                     Skip (write empty spans)
  e start end LABEL     Add span manually (char offsets, end exclusive). e.g.,  e 11 31 EMAIL
  d idx                 Delete span by index
  l                     List current spans
  p                     Preview with inline markers
  h                     Help
  q                     Quit (saves progress)
"""

def decode_bio_spans(text, id2label, logits, offsets):
    """
    Greedy BIO decode from token logits -> char spans with 'label' names (no 'B-/I-').
    Works for label sets like {O, B-EMAIL, I-EMAIL, ...}.
    """
    ids = logits.argmax(dim=-1).tolist()        # [T]
    offs = offsets[0].tolist()                   # [[s,e], ...]
    spans = []
    start = None
    cur = None
    prev_end = None

    for j, lab_id in enumerate(ids):
        lab = id2label[lab_id]
        s, e = offs[j]
        if e <= s:
            continue
        if lab.startswith("B-"):
            if start is not None:
                spans.append({"start": start, "end": prev_end, "label": cur})
            start = s
            cur = lab[2:]
            prev_end = e
        elif lab.startswith("I-") and cur == lab[2:]:
            prev_end = e
        else:
            if start is not None:
                spans.append({"start": start, "end": prev_end, "label": cur})
            start = None
            cur = None
            prev_end = None

    if start is not None:
        spans.append({"start": start, "end": prev_end, "label": cur})
    return spans

def normalize_spans(spans):
    out = []
    seen = set()
    for sp in spans:
        lbl = LABEL_MAP.get(sp["label"].upper(), sp["label"].upper())
        if lbl in ALLOWED:
            key = (sp["start"], sp["end"], lbl)
            if key not in seen:
                out.append({"start": sp["start"], "end": sp["end"], "label": lbl})
                seen.add(key)
    return out

def print_spans(text, spans):
    for i, sp in enumerate(spans):
        frag = text[sp["start"]:sp["end"]]
        print(f"  [{i}] {sp['label']:>10}  ({sp['start']:>5},{sp['end']:>5}):  {frag}")

def highlight(text, spans):
    marks = []
    for i, sp in enumerate(spans):
        marks.append((sp["start"], f"[{sp['label']}#{i}>>"))
        marks.append((sp["end"], f"<<{sp['label']}#{i}]"))
    marks.sort(key=lambda x: x[0])
    parts, last = [], 0
    for pos, tag in marks:
        parts.append(text[last:pos]); parts.append(tag); last = pos
    parts.append(text[last:])
    return "".join(parts)

def model_prelabel(text, tok, mdl, device, max_length=256):
    enc = tok(text, return_offsets_mapping=True, truncation=True, max_length=max_length, return_tensors="pt")
    offs = enc.pop("offset_mapping")
    with torch.no_grad():
        logits = mdl(**{k: v.to(device) for k, v in enc.items()}).logits[0]  # [T,C]
    spans = decode_bio_spans(text, mdl.config.id2label, logits, offs)
    return normalize_spans(spans)

def main():
    if len(sys.argv) < 4:
        print("Usage: python annotate_pool.py <pool.jsonl> <labeled_pool.jsonl> <model_dir> [--limit N] [--local-only]", file=sys.stderr)
        sys.exit(1)
    inp = Path(sys.argv[1])
    outp = Path(sys.argv[2])
    model_dir = Path(sys.argv[3])

    # opts
    limit = None
    local_only = False
    for arg in sys.argv[4:]:
        if arg == "--local-only":
            local_only = True
        elif arg.startswith("--limit"):
            try:
                limit = int(arg.split()[-1])  # supports: "--limit 200" if passed as two tokens
            except Exception:
                pass

    if not inp.exists():
        print(f"[ERROR] Input file not found: {inp}", file=sys.stderr); sys.exit(1)
    if not (model_dir / "config.json").exists():
        print(f"[ERROR] Model dir missing config.json: {model_dir}", file=sys.stderr); sys.exit(1)

    # Load model
    print(f"[INFO] Loading model from: {model_dir}")
    tok = AutoTokenizer.from_pretrained(model_dir.as_posix(), local_files_only=local_only, use_fast=True)
    mdl = AutoModelForTokenClassification.from_pretrained(model_dir.as_posix(), local_files_only=local_only)
    mdl.eval()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    mdl.to(device)
    print(f"[INFO] Device: {device}")

    # Track what's already labeled to allow resume
    seen_texts = set()
    if outp.exists():
        with outp.open("r", encoding="utf-8") as r:
            for line in r:
                try:
                    obj = json.loads(line)
                    seen_texts.add(obj.get("text",""))
                except Exception:
                    pass

    written = 0
    with inp.open("r", encoding="utf-8") as f, outp.open("a", encoding="utf-8") as w:
        for line in f:
            obj = json.loads(line)
            text = obj.get("text") or obj.get("message") or obj.get("msg") or obj.get("payload") or obj.get("line") or obj.get("raw") or ""
            if not text or text in seen_texts:
                continue

            spans = model_prelabel(text, tok, mdl, device)
            print("\n" + "-"*100)
            print(text)
            print_spans(text, spans)

            while True:
                cmd = input("[a=accept, s=skip, e=add, d=del, l=list, p=preview, h=help, q=quit] > ").strip()
                if cmd == "a":
                    break
                elif cmd == "s":
                    spans = []
                    break
                elif cmd.startswith("e "):
                    parts = cmd.split()
                    if len(parts) >= 4:
                        try:
                            s, e = int(parts[1]), int(parts[2]); lbl = parts[3].upper()
                            lbl = LABEL_MAP.get(lbl, lbl)
                            if lbl in ALLOWED and 0 <= s < e <= len(text):
                                spans.append({"start": s, "end": e, "label": lbl})
                            else:
                                print(f"Bad offsets or label. Allowed: {sorted(ALLOWED)}")
                        except Exception:
                            print("Usage: e <start> <end> <LABEL>")
                    else:
                        print("Usage: e <start> <end> <LABEL>")
                elif cmd.startswith("d "):
                    try:
                        idx = int(cmd.split()[1]); spans.pop(idx)
                    except Exception:
                        print("Bad index.")
                elif cmd == "l":
                    print_spans(text, spans)
                elif cmd == "p":
                    print(highlight(text, spans))
                elif cmd == "h":
                    print(HELP)
                elif cmd == "q":
                    print("[INFO] Quitting… progress saved.")
                    sys.exit(0)
                else:
                    print("Unknown command. 'h' for help.")

            # Normalize (again) and write
            spans = normalize_spans(spans)
            w.write(json.dumps({"text": text, "spans": spans}, ensure_ascii=False) + "\n")
            w.flush()
            seen_texts.add(text)
            written += 1
            if limit and written >= limit:
                print(f"[INFO] Reached limit {limit}.")
                break

    print(f"[DONE] Wrote {written} labeled items to {outp}")

if __name__ == "__main__":
    main()
