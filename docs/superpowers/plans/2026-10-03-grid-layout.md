# Grid Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Task graph:** `.blend/specs/2026-10-03-grid-layout-tasks.edn`. Set each task node's `:state` alongside its checkboxes: `:in-progress` when you start it, `:done` once its review is clean, `:blocked` plus a `:reason` string when stuck.

**Goal:** Top-level boxes with `:grid [col row]` / `[col row w h]` are placed on exact grid cells; ELK lays out each box's contents; loose elements sit beside the box they connect to; edges between cells run orthogonally through the gaps.

**Architecture:** The server validates `:grid` and puts a normalized `{:col :row :w :h}` on each box. A new pure namespace `src/simpleviz/grid.cljs` turns a graph into an ELK-shaped layout (children at their places, every edge at the root with absolute points) using one ELK run per top-level box (ports on the sides facing the other ends) plus its own grid sizing and gap router. `relayout!` calls it instead of the single ELK run when any box is gridded; scene, hit-testing, canvas and export stay unchanged.

**Tech Stack:** babashka (`server/graph.clj`, `clojure.test`), squint ClojureScript (`node:test`), elkjs (vendored `public/vendor/elk.bundled.js`).

**Spec:** `docs/superpowers/specs/2026-10-03-grid-layout-design.md`

## Global Constraints

- `:grid [col row]` or `[col row w h]`, integers, `col`/`row` ≥ 0, `w`/`h` ≥ 1 (default 1); top-level boxes only; overlap → the box first by sorted name keeps its cells.
- Warning texts, verbatim:
  - `box "<name>": :grid only applies to top-level boxes, ignored`
  - `box "<name>": :grid must be [col row] or [col row w h] (integers, col/row ≥ 0, w/h ≥ 1), ignored`
  - `box "<name>": :grid <value> overlaps box "<other>", ignored`
- No gridded box → today's layout, byte-for-byte the same code path.
- Numbers: outer margin 20 px; gap between columns/rows 80 px; outer routing gap 30 px; lane spacing 10 px; a gap with n lanes is at least `n × 10 + 40` px; stack spacing 20 px.
- Boxes sit top-left in their cell at their own size.
- Loose attachment: most edges (to anything inside the box) wins; ties → box first by sorted elk id; side = left when the loose element is the source of more of those edges than the target, else right; chains follow the attached neighbour with most edges; the rest form the strip.
- Ports: EAST/WEST when the other end's columns lie entirely right/left, else SOUTH/NORTH by rows, same slot → EAST.
- Routing: right angles only; fewest bends, then shortest; lanes per gap ordered by where each edge uses the gap, then id.
- The grid layout's result has the shape `scene/build-scene` reads: `{:id "root" :width :height :children [...] :edges [{:id :container "root" :sections [{:startPoint :bendPoints :endPoint}] :labels [...]}]}`.
- squint: keywords are strings at runtime; maps are JS objects (string keys — use `(str i "," j)` composite keys, never vectors as keys); use `js/Set`/`.has` for sets; compare arrays via `js/JSON.stringify`, not `=`; stick to idioms in `src/simpleviz/*.cljs` (`when-let`, `assoc!` on local maps, `cond->`); `undefined` ≠ `nil` under `=` — normalize with `(or x nil)`.
- Every commit message ends with the trailer `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **A grid with a single row or column** — routing must still find a path (through the outer gaps), not throw or return nothing. Pinned in Task 4 (router test "one row: around through the outer gap").
2. **An edge between two members of one loose stack** — same slot, both EAST; must route out and back, not produce a zero-length or diagonal path. Pinned in Task 4 (direct same-gap test) and Task 6 (real ELK).
3. **A collapsed gridded box, and an empty box** — leaves without an ELK run; edges attach to their borders. Pinned in Task 6.
4. **Compare mode with a removed box on a cell a new box took** — overlap resolved client-side (later by sorted name becomes loose), no crash. Pinned in Task 2 (`grid-cells` overlap test) and checked in Task 7.
5. **Seeded relayout after an edit inside a gridded box** — contents stay put, the box stays in its cell. Pinned in Task 6 (layout-grid with positions) and Task 7.

---

### Task 1: Server — validate `:grid`, normalized `:grid` on boxes

**Files:**
- Modify: `server/graph.clj` (new fns before `normalize` ~line 384; `normalize`)
- Test: `test/graph_test.clj`, `test/diff_test.clj`

**Interfaces:**
- Produces: every normalized box map has `:grid` — `{:col :row :w :h}` or nil — in `graph/normalize` output (and thus in `/api/graph` JSON as `"grid"`). `:grid` stays in `:attrs`.

- [ ] **Step 1: Write the failing tests** — append to `test/graph_test.clj`:

```clojure
(defn- boxes-g [boxes & [extra]]
  (graph/normalize (merge {:nodes {:a {} :b {} :c {}} :boxes boxes} extra)))

(defn- grid-of [g nm] (:grid (first (filter (fn [b] (= nm (:name b))) (:boxes g)))))

