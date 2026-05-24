# scripts/clean_logs_to_messages.py
import json, re, sys

# patterns
MSG_Q = re.compile(r'message\s*=\s*"([^"]*)"', re.I)             # message="..."
MSG_NEST = re.compile(r'message\s*=\s*"(.*message\s*=\s*"[^"]*".*)"', re.I)
MSG_TAG = re.compile(r'<msg>(.*?)</msg>', re.I|re.S)             # <msg>...</msg>
PAYLOAD = re.compile(r'payload\s*=\s*(\{.*\})', re.I)            # payload={...}

def unwrap_nested_message(s: str) -> str:
    # repeatedly peel message="...message="inner"..." → "inner"
    prev = None
    cur = s
    for _ in range(3):  # safety
        m = MSG_NEST.search(cur)
        if not m: break
        cur = m.group(1)
        m2 = MSG_Q.search(cur)
        if m2: cur = m2.group(1)
        if cur == prev: break
        prev = cur
    m = MSG_Q.search(cur)
    return m.group(1) if m else cur

def emit_payload_lines(payload_str: str):
    try:
        obj = json.loads(payload_str)
    except Exception:
        return []
    out = []
    if v:=obj.get("name"):    out.append(f"Customer name: {v}")
    if v:=obj.get("email"):   out.append(f"User email: {v}")
    if v:=obj.get("phone"):   out.append(f"Phone: {v}")
    if v:=obj.get("ssn"):     out.append(f"my social: {v}")
    if v:=obj.get("address"): out.append(f"Customer address: {v}")
    return out

inp = sys.argv[1] if len(sys.argv)>1 else r"data\to_annotate\pool.jsonl"
out = sys.argv[2] if len(sys.argv)>2 else r"data\to_annotate\pool_norm.jsonl"

w = open(out,"w",encoding="utf-8"); kept=0
for line in open(inp,"r",encoding="utf-8"):
    obj = json.loads(line)
    t = obj.get("text","")

    # 1) payload={...}
    mp = PAYLOAD.search(t)
    if mp:
        msgs = emit_payload_lines(mp.group(1))
        for msg in msgs:
            o = {"text": msg, "meta": obj.get("meta",{})}
            w.write(json.dumps(o, ensure_ascii=False)+"\n"); kept+=1
        continue

    # 2) <msg>...</msg>
    mt = MSG_TAG.search(t)
    if mt:
        msg = mt.group(1).strip()
        w.write(json.dumps({"text": msg, "meta": obj.get("meta",{})}, ensure_ascii=False)+"\n"); kept+=1
        continue

    # 3) nested message="...message="inner"..."
    if MSG_NEST.search(t):
        msg = unwrap_nested_message(t).strip()
        w.write(json.dumps({"text": msg, "meta": obj.get("meta",{})}, ensure_ascii=False)+"\n"); kept+=1
        continue

    # 4) message="..."
    mq = MSG_Q.search(t)
    if mq:
        msg = mq.group(1).strip()
        w.write(json.dumps({"text": msg, "meta": obj.get("meta",{})}, ensure_ascii=False)+"\n"); kept+=1
        continue

    # 5) fallback: keep as-is
    w.write(json.dumps(obj, ensure_ascii=False)+"\n"); kept+=1

w.close()
print("kept:", kept, "->", out)
