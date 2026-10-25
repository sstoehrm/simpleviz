// Record the README overview: docs/assets/overview.mp4 and .gif — a doc
// that opens a graph beside it, the graph following its file, a table
// that calculates, the doc presented as slides with a graph and a
// question.
//   bb build && node dev/record-overview.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-features.mjs: serve a workspace, drive a
// headless Chromium over CDP — real mouse and key events — inject a
// caption and a cursor dot, encode the screencast.
import fs from "node:fs";
import path from "node:path";
import { recorder, ROOT, W, H, sleep } from "./recorder.mjs";

const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const CDP_PORT = 9336, PORT = 7495;
const { ws, start, serverUrl, send, js, launch, moveTo, click, elAt, key, caption, settled,
        capture, encode, report, close } = recorder("simpleviz-overview-", { hover: true });

// ---- workspace -------------------------------------------------------------

// the examples as simpleviz demo copies them: intro.md links the rest
fs.cpSync(path.join(ROOT, "examples"), ws, { recursive: true });
const demo = path.join(ws, "demo.edn");
const edit = (file, from, to) => {
  const s = fs.readFileSync(file, "utf8");
  if (!s.includes(from)) throw new Error(`${path.basename(file)}: no ${JSON.stringify(from)}`);
  fs.writeFileSync(file, s.replace(from, to));
};

// caption pill bottom center (slides own the top), cursor dot, locators
const OVERLAY = `(() => {
  if (window.__rec) return;
  const install = () => {
    const st = document.createElement("style");
    st.textContent = \`
      #rec-cap { position: fixed; left: 50%; bottom: 22px; transform: translateX(-50%); z-index: 2147483647;
        pointer-events: none; max-width: 92vw; text-align: center;
        background: #1f2430; color: #fff; font: 600 17px/1.3 system-ui, sans-serif;
        padding: 9px 16px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,.25); transition: opacity .25s; }
      #rec-cap:empty { opacity: 0; }
      #rec-cap kbd, #rec-cap code { font: 600 15px ui-monospace, monospace; background: #3b4252; border-radius: 4px; padding: 1px 6px; }
      #rec-cur { position: fixed; left: -40px; top: -40px; width: 18px; height: 18px; margin: -9px 0 0 -9px;
        border-radius: 50%; background: rgba(59,108,240,.55); border: 2px solid #fff;
        box-shadow: 0 0 0 1px rgba(59,108,240,.9); z-index: 2147483647; pointer-events: none; transition: transform .12s; }
      #rec-cur.down { transform: scale(.65); }\`;
    document.head.append(st);
    document.body.append(Object.assign(document.createElement("div"), { id: "rec-cap" }),
                         Object.assign(document.createElement("div"), { id: "rec-cur" }));
  };
  if (document.body) install(); else document.addEventListener("DOMContentLoaded", install);
  let cx = -40, cy = -40;
  window.__rec = {
    caption(html) { document.getElementById("rec-cap").innerHTML = html; },
    jump(x, y) { cx = x; cy = y; const c = document.getElementById("rec-cur"); c.style.left = x + "px"; c.style.top = y + "px"; },
    move(x, y, ms) {
      const x0 = cx, y0 = cy, t0 = performance.now();
      return new Promise(done => {
        const step = t => {
          const u = Math.min(1, (t - t0) / ms), e = u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2;
          this.jump(x0 + (x - x0) * e, y0 + (y - y0) * e);
          u < 1 ? requestAnimationFrame(step) : done([x, y]);
        };
        requestAnimationFrame(step);
      });
    },
    press(down) { document.getElementById("rec-cur").classList.toggle("down", down); },
    async at(id) {
      const app = await import("./js/simpleviz/app.mjs"), cv = await import("./js/simpleviz/canvas.mjs");
      const st = app.state.val;
      const it = (st.scene?.items ?? []).find(i => i.id === id);
      if (!it) throw new Error("no scene item " + id);
      const v = cv.view, r = document.getElementById("canvas-wrap").getBoundingClientRect();
      const gx = it.kind === "box" ? it.x + 24 : it.x + it.w / 2;
      const gy = it.kind === "box" ? it.y + it["title-h"] / 2 : it.y + it.h / 2;
      return [r.left + v.x + gx * v.k, r.top + v.y + gy * v.k];
    },
    el(sel, text) {
      const e = [...document.querySelectorAll(sel)].find(e => !text || e.textContent.includes(text));
      if (!e) throw new Error("no element " + sel + " " + (text ?? ""));
      const r = e.getBoundingClientRect();
      return [r.left + r.width / 2, r.top + r.height / 2];
    },
  };
})()`;

// poll the page until `cond` (a JS expression) holds
const until = (cond, what, ms = 15000) => js(`(async () => {
  for (const t0 = Date.now(); Date.now() - t0 < ${ms}; await new Promise(r => setTimeout(r, 50)))
    if (${cond}) return true;
  throw new Error(${JSON.stringify("never: " + what)});
})()`);
// scroll #md-view, eased, until the first `sel` holding `text` sits a
// third of the way down
async function reveal(sel, text, ms = 1100) {
  await js(`(async () => {
    const v = document.getElementById("md-view");
    const e = [...v.querySelectorAll(${JSON.stringify(sel)})].find(e => e.textContent.includes(${JSON.stringify(text)}));
    if (!e) throw new Error("no ${sel} ${text}");
    const y0 = v.scrollTop, dy = e.getBoundingClientRect().top - v.getBoundingClientRect().top - v.clientHeight / 3, t0 = performance.now();
    await new Promise(done => { const step = t => { const u = Math.min(1, (t - t0) / ${ms});
      v.scrollTop = y0 + dy * (u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2); u < 1 ? requestAnimationFrame(step) : done(); };
      requestAnimationFrame(step); }); })()`);
}
const link = text => elAt("#md-view a.md-link", text);
const mdPage = name => until(`(await import("./js/simpleviz/app.mjs")).state.val.md?.path?.endsWith(${JSON.stringify(name)})
  && document.getElementById("md-view")`, `the md page ${name}`);
