# Canvas Renderer v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the SVG renderer with a HiDPI canvas painter fed by a pure scene list, with pure hit-testing, ELK-managed edge labels, wider spacing, and a selection model that highlights only the selected shape.

**Architecture:** `scene.cljs` (pure: layout+graph+colors → flat absolute-coordinate draw list) and `hit.cljs` (pure: inverse transform + priority hit-testing with header-strip-only box hits) are node-testable; `canvas.cljs` (DOM) paints the scene and owns view/pan/zoom; `app.cljs` keeps reagami for banner/details/canvas element and wires click → hit-test → selection. `transform.cljs` attaches measured ELK edge labels and stretches spacing.

**Tech Stack:** Squint CLJS (existing toolchain: `bb build` → `public/js/`), canvas 2D (`roundRect`, `setTransform`, `strokeText` halo), ELK.js layered with `elk.edgeLabels.inline`.

**Spec:** `docs/superpowers/specs/2026-08-05-canvas-renderer-design.md` — read before starting.

## Global Constraints

- Squint gotchas: keywords are strings and NOT functions (`(mapv :id xs)` breaks — wrap in fns); maps are JS objects (`assoc` copies, `assoc!` mutates — `view` is intentionally mutable); `#{}` is a JS Set.
- Scene/hit namespaces must be DOM-free (imported by `node --test`).
- Scene item kinds are the strings `"box"`, `"edge"`, `"edge-label"`, `"node"` at runtime (squint keywords).
- Selection accent: `#2563eb`; only the selected item's own shape changes (2px outline; edges 2.5px stroke) — no fill changes, box children unaffected.
- Box hit-target: header strip (top `title-h` = 28px) or 4px border band; interior clicks select nothing. Nodes → edges (distance ≤ tol) → boxes (innermost first).
- ELK spacing exactly: `nodeNodeBetweenLayers "80"`, `nodeNode "45"`, `edgeNode "30"`, `edgeEdge "20"`, `elk.edgeLabels.inline "true"`; label entries `{:text "name (type)" :width (ceil measure + 4) :height 14}` only for edges with non-empty label text.
- Fonts: node name `"bold 14px system-ui, sans-serif"`, sub/label `"11px system-ui, sans-serif"` — canvas.cljs reuses transform's `NODE-FONT`/`SUB-FONT` (no duplicated constants).
- Verification always uses the stale-output guard: `rm -rf public/js && bb test`.
- User-visible behavior otherwise unchanged (live reload preserving view, banners, details panel incl. ✕/kind, arrowheads per direction, container-relative edge coordinates).
- Commit messages end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: ELK edge labels + stretched spacing

**Files:**
- Modify: `src/simpleviz/transform.cljs`
- Test: `test/simpleviz/transform_test.cljs`, `test/simpleviz/layout_test.cljs`

**Interfaces:**
- Consumes: existing `to-elk(graph, measure)`.
- Produces: ELK edges may carry `:labels [{:text :width :height}]`; ELK layout output then carries per-edge `:labels [{:x :y :width :height :text}]` (container-relative, like sections) — Task 2's scene builder relies on exactly that. Root `layoutOptions` values per Global Constraints.

- [ ] **Step 1: Write the failing tests**

In `test/simpleviz/transform_test.cljs`, update the root-options test and add a labels test:

```clojure
(test "root layout options select hierarchical layered layout"
  (fn []
    (let [elk (to-elk (graph {}) measure)]
      (assert/equal (get (:layoutOptions elk) "elk.algorithm") "layered")
      (assert/equal (get (:layoutOptions elk) "elk.direction") "RIGHT")
      (assert/equal (get (:layoutOptions elk) "elk.hierarchyHandling") "INCLUDE_CHILDREN")
      (assert/equal (get (:layoutOptions elk) "elk.layered.spacing.nodeNodeBetweenLayers") "80")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.nodeNode") "45")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.edgeNode") "30")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.edgeEdge") "20")
      (assert/equal (get (:layoutOptions elk) "elk.edgeLabels.inline") "true"))))

(test "named edges get measured ELK labels; unnamed edges get none"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "calls" :type "http" :attrs {}}
                            {:id "e1" :source "b" :target "a"
                             :arrows {:source false :target true}
                             :name "" :type "" :attrs {}}]})
          elk (to-elk g measure)
          lbl (first (:labels (first (:edges elk))))]
      (assert/equal (:text lbl) "calls (http)")
      (assert/ok (>= (:width lbl) (measure "calls (http)" nil)))
      (assert/equal (:height lbl) 14)
      (assert/equal (:labels (second (:edges elk))) js/undefined))))
```

