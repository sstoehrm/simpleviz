# Compare-Mode Change Cycling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In compare mode, each legend row shows its change count and cycles the viewport through that status's elements on click.

**Architecture:** Pure stop-list computation in `scene.cljs` (`diff-stops`), status-set roll-up in `prune.cljs` (`:diff-inside` becomes a sorted status array, nil when empty), a `center-on!` viewport helper in `canvas.cljs`, and button-ized legend rows with per-status cursors in `app.cljs`. Server untouched. Spec: `docs/superpowers/specs/2026-08-09-diff-cycling-design.md`.

**Tech Stack:** squint ClojureScript (maps are JS objects, `filterv`/`mapv` return JS arrays; keep each file's idioms — mutable JS accumulators in scene/prune hot paths). Tests: `bb build && bb test:js`; single file via `node --test public/js/simpleviz/<name>_test.mjs`.

## Global Constraints

- `:diff-inside` is now either **nil/absent** or a **sorted JS array of status strings** — never an empty array (empty arrays are truthy in JS and would break the canvas dot check and stop expansion). Enforce at the producer (prune), not the consumers.
- Statuses are exactly `"added"`, `"modified"`, `"removed"`.
- Edge labels (`"edge-label"` kind) are never stops.
- A shell whose own `:diff` equals a status it also hides appears ONCE in that status's stop list.
- Single-file mode renders zero diff UI (legend absent), exactly as today; all existing tests keep passing (the two prune `assert/ok` truthiness tests still hold: non-empty array truthy, nil falsy).
- Per-status cursors reset whenever a new scene is installed.
- Zoom on jump: never lowered; raised only when the item would render under 40px in its larger bbox dimension; capped at 1.0.
- Commit style `feat:`/`docs:` as in git history.

---

### Task 1: `prune.cljs` — status-set roll-up

**Files:**
- Modify: `src/simpleviz/prune.cljs`
- Modify: `test/simpleviz/prune_test.cljs`

**Interfaces:**
- Consumes: existing `mark-dead`, graph elements with `:diff`.
- Produces: private `contents-changed` (renamed from `contents-changed?`): `[graph box-name]` → sorted JS array of hidden statuses, or nil. `collapse-boxes` and `collapse-scene` store it under `:diff-inside`. Canvas's existing `(and (:collapsed item) (:diff-inside item))` dot check keeps working unchanged (nil vs non-empty array).

- [ ] **Step 1: Update/extend the tests**

In `test/simpleviz/prune_test.cljs`, extend the existing roll-up test with exact-value assertions and a mixed-status case. Replace the test `"collapsed box rolls up hidden diffs as :diff-inside"` with:

```clojure
(test "collapsed box rolls up hidden diff statuses as :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner")) ["added"]))
    ;; nested: change inside inner, collapse outer
    (let [raw (assoc-in (graph) [:nodes "b" :diff] "removed")
          g (collapse-boxes raw #{"outer"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "outer")) ["removed"]))
    ;; multiple statuses: sorted array
    (let [raw (-> (graph)
                  (assoc-in [:nodes "b" :diff] "removed")
                  (assoc-in [:nodes "d" :diff] "added"))
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner"))
                        ["added" "removed"]))
    ;; no changes -> nil, not []
    (let [g (collapse-boxes (graph) #{"inner"})]
      (assert/ok (nil? (:diff-inside (get (:boxes-by-name g) "inner")))))))
```

Extend the interior-edge test's assertion (same test name, replace the body's assert):

```clojure
(test "fully-interior diff edge sets :diff-inside"
  (fn []
    (let [raw (update (graph) :edges
                      (fn [es] (mapv (fn [e] (if (= (:id e) "e2")
                                               (assoc e :diff "removed")
                                               e))
                                     es)))
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner")) ["removed"]))))
```

And the collapse-scene test:

