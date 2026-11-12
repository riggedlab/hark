# /// script
# requires-python = ">=3.11"
# dependencies = ["bleak>=0.22", "opuslib>=3.0.1"]
# ///
"""从录音器接收 BLE Opus 音频，解码后保存成 WAV。

用法:  uv run tools/ble_receiver.py [秒数] [输出文件.wav]
"""
import asyncio, os, sys, time, wave, struct, math

# 让 opuslib 找到 Homebrew 装的 libopus
os.environ.setdefault("DYLD_FALLBACK_LIBRARY_PATH", "/opt/homebrew/lib")
import opuslib  # noqa: E402
from bleak import BleakClient, BleakScanner  # noqa: E402

SVC_UUID = "5a1d0001-7e3c-4b8a-9f00-524947000000"
AUDIO_UUID = "5a1d0002-7e3c-4b8a-9f00-524947000000"
RATE, FRAME = 16000, 320

dur = float(sys.argv[1]) if len(sys.argv) > 1 else 20
out_path = sys.argv[2] if len(sys.argv) > 2 else f"rec-{time.strftime('%Y%m%d-%H%M%S')}.wav"


async def main():
    print("请先短按板子上的用户按键，绿灯闪烁后在 60 秒内连接并完成系统配对。")
    print("注意：电脑配对成功后会顶掉手机的配对；恢复手机录音需在 App 点「重新配对」并再次按键。")
    print("扫描设备中…")
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: SVC_UUID in [u.lower() for u in ad.service_uuids]
        or ad.service_data.get(SVC_UUID) == b"\x01", timeout=15)
    if not dev:
        print("没找到设备。板子插着电吗？手机 nRF Connect 是不是还连着它？（同一时间只能连一个）")
        return
    print(f"找到: {dev.name} ({dev.address})")

    dec = opuslib.Decoder(RATE, 1)
    pcm = bytearray()
    st = {"pkts": 0, "lost": 0, "bytes": 0, "last": None}

    def on_audio(_, data: bytearray):
        seq = data[0] | (data[1] << 8)
        frame = bytes(data[2:])
        if st["last"] is not None:
            gap = (seq - st["last"] - 1) & 0xFFFF
            for _ in range(min(gap, 50)):          # 丢包用 PLC 补
                pcm.extend(dec.decode(b"", FRAME))
            st["lost"] += gap
        st["last"] = seq
        st["pkts"] += 1
        st["bytes"] += len(frame)
        pcm.extend(dec.decode(frame, FRAME))

    async with BleakClient(dev) as c:
        print(f"已连接，MTU={c.mtu_size}。订阅需要加密，macOS 可能会弹出配对提示。")
        await c.start_notify(AUDIO_UUID, on_audio)
        print(f"已加密订阅，开始录音 {dur:.0f} 秒，对着板子说话吧…")
        t0 = time.time()
        while time.time() - t0 < dur:
            await asyncio.sleep(1)
            # 最近 1 秒的音量
            tail = pcm[-RATE * 2:]
            n = len(tail) // 2
            rms = math.sqrt(sum(s * s for s in struct.unpack(f"<{n}h", tail)) / n) if n else 0
            db = 20 * math.log10((rms + 1) / 32768)
            bar = "#" * max(0, int((db + 70) / 70 * 40))
            print(f"{time.time() - t0:5.1f}s  包 {st['pkts']:5d}  丢 {st['lost']:3d}  "
                  f"{st['bytes'] / max(time.time() - t0, 1e-3) / 1000:4.1f} KB/s  {db:6.1f} dB |{bar}")
        await c.stop_notify(AUDIO_UUID)

    with wave.open(out_path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(pcm)
    total = st["pkts"] + st["lost"]
    print(f"\n已保存 {out_path}：{len(pcm) / 2 / RATE:.1f} 秒，"
          f"丢包率 {st['lost'] / max(total, 1) * 100:.2f}%")


asyncio.run(main())