In `test/simpleviz/layout_test.cljs`, add:

```clojure
(test "ELK returns label coordinates for labeled edges"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "") "b" (node "b" "")}
                    :edges [(assoc (edge 0 "a" "b" {:source false :target true})
                                   :name "calls" :type "http")]})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (let [lbl (first (:labels (first (:edges layout))))]
                     (assert/ok lbl "label present in layout output")
                     (assert/ok (and (some? (:x lbl)) (some? (:y lbl)))))))))))
```

- [ ] **Step 2: Compile and run to verify failure**

Run: `bb build && node --test public/js/simpleviz/transform_test.mjs`
Expected: FAIL (spacing values and labels missing).

- [ ] **Step 3: Implement**

In `src/simpleviz/transform.cljs`: replace the `layoutOptions` map values per Global Constraints, and replace the `:edges` mapv with:

```clojure
     :edges (mapv (fn [e]
                    (let [parts (filterv (fn [s] (pos? (.-length s)))
                                         [(:name e)
                                          (if (pos? (.-length (:type e)))
                                            (str "(" (:type e) ")")
                                            "")])
                          label (.join parts " ")
                          base {:id (:id e)
                                :sources [(str "n:" (:source e))]
                                :targets [(str "n:" (:target e))]}]
                      (if (pos? (.-length label))
                        (assoc base :labels [{:text label
                                              :width (+ (js/Math.ceil (measure label SUB-FONT)) 4)
                                              :height 14}])
                        base)))
                  edges)
```

- [ ] **Step 4: Verify pass**

Run: `rm -rf public/js && bb test`
Expected: all green (transform 5, layout 3, colors 8, clj suite).

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/transform.cljs test/simpleviz/transform_test.cljs test/simpleviz/layout_test.cljs
git commit -m "feat: ELK-managed edge labels and stretched spacing"
```

---

### Task 2: Pure scene builder

**Files:**
- Create: `src/simpleviz/scene.cljs`
- Test: `test/simpleviz/scene_test.cljs`

**Interfaces:**
- Consumes: ELK layout output (children x/y/width/height, edges with `:container`, `:sections`, `:labels`), graph (`:nodes`, `:edges`, `:boxes-by-name`), colors map (`{:node {type hsl} :box {type {:border :fill}} :neutral-node :neutral-box}`).
- Produces: `build-scene {:layout .. :graph .. :colors ..}` → `{:items [..] :width :height}`. Item shapes (all with absolute coords):
  - `{:kind :box :id "b:x" :x :y :w :h :title-h 28 :border :fill :name :type :attrs}`
  - `{:kind :edge :id "e0" :sections [[{:x :y}..]..] :points [{:x :y}..] :arrows {:source :target} :name :type :attrs}`
  - `{:kind :edge-label :id "e0-label" :edge-id "e0" :x :y :w :h :text}`
  - `{:kind :node :id "n:a" :x :y :w :h :color :name :type :attrs}`
  - Order: all boxes (parents before children), then edges, then edge labels, then nodes.

- [ ] **Step 1: Write the failing tests**

Create `test/simpleviz/scene_test.cljs`:

```clojure
(ns simpleviz.scene-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.scene :refer [build-scene]]))

(def colors
  {:node {"svc" "hsl(120 65% 38%)"}
   :box {"zone" {:border "hsl(1 45% 55%)" :fill "hsl(1 45% 55% / 0.1)"}}
   :neutral-node "hsl(0 0% 40%)"
   :neutral-box {:border "hsl(0 0% 65%)" :fill "hsl(0 0% 65% / 0.1)"}})

(defn gnode [id type] {:id id :name id :type type :attrs {}})

(def graph
  {:nodes {"a" (gnode "a" "svc") "b" (gnode "b" "")}
   :edges [{:id "e0" :source "a" :target "b"
            :arrows {:source false :target true} :name "calls" :type "http"
            :attrs {:nodes ["a" "b"]}}]
   :boxes-by-name {"grp" {:id "b:grp" :name "grp" :type "" :components ["n:a"] :attrs {}}}})

(def layout
  {:width 500 :height 300
   :children [{:id "b:grp" :x 10 :y 20 :width 200 :height 150
               :children [{:id "n:a" :x 14 :y 40 :width 60 :height 30}]}
              {:id "n:b" :x 300 :y 50 :width 60 :height 30}]
   :edges [{:id "e0" :container "b:grp"
            :sections [{:startPoint {:x 1 :y 2} :bendPoints [{:x 3 :y 4}]
                        :endPoint {:x 5 :y 6}}
                       {:startPoint {:x 7 :y 8} :endPoint {:x 9 :y 10}}]
            :labels [{:x 2 :y 3 :width 40 :height 14 :text "calls (http)"}]}]})

