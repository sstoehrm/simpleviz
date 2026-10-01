// Record the README tour: docs/assets/demo.mp4 and demo.gif.
//   bb build && node dev/record-demo.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Copies the examples to a temp folder, serves them, drives a headless
// Chromium through the scenes below over the DevTools protocol and
// encodes its screencast. Captions and the cursor dot are injected DOM.
import { spawn, execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const W = 1280, H = 720, CDP_PORT = 9333, PORT = 7491, CMP_PORT = 7492;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "simpleviz-rec-"));
const ws = path.join(tmp, "ws"), frames = path.join(tmp, "frames");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const procs = [];

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.mkdirSync(path.join(ws, "views"));
fs.mkdirSync(frames);
fs.copyFileSync(path.join(ROOT, "examples/demo.edn"), path.join(ws, "demo.edn"));
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
fs.writeFileSync(path.join(ws, "views/deploy.edn"), `{:nodes {:ingress {:name "Ingress" :type "k8s"}
         :api-svc {:name "api-svc" :type "deployment" :replicas 3}
         :pg      {:name "postgres-0" :type "statefulset"}
         :redis   {:name "redis" :type "deployment"}}
 :edges {[:ingress :api-svc] {:direction :-> :name "443"}
         [:api-svc :pg]      {:direction :-> :name "5432"}
         [:api-svc :redis]   {:direction :-> :name "6379"}}
 :boxes {:cluster {:name "prod cluster" :type "k8s"
                   :components #{:ingress :api-svc :pg :redis}}}}
`);
const demo = path.join(ws, "demo.edn");
const edit = (file, from, to) => {
  const s = fs.readFileSync(file, "utf8");
  if (!s.includes(from)) throw new Error(`${path.basename(file)}: no ${JSON.stringify(from)}`);
  fs.writeFileSync(file, s.replace(from, to));
};
// the API node is "the same thing" as api-svc in the deploy view
edit(demo, `:ref "api/internals.edn"}`, `:ref "api/internals.edn"\n                  :pair "views/deploy.edn#api-svc"}`);

// ---- processes -------------------------------------------------------------

function start(cmd, args) {
  const p = spawn(cmd, args, { cwd: ROOT, stdio: ["ignore", "pipe", "pipe"], detached: true });
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

// The overlay: caption pill top left, cursor dot, helpers to locate scene
// items on screen. Installed on every document (the compare server is a
// second page load).
const OVERLAY = `(() => {
  if (window.__rec) return;
  const install = () => {
    const st = document.createElement("style");
    st.textContent = \`
      #rec-cap { position: fixed; left: 16px; top: 14px; z-index: 2147483647; pointer-events: none;
        background: #1f2430; color: #fff; font: 600 17px/1.25 system-ui, sans-serif;
        padding: 9px 15px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,.25);
        transition: opacity .25s; }
      #rec-cap:empty { opacity: 0; }
      #rec-cap kbd { font: 600 15px ui-monospace, monospace; background: #3b4252; border-radius: 4px; padding: 1px 6px; }
      #rec-cur { position: fixed; left: -40px; top: -40px; width: 18px; height: 18px; margin: -9px 0 0 -9px;
        border-radius: 50%; background: rgba(59,108,240,.55); border: 2px solid #fff;
        box-shadow: 0 0 0 1px rgba(59,108,240,.9); z-index: 2147483647; pointer-events: none;
        transition: transform .12s; }
      #rec-cur.down { transform: scale(.65); }\`;
    document.head.append(st);
    const cap = Object.assign(document.createElement("div"), { id: "rec-cap" });
    const cur = Object.assign(document.createElement("div"), { id: "rec-cur" });
    document.body.append(cap, cur);
    // step below the trail or the diff legend rather than cover them
    const dodge = () => {
      const c = cap.getBoundingClientRect();
      let top = 14;
      for (const e of document.querySelectorAll("#trail, #diff-legend")) {
        const r = e.getBoundingClientRect();
        if (r.width && r.left < c.right && r.right > c.left && r.top < 14 + c.height) top = Math.max(top, r.bottom + 10);
      }
      cap.style.top = top + "px";
      requestAnimationFrame(dodge);
    };
    requestAnimationFrame(dodge);
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
    // screen point of a scene item: "n:api", "b:storage" (title bar), or an
    // edge by name
    async at(id) {
      const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
      const st = app.state.val;
      const it = (st.scene?.items ?? []).find(i => i.id === id || (i.kind === "edge" && i.name === id));
      if (!it) throw new Error("no scene item " + id + " in " + (st.scene?.items ?? []).map(i => i.id).join(" "));
      const v = cv.view, r = document.getElementById("canvas-wrap").getBoundingClientRect();
      let gx, gy;
      if (it.kind === "edge") { const p = it.points, a = p[Math.floor((p.length - 1) / 2)], b = p[Math.floor((p.length - 1) / 2) + 1]; gx = (a.x + b.x) / 2; gy = (a.y + b.y) / 2; }
      else if (it.kind === "box") { gx = it.x + 24; gy = it.y + it["title-h"] / 2; }
      else { gx = it.x + it.w / 2; gy = it.y + it.h / 2; }
      return [r.left + v.x + gx * v.k, r.top + v.y + gy * v.k];
    },
    // center of the first element matching a CSS selector (and text)
    el(sel, text) {
      const e = [...document.querySelectorAll(sel)].find(e => !text || e.textContent.includes(text));
      if (!e) throw new Error("no element " + sel + " " + (text ?? ""));
      const r = e.getBoundingClientRect();
      return [r.left + r.width / 2, r.top + r.height / 2];
    },
  };
})()`;

const caption = html => js(`__rec.caption(${JSON.stringify(html)})`);
const settled = () => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 100; i++) {
    const st = app.state.val;
    if (st.scene && !st.layouting) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  return false;
})()`);
// wait until the page shows the graph file whose path ends with name
const shows = name => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 100; i++) {
    const st = app.state.val;
    if (String(st.graph?.path ?? "").endsWith(${JSON.stringify(name)}) && st.scene && !st.layouting) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error("never showed ${name}: " + app.state.val.graph?.path);
})()`);
async function moveTo(target, ms = 550) {
  const [x, y] = typeof target === "string" ? await js(`__rec.at(${JSON.stringify(target)})`) : target;
  await js(`__rec.move(${x}, ${y}, ${ms})`);
  return [x, y];
}
async function click(target, ms) {
  const [x, y] = await moveTo(target, ms);
  await sleep(120);
  await js("__rec.press(true)");
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"])
    await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
  await sleep(120);
  await js("__rec.press(false)");
}
const elAt = (sel, text) => js(`__rec.el(${JSON.stringify(sel)}, ${JSON.stringify(text ?? null)})`);
async function key(k, opts = {}) {
  const codes = { Enter: 13, Escape: 27 };
  for (const type of ["keyDown", "keyUp"])
    await send("Input.dispatchKeyEvent", { type, key: k, code: k.length === 1 ? `Key${k.toUpperCase()}` : k,
      text: type === "keyDown" && k.length === 1 ? k : undefined,
      windowsVirtualKeyCode: codes[k] ?? (k.length === 1 ? k.toUpperCase().charCodeAt(0) : 0), ...opts });
}
async function chord(a, b) { await key(a); await sleep(260); await key(b); }
async function type(text) { for (const ch of text) { await send("Input.insertText", { text: ch }); await sleep(70); } }
async function navigate(url) {
  await send("Page.navigate", { url });
  await sleep(600);
  await settled();
  await sleep(300);
}
async function theme(name) {
  await js(`(() => { const s = document.getElementById("theme-select");
    s.value = ${JSON.stringify(name)}; s.dispatchEvent(new Event("change", { bubbles: true })); })()`);
}

