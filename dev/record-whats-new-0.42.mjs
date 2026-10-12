// Record the "what's new in 0.42" video: docs/assets/whats-new-0.42.mp4
// and .gif — manual layout (✥, dragging on the grid, auto) and the md
// editor's vi mode from 0.41.
//   bb build && node dev/record-whats-new-0.42.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-features.mjs: serve a workspace, drive a
// headless Chromium over CDP — real mouse and key events — inject a
// caption and a cursor dot, encode the screencast.
import { spawn, execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const W = 1280, H = 720, CDP_PORT = 9335, PORT = 7494;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "simpleviz-042-"));
// a snap chromium can't use a profile under /tmp
const snapDir = path.join(os.homedir(), "snap/chromium/common");
const profile = fs.mkdtempSync(path.join(fs.existsSync(snapDir) ? snapDir : os.tmpdir(), "simpleviz-rec-profile-"));
const ws = path.join(tmp, "ws"), frames = path.join(tmp, "frames");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const procs = [];

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.mkdirSync(path.join(ws, "docs"));
fs.mkdirSync(frames);
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
const demo = path.join(ws, "demo.edn");
fs.writeFileSync(demo, fs.readFileSync(path.join(ROOT, "examples/demo.edn"), "utf8")
  .replace(`:auth   {:name "Auth"     :type "service"}`, `:auth   {:name "Auth"     :type "service" :md-ref "docs/auth.md"}`));
if (!fs.readFileSync(demo, "utf8").includes(":md-ref")) throw new Error("demo.edn: no Auth node to give an :md-ref");
fs.writeFileSync(path.join(ws, "docs/auth.md"),
  "# Auth\n\nIssues bearer tokens for the Web UI.\nCalled by the API on every request.\n");

// ---- processes -------------------------------------------------------------

const env = { ...process.env, SIMPLEVIZ_HOME: path.join(tmp, "home") };
function start(cmd, args) {
  const p = spawn(cmd, args, { cwd: ROOT, env, stdio: ["ignore", "pipe", "pipe"], detached: true });
  let log = ""; p.stdout.on("data", d => log += d); p.stderr.on("data", d => log += d);
  p.log = () => log;
  procs.push(p);
  return p;
}
async function waitFor(url, ms = 20000) {
  for (const t0 = Date.now(); Date.now() - t0 < ms; await sleep(200))
    try { const r = await fetch(url); if (r.ok) return r; } catch {}
  throw new Error(`timed out waiting for ${url}`);
}
function cleanup() {
  for (const p of procs) try { process.kill(-p.pid); } catch {}
  fs.rmSync(tmp, { recursive: true, force: true });
  fs.rmSync(profile, { recursive: true, force: true });
}
process.on("exit", cleanup);
process.on("SIGINT", () => process.exit(130));

// ---- CDP -------------------------------------------------------------------

let ws_, msgId = 0;
const pending = new Map(), listeners = new Map();
const send = (method, params = {}) => new Promise((res, rej) => {
  const i = ++msgId;
  pending.set(i, m => m.error ? rej(new Error(`${method}: ${m.error.message}`)) : res(m.result));
  ws_.send(JSON.stringify({ id: i, method, params }));
});
const on = (method, f) => listeners.set(method, f);
async function js(expr) {
  const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`eval failed: ${r.exceptionDetails.exception?.description ?? expr}`);
  return r.result.value;
}

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
      const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
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

