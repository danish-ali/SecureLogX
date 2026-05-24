import json, re, sys
msg_re = re.compile(r'message\s*=\s*(?:"([^"]*)"|\'([^\']*)\'|([^\s].*))', re.I)
# simple PII hints for fallback
re_email = re.compile(r'@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
re_phone = re.compile(r'\(\d{3}\)\s?\d{3}-\d{4}|\d{3}[-\s]\d{3}[-\s]\d{4}')
re_ssn   = re.compile(r'\b\d{3}-\d{2}-\d{4}\b')

inp = r"data\to_annotate\pool.jsonl"
out = r"data\to_annotate\pool_clean.jsonl"
kept = 0
with open(out, "w", encoding="utf-8") as w:
  for line in open(inp, "r", encoding="utf-8"):
    obj = json.loads(line)
    t = obj.get("text","")
    m = msg_re.search(t)
    if m:
      val = next((g for g in m.groups() if g), "").strip()
      if val:  # non-empty message=...
        obj["text"] = val
        w.write(json.dumps(obj, ensure_ascii=False) + "\n")
        kept += 1
        continue
    # fallback: keep if whole line looks PII-ish
    if re_email.search(t) or re_phone.search(t) or re_ssn.search(t):
      w.write(json.dumps(obj, ensure_ascii=False) + "\n")
      kept += 1
print("kept:", kept)