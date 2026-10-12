// Record the "what's new" video: docs/assets/whats-new.mp4 and .gif —
// md pages with GitHub markdown, Present, >> links.
//   bb build && node dev/record-features.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-demo.mjs: serve a workspace, drive a headless
// Chromium over CDP, inject a caption and a cursor dot, encode the
// screencast.
import { spawn, execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const W = 1280, H = 720, CDP_PORT = 9334, PORT = 7493;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "simpleviz-feat-"));
// a snap chromium can't use a profile under /tmp
const snapDir = path.join(os.homedir(), "snap/chromium/common");
const profile = fs.mkdtempSync(path.join(fs.existsSync(snapDir) ? snapDir : os.tmpdir(), "simpleviz-rec-profile-"));
const ws = path.join(tmp, "ws"), frames = path.join(tmp, "frames");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const procs = [];

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.mkdirSync(frames);
fs.copyFileSync(path.join(ROOT, "examples/demo.edn"), path.join(ws, "demo.edn"));
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
fs.copyFileSync(path.join(ROOT, "plugins/simpleviz/assets/icon.png"), path.join(ws, "logo.png"));
fs.writeFileSync(path.join(ws, "api/notes.md"), `# API notes

The API is the **only** way in: the Web UI and every worker go through it.

| Route | Does |
| --- | --- |
| \`/orders\` | create and list orders |
| \`/auth\` | hand out tokens |

> Every request carries a bearer token.
`);
const deck = path.join(ws, "talk.md");
fs.writeFileSync(deck, `---

# The order platform

## where a click goes

Platform team · October 2026

---

# Today

- [x] Where we are
- [x] **The system**, live
- [ ] What comes next[^q]

> Questions any time — *really*.

[^q]: Next quarter's plan, not settled yet.

---

>> [the system](demo.edn)

# The system

Every request passes the **API**.

- Hover a node for its attributes
- Click it to inspect, read-only
- Collapse a box with its −

---

>> [notes](api/notes.md)

# The API

Its notes, rendered beside the slide.

---

>> ![logo](logo.png)

# A picture

Any \`.png\`, \`.jpg\`, \`.gif\`, \`.svg\` or \`.webp\`.

---

# Thanks

~~Slides in another tool~~ — one md file.
`);

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

const caption = html => js(`__rec.caption(${JSON.stringify(html)})`);
// wait until the presented slide's side has landed: its graph laid out,
// or its md/picture shown
const slideReady = () => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 120; i++) {
    const st = app.state.val, p = st.present;
    const side = p && p.side;
    const img = document.querySelector("#present-side img");
    const ok = p && (p.dest == null
      || (st.side && st.scene && !st.layouting)
      || (side && side.kind === "md" && side.text != null)
      || (side && side.kind === "image" && img && img.complete));
    if (ok) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  return false;
})()`);
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
async function next() { await key("ArrowRight"); await sleep(250); await slideReady(); }
async function scrollView(sel, dy, ms) {
  await js(`(async () => { const e = document.querySelector(${JSON.stringify(sel)}), y0 = e.scrollTop, t0 = performance.now();
    await new Promise(done => { const step = t => { const u = Math.min(1, (t - t0) / ${ms});
      e.scrollTop = y0 + ${dy} * (u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2); u < 1 ? requestAnimationFrame(step) : done(); };
      requestAnimationFrame(step); }); })()`);
}

// ---- run -------------------------------------------------------------------

try {
  start("bb", ["serve", deck, "--port", String(PORT)]);
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
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  await js(`(async () => { for (let i = 0; i < 100 && !document.getElementById("md-view"); i++) await new Promise(r => setTimeout(r, 50)); })()`);
  await sleep(500);

  const shots = [];
  on("Page.screencastFrame", ({ data, metadata, sessionId }) => {
    const f = path.join(frames, `${String(shots.length).padStart(5, "0")}.png`);
    fs.writeFileSync(f, Buffer.from(data, "base64"));
    shots.push({ f, t: metadata.timestamp, at: Date.now() });
    send("Page.screencastFrameAck", { sessionId });
  });

  // 1 — the md page, rendered
  await js(`__rec.jump(${W / 2}, ${H / 2 + 60})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz 0.40: an md page renders GitHub markdown");
  await sleep(2000);
  await caption("Task lists, <b>bold</b> and <i>italic</i>, quotes, tables, footnotes, pictures");
  await scrollView("#md-view", 330, 2200);
  await sleep(1400);
  await scrollView("#md-view", 520, 2200);
  await sleep(1400);
  await scrollView("#md-view", -2000, 900);

  // 2 — the >> lines, in Edit
  await caption("A slide's first line <code>&gt;&gt; [text](file)</code> links what shows beside it");
  await click(await elAt(".md-mode"), 600);
  await sleep(600);
  await js(`(ta => { const i = ta.value.indexOf(">> [the system]"); ta.setSelectionRange(i, ta.value.indexOf("\\n", i)); ta.blur();
    const lh = parseFloat(getComputedStyle(ta).lineHeight); ta.scrollTop = Math.max(0, ta.value.slice(0, i).split("\\n").length * lh - 120); })(document.getElementById("md-text"))`);
  await sleep(500);
  await moveTo(await elAt(".md-mark"), 700);
  await sleep(2300);
  await click(await elAt(".md-mode"), 500);
  await sleep(400);

  // 3 — Present
  await caption("<b>Present</b> shows the page as slides, split at <code>---</code>");
  await click(await elAt(".md-present"), 600);
  await slideReady();
  await js(`__rec.move(${W / 2}, ${H + 40}, 500)`);
  await sleep(2300);
  await caption("<kbd>→</kbd> or <kbd>space</kbd> steps through the slides");
  await next();
  await sleep(2600);

  // 4 — a graph beside the slide
  await caption("<code>&gt;&gt;</code> a graph: it sits beside the slide, ready to explore");
  await next();
  await sleep(1500);
  await caption("Hover a node for its attributes");
  await moveTo("n:api", 700);
  await sleep(1700);
  await moveTo("n:db", 600);
  await sleep(1500);
  await caption("Click it to inspect — read-only while presenting");
  await click("n:api", 500);
  await sleep(2300);
  await key("Escape");
  await sleep(300);
  await caption("Collapse and expand boxes as usual");
  await click(await js(`(async () => { const [x, y] = await __rec.at("b:storage");
    const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
    const it = app.state.val.scene.items.find(i => i.id === "b:storage"), v = cv.view,
          r = document.getElementById("canvas-wrap").getBoundingClientRect();
    return [r.left + v.x + (it.x + it.w - 13) * v.k, r.top + v.y + (it.y + 13) * v.k]; })()`), 600);
  await sleep(1800);
  await js(`__rec.move(${W * 0.3}, ${H + 40}, 500)`);

  // 5 — an md file beside the slide
  await caption("<code>&gt;&gt;</code> an md file: rendered beside the slide");
  await next();
  await sleep(2800);

  // 6 — a picture
  await caption("<code>&gt;&gt; ![alt](pic.png)</code>: a picture");
  await next();
  await sleep(2600);

  // 7 — the end, leave
  await next();
  await caption("<kbd>Esc</kbd> leaves; <code>?slide=4</code> in the URL survives a reload");
  await sleep(2400);
  await key("Escape");
  await sleep(900);

  // 8 — outro
  await caption("Start one: <code>simpleviz init talk.md -t presentation</code>");
  await sleep(2600);
  await caption("github.com/sstoehrm/simpleviz");
  await sleep(1800);
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
  const mp4 = path.join(OUT, "whats-new.mp4"), gif = path.join(OUT, "whats-new.gif");
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