(defn scene [] (build-scene {:layout layout :graph graph :colors colors}))

(defn items-of [kind] (filterv (fn [it] (= (:kind it) kind)) (:items (scene))))

(test "nodes get absolute positions and resolved colors"
  (fn []
    (let [[a] (filterv (fn [it] (= (:id it) "n:a")) (items-of "node"))
          [b] (filterv (fn [it] (= (:id it) "n:b")) (items-of "node"))]
      (assert/equal (:x a) 24)   ; 10 + 14
      (assert/equal (:y a) 60)   ; 20 + 40
      (assert/equal (:color a) "hsl(120 65% 38%)")
      (assert/equal (:x b) 300)
      (assert/equal (:color b) "hsl(0 0% 40%)"))))

(test "boxes carry absolute rect, title-h, and neutral colors when untyped"
  (fn []
    (let [[box] (items-of "box")]
      (assert/equal (:x box) 10)
      (assert/equal (:w box) 200)
      (assert/equal (:title-h box) 28)
      (assert/equal (:border box) "hsl(0 0% 65%)"))))

(test "edge sections are container-offset with pen-lifts preserved"
  (fn []
    (let [[e] (items-of "edge")]
      (assert/equal (.-length (:sections e)) 2)
      (assert/deepEqual (nth (:sections e) 0)
                        [{:x 11 :y 22} {:x 13 :y 24} {:x 15 :y 26}])
      (assert/deepEqual (nth (:sections e) 1)
                        [{:x 17 :y 28} {:x 19 :y 30}])
      (assert/equal (.-length (:points e)) 5)
      (assert/deepEqual (:arrows e) {:source false :target true}))))

(test "edge labels are container-offset"
  (fn []
    (let [[lbl] (items-of "edge-label")]
      (assert/equal (:x lbl) 12)   ; 2 + 10
      (assert/equal (:y lbl) 23)   ; 3 + 20
      (assert/equal (:text lbl) "calls (http)")
      (assert/equal (:edge-id lbl) "e0"))))

(test "draw order: boxes, edges, labels, nodes; scene carries size"
  (fn []
    (let [kinds (mapv (fn [it] (:kind it)) (:items (scene)))]
      (assert/deepEqual kinds ["box" "edge" "edge-label" "node" "node"])
      (assert/equal (:width (scene)) 500))))

(test "edges without sections or without graph entry are skipped"
  (fn []
    (let [l2 (assoc layout :edges [{:id "e0" :container "b:grp" :sections []}
                                   {:id "ghost" :sections [{:startPoint {:x 0 :y 0}
                                                            :endPoint {:x 1 :y 1}}]}])
          s (build-scene {:layout l2 :graph graph :colors colors})]
      (assert/deepEqual (filterv (fn [it] (= (:kind it) "edge")) (:items s)) []))))
```

- [ ] **Step 2: Compile and run to verify failure**

Run: `bb build && node --test public/js/simpleviz/scene_test.mjs`
Expected: FAIL (namespace `simpleviz.scene` missing).

- [ ] **Step 3: Implement**

Create `src/simpleviz/scene.cljs`:

```clojure
(ns simpleviz.scene)

;; layout + graph + colors -> flat, back-to-front draw list with absolute
;; coordinates: boxes, edges, edge labels, nodes. Pure data; the canvas
;; painter draws it and hit-testing walks it. No DOM.

(def TITLE-H 28)

(defn- node-color [node colors]
  (if (pos? (.-length (:type node)))
    (get (:node colors) (:type node))
    (:neutral-node colors)))

(defn- box-colors [box colors]
  (if (pos? (.-length (:type box)))
    (get (:box colors) (:type box))
    (:neutral-box colors)))

(defn- section-points [sec]
  (into [(:startPoint sec)]
        (conj (vec (or (:bendPoints sec) [])) (:endPoint sec))))

(defn- offset-pts [pts origin]
  (mapv (fn [p] {:x (+ (:x p) (:x origin)) :y (+ (:y p) (:y origin))}) pts))

