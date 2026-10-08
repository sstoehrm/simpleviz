(ns simpleviz.scene
  (:require [simpleviz.editor :refer [ref-of]]))

;; layout + graph + type-color slots -> flat, back-to-front draw list with absolute
;; coordinates: boxes, edges, edge labels, nodes. Pure data; the canvas
;; painter draws it and hit-testing walks it. No DOM.
;;
;; Hot path for large graphs: accumulation uses mutating JS arrays/Maps —
;; squint's persistent conj/assoc copy on every call, which turns 10k-item
;; builds quadratic (guarded by a perf test).

(def TITLE-H 28)

;; shared by the painter (draw threshold) and hit-testing (click zone):
;; below this rendered font height, text/labels/arrowheads and the box
;; hide-button are neither drawn nor clickable
(def TEXT-MIN-PX 4.5)

;; hide-button square in the box header, offsets from the top-right corner
(def HIDE-BTN-SIZE 15)
(def HIDE-BTN-RIGHT 22)
(def HIDE-BTN-TOP 7)

(def ^:private BBOX-PAD 10)

;; :text on a node or box shows inside it: lines of TEXT-FONT, broken at
;; "\n" and wrapped past TEXT-W, LINE-H apart. The layout sizes elements
;; for them and the painter draws them, both from text-lines.
(def TEXT-FONT "11px system-ui, sans-serif")
(def TEXT-W 220)
(def LINE-H 14)

(defn- wrap-line
  "Line s (no line breaks) as lines no wider than max-w, broken between
  words; a word wider than that keeps a line to itself."
  [s measure max-w]
  (let [words (filterv (fn [w] (pos? (.-length w))) (.split s " "))]
    (if (empty? words)
      [""]
      (loop [ws (rest words) line (first words) acc []]
        (if (empty? ws)
          (conj acc line)
          (let [cand (str line " " (first ws))]
            (if (<= (measure cand TEXT-FONT) max-w)
              (recur (rest ws) cand acc)
              (recur (rest ws) (first ws) (conj acc line)))))))))

(defn text-lines
  "The lines an element's :text shows as: a string, or a vector of them
  (numbers and keywords as text), split at line breaks and wrapped at
  TEXT-W by measure ((fn [text font] width)). [] without text, or for
  any other value (a map stays an inspector-only attribute)."
  [text measure]
  (let [parts (cond (string? text) [text]
                    (number? text) [(str text)]
                    (vector? text) (mapv str (filterv (fn [x] (or (string? x) (number? x))) text))
                    :else [])]
    (if (every? (fn [p] (= "" (.trim p))) parts)
      []
      (vec (mapcat (fn [p] (mapcat (fn [l] (wrap-line l measure TEXT-W)) (.split p "\n")))
                   parts)))))

(defn text-width
  "The widest of lines in TEXT-FONT, 0 for none."
  [lines measure]
  (reduce (fn [m l] (js/Math.max m (measure l TEXT-FONT))) 0 lines))

(defn text-height
  "The room lines take: LINE-H each plus a little air below, 0 for none."
  [lines]
  (if (empty? lines) 0 (+ 4 (* LINE-H (count lines)))))

;; a :type "database" node is drawn as the database cylinder: an ellipse
;; of this half-height at top and bottom, the layout adding both rims
(def DB-RIM 6)

(defn database?
  "Is a node of type t drawn as a database cylinder?"
  [t]
  (= "database" (.toLowerCase (str (or t "")))))

