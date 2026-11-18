"""按新方案识别哪些句子是「我」：增强版 CAM++ 声纹 + 录音内聚类 + 响度。

- 注册：标注里所有「我」的句子（相当于用户在 App 里点过「这是我」）。
- 打分：同一段录音里先把 >=0.8s 的句子按声音聚成组，短句挂到最像的组；
  分数 = (1-wg)*句子自身相似度 + wg*所在组相似度 + 0.01*(dB+35)，wg 随句长从 0.8 降到 0.2（与 Android Voiceprint.kt 一致）。
- 阈值：在标注上做「留一段录音」交叉验证，取别人/外放被误认成我 <= 2% 的阈值，避免背答案。
用法: uv run python identify_me.py <emb.npz> <label_dir>[,<label_dir2>...] <out.json>
"""
import sys, json, collections
import numpy as np
from sklearn.cluster import AgglomerativeClustering

LONG_S, CLUSTER_DIST, W_DB = 0.8, 0.6, 0.01


def group_weight(dur):
    """长句信自己、短句信组：2026-11-13 标注 391 条上，固定 0.7 会把同录音里的别人拉进「我」组。"""
    return float(np.clip(0.8 - 0.6 * dur / 4, 0.2, 0.8))


def load_labels(dirs):
    out = {}
    for d in dirs.split(","):
        items = {it["id"]: it for it in json.load(open(f"{d}/items.json"))}
        for k, v in json.load(open(f"{d}/labels.json")).items():
            it = items[k]
            out[(it["file"], int(k.rsplit("_", 1)[1]))] = v
    return out


def groups_by_file(E, M):
    """每段录音内聚类；返回 句子下标 -> 组成员下标列表。"""
    by_file = collections.defaultdict(list)
    for i, m in enumerate(M):
        by_file[m["file"]].append(i)
    dur = np.array([m["end"] - m["start"] for m in M])
    group_of = {}
    for f, idx in by_file.items():
        long_ = [i for i in idx if dur[i] >= LONG_S]
        short = [i for i in idx if dur[i] < LONG_S]
        if len(long_) >= 2:
            lab = AgglomerativeClustering(n_clusters=None, distance_threshold=CLUSTER_DIST, metric="cosine",
                                          linkage="average").fit(E[long_]).labels_
        else:
            lab = np.zeros(len(long_), dtype=int)
        groups = [[long_[j] for j in np.where(lab == c)[0]] for c in set(lab)] if long_ else []
        vecs = []
        for g in groups:
            v = (E[g] * dur[g, None]).sum(0)
            vecs.append(v / np.linalg.norm(v))
        for g in groups:
            for i in g:
                group_of[i] = g
        for i in short:  # 短句挂到最像的组上，组本身不因短句改变
            if vecs:
                j = int(np.argmax([E[i] @ v for v in vecs]))
                group_of[i] = groups[j]
            else:
                group_of[i] = [i]
    return group_of, dur


def score_all(E, M, group_of, dur, centroid, subset):
    out = {}
    cache = {}
    for i in subset:
        g = group_of[i]
        key = id(g)
        if key not in cache:
            v = (E[g] * dur[g, None]).sum(0)
            cache[key] = float((v / np.linalg.norm(v)) @ centroid)
        db = M[i].get("db")
        wg = group_weight(dur[i])
        out[i] = (1 - wg) * float(E[i] @ centroid) + wg * cache[key] + W_DB * ((db if db is not None else -50) + 35)
    return out


def unit(v):
    return v / np.linalg.norm(v)


def main():
    npz, label_dirs, out_path = sys.argv[1:4]
    z = np.load(npz)
    E, M = z["emb"], [json.loads(x) for x in z["meta"]]
    labels = load_labels(label_dirs)
    lab_idx = {i: labels[(m["file"], m["idx"])] for i, m in enumerate(M) if (m["file"], m["idx"]) in labels}
    lab_idx = {i: v for i, v in lab_idx.items() if v != "mixed"}
    me_idx = [i for i, v in lab_idx.items() if v == "me"]
    group_of, dur = groups_by_file(E, M)

    # 留一段录音交叉验证定阈值
    cv_s, cv_y = [], []
    for f in {M[i]["file"] for i in lab_idx}:
        enr = [i for i in me_idx if M[i]["file"] != f]
        test = [i for i in lab_idx if M[i]["file"] == f]
        sc = score_all(E, M, group_of, dur, unit(E[enr].mean(0)), test)
        for i in test:
            cv_s.append(sc[i]); cv_y.append(lab_idx[i] == "me")
    cv_s, cv_y = np.array(cv_s), np.array(cv_y)
    th = None
    for t in np.sort(cv_s):
        if ((cv_s >= t) & ~cv_y).sum() / (~cv_y).sum() <= 0.02:
            th = float(t); break
    rec = ((cv_s >= th) & cv_y).sum() / cv_y.sum()
    fa = ((cv_s >= th) & ~cv_y).sum() / (~cv_y).sum()
    print(f"交叉验证阈值 {th:.3f}：我召回 {rec:.2f}，误认 {fa:.3f}（标注 {len(cv_y)} 条，其中我 {cv_y.sum()}）")

    sc = score_all(E, M, group_of, dur, unit(E[me_idx].mean(0)), range(len(M)))
    res = []
    for i, m in enumerate(M):
        res.append(dict(emb_index=i, file=m["file"], idx=m["idx"], start=m["start"], end=m["end"], text=m["text"],
                        db=m["db"], score=round(sc[i], 3), is_me=sc[i] >= th, labeled=lab_idx.get(i)))
    json.dump(dict(threshold=th, items=res), open(out_path, "w"), ensure_ascii=False)
    c = collections.Counter((r["file"][:8], r["is_me"], r["end"] - r["start"] >= LONG_S) for r in res)
    for day in sorted({k[0] for k in c}):
        print(day, f"长句 我 {c[(day,True,True)]}/{c[(day,True,True)]+c[(day,False,True)]}",
              f"短句(<{LONG_S}s) 我 {c[(day,True,False)]}/{c[(day,True,False)]+c[(day,False,False)]}")


if __name__ == "__main__":
    main()
