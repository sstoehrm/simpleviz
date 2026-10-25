// Record the "what's new in 0.42" video: docs/assets/whats-new-0.42.mp4
// and .gif — manual layout (✥, dragging on the grid, auto) and the md
// editor's vi mode from 0.41.
//   bb build && node dev/record-whats-new-0.42.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-features.mjs: serve a workspace, drive a
// headless Chromium over CDP — real mouse and key events — inject a
// caption and a cursor dot, encode the screencast.
import fs from "node:fs";
import path from "node:path";
import { recorder, ROOT, W, H, sleep } from "./recorder.mjs";

const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const CDP_PORT = 9335, PORT = 7494;
const { ws, start, serve, waitFor, send, on, js, launch, moveTo, click, elAt, key, caption, settled,
        capture, encode, report, close } = recorder("simpleviz-042-", { hover: true });

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.mkdirSync(path.join(ws, "docs"));
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
const demo = path.join(ws, "demo.edn");
fs.writeFileSync(demo, fs.readFileSync(path.join(ROOT, "examples/demo.edn"), "utf8")
  .replace(`:auth   {:name "Auth"     :type "service"}`, `:auth   {:name "Auth"     :type "service" :md-ref "docs/auth.md"}`));
if (!fs.readFileSync(demo, "utf8").includes(":md-ref")) throw new Error("demo.edn: no Auth node to give an :md-ref");
fs.writeFileSync(path.join(ws, "docs/auth.md"),
  "# Auth\n\nIssues bearer tokens for the Web UI.\nCalled by the API on every request.\n");

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

// the screen point of a scene item's middle (a box's too, not its title)
const middleOf = id => js(`(async () => {
  const app = await import("./js/simpleviz/app.mjs"), cv = await import("./js/simpleviz/canvas.mjs");
  const it = app.state.val.scene.items.find(i => i.id === ${JSON.stringify(id)});
  if (!it) throw new Error("no scene item ${id}");
  const v = cv.view, r = document.getElementById("canvas-wrap").getBoundingClientRect();
  return [r.left + v.x + (it.x + it.w / 2) * v.k, r.top + v.y + (it.y + it.h / 2) * v.k]; })()`);
// a real drag: press, move in steps (the dot along), let go
async function drag(from, dx, dy, ms = 1300) {
  const [x, y] = await moveTo(from, 600);
  await sleep(150);
  await js("__rec.press(true)");
  await send("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", buttons: 1, clickCount: 1 });
  const n = Math.round(ms / 30);
  for (let i = 1; i <= n; i++) {
    const u = i / n, e = u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2;
    const px = x + dx * e, py = y + dy * e;
    await js(`__rec.jump(${px}, ${py})`);
    await send("Input.dispatchMouseEvent", { type: "mouseMoved", x: px, y: py, button: "left", buttons: 1 });
    await sleep(30);
  }
  await send("Input.dispatchMouseEvent", { type: "mouseReleased", x: x + dx, y: y + dy, button: "left", buttons: 0, clickCount: 1 });
  await js("__rec.press(false)");
  await sleep(500);
  await settled();
}
// keys as typed: a char inserts text where a text field takes it
async function type(keys, gap = 140) {
  for (const k of keys) {
    const named = { Escape: 27, Enter: 13 }[k];
    await send("Input.dispatchKeyEvent", named
      ? { type: "keyDown", key: k, code: k, windowsVirtualKeyCode: named }
      : { type: "keyDown", key: k, text: k, unmodifiedText: k });
    await send("Input.dispatchKeyEvent", { type: "keyUp", key: k });
    await sleep(gap);
  }
}
async function ctrl(k) {
  for (const type of ["keyDown", "keyUp"])
    await send("Input.dispatchKeyEvent", { type, key: k, code: "Key" + k.toUpperCase(), modifiers: 2,
                                           windowsVirtualKeyCode: k.toUpperCase().charCodeAt(0) });
}
const mdText = () => js(`document.getElementById("md-text")?.value ?? null`);