(defn build-scene [{:keys [layout graph colors]}]
  (let [boxes (atom [])
        nodes (atom [])
        origins (atom {})]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                   c (box-colors box colors)]
               (swap! boxes conj {:kind :box :id (:id child)
                                  :x x :y y :w (:width child) :h (:height child)
                                  :title-h TITLE-H
                                  :border (:border c) :fill (:fill c)
                                  :name (:name box) :type (:type box)
                                  :attrs (:attrs box)})
               (swap! origins assoc (:id child) {:x x :y y})
               (walk child x y))
             (let [node (get (:nodes graph) (.slice (:id child) 2))]
               (swap! nodes conj {:kind :node :id (:id child)
                                  :x x :y y :w (:width child) :h (:height child)
                                  :color (node-color node colors)
                                  :name (:name node) :type (:type node)
                                  :attrs (:attrs node)}))))))
     layout 0 0)
    (let [edges-by-id (reduce (fn [acc e] (assoc acc (:id e) e)) {} (:edges graph))
          origin-of (fn [elk-edge] (or (get @origins (:container elk-edge)) {:x 0 :y 0}))
          edge-items
          (vec (filter some?
                (mapv (fn [elk-edge]
                        (let [e (get edges-by-id (:id elk-edge))
                              origin (origin-of elk-edge)
                              sections (mapv (fn [sec]
                                               (offset-pts (section-points sec) origin))
                                             (or (:sections elk-edge) []))]
                          (when (and (some? e) (pos? (.-length sections)))
                            {:kind :edge :id (:id elk-edge)
                             :sections sections
                             :points (vec (apply concat sections))
                             :arrows (:arrows e)
                             :name (:name e) :type (:type e) :attrs (:attrs e)})))
                      (or (:edges layout) []))))
          label-items
          (vec (filter some?
                (mapv (fn [elk-edge]
                        (let [origin (origin-of elk-edge)
                              lbl (first (or (:labels elk-edge) []))]
                          (when (some? lbl)
                            {:kind :edge-label :id (str (:id elk-edge) "-label")
                             :edge-id (:id elk-edge)
                             :x (+ (:x lbl) (:x origin))
                             :y (+ (:y lbl) (:y origin))
                             :w (:width lbl) :h (:height lbl)
                             :text (:text lbl)})))
                      (or (:edges layout) []))))]
      {:items (vec (concat @boxes edge-items label-items @nodes))
       :width (or (:width layout) 0)
       :height (or (:height layout) 0)})))
```

- [ ] **Step 4: Verify pass**

Run: `rm -rf public/js && bb test`
Expected: all green (scene 6 new tests included).

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/scene.cljs test/simpleviz/scene_test.cljs
git commit -m "feat: pure scene builder for canvas renderer"
```

---

### Task 3: Pure hit-testing

**Files:**
- Create: `src/simpleviz/hit.cljs`
- Test: `test/simpleviz/hit_test.cljs`

**Interfaces:**
- Consumes: scene from Task 2 (`:items` with kinds `"node"`/`"edge"`/`"box"`; boxes ordered parents-before-children).
- Produces: `client->graph view {mx my as two args}` → `{:x :y}` graph point; `hit-test scene p tol` → hit item or nil. Priority per Global Constraints.

- [ ] **Step 1: Write the failing tests**

Create `test/simpleviz/hit_test.cljs`:

```clojure
(ns simpleviz.hit-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.hit :refer [client->graph hit-test]]))

(defn scene [items] {:items items :width 500 :height 300})

(def node-a {:kind "node" :id "n:a" :x 100 :y 100 :w 60 :h 30})
(def edge-e {:kind "edge" :id "e0"
             :sections [[{:x 0 :y 200} {:x 300 :y 200}]]})
(def outer-box {:kind "box" :id "b:outer" :x 50 :y 50 :w 300 :h 220 :title-h 28})
(def inner-box {:kind "box" :id "b:inner" :x 80 :y 150 :w 120 :h 80 :title-h 28})

(test "client->graph inverts the view transform"
  (fn []
    (let [p (client->graph {:x 100 :y 50 :k 2} 140 90)]
      (assert/deepEqual p {:x 20 :y 20}))))

(test "node beats edge beats box"
  (fn []
    (let [s (scene [outer-box edge-e node-a])]
      (assert/equal (:id (hit-test s {:x 110 :y 110} 6)) "n:a")
      (assert/equal (:id (hit-test s {:x 250 :y 202} 6)) "e0")
      (assert/equal (:id (hit-test s {:x 60 :y 60} 6)) "b:outer"))))

(test "edge tolerance respected"
  (fn []
    (let [s (scene [edge-e])]
      (assert/equal (:id (hit-test s {:x 150 :y 205} 6)) "e0")
      (assert/ok (nil? (hit-test s {:x 150 :y 205} 3))))))

(test "box interior selects nothing; header and border bands select the box"
  (fn []
    (let [s (scene [outer-box])]
      (assert/equal (:id (hit-test s {:x 200 :y 60} 6)) "b:outer")   ; header strip
      (assert/equal (:id (hit-test s {:x 52 :y 150} 6)) "b:outer")   ; left band
      (assert/equal (:id (hit-test s {:x 348 :y 150} 6)) "b:outer")  ; right band
      (assert/equal (:id (hit-test s {:x 200 :y 268} 6)) "b:outer")  ; bottom band
      (assert/ok (nil? (hit-test s {:x 200 :y 150} 6))))))            ; interior

(test "nested boxes: innermost header wins"
  (fn []
    (let [s (scene [outer-box inner-box])]
      (assert/equal (:id (hit-test s {:x 100 :y 160} 6)) "b:inner")
      (assert/equal (:id (hit-test s {:x 200 :y 60} 6)) "b:outer"))))
```

