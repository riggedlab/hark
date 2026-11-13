# 声纹离线实验

用手机真实录音评估声纹方案。数据和标注只在本机，不提交。

1. `pull_phone_data.sh <dir>`：拉手机私有数据（需 debuggable 构建）。
2. `uv run python embed_segments.py <dir> <model.onnx> <out.npz> [--days ...]`：逐句提声纹。
3. `uv run python label_server.py <dir> <npz> <work> [--day | --pick]`：浏览器里听音标注。
4. `uv run python eval_me.py` / `identify_me.py`：对比方案、按新方案出结果（参数与 Android Voiceprint.kt 一致）。
5. `make_seed.py`：标注转手机种子 `voice_seed.json`；`check_phone.py`：用手机重算结果对照标注验收。
6. `generate_golden.py`：生成 Kotlin 与 sherpa-onnx 声纹对齐测试的参考数据。
