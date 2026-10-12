(ns simpleviz.manual
  "Manual layout: top-level nodes and boxes where their :pos [col row]
  puts them, on a grid of GRID px. The layered layout still arranges what
  is inside each box; placing moves each top-level element, its contents
  with it, and draws the edges between top-level elements straight.
  Pure."
  (:require [simpleviz.editor :refer [top-box-of]]))

(def GRID 20)

;; cell [0 0]'s top-left: room for what sticks out of an element there —
;; a self-loop and its label above it
(def ^:private MARGIN (* 2 GRID))

(defn cell->xy
  "The top-left of cell [col row], in layout coordinates."
  [[col row]]
  {:x (+ MARGIN (* col GRID)) :y (+ MARGIN (* row GRID))})

(defn xy->cell
  "The cell whose top-left is nearest x y — never left of or above cell 0."
  [x y]
  [(max 0 (js/Math.round (/ (- x MARGIN) GRID)))
   (max 0 (js/Math.round (/ (- y MARGIN) GRID)))])

;; the gap above the row of elements that have no :pos yet
(def ^:private STRIP-GAP 40)

(defn- top-id
  "The elk id of the top-level element holding elk id `id`, or `id`."
  [parent-of id]
  (if-let [b (top-box-of parent-of id)] (str "b:" b) id))

(defn- pos-of
  "The [col row] of top-level elk id `id` in graph g, or nil."
  [g id]
  (if (.startsWith id "n:")
    (:pos (get (:nodes g) (.slice id 2)))
    (:pos (get (:boxes-by-name g) (.slice id 2)))))

(defn manual?
  "Is graph g laid out by hand: has a top-level node or box a :pos? (The
  server keeps :pos on top-level elements only.) In a comparison, a
  removed element's :pos is the old file's and doesn't count."
  [g]
  (let [placed? (fn [el] (and (some? (:pos el)) (not= "removed" (:diff el))))]
    (boolean (or (some placed? (js/Object.values (or (:nodes g) {})))
                 (some placed? (or (:boxes g) []))))))

(defn with-pos
  "Graph g with top-level elk id `id` at cell `pos` — a drag's preview."
  [g id pos]
  (if (.startsWith id "n:")
    (assoc-in g [:nodes (.slice id 2) :pos] pos)
    (assoc-in g [:boxes-by-name (.slice id 2) :pos] pos)))

(defn snapshot
  "{elk-id [col row]}: the cell nearest each top-level element of a
  layout — where switching to manual layout puts it."
  [layout]
  (into {} (map (fn [c] [(:id c) (xy->cell (:x c) (:y c))]) (:children layout))))

(defn- shift [p d] {:x (+ (:x p) (:x d)) :y (+ (:y p) (:y d))})

