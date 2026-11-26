"""
向量语义地图生成器（开发/调参工具）

用途：
    把 pgvector 里的 chunk 向量降到 2 维，画成一张可交互的 HTML，
    用来肉眼验证「切分质量」和「检索排序」是否合理。

    改完切分策略、换了 embedding 模型、加了 rerank 之后，
    用这张图能直接看出效果变化，而不是盲猜。

前置：
    1. 数据库有数据
    2. embedding 服务在跑（默认 http://127.0.0.1:8001）

用法：
    # ① 从数据库导出（在项目根目录执行）
    docker exec pgvector psql -U postgres -d ragdb -c \
      "COPY (SELECT id, doc_id, seq, coalesce(section,'') AS section, start_page, end_page, \
       replace(replace(content, chr(10), ' '), chr(13), ' ') AS content, embedding::text AS emb \
       FROM chunks ORDER BY doc_id, seq) TO STDOUT WITH (FORMAT csv, HEADER true)" > target/chunks-export.csv

    # ② 生成 HTML
    python scripts/build_vector_map.py --csv target/chunks-export.csv --out target/vector-map.html

    # ③ 用浏览器打开 target/vector-map.html
"""

import argparse
import csv
import json
import urllib.request

import numpy as np
from sklearn.manifold import TSNE

PRESET_QUERIES = [
    "J1轴的最大动作范围是多少",
    "更换润滑脂的周期是多久",
    "控制柜的安装尺寸要求",
    "常见故障有哪些",
    "安全注意事项",
]


def load_chunks(csv_path):
    rows = []
    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            emb = np.fromstring(r["emb"].strip()[1:-1], sep=",", dtype=np.float32)
            if emb.size == 0:
                continue
            rows.append({
                "id": r["id"],
                "doc": r["doc_id"],
                "seq": int(r["seq"]),
                "section": r["section"],
                "page": f'{r["start_page"]}-{r["end_page"]}',
                "content": r["content"],
                "vec": emb,
            })
    return rows


