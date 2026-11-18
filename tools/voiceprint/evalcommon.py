"""评测公用：读取标注 + 某个模型的逐句声纹，统一成 (emb, meta, y) 。"""
import json, os
import numpy as np


def load(npz, label_dir):
    z = np.load(npz)
    E, M = z["emb"], [json.loads(x) for x in z["meta"]]
    key = {(m["file"], m["idx"]): i for i, m in enumerate(M)}
    items = json.load(open(os.path.join(label_dir, "items.json")))
    labels = json.load(open(os.path.join(label_dir, "labels.json")))
    rows = []
    for it in items:
        lab = labels.get(it["id"])
        if lab is None or lab == "mixed":
            continue
        idx = int(it["id"].rsplit("_", 1)[1])
        i = key.get((it["file"], idx))
        if i is None:
            continue
        cls = "me" if lab == "me" else "media" if lab == "media" else "other"
        rows.append(dict(i=i, cls=cls, who=lab, file=it["file"], dur=it["dur"]))
    return E, M, rows


def fa_at(scores, y, max_fa):
    """在「别人被认成我」比例 <= max_fa 的最低阈值下，返回 (阈值, 我的召回率)。"""
    s, y = np.asarray(scores), np.asarray(y)
    best = (None, 0.0)
    for t in sorted(set(s)):
        pred = s >= t
        fa = (pred & ~y).sum() / max(1, (~y).sum())
        if fa <= max_fa:
            return t, (pred & y).sum() / max(1, y.sum())
    return best


def auc(scores, y):
    s, y = np.asarray(scores), np.asarray(y)
    pos, neg = s[y], s[~y]
    return float((pos[:, None] > neg[None, :]).mean() + 0.5 * (pos[:, None] == neg[None, :]).mean())
