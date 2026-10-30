(ns simpleviz.manual
  "Manual layout: top-level nodes and boxes where their :pos [col row]
  puts them, on a grid of GRID px. The layered layout still arranges what
  is inside each box; placing moves each top-level element, its contents
  with it, and routes the edges between top-level elements: level or
  plumb through what their ends share, else bent once at a right angle.
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

(defn- line-edge
  "Edge e as one section through points `pts` — its ends, and between
  them where it bends — its label centered on the middle of the longest
  leg."
  [e pts]
  (let [a (first pts) b (last pts) bends (vec (rest (butlast pts)))
        leg-len (fn [[p q]] (+ (js/Math.abs (- (:x q) (:x p))) (js/Math.abs (- (:y q) (:y p)))))
        [p q] (apply max-key leg-len (map vector pts (rest pts)))
        mid {:x (/ (+ (:x p) (:x q)) 2) :y (/ (+ (:y p) (:y q)) 2)}]
    (cond-> (-> e
                (assoc :sections [(cond-> {:id (str (:id e) "_s0") :startPoint a :endPoint b}
                                    (seq bends) (assoc :bendPoints bends))])
                (dissoc :junctionPoints))
      (some? (:labels e))
      (assoc :labels (mapv (fn [l] (assoc l :x (- (:x mid) (/ (:width l) 2))
                                          :y (- (:y mid) (/ (:height l) 2))))
                           (:labels e))))))

;; what two elements must share, in px, for an edge between them to run
;; level or plumb
(def ^:private MIN-SHARED 6)

(defn- shared
  "The range [lo hi] rects s and t share on `axis` — \"y\" their rows,
  \"x\" their columns — while apart on the other one; nil when they share
  less than MIN-SHARED."
  [s t axis]
  (let [[p len q qlen] (if (= axis "y") ["y" "height" "x" "width"] ["x" "width" "y" "height"])
        lo (max (get s p) (get t p))
        hi (min (+ (get s p) (get s len)) (+ (get t p) (get t len)))
        apart (or (<= (+ (get s q) (get s qlen)) (get t q))
                  (<= (+ (get t q) (get t qlen)) (get s q)))]
    (when (and apart (>= (- hi lo) MIN-SHARED)) [lo hi])))

(defn- facing-ends
  "From rect s's side facing rect t to t's side facing s, at `at` on
  `axis`: a level line for \"y\", a plumb one for \"x\"."
  [s t axis at]
  (if (= axis "y")
    (if (<= (+ (:x s) (:width s)) (:x t))
      [{:x (+ (:x s) (:width s)) :y at} {:x (:x t) :y at}]
      [{:x (:x s) :y at} {:x (+ (:x t) (:width t)) :y at}])
    (if (<= (+ (:y s) (:height s)) (:y t))
      [{:x at :y (+ (:y s) (:height s))} {:x at :y (:y t)}]
      [{:x at :y (:y s)} {:x at :y (+ (:y t) (:height t))}])))

(defn- outside?
  "Is v beyond rect r on `axis`, \"x\" or \"y\"?"
  [v r axis]
  (let [p (get r axis) len (get r (if (= axis "x") "width" "height"))]
    (or (< v p) (> v (+ p len)))))

(defn- elbow
  "Ends and corner [a bend b] of a right-angled line from rect s to rect
  t: leaving s level and entering t plumb, or with `plumb-first` the other
  way round; nil when a leg would run through s or t. It leaves at
  fraction f of s's side — a half: the middle — and enters t where the
  edges of one pair nest: at f, or at 1 − f when it heads right and down
  or left and up."
  [s t plumb-first f]
  (let [g (if (= (< (:x (center s)) (:x (center t))) (< (:y (center s)) (:y (center t)))) (- 1 f) f)
        cs {:x (+ (:x s) (* f (:width s))) :y (+ (:y s) (* f (:height s)))}
        ct {:x (+ (:x t) (* g (:width t))) :y (+ (:y t) (* g (:height t)))}]
    (if plumb-first
      (when (and (outside? (:x cs) t "x") (outside? (:y ct) s "y"))
        [{:x (:x cs) :y (if (< (:y ct) (:y cs)) (:y s) (+ (:y s) (:height s)))}
         {:x (:x cs) :y (:y ct)}
         {:x (if (< (:x cs) (:x ct)) (:x t) (+ (:x t) (:width t))) :y (:y ct)}])
      (when (and (outside? (:y cs) t "y") (outside? (:x ct) s "x"))
        [{:x (if (< (:x cs) (:x ct)) (+ (:x s) (:width s)) (:x s)) :y (:y cs)}
         {:x (:x ct) :y (:y cs)}
         {:x (:x ct) :y (if (< (:y cs) (:y ct)) (:y t) (+ (:y t) (:height t)))}]))))

(defn- crosses?
  "Does the level or plumb leg from p to q run through rect r's inside?"
  [[p q] r]
  (let [in? (fn [v lo len] (< lo v (+ lo len)))
        spans? (fn [a b lo len] (< (max (min a b) lo) (min (max a b) (+ lo len))))]
    (if (= (:y p) (:y q))
      (and (in? (:y p) (:y r) (:height r)) (spans? (:x p) (:x q) (:x r) (:width r)))
      (and (in? (:x p) (:x r) (:width r)) (spans? (:y p) (:y q) (:y r) (:height r))))))

(defn- holds?
  "Does rect r hold rect q?"
  [r q]
  (and (<= (:x r) (:x q)) (<= (:y r) (:y q))
       (>= (+ (:x r) (:width r)) (+ (:x q) (:width q)))
       (>= (+ (:y r) (:height r)) (+ (:y q) (:height q)))))

(defn- route-edge
  "Edge e from rect s to rect t: level through the rows they share, else
  plumb through the columns they share, else bent once at a right angle
  — leaving s along the longer way to t first — else straight border to
  border. The first of these that runs through none of `obstacles` (but
  s, t and the boxes holding them) wins, else the first. The `n` edges
  between the same two share the range evenly, e the `i`th."
  [e s t i n obstacles]
  (let [f (/ (inc i) (inc n))
        ys (shared s t "y")
        xs (when (nil? ys) (shared s t "x"))
        wide (>= (js/Math.abs (- (:x (center t)) (:x (center s))))
                 (js/Math.abs (- (:y (center t)) (:y (center s)))))
        routes (keep identity [(when-let [[lo hi] (or ys xs)]
                                 (facing-ends s t (if ys "y" "x") (+ lo (* f (- hi lo)))))
                               (elbow s t (not wide) f)
                               (elbow s t wide f)])
        in-way (fn [r] (and (not (identical? r s)) (not (identical? r t))
                            (not (holds? r s)) (not (holds? r t))))
        clear? (fn [pts]
                 (not (some (fn [r] (and (in-way r) (some #(crosses? % r) (map vector pts (rest pts)))))
                            obstacles)))]
    (line-edge e (or (first (filter clear? routes))
                     (first routes)
                     [(border-point s (center t)) (border-point t (center s))]))))

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
  every other edge between top-level elements is routed by route-edge."
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
        ;; what an edge may run through: the top-level elements — a box
        ;; standing for all it holds — and the insides of the boxes
        ;; holding its ends
        tops (mapv #(.get rects (:id %)) kids')
        inside (let [m (js/Map.)]
                 (doseq [[id r] (.entries rects)]
                   (let [k (top-id po id)]
                     (when (not= k id) (.set m k (conj (or (.get m k) []) r)))))
                 m)
        root? (fn [e] (or (nil? (:container e)) (= "root" (:container e))))
        ;; the edges between the same two elements, either way: how many,
        ;; and how many came before each
        pair (fn [e] (let [s (first (:sources e)) t (first (:targets e))]
                       (js/JSON.stringify (if (< s t) [s t] [t s]))))
        totals (frequencies (map pair (filter root? (or (:edges layout) []))))
        seen (js/Map.)
        edges (mapv (fn [e]
                      (if (root? e)
                        (let [s (first (:sources e))
                              t (first (:targets e))
                              st (top-id po s)
                              tt (top-id po t)]
                          (cond
                            (= st tt) (shift-edge e (get delta st))
                            (and (.has rects s) (.has rects t))
                            (let [k (pair e) i (or (.get seen k) 0)]
                              (.set seen k (inc i))
                              (route-edge e (.get rects s) (.get rects t) i (get totals k)
                                          (concat tops (.get inside st) (when (not= st tt) (.get inside tt)))))
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
