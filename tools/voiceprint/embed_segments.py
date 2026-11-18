"""对手机拉回的数据逐句提取声纹，存成 npz，供后续离线分析。

用法: uv run python embed_segments.py <phone_data_dir> <model.onnx> <out.npz> [--min-dur 0.8] [--days 20261112,20261113]
只处理带真实时间戳的句子（end-start > min_dur）。音频用 ffmpeg 解码成 16k 单声道。
"""
import argparse, json, glob, os, subprocess
import numpy as np
import sherpa_onnx


def decode16k(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data"); ap.add_argument("model"); ap.add_argument("out")
    ap.add_argument("--min-dur", type=float, default=0.8)
    ap.add_argument("--days", default="", help="逗号分隔的日期前缀，如 20261112,20261113；空=全部")
    a = ap.parse_args()
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.model, num_threads=4))
    embs, meta = [], []
    for tf in sorted(glob.glob(os.path.join(a.data, "texts", "*.json"))):
        d = json.load(open(tf))
        if a.days and not any(d["name"].startswith(x) for x in a.days.split(",")):
            continue
        segs = [s for s in d.get("segments") or [] if s.get("end", 0) - s.get("start", 0) >= a.min_dur]
        opus = os.path.join(a.data, "chunks", d["name"])
        if not segs or not os.path.exists(opus):
            continue
        pcm = decode16k(opus)
        for i, s in enumerate(d["segments"]):
            st, ed = s.get("start", 0), s.get("end", 0)
            if ed - st < a.min_dur:
                continue
            clip = pcm[int(st * 16000):int(ed * 16000)]
            if len(clip) < 0.25 * 16000:
                continue
            stream = ext.create_stream()
            stream.accept_waveform(16000, clip)
            stream.input_finished()
            e = np.array(ext.compute(stream), dtype=np.float32)
            embs.append(e / (np.linalg.norm(e) + 1e-9))
            meta.append(dict(file=d["name"], engine=d.get("engine"), idx=i, start=st, end=ed,
                             text=s.get("text", ""), speaker=s.get("speaker"), db=s.get("db"),
                             snr=s.get("snr_db"), prox=s.get("proximity")))
        print(d["name"], len(embs), flush=True)
    np.savez(a.out, emb=np.stack(embs), meta=np.array([json.dumps(m, ensure_ascii=False) for m in meta]))


if __name__ == "__main__":
    main()