// the presented slide's side has landed: its graph laid out, or none
const slideReady = () => until(`(() => { const st = window.__app.state.val, p = st.present;
  return p && (p.dest == null || (st.side && st.scene && !st.layouting)); })()`, "a slide ready");
async function next() { await key("ArrowRight"); await sleep(250); await slideReady(); }

// ---- run -------------------------------------------------------------------

try {
  const server = start("bb", ["serve", path.join(ws, "intro.md"), "--port", String(PORT)]);
  const url = await serverUrl(server);
  await launch(CDP_PORT, OVERLAY);
  await send("Page.navigate", { url });
  await sleep(1000);
  await js(`(async () => { for (let i = 0; i < 200 && !(document.readyState === "complete" && window.ELK); i++)
    await new Promise(r => setTimeout(r, 50)); })()`);
  await js(`(async () => { window.__app = await import("./js/simpleviz/app.mjs"); })()`);
  await mdPage("intro.md");
  await sleep(500);

  const shots = capture();

  // 1 — a doc
  await js(`__rec.jump(${W / 2}, ${H / 2 + 60})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz: diagrams, docs and slides from plain markdown and EDN files");
  await sleep(2200);

  // 2 — a graph beside it
  await caption("A link to an <code>.edn</code> file opens the diagram beside the doc");
  await sleep(600);
  await click(await link("the demo graph"), 700);
  await settled();
  await js(`__rec.move(${W * 0.75}, ${H - 90}, 500)`);
  await sleep(2200);

  // 3 — the file is the source
  await caption("Edit the file in any editor: the diagram follows on save");
  await sleep(500);
  edit(demo, `:logs   {:name "Log Sink"}}`,
    `:logs   {:name "Log Sink"}\n         :search {:name "Search"   :type "service"}}`);
  edit(demo, `[:worker :logs]  {:direction :->  :name "write"}`,
    `[:worker :logs]  {:direction :->  :name "write"}\n         [:api :search]   {:direction :->  :name "index"   :type "http"}`);
  edit(demo, `#{:api :auth :storage :worker :queue}`, `#{:api :auth :storage :worker :queue :search}`);
  await until(`window.__app.state.val.scene?.items?.some(i => i.id === "n:search") && !window.__app.state.val.layouting`,
              "the Search node from the file");
  await sleep(2000);

  // 4 — inspect
  await caption("Click anything to inspect it — or edit it right in the page");
  await click("n:api", 700);
  await sleep(2200);
  await click(await elAt("#details-close"), 400);
  await sleep(300);

  // 5 — a table that calculates
  await caption("Tables calculate: a cell like <code>=(sum :b)</code> shows its result");
  await reveal("a.md-link", "the tables", 350);
  await click(await link("the tables"), 700);
  await mdPage("tables.md");
  await until(`[...document.querySelectorAll("#md-view td.calc")].some(e => e.textContent.includes("1615.00"))`,
              "the table's total");
  await js(`__rec.move(${W * 0.6}, ${H - 90}, 500)`);
  await sleep(1200);
  await reveal("h2", "Formulas in the table", 900);
  await moveTo(await elAt("#md-view td.calc", "1615.00"), 700);
  await sleep(2200);
  await caption("…formatted by a bit of Clojure, or computed over a csv file");
  await reveal("h2", "Formulas over a data file", 1400);
  await sleep(2200);

  // 6 — the doc as slides
  await caption("Any md doc is also a talk");
  await click(await elAt(".trail-crumb", "intro"), 600);
  await mdPage("intro.md");
  await sleep(400);
  await reveal("a.md-link", "the deck", 350);
  await click(await link("the deck"), 700);
  await mdPage("deck.md");
  await sleep(1200);
  await caption("<b>Present</b> shows it as slides, split at <code>---</code>");
  await click(await elAt(".md-present"), 600);
  await slideReady();
  await js(`__rec.move(${W / 2}, ${H + 40}, 500)`);
  await sleep(1600);
  await next();
  await sleep(1500);
  await caption("<code>&gt;&gt; [the demo](demo.edn)</code> puts a live graph beside a slide");
  await next();
  await sleep(1400);
  await moveTo("n:api", 700);
  await sleep(1800);

  // 7 — a question
  await caption("<code>???</code> turns a task list into a question; the answers land in a file");
  await key("End", { code: "End", windowsVirtualKeyCode: 35 });
  await sleep(300);
  await slideReady();
  await until(`document.querySelector(".ask-choice")`, "the question's choices");
  await sleep(1200);
  await click(await elAt(".ask-choice", "deck.answers.edn"), 700);
  await sleep(700);
  await click(await elAt(".ask-submit"), 600);
  await until(`document.querySelector(".ask.locked")`, "the answer locked");
  await sleep(1800);
  if (!fs.existsSync(path.join(ws, "deck.answers.edn"))) throw new Error("no deck.answers.edn after Submit");

  // 8 — outro
  await caption("Try it: <code>simpleviz demo</code> — github.com/sstoehrm/simpleviz");
  await js(`__rec.move(${W / 2}, ${H + 40}, 500)`);
  await sleep(2200);
  await send("Page.stopScreencast");
  encode(shots, { out: OUT, name: "overview" });
} catch (e) {
  report(e);
} finally {
  await close();
}
