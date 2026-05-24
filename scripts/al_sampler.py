#!/usr/bin/env python3
# SecureLogX Active-Learning Sampler (model-only)
# Picks unlabeled lines where a trained NER model is uncertain, with optional digit bias.

import argparse, json, os, sys, uuid, random, re
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional
# Try to import heavy ML deps; allow the script to be parsed/run in --simulate mode
HAS_TORCH = False
try:
    import torch
    HAS_TORCH = True
except Exception:
    torch = None  # type: ignore

# heavy ML deps (imported lazily inside main unless --simulate is used)

BANNER = "SecureLogX Active-Learning Sampler (model-only)"

_MSG_RE = re.compile(r'message="([^"]+)"')

def read_lines(paths: List[str], force_format: str = "auto", min_chars: int = 10):
    """
    Yield (text, meta) from multiple files.
    - JSONL: one object per line; uses 'message'|'text'|'msg' keys if present.
    - XML: single-line parse; if <msg>..</msg> on same line, extracts inner text.
    - RAW: tries to extract message="..."; otherwise returns the whole line.
    """
    for p in paths:
        if not os.path.exists(p):
            print(f"[WARN] Missing input: {p}", file=sys.stderr)
            continue
        fmt_forced = force_format if force_format != "auto" else None
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or len(s) < min_chars:
                    continue
                meta = {"source": p}
                txt = s
                if fmt_forced == "json" or (fmt_forced is None and s.startswith("{")):
                    try:
                        obj = json.loads(s)
                        txt = str(obj.get("message") or obj.get("text") or obj.get("msg") or s)
                    except Exception:
                        txt = s
                    meta["format"] = "json"
                elif fmt_forced == "xml" or (fmt_forced is None and s.startswith("<")):
                    lo = s.lower()
                    start = lo.find("<msg>")
                    end = lo.find("</msg>")
                    if start >= 0 and end > start:
                        txt = s[start+5:end]
                    else:
                        txt = s
                    meta["format"] = "xml"
                else:
                    # RAW: try message="..."
                    m = _MSG_RE.search(s)
                    txt = m.group(1) if m else s
                    meta["format"] = "raw"
                if txt and len(txt) >= min_chars:
                    yield txt, meta

def digit_ratio(t: str) -> float:
    d = sum(ch.isdigit() for ch in t)
    return d / max(1, len(t))

def pick_indices(
    texts: List[str],
    tok,
    model,
    unc_thr: float,
    digit_thr: float,
    top_k: int,
    max_length: int,
    device: str,
    use_entropy: bool = False,
    debug: int = 0,
) -> List[Tuple[int, bool, float]]:
    """
    Return a list of (index, has_digit_tok, best_unc) where each selected line
    has at least one token with uncertainty >= unc_thr AND digit_ratio >= digit_thr.
    """
    picks: List[Tuple[int, bool, float]] = []
    model.eval()
    with torch.no_grad():
        for i, t in enumerate(texts):
            enc = tok(
                t,
                return_offsets_mapping=True,
                truncation=True,
                max_length=max_length,
                return_tensors="pt",
            )
            offsets = enc.pop("offset_mapping")
            enc = {k: v.to(device) for k, v in enc.items()}

            logits = model(**enc).logits[0]  # [T, C]
            probs = logits.softmax(dim=-1)
            if use_entropy:
                # Entropy as uncertainty (higher is more uncertain)
                ent = -(probs * (probs.clamp_min(1e-12)).log()).sum(dim=-1)
                # normalize per-sequence for stability (0..1)
                unc = (ent - ent.min()) / (ent.max() - ent.min() + 1e-12)
            else:
                maxp = probs.max(dim=-1).values  # [T]
                unc = 1.0 - maxp                  # token uncertainty

            offs = offsets[0].tolist()
            toks = [t[s:e] if (e > s) else "" for s, e in offs]

            good = False
            has_digit_tok = False
            best_unc = float(unc.max().item()) if unc.numel() else 0.0

            for (s, e), toktxt, u in zip(offs, toks, unc):
                if e <= s:
                    continue
                dr = digit_ratio(toktxt)
                if dr >= digit_thr:
                    has_digit_tok = True
                if (dr >= digit_thr) and (float(u) >= unc_thr):
                    good = True
                    break

            if debug > 0 and i < debug:
                # peek: show the token with max uncertainty (guard torch when not available)
                try:
                    j = int(torch.argmax(unc).item()) if hasattr(unc, 'numel') and unc.numel() else -1
                    peek_tok = toks[j] if 0 <= j < len(toks) else ""
                except Exception:
                    peek_tok = ""
                print(f"[DBG] line#{i} best_unc={best_unc:.3f} tok='{peek_tok}' dig={digit_ratio(peek_tok):.2f}")

            if good:
                picks.append((i, has_digit_tok, best_unc))
                if len(picks) >= top_k:
                    break
    return picks