// ---- run -------------------------------------------------------------------

try {
  const url = await serve([demo, "--port", String(PORT)]);
  await launch(CDP_PORT, OVERLAY);
  // auto asks before it drops the positions: yes
  on("Page.javascriptDialogOpening", () => send("Page.handleJavaScriptDialog", { accept: true }));
  await send("Page.navigate", { url });
  // the page's own scripts first: importing the app before ELK is there fails
  await sleep(1000);
  await js(`(async () => { for (let i = 0; i < 200 && !(document.readyState === "complete" && window.ELK); i++)
    await new Promise(r => setTimeout(r, 50)); })()`);
  await settled();
  await sleep(800);

  const shots = capture();

  // 1 — the graph, laid out automatically
  await js(`__rec.jump(${W / 2}, ${H - 120})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz 0.42: lay a graph out by hand");
  await sleep(2200);

  // 2 — ✥
  await caption("<b>✥</b> turns manual layout on: each node and box gets a <code>:pos</code> on a 20 px grid");
  await click(await elAt("#arrange-btn"), 700);
  await js(`(async () => { const app = await import("./js/simpleviz/app.mjs");
    for (let i = 0; i < 100 && !(app.state.val.graph?.nodes?.web?.pos); i++) await new Promise(r => setTimeout(r, 50)); })()`);
  await settled();
  await sleep(2400);

  // 3 — drag nodes
  await caption("Drag a node — it snaps cell by cell; letting go writes its <code>:pos</code> to the file");
  await drag("n:web", 0, 170);
  await sleep(700);
  await drag("n:mail", 90, 170);
  await sleep(1200);

  // 4 — drag a box
  await caption("Drag a box by any part of it — what is inside comes along");
  await drag(await middleOf("b:backend"), 100, 60, 1500);
  await sleep(1400);
  await caption("Edges between placed elements run straight; inside a box they are routed as before");
  await js(`__rec.move(${W / 2}, ${H - 90}, 600)`);
  await sleep(2800);

  // 5 — undo, auto
  await caption("<kbd>Ctrl</kbd>+<kbd>Z</kbd> takes a move back");
  await ctrl("z");
  await sleep(600);
  await settled();
  await sleep(1800);
  await caption("<b>auto</b> forgets the hand placement: the layout is automatic again");
  await click(await elAt("#auto-btn"), 700);
  await sleep(700);
  await settled();
  await sleep(2200);

  // 6 — vi mode (0.41)
  await caption("Since 0.41: vi keys in md docs — select Auth, <kbd>f</kbd> <kbd>m</kbd> opens its doc");
  await click("n:auth", 600);
  await sleep(500);
  await type(["f", "m"], 300);
  await js(`(async () => { for (let i = 0; i < 100 && !document.getElementById("md-text"); i++) await new Promise(r => setTimeout(r, 50)); })()`);
  await sleep(1200);
  await caption("<b>vi</b> turns vi mode on — normal mode first");
  await click(await elAt(".md-vi"), 600);
  await js(`__rec.move(${W / 2}, ${H - 90}, 500)`);
  await sleep(1400);
  await caption("<kbd>j</kbd> <kbd>j</kbd> down, <kbd>c</kbd><kbd>w</kbd> changes a word");
  await type(["j", "j"], 450);
  await sleep(400);
  await type(["c", "w"], 450);
  await type([..."Hands out"], 110);
  await type(["Escape"]);
  await sleep(1300);
  await caption("<kbd>:w</kbd> saves");
  await type([":", "w"], 350);
  await sleep(500);
  await type(["Enter"]);
  await sleep(1800);
  if (!String(await mdText()).includes("Hands out bearer tokens")) throw new Error("the vi edit didn't land: " + await mdText());

  // 7 — outro
  await caption("simpleviz 0.42 — github.com/sstoehrm/simpleviz");
  await sleep(2400);
  await send("Page.stopScreencast");
  encode(shots, { out: OUT, name: "whats-new-0.42" });
} catch (e) {
  report(e);
} finally {
  await close();
}
