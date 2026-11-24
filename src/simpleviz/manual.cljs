(ns simpleviz.manual
  "Manual layout: nodes and boxes where their :pos [col row] puts them,
  on a grid of GRID px — top-level ones on the canvas, nested ones inside
  their box. A box none of whose contents has a :pos keeps its layered
  inside; one with them grows or shrinks to what it holds. Placing moves
  each element, its contents with it, and routes the edges between
  elements that moved apart: level or plumb through what their ends
  share, else bent once at a right angle. Pure."
  (:require [simpleviz.editor :refer [top-box-of]]))

(def GRID 20)

;; cell [0 0]'s top-left: room for what sticks out of an element there —
;; a self-loop and its label above it
(def ^:private MARGIN (* 2 GRID))

(def ROOT-ORIGIN
  "Where cell [0 0] of the canvas starts: room for what sticks out of an
  element there — a self-loop and its label above it."
  {:x MARGIN :y MARGIN})

(defn cell->xy
  "The top-left of cell [col row] counted from `origin` (the canvas's
  by default)."
  ([cell] (cell->xy cell ROOT-ORIGIN))
  ([[col row] origin]
   {:x (+ (:x origin) (* col GRID)) :y (+ (:y origin) (* row GRID))}))

(defn xy->cell
  "The cell, counted from `origin` (the canvas's by default), whose
  top-left is nearest x y — never left of or above cell 0."
  ([x y] (xy->cell x y ROOT-ORIGIN))
  ([x y origin]
   ;; Math.max, not max: rounding -0.3 gives -0, which max keeps
   [(js/Math.max 0 (js/Math.round (/ (- x (:x origin)) GRID)))
    (js/Math.max 0 (js/Math.round (/ (- y (:y origin)) GRID)))]))

(defn- up-to-grid [v] (* GRID (js/Math.ceil (/ v GRID))))

;; a box's padding when its layout doesn't carry one (transform/to-elk
;; puts :pad on every box with contents)
(def ^:private DEFAULT-PAD {:top 40 :left 14 :bottom 14 :right 14})

(defn- inner-origin
  "Where cell [0 0] inside box `b` starts, relative to the box: past its
  padding, rounded up to the grid — on a box on a cell, the canvas's grid."
  [b]
  (let [pad (or (:pad b) DEFAULT-PAD)]
    {:x (up-to-grid (:left pad)) :y (up-to-grid (:top pad))}))

;; the gap above the row of elements that have no :pos yet
(def ^:private STRIP-GAP 40)

(defn- top-id
  "The elk id of the top-level element holding elk id `id`, or `id`."
  [parent-of id]
  (if-let [b (top-box-of parent-of id)] (str "b:" b) id))

(defn- pos-of
  "The [col row] of elk id `id` in graph g, or nil."
  [g id]
  (if (.startsWith id "n:")
    (:pos (get (:nodes g) (.slice id 2)))
    (:pos (get (:boxes-by-name g) (.slice id 2)))))

(defn manual?
  "Is graph g laid out by hand: has a top-level node or box a :pos? (The
  server keeps a nested :pos only when its boxes, up to the top level,
  have one too.) In a comparison, a removed element's :pos is the old
  file's and doesn't count."
  [g]
  (let [placed? (fn [el] (and (some? (:pos el)) (not= "removed" (:diff el))))]
    (boolean (or (some placed? (js/Object.values (or (:nodes g) {})))
                 (some placed? (or (:boxes g) []))))))

(defn with-pos
  "Graph g with elk id `id` at cell `pos` — a drag's preview."
  [g id pos]
  (if (.startsWith id "n:")
    (assoc-in g [:nodes (.slice id 2) :pos] pos)
    (assoc-in g [:boxes-by-name (.slice id 2) :pos] pos)))

(defn with-cells
  "Graph g with each elk id of `cells` ({id [col row]}) at its cell."
  [g cells]
  (reduce (fn [g [id cell]] (with-pos g id cell)) g cells))

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

(defn- legs "The legs of a line through points `pts`: [[p q]]." [pts] (map vector pts (rest pts)))