```clojure
(test "collapse-scene marks freshly collapsed shells with :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          sc {:items [{:kind "box" :id "b:inner"}
                      {:kind "box" :id "b:outer"}
                      {:kind "node" :id "n:b"}]}
          out (collapse-scene sc raw #{"inner"})
          shell (first (filterv (fn [it] (= (:id it) "b:inner")) (:items out)))]
      (assert/ok (:collapsed shell))
      (assert/deepEqual (:diff-inside shell) ["added"]))))
```

- [ ] **Step 2: Run to verify the new assertions fail**

Run: `bb build && node --test public/js/simpleviz/prune_test.mjs`
Expected: FAIL — deepEqual against `true` (current boolean).

- [ ] **Step 3: Implement**

In `src/simpleviz/prune.cljs`, replace `contents-changed?` with:

```clojure
(defn- contents-changed
  "Sorted array of the :diff statuses present in box b's transitive
  contents (member nodes, nested boxes, edges wholly inside); nil when
  none — never an empty array (empty arrays are truthy in JS and the
  canvas dot / stop expansion rely on nil = no hidden changes)."
  [graph b]
  (let [{bs' :boxes ns' :nodes} (mark-dead graph [b])
        acc (js/Set.)]
    (doseq [nm (js/Array.from bs')]
      (let [d (:diff (get (:boxes-by-name graph) nm))]
        (when (and (not= nm b) (some? d)) (.add acc d))))
    (doseq [n (js/Array.from ns')]
      (let [d (:diff (get (:nodes graph) n))]
        (when (some? d) (.add acc d))))
    (doseq [e (:edges graph)]
      (when (and (some? (:diff e))
                 (.has ns' (:source e))
                 (.has ns' (:target e)))
        (.add acc (:diff e))))
    (when (pos? (.-size acc))
      (.sort (js/Array.from acc)))))
```

Update the two call sites (collapsed-shell branch in `collapse-boxes`, shell-marking `.map` in `collapse-scene`) from `(contents-changed? …)` to `(contents-changed …)` — the stored key stays `:diff-inside`.

- [ ] **Step 4: Run the full JS suite**

Run: `bb build && bb test:js`
Expected: PASS — including the scene pass-through test (`:diff-inside true` in its fixture is opaque data the scene copies; it still passes) and canvas compile.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/prune.cljs test/simpleviz/prune_test.cljs
git commit -m "feat: roll up hidden diff statuses as a set on collapsed shells"
```

---

### Task 2: `scene.cljs` — pure `diff-stops`

**Files:**
- Modify: `src/simpleviz/scene.cljs`
- Modify: `test/simpleviz/scene_test.cljs`

**Interfaces:**
- Consumes: scene items with `:kind`, `:diff`, `:diff-inside` (Task 1's array-or-nil).
- Produces: `scene/diff-stops [sc]` → `{"added" <JS array of items> "modified" … "removed" …}`; nil-safe for a nil scene (returns empty arrays).

- [ ] **Step 1: Write the failing tests** (append to `test/simpleviz/scene_test.cljs`)

```clojure
(test "diff-stops groups visible items by status"
  (fn []
    (let [items [{:kind "node" :id "n:a" :diff "added"}
                 {:kind "node" :id "n:b"}
                 {:kind "edge" :id "e0" :diff "removed"}
                 {:kind "edge-label" :id "e0-label" :diff "removed"}
                 {:kind "box" :id "b:x" :diff "modified"}]
          stops (scene/diff-stops {:items items})]
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "added")) ["n:a"])
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "modified")) ["b:x"])
      ;; edge labels are never stops
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "removed")) ["e0"]))))

(test "diff-stops expands collapsed shells once per hidden status"
  (fn []
    (let [shell {:kind "box" :id "b:s" :diff "modified"
                 :collapsed true :diff-inside ["modified" "removed"]}
          stops (scene/diff-stops {:items [shell]})]
      ;; own :diff and hidden "modified" dedupe to ONE stop
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "modified")) ["b:s"])
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "removed")) ["b:s"])
      (assert/deepEqual (get stops "added") []))))