// ---- run -------------------------------------------------------------------

try {
  start("bb", ["serve", demo, "--port", String(PORT)]);
  const chrome = start(process.env.CHROMIUM ?? "chromium", ["--headless=new", `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${path.join(tmp, "chrome")}`, `--window-size=${W},${H}`, "--hide-scrollbars",
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

  await navigate(`http://127.0.0.1:${PORT}/`);
  const size = await js("[innerWidth, innerHeight]");
  if (size[0] !== W || size[1] !== H) throw new Error(`viewport is ${size}, wanted ${W}x${H}`);

  // screencast: frames come only on change; keep each with its timestamp
  const shots = [];
  on("Page.screencastFrame", ({ data, metadata, sessionId }) => {
    const f = path.join(frames, `${String(shots.length).padStart(5, "0")}.png`);
    fs.writeFileSync(f, Buffer.from(data, "base64"));
    shots.push({ f, t: metadata.timestamp, at: Date.now() });
    send("Page.screencastFrameAck", { sessionId });
  });

  // 1 — title
  await js(`__rec.jump(${W / 2}, ${H / 2 + 40})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz — an EDN file becomes an auto-laid-out diagram");
  await sleep(2200);

  // 2 — live reload
  await caption("Edit the file: the page live-reloads");
  await sleep(500);
  edit(demo, `:logs   {:name "Log Sink"}}`,
    `:logs   {:name "Log Sink"}\n         :search {:name "Search"   :type "service"}}`);
  edit(demo, `[:worker :logs]  {:direction :->  :name "write"}`,
    `[:worker :logs]  {:direction :->  :name "write"}\n         [:api :search]   {:direction :->  :name "index"   :type "http"}`);
  edit(demo, `#{:api :auth :storage :worker :queue}`, `#{:api :auth :storage :worker :queue :search}`);
  await sleep(2200);

  // 3 — inspect
  await caption("Click anything to inspect its attributes");
  await click("n:api");
  await sleep(1700);

  // 4 — edit in place
  await caption("Edit in place: <kbd>c n</kbd> adds a node, written back to the file");
  await click("b:storage");
  await sleep(500);
  await chord("c", "n");
  await sleep(400);
  await type("S3::storage");
  await sleep(300);
  await key("Enter");
  await sleep(1800);
  await key("Escape");

  // 5 — state marks
  await caption("<code>:state</code> marks progress: new, in progress, blocked, done");
  edit(demo, `:name "Search"   :type "service"`, `:name "Search"   :type "service" :state :new`);
  edit(demo, `:lang "clojure" :replicas 3`, `:lang "clojure" :replicas 3 :state :in-progress`);
  edit(demo, `:type "database" :version "16"`, `:type "database" :version "16" :state :blocked`);
  edit(demo, `:framework "htmx"`, `:framework "htmx" :state :done`);
  await sleep(1000);
  await moveTo("n:search", 500);
  await moveTo("n:api", 400);
  await moveTo("n:db", 400);
  await moveTo("n:web", 500);
  await sleep(500);

  // 6 — themes
  await caption("Pick one of 12 themes — or set <code>:theme</code> in the file");
  await click(await elAt("#theme-select"));
  for (const t of ["nord", "dracula", "paper", "blueprint"]) { await theme(t); await sleep(900); }
  await theme("");
  await sleep(300);

  // 7 — pairs
  await caption("Pairs link the same thing across graphs: <kbd>f p</kbd>");
  await click("n:api");
  await sleep(900);
  await chord("f", "p");
  await shows("views/deploy.edn");
  await sleep(2000);

  // 8 — refs
  await caption("Follow a <code>:ref</code> into a sub-graph: <kbd>f r</kbd>");
  await click(await elAt(".trail-crumb"));
  await shows("demo.edn");
  await sleep(300);
  await click("n:api");
  await sleep(600);
  await chord("f", "r");
  await shows("api/internals.edn");
  await sleep(2000);

  // 9 — fork, edit, compare
  await send("Page.stopScreencast");
  // the last frame before the cut holds until the stop, not until the
  // first frame of the next screencast
  const cut = shots.length, cutHold = (Date.now() - shots.at(-1).at) / 1000;
  execFileSync("bb", ["fork", demo, "next"], { cwd: ROOT, stdio: "ignore" });
  const next = path.join(ws, "demo-next.edn");
  edit(next, `:mail   {:name "Mailer"   :type "external" :provider "ses"}\n`, "");
  edit(next, `[:worker :mail]  {:direction :->  :name "send"    :type "smtp"}\n`, "");
  edit(next, `:logs   {:name "Log Sink"}`, `:logs   {:name "Log Sink"}\n         :metrics {:name "Metrics" :type "service"}`);
  edit(next, `[:worker :logs]  {:direction :->  :name "write"}`,
    `[:worker :logs]  {:direction :->  :name "write"}\n         [:api :metrics]  {:direction :->  :name "emit"    :type "http"}`);
  edit(next, `:version "16"`, `:version "17"`);
  edit(next, `:auth "bearer"`, `:auth "mtls"`);
  edit(next, `:storage :worker :queue :search}`, `:storage :worker :queue :search :metrics}`);
  start("bb", ["serve", demo, "next", "--port", String(CMP_PORT)]);
  await waitFor(`http://127.0.0.1:${CMP_PORT}/`);
  await navigate(`http://127.0.0.1:${CMP_PORT}/`);
  await js(`__rec.jump(${W / 2}, ${H / 2 + 40})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("Fork, edit, compare: <code>simpleviz demo.edn next</code>");
  await sleep(1800);
  await caption("The legend steps through every change");
  for (const row of ["added", "modified", "removed"]) { await click(await elAt("#diff-legend *", row), 450); await sleep(900); }

  // 10 — export: close the inspector and fit the whole graph again first
  await click(await elAt("#details-close"), 400);
  await js(`(async () => { const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
    cv.refit_next_BANG_(); cv.fit_view_once_BANG_(app.state.val.scene); cv.request_paint_BANG_(); })()`);
  await caption("Export a PNG or an SVG, the source embedded");
  await click(await elAt("#export-btn"));
  await sleep(1800);
  await key("Escape");

  // 11 — outro
  await caption("github.com/sstoehrm/simpleviz");
  await js(`__rec.move(${W / 2}, ${H + 40}, 600)`);
  await sleep(1800);
  await send("Page.stopScreencast");
  const endHold = (Date.now() - shots.at(-1).at) / 1000;

  // ---- encode --------------------------------------------------------------
  // the two screencasts are joined back to back: drop the gap between them
  const list = [];
  for (let i = 0; i < shots.length; i++) {
    const d = i + 1 === cut ? cutHold : i + 1 < shots.length ? shots[i + 1].t - shots[i].t : endHold;
    list.push(`file '${shots[i].f}'`, `duration ${Math.min(Math.max(d, 0.001), 3).toFixed(3)}`);
  }
  list.push(`file '${shots.at(-1).f}'`);
  const concat = path.join(tmp, "frames.txt");
  fs.writeFileSync(concat, list.join("\n") + "\n");
  const mp4 = path.join(OUT, "demo.mp4"), gif = path.join(OUT, "demo.gif");
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