def pick_indices_unc_only(
    texts: List[str],
    tok,
    model,
    unc_thr: float,
    top_k: int,
    max_length: int,
    device: str,
    use_entropy: bool = False,
) -> List[Tuple[int, bool, float]]:
    """
    Fallback: select by uncertainty only (ignore digit threshold).
    Returns (index, has_digit_tok, best_unc); has_digit_tok=True if ANY token in the line has digits.
    """
    picks: List[Tuple[int, bool, float]] = []
    model.eval()
    # expects model/tok and torch to be usable; call from main where torch exists
    with torch.no_grad():
        for i, t in enumerate(texts):
            enc = tok(
                t,
                return_offsets_mapping=True,
                truncation=True,
                max_length=max_length,
                return_tensors="pt",
            )
            offsets = enc.pop("offset_mapping")
            enc = {k: v.to(device) for k, v in enc.items()}
            logits = model(**enc).logits[0]
            probs = logits.softmax(dim=-1)
            if use_entropy:
                ent = -(probs * (probs.clamp_min(1e-12)).log()).sum(dim=-1)
                unc = (ent - ent.min()) / (ent.max() - ent.min() + 1e-12)
            else:
                maxp = probs.max(dim=-1).values
                unc = 1.0 - maxp

            offs = offsets[0].tolist()
            toks = [t[s:e] if (e > s) else "" for s, e in offs]

            best_unc = float(unc.max().item()) if unc.numel() else 0.0
            if best_unc >= unc_thr:
                # flag TRUE if there's ANY digit token in the line
                has_digit_any = any(digit_ratio(toktxt) > 0.0 for toktxt in toks if toktxt)
                picks.append((i, has_digit_any, best_unc))
                if len(picks) >= top_k:
                    break
    return picks

def simulate_pick_indices(
    texts: List[str],
    unc_thr: float,
    digit_thr: float,
    top_k: int,
    seed: int = 42,
) -> List[Tuple[int, bool, float]]:
    """Simple deterministic simulation of pick_indices that doesn't require ML deps.
    Uses per-line pseudo-uncertainty (rng based on seed) and whitespace tokenization.
    """
    rng = random.Random(seed)
    items: List[Tuple[int, bool, float]] = []
    for i, t in enumerate(texts):
        # pseudo 'best_unc' reproducible per-line: mix seed + line index so ordering is stable
        rng.seed(seed + i)
        best_unc = rng.random()
        toks = re.findall(r"\S+", t)
        has_digit_any = any(digit_ratio(tok) > 0.0 for tok in toks)
        good = False
        if best_unc >= unc_thr:
            # require at least one token with digit_ratio >= digit_thr
            for tok in toks:
                if digit_ratio(tok) >= digit_thr and best_unc >= unc_thr:
                    good = True
                    break
        if good:
            items.append((i, has_digit_any, best_unc))
    # sort by best_unc descending and take top_k
    items.sort(key=lambda x: x[2], reverse=True)
    return items[:top_k]