(defn- shift-edge [e d]
  (cond-> (assoc e :sections (mapv (fn [s]
                                     (cond-> (assoc s :startPoint (shift (:startPoint s) d)
                                                    :endPoint (shift (:endPoint s) d))
                                       (some? (:bendPoints s)) (assoc :bendPoints (mapv #(shift % d) (:bendPoints s)))))
                                   (or (:sections e) [])))
    (some? (:labels e)) (assoc :labels (mapv #(merge % (shift % d)) (:labels e)))))

(defn- border-point
  "Where the line from rect r's center toward point q leaves r."
  [r q]
  (let [cx (+ (:x r) (/ (:width r) 2))
        cy (+ (:y r) (/ (:height r) 2))
        dx (- (:x q) cx)
        dy (- (:y q) cy)
        t (min (if (zero? dx) js/Infinity (/ (:width r) 2 (js/Math.abs dx)))
               (if (zero? dy) js/Infinity (/ (:height r) 2 (js/Math.abs dy))))]
    (if (js/isFinite t)
      {:x (+ cx (* dx t)) :y (+ cy (* dy t))}
      {:x cx :y cy})))

(defn- center [r] {:x (+ (:x r) (/ (:width r) 2)) :y (+ (:y r) (/ (:height r) 2))})

(defn- straight-edge
  "Edge e as one straight section from rect s's border to rect t's, its
  label centered on the middle."
  [e s t]
  (let [a (border-point s (center t))
        b (border-point t (center s))
        mid {:x (/ (+ (:x a) (:x b)) 2) :y (/ (+ (:y a) (:y b)) 2)}]
    (cond-> (-> e
                (assoc :sections [{:id (str (:id e) "_s0") :startPoint a :endPoint b}])
                (dissoc :junctionPoints))
      (some? (:labels e))
      (assoc :labels (mapv (fn [l] (assoc l :x (- (:x mid) (/ (:width l) 2))
                                          :y (- (:y mid) (/ (:height l) 2))))
                           (:labels e))))))

(defn- abs-rects
  "{elk-id {:x :y :width :height}} of every node and box, in root
  coordinates."
  [layout]
  (let [out (js/Map.)]
    ((fn walk [parent ox oy]
       (doseq [c (or (:children parent) [])]
         (let [x (+ ox (:x c)) y (+ oy (:y c))]
           (.set out (:id c) {:x x :y y :width (:width c) :height (:height c)})
           (walk c x y))))
     layout 0 0)
    out))

(defn- edge-extent
  "The far corners of edges' points and labels: [{:x :y}]."
  [edges]
  (mapcat (fn [e]
            (concat (mapcat (fn [s] (concat [(:startPoint s) (:endPoint s)] (or (:bendPoints s) [])))
                            (or (:sections e) []))
                    (map (fn [l] {:x (+ (:x l) (:width l)) :y (+ (:y l) (:height l))}) (or (:labels e) []))))
          edges))

(defn place
  "Layered `layout` of graph g with each top-level element moved to its
  :pos cell, and those without one in a row under the rest. Edges inside
  a box move with it; a self-loop on a top-level element moves with that;
  every other edge between top-level elements runs straight."
  [layout g]
  (let [kids (or (:children layout) [])
        cell (fn [c] (some-> (pos-of g (:id c)) cell->xy))
        bottom (reduce max 0 (keep (fn [c] (when-let [p (cell c)] (+ (:y p) (:height c)))) kids))
        strip-y (* GRID (js/Math.ceil (/ (+ bottom STRIP-GAP) GRID)))
        kids' (loop [cs kids x MARGIN out []]
                (if (empty? cs)
                  out
                  (let [c (first cs)]
                    (if-let [p (cell c)]
                      (recur (rest cs) x (conj out (merge c p)))
                      (recur (rest cs)
                             (* GRID (js/Math.ceil (/ (+ x (:width c) STRIP-GAP) GRID)))
                             (conj out (assoc c :x x :y strip-y)))))))
        delta (into {} (map (fn [old new] [(:id old) {:x (- (:x new) (:x old)) :y (- (:y new) (:y old))}])
                            kids kids'))
        placed (assoc layout :children kids')
        rects (abs-rects placed)
        po (:parent-of g)
        edges (mapv (fn [e]
                      (if (or (nil? (:container e)) (= "root" (:container e)))
                        (let [s (first (:sources e))
                              t (first (:targets e))
                              st (top-id po s)
                              tt (top-id po t)]
                          (cond
                            (= st tt) (shift-edge e (get delta st))
                            (and (.has rects s) (.has rects t)) (straight-edge e (.get rects s) (.get rects t))
                            :else e))
                        e))
                    (or (:edges layout) []))]
    (assoc placed
           :edges edges
           :width (+ GRID (reduce max 0 (concat (map (fn [c] (+ (:x c) (:width c))) kids')
                                                (map :x (edge-extent edges)))))
           :height (+ GRID (reduce max 0 (concat (map (fn [c] (+ (:y c) (:height c))) kids')
                                                 (map :y (edge-extent edges))))))))

(defn top-at
  "The elk id of the top-level element of a placed layout under point p
  (root coordinates) — a node before a box, the last drawn first — or
  nil."
  [layout p]
  (let [kids (or (:children layout) [])
        in? (fn [c] (and (<= (:x c) (:x p) (+ (:x c) (:width c)))
                         (<= (:y c) (:y p) (+ (:y c) (:height c)))))
        nodes (filter #(.startsWith (:id %) "n:") kids)
        boxes (filter #(.startsWith (:id %) "b:") kids)]
    (:id (or (some #(when (in? %) %) (reverse nodes))
             (some #(when (in? %) %) (reverse boxes))))))