def embed_query(text, url):
    req = urllib.request.Request(
        url,
        data=json.dumps({"model": "bge-m3", "input": [text]}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read())
    return np.array(data["data"][0]["embedding"], dtype=np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="target/chunks-export.csv")
    ap.add_argument("--out", default="target/vector-map.html")
    ap.add_argument("--embed-url", default="http://127.0.0.1:8001/v1/embeddings")
    args = ap.parse_args()

    chunks = load_chunks(args.csv)
    n = len(chunks)
    print(f"载入 {n} 个 chunk，维度 {chunks[0]['vec'].shape[0]}")

    mat = np.vstack([c["vec"] for c in chunks])
    mat = mat / np.clip(np.linalg.norm(mat, axis=1, keepdims=True), 1e-8, None)

    sims = mat @ mat.T
    np.fill_diagonal(sims, -1)
    neighbors = []
    for i in range(n):
        idx = np.argsort(-sims[i])[:10]
        neighbors.append([{"i": int(j), "s": round(float(sims[i, j]), 4)} for j in idx])
    print("近邻计算完成")

    print("t-SNE 降维中...")
    perp = min(20, max(5, n // 8))
    xy = TSNE(n_components=2, perplexity=perp, init="pca",
              random_state=42, learning_rate="auto").fit_transform(mat)
    xy = (xy - xy.min(axis=0)) / (xy.max(axis=0) - xy.min(axis=0))

    docs = sorted({c["doc"] for c in chunks})
    doc_index = {d: i for i, d in enumerate(docs)}

    points = [{
        "i": i,
        "x": round(float(xy[i, 0]), 5),
        "y": round(float(xy[i, 1]), 5),
        "d": doc_index[c["doc"]],
        "doc": c["doc"],
        "sec": c["section"],
        "page": c["page"],
        "text": c["content"][:400],
    } for i, c in enumerate(chunks)]

    queries = []
    for q in PRESET_QUERIES:
        try:
            qv = embed_query(q, args.embed_url)
            qv = qv / max(np.linalg.norm(qv), 1e-8)
            score = mat @ qv
            order = np.argsort(-score)[:5]
            queries.append({
                "q": q,
                "hits": [{"i": int(j), "s": round(float(score[j]), 4)} for j in order],
            })
            print(f"  检索预计算: {q[:20]} → top1 {score[order[0]]:.4f}")
        except Exception as e:
            print(f"  检索预计算失败 {q}: {e}")

    data = {"docs": docs, "points": points, "neighbors": neighbors, "queries": queries}
    html = build_html(json.dumps(data, ensure_ascii=False))
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"\n生成完成: {args.out}")


TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>bge-m3 向量语义地图</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; background:#0f1420; color:#d8dee9;
         font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  #app { display:flex; height:100vh; }
  #left { flex:1; position:relative; min-width:0; }
  #right { width:380px; border-left:1px solid #232b3d; padding:16px;
           overflow-y:auto; background:#131a28; }
  h1 { font-size:15px; margin:0 0 4px; color:#8ab4f8; font-weight:600; }
  .sub { font-size:12px; color:#6b7280; margin-bottom:12px; }
  #map { width:100%; height:100%; display:block; cursor:crosshair; }
  .pt { transition: r .12s, opacity .12s; }
  .pt:hover { r: 8; }
  .legend { position:absolute; left:14px; top:14px; font-size:12px;
            background:rgba(19,26,40,.85); padding:10px 12px; border-radius:8px;
            border:1px solid #232b3d; }
  .legend div { display:flex; align-items:center; gap:6px; margin:3px 0; }
  .dot { width:9px; height:9px; border-radius:50%; }
  .qbtn { display:block; width:100%; text-align:left; margin:4px 0; padding:7px 10px;
          background:#1b2436; color:#c7d0e0; border:1px solid #2a3448;
          border-radius:6px; cursor:pointer; font-size:12px; }
  .qbtn:hover { background:#22304a; border-color:#3a4a68; }
  .qbtn.active { background:#1e3a5f; border-color:#4a7ab8; color:#e8f0ff; }
  .card { background:#1b2436; border:1px solid #2a3448; border-radius:8px;
          padding:10px 12px; margin:8px 0; font-size:12px; line-height:1.6; }
  .meta { color:#7c8aa5; font-size:11px; margin-bottom:4px; }
  .score { color:#4ade80; font-weight:600; }
  .hit { cursor:pointer; padding:5px 8px; border-radius:5px; margin:3px 0;
         background:#161e2d; border:1px solid #232b3d; }
  .hit:hover { background:#1e2a3f; }
  .hit .bar { height:4px; background:#2a3448; border-radius:2px; margin-top:4px; }
  .hit .fill { height:100%; background:#4a7ab8; border-radius:2px; }
  .sect { color:#8ab4f8; }
</style>
</head>
<body>
<div id="app">
  <div id="left">
    <svg id="map"></svg>
    <div class="legend" id="legend"></div>
  </div>
  <aside id="right">
    <h1>向量语义地图</h1>
    <div class="sub">1024 维 → t-SNE 降到 2 维 ｜ 悬停看内容，点击看近邻</div>
    <div id="queryBox"></div>
    <div id="queryResult"></div>
    <div id="detail"></div>
  </aside>
</div>
<script>
const DATA = __DATA__;
const COLORS = ["#4a7ab8", "#d97757", "#56a56b", "#b06bb0", "#c9a227"];
const svg = document.getElementById("map");
const panel = document.getElementById("detail");
const qres = document.getElementById("queryResult");

document.getElementById("legend").innerHTML = DATA.docs.map((d, i) =>
  `<div><span class="dot" style="background:${COLORS[i % COLORS.length]}"></span>${d}</div>`).join("");

function draw() {
  const w = svg.clientWidth, h = svg.clientHeight, pad = 40;
  svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
  let inner = `<line x1="${pad}" y1="${h-pad}" x2="${w-pad}" y2="${h-pad}" stroke="#232b3d"/>`;
  inner += `<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${h-pad}" stroke="#232b3d"/>`;
  DATA.points.forEach(p => {
    p._cx = pad + p.x * (w - 2*pad);
    p._cy = pad + (1 - p.y) * (h - 2*pad);
    inner += `<circle class="pt" data-i="${p.i}" cx="${p._cx}" cy="${p._cy}" r="5"
      fill="${COLORS[p.d % COLORS.length]}" fill-opacity="0.75"
      stroke="#0f1420" stroke-width="1"></circle>`;
  });
  inner += `<g id="links"></g>`;
  svg.innerHTML = inner;
  svg.querySelectorAll(".pt").forEach(el => {
    el.addEventListener("mouseenter", e => showTip(e, +el.dataset.i));
    el.addEventListener("mouseleave", hideTip);
    el.addEventListener("click", () => select(+el.dataset.i));
  });
}

let tip;
function showTip(e, i) {
  const p = DATA.points[i];
  if (!tip) {
    tip = document.createElement("div");
    tip.style.cssText = "position:fixed;max-width:380px;background:#1b2436;border:1px solid #3a4a68;"
      + "border-radius:8px;padding:10px;font-size:12px;line-height:1.6;pointer-events:none;z-index:99;"
      + "box-shadow:0 8px 24px rgba(0,0,0,.5)";
    document.body.appendChild(tip);
  }
  tip.innerHTML = `<div class="meta">${p.doc} ｜ P${p.page}</div>
    <div class="sect">${p.sec || "(无章节)"}</div>
    <div style="margin-top:6px;color:#aeb8cc">${p.text.slice(0, 200)}…</div>`;
  tip.style.left = Math.min(e.clientX + 16, innerWidth - 400) + "px";
  tip.style.top = Math.min(e.clientY + 16, innerHeight - 220) + "px";
  tip.style.display = "block";
}
function hideTip() { if (tip) tip.style.display = "none"; }

function select(i) {
  const p = DATA.points[i];
  document.getElementById("links").innerHTML = DATA.neighbors[i].map(nb => {
    const q = DATA.points[nb.i];
    return `<line x1="${p._cx}" y1="${p._cy}" x2="${q._cx}" y2="${q._cy}"
      stroke="#4ade8033" stroke-width="1.2"/>`;
  }).join("");

  let html = `<div class="card">
    <div class="meta">${p.doc} ｜ P${p.page}</div>
    <div class="sect" style="margin-bottom:6px">${p.sec || "(无章节)"}</div>
    <div style="color:#c7d0e0">${p.text}…</div>
  </div><div class="meta" style="margin-top:10px">最相似的 10 个 chunk：</div>`;
  DATA.neighbors[i].forEach(nb => {
    const q = DATA.points[nb.i];
    html += `<div class="hit" data-j="${nb.i}">
      <div><span class="score">${nb.s.toFixed(3)}</span> &nbsp;${(q.sec || q.doc).slice(0,28)}</div>
      <div class="meta">${q.doc.slice(0,10)}… P${q.page}</div></div>`;
  });
  panel.innerHTML = html;
  panel.querySelectorAll(".hit").forEach(el =>
    el.addEventListener("click", () => select(+el.dataset.j)));
}

function runQuery(k) {
  const q = DATA.queries[k];
  document.querySelectorAll(".qbtn").forEach((b, i) => b.classList.toggle("active", i === k));
  let html = `<div class="meta" style="margin-top:12px">检索结果（余弦相似度）</div>`;
  q.hits.forEach((h, r) => {
    const p = DATA.points[h.i];
    const pct = Math.max(0, Math.min(100, (h.s - 0.3) / 0.5 * 100));
    html += `<div class="hit" data-j="${h.i}">
      <div>#${r+1} <span class="score">${h.s.toFixed(3)}</span>
        &nbsp;${(p.sec || p.doc).slice(0,26)}</div>
      <div class="meta">${p.doc.slice(0,10)}… P${p.page}</div>
      <div class="bar"><div class="fill" style="width:${pct}%"></div></div></div>`;
  });
  qres.innerHTML = html;
  qres.querySelectorAll(".hit").forEach(el =>
    el.addEventListener("click", () => select(+el.dataset.j)));
}

document.getElementById("queryBox").innerHTML =
  `<div class="meta" style="margin-top:12px">试试这些问题：</div>` +
  DATA.queries.map((q, i) => `<button class="qbtn" data-k="${i}">${q.q}</button>`).join("");
document.querySelectorAll(".qbtn").forEach(b =>
  b.addEventListener("click", () => runQuery(+b.dataset.k)));

draw();
addEventListener("resize", draw);
</script>
</body>
</html>"""


def build_html(data_json):
    return TEMPLATE.replace("__DATA__", data_json)


if __name__ == "__main__":
    main()