(defn- line-edge
  "Edge e as one section through points `pts` — its ends, and between
  them where it bends — its label centered on the middle of the longest
  leg."
  [e pts]
  (let [a (first pts) b (last pts) bends (vec (rest (butlast pts)))
        leg-len (fn [[p q]] (+ (js/Math.abs (- (:x q) (:x p))) (js/Math.abs (- (:y q) (:y p)))))
        [p q] (apply max-key leg-len (legs pts))
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

(def ^:private LEN {"x" "width" "y" "height"})
(def ^:private OTHER {"x" "y" "y" "x"})
(defn- end "Where rect r ends on `axis`, \"x\" or \"y\"." [r axis] (+ (get r axis) (get r (LEN axis))))

(defn- shared
  "The range [lo hi] rects s and t share on `axis` — \"y\" their rows,
  \"x\" their columns — while apart on the other one; nil when they share
  less than MIN-SHARED."
  [s t axis]
  (let [o (OTHER axis)
        lo (max (get s axis) (get t axis))
        hi (min (end s axis) (end t axis))
        apart (or (<= (end s o) (get t o)) (<= (end t o) (get s o)))]
    (when (and apart (>= (- hi lo) MIN-SHARED)) [lo hi])))

(defn- facing-ends
  "From rect s's side facing rect t to t's side facing s, at `at` on
  `axis`: a level line for \"y\", a plumb one for \"x\"."
  [s t axis at]
  (let [q (OTHER axis)
        [a b] (if (<= (end s q) (get t q)) [(end s q) (get t q)] [(get s q) (end t q)])]
    [{q a axis at} {q b axis at}]))

(defn- outside?
  "Is v beyond rect r on `axis`, \"x\" or \"y\"?"
  [v r axis]
  (or (< v (get r axis)) (> v (end r axis))))

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
        ct {:x (+ (:x t) (* g (:width t))) :y (+ (:y t) (* g (:height t)))}
        ;; u: the axis the first leg runs along, w the other
        [u w] (if plumb-first ["y" "x"] ["x" "y"])]
    (when (and (outside? (get cs w) t w) (outside? (get ct u) s u))
      [{u (if (< (get cs u) (get ct u)) (end s u) (get s u)) w (get cs w)}
       {u (get ct u) w (get cs w)}
       {u (get ct u) w (if (< (get cs w) (get ct w)) (get t w) (end t w))}])))

