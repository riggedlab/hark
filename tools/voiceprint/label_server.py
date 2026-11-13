"""本地声纹标注工具：从 embed_segments.py 的结果里抽样句子，切成小音频，浏览器里听音打标签。

用法: uv run python label_server.py <phone_data_dir> <emb.npz> <work_dir> [--n 200] [--port 8765] [--day 20261113] [--pick picks.json]
标签实时写入 <work_dir>/labels.json，可中断续标。音频只在本机，不上传任何地方。
"""
import argparse, json, os, random, subprocess, collections
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
import numpy as np

PAGE = r"""<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>声纹标注</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--bg:#fafaf8;--fg:#1d1d1f;--mut:#6e6e73;--card:#fff;--line:#e5e5ea;--acc:#0a7cff}
@media (prefers-color-scheme:dark){:root{--bg:#161618;--fg:#f2f2f2;--mut:#98989d;--card:#1f1f22;--line:#333;--acc:#3b95ff}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,"PingFang SC",sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px}
.meta{color:var(--mut);font-size:13px}.text{font-size:20px;margin:12px 0 16px}
.ctx{color:var(--mut);font-size:13px;margin:4px 0}
.btns{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-top:16px}
button{font:inherit;padding:12px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--fg);cursor:pointer;text-align:left}
button:hover{border-color:var(--acc)} kbd{font:12px ui-monospace,monospace;color:var(--mut);float:right}
input{font:inherit;width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--fg);margin-top:10px}
.bar{height:4px;background:var(--line);border-radius:2px;margin:12px 0}.bar>i{display:block;height:100%;background:var(--acc);border-radius:2px}
.done{font-size:20px;text-align:center;padding:40px}
</style></head><body><main>
<div class="meta" id="prog"></div><div class="bar"><i id="bar"></i></div>
<div class="card" id="card"></div>
<p class="meta">空格 重听 · 1 我 · 2 别人（可填名字，回车确认） · 3 外放/视频/播客 · 4 混杂/听不清 · ← 上一条</p>
</main><script>
let items=[],labels={},pos=0,lastName="";
const $=id=>document.getElementById(id);
async function init(){items=await (await fetch('items.json')).json();labels=await (await fetch('labels.json')).json();
 pos=items.findIndex(it=>!(it.id in labels));if(pos<0)pos=items.length;render();}
function render(){const n=Object.keys(labels).length;$('prog').textContent=`已标 ${n} / ${items.length}`;$('bar').style.width=(100*n/items.length)+'%';
 if(pos>=items.length){$('card').innerHTML='<div class="done">全部标完了，可以关掉页面</div>';return}
 const it=items[pos],cur=labels[it.id];
 $('card').innerHTML=`<div class="meta">${it.file} · ${it.start.toFixed(1)}s · 时长 ${it.dur.toFixed(1)}s · ${it.db??'?'} dB${cur?' · 已标：'+cur:''}</div>
 <div class="ctx">${it.prev||''}</div><div class="text">${it.text}</div><div class="ctx">${it.next||''}</div>
 <audio id="au" src="clips/${it.id}.wav" autoplay controls style="width:100%"></audio>
 <div class="btns"><button onclick="mark('me')">我<kbd>1</kbd></button><button onclick="other()">别人<kbd>2</kbd></button>
 <button onclick="mark('media')">外放 / 视频 / 播客<kbd>3</kbd></button><button onclick="mark('mixed')">混杂 / 听不清<kbd>4</kbd></button></div>
 <input id="nm" placeholder="别人的名字或代号（可空），回车确认" style="display:none">`;}
function other(){const nm=$('nm');nm.style.display='block';nm.value=lastName;nm.focus();nm.select();
 nm.onkeydown=e=>{e.stopPropagation();if(e.key==='Enter'){lastName=nm.value.trim();mark('other'+(lastName?':'+lastName:''))}if(e.key==='Escape'){nm.style.display='none'}}}
async function mark(v){const it=items[pos];labels[it.id]=v;await fetch('label',{method:'POST',body:JSON.stringify({id:it.id,label:v})});pos++;render();}
document.onkeydown=e=>{if(e.key===' '){e.preventDefault();const a=$('au');if(a){a.currentTime=0;a.play()}}
 else if(e.key==='1')mark('me');else if(e.key==='2'){e.preventDefault();other()}else if(e.key==='3')mark('media');else if(e.key==='4')mark('mixed');
 else if(e.key==='ArrowLeft'&&pos>0){pos--;render()}};
init();
</script></body></html>"""