def simulate_pick_indices_unc_only(
    texts: List[str],
    unc_thr: float,
    top_k: int,
    seed: int = 42,
) -> List[Tuple[int, bool, float]]:
    rng = random.Random(seed)
    items: List[Tuple[int, bool, float]] = []
    for i, t in enumerate(texts):
        rng.seed(seed + i)
        best_unc = rng.random()
        if best_unc >= unc_thr:
            toks = re.findall(r"\S+", t)
            has_digit_any = any(digit_ratio(tok) > 0.0 for tok in toks)
            items.append((i, has_digit_any, best_unc))
    items.sort(key=lambda x: x[2], reverse=True)
    return items[:top_k]

def dedupe_texts(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    seen = set(); out = []
    for it in items:
        t = it["text"]
        if t not in seen:
            out.append(it); seen.add(t)
    return out

def main():
    ap = argparse.ArgumentParser(description=BANNER)
    ap.add_argument("-i", "--input", required=True, nargs="+", help="Input files (raw/JSONL/XML, one record per line).")
    ap.add_argument("-o", "--output", required=True, help="Output JSONL for annotation.")
    ap.add_argument("--model-dir", required=True, help="HF fine-tuned model dir (not ONNX).")
    ap.add_argument("--limit", type=int, default=500, help="Max number of samples to output.")
    ap.add_argument("--unc", type=float, default=0.35, help="Token uncertainty threshold (0..1).")
    ap.add_argument("--digit", type=float, default=0.5, help="Digit ratio threshold (0..1).")
    ap.add_argument("--min-chars", type=int, default=10, help="Skip very short lines.")
    ap.add_argument("--format", choices=["auto","raw","json","xml"], default="auto", help="Force input format for all lines.")
    ap.add_argument("--dedupe", action="store_true", help="Dedupe by exact text.")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--max-length", type=int, default=256, help="Tokenizer max_length (default 256).")
    ap.add_argument("--local-only", action="store_true", help="Load model/tokenizer without hitting HF Hub.")
    ap.add_argument("--fallback-unc-only", action="store_true", help="If no picks, retry with uncertainty-only.")
    ap.add_argument("--use-entropy", action="store_true", help="Use entropy as uncertainty instead of 1-maxP.")
    ap.add_argument("--debug", type=int, default=0, help="Print debug info for first N lines.")
    ap.add_argument("--simulate", action="store_true", help="Run in simulation mode without torch/transformers.")
    args = ap.parse_args()

    random.seed(args.seed)

    # Resolve & validate paths early
    model_dir = Path(args.model_dir).resolve()
    print(f"[INFO] Using model dir: {model_dir}")
    if not (model_dir / "config.json").exists():
        print(f"[ERROR] config.json not found in: {model_dir}", file=sys.stderr)
        sys.exit(1)

    missing_inputs = [p for p in args.input if not Path(p).exists()]
    if missing_inputs:
        print("[WARN] Missing inputs:\n  " + "\n  ".join(missing_inputs), file=sys.stderr)

    # load model/tokenizer
    if args.simulate:
        # no heavy deps; we will use simulated pickers below
        tok = None
        model = None
        device = "cpu"
    else:
        # import transformers lazily so file can be parsed without the package
        try:
            from transformers import AutoTokenizer as _AutoTokenizer, AutoModelForTokenClassification as _AutoModel
        except Exception as e:
            print(f"[ERROR] transformers import failed: {e}", file=sys.stderr)
            return 1
        load_kwargs = {"use_fast": True}
        if args.local_only:
            load_kwargs["local_files_only"] = True
        tok = _AutoTokenizer.from_pretrained(model_dir.as_posix(), **load_kwargs)
        model = _AutoModel.from_pretrained(model_dir.as_posix(), local_files_only=args.local_only)

        device = "cuda" if (HAS_TORCH and torch.cuda.is_available()) else "cpu"
        if HAS_TORCH:
            model.to(device)
        else:
            print("[WARN] torch not available; cannot run real model. Use --simulate to run without ML deps.", file=sys.stderr)
            return 1

    # read inputs
    texts, metas = [], []
    count_by_file: Dict[str, int] = {}
    for p in args.input:
        cnt = 0
        for txt, meta in read_lines([p], force_format=args.format, min_chars=args.min_chars):
            texts.append(txt); metas.append(meta); cnt += 1
        count_by_file[p] = cnt

    if not texts:
        print("[INFO] No lines read. Check paths/format/encoding.", file=sys.stderr)
        print(f"[INFO] Inputs: {args.input}", file=sys.stderr)
        return 0

    # pick indices with digit + uncertainty gate
    if args.simulate:
        idxs = simulate_pick_indices(texts, unc_thr=args.unc, digit_thr=args.digit, top_k=args.limit, seed=args.seed)
    else:
        idxs = pick_indices(
            texts, tok, model,
            unc_thr=args.unc, digit_thr=args.digit, top_k=args.limit,
            max_length=args.max_length, device=device,
            use_entropy=args.use_entropy, debug=args.debug
        )

    # fallback / top-up: uncertainty-only
    # If --fallback-unc-only is provided, use uncertainty-only picks either as a
    # full fallback (when idxs is empty) or as a top-up when idxs < --limit.
    if args.fallback_unc_only:
        if not idxs:
            print("[INFO] No picks under AND gate; retrying with uncertainty-only …")
            if args.simulate:
                idxs = simulate_pick_indices_unc_only(texts, unc_thr=args.unc, top_k=args.limit, seed=args.seed)
            else:
                idxs = pick_indices_unc_only(
                    texts, tok, model,
                    unc_thr=args.unc, top_k=args.limit,
                    max_length=args.max_length, device=device,
                    use_entropy=args.use_entropy
                )
        else:
            if len(idxs) < args.limit:
                print(f"[INFO] Top-up: have {len(idxs)} picks, target {args.limit}; adding uncertainty-only picks …")
                if args.simulate:
                    extra = simulate_pick_indices_unc_only(texts, unc_thr=args.unc, top_k=args.limit, seed=args.seed)
                else:
                    extra = pick_indices_unc_only(
                        texts, tok, model,
                        unc_thr=args.unc, top_k=args.limit,
                        max_length=args.max_length, device=device,
                        use_entropy=args.use_entropy
                    )
                existing = set(i for i, _, _ in idxs)
                for i, has_digit, best_unc in extra:
                    if i not in existing:
                        idxs.append((i, has_digit, best_unc))
                        existing.add(i)
                    if len(idxs) >= args.limit:
                        break

    # build pool
    pool = []
    for i, has_digit, best_unc in idxs:
        pool.append({
            "id": str(uuid.uuid4()),
            "text": texts[i],
            "meta": metas[i],
            "signals": {
                "uncertainty>=thr": True,
                "digit_ratio>=thr": bool(has_digit),
                "best_unc": round(float(best_unc), 6)
            }
        })

    if args.dedupe and pool:
        before = len(pool)
        pool = dedupe_texts(pool)
        print(f"[INFO] Deduped: {before} → {len(pool)}")

    # ensure dir & write JSONL
    outp = Path(args.output)
    outp.parent.mkdir(parents=True, exist_ok=True)
    with outp.open("w", encoding="utf-8") as w:
        for item in pool:
            w.write(json.dumps(item, ensure_ascii=False) + "\n")

    # stats
    print(f"[INFO] {BANNER}")
    for p, cnt in count_by_file.items():
        print(f"[INFO] File: {p} | usable lines: {cnt}")
    print(f"[INFO] Selected for annotation: {len(pool)} / {len(texts)}")
    print(f"[INFO] Output written: {args.output}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