;; the :state values a node shows as a corner mark; anything else stays an
;; ordinary attribute
(def STATES #{"new" "in-progress" "blocked" "done"})

(defn- node-state [node]
  (let [s (:state (:attrs node))]
    (when (contains? STATES s) s)))

(defn- color-idx
  "The element's type-color slot from idx-by-type, nil when untyped. The
  painter looks the color up in the current theme, so a theme change
  never invalidates a scene."
  [el idx-by-type]
  (when (pos? (.-length (:type el)))
    (get idx-by-type (:type el))))

(defn- section-points [sec]
  (into [(:startPoint sec)]
        (conj (vec (or (:bendPoints sec) [])) (:endPoint sec))))

(defn- offset-pts [pts origin]
  (mapv (fn [p] {:x (+ (:x p) (:x origin)) :y (+ (:y p) (:y origin))}) pts))

(defn- rect-bbox [x y w h]
  {:x0 (- x BBOX-PAD) :y0 (- y BBOX-PAD)
   :x1 (+ x w BBOX-PAD) :y1 (+ y h BBOX-PAD)})

(defn- points-bbox [pts]
  (loop [i 0
         x0 js/Infinity y0 js/Infinity
         x1 (- js/Infinity) y1 (- js/Infinity)]
    (if (< i (.-length pts))
      (let [p (nth pts i)]
        (recur (inc i)
               (js/Math.min x0 (:x p)) (js/Math.min y0 (:y p))
               (js/Math.max x1 (:x p)) (js/Math.max y1 (:y p))))
      {:x0 (- x0 BBOX-PAD) :y0 (- y0 BBOX-PAD)
       :x1 (+ x1 BBOX-PAD) :y1 (+ y1 BBOX-PAD)})))

(defn visible?
  "Does the item's bounding box intersect the view rect {:x0 :y0 :x1 :y1}?"
  [item vr]
  (let [bb (:bbox item)]
    (and (< (:x0 bb) (:x1 vr)) (> (:x1 bb) (:x0 vr))
         (< (:y0 bb) (:y1 vr)) (> (:y1 bb) (:y0 vr)))))

(defn- pair-fields
  "The pair data a node or box item carries: its payload :pairs, whether
  it has any, and whether one of them is broken."
  [el]
  (let [ps (or (:pairs el) [])]
    {:pairs ps
     :pair? (pos? (.-length ps))
     :pair-problem? (boolean (some (fn [p] (some? (:problem p))) ps))}))

(defn- naive-measure
  "A stand-in text measure for callers without a canvas (tests)."
  [text _font]
  (* 6 (.-length (str text))))

(defn build-scene
  "The draw list for layout + graph + type-color slots. `measure`
  ((fn [text font] width), as the layout used) wraps :text into lines."
  [{:keys [layout graph colors measure]}]
  (let [boxes (js/Array.)
        nodes (js/Array.)
        origins (js/Map.)]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                   ;; genuinely empty (unlike a user-collapsed shell, which
                   ;; also has no components but carries :collapsed): draw
                   ;; collapsed-style, but there is nothing to toggle
                   empty? (and (not (:collapsed box))
                               (zero? (.-length (or (:components box) []))))
                   ;; an expanded box shows its :text under the header
                   lines (if (or (:collapsed box) empty?)
                           []
                           (text-lines (:text (:attrs box)) (or measure naive-measure)))]
               (.push boxes (merge {:kind "box" :id (:id child)
                                    :x x :y y :w (:width child) :h (:height child)
                                    ;; the header strip, with the text under it
                                    :title-h (+ TITLE-H (text-height lines))
                                    :text-lines lines
                                    :collapsed (or (:collapsed box) empty?)
                                    :empty empty?
                                    :bbox (rect-bbox x y (:width child) (:height child))
                                    :color-idx (color-idx box (:box colors))
                                    :name (or (:label box) (:name box)) :type (:type box)
                                    :attrs (:attrs box)
                                    :md-ref? (some? (ref-of box :md-ref))
                                    :diff (:diff box) :changed (:changed box) :diff-inside (:diff-inside box)}
                             (pair-fields box)))
               (.set origins (:id child) {:x x :y y})
               (walk child x y))
             (let [node (get (:nodes graph) (.slice (:id child) 2))]
               (.push nodes (merge {:kind "node" :id (:id child)
                                    :x x :y y :w (:width child) :h (:height child)
                                    :bbox (rect-bbox x y (:width child) (:height child))
                                    :color-idx (color-idx node (:node colors))
                                    :name (:name node) :type (:type node)
                                    :attrs (:attrs node)
                                    :ref? (some? (ref-of node))
                                    :md-ref? (some? (ref-of node :md-ref))
                                    :text-lines (text-lines (:text (:attrs node)) (or measure naive-measure))
                                    :database? (database? (:type node))
                                    :state (node-state node)
                                    :diff (:diff node) :changed (:changed node)}
                             (pair-fields node))))))))
     layout 0 0)
    (let [edges-by-id (let [m (js/Map.)]
                        (doseq [e (:edges graph)] (.set m (:id e) e))
                        m)
          origin-of (fn [elk-edge] (or (.get origins (:container elk-edge)) {:x 0 :y 0}))
          edge-items (js/Array.)
          label-items (js/Array.)]
      (doseq [elk-edge (or (:edges layout) [])]
        (let [e (.get edges-by-id (:id elk-edge))
              origin (origin-of elk-edge)
              sections (mapv (fn [sec] (offset-pts (section-points sec) origin))
                             (or (:sections elk-edge) []))]
          (when (and (some? e) (pos? (.-length sections)))
            (let [points (vec (apply concat sections))]
              (.push edge-items {:kind "edge" :id (:id elk-edge)
                                 :sections sections
                                 :points points
                                 :bbox (points-bbox points)
                                 :arrows (:arrows e)
                                 :source (:source e)
                                 :target (:target e)
                                 :source-id (:source-id e)
                                 :target-id (:target-id e)
                                 :name (:name e) :type (:type e) :attrs (:attrs e)
                                 :diff (:diff e) :changed (:changed e)})))
          (when-let [lbl (first (or (:labels elk-edge) []))]
            (let [lx (+ (:x lbl) (:x origin))
                  ly (+ (:y lbl) (:y origin))]
              (.push label-items {:kind "edge-label" :id (str (:id elk-edge) "-label")
                                  :edge-id (:id elk-edge)
                                  :x lx :y ly
                                  :w (:width lbl) :h (:height lbl)
                                  :bbox (rect-bbox lx ly (:width lbl) (:height lbl))
                                  :text (:text lbl)
                                  ;; runs along a vertical edge, turned 90°
                                  :rotated (= true (:rotated lbl))
                                  :diff (when (some? e) (:diff e))})))))
      {:items (.concat boxes edge-items label-items nodes)
       :width (or (:width layout) 0)
       :height (or (:height layout) 0)})))

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
