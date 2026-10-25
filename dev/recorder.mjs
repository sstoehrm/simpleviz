// What the dev/record-*.mjs videos share: a workspace in a temp folder,
// the processes they start (bb serve, a headless Chromium), the DevTools
// protocol, a pointer and keys that drive the page as a viewer would,
// the screencast and its encoding to an .mp4 and a .gif.
//   const r = recorder("simpleviz-rec-", { hover: true });
//   const server = r.start("bb", ["serve", file, "--port", PORT]);
//   const url = await r.serverUrl(server);
//   await r.launch(CDP_PORT, OVERLAY); const shots = r.capture(); …
//   r.encode(shots, { out, name });
// Needs Node >= 22 (global WebSocket), chromium (or $CHROMIUM), ffmpeg.
import { spawn, execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
export const W = 1280, H = 720;
export const sleep = ms => new Promise(r => setTimeout(r, ms));

// `hover`: the real pointer follows every move of the dot, so hover
// tooltips show; else it moves only to click.
export function recorder(prefix, { hover = false } = {}) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  // a snap chromium can't use a profile under /tmp
  const snapDir = path.join(os.homedir(), "snap/chromium/common");
  const profile = fs.mkdtempSync(path.join(fs.existsSync(snapDir) ? snapDir : os.tmpdir(), "simpleviz-rec-profile-"));
  const ws = path.join(tmp, "ws"), frames = path.join(tmp, "frames");
  fs.mkdirSync(ws, { recursive: true });
  fs.mkdirSync(frames);

  // ---- processes -----------------------------------------------------------

  // the servers keep the theme pick in a home of their own, never in
  // your ~/.simpleviz
  const env = { ...process.env, SIMPLEVIZ_HOME: path.join(tmp, "home") };
  const procs = [];
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
  // The port alone grants no access: use this server's printed session
  // URL, which bb serve includes after "serving … at".
  async function serverUrl(proc, ms = 20000) {
    for (const t0 = Date.now(); Date.now() - t0 < ms; await sleep(200)) {
      const url = proc.log().match(/http:\/\/localhost:\d+\/[A-Za-z0-9_-]{43}(?=\s|$)/)?.[0];
      if (url) { await waitFor(url, ms); return url; }
      if (proc.exitCode !== null || proc.signalCode !== null) throw new Error("server exited before printing its URL");
    }
    throw new Error("server did not print its session URL");
  }
  function cleanup() {
    for (const p of procs) try { process.kill(-p.pid); } catch {}
    // Chromium may still be writing its profile as it goes: try again
    for (const dir of [tmp, profile]) fs.rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  }
  process.on("exit", cleanup);
  process.on("SIGINT", () => process.exit(130));

  // ---- CDP -----------------------------------------------------------------

  let sock, msgId = 0;
  const pending = new Map(), listeners = new Map();
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++msgId;
    pending.set(i, m => m.error ? rej(new Error(`${method}: ${m.error.message}`)) : res(m.result));
    sock.send(JSON.stringify({ id: i, method, params }));
  });
  const on = (method, f) => listeners.set(method, f);
  async function js(expr) {
    const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(`eval failed: ${r.exceptionDetails.exception?.description ?? expr}`);
    return r.result.value;
  }

  // A headless Chromium on `cdpPort`, its page W×H in the light scheme,
  // `overlay` (the caption, the cursor dot, the locators: window.__rec)
  // installed on every document.
  async function launch(cdpPort, overlay) {
    const chrome = start(process.env.CHROMIUM ?? "chromium", ["--headless=new", `--remote-debugging-port=${cdpPort}`,
      `--user-data-dir=${profile}`, `--window-size=${W},${H}`, "--hide-scrollbars",
      "--force-color-profile=srgb", "--no-first-run", "about:blank"]);
    const targets = await (await waitFor(`http://127.0.0.1:${cdpPort}/json`)).json();
    const page = targets.find(t => t.type === "page");
    if (!page) throw new Error("no page target\n" + chrome.log());
    sock = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise(r => sock.onopen = r);
    sock.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
      else if (listeners.has(m.method)) listeners.get(m.method)(m.params);
    };
    await send("Page.enable"); await send("Runtime.enable");
    await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 1, mobile: false });
    await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "light" }] });
    await send("Page.addScriptToEvaluateOnNewDocument", { source: overlay });
  }

  // ---- the viewer ----------------------------------------------------------

  // the dot glides to `target` (a selector, or [x y]); with `hover` the
  // real pointer follows
  async function moveTo(target, ms = 550) {
    const [x, y] = typeof target === "string" ? await js(`__rec.at(${JSON.stringify(target)})`) : target;
    await js(`__rec.move(${x}, ${y}, ${ms})`);
    if (hover) await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
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
  // a named key (Enter, Escape, ArrowRight …) or a character, which types
  // where a text field takes it
  async function key(k, opts = {}) {
    const codes = { Enter: 13, Escape: 27, ArrowRight: 39, ArrowLeft: 37 };
    for (const type of ["keyDown", "keyUp"])
      await send("Input.dispatchKeyEvent", { type, key: k, code: k.length === 1 ? `Key${k.toUpperCase()}` : k,
        text: type === "keyDown" && k.length === 1 ? k : undefined,
        windowsVirtualKeyCode: codes[k] ?? (k.length === 1 ? k.toUpperCase().charCodeAt(0) : 0), ...opts });
  }
  const caption = html => js(`__rec.caption(${JSON.stringify(html)})`);
  // the graph shown is laid out
  const settled = () => js(`(async () => {
    const app = await import("./js/simpleviz/app.mjs");
    for (let i = 0; i < 200; i++) {
      const st = app.state.val;
      if (st.scene && !st.layouting) return true;
      await new Promise(r => setTimeout(r, 50));
    }
    return false;
  })()`);

  // ---- the video -----------------------------------------------------------

  // The screencast's frames as they come — only on change — each with its
  // timestamp; startScreencast starts them.
  function capture() {
    const shots = [];
    on("Page.screencastFrame", ({ data, metadata, sessionId }) => {
      const f = path.join(frames, `${String(shots.length).padStart(5, "0")}.png`);
      fs.writeFileSync(f, Buffer.from(data, "base64"));
      shots.push({ f, t: metadata.timestamp, at: Date.now() });
      send("Page.screencastFrameAck", { sessionId });
    });
    return shots;
  }

  // `shots` as <out>/<name>.mp4 and .gif, each frame held until the next
  // (at most 3 s), the last for as long as the page stayed still since.
  // `cut`, the index where a second screencast starts, is held for
  // `cutHold` seconds instead of the gap between the two.
  function encode(shots, { out, name, cut, cutHold }) {
    const endHold = (Date.now() - shots.at(-1).at) / 1000;
    const list = [];
    for (let i = 0; i < shots.length; i++) {
      const d = i + 1 === cut ? cutHold : i + 1 < shots.length ? shots[i + 1].t - shots[i].t : endHold;
      list.push(`file '${shots[i].f}'`, `duration ${Math.min(Math.max(d, 0.001), 3).toFixed(3)}`);
    }
    list.push(`file '${shots.at(-1).f}'`);
    const concat = path.join(tmp, "frames.txt");
    fs.writeFileSync(concat, list.join("\n") + "\n");
    fs.mkdirSync(out, { recursive: true });
    const mp4 = path.join(out, `${name}.mp4`), gif = path.join(out, `${name}.gif`);
    execFileSync("ffmpeg", ["-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", concat,
      "-vf", "fps=25,format=yuv420p", "-c:v", "libx264", "-preset", "slow", "-crf", "24",
      "-movflags", "+faststart", mp4], { stdio: "inherit" });
    execFileSync("ffmpeg", ["-y", "-v", "error", "-i", mp4, "-vf",
      "fps=10,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
      gif], { stdio: "inherit" });
    console.log(`wrote ${mp4} and ${gif} (${shots.length} frames)`);
  }

  // a recording that failed: why, and the tail of what each process said
  function report(e) {
    console.error(e.stack ?? e);
    for (const p of procs) if (p.log().trim()) console.error(`--- ${p.spawnargs.join(" ")}\n${p.log().slice(-2000)}`);
    process.exitCode = 1;
  }
  async function close() {
    await send("Browser.close").catch(() => {});
    sock?.close();
    cleanup();
  }

  return { tmp, ws, frames, env, start, waitFor, serverUrl, send, on, js, launch, moveTo, click, elAt, key, caption, settled,
           capture, encode, report, close };
}