- [ ] **Step 2: Compile and run to verify failure**

Run: `bb build && node --test public/js/simpleviz/hit_test.mjs`
Expected: FAIL (namespace `simpleviz.hit` missing).

- [ ] **Step 3: Implement**

Create `src/simpleviz/hit.cljs`:

```clojure
(ns simpleviz.hit)

;; Pure hit-testing over the scene display list. All coordinates in graph
;; space; convert mouse coordinates with client->graph first. No DOM.

(defn client->graph [view mx my]
  {:x (/ (- mx (:x view)) (:k view))
   :y (/ (- my (:y view)) (:k view))})

(defn- in-rect? [p x y w h]
  (and (>= (:x p) x) (<= (:x p) (+ x w))
       (>= (:y p) y) (<= (:y p) (+ y h))))

(defn- dist-to-segment [p a b]
  (let [dx (- (:x b) (:x a))
        dy (- (:y b) (:y a))
        len2 (+ (* dx dx) (* dy dy))
        t (if (zero? len2)
            0
            (js/Math.max 0 (js/Math.min 1 (/ (+ (* (- (:x p) (:x a)) dx)
                                                (* (- (:y p) (:y a)) dy))
                                             len2))))
        cx (+ (:x a) (* t dx))
        cy (+ (:y a) (* t dy))]
    (js/Math.hypot (- (:x p) cx) (- (:y p) cy))))

(defn- near-sections? [p sections tol]
  (boolean
   (some (fn [pts]
           (some (fn [i]
                   (<= (dist-to-segment p (nth pts i) (nth pts (inc i))) tol))
                 (range (dec (.-length pts)))))
         sections)))

(defn- box-hit?
  "Header strip or 4px border band only — never the interior content area."
  [p item]
  (let [{:keys [x y w h title-h]} item]
    (and (in-rect? p x y w h)
         (or (<= (:y p) (+ y title-h))
             (<= (:x p) (+ x 4))
             (>= (:x p) (- (+ x w) 4))
             (>= (:y p) (- (+ y h) 4))))))

(defn hit-test
  "Returns the hit scene item or nil. Priority: nodes, then edges within
  tol, then boxes innermost-first (reverse draw order)."
  [scene p tol]
  (let [items (:items scene)
        by-kind (fn [k] (filterv (fn [it] (= (:kind it) k)) items))]
    (or (some (fn [it] (when (in-rect? p (:x it) (:y it) (:w it) (:h it)) it))
              (by-kind "node"))
        (some (fn [it] (when (near-sections? p (:sections it) tol) it))
              (by-kind "edge"))
        (some (fn [it] (when (box-hit? p it) it))
              (reverse (by-kind "box"))))))
```

- [ ] **Step 4: Verify pass**

Run: `rm -rf public/js && bb test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/hit.cljs test/simpleviz/hit_test.cljs
git commit -m "feat: pure hit-testing with header-strip box targets"
```

---

### Task 4: Canvas painter + app rewiring + SVG removal

**Files:**
- Create: `src/simpleviz/canvas.cljs`
- Modify: `src/simpleviz/app.cljs`
- Delete: `src/simpleviz/render.cljs`
- Modify: `public/style.css`, `README.md`

**Interfaces:**
- Consumes: `scene/build-scene`, `hit/client->graph`, `hit/hit-test`, `transform/NODE-FONT`, `transform/SUB-FONT`, colors maps, reagami `render`.
- Produces: `canvas/measure`, `canvas/view`, `canvas/suppress-click`, `canvas/fit-view-once! scene`, `canvas/setup-pan-zoom! wrap`, `canvas/set-repaint! cb`, `canvas/request-paint!`, `canvas/paint! canvas-el scene selected-id`.