async function moveTo(target, ms = 550) {
  const [x, y] = typeof target === "string" ? await js(`__rec.at(${JSON.stringify(target)})`) : target;
  await js(`__rec.move(${x}, ${y}, ${ms})`);
  // the real pointer follows, so hover tooltips show
  await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
  return [x, y];
}
async function click(target, ms) {
  const [x, y] = await moveTo(target, ms);
  await sleep(120);
  await js("__rec.press(true)");
  for (const type of ["mousePressed", "mouseReleased"])
    await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
  await sleep(120);
  await js("__rec.press(false)");
}
const elAt = (sel, text) => js(`__rec.el(${JSON.stringify(sel)}, ${JSON.stringify(text ?? null)})`);
async function key(k) {
  const codes = { Enter: 13, Escape: 27, ArrowRight: 39, ArrowLeft: 37 };
  for (const type of ["keyDown", "keyUp"])
    await send("Input.dispatchKeyEvent", { type, key: k, code: k, windowsVirtualKeyCode: codes[k] ?? 0 });
}
const caption = html => js(`__rec.caption(${JSON.stringify(html)})`);
const settled = () => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 200; i++) {
    const st = app.state.val;
    if (st.scene && !st.layouting) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  return false;
})()`);
// the screen point of a scene item's middle (a box's too, not its title)
const middleOf = id => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
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
  start("bb", ["serve", demo, "--port", String(PORT)]);
  const chrome = start(process.env.CHROMIUM ?? "chromium", ["--headless=new", `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${profile}`, `--window-size=${W},${H}`, "--hide-scrollbars",
    "--force-color-profile=srgb", "--no-first-run", "about:blank"]);
  await waitFor(`http://127.0.0.1:${PORT}/`);
  const targets = await (await waitFor(`http://127.0.0.1:${CDP_PORT}/json`)).json();
  const page = targets.find(t => t.type === "page");
  if (!page) throw new Error("no page target\n" + chrome.log());
  ws_ = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws_.onopen = r);
  ws_.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    else if (listeners.has(m.method)) listeners.get(m.method)(m.params);
  };
  await send("Page.enable"); await send("Runtime.enable");
  await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "light" }] });
  await send("Page.addScriptToEvaluateOnNewDocument", { source: OVERLAY });
  // auto asks before it drops the positions: yes
  on("Page.javascriptDialogOpening", () => send("Page.handleJavaScriptDialog", { accept: true }));
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  // the page's own scripts first: importing the app before ELK is there fails
  await sleep(1000);
  await js(`(async () => { for (let i = 0; i < 200 && !(document.readyState === "complete" && window.ELK); i++)
    await new Promise(r => setTimeout(r, 50)); })()`);
  await settled();
  await sleep(800);

  const shots = [];
  on("Page.screencastFrame", ({ data, metadata, sessionId }) => {
    const f = path.join(frames, `${String(shots.length).padStart(5, "0")}.png`);
    fs.writeFileSync(f, Buffer.from(data, "base64"));
    shots.push({ f, t: metadata.timestamp, at: Date.now() });
    send("Page.screencastFrameAck", { sessionId });
  });

  // 1 — the graph, laid out automatically
  await js(`__rec.jump(${W / 2}, ${H - 120})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz 0.42: lay a graph out by hand");
  await sleep(2200);

  // 2 — ✥
  await caption("<b>✥</b> turns manual layout on: each node and box gets a <code>:pos</code> on a 20 px grid");
  await click(await elAt("#arrange-btn"), 700);
  await js(`(async () => { const app = await import("/js/simpleviz/app.mjs");
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
  const endHold = (Date.now() - shots.at(-1).at) / 1000;

  // ---- encode ----------------------------------------------------------------
  const list = [];
  for (let i = 0; i < shots.length; i++) {
    const d = i + 1 < shots.length ? shots[i + 1].t - shots[i].t : endHold;
    list.push(`file '${shots[i].f}'`, `duration ${Math.min(Math.max(d, 0.001), 3).toFixed(3)}`);
  }
  list.push(`file '${shots.at(-1).f}'`);
  const concat = path.join(tmp, "frames.txt");
  fs.writeFileSync(concat, list.join("\n") + "\n");
  fs.mkdirSync(OUT, { recursive: true });
  const mp4 = path.join(OUT, "whats-new-0.42.mp4"), gif = path.join(OUT, "whats-new-0.42.gif");
  execFileSync("ffmpeg", ["-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", concat,
    "-vf", "fps=25,format=yuv420p", "-c:v", "libx264", "-preset", "slow", "-crf", "24",
    "-movflags", "+faststart", mp4], { stdio: "inherit" });
  execFileSync("ffmpeg", ["-y", "-v", "error", "-i", mp4, "-vf",
    "fps=10,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
    gif], { stdio: "inherit" });
  console.log(`wrote ${mp4} and ${gif} (${shots.length} frames)`);
  await send("Browser.close").catch(() => {});
} catch (e) {
  console.error(e.stack ?? e);
  for (const p of procs) if (p.log().trim()) console.error(`--- ${p.spawnargs.join(" ")}\n${p.log().slice(-2000)}`);
  process.exitCode = 1;
} finally {
  ws_?.close();
  cleanup();
}
