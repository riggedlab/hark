"""生成 Android CAM++ 对齐测试资源：uv run --project tools/voiceprint python tools/voiceprint/generate_golden.py。"""
from pathlib import Path
import hashlib
import json
import subprocess

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[2]
MODEL = Path.home() / '.cache/hark/models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx'
SHA = 'aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2'
assert hashlib.sha256(MODEL.read_bytes()).hexdigest() == SHA
OUT = ROOT / 'android/app/src/test/resources/voiceprint'
OUT.mkdir(parents=True, exist_ok=True)
raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(ROOT / 'internal/asr/probe.opus'),
                      '-ac', '1', '-ar', '16000', '-f', 'f32le', '-'], check=True, capture_output=True).stdout
pcm = np.frombuffer(raw, dtype='<f4')
extractor = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(MODEL), num_threads=2))
# 全段和短句都测，防止长音频掩盖前处理的差异。
references = []
for start, end in [(0, len(pcm)), (16000, 20800)]:
    stream = extractor.create_stream()
    stream.accept_waveform(16000, pcm[start:end])
    stream.input_finished()
    emb = np.asarray(extractor.compute(stream), dtype=np.float32)
    emb /= np.linalg.norm(emb)
    references.append(dict(start_sample=start, end_sample=end, embedding=emb.tolist()))
(OUT / 'probe.f32').write_bytes(raw)
(OUT / 'reference.json').write_text(json.dumps(dict(model_sha256=SHA, sherpa_onnx=sherpa_onnx.__version__, references=references), indent=2) + '\n')
print(f'{len(pcm)} samples; {len(references)} references -> {OUT}')