(test "diff-stops is nil-safe"
  (fn []
    (assert/deepEqual (get (scene/diff-stops nil) "added") [])))
```

- [ ] **Step 2: Run to verify they fail**

Run: `bb build && node --test public/js/simpleviz/scene_test.mjs`
Expected: FAIL — `diff-stops` undefined.

- [ ] **Step 3: Implement** (append to `src/simpleviz/scene.cljs`)

```clojure
(defn diff-stops
  "Cycle stops per diff status: {\"added\" [items] ...}. Nodes, boxes and
  edges whose :diff matches; collapsed shells additionally stop for every
  status in :diff-inside (deduped against their own :diff). Edge labels
  are never stops. Nil-safe for a missing scene."
  [sc]
  (let [acc {"added" (js/Array.) "modified" (js/Array.) "removed" (js/Array.)}]
    (doseq [it (or (:items sc) [])]
      (when (not= (:kind it) "edge-label")
        (let [d (:diff it)]
          (when (some? (get acc d)) (.push (get acc d) it))
          (doseq [s (or (:diff-inside it) [])]
            (when (and (not= s d) (some? (get acc s)))
              (.push (get acc s) it))))))
    acc))
```

- [ ] **Step 4: Run the full JS suite**

Run: `bb build && bb test:js`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/scene.cljs test/simpleviz/scene_test.cljs
git commit -m "feat: pure diff-stops grouping for change cycling"
```

---

### Task 3: `canvas.cljs` + `app.cljs` + CSS — center-on! and legend cycling

**Files:**
- Modify: `src/simpleviz/canvas.cljs`
- Modify: `src/simpleviz/app.cljs`
- Modify: `public/style.css`

**Interfaces:**
- Consumes: `scene/diff-stops` (Task 2), items with `:bbox {:x0 :y0 :x1 :y1}`, existing `item->payload`, `canvas/view`, `canvas/request-paint!`.
- Produces: `canvas/center-on! [item]`; app state key `:diff-cursors {}` (status → last-jumped index); legend rows as buttons showing `count` or `idx+1/count`.

- [ ] **Step 1: `canvas/center-on!`** (add after `fit-view-once!`)

```clojure
(defn center-on!
  "Pan so the item's bbox center is view-centered. Zoom is raised (never
  lowered, capped at 1.0) only when the item would render under 40px in
  its larger dimension."
  [item]
  (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
        bb (:bbox item)
        cx (/ (+ (:x0 bb) (:x1 bb)) 2)
        cy (/ (+ (:y0 bb) (:y1 bb)) 2)
        dim (js/Math.max (- (:x1 bb) (:x0 bb)) (- (:y1 bb) (:y0 bb)))
        k0 (:k view)
        k (if (< (* dim k0) 40)
            (js/Math.min 1.0 (js/Math.max k0 (/ 40 dim)))
            k0)]
    (assoc! view
            :k k
            :x (- (/ (.-width rect) 2) (* cx k))
            :y (- (/ (.-height rect) 2) (* cy k)))
    (request-paint!)))
```

- [ ] **Step 2: app state + cursor reset**

In `src/simpleviz/app.cljs`:
- Add `:diff-cursors {}` to the initial `state` map.
- In `relayout!`, add `:diff-cursors {}` to BOTH `swap! state assoc` calls that install `:scene` (the cache-hit branch and the fresh-layout branch). Reload and collapse/expand flow through `relayout!`, so these two cover all scene installs.

- [ ] **Step 3: cycling + legend rows**

Ordering matters (squint resolves symbols top-to-bottom; the file uses `declare` only for `relayout!`): DELETE `legend-view` from its current position (before `item->payload`) and re-add it BELOW the two new functions, which themselves go right after `item->payload`. Final order: `item->payload` → `cycle-diff!` → `legend-row` → `legend-view` → (unchanged) `canvas-view` … `app-view`. `simpleviz.scene :as scene` is already required:

