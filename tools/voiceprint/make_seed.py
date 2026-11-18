"""把标注转成手机端种子文件 voice_seed.json：{"self":[{file,start,end}], "<人名>":[...]}。

用法: python3 make_seed.py <label_dir>[,<label_dir2>...] <out.json>
只收「我」和有名字的别人；外放、混杂、无名的别人不进种子。
"""
import json, sys, collections

out = collections.defaultdict(list)
seen = set()
for d in sys.argv[1].split(","):
    items = {it["id"]: it for it in json.load(open(f"{d}/items.json"))}
    for k, v in json.load(open(f"{d}/labels.json")).items():
        it = items[k]
        key = (it["file"], round(it["start"], 2))
        if key in seen:
            continue
        seen.add(key)
        who = "self" if v == "me" else v.split(":", 1)[1] if v.startswith("other:") else None
        if who:
            out[who].append(dict(file=it["file"], start=it["start"], end=it["start"] + it["dur"]))
json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False)
print({k: len(v) for k, v in out.items()})
