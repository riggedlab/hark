"""验收：用手机上重算后的转写，对照人工标注算「我」的召回和误认。

用法: python3 check_phone.py <phone_data_dir> <label_dir>[,<label_dir2>...] [--exclude-seed seed.json]
手机端「我」的判定：speaker 解析到「我」的身份（名册名字为「我」或 self 身份）。
注意：种子样本本身也在标注里，--exclude-seed 时把种子里的句子排除，只看没喂给手机的句子。
"""
import json, sys, os, collections, argparse

ap = argparse.ArgumentParser()
ap.add_argument("data"); ap.add_argument("labels"); ap.add_argument("--exclude-seed")
ap.add_argument("--self-ids", default="self", help="逗号分隔，被视为「我」的 speaker id")
a = ap.parse_args()
phone = {}
p = os.path.join(a.data, "speakers_phone.json")
if os.path.exists(p):
    phone = json.load(open(p)).get("items", {})
self_ids = set(a.self_ids.split(",")) | {k for k, v in phone.items() if v.get("name") == "我"}
seed = set()
if a.exclude_seed:
    for lst in json.load(open(a.exclude_seed)).values():
        seed |= {(x["file"], round(x["start"], 2)) for x in lst}
t = collections.Counter()
for d in a.labels.split(","):
    items = {it["id"]: it for it in json.load(open(f"{d}/items.json"))}
    for k, v in json.load(open(f"{d}/labels.json")).items():
        if v == "mixed":
            continue
        it = items[k]
        if (it["file"], round(it["start"], 2)) in seed:
            continue
        tr = json.load(open(os.path.join(a.data, "texts", it["file"] + ".json")))
        seg = next((s for s in tr["segments"] if abs(s["start"] - it["start"]) < 0.02), None)
        if seg is None:
            t[("missing",)] += 1
            continue
        sp = seg.get("speaker") or ""
        pred = sp in self_ids
        t[("me" if v == "me" else "notme", pred)] += 1
me_n = t[("me", True)] + t[("me", False)]
no_n = t[("notme", True)] + t[("notme", False)]
print(f"我召回 {t[('me', True)]}/{me_n} = {t[('me', True)]/max(1,me_n):.2f}；误认成我 {t[('notme', True)]}/{no_n}；对不上的句子 {t[('missing',)]}")