- [ ] **Step 1: Write the painter**

Create `src/simpleviz/canvas.cljs`:

```clojure
(ns simpleviz.canvas
  (:require [simpleviz.transform :refer [NODE-FONT SUB-FONT]]))

;; HiDPI canvas painter + view state + pan/zoom. DOM-only namespace —
;; never imported by node tests.

(def ^:private measure-ctx
  (.getContext (js/document.createElement "canvas") "2d"))

(defn measure [text font]
  (set! (.-font measure-ctx) font)
  (.-width (.measureText measure-ctx text)))

(def ACCENT "#2563eb")

;; Mutated in place (assoc!), outside the state atom so pan/zoom repaints
;; without re-rendering the DOM.
(def view {:x 0 :y 0 :k 1 :initialized false})
(def suppress-click (atom false))

(def ^:private repaint-cb (atom nil))
(def ^:private dirty (atom false))

(defn set-repaint! [cb] (reset! repaint-cb cb))

(defn request-paint! []
  (when-not @dirty
    (reset! dirty true)
    (js/requestAnimationFrame
     (fn [_]
       (reset! dirty false)
       (when-let [cb @repaint-cb] (cb))))))

(defn fit-view-once! [scene]
  (when-not (:initialized view)
    (assoc! view :initialized true)
    (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
          w (js/Math.max (:width scene) 1)
          h (js/Math.max (:height scene) 1)
          k (js/Math.min 1.25 (* 0.9 (js/Math.min (/ (.-width rect) w)
                                                  (/ (.-height rect) h))))]
      (assoc! view
              :k k
              :x (/ (- (.-width rect) (* w k)) 2)
              :y (/ (- (.-height rect) (* h k)) 2)))))

(defn- rounded-rect [ctx x y w h r]
  (.beginPath ctx)
  (.roundRect ctx x y w h r))

(defn- draw-box [ctx item sel?]
  (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 10)
  (set! (.-fillStyle ctx) (:fill item))
  (.fill ctx)
  (set! (.-strokeStyle ctx) (if sel? ACCENT (:border item)))
  (set! (.-lineWidth ctx) (if sel? 2 1))
  (.stroke ctx)
  (set! (.-textAlign ctx) "left")
  (set! (.-font ctx) "bold 13px system-ui, sans-serif")
  (set! (.-fillStyle ctx) (:border item))
  (.fillText ctx (:name item) (+ (:x item) 12) (+ (:y item) 20))
  (when (pos? (.-length (:type item)))
    (let [nw (.-width (.measureText ctx (:name item)))]
      (set! (.-font ctx) SUB-FONT)
      (set! (.-fillStyle ctx) "#888")
      (.fillText ctx (str "(" (:type item) ")")
                 (+ (:x item) 12 nw 5) (+ (:y item) 20)))))

(defn- draw-node [ctx item sel?]
  (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 6)
  (set! (.-fillStyle ctx) "#fff")
  (.fill ctx)
  (set! (.-strokeStyle ctx) (if sel? ACCENT "#ddd"))
  (set! (.-lineWidth ctx) (if sel? 2 1))
  (.stroke ctx)
  (set! (.-textAlign ctx) "center")
  (set! (.-font ctx) NODE-FONT)
  (set! (.-fillStyle ctx) (:color item))
  (.fillText ctx (:name item) (+ (:x item) (/ (:w item) 2)) (+ (:y item) 19))
  (when (pos? (.-length (:type item)))
    (set! (.-font ctx) SUB-FONT)
    (set! (.-fillStyle ctx) "#888")
    (.fillText ctx (str "(" (:type item) ")")
               (+ (:x item) (/ (:w item) 2)) (+ (:y item) 35))))

(defn- draw-arrowhead [ctx from to]
  (let [angle (js/Math.atan2 (- (:y to) (:y from)) (- (:x to) (:x from)))
        size 8]
    (.save ctx)
    (.translate ctx (:x to) (:y to))
    (.rotate ctx angle)
    (.beginPath ctx)
    (.moveTo ctx 0 0)
    (.lineTo ctx (- size) (/ size 2.2))
    (.lineTo ctx (- size) (/ size -2.2))
    (.closePath ctx)
    (set! (.-fillStyle ctx) "#555")
    (.fill ctx)
    (.restore ctx)))

(defn- draw-edge [ctx item sel?]
  (set! (.-strokeStyle ctx) (if sel? ACCENT "#555"))
  (set! (.-lineWidth ctx) (if sel? 2.5 1.5))
  (doseq [pts (:sections item)]
    (.beginPath ctx)
    (.moveTo ctx (:x (nth pts 0)) (:y (nth pts 0)))
    (doseq [p (rest pts)]
      (.lineTo ctx (:x p) (:y p)))
    (.stroke ctx))
  (let [sections (:sections item)
        first-sec (nth sections 0)
        last-sec (nth sections (dec (.-length sections)))]
    (when (:target (:arrows item))
      (draw-arrowhead ctx
                      (nth last-sec (- (.-length last-sec) 2))
                      (nth last-sec (dec (.-length last-sec)))))
    (when (:source (:arrows item))
      (draw-arrowhead ctx (nth first-sec 1) (nth first-sec 0)))))

(defn- draw-edge-label [ctx item]
  (set! (.-textAlign ctx) "center")
  (set! (.-font ctx) SUB-FONT)
  (let [cx (+ (:x item) (/ (:w item) 2))
        cy (+ (:y item) (:h item) -3)]
    (set! (.-lineWidth ctx) 3)
    (set! (.-strokeStyle ctx) "#fafafa")
    (.strokeText ctx (:text item) cx cy)
    (set! (.-fillStyle ctx) "#444")
    (.fillText ctx (:text item) cx cy)))

(defn paint! [canvas-el scene selected-id]
  (let [ctx (.getContext canvas-el "2d")
        dpr (or (.-devicePixelRatio js/window) 1)
        pw (js/Math.round (* (.-clientWidth canvas-el) dpr))
        ph (js/Math.round (* (.-clientHeight canvas-el) dpr))]
    (when (or (not= (.-width canvas-el) pw) (not= (.-height canvas-el) ph))
      (set! (.-width canvas-el) pw)
      (set! (.-height canvas-el) ph))
    (.setTransform ctx 1 0 0 1 0 0)
    (set! (.-fillStyle ctx) "#fafafa")
    (.fillRect ctx 0 0 pw ph)
    (.setTransform ctx (* dpr (:k view)) 0 0 (* dpr (:k view))
                   (* dpr (:x view)) (* dpr (:y view)))
    (doseq [item (:items scene)]
      (let [sel? (= selected-id (:id item))]
        (case (:kind item)
          "box" (draw-box ctx item sel?)
          "edge" (draw-edge ctx item sel?)
          "edge-label" (draw-edge-label ctx item)
          "node" (draw-node ctx item sel?)
          nil)))))

(defn setup-pan-zoom! [wrap]
  (.addEventListener wrap "wheel"
    (fn [e]
      (when-not (.closest (.-target e) "#details, #banner")
        (.preventDefault e)
        (let [factor (if (< (.-deltaY e) 0) 1.1 (/ 1 1.1))
              rect (.getBoundingClientRect wrap)
              mx (- (.-clientX e) (.-left rect))
              my (- (.-clientY e) (.-top rect))]
          (assoc! view
                  :x (- mx (* (- mx (:x view)) factor))
                  :y (- my (* (- my (:y view)) factor))
                  :k (* (:k view) factor))
          (request-paint!))))
    {:passive false})
  (let [drag (atom nil)]
    (.addEventListener wrap "pointerdown"
      (fn [e]
        (when-not (.closest (.-target e) "#details, #banner")
          (reset! drag {:x (.-clientX e) :y (.-clientY e)
                        :vx (:x view) :vy (:y view) :moved false})
          (.setPointerCapture wrap (.-pointerId e)))))
    (.addEventListener wrap "pointermove"
      (fn [e]
        (when-let [d @drag]
          (let [dx (- (.-clientX e) (:x d))
                dy (- (.-clientY e) (:y d))]
            (when (> (+ (js/Math.abs dx) (js/Math.abs dy)) 3)
              (swap! drag assoc :moved true))
            (assoc! view :x (+ (:vx d) dx) :y (+ (:vy d) dy))
            (request-paint!)))))
    (.addEventListener wrap "pointerup"
      (fn [_]
        (when (and @drag (:moved @drag)) (reset! suppress-click true))
        (reset! drag nil)))
    (.addEventListener wrap "pointercancel" (fn [_] (reset! drag nil))))
  (.addEventListener js/window "resize" (fn [_] (request-paint!))))
```