(defn- crosses?
  "Does the level or plumb leg from p to q run through rect r's inside?"
  [[p q] r]
  ;; a: the axis the leg stays put on, b the one it runs along
  (let [[a b] (if (= (:y p) (:y q)) ["y" "x"] ["x" "y"])]
    (and (< (get r a) (get p a) (end r a))
         (< (max (min (get p b) (get q b)) (get r b)) (min (max (get p b) (get q b)) (end r b))))))

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
        wide (>= (js/Math.abs (- (:x (center t)) (:x (center s))))
                 (js/Math.abs (- (:y (center t)) (:y (center s)))))
        routes (keep identity [(some (fn [axis] (when-let [[lo hi] (shared s t axis)]
                                                  (facing-ends s t axis (+ lo (* f (- hi lo))))))
                                     ["y" "x"])
                               (elbow s t (not wide) f)
                               (elbow s t wide f)])
        ;; s, t and the boxes holding them: every rect holds itself
        in-way (fn [r] (not (or (holds? r s) (holds? r t))))
        clear? (fn [pts]
                 (let [ls (vec (legs pts))]
                   (not (some (fn [r] (and (in-way r) (some #(crosses? % r) ls)))
                              obstacles))))]
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

(defn- hand-placed?
  "Are the contents of layout element (or root) `c` placed by hand: has
  one of them a :pos in g?"
  [g c]
  (boolean (some #(some? (pos-of g (:id %))) (or (:children c) []))))

(defn- place-on-cells
  "Elements `kids` of one container, each on its :pos cell counted from
  `origin`, those without one in a row under the rest."
  [g kids origin]
  (let [cell (fn [c] (some-> (pos-of g (:id c)) (cell->xy origin)))
        bottom (reduce max (:y origin) (keep (fn [c] (when-let [p (cell c)] (+ (:y p) (:height c)))) kids))
        strip-y (up-to-grid (+ bottom STRIP-GAP))]
    (loop [cs kids x (:x origin) out []]
      (if (empty? cs)
        out
        (let [c (first cs)]
          (if-let [p (cell c)]
            (recur (rest cs) x (conj out (merge c p)))
            (recur (rest cs)
                   (up-to-grid (+ x (:width c) STRIP-GAP))
                   (conj out (assoc c :x x :y strip-y)))))))))

(defn- place-box
  "Box `b` with its contents placed — innermost first, as their sizes
  set its own: on their cells when it is placed by hand inside, and
  then as big as they need, else as laid out. `manual` (a js/Set)
  collects the boxes placed by hand inside."
  [g b manual]
  (if (empty? (or (:children b) []))
    b
    (let [kids (mapv #(place-box g % manual) (:children b))]
      (if (hand-placed? g b)
        (let [pad (or (:pad b) DEFAULT-PAD)
              kids' (place-on-cells g kids (inner-origin b))]
          (.add manual (:id b))
          (assoc b :children kids'
                 :width (max (or (:width (:min b)) 0)
                             (+ (reduce max 0 (map #(+ (:x %) (:width %)) kids')) (:right pad)))
                 :height (max (or (:height (:min b)) 0)
                              (+ (reduce max 0 (map #(+ (:y %) (:height %)) kids')) (:bottom pad)))))
        (assoc b :children kids)))))

(defn- ancestors-of
  "Elk id `id`'s boxes, innermost first, as elk ids."
  [parent-of id]
  (loop [b (get parent-of id) out []]
    (if (nil? b) out (recur (get parent-of (str "b:" b)) (conj out (str "b:" b))))))

(defn- child-of
  "The element directly in container `k` (elk id, or \"root\") holding
  elk id `id` — `id` itself when it is directly in `k`."
  [parent-of k id]
  (let [chain (into [id] (ancestors-of parent-of id))]
    (if (= k "root")
      (last chain)
      (some (fn [[inner outer]] (when (= outer k) inner)) (map vector chain (rest chain))))))

(defn place
  "Layered `layout` of graph g with each element that has a :pos moved
  to its cell — counted inside its box for a nested one — and those
  without one, in a container placed by hand, in a row under the rest.
  An edge whose ends kept their places relative to one another moves
  along (a self-loop, an edge inside a box laid out as before); every
  other edge in a container placed by hand is routed by route-edge."
  [layout g]
  (let [manual (js/Set.)
        kids (mapv #(place-box g % manual) (or (:children layout) []))
        kids' (place-on-cells g kids ROOT-ORIGIN)
        placed (assoc layout :children kids')
        _ (.add manual "root")
        old-rects (abs-rects layout)
        rects (abs-rects placed)
        po (:parent-of g)
        origin (fn [rs k] (if (= k "root") {:x 0 :y 0} (.get rs k)))
        ;; how far elk id `id` moved inside container k
        moved (fn [k id]
                (let [o (origin old-rects k) n (origin rects k)
                      a (.get old-rects id) b (.get rects id)]
                  {:x (- (- (:x b) (:x n)) (- (:x a) (:x o)))
                   :y (- (- (:y b) (:y n)) (- (:y a) (:y o)))}))
        ;; what an edge may run through: the top-level elements — a box
        ;; standing for all it holds — and all inside the top-level
        ;; elements holding its ends
        tops (mapv #(.get rects (:id %)) kids')
        inside (let [m (js/Map.)]
                 (doseq [[id r] (.entries rects)]
                   (let [k (top-id po id)]
                     (when (not= k id)
                       (when-not (.has m k) (.set m k #js []))
                       (.push (.get m k) r))))
                 m)
        container (fn [e] (if (nil? (:container e)) "root" (:container e)))
        ;; the edges between the same two elements, either way: how many,
        ;; and how many came before each
        pair (fn [e] (let [s (first (:sources e)) t (first (:targets e))]
                       (js/JSON.stringify (if (< s t) [s t] [t s]))))
        routed? (fn [e] (.has manual (container e)))
        totals (frequencies (map pair (filter routed? (or (:edges layout) []))))
        seen (js/Map.)
        edges (mapv (fn [e]
                      (let [k (container e)
                            s (first (:sources e))
                            t (first (:targets e))]
                        (cond
                          (not (routed? e)) e
                          (not (and (.has rects s) (.has rects t))) e
                          ;; moved as one: a self-loop, or both ends in an
                          ;; element whose inside is as laid out
                          (or (= s t)
                              (let [ks (child-of po k s)]
                                (and (= ks (child-of po k t)) (not (.has manual ks)))))
                          (shift-edge e (moved k s))
                          :else
                          (let [kp (pair e) i (or (.get seen kp) 0)
                                st (top-id po s) tt (top-id po t)]
                            (.set seen kp (inc i))
                            (assoc (route-edge e (.get rects s) (.get rects t) i (get totals kp)
                                               (concat tops (.get inside st) (when (not= st tt) (.get inside tt))))
                                   :container "root")))))
                    (or (:edges layout) []))
        root-edges (filter #(= "root" (container %)) edges)]
    (assoc placed
           :edges edges
           :width (+ GRID (reduce max 0 (concat (map (fn [c] (+ (:x c) (:width c))) kids')
                                                (map :x (edge-extent root-edges)))))
           :height (+ GRID (reduce max 0 (concat (map (fn [c] (+ (:y c) (:height c))) kids')
                                                 (map :y (edge-extent root-edges))))))))

(defn- under
  "The elements of placed `layout` under point p (root coordinates),
  outermost first: [[elk-id parent-id]]."
  [layout p]
  ((fn walk [parent ox oy]
     (mapcat (fn [c]
               (let [x (+ ox (:x c)) y (+ oy (:y c))]
                 (when (and (<= x (:x p) (+ x (:width c))) (<= y (:y p) (+ y (:height c))))
                   (cons [(:id c) (:id parent)] (walk c x y)))))
             (or (:children parent) [])))
   layout 0 0))

(defn element-at
  "The elk id of the element of a placed layout under point p (root
  coordinates) — the innermost, a node before a box, the last drawn
  first — or nil."
  [layout p]
  (let [hits (map first (under layout p))]
    (or (last (filter #(.startsWith % "n:") hits))
        (last hits))))

(defn- locate
  "[element parent abs-origin-of-parent] of elk id `id` in placed
  `layout`, or nil."
  [layout id]
  ((fn walk [parent ox oy]
     (some (fn [c]
             (if (= id (:id c))
               [c parent {:x ox :y oy}]
               (walk c (+ ox (:x c)) (+ oy (:y c)))))
           (or (:children parent) [])))
   layout 0 0))

(defn frame
  "Where elk id `id` sits in placed `layout`, in root coordinates: :at
  its top-left, :origin where the cells of its container start, and
  :cell the cell nearest it there; nil when not in the layout."
  [layout id]
  (when-let [[c parent po] (locate layout id)]
    (let [io (if (= "root" (:id parent)) ROOT-ORIGIN (inner-origin parent))
          origin {:x (+ (:x po) (:x io)) :y (+ (:y po) (:y io))}
          at {:x (+ (:x po) (:x c)) :y (+ (:y po) (:y c))}]
      {:origin origin :at at :cell (xy->cell (:x at) (:y at) origin)})))

(defn pins
  "{elk-id [col row]}: the cells to write along with moving elk id
  `id` of placed `layout` (of graph g), so that nothing else jumps: in
  each container up to it not yet placed by hand, all it holds where it
  is now; each box holding it that has no :pos yet, where it is now."
  [layout g id]
  (let [boxes (reverse (ancestors-of (:parent-of g) id))
        chain (map vector (cons "root" boxes) (concat boxes [id]))
        node-of (fn [k] (if (= k "root") layout (first (locate layout k))))]
    (reduce (fn [out [k next-id]]
              (let [c (node-of k)]
                (cond
                  (not (hand-placed? g c))
                  (into out (map (fn [kid] [(:id kid) (:cell (frame layout (:id kid)))]) (:children c)))
                  (and (not= next-id id) (nil? (pos-of g next-id)))
                  (assoc out next-id (:cell (frame layout next-id)))
                  :else out)))
            {} chain)))