```clojure
(defn- cycle-diff! [status]
  (let [stops (get (scene/diff-stops (:scene @state)) status)]
    (when (pos? (.-length stops))
      (let [idx (mod (inc (get (:diff-cursors @state) status -1))
                     (.-length stops))
            item (nth stops idx)]
        (swap! state (fn [st]
                       (-> st
                           (assoc-in [:diff-cursors status] idx)
                           (assoc :selected (item->payload item)))))
        (canvas/center-on! item)))))

(defn- legend-row [st status glyph cls stops]
  (let [n (.-length stops)
        idx (get (:diff-cursors st) status)]
    [:button {:key status :type "button"
              :class (str "dl-row" (if (zero? n) " dl-empty" ""))
              :disabled (zero? n)
              :title (if (zero? n)
                       (str "no " status " elements")
                       (str "jump to the next " status " element"))
              :on-click (fn [e] (.stopPropagation e) (cycle-diff! status))}
     [:span {:class (str "dl-key " cls)} glyph]
     [:span {:class "dl-label"} status]
     [:span {:class "dl-count"}
      (if (some? idx) (str (inc idx) "/" n) (str n))]]))
```

Replace `legend-view` (and its call site — it now takes the whole state):

```clojure
(defn- legend-view [st]
  (when-let [cmp (:compare (:graph st))]
    (let [stops (scene/diff-stops (:scene st))]
      [:div {:id "diff-legend"}
       [:div {:class "dl-files"} (str (:old cmp) " → " (:new cmp))]
       (legend-row st "added" "+" "dl-added" (get stops "added"))
       (legend-row st "modified" "~" "dl-modified" (get stops "modified"))
       (legend-row st "removed" "−" "dl-removed" (get stops "removed"))])))
```

In `app-view`, change the call to `(when (some? (:graph st)) (legend-view st))`.

- [ ] **Step 4: CSS** (replace the `.dl-row` rule in `public/style.css`, add the new classes after it)

```css
#diff-legend .dl-row { display: flex; align-items: center; gap: 6px; margin-top: 2px;
                       width: 100%; background: none; border: none; padding: 2px 4px;
                       font: inherit; color: inherit; text-align: left;
                       border-radius: 4px; cursor: pointer; }
#diff-legend .dl-row:hover { background: var(--hover); }
#diff-legend .dl-row.dl-empty { opacity: .45; cursor: default; }
#diff-legend .dl-row.dl-empty:hover { background: none; }
#diff-legend .dl-label { flex: 1; }
#diff-legend .dl-count { color: var(--text-muted); font-variant-numeric: tabular-nums; }
```

- [ ] **Step 5: Full suite**

Run: `bb build && bb test:js && bb test:clj`
Expected: PASS (no new unit tests in this task — canvas/app are DOM namespaces).

- [ ] **Step 6: Manual verification** (serve `bb serve examples/demo.edn examples/demo-next.edn` on a spare port; API-level where possible, visual points listed for the human)

Verifiable without a browser: page serves 200; compiled `app.mjs` contains `cycle-diff` and `diff-legend` button markup renders (string-level check on compiled output is acceptable evidence).
Visual (defer to human if no browser tooling): clicking `added` cycles Metrics → emit edge → wraps, count shows `1/2` then `2/2`; `removed` row cycles Mailer/send; a zero row is dimmed and inert; collapsing `backend` makes its shell a stop for the statuses it hides; jumping selects the element and opens the inspector; zoomed-out jumps bump zoom; editing a file resets counters.

- [ ] **Step 7: Commit**

```bash
git add src/simpleviz/canvas.cljs src/simpleviz/app.cljs public/style.css
git commit -m "feat: legend buttons cycle through compare-mode changes"
```