- [ ] **Step 2: Rewire the app**

In `src/simpleviz/app.cljs`:
- Requires become:

```clojure
(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.transform :refer [to-elk]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]))
```

- Replace every `r/...` usage: `r/measure` → `canvas/measure`; `r/fit-view-once! layout` → `canvas/fit-view-once! sc`; `r/setup-pan-zoom!` → `canvas/setup-pan-zoom!`; delete the `r/graph-view` call from `app-view`.
- Add to `reload!`'s success branch (after `layout`):

```clojure
              sc (scene/build-scene {:layout layout :graph g :colors cmap})]
          (canvas/fit-view-once! sc)
          (swap! state assoc
                 :error nil :graph g :warnings (:warnings g)
                 :colors cmap :layout layout :scene sc)
```

- Add the payload builder and canvas view:

```clojure
(defn- item->payload [item]
  (let [nm (str (if (nil? (:name item)) "" (:name item)))
        fallback (if (and (= (:kind item) "edge")
                          (js/Array.isArray (:nodes (:attrs item))))
                   (str (nth (:nodes (:attrs item)) 0) " → "
                        (nth (:nodes (:attrs item)) 1))
                   (:id item))]
    {:kind (:kind item)
     :elk-id (:id item)
     :title (if (pos? (.-length nm)) nm fallback)
     :subtitle (str (if (nil? (:type item)) "" (:type item)))
     :attrs (:attrs item)}))

(defn- canvas-view []
  [:canvas
   {:id "canvas" :key "the-canvas"
    :on-click
    (fn [e]
      (if @canvas/suppress-click
        (reset! canvas/suppress-click false)
        (let [rect (.getBoundingClientRect (.-currentTarget e))
              p (hit/client->graph canvas/view
                                   (- (.-clientX e) (.-left rect))
                                   (- (.-clientY e) (.-top rect)))
              tol (/ 8 (:k canvas/view))
              s (:scene @state)
              item (when (some? s) (hit/hit-test s p tol))]
          (on-select (when (some? item) (item->payload item))))))}])
```

