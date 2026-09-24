// Drive a headless Chromium over the DevTools protocol (Node >= 22, global
// WebSocket). Start Chromium first:
//   chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 about:blank &
// then: node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7465/","wait":3000},{"screenshot":"/tmp/s.png"}]'
// Actions: navigate/wait, wait, click [x y], eval "js" (with
// "awaitPromise": true, prints what the promise resolves to), type "text",
// key "Enter"|"f"|..., screenshot "path".
const actions = JSON.parse(process.argv[2]);
const targets = await (await fetch("http://127.0.0.1:9222/json")).json();
const page = targets.find(t => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const sleep = ms => new Promise(r => setTimeout(r, ms));
await send("Page.enable"); await send("Runtime.enable");
for (const a of actions) {
  if (a.navigate) { await send("Page.navigate", { url: a.navigate }); await sleep(a.wait ?? 1500); }
  else if (a.wait) await sleep(a.wait);
  else if (a.click) { const [x, y] = a.click;
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"])
      await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
    await sleep(300); }
  else if (a.eval) { const r = await send("Runtime.evaluate", { expression: a.eval, returnByValue: true, awaitPromise: !!a.awaitPromise }); console.log(JSON.stringify(r.result?.result?.value ?? r.result)); }
  else if (a.type) { await send("Input.insertText", { text: a.type }); await sleep(200); }
  else if (a.key) { for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", { type, key: a.key, code: a.key, windowsVirtualKeyCode: a.key === "Enter" ? 13 : 0, modifiers: a.shift ? 8 : 0 }); await sleep(300); }
  else if (a.screenshot) { const r = await send("Page.captureScreenshot", { format: "png" });
    const fs = await import("node:fs"); fs.writeFileSync(a.screenshot, Buffer.from(r.result.data, "base64")); console.log("saved", a.screenshot); }
}
ws.close();
