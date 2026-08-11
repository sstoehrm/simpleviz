# PNG Export (PR A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An "Export PNG" button in the viewer downloads the whole graph as a PNG (no metadata yet — that's PR B).

**Architecture:** Server adds the graph file's basename to `/api/graph` (download name). `canvas.cljs` extracts the item-painting loop into a shared `paint-items!` used by both the live `paint!` and a new offscreen `export-canvas`; `app.cljs` adds the button + blob download. Spec: `docs/superpowers/specs/2026-08-10-png-export-design.md` (PR A section).

**Tech Stack:** babashka server (`bb test:clj`), squint frontend (`bb build && bb test:js`).

## Global Constraints

- Export renders the WHOLE scene (not the viewport) at scale `min(2, 8000 / max(width, height))` on the current theme's background, text always on, no selection ring.
- The live canvas rendering must be pixel-identical to before — `paint!` is a pure refactor around the extracted loop.
- `/api/graph` gains `:file "<basename>"` in BOTH modes (new file's basename in compare); nothing else about the payload changes.
- Download name: basename with `.edn` stripped + `.png`; fallback `graph.png`.
- `#export-btn` joins `#theme-toggle` in BOTH pan-zoom `closest` exclusion selectors.
- Commit style `feat:`/`docs:` as in git history.

---

### Task 1: server — `:file` basename in the graph payload

**Files:**
- Modify: `server/serve.clj`
- Modify: `test/server_test.clj`

**Interfaces:**
- Produces: `graph-json` gains a 2-arity `[s fname]` that assocs `:file fname` onto the normalized map before JSON encoding (1-arity keeps today's behavior for existing tests); `compare-json` gains a `:file` key = the NEW file's basename. The handler passes `(.getName (io/file f))`.

- [ ] **Step 1: Write the failing tests** (append to `test/server_test.clj`)

```clojure
(deftest graph-json-includes-file-basename
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}" "demo.edn"))]
    (is (= "demo.edn" (get out "file"))))
  ;; 1-arity unchanged: no :file key
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "file")))))

(deftest compare-json-includes-new-file-basename
  (let [out (json/parse-string
             (serve/compare-json "{}" "{}" "examples/old.edn" "examples/new.edn"))]
    (is (= "new.edn" (get out "file")))))
```

- [ ] **Step 2: Run to verify failures**

Run: `bb test:clj`
Expected: FAIL — no `file` key, no 2-arity.

- [ ] **Step 3: Implement in `server/serve.clj`**

```clojure
(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  With fname, the payload carries it as :file (the export download
  name). Parse failures return {\"error\": message} instead of throwing."
  ([s] (graph-json s nil))
  ([s fname]
   (try
     (json/generate-string
      (cond-> (graph/normalize (edn/read-string s))
        (some? fname) (assoc :file fname)))
     (catch Exception e
       (json/generate-string {:error (ex-message e)})))))
```

In `compare-json`, wrap the union in the same way (the new file's basename comes from the caller):

```clojure
      (json/generate-string
       (assoc (diff/union old-g new-g old-name new-name)
              :file (.getName (io/file new-name))))
```

In the handler's `"/api/graph"` branch, pass the basename for single-file mode:

```clojure
                          (graph-json (slurp new) (.getName (io/file new)))
```

- [ ] **Step 4: Run to verify green, commit**

Run: `bb test:clj`
Expected: PASS.

```bash
git add server/serve.clj test/server_test.clj
git commit -m "feat: graph payload carries the served file's basename"
```

---

### Task 2: canvas export path + button

**Files:**
- Modify: `src/simpleviz/canvas.cljs`
- Modify: `src/simpleviz/app.cljs`
- Modify: `public/style.css`

**Interfaces:**
- Produces: `canvas/export-canvas [sc]` → offscreen `<canvas>` with the whole scene painted; private `paint-items!` shared by `paint!` and `export-canvas`; `app`'s `#export-btn` + `export-png!`.

- [ ] **Step 1: Extract the shared painting loop in `src/simpleviz/canvas.cljs`**

The culling/text-threshold/item `case` block at the end of `paint!` becomes:

```clojure
(defn- paint-items!
  "Draw every scene item visible in the graph-space rect vr at zoom k."
  [ctx sc vr k selected-id]
  (let [text? (>= (* k 11) scene/TEXT-MIN-PX)]
    (doseq [item (:items sc)]
      (when (scene/visible? item vr)
        (let [sel? (= selected-id (:id item))]
          (case (:kind item)
            "box" (draw-box ctx item sel? text?)
            "edge" (draw-edge ctx item sel? text?)
            "edge-label" (when text? (draw-edge-label ctx item))
            "node" (draw-node ctx item sel? text?)
            nil))))))
```

`paint!` keeps its canvas sizing/clear/transform code and ends with:

```clojure
    (let [k (:k view)
          vr {:x0 (/ (- 0 (:x view)) k) :y0 (/ (- 0 (:y view)) k)
              :x1 (/ (- (.-clientWidth canvas-el) (:x view)) k)
              :y1 (/ (- (.-clientHeight canvas-el) (:y view)) k)}]
      (paint-items! ctx sc2 vr k selected-id))
```

- [ ] **Step 2: Add `export-canvas`** (public, after `paint!`)

```clojure
(defn export-canvas
  "Offscreen canvas with the WHOLE scene at up to 2x scale (capped so
  the larger pixel dimension stays <= 8000), on the current theme's
  background. No selection ring."
  [sc]
  (let [w (js/Math.max 1 (:width sc))
        h (js/Math.max 1 (:height sc))
        k (js/Math.min 2 (/ 8000 (js/Math.max w h)))
        cnv (js/document.createElement "canvas")
        ctx (.getContext cnv "2d")]
    (set! (.-width cnv) (js/Math.ceil (* w k)))
    (set! (.-height cnv) (js/Math.ceil (* h k)))
    (set! (.-fillStyle ctx) (:bg @palette))
    (.fillRect ctx 0 0 (.-width cnv) (.-height cnv))
    (.setTransform ctx k 0 0 k 0 0)
    (paint-items! ctx sc {:x0 0 :y0 0 :x1 w :y1 h} k nil)
    cnv))
```

- [ ] **Step 3: Button + download in `src/simpleviz/app.cljs`**

Add after `toggle-theme!` (needs nothing later than the state atom; the view references it, and views resolve at render time — but keep definition before `app-view` for squint ordering):

```clojure
(defn- export-png! []
  (when-let [sc (:scene @state)]
    (let [nm (let [f (:file (:graph @state))]
               (if (some? f) (.replace f (js/RegExp. "\\.edn$") "") "graph"))
          cnv (canvas/export-canvas sc)]
      (.toBlob cnv
               (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       a (js/document.createElement "a")]
                   (set! (.-href a) url)
                   (set! (.-download a) (str nm ".png"))
                   (.click a)
                   (js/setTimeout (fn [] (js/URL.revokeObjectURL url)) 1000)))
               "image/png"))))
```

In `app-view`, add the button right before the theme toggle:

```clojure
   [:button {:id "export-btn" :type "button" :title "Export PNG"
             :on-click (fn [e] (.stopPropagation e) (export-png!))}
    "⇩"]
```

- [ ] **Step 4: CSS + pan-zoom exclusion**

`public/style.css`: copy `#theme-toggle`'s rule block for `#export-btn`, offset left of it (theme toggle's `right` value + 40px; read the actual rule and match its styling verbatim, only changing `right`).

`src/simpleviz/canvas.cljs`: both `closest` selector strings gain `, #export-btn`.

- [ ] **Step 5: Full suites**

Run: `bb build && bb test:js && bb test:clj`
Expected: PASS (refactor must not disturb any existing test).

- [ ] **Step 6: Manual verification** (serve demo pair on a spare port; browser needed — defer visuals to the human, but verify compile-level: compiled `app.mjs` contains `export-btn` and `export_png`)

Visual checklist for the human: button next to theme toggle in both themes; click downloads `demo-next.png` (compare) / `demo.png` (single); image shows the whole graph incl. diff styling in compare mode; live canvas rendering unchanged (pan/zoom/select all normal).

- [ ] **Step 7: Commit**

```bash
git add src/simpleviz/canvas.cljs src/simpleviz/app.cljs public/style.css
git commit -m "feat: export the diagram as a PNG from the viewer"
```