(deftest grid-accepts-both-forms
  (let [g (boxes-g {:x {:grid [0 1] :components #{:a}} :y {:grid [1 0 2 3] :components #{:b}}})]
    (is (= {:col 0 :row 1 :w 1 :h 1} (grid-of g "x")))
    (is (= {:col 1 :row 0 :w 2 :h 3} (grid-of g "y")))
    (is (= [0 1] (get-in (first (filter (fn [b] (= "x" (:name b))) (:boxes g))) [:attrs :grid])))
    (is (= [] (:warnings g)))))

(deftest grid-absent-is-nil
  (let [g (boxes-g {:x {:components #{:a}}})]
    (is (nil? (grid-of g "x")))
    (is (= [] (:warnings g)))))

(deftest grid-on-a-nested-box-warns
  (let [g (boxes-g {:outer {:components #{:inner}} :inner {:grid [0 0] :components #{:a}}})]
    (is (nil? (grid-of g "inner")))
    (is (= ["box \"inner\": :grid only applies to top-level boxes, ignored"] (:warnings g)))))

(deftest grid-malformed-values-warn
  (doseq [v [[0] [0 0 1] [-1 0] [0 0 0 1] [0 0 1 0] [0.5 0] ["0" 0] :x {:col 0}]]
    (let [g (boxes-g {:x {:grid v :components #{:a}}})]
      (is (nil? (grid-of g "x")) (pr-str v))
      (is (= ["box \"x\": :grid must be [col row] or [col row w h] (integers, col/row ≥ 0, w/h ≥ 1), ignored"]
             (:warnings g)) (pr-str v)))))

(deftest grid-overlap-keeps-the-first-by-sorted-name
  (let [g (boxes-g {:zeta {:grid [0 0 2 1] :components #{:a}} :alpha {:grid [1 0] :components #{:b}}
                    :mid {:grid [0 1] :components #{:c}}})]
    (is (= {:col 1 :row 0 :w 1 :h 1} (grid-of g "alpha")))
    (is (nil? (grid-of g "zeta")))
    (is (= {:col 0 :row 1 :w 1 :h 1} (grid-of g "mid")))
    (is (= ["box \"zeta\": :grid [0 0 2 1] overlaps box \"alpha\", ignored"] (:warnings g)))))
```

append to `test/diff_test.clj`:

```clojure
(deftest union-boxes-carry-the-grid-of-their-side
  (let [g (u {:nodes {:a {} :b {}} :boxes {:kept {:grid [0 0] :components #{:a}}
                                           :gone {:grid [1 0] :components #{:b}}}}
             {:nodes {:a {} :b {}} :boxes {:kept {:grid [2 0] :components #{:a}}}})
        by (into {} (map (juxt :name identity)) (:boxes g))]
    (is (= {:col 2 :row 0 :w 1 :h 1} (:grid (get by "kept"))))
    (is (= "modified" (:diff (get by "kept"))))
    (is (= {:col 1 :row 0 :w 1 :h 1} (:grid (get by "gone"))))
    (is (= "removed" (:diff (get by "gone"))))))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb test:clj 2>&1 | grep -E "^(FAIL|ERROR) in|^Ran|failures"`
Expected: failures in the five `grid-*` tests and `union-boxes-carry-the-grid-of-their-side` (`:grid` is nil everywhere).

- [ ] **Step 3: Implement** — in `server/graph.clj`, before `(defn normalize`:

```clojure
(defn- grid-cell
  "{:col :row :w :h} for a well-formed :grid value, else nil."
  [v]
  (when (and (vector? v) (contains? #{2 4} (count v)) (every? integer? v))
    (let [[col row w h] (if (= 2 (count v)) (conj v 1 1) v)]
      (when (and (>= col 0) (>= row 0) (>= w 1) (>= h 1))
        {:col col :row row :w w :h h}))))

(defn- resolve-grids
  "Each box with its :grid as {:col :row :w :h}, or nil: only top-level
  boxes, well-formed values, and — where cells overlap — the box first
  by sorted name keeps them. Everything else warns and is ignored."
  [boxes parent-of warn!]
  (let [raw (into {} (map (fn [b] [(:name b) (get-in b [:attrs :grid])])) boxes)
        cells (into {}
                    (keep (fn [b]
                            (let [v (get raw (:name b))]
                              (when (some? v)
                                (cond
                                  (some? (get parent-of (:id b)))
                                  (do (warn! (str "box \"" (:name b) "\": :grid only applies to top-level boxes, ignored"))
                                      nil)
                                  (nil? (grid-cell v))
                                  (do (warn! (str "box \"" (:name b) "\": :grid must be [col row] or [col row w h]"
                                                  " (integers, col/row ≥ 0, w/h ≥ 1), ignored"))
                                      nil)
                                  :else [(:name b) (grid-cell v)])))))
                    boxes)
        kept (first
              (reduce (fn [[kept taken] nm]
                        (let [{:keys [col row w h]} (get cells nm)
                              ks (for [c (range col (+ col w)) r (range row (+ row h))] [c r])]
                          (if-let [other (some taken ks)]
                            (do (warn! (str "box \"" nm "\": :grid " (pr-str (get raw nm))
                                            " overlaps box \"" other "\", ignored"))
                                [kept taken])
                            [(assoc kept nm (get cells nm)) (into taken (map (fn [k] [k nm])) ks)])))
                      [{} {}]
                      (sort (keys cells))))]
    (mapv (fn [b] (assoc b :grid (get kept (:name b)))) boxes)))
```

In `normalize`, change the result map's `:boxes boxes` to `:boxes (resolve-grids boxes parent-of warn!)` — the call must run before `:warnings @warnings` is read, so bind it first: add `boxes (resolve-grids boxes parent-of warn!)` to the `let` right after `edges (drop-containment-edges …)`.

`server/diff.clj` needs no change: `diff-boxes` keeps the new side's box map for kept/added boxes and the old side's for removed ones, `:grid` included; the attribute diff already reports a changed `:grid`.

- [ ] **Step 4: Run the tests**

Run: `bb test:clj 2>&1 | grep -E "^(FAIL|ERROR) in|^Ran|failures"`
Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git add server/graph.clj test/graph_test.clj test/diff_test.clj
git commit -m "feat(graph): validate :grid on top-level boxes, normalized :grid on each box"
```

---

### Task 2: `grid.cljs` — structure: cells, top-level items, loose attachment

**Files:**
- Create: `src/simpleviz/grid.cljs`
- Test: `test/simpleviz/grid_test.cljs`

**Interfaces:**
- Consumes: `simpleviz.editor/top-box-of`; graph shape `{:nodes {id {:id}} :boxes [{:name :grid :components}] :parent-of {elk-id box-name} :edges [{:source :target :source-id? :target-id?}]}` (as `transform/to-elk` gets it); box `:grid` from Task 1.
- Produces (public):
  - `(top-of parent-of elk-id)` → elk id of the top-level element containing it
  - `(edge-ends e)` → `[source-elk-id target-elk-id]`
  - `(top-items graph)` → elk ids of top-level elements, nodes then boxes, payload order
  - `(grid-cells graph)` → `{elk-id {:col :row :w :h}}`
  - `(grid-mode? graph)` → boolean
  - `(attach-loose graph cells)` → `{:attached {elk-id {:anchor elk-id :side "left"|"right"}} :strip [elk-id …]}`

- [ ] **Step 1: Write the failing tests** — create `test/simpleviz/grid_test.cljs`:

```clojure
(ns simpleviz.grid-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.grid :refer [top-of edge-ends top-items grid-cells grid-mode? attach-loose]]))

(defn- n [id] {:id id :name id :type "" :attrs {}})
(defn- box [nm grid comps] {:name nm :grid grid :components comps :type "" :attrs {}})
(defn- e [s t] {:id (str s ">" t) :source s :target t})

;; front [0 0] holds web, back [1 0] holds api; user → web (left of
;; front), api → mail (right of back), x2 hangs off mail, lone and the
;; p1–p2 pair reach no gridded box
(def g
  {:nodes {"user" (n "user") "web" (n "web") "api" (n "api") "mail" (n "mail")
           "x2" (n "x2") "lone" (n "lone") "p1" (n "p1") "p2" (n "p2")}
   :boxes [(box "front" {:col 0 :row 0 :w 1 :h 1} ["n:web"])
           (box "back" {:col 1 :row 0 :w 1 :h 1} ["n:api"])]
   :parent-of {"n:web" "front" "n:api" "back"}
   :edges [(e "user" "web") (e "api" "mail") (e "x2" "mail") (e "p1" "p2")]})

(test "top-of and edge-ends name top-level elk ids"
  (fn []
    (assert/equal (top-of (:parent-of g) "n:web") "b:front")
    (assert/equal (top-of (:parent-of g) "n:user") "n:user")
    (assert/equal (top-of (:parent-of g) "b:front") "b:front")
    (assert/deepEqual (edge-ends (e "user" "web")) ["n:user" "n:web"])
    (assert/deepEqual (edge-ends {:source "a" :target "x" :source-id "b:a"}) ["b:a" "n:x"])))

(test "top-items lists top-level nodes then boxes"
  (fn []
    (assert/deepEqual (top-items g) ["n:user" "n:mail" "n:x2" "n:lone" "n:p1" "n:p2" "b:front" "b:back"])))

(test "grid-cells keeps top-level gridded boxes, first by sorted name on overlap"
  (fn []
    (assert/deepEqual (js/Object.keys (grid-cells g)) ["b:back" "b:front"])
    (assert/ok (grid-mode? g))
    (assert/ok (not (grid-mode? (assoc g :boxes [(box "front" nil ["n:web"])]))))
    (let [o (assoc g :boxes [(box "zeta" {:col 0 :row 0 :w 2 :h 1} ["n:web"])
                             (box "alpha" {:col 1 :row 0 :w 1 :h 1} ["n:api"])])]
      (assert/deepEqual (js/Object.keys (grid-cells o)) ["b:alpha"]))
    ;; a gridded box inside another box is not a cell
    (let [nested (assoc g :parent-of (assoc (:parent-of g) "b:back" "front"))]
      (assert/deepEqual (js/Object.keys (grid-cells nested)) ["b:front"]))))

(test "attach-loose: beside the box with most edges, chains, the rest in the strip"
  (fn []
    (let [{:keys [attached strip]} (attach-loose g (grid-cells g))]
      (assert/deepEqual (get attached "n:user") {:anchor "b:front" :side "left"})
      (assert/deepEqual (get attached "n:mail") {:anchor "b:back" :side "right"})
      (assert/deepEqual (get attached "n:x2") {:anchor "b:back" :side "right"})
      (assert/deepEqual strip ["n:lone" "n:p1" "n:p2"]))))

(test "attach-loose: a tie goes to the box first by sorted id"
  (fn []
    (let [t (-> g
                (assoc-in [:nodes "t"] (n "t"))
                (assoc :edges [(e "t" "web") (e "api" "t")]))
          {:keys [attached]} (attach-loose t (grid-cells t))]
      ;; one edge each way: b:back < b:front; t is the target of api → right
      (assert/deepEqual (get attached "n:t") {:anchor "b:back" :side "right"}))))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)|does not provide|Cannot find"`
Expected: `grid_test.mjs` fails to load (module `./grid.mjs` missing).

- [ ] **Step 3: Implement** — create `src/simpleviz/grid.cljs`:

```clojure
(ns simpleviz.grid
  "Grid layout (:grid on top-level boxes): gridded boxes on exact cells,
  ELK inside each box, loose elements beside the box they connect to,
  edges between cells routed through the gaps. Pure: ELK is passed in."
  (:require [simpleviz.editor :refer [top-box-of]]))

(def MARGIN 20)
(def GAP 80)
(def OUTER-GAP 30)
(def LANE 10)
(def STACK-GAP 20)

;; ---- structure ----

(defn top-of
  "The top-level element (elk id) that contains elk id `id`, or `id`."
  [parent-of id]
  (if-let [b (top-box-of parent-of id)] (str "b:" b) id))

(defn edge-ends
  "An edge's [source target] elk ids."
  [e]
  [(or (:source-id e) (str "n:" (:source e)))
   (or (:target-id e) (str "n:" (:target e)))])

(defn top-items
  "Elk ids of the top-level elements: nodes, then boxes, in the order the
  graph lists them."
  [graph]
  (let [po (:parent-of graph)
        out []]
    (doseq [nd (js/Object.values (:nodes graph))]
      (let [id (str "n:" (:id nd))] (when (nil? (get po id)) (.push out id))))
    (doseq [b (:boxes graph)]
      (let [id (str "b:" (:name b))] (when (nil? (get po id)) (.push out id))))
    out))

(defn grid-cells
  "{elk-id {:col :row :w :h}} of the top-level boxes with a :grid. Where
  cells overlap the box first by sorted name keeps them: the server
  already drops overlaps, but a comparison's union can bring two boxes
  onto one cell."
  [graph]
  (let [po (:parent-of graph)
        taken (js/Set.)
        out {}]
    (doseq [b (sort-by (fn [b] (:name b))
                       (filterv (fn [b] (and (some? (:grid b))
                                             (nil? (get po (str "b:" (:name b))))))
                                (:boxes graph)))]
      (let [{:keys [col row w h]} (:grid b)
            ks (vec (mapcat (fn [c] (mapv (fn [r] (str c "," r)) (range row (+ row h))))
                            (range col (+ col w))))]
        (when-not (some (fn [k] (.has taken k)) ks)
          (doseq [k ks] (.add taken k))
          (assoc! out (str "b:" (:name b)) (:grid b)))))
    out))

(defn grid-mode?
  "Does any top-level box have a grid cell?"
  [graph]
  (pos? (.-length (js/Object.keys (grid-cells graph)))))

(defn attach-loose
  "Where the loose top-level elements go: {:attached {id {:anchor box-id
  :side \"left\"|\"right\"}} :strip [ids]}. A loose element joins the
  gridded box it has the most edges to (ties: first by sorted id) — left
  of it when it is the source of more of those edges than the target,
  else right. Then, until nothing changes, one with no such edge joins
  the attached loose element it has the most edges to, on its anchor and
  side. The rest form the strip, in top-items order."
  [graph cells]
  (let [po (:parent-of graph)
        loose (filterv (fn [id] (nil? (get cells id))) (top-items graph))
        counts {}
        bump! (fn [a b k]
                (let [m (or (get counts a) (let [m {}] (assoc! counts a m) m))
                      c (or (get m b) (let [c {:out 0 :in 0}] (assoc! m b c) c))]
                  (assoc! c k (inc (get c k)))))]
    (doseq [e (:edges graph)]
      (let [[s t] (edge-ends e)
            ts (top-of po s)
            tt (top-of po t)]
        (when (not= ts tt)
          (bump! ts tt :out)
          (bump! tt ts :in))))
    (let [attached {}
          best (fn [id pred]
                 (let [m (or (get counts id) {})]
                   (reduce (fn [acc k]
                             (let [c (get m k)
                                   total (+ (:out c) (:in c))]
                               (if (or (nil? acc) (> total (:n acc))) {:id k :n total :c c} acc)))
                           nil
                           (sort (filterv pred (js/Object.keys m))))))]
      (doseq [id loose]
        (when-let [b (best id (fn [k] (some? (get cells k))))]
          (assoc! attached id {:anchor (:id b)
                               :side (if (> (:out (:c b)) (:in (:c b))) "left" "right")})))
      (loop []
        (let [changed (atom false)]
          (doseq [id loose]
            (when (nil? (get attached id))
              (when-let [b (best id (fn [k] (some? (get attached k))))]
                (assoc! attached id (get attached (:id b)))
                (reset! changed true))))
          (when @changed (recur))))
      {:attached attached
       :strip (filterv (fn [id] (nil? (get attached id))) loose)})))
```

- [ ] **Step 4: Run the tests**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)"`
Expected: `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/grid.cljs test/simpleviz/grid_test.cljs
git commit -m "feat(grid): cells, top-level items and loose attachment"
```

---

### Task 3: `grid.cljs` — geometry: slots, port sides, track sizing

**Files:**
- Modify: `src/simpleviz/grid.cljs` (append)
- Test: `test/simpleviz/grid_test.cljs` (append; extend `:refer`)

**Interfaces:**
- Consumes: `grid-cells`, `attach-loose` output (Task 2).
- Produces:
  - Track numbers: grid column/row `i` is track `3i+1`; a stack left of column `i` is track `3i`, right of it `3i+2`.
  - `(slots cells attached)` → `{elk-id {:c0 :c1 :r0 :r1}}` (track numbers)
  - `(port-side from-slot to-slot)` → `"EAST"|"WEST"|"SOUTH"|"NORTH"`
  - `(tracks n extra)` → sorted vector of track numbers
  - `(base-gaps n)` → n+1 gap widths (`OUTER-GAP` at both ends, `GAP` between)
  - `(track-sizes ts items gaps)` → `{track size}`; items `[{:t0 :t1 :size}]`
  - `(axis ts items gaps)` → `{:ts :size [..] :pos [..] :gaps :end}` (per track index)
  - `(centres ax)` → n+1 gap centre coordinates
  - `(widen gaps lanes prefix)` → gap widths at least `lanes × LANE + 40`; `lanes` `{"v2" 3 …}`, prefix `"v"`/`"h"`

- [ ] **Step 1: Write the failing tests** — add `slots port-side tracks base-gaps track-sizes axis centres widen` to the `:refer`, then append:

```clojure
(test "slots: cells on 3i+1 tracks, stacks beside their anchor's span"
  (fn []
    (let [cells {"b:front" {:col 0 :row 0 :w 1 :h 1} "b:wide" {:col 1 :row 1 :w 2 :h 1}}
          att {"n:user" {:anchor "b:front" :side "left"} "n:out" {:anchor "b:wide" :side "right"}}
          s (slots cells att)]
      (assert/deepEqual (get s "b:front") {:c0 1 :c1 1 :r0 1 :r1 1})
      (assert/deepEqual (get s "b:wide") {:c0 4 :c1 7 :r0 4 :r1 4})
      (assert/deepEqual (get s "n:user") {:c0 0 :c1 0 :r0 1 :r1 1})
      (assert/deepEqual (get s "n:out") {:c0 8 :c1 8 :r0 4 :r1 4}))))

(test "port-side faces the other slot"
  (fn []
    (let [a {:c0 1 :c1 1 :r0 1 :r1 1}]
      (assert/equal (port-side a {:c0 4 :c1 4 :r0 1 :r1 1}) "EAST")
      (assert/equal (port-side a {:c0 0 :c1 0 :r0 1 :r1 1}) "WEST")
      (assert/equal (port-side a {:c0 1 :c1 4 :r0 4 :r1 4}) "SOUTH")
      (assert/equal (port-side {:c0 1 :c1 1 :r0 4 :r1 4} a) "NORTH")
      (assert/equal (port-side a a) "EAST"))))

(test "tracks and base gaps"
  (fn []
    (assert/deepEqual (tracks 2 [5 0]) [0 1 4 5])
    (assert/deepEqual (tracks 3 []) [1 4 7])
    (assert/deepEqual (base-gaps 2) [30 80 30])))

(test "track-sizes: largest single item, spans grow their last track"
  (fn []
    (let [sz (track-sizes [1 4] [{:t0 1 :t1 1 :size 100} {:t0 4 :t1 4 :size 50}
                                 {:t0 1 :t1 4 :size 300}] [30 80 30])]
      (assert/equal (get sz 1) 100)
      ;; 100 + 80 + 50 = 230 < 300: the last track grows by 70
      (assert/equal (get sz 4) 120))
    ;; an empty track stays 0 wide but keeps its gaps
    (let [ax (axis [1 4 7] [{:t0 1 :t1 1 :size 100} {:t0 7 :t1 7 :size 40}] [30 80 80 30])]
      (assert/deepEqual (:size ax) [100 0 40])
      (assert/deepEqual (:pos ax) [50 230 310]))))

(test "axis positions and gap centres"
  (fn []
    (let [ax (axis [1 4] [{:t0 1 :t1 1 :size 100} {:t0 4 :t1 4 :size 50}
                          {:t0 1 :t1 4 :size 300}] [30 80 30])]
      (assert/deepEqual (:size ax) [100 120])
      (assert/deepEqual (:pos ax) [50 230])
      (assert/equal (:end ax) 380)
      (assert/deepEqual (centres ax) [35 190 365]))))

(test "widen makes room for the lanes"
  (fn []
    (assert/deepEqual (widen [30 80 30] {"v1" 6 "h0" 9} "v") [30 100 30])
    (assert/deepEqual (widen [30 80 30] {"v1" 6 "h0" 9} "h") [130 80 30])))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)|does not provide"`
Expected: `grid_test.mjs` fails: `does not provide an export named 'slots'`.

- [ ] **Step 3: Implement** — append to `src/simpleviz/grid.cljs`:

```clojure
;; ---- geometry: tracks are the grid's columns/rows plus loose stacks ----
;; Grid column (row) i is track 3i+1; a stack left of column i is track
;; 3i, right of it 3i+2 — so sorting track numbers orders everything.

(defn slots
  "{id {:c0 :c1 :r0 :r1}} in track numbers: gridded boxes on their cells,
  each loose element in its stack's track beside its anchor's span (left
  of the first column or right of the last), in the anchor's top row."
  [cells attached]
  (let [out {}]
    (doseq [[id {:keys [col row w h]}] (js/Object.entries cells)]
      (assoc! out id {:c0 (+ 1 (* 3 col)) :c1 (+ 1 (* 3 (+ col w -1)))
                      :r0 (+ 1 (* 3 row)) :r1 (+ 1 (* 3 (+ row h -1)))}))
    (doseq [[id {:keys [anchor side]}] (js/Object.entries attached)]
      (let [{:keys [col row w]} (get cells anchor)
            c (if (= side "left") (* 3 col) (+ 2 (* 3 (+ col w -1))))
            r (+ 1 (* 3 row))]
        (assoc! out id {:c0 c :c1 c :r0 r :r1 r})))
    out))

(defn port-side
  "The side of slot `from` that faces slot `to`: EAST/WEST when `to`'s
  columns lie entirely right/left, else SOUTH/NORTH; EAST for the same
  slot."
  [from to]
  (cond
    (> (:c0 to) (:c1 from)) "EAST"
    (< (:c1 to) (:c0 from)) "WEST"
    (> (:r0 to) (:r1 from)) "SOUTH"
    (< (:r1 to) (:r0 from)) "NORTH"
    :else "EAST"))

(defn tracks
  "The sorted track numbers of an axis with `n` grid indices (all of
  them, empty ones too) plus `extra` stack tracks."
  [n extra]
  (vec (sort (distinct (into (mapv (fn [i] (+ 1 (* 3 i))) (range n)) extra)))))

(defn base-gaps
  "Gap widths of an axis with n tracks: before each track and after the
  last; the outer two are routing channels around the grid."
  [n]
  (mapv (fn [i] (if (or (zero? i) (= i n)) OUTER-GAP GAP)) (range (inc n))))

(defn track-sizes
  "Size per track number: the largest item within that track alone;
  then every spanning item, shortest span first, grows its last track by
  what the span lacks (tracks plus the gaps between them)."
  [ts items gaps]
  (let [size {}
        at (fn [t] (.indexOf ts t))]
    (doseq [t ts] (assoc! size t 0))
    (doseq [it items]
      (when (= (:t0 it) (:t1 it))
        (assoc! size (:t0 it) (max (get size (:t0 it)) (:size it)))))
    (doseq [it (sort-by (fn [it] (- (at (:t1 it)) (at (:t0 it))))
                        (filterv (fn [it] (not= (:t0 it) (:t1 it))) items))]
      (let [i0 (at (:t0 it))
            i1 (at (:t1 it))
            have (reduce + 0 (map (fn [i] (+ (get size (nth ts i)) (if (< i i1) (nth gaps (inc i)) 0)))
                                  (range i0 (inc i1))))]
        (when (> (:size it) have)
          (assoc! size (:t1 it) (+ (get size (:t1 it)) (- (:size it) have))))))
    size))

(defn axis
  "Track geometry of one axis: :size and :pos (start) per track index,
  :gaps as given, :end the far edge of the last outer gap."
  [ts items gaps]
  (let [sz (track-sizes ts items gaps)
        n (count ts)
        size (mapv (fn [t] (get sz t)) ts)
        pos (loop [i 0 x (+ MARGIN (nth gaps 0)) acc []]
              (if (< i n)
                (recur (inc i) (+ x (nth size i) (nth gaps (inc i))) (conj acc x))
                acc))]
    {:ts ts :size size :pos pos :gaps gaps
     :end (+ (if (pos? n) (+ (last pos) (last size)) MARGIN) (nth gaps n))}))

(defn centres
  "The centre line of every gap of an axis: gap i lies before track i,
  gap n after the last."
  [{:keys [pos size gaps]}]
  (let [n (count pos)]
    (mapv (fn [i]
            (+ (if (zero? i) MARGIN (+ (nth pos (dec i)) (nth size (dec i))))
               (/ (nth gaps i) 2)))
          (range (inc n)))))

(defn widen
  "Gap widths of an axis grown to fit their lanes (`lanes` counts per gap
  key, \"v3\" / \"h1\"; `prefix` picks the axis)."
  [gaps lanes prefix]
  (vec (map-indexed (fn [i g] (let [n (or (get lanes (str prefix i)) 0)]
                                (max g (+ 40 (* LANE n)))))
                    gaps)))
```

- [ ] **Step 4: Run the tests**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)"`
Expected: `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/grid.cljs test/simpleviz/grid_test.cljs
git commit -m "feat(grid): slots, port sides and track sizing"
```

---

### Task 4: `grid.cljs` — the gap router

**Files:**
- Modify: `src/simpleviz/grid.cljs` (append)
- Test: `test/simpleviz/grid_test.cljs` (append; extend `:refer`)

**Interfaces:**
- Consumes: `centres` output (Task 3) as `vx` (vertical gap centres, column axis) and `hy` (horizontal gap centres, row axis).
- Produces:
  - Entry: `{:x :y :axis "v"|"h" :gap i}` — the port point and the gap it opens onto (`"v"` = a vertical gap, for EAST/WEST; `"h"` for NORTH/SOUTH).
  - `(route-edges routes vx hy)` with `routes [{:id :from entry :to entry}]` → `{:points {id [{:x :y} …]} :lanes {"v1" n "h0" n …}}`; points run from `from` to `to`, right angles only.
  - `(label-at points w h)` → `{:x :y}` top-left of a label on the longest segment.

- [ ] **Step 1: Write the failing tests** — add `route-edges label-at` to the `:refer`, then append:

```clojure
;; two columns, two rows: gap centres from the axis test
(def vx [35 190 365])
(def hy [35 190 365])

(defn- orthogonal? [pts]
  (every? (fn [i] (let [a (nth pts i) b (nth pts (inc i))]
                    (or (= (:x a) (:x b)) (= (:y a) (:y b)))))
          (range (dec (count pts)))))

(test "route-edges: same gap — out, along, in"
  (fn []
    (let [r (route-edges [{:id "e" :from {:x 150 :y 100 :axis "v" :gap 1}
                                   :to {:x 230 :y 120 :axis "v" :gap 1}}] vx hy)]
      (assert/deepEqual (get (:points r) "e")
                        [{:x 150 :y 100} {:x 190 :y 100} {:x 190 :y 120} {:x 230 :y 120}])
      (assert/deepEqual (:lanes r) {"v1" 1}))))

(test "route-edges: fewest bends through a crossing"
  (fn []
    ;; east side of [0 0] to the top of [1 1]
    (let [r (route-edges [{:id "e" :from {:x 150 :y 100 :axis "v" :gap 1}
                                   :to {:x 300 :y 230 :axis "h" :gap 1}}] vx hy)
          pts (get (:points r) "e")]
      (assert/deepEqual pts [{:x 150 :y 100} {:x 190 :y 100} {:x 190 :y 190}
                             {:x 300 :y 190} {:x 300 :y 230}])
      (assert/ok (orthogonal? pts)))))

(test "route-edges: one row — around through the outer gap"
  (fn []
    ;; three columns in one row; east of column 0 to west of column 2:
    ;; column 1 is in the way, so the path uses an outer row gap
    (let [vx3 [35 190 345 500] hy1 [35 190]
          ;; west of column 2 opens onto gap 2
          r (route-edges [{:id "e" :from {:x 150 :y 100 :axis "v" :gap 1}
                                   :to {:x 385 :y 100 :axis "v" :gap 2}}] vx3 hy1)
          pts (get (:points r) "e")]
      (assert/ok (orthogonal? pts))
      (assert/deepEqual (first pts) {:x 150 :y 100})
      (assert/deepEqual (last pts) {:x 385 :y 100})
      (assert/ok (some (fn [p] (or (= (:y p) 35) (= (:y p) 190))) pts) "uses an outer row gap"))))

(test "route-edges: edges sharing a gap get lanes ordered by position"
  (fn []
    (let [r (route-edges [{:id "e1" :from {:x 150 :y 100 :axis "v" :gap 1} :to {:x 230 :y 120 :axis "v" :gap 1}}
                          {:id "e2" :from {:x 150 :y 50 :axis "v" :gap 1} :to {:x 230 :y 60 :axis "v" :gap 1}}]
                         vx hy)]
      (assert/equal (:x (second (get (:points r) "e2"))) 185)
      (assert/equal (:x (second (get (:points r) "e1"))) 195)
      (assert/deepEqual (:lanes r) {"v1" 2}))))

(test "label-at sits on the longest segment"
  (fn []
    (assert/deepEqual (label-at [{:x 0 :y 0} {:x 100 :y 0} {:x 100 :y 20}] 20 10) {:x 40 :y -12})
    (assert/deepEqual (label-at [{:x 0 :y 0} {:x 0 :y 100}] 20 10) {:x 4 :y 45})))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)|does not provide"`
Expected: `does not provide an export named 'route_edges'`.

- [ ] **Step 3: Implement** — append to `src/simpleviz/grid.cljs`:

```clojure
;; ---- routing through the gaps ----
;; Vertices are gap crossings (i, j): vertical gap i (centre vx[i]) meets
;; horizontal gap j (centre hy[j]). A route leaves its port straight into
;; the gap the port faces, runs along gaps, and enters the target's port
;; straight. Cost: bends first, then length.

(def ^:private BEND 1000000)

(defn- band
  "Index k with cs[k] <= v < cs[k+1], within 0 .. (count cs) - 2."
  [cs v]
  (loop [k 0]
    (if (and (< k (- (count cs) 2)) (<= (nth cs (inc k)) v)) (recur (inc k)) k)))

(defn- entry-verts
  "The two crossings on either side of where entry `e` meets its gap."
  [vx hy e]
  (if (= (:axis e) "v")
    (let [k (band hy (:y e))] [[(:gap e) k] [(:gap e) (inc k)]])
    (let [k (band vx (:x e))] [[k (:gap e)] [(inc k) (:gap e)]])))

(defn- find-path
  "Crossings [[i j] ...] of the fewest-bends-then-shortest path from
  entry `from` to entry `to`."
  [vx hy from to]
  (let [nv (count vx)
        nh (count hy)
        dist {}
        prev {}
        done (js/Set.)
        kk (fn [i j h] (str i "," j "," h))
        at (fn [i j] {:x (nth vx i) :y (nth hy j)})
        d (fn [a b] (+ (js/Math.abs (- (:x a) (:x b))) (js/Math.abs (- (:y a) (:y b)))))
        relax! (fn [k c p]
                 (when (or (nil? (get dist k)) (< c (get dist k)))
                   (assoc! dist k c)
                   (assoc! prev k p)))]
    (doseq [[i j] (entry-verts vx hy from)]
      (relax! (kk i j (:axis from)) (+ BEND (d from (at i j))) nil))
    (loop []
      (let [best (reduce (fn [acc k] (if (and (not (.has done k))
                                              (or (nil? acc) (< (get dist k) (get dist acc))))
                                       k acc))
                         nil (js/Object.keys dist))]
        (when (some? best)
          (.add done best)
          (let [[si sj h] (.split best ",")
                i (js/parseInt si)
                j (js/parseInt sj)
                c (get dist best)]
            (doseq [[i2 j2 h2] [[i (dec j) "v"] [i (inc j) "v"] [(dec i) j "h"] [(inc i) j "h"]]]
              (when (and (<= 0 i2) (< i2 nv) (<= 0 j2) (< j2 nh))
                (relax! (kk i2 j2 h2)
                        (+ c (if (= h h2) 0 BEND) (d (at i j) (at i2 j2)))
                        best))))
          (recur))))
    (let [finals (vec (mapcat (fn [[i j]]
                                (keep (fn [h]
                                        (let [k (kk i j h)]
                                          (when (some? (get dist k))
                                            [(+ (get dist k) (if (= h (:axis to)) 0 BEND) BEND (d (at i j) to)) k])))
                                      ["v" "h"]))
                              (entry-verts vx hy to)))
          [_ end] (reduce (fn [a b] (if (or (nil? a) (< (first b) (first a))) b a)) nil finals)]
      (loop [k end acc ()]
        (if (nil? k)
          (vec acc)
          (let [[si sj] (.split k ",")]
            (recur (get prev k) (cons [(js/parseInt si) (js/parseInt sj)] acc))))))))

(defn- corner-of [e]
  (if (= (:axis e) "v") {:vg (:gap e) :y (:y e)} {:hg (:gap e) :x (:x e)}))

(defn route-edges
  "Routes for `routes` [{:id :from entry :to entry}] (entry: {:x :y :axis
  :gap}): {:points {id [{:x :y} ...]} :lanes {gap-key count}}. Edges that
  share a gap get lanes LANE apart, centred, ordered by where they use
  the gap (then id)."
  [routes vx hy]
  (let [corners {}
        users {}]
    (doseq [r routes]
      (let [{:keys [from to]} r
            mid (if (and (= (:axis from) (:axis to)) (= (:gap from) (:gap to)))
                  []
                  (mapv (fn [[i j]] {:vg i :hg j}) (find-path vx hy from to)))
            cs (into (into [(corner-of from)] mid) [(corner-of to)])]
        (assoc! corners (:id r) cs)
        (doseq [c cs]
          (when (some? (:vg c))
            (let [g (str "v" (:vg c))
                  p (if (some? (:y c)) (:y c) (nth hy (:hg c)))
                  m (or (get users g) (let [m {}] (assoc! users g m) m))]
              (assoc! m (:id r) (min p (or (get m (:id r)) p)))))
          (when (some? (:hg c))
            (let [g (str "h" (:hg c))
                  p (if (some? (:x c)) (:x c) (nth vx (:vg c)))
                  m (or (get users g) (let [m {}] (assoc! users g m) m))]
              (assoc! m (:id r) (min p (or (get m (:id r)) p))))))))
    (let [order {}
          lanes {}]
      (doseq [[g m] (js/Object.entries users)]
        ;; by position, then id (sort-by is stable)
        (let [ids (mapv first (sort-by (fn [[_ p]] p) (sort-by (fn [[id _]] id) (js/Object.entries m))))]
          (assoc! order g ids)
          (assoc! lanes g (count ids))))
      (let [off (fn [g id] (let [ids (get order g)]
                             (* LANE (- (.indexOf ids id) (/ (dec (count ids)) 2)))))
            points {}]
        (doseq [r routes]
          (let [id (:id r)
                pts (into (into [{:x (:x (:from r)) :y (:y (:from r))}]
                                (mapv (fn [c]
                                        {:x (if (some? (:vg c)) (+ (nth vx (:vg c)) (off (str "v" (:vg c)) id)) (:x c))
                                         :y (if (some? (:hg c)) (+ (nth hy (:hg c)) (off (str "h" (:hg c)) id)) (:y c))})
                                      (get corners id)))
                          [{:x (:x (:to r)) :y (:y (:to r))}])]
            (assoc! points id pts)))
        {:points points :lanes lanes}))))

(defn label-at
  "Top-left of a w×h label centred on the longest segment of `points`:
  above a horizontal segment, right of a vertical one."
  [points w h]
  (let [segs (mapv (fn [i] [(nth points i) (nth points (inc i))]) (range (dec (count points))))
        len (fn [[a b]] (+ (js/Math.abs (- (:x b) (:x a))) (js/Math.abs (- (:y b) (:y a)))))
        [a b] (reduce (fn [best s] (if (> (len s) (len best)) s best)) (first segs) segs)
        mx (/ (+ (:x a) (:x b)) 2)
        my (/ (+ (:y a) (:y b)) 2)]
    (if (= (:y a) (:y b))
      {:x (- mx (/ w 2)) :y (- my h 2)}
      {:x (+ mx 4) :y (- my (/ h 2))})))
```


- [ ] **Step 4: Run the tests**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)"`
Expected: `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/grid.cljs test/simpleviz/grid_test.cljs
git commit -m "feat(grid): orthogonal router through the gaps, with lanes"
```

---

### Task 5: `transform.cljs` — one element's own ELK run

**Files:**
- Modify: `src/simpleviz/transform.cljs` (after `to-elk`, ~line 100)
- Test: `test/simpleviz/layout_test.cljs` (append; extend `:refer`)

**Interfaces:**
- Consumes: a top-level child of `to-elk`'s output; its root `:layoutOptions`.
- Produces: `(element-run root-options child ports edges)` → ELK input `{:id "root" :layoutOptions (root options, padding 0) :children [child + ports] :edges edges}`; `ports` `[{:id :side}]` become `{:id :width 1 :height 1 :layoutOptions {"elk.port.side" side}}` on the child, which also gets `"elk.portConstraints" "FIXED_SIDE"`.

- [ ] **Step 1: Write the failing test** — add `element-run` to the layout test's `:refer`, then append:

```clojure
(test "element-run lays out one box on its own with ports on its sides"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "") "b" (node "b" "")}
                    :boxes [{:id "b:grp" :name "grp" :type "" :components ["n:a" "n:b"] :attrs {}}]
                    :parent-of {"n:a" "grp" "n:b" "grp"}
                    :edges [(edge 0 "a" "b" {:source false :target true})]})
          elk-g (to-elk g measure)
          box (first (:children elk-g))
          run (element-run (:layoutOptions elk-g) box
                           [{:id "p:x:s" :side "EAST"} {:id "p:y:t" :side "SOUTH"}]
                           (into (:edges elk-g)
                                 [{:id "x:s" :sources ["n:b"] :targets ["p:x:s"]}
                                  {:id "y:t" :sources ["p:y:t"] :targets ["n:a"]}]))]
      (-> (.layout (ELK.) run)
          (.then (fn [r]
                   (let [b (first (:children r))
                         port (fn [id] (first (filterv (fn [p] (= (:id p) id)) (:ports b))))]
                     (assert/equal (:id b) "b:grp")
                     ;; EAST port on the right border, SOUTH on the bottom
                     (assert/ok (>= (:x (port "p:x:s")) (- (:width b) 2)))
                     (assert/ok (>= (:y (port "p:y:t")) (- (:height b) 2)))
                     (assert/equal (.-length (:edges r)) 3))))))))
```

- [ ] **Step 2: Run to see it fail**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)|does not provide"`
Expected: `does not provide an export named 'element_run'`.

- [ ] **Step 3: Implement** — in `src/simpleviz/transform.cljs`, after `to-elk`:

```clojure
(defn element-run
  "ELK input that lays out one top-level element on its own (grid mode):
  `child` — its to-elk node — inside a dummy root (ELK takes no ports on
  the root of a run), with a port on its border per `ports` entry
  [{:id :side}] and `edges` (its inner edges plus those to its ports)."
  [root-options child ports edges]
  {:id "root"
   :layoutOptions (assoc root-options "elk.padding" "[top=0,left=0,bottom=0,right=0]")
   :children [(if (pos? (count ports))
                (assoc child
                       :ports (mapv (fn [p] {:id (:id p) :width 1 :height 1
                                             :layoutOptions {"elk.port.side" (:side p)}})
                                    ports)
                       :layoutOptions (assoc (or (:layoutOptions child) {})
                                             "elk.portConstraints" "FIXED_SIDE"))
                child)]
   :edges edges})
```

- [ ] **Step 4: Run the tests**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)"`
Expected: `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/transform.cljs test/simpleviz/layout_test.cljs
git commit -m "feat(transform): element-run — one top-level element with border ports"
```

---

### Task 6: `grid.cljs` — `layout-grid`, the whole pipeline

**Files:**
- Modify: `src/simpleviz/grid.cljs` (append; add `[simpleviz.transform :refer [element-run layout-positions seed-layout seedable?]]` to `:require`)
- Test: `test/simpleviz/layout_test.cljs` (append; `:require [simpleviz.grid :refer [layout-grid]]` and `[simpleviz.scene :refer [build-scene]]`)

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Produces: `(layout-grid graph elk-graph run-elk positions)` → promise of an ELK-shaped layout (see Global Constraints), tagged `:seeded true` when `positions` was given. `graph` is the collapse-applied graph `to-elk` got, `elk-graph` its `to-elk` output, `run-elk` `(fn [input] promise-of-result)`, `positions` `(layout-positions previous)` or nil.

- [ ] **Step 1: Write the failing tests** — append to `test/simpleviz/layout_test.cljs`:

```clojure
(defn- gbox [nm grid comps] {:id (str "b:" nm) :name nm :type "" :grid grid :components comps :attrs {}})

;; the spec's example: frontend [0 0], backend [1 0], data spans [0 1 2 1];
;; user is loose (points into frontend → left); s1–s2 form the strip
(def grid-g
  (graph {:nodes {"user" (node "user" "") "web" (node "web" "") "api" (node "api" "")
                  "auth" (node "auth" "") "db" (node "db" "") "cache" (node "cache" "")
                  "s1" (node "s1" "") "s2" (node "s2" "")}
          :boxes [(gbox "frontend" {:col 0 :row 0 :w 1 :h 1} ["n:web"])
                  (gbox "backend" {:col 1 :row 0 :w 1 :h 1} ["n:api" "n:auth"])
                  (gbox "data" {:col 0 :row 1 :w 2 :h 1} ["n:db" "n:cache"])]
          :parent-of {"n:web" "frontend" "n:api" "backend" "n:auth" "backend"
                      "n:db" "data" "n:cache" "data"}
          :edges [(edge 0 "user" "web" {:source false :target true})
                  (edge 1 "web" "api" {:source false :target true})
                  (edge 2 "api" "auth" {:source false :target true})
                  (edge 3 "api" "db" {:source false :target true})
                  (edge 4 "api" "cache" {:source false :target true})
                  (edge 5 "s1" "s2" {:source false :target true})]}))

(defn- run-elk [input] (.layout (ELK.) input))

(defn- by-id [layout id] (first (filterv (fn [c] (= (:id c) id)) (:children layout))))

(defn- pts [e] (let [s (first (:sections e))] (into (into [(:startPoint s)] (or (:bendPoints s) [])) [(:endPoint s)])))

(test "layout-grid puts gridded boxes on their cells and routes every edge"
  (fn []
    (-> (layout-grid grid-g (to-elk grid-g measure) run-elk nil)
        (.then (fn [l]
                 (let [fe (by-id l "b:frontend") be (by-id l "b:backend") da (by-id l "b:data")
                       us (by-id l "n:user")]
                   ;; row 0 aligned at the top, backend right of frontend
                   (assert/equal (:y fe) (:y be))
                   (assert/ok (> (:x be) (+ (:x fe) (:width fe))))
                   ;; data under both, starting in column 0
                   (assert/ok (> (:y da) (max (+ (:y fe) (:height fe)) (+ (:y be) (:height be)))))
                   (assert/ok (< (js/Math.abs (- (:x da) (:x fe))) 1))
                   ;; loose user left of frontend, in its row
                   (assert/ok (< (+ (:x us) (:width us)) (:x fe)))
                   (assert/equal (:y us) (:y fe))
                   ;; strip under the grid
                   (assert/ok (> (:y (by-id l "n:s1")) (+ (:y da) (:height da))))
                   ;; every edge routed, all at the root, right angles only
                   (assert/equal (.-length (:edges l)) 6)
                   (doseq [e (:edges l)]
                     (assert/equal (:container e) "root")
                     (let [p (pts e)]
                       (assert/ok (>= (count p) 2) (:id e))
                       (doseq [i (range (dec (count p)))]
                         (let [a (nth p i) b (nth p (inc i))]
                           (assert/ok (or (< (js/Math.abs (- (:x a) (:x b))) 0.01)
                                          (< (js/Math.abs (- (:y a) (:y b))) 0.01))
                                      (str (:id e) " segment " i))))))
                   ;; the scene builder takes it as an ELK result
                   (let [sc (build-scene {:layout l :graph grid-g :colors {:node {} :box {}}})]
                     (assert/equal (.-length (filterv (fn [it] (= (:kind it) "edge")) (:items sc))) 6))))))))

(test "layout-grid: a collapsed gridded box is a leaf in its cell"
  (fn []
    (let [g (-> grid-g
                (assoc :boxes [(gbox "frontend" {:col 0 :row 0 :w 1 :h 1} ["n:web"])
                               (assoc (gbox "backend" {:col 1 :row 0 :w 1 :h 1} []) :collapsed true)
                               (gbox "data" {:col 0 :row 1 :w 2 :h 1} ["n:db" "n:cache"])])
                (assoc :parent-of {"n:web" "frontend" "n:db" "data" "n:cache" "data"})
                (assoc :nodes (dissoc (:nodes grid-g) "api" "auth"))
                (assoc :edges [(edge 0 "user" "web" {:source false :target true})
                               (assoc (edge 1 "web" "backend" {:source false :target true}) :target-id "b:backend")
                               (assoc (edge 3 "backend" "db" {:source false :target true}) :source-id "b:backend")]))
          g (assoc g :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes g)))]
      (-> (layout-grid g (to-elk g measure) run-elk nil)
          (.then (fn [l]
                   (let [be (by-id l "b:backend")]
                     (assert/ok (nil? (:children be)))
                     (assert/equal (.-length (:edges l)) 3)
                     (doseq [e (:edges l)] (assert/ok (>= (count (pts e)) 2))))))))))

(test "layout-grid: seeded by the previous layout, a box keeps its cell and its contents"
  (fn []
    (let [elk-g (to-elk grid-g measure)]
      (-> (layout-grid grid-g elk-g run-elk nil)
          (.then (fn [l1]
                   (-> (layout-grid grid-g elk-g run-elk (layout-positions l1))
                       (.then (fn [l2]
                                (assert/equal (:seeded l2) true)
                                (let [b1 (by-id l1 "b:backend") b2 (by-id l2 "b:backend")]
                                  (assert/equal (:x b2) (:x b1))
                                  (assert/equal (:y b2) (:y b1))
                                  (assert/deepEqual (mapv (fn [c] [(:id c) (:x c) (:y c)]) (:children b2))
                                                    (mapv (fn [c] [(:id c) (:x c) (:y c)]) (:children b1)))))))))))))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)|does not provide"`
Expected: `does not provide an export named 'layout_grid'`.

- [ ] **Step 3: Implement** — extend the `ns` form of `src/simpleviz/grid.cljs`:

```clojure
  (:require [simpleviz.editor :refer [top-box-of]]
            [simpleviz.transform :refer [element-run layout-positions seed-layout seedable?]])
```

and append:

```clojure
;; ---- the pipeline ----

(defn- run-positions
  "The previous layout's positions of top-level element `id` and all it
  contains, relative to the element, which sits at 0,0 under \"root\" —
  the seed for its own ELK run; nil when it was not placed before."
  [positions po id]
  (when-let [p0 (get positions id)]
    (let [out {}]
      (doseq [[k p] (js/Object.entries positions)]
        (when (= (top-of po k) id)
          (assoc! out k (assoc p :x (- (:x p) (:x p0)) :y (- (:y p) (:y p0))
                               :parent (if (= k id) "root" (:parent p))))))
      out)))

(defn- abs-points
  "An ELK edge's points (all sections) translated by its container's
  origin in the run (`rp`, layout-positions of the run) plus (ox, oy)."
  [e rp ox oy]
  (let [o (or (get rp (:container e)) {:x 0 :y 0})
        tx (fn [p] {:x (+ ox (:x o) (:x p)) :y (+ oy (:y o) (:y p))})]
    (vec (mapcat (fn [s] (mapv tx (into (into [(:startPoint s)] (or (:bendPoints s) [])) [(:endPoint s)])))
                 (or (:sections e) [])))))

(defn- abs-labels [e rp ox oy]
  (let [o (or (get rp (:container e)) {:x 0 :y 0})]
    (mapv (fn [lb] (assoc lb :x (+ ox (:x o) (:x lb)) :y (+ oy (:y o) (:y lb)))) (or (:labels e) []))))

(defn- dedupe-points [ps]
  (reduce (fn [acc p]
            (let [q (peek acc)]
              (if (and (some? q) (< (js/Math.abs (- (:x q) (:x p))) 0.01) (< (js/Math.abs (- (:y q) (:y p))) 0.01))
                acc
                (conj acc p))))
          [] ps))

(defn- root-edge [id points labels]
  {:id id :container "root"
   :sections [{:startPoint (first points)
               :bendPoints (vec (rest (butlast points)))
               :endPoint (last points)}]
   :labels labels})

(defn- border-point
  "Point k (0-based) of n spread along `side` of the w×h element at x,y."
  [x y w h side k n]
  (let [f (/ (inc k) (inc n))]
    (case side
      "EAST" {:x (+ x w) :y (+ y (* h f))}
      "WEST" {:x x :y (+ y (* h f))}
      "SOUTH" {:x (+ x (* w f)) :y (+ y h)}
      {:x (+ x (* w f)) :y y})))

(defn ^:async layout-grid
  "The grid layout of `graph` (collapse applied) as an ELK-shaped result:
  top-level elements as :children at their places, every edge at the
  root with absolute points. `elk-graph` is (to-elk graph ..), `run-elk`
  lays out one ELK input (a promise), `positions` are the previous
  layout's (layout-positions) or nil — they seed each box's own run."
  [graph elk-graph run-elk positions]
  (let [po (:parent-of graph)
        cells (grid-cells graph)
        {:keys [attached strip]} (attach-loose graph cells)
        sl (slots cells attached)
        kids {}
        _ (doseq [c (:children elk-graph)] (assoc! kids (:id c) c))
        compound? (fn [id] (some? (:children (get kids id))))
        in-strip (js/Set. strip)
        loose (filterv (fn [id] (some? (get attached id))) (top-items graph))
        inner {} ports {} strip-edges [] cross []]
    ;; sort the edges: inside one element, inside the strip, across cells
    (doseq [e (:edges elk-graph)]
      (let [s (first (:sources e)) t (first (:targets e))
            ts (top-of po s) tt (top-of po t)]
        (cond
          (= ts tt) (when (compound? ts) (assoc! inner ts (conj (or (get inner ts) []) e)))
          (and (.has in-strip ts) (.has in-strip tt)) (.push strip-edges e)
          :else
          (let [c {:e e :s s :t t :ts ts :tt tt
                   :sside (port-side (get sl ts) (get sl tt))
                   :tside (port-side (get sl tt) (get sl ts))}]
            (.push cross c)
            (when (compound? ts)
              (assoc! ports ts (conj (or (get ports ts) []) {:id (str "p:" (:id e) ":s") :side (:sside c)}))
              (when (not= s ts)
                (assoc! inner ts (conj (or (get inner ts) [])
                                       {:id (str (:id e) ":s") :sources [s] :targets [(str "p:" (:id e) ":s")]}))))
            (when (compound? tt)
              (assoc! ports tt (conj (or (get ports tt) []) {:id (str "p:" (:id e) ":t") :side (:tside c)}))
              (when (not= t tt)
                (assoc! inner tt (conj (or (get inner tt) [])
                                       {:id (str (:id e) ":t") :sources [(str "p:" (:id e) ":t")] :targets [t]}))))))))
    ;; one ELK run per placed compound element, one for the strip
    (let [run-ids (filterv compound? (into (vec (js/Object.keys cells)) loose))
          inputs (mapv (fn [id]
                         (let [run (element-run (:layoutOptions elk-graph) (get kids id)
                                                (or (get ports id) []) (or (get inner id) []))
                               rel (when (some? positions) (run-positions positions po id))]
                           (if (and (some? rel) (seedable? run rel)) (seed-layout run rel) run)))
                       run-ids)
          results (js-await (js/Promise.all (mapv run-elk inputs)))
          res {}
          _ (doseq [i (range (count run-ids))] (assoc! res (nth run-ids i) (nth results i)))
          strip-res (when (pos? (count strip))
                      (js-await (run-elk {:id "root"
                                          :layoutOptions (assoc (:layoutOptions elk-graph)
                                                                "elk.padding" "[top=0,left=0,bottom=0,right=0]")
                                          :children (mapv (fn [id] (get kids id)) strip)
                                          :edges strip-edges})))
          node-of (fn [id] (if-let [r (get res id)] (first (:children r)) (get kids id)))
          ;; stacks: loose elements sharing a slot, top-down in top-items order
          stacks {}
          _ (doseq [id loose]
              (let [k (str (:c0 (get sl id)) "," (:r0 (get sl id)))]
                (assoc! stacks k (conj (or (get stacks k) []) id))))
          col-items (into (mapv (fn [[id s]] {:t0 (:c0 s) :t1 (:c1 s) :size (:width (node-of id))})
                                (js/Object.entries (select-keys* sl (js/Object.keys cells))))
                          (mapv (fn [[k ids]] {:t0 (:c0 (get sl (first ids))) :t1 (:c0 (get sl (first ids)))
                                               :size (apply max (mapv (fn [id] (:width (node-of id))) ids))})
                                (js/Object.entries stacks)))
          row-items (into (mapv (fn [[id s]] {:t0 (:r0 s) :t1 (:r1 s) :size (:height (node-of id))})
                                (js/Object.entries (select-keys* sl (js/Object.keys cells))))
                          (mapv (fn [[k ids]] {:t0 (:r0 (get sl (first ids))) :t1 (:r0 (get sl (first ids)))
                                               :size (+ (reduce + 0 (mapv (fn [id] (:height (node-of id))) ids))
                                                        (* STACK-GAP (dec (count ids))))})
                                (js/Object.entries stacks)))
          ncols (apply max (mapv (fn [c] (+ (:col c) (:w c))) (js/Object.values cells)))
          nrows (apply max (mapv (fn [c] (+ (:row c) (:h c))) (js/Object.values cells)))
          col-ts (tracks ncols (mapv (fn [ids] (:c0 (get sl (first ids)))) (js/Object.values stacks)))
          row-ts (tracks nrows [])
          ;; where every placed element's top-left goes
          place (fn [ca ra]
                  (let [out {}
                        at (fn [ax ts t] (nth (:pos ax) (.indexOf ts t)))]
                    (doseq [id (js/Object.keys cells)]
                      (assoc! out id {:x (at ca col-ts (:c0 (get sl id))) :y (at ra row-ts (:r0 (get sl id)))}))
                    (doseq [ids (js/Object.values stacks)]
                      (let [s (get sl (first ids))
                            x (at ca col-ts (:c0 s))]
                        (loop [y (at ra row-ts (:r0 s)) i 0]
                          (when (< i (count ids))
                            (let [id (nth ids i)]
                              (assoc! out id {:x x :y y})
                              (recur (+ y (:height (node-of id)) STACK-GAP) (inc i)))))))
                    out))
          gap-of (fn [slot side]
                   (case side
                     "EAST" (inc (.indexOf col-ts (:c1 slot)))
                     "WEST" (.indexOf col-ts (:c0 slot))
                     "SOUTH" (inc (.indexOf row-ts (:r1 slot)))
                     (.indexOf row-ts (:r0 slot))))
          ;; leaves spread their edge ends along each side
          leaf-ends {}
          _ (doseq [c cross]
              (doseq [[id side key] [[(:ts c) (:sside c) (str (:id (:e c)) ":s")]
                                     [(:tt c) (:tside c) (str (:id (:e c)) ":t")]]]
                (when-not (compound? id)
                  (let [k (str id "|" side)]
                    (assoc! leaf-ends k (conj (or (get leaf-ends k) []) key))))))
          ;; run results translated to final coordinates: element at p
          run-offset (fn [id p] (let [ch (node-of id)] {:ox (- (:x p) (:x ch)) :oy (- (:y p) (:y ch))}))
          inner-points (fn [id p eid]
                         (when-let [r (get res id)]
                           (when-let [e (some (fn [e] (when (= (:id e) eid) e)) (:edges r))]
                             (let [{:keys [ox oy]} (run-offset id p)]
                               (abs-points e (layout-positions r) ox oy)))))
          port-point (fn [id p pid]
                       (let [ch (node-of id)
                             pt (some (fn [q] (when (= (:id q) pid) q)) (or (:ports ch) []))]
                         {:x (+ (:x p) (:x pt) (/ (:width pt) 2)) :y (+ (:y p) (:y pt) (/ (:height pt) 2))}))
          end-entry (fn [places c end]
                      (let [id (if (= end "s") (:ts c) (:tt c))
                            side (if (= end "s") (:sside c) (:tside c))
                            p (get places id)
                            eid (:id (:e c))
                            key (str eid ":" end)
                            ip (inner-points id p key)
                            pt (cond
                                 (some? ip) (if (= end "s") (last ip) (first ip))
                                 (compound? id) (port-point id p (str "p:" key))
                                 :else (let [ks (get leaf-ends (str id "|" side))
                                             nd (node-of id)]
                                         (border-point (:x p) (:y p) (:width nd) (:height nd) side
                                                       (.indexOf ks key) (count ks))))]
                        {:inner (or ip []) :entry {:x (:x pt) :y (:y pt)
                                                   :axis (if (or (= side "EAST") (= side "WEST")) "v" "h")
                                                   :gap (gap-of (get sl id) side)}}))]
      (loop [cg (base-gaps (count col-ts)) rg (base-gaps (count row-ts)) k 0]
        (let [ca (axis col-ts col-items cg)
              ra (axis row-ts row-items rg)
              places (place ca ra)
              ends (mapv (fn [c] [c (end-entry places c "s") (end-entry places c "t")]) cross)
              routed (route-edges (mapv (fn [[c s t]] {:id (:id (:e c)) :from (:entry s) :to (:entry t)}) ends)
                                  (centres ca) (centres ra))
              cg2 (widen (base-gaps (count col-ts)) (:lanes routed) "v")
              rg2 (widen (base-gaps (count row-ts)) (:lanes routed) "h")]
          (if (and (< k 2) (or (not= (js/JSON.stringify cg2) (js/JSON.stringify cg))
                               (not= (js/JSON.stringify rg2) (js/JSON.stringify rg))))
            (recur cg2 rg2 (inc k))
            (let [sx MARGIN
                  sy (+ (:end ra) STACK-GAP)
                  children (into (mapv (fn [id] (let [p (get places id)] (assoc (node-of id) :x (:x p) :y (:y p))))
                                       (into (vec (js/Object.keys cells)) loose))
                                 (mapv (fn [ch] (assoc ch :x (+ sx (:x ch)) :y (+ sy (:y ch))))
                                       (if (some? strip-res) (:children strip-res) [])))
                  run-edges (vec (mapcat (fn [id]
                                           (let [r (get res id)
                                                 rp (layout-positions r)
                                                 {:keys [ox oy]} (run-offset id (get places id))]
                                             (keep (fn [e]
                                                     (when-not (or (.endsWith (:id e) ":s") (.endsWith (:id e) ":t"))
                                                       (root-edge (:id e) (abs-points e rp ox oy) (abs-labels e rp ox oy))))
                                                   (:edges r))))
                                         (js/Object.keys res)))
                  strip-edges' (if (some? strip-res)
                                 (let [rp (layout-positions strip-res)]
                                   (mapv (fn [e] (root-edge (:id e) (abs-points e rp sx sy) (abs-labels e rp sx sy)))
                                         (or (:edges strip-res) [])))
                                 [])
                  cross-edges (mapv (fn [[c s t]]
                                      (let [e (:e c)
                                            ps (dedupe-points (into (into (:inner s) (get (:points routed) (:id e))) (:inner t)))
                                            lb (first (or (:labels e) []))]
                                        (root-edge (:id e) ps
                                                   (if (some? lb) [(merge lb (label-at ps (:width lb) (:height lb)))] []))))
                                    ends)
                  strip-w (if (some? strip-res) (+ sx (:width strip-res) MARGIN) 0)]
              (cond-> {:id "root"
                       :width (max (+ (:end ca) MARGIN) strip-w)
                       :height (+ (if (some? strip-res) (+ sy (:height strip-res)) (:end ra)) MARGIN)
                       :children children
                       :edges (into (into run-edges strip-edges') cross-edges)}
                (some? positions) (assoc :seeded true)))))))))
```

`select-keys*` is a small private helper — add it above `layout-grid`:

```clojure
(defn- select-keys*
  "Map m restricted to keys ks (JS-object keys)."
  [m ks]
  (let [out {}] (doseq [k ks] (assoc! out k (get m k))) out))
```


- [ ] **Step 4: Run the tests**

Run: `bb build >/dev/null 2>&1; bb test:js 2>&1 | grep -E "^✖|ℹ (pass|fail)"`
Expected: `ℹ fail 0`. A failing assertion here is a pipeline bug: debug it (superpowers:systematic-debugging) — dump the layout with `(js/console.log (js/JSON.stringify l))` in a scratch run, never loosen the assertion.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/grid.cljs test/simpleviz/layout_test.cljs
git commit -m "feat(grid): layout-grid — per-box ELK runs, placement and routing in one ELK-shaped result"
```

---

### Task 7: Wire it in, document it, check it end to end

**Files:**
- Modify: `src/simpleviz/app.cljs` (`ns` `:require`; `relayout!` ~line 990-1020; help text "Navigate")
- Modify: `plugins/simpleviz/skills/simpleviz/SKILL.md`, `docs/guide.md`, `README.md`

**Interfaces:**
- Consumes: `grid/grid-mode?`, `grid/grid-cells`, `grid/layout-grid` (Tasks 2, 6).

- [ ] **Step 1: `relayout!`** — add `[simpleviz.grid :as grid]` to `app.cljs`'s `:require`. In `relayout!`, replace the `fp (elk-fingerprint elk-graph)` binding and the `layout (cond …)` form with:

```clojure
                grid? (grid/grid-mode? g)
                ;; in grid mode the cells shape the layout too
                fp (elk-fingerprint (if grid? {:elk elk-graph :cells (grid/grid-cells g)} elk-graph))
                prev (when (some? hit) (:layout hit))
                positions (when (some? prev) (layout-positions prev))
                layout (cond (and (some? prev) (= fp (:fingerprint hit)))
                             prev

                             grid?
                             (js-await (grid/layout-grid g elk-graph (fn [input] (.layout elk input)) positions))

                             (and (some? prev) (seedable? elk-graph positions))
                             (assoc (js-await (.layout elk (seed-layout elk-graph positions)))
                                    :seeded true)

                             :else (js-await (.layout elk elk-graph)))
```

(keep the existing `prev`/`positions` bindings if they are already there — only `grid?`, `fp` and the `grid?` branch are new). The clean relayout (`relayout! true`) clears the cache, so `prev` is nil and the grid layout runs unseeded.

- [ ] **Step 2: Build, full suite**

Run: `bb test 2>&1 | grep -E "^Ran|failures|ℹ (pass|fail)"`
Expected: `0 failures, 0 errors.` and `ℹ fail 0`.

- [ ] **Step 3: Docs**

`plugins/simpleviz/skills/simpleviz/SKILL.md` — after the `:boxes` rule ("Grouping is `:boxes` with `:components` …"), add:

```markdown
- `:grid [col row]` (or `[col row w h]` to span) on a top-level box puts it on that cell of a grid, counted from 0: columns as wide as their widest box, rows as tall as their tallest, boxes top-left in their cell. ELK still lays out what is inside each box; nodes and boxes without a cell sit beside the gridded box they have the most edges to (left when their edges point into it), the rest in a strip under the grid; edges between cells run through the gaps at right angles. Use it for the main boxes of a spec figure when the arrangement matters. On a nested box, malformed, or overlapping another box's cells it warns and is ignored. A file without `:grid` lays out as before.
```

`docs/guide.md` — after the `:pair` bullet, add:

```markdown
- `:grid [col row]` or `[col row w h]` puts a top-level box on a grid cell
  (see [Grid layout](#grid-layout)).
```

and before `## Editing docs`:

```markdown
## Grid layout

Give top-level boxes a `:grid` cell and they are placed exactly there:

    :boxes {:frontend {:grid [0 0] :components #{:web}}
            :backend  {:grid [1 0] :components #{:api :auth}}
            :data     {:grid [0 1 2 1] :components #{:db}}}  ; spans 2 columns

- Columns and rows count from 0. A column is as wide as its widest box, a
  row as tall as its tallest; boxes sit top-left in their cell. An empty
  column or row still leaves its gap.
- Inside each box the usual layout runs; edges leave a box on the side
  facing their other end.
- Nodes and boxes without a cell go beside the gridded box they have the
  most edges to — left when their edges point into it, else right. What
  reaches no gridded box sits in a strip under the grid.
- Edges between cells run through the gaps at right angles; edges sharing
  a gap get their own lanes.
- Only top-level boxes take a cell. A nested box, a malformed value, or a
  cell another box already has warns and is ignored (the box first by
  sorted name keeps an overlapping cell).
```

`README.md` — in the data-format example, change the `:backend` box's first line to:

```
 :boxes {:backend                    ; key is the box id; :grid [0 0] pins a top-level box to a grid cell
```

`app.cljs` help, "Navigate" paragraph — append: ` Top-level boxes with :grid [col row] sit on that grid cell; the rest arranges itself around them.`

- [ ] **Step 4: Full suite again** (the docs test re-reads the README example)

Run: `bb test 2>&1 | grep -E "^Ran|failures|ℹ (pass|fail)"`
Expected: `0 failures, 0 errors.` and `ℹ fail 0`.

- [ ] **Step 5: End to end** — gridded copy of the demo, served and screenshotted:

```bash
S=<scratchpad>; cp examples/demo.edn $S/grid-demo.edn
# backend on [1 0]; storage out of backend, top-level on [1 1]; web, mail,
# logs stay loose (web left of backend, mail/logs right of it)
sed -i 's/:components #{:api :auth :storage :worker :queue}/:grid [1 0] :components #{:api :auth :worker :queue}/' $S/grid-demo.edn
sed -i 's/:storage {:type "zone" :components #{:db :cache}}/:storage {:type "zone" :grid [1 1] :components #{:db :cache}}/' $S/grid-demo.edn
grep -c ':grid' $S/grid-demo.edn    # → 2
bb check $S/grid-demo.edn           # → ok
bb serve $S/grid-demo.edn --port 7480 &
chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 --user-data-dir=$S/chrome about:blank &
node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7480/","wait":3000},{"screenshot":"'$S'/grid-1.png"}]'
```

Look at the screenshot: boxes on their cells, rows aligned, loose nodes beside their box, edges orthogonal through the gaps, labels readable, nothing overlapping. Then: switch to the dark theme and screenshot; collapse a gridded box (click its −) and screenshot; add a node inside a box via the file and check the box stays put and its contents keep their places; fork (`bb fork $S/grid-demo.edn next`), change a `:grid` in the fork, serve the comparison and screenshot. Fix what looks wrong with a failing unit test first where one can pin it. Stop both processes afterwards.

- [ ] **Step 6: Commit**

```bash
git add src/simpleviz/app.cljs plugins/simpleviz/skills/simpleviz/SKILL.md docs/guide.md README.md
git commit -m "feat: grid layout — :grid on top-level boxes"
```