def build(data, npz, work, n, day=None, seed=7, pick=None):
    z = np.load(npz)
    meta = [json.loads(x) for x in z["meta"]]
    rnd = random.Random(seed)
    notes = {}
    if pick:  # 外部指定要复核的句子（如 identify_me.py 识别结果），不再随机抽
        for p in json.load(open(pick)):
            notes[p["emb_index"]] = p.get("note", "")
    # 分层抽样：按时长桶均匀覆盖，短句也要有，保证评测能反映短句效果
    buckets = collections.defaultdict(list)
    for i, m in enumerate(meta):
        if day and not m["file"].startswith(day):
            continue
        d = m["end"] - m["start"]
        b = 0 if d < 1.5 else 1 if d < 2.5 else 2 if d < 4 else 3
        buckets[b].append(i)
    picked = []
    for b in sorted(buckets):
        picked += rnd.sample(buckets[b], min(len(buckets[b]), n // 4))
    if pick:
        picked = list(notes)
    picked.sort(key=lambda i: (meta[i]["file"], meta[i]["start"]))
    os.makedirs(os.path.join(work, "clips"), exist_ok=True)
    texts = {}
    items = []
    for i in picked:
        m = meta[i]
        key = m["file"]
        if key not in texts:
            texts[key] = json.load(open(os.path.join(data, "texts", key + ".json")))["segments"]
        segs = texts[key]
        cid = f"{key.replace('.opus','')}_{m['idx']}"
        out = os.path.join(work, "clips", cid + ".wav")
        if not os.path.exists(out):
            subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-ss", str(m["start"]), "-to", str(m["end"]),
                            "-i", os.path.join(data, "chunks", key), "-ac", "1", "-ar", "16000", out], check=True)
        items.append(dict(id=cid, emb_index=i, file=key, start=m["start"], dur=m["end"] - m["start"],
                          db=m["db"], text=m["text"], note=notes.get(i, ""),
                          prev=segs[m["idx"] - 1]["text"] if m["idx"] > 0 else "",
                          next=segs[m["idx"] + 1]["text"] if m["idx"] + 1 < len(segs) else ""))
    json.dump(items, open(os.path.join(work, "items.json"), "w"), ensure_ascii=False)
    lp = os.path.join(work, "labels.json")
    if not os.path.exists(lp):
        json.dump({}, open(lp, "w"))
    open(os.path.join(work, "index.html"), "w").write(PAGE)
    print(f"{len(items)} clips ready in {work}")


def serve(work, port):
    lp = os.path.join(work, "labels.json")

    class H(SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=work, **k)

        def log_message(self, *a):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            labels = json.load(open(lp))
            labels[body["id"]] = body["label"]
            json.dump(labels, open(lp, "w"), ensure_ascii=False, indent=0)
            self.send_response(204); self.end_headers()

    print(f"open http://127.0.0.1:{port}/")
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("data"); ap.add_argument("npz"); ap.add_argument("work")
    ap.add_argument("--n", type=int, default=200); ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--day", help="只抽某天的录音，如 20261113")
    ap.add_argument("--pick", help="JSON 列表 [{emb_index, note}]，指定要复核的句子")
    a = ap.parse_args()
    if not os.path.exists(os.path.join(a.work, "items.json")):
        build(a.data, a.npz, a.work, a.n, a.day, pick=a.pick)
    serve(a.work, a.port)