- `app-view` becomes:

```clojure
(defn- app-view [st]
  [:div {:id "root"}
   (banner-view st)
   (canvas-view)
   (when (some? (:selected st))
     (details-view (:selected st)))])
```

- Rendering + painting wiring (replace `rerender!` and the init block):

```clojure
(defn- paint-now! []
  (when-let [canvas-el (js/document.getElementById "canvas")]
    (when-let [s (:scene @state)]
      (canvas/paint! canvas-el s (:elk-id (:selected @state))))))

(defn- rerender! []
  (render app-el (app-view @state))
  (canvas/request-paint!))

;; init
(canvas/set-repaint! paint-now!)
(add-watch state :render (fn [_ _ _ _] (rerender!)))
(canvas/setup-pan-zoom! (js/document.getElementById "canvas-wrap"))
(rerender!)
(tick)
(js/setInterval tick 1000)
```

- [ ] **Step 3: Delete the SVG renderer and prune CSS**

```bash
git rm src/simpleviz/render.cljs
```

In `public/style.css`: delete the SVG-only rules (`.node-bg`, `.node-name`, `.node-sub`, `.box-name`, `.box-sub`, `.edge-line`, `.edge-hit`, `.edge-label`, `.selectable`, `.selected ...`, `g.edge.selected ...`). Keep `#canvas-wrap`, `#canvas` (`width: 100%; height: 100%; display: block;`), banner, details, and close-button rules.

In `README.md`, in the Development section, change the layout sentence to:

```markdown
Rendering: HTML5 canvas (HiDPI) fed by a pure scene list; layout by vendored
[ELK.js](https://github.com/kieler/elkjs) (layered, left-to-right, compound
boxes, ELK-placed edge labels).
```

- [ ] **Step 4: Full verification**

```bash
rm -rf public/js && bb test        # all green
bb serve examples/demo.edn &
sleep 2
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/js/simpleviz/canvas.mjs  # 200
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/js/simpleviz/scene.mjs   # 200
curl -s localhost:8080/js/simpleviz/render.mjs | head -c 40                      # stale-free: 404 "not found"
kill %1
```

(The render.mjs check requires the `rm -rf public/js` above — a leftover compiled render.mjs would 200.)

- [ ] **Step 5: Browser check**

Serve the demo and verify: graph renders on canvas (crisp on HiDPI), labels sit on their edges without overlapping each other, layout visibly more spread out; clicking a node/edge selects with a subtle outline on that shape only; clicking a box HEADER selects the box (outline on the box border only — nothing inside lights up); clicking empty space inside a box deselects; pan/zoom smooth; live reload preserves view; error banner behavior unchanged. If running without a browser, hand this checklist to the controller — do not skip silently.

- [ ] **Step 6: Commit**

```bash
git add src/simpleviz/canvas.cljs src/simpleviz/app.cljs public/style.css README.md
git commit -m "feat: canvas renderer with scene-based painting and scoped selection"
```
