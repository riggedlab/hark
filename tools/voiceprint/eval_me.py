"""评测「是不是我」：对比现网单原型和几种新打分方式。

模拟真实使用：随机挑 K 条「我」当注册样本（来自少数几段录音），
在其余录音的标注句上测，重复多次取平均。指标：别人/外放被误认成我 <= 2% 时，我的召回率。

用法: uv run python eval_me.py <emb.npz> <label_dir> [--trials 40]
"""
import argparse, collections
import numpy as np
from sklearn.cluster import AgglomerativeClustering
from evalcommon import load, fa_at, auc


def knn_score(X, bank, k=3):
    s = X @ bank.T
    k = min(k, bank.shape[0])
    return np.sort(s, axis=1)[:, -k:].mean(1)


def self_train(E, pool_idx, enroll, rounds=3, add_th=0.62, k=3, min_dur=None, durs=None):
    """从无标注历史里自动扩充「我」的样本库：只收和库里最像的几条足够接近、且够长的句子。"""
    bank = E[enroll]
    for _ in range(rounds):
        sc = knn_score(E[pool_idx], bank, k)
        ok = sc >= add_th
        if durs is not None:
            ok &= durs[pool_idx] >= 1.5
        bank = np.concatenate([E[enroll], E[pool_idx[ok]]])
    return bank


def session_smooth(E, M, idx_of_file, base_scores_all, th=0.5, alpha=0.5):
    """同一段录音内先按声音聚类，句子分数和所在簇的平均分做混合：短句借长句的光。"""
    out = dict(base_scores_all)
    for f, idx in idx_of_file.items():
        if len(idx) < 2:
            continue
        lab = AgglomerativeClustering(n_clusters=None, distance_threshold=th, metric="cosine",
                                      linkage="average").fit(E[idx]).labels_
        for c in set(lab):
            mem = [idx[j] for j in np.where(lab == c)[0]]
            w = np.array([M[i]["end"] - M[i]["start"] for i in mem])
            cm = float(np.average([base_scores_all[i] for i in mem], weights=w))
            for i in mem:
                out[i] = (1 - alpha) * base_scores_all[i] + alpha * cm
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("npz"); ap.add_argument("labels")
    ap.add_argument("--trials", type=int, default=40); ap.add_argument("--day", default="20261113")
    a = ap.parse_args()
    E, M, rows = load(a.npz, a.labels)
    durs = np.array([m["end"] - m["start"] for m in M])
    labeled = {r["i"] for r in rows}
    pool_all = np.array([i for i in range(len(M)) if i not in labeled])
    pool_day = np.array([i for i in pool_all if M[i]["file"].startswith(a.day)])
    idx_of_file = collections.defaultdict(list)
    for i, m in enumerate(M):
        if m["file"].startswith(a.day):
            idx_of_file[m["file"]].append(i)
    me = [r for r in rows if r["cls"] == "me"]
    rng = np.random.default_rng(0)
    res = collections.defaultdict(list)
    for K in (3, 5, 10):
        for t in range(a.trials):
            enr_rows = [me[j] for j in rng.choice(len(me), K, replace=False)]
            enr_files = {r["file"] for r in enr_rows}
            enroll = [r["i"] for r in enr_rows]
            test = [r for r in rows if r["file"] not in enr_files]
            y = np.array([r["cls"] == "me" for r in test])
            X = E[[r["i"] for r in test]]
            c = E[enroll].mean(0); c /= np.linalg.norm(c)
            methods = {"单原型(均值)": X @ c, "多样本 kNN": knn_score(X, E[enroll])}
            bank_d = self_train(E, pool_day, enroll, durs=durs)
            bank_a = self_train(E, pool_all, enroll, durs=durs)
            methods["自扩充库(当天)"] = knn_score(X, bank_d)
            methods["自扩充库(全部历史)"] = knn_score(X, bank_a)
            # 录音内聚类平滑：需要当天所有句子的分数
            day_idx = [i for f in idx_of_file for i in idx_of_file[f]]
            base = dict(zip(day_idx, knn_score(E[day_idx], bank_d)))
            sm = session_smooth(E, M, idx_of_file, base)
            methods["自扩充库+录音内聚类"] = np.array([sm[r["i"]] for r in test])
            for name, s in methods.items():
                _, rec = fa_at(s, y, 0.02)
                res[(K, name)].append((rec, auc(s, y), len(bank_d) if "当天" in name else len(bank_a) if "全部" in name else K))
    print(f"{'注册条数':<6}{'方法':<22}{'误认<=2%时召回':>14}{'AUC':>8}{'库大小':>8}")
    for (K, name), v in res.items():
        v = np.array(v)
        print(f"{K:<8}{name:<22}{v[:,0].mean():>14.2f}{v[:,1].mean():>8.3f}{v[:,2].mean():>8.0f}")


if __name__ == "__main__":
    main()
