(ns simpleviz.grid
  "Grid layout (:grid on top-level boxes): gridded boxes on exact cells,
  ELK inside each box, loose elements beside the box they connect to,
  edges between cells routed through the gaps. Pure: ELK is passed in."
  (:require [simpleviz.editor :refer [top-box-of]]
            [simpleviz.transform :refer [element-run layout-positions seed-layout seedable?]]))

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
                                (if (pos? n) (max g (+ 40 (* LANE n))) g)))
                    gaps)))

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

(defn- v-blocked?
  "Is vertical gap i, in row band j, inside a spanning element? `spans`
  [{:i0 :i1 :j0 :j1}] are column/row track index ranges."
  [spans i j]
  (some (fn [sp] (and (< (:i0 sp) i) (<= i (:i1 sp)) (<= (:j0 sp) j) (<= j (:j1 sp)))) spans))

(defn- h-blocked?
  "Is horizontal gap j, in column band i, inside a spanning element?"
  [spans i j]
  (some (fn [sp] (and (< (:j0 sp) j) (<= j (:j1 sp)) (<= (:i0 sp) i) (<= i (:i1 sp)))) spans))

(defn- find-path
  "Crossings [[i j] ...] of the fewest-bends-then-shortest path from
  entry `from` to entry `to`, never along a gap a spanning element
  covers."
  [vx hy from to spans]
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
              (when (and (<= 0 i2) (< i2 nv) (<= 0 j2) (< j2 nh)
                         (not (if (= h2 "v")
                                (v-blocked? spans i (min j j2))
                                (h-blocked? spans (min i i2) j))))
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
  [routes vx hy & [spans]]
  (let [spans (or spans [])
        corners {}
        users {}
        ;; two ends on one gap connect straight along it, unless a
        ;; spanning element covers the gap somewhere between them
        straight? (fn [from to]
                    (and (= (:axis from) (:axis to)) (= (:gap from) (:gap to))
                         (let [v? (= (:axis from) "v")
                               cs (if v? hy vx)
                               k0 (band cs (if v? (:y from) (:x from)))
                               k1 (band cs (if v? (:y to) (:x to)))]
                           (not (some (fn [k] (if v? (v-blocked? spans (:gap from) k) (h-blocked? spans k (:gap from))))
                                      (range (min k0 k1) (inc (max k0 k1))))))))]
    (doseq [r routes]
      (let [{:keys [from to]} r
            mid (if (straight? from to)
                  []
                  (mapv (fn [[i j]] {:vg i :hg j}) (find-path vx hy from to spans)))
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

(defn- simplify
  "points without the middle ones of straight runs."
  [points]
  (let [n (count points)
        same? (fn [a b c] (or (and (= (:x a) (:x b)) (= (:x b) (:x c)))
                              (and (= (:y a) (:y b)) (= (:y b) (:y c)))))]
    (if (< n 3)
      (vec points)
      (into [(first points)]
            (conj (filterv some? (mapv (fn [i] (let [p (nth points i)]
                                                 (when-not (same? (nth points (dec i)) p (nth points (inc i))) p)))
                                       (range 1 (dec n))))
                  (last points))))))

(defn- overlaps? [a b]
  (and (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
       (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a)))))

(defn label-at
  "Top-left of a w×h label on `points`, clear of the rects in `taken`
  ({:x :y :w :h}). Tried in rounds — on each segment's usual side (above
  a horizontal one, right of a vertical one) within the segment; then on
  the other side; then sliding past the segment's ends — segments
  longest first within a round, each from its centre outwards. Nothing
  clear: the longest segment's centre. `rotate?`: on a vertical segment
  the label runs along it, turned 90° (h wide, w tall) — the result then
  also carries the rect's :w :h and :rotated."
  [points w0 h0 & [taken rotate?]]
  (let [points (simplify points)
        taken (or taken [])
        segs (sort-by (fn [[a b]] (- (+ (js/Math.abs (- (:x b) (:x a))) (js/Math.abs (- (:y b) (:y a))))))
                      (mapv (fn [i] [(nth points i) (nth points (inc i))]) (range (dec (count points)))))
        spots (fn [[a b] other-side? bounded?]
                (let [horiz? (= (:y a) (:y b))
                      turned? (and rotate? (not horiz?))
                      w (if turned? h0 w0)
                      h (if turned? w0 h0)
                      lo (if horiz? (min (:x a) (:x b)) (min (:y a) (:y b)))
                      hi (if horiz? (max (:x a) (:x b)) (max (:y a) (:y b)))
                      size (if horiz? w h)
                      step (+ size 4)
                      start (- (/ (+ lo hi) 2) (/ size 2))
                      at (fn [v] (cond-> (if horiz?
                                           {:x v :y (if other-side? (+ (:y a) 2) (- (:y a) h 2))}
                                           {:x (if other-side? (- (:x a) w 4) (+ (:x a) 4)) :y v})
                                   rotate? (assoc :w w :h h :rotated turned?)))
                      fits? (fn [v] (or (not bounded?) (and (>= v lo) (<= (+ v size) hi))))
                      n (if bounded? (js/Math.ceil (/ (- hi lo) step)) 6)]
                  (into [(at start)]
                        (mapcat (fn [k] (keep (fn [v] (when (fits? v) (at v)))
                                              [(+ start (* k step)) (- start (* k step))]))
                                (range 1 (inc n))))))
        clear? (fn [p] (not (some (fn [t] (overlaps? (assoc p :w (or (:w p) w0) :h (or (:h p) h0)) t))
                                  taken)))
        round (fn [other-side? bounded?]
                (some (fn [seg] (some (fn [p] (when (clear? p) p)) (spots seg other-side? bounded?))) segs))]
    (or (round false true) (round true true) (round false false) (round true false)
        (first (spots (first segs) false true)))))

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
  (simplify
   (reduce (fn [acc p]
            (let [q (peek acc)]
              (if (and (some? q) (< (js/Math.abs (- (:x q) (:x p))) 0.01) (< (js/Math.abs (- (:y q) (:y p))) 0.01))
                acc
                (conj acc p))))
          [] ps)))

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

(defn- with-direction
  "A copy of ELK input `run` laid out in direction `dir`."
  [run dir]
  (let [c (js/JSON.parse (js/JSON.stringify run))]
    (assoc c :layoutOptions (assoc (:layoutOptions c) "elk.direction" dir))))

(defn- portless
  "A copy of element run `run` without its ports and the edges to them."
  [run]
  (let [c (js/JSON.parse (js/JSON.stringify run))]
    (assoc c
           :children (mapv (fn [ch] (assoc ch :ports [])) (:children c))
           :edges (filterv (fn [e] (not (:port e))) (:edges c)))))

(def SQUARE-RATIO
  "A box laid out wider than this many times its height also tries
  top-to-bottom (layout-grid's :square)."
  1.3)

(def WRAP-OPTIONS
  "ELK options that wrap a long left-to-right layout into rows stacked
  top to bottom, aiming at about this width:height."
  {"elk.layered.wrapping.strategy" "MULTI_EDGE"
   "elk.aspectRatio" "1.6"})

(defn squareness
  "How far an ELK result is from square: 0 for square, growing either way."
  [r]
  (js/Math.abs (js/Math.log (/ (max 1 (:width r)) (max 1 (:height r))))))

(defn turned-labels
  "A copy of ELK input `run` whose edge labels are turned 90°: as tall as
  their text is long, as wide as a line — for a top-to-bottom layout, so
  each label runs along its vertical edge."
  [run]
  (let [c (js/JSON.parse (js/JSON.stringify run))]
    (assoc c :edges (mapv (fn [e] (if (seq (:labels e))
                                    (assoc e :labels (mapv (fn [lb] (assoc lb :width (:height lb) :height (:width lb)))
                                                           (:labels e)))
                                    e))
                          (or (:edges c) [])))))

(defn- select-keys*
  "Map m restricted to keys ks (JS-object keys)."
  [m ks]
  (let [out {}] (doseq [k ks] (assoc! out k (get m k))) out))

(defn ^:async layout-grid
  "The grid layout of `graph` (collapse applied) as an ELK-shaped result:
  top-level elements as :children at their places, every edge at the
  root with absolute points. `elk-graph` is (to-elk graph ..), `run-elk`
  lays out one ELK input (a promise), `prev` is the previous layout or
  nil. A box whose ELK input is unchanged since `prev` keeps its result
  (its :runs) — ELK's interactive mode re-places a box's ports, so even
  a seeded run of an unchanged box could move its contents; a changed
  box is seeded with the previous positions. The result carries :runs
  {id {:key input-json :result elk-result :dir ..}} for the next call.
  `opts` (compact mode, all off by default): :square — a box laid out
  much wider than tall is also tried top-to-bottom, and the squarer
  result kept; a box keeps the direction it had in `prev`. Labels in a
  top-to-bottom box run along its vertical edges, turned (:rotated).
  :wrap-strip — the strip wraps into rows rather than one long line.
  :rotate-labels — labels between cells on a vertical stretch turn too."
  [graph elk-graph run-elk prev & [opts]]
  (let [positions (when (some? prev) (layout-positions prev))
        prev-runs (or (when (some? prev) (:runs prev)) {})
        po (:parent-of graph)
        cells (grid-cells graph)
        {:keys [attached strip]} (attach-loose graph cells)
        sl (slots cells attached)
        kids {}
        _ (doseq [c (:children elk-graph)] (assoc! kids (:id c) c))
        compound? (fn [id] (some? (:children (get kids id))))
        in-strip (js/Set. strip)
        loose (filterv (fn [id] (some? (get attached id))) (top-items graph))
        inner {} ports {} strip-edges [] cross [] loops []
        ;; ELK inputs name edges by their endpoints, not by the server's
        ;; positional ids: a new edge elsewhere renumbers those, and a
        ;; box's input — and so its reuse — must not change with them
        cid (fn [e] (str (first (:sources e)) "→" (first (:targets e))))
        orig (js/Map.)
        canon (fn [e] (let [k (cid e)] (.set orig k (:id e)) (assoc e :id k)))
        port-edge-ids (js/Set.)]
    ;; sort the edges: inside one element, inside the strip, across cells
    (doseq [e (:edges elk-graph)]
      (let [s (first (:sources e)) t (first (:targets e))
            ts (top-of po s) tt (top-of po t)]
        (cond
          (= ts tt) (cond
                      ;; inside a strip box, or a loop in the strip: the strip run
                      (.has in-strip ts) (.push strip-edges (canon e))
                      (compound? ts) (assoc! inner ts (conj (or (get inner ts) []) (canon e)))
                      ;; a loop on a placed leaf: drawn beside it below
                      :else (.push loops e))
          (and (.has in-strip ts) (.has in-strip tt)) (.push strip-edges (canon e))
          :else
          (let [k (cid e)
                c {:e e :cid k :s s :t t :ts ts :tt tt
                   :sside (port-side (get sl ts) (get sl tt))
                   :tside (port-side (get sl tt) (get sl ts))}]
            (.push cross c)
            (when (compound? ts)
              (assoc! ports ts (conj (or (get ports ts) []) {:id (str "p:" k ":s") :side (:sside c)}))
              (when (not= s ts)
                (.add port-edge-ids (str k ":s"))
                (assoc! inner ts (conj (or (get inner ts) [])
                                       {:id (str k ":s") :port true :sources [s] :targets [(str "p:" k ":s")]}))))
            (when (compound? tt)
              (assoc! ports tt (conj (or (get ports tt) []) {:id (str "p:" k ":t") :side (:tside c)}))
              (when (not= t tt)
                (.add port-edge-ids (str k ":t"))
                (assoc! inner tt (conj (or (get inner tt) [])
                                       {:id (str k ":t") :port true :sources [(str "p:" k ":t")] :targets [t]}))))))))
    ;; one ELK run per placed compound element, one for the strip
    (let [run-ids (filterv compound? (into (vec (js/Object.keys cells)) loose))
          runs {}
          solve (fn [input run]
                  ;; ELK writes into its input: hand it a copy, so the
                  ;; caller's elk-graph (and the next call's keys) stay clean
                  (-> (run-elk (js/JSON.parse (js/JSON.stringify input)))
                      ;; some port mixes make ELK throw in one direction
                      ;; and not in another (sides stay fixed); last
                      ;; resort: no ports, the edges leave from the border
                      (.catch (fn [_] (run-elk (with-direction input "DOWN"))))
                      (.catch (fn [_] (run-elk (with-direction input "LEFT"))))
                      (.catch (fn [_] (run-elk (with-direction input "UP"))))
                      (.catch (fn [_] (run-elk (portless run))))))
          ;; :square — the top-to-bottom try, its labels turned to run
          ;; along the vertical edges; a run that fails keeps `fallback`
          solve-down (fn [input fallback]
                       (-> (run-elk (turned-labels (with-direction input "DOWN")))
                           (.then (fn [r] {:result r :dir "DOWN"}))
                           (.catch (fn [_] fallback))))
          run-of (fn [id run]
                   (let [k (js/JSON.stringify run)
                         old (get prev-runs id)]
                     (if (and (some? old) (= k (:key old)))
                       (do (assoc! runs id old) (js/Promise.resolve (:result old)))
                       (let [rel (when (and (some? positions) (not= id "strip"))
                                   (run-positions positions po id))
                             input (if (and (some? rel) (seedable? run rel)) (seed-layout run rel) run)
                             square? (and (:square opts) (not= id "strip"))]
                         (-> (cond
                               (not square?) (.then (solve input run) (fn [r] {:result r}))
                               ;; an edit keeps the box's direction, so it doesn't flip
                               (= "DOWN" (:dir old)) (solve-down input nil)
                               (= "RIGHT" (:dir old)) (.then (solve input run) (fn [r] {:result r :dir "RIGHT"}))
                               :else
                               (.then (solve input run)
                                      (fn [r]
                                        (let [right {:result r :dir "RIGHT"}]
                                          (if (> (/ (:width r) (max 1 (:height r))) SQUARE-RATIO)
                                            (.then (solve-down input right)
                                                   (fn [d] (if (< (squareness (:result d)) (squareness r)) d right)))
                                            right)))))
                             ;; a failed top-to-bottom edit run: left-to-right after all
                             (.then (fn [d] (or d (.then (solve input run) (fn [r] {:result r :dir "RIGHT"})))))
                             (.then (fn [d]
                                      (assoc! runs id (assoc d :key k))
                                      (:result d))))))))
          results (js-await (js/Promise.all
                             (mapv (fn [id] (run-of id (element-run (:layoutOptions elk-graph) (get kids id)
                                                                    (or (get ports id) []) (or (get inner id) []))))
                                   run-ids)))
          res {}
          _ (doseq [i (range (count run-ids))] (assoc! res (nth run-ids i) (nth results i)))
          strip-res (when (pos? (count strip))
                      (js-await (run-of "strip"
                                        {:id "root"
                                         :layoutOptions (cond-> (assoc (:layoutOptions elk-graph)
                                                                       "elk.padding" "[top=0,left=0,bottom=0,right=0]")
                                                          (:wrap-strip opts) (merge WRAP-OPTIONS))
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
          ;; gridded boxes spanning several tracks cover the gaps inside them
          spans (vec (keep (fn [id]
                             (let [sl' (get sl id)
                                   sp {:i0 (.indexOf col-ts (:c0 sl')) :i1 (.indexOf col-ts (:c1 sl'))
                                       :j0 (.indexOf row-ts (:r0 sl')) :j1 (.indexOf row-ts (:r1 sl'))}]
                               (when (or (< (:i0 sp) (:i1 sp)) (< (:j0 sp) (:j1 sp))) sp)))
                           (js/Object.keys cells)))
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
              (doseq [[id side key] [[(:ts c) (:sside c) (str (:cid c) ":s")]
                                     [(:tt c) (:tside c) (str (:cid c) ":t")]]]
                (let [k (str id "|" side)]
                  (assoc! leaf-ends k (conj (or (get leaf-ends k) []) key)))))
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
                         (when (some? pt)
                           {:x (+ (:x p) (:x pt) (/ (:width pt) 2)) :y (+ (:y p) (:y pt) (/ (:height pt) 2))})))
          end-entry (fn [places c end]
                      (let [id (if (= end "s") (:ts c) (:tt c))
                            side (if (= end "s") (:sside c) (:tside c))
                            p (get places id)
                            key (str (:cid c) ":" end)
                            ip (inner-points id p key)
                            pt (or
                                 (when (some? ip) (if (= end "s") (last ip) (first ip)))
                                 (when (compound? id) (port-point id p (str "p:" key)))
                                 ;; a leaf, or a box laid out without its ports
                                 (let [ks (get leaf-ends (str id "|" side))
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
                                  (centres ca) (centres ra) spans)
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
                                                 down? (= "DOWN" (:dir (get runs id)))
                                                 {:keys [ox oy]} (run-offset id (get places id))]
                                             (keep (fn [e]
                                                     (when-not (.has port-edge-ids (:id e))
                                                       (root-edge (.get orig (:id e)) (abs-points e rp ox oy)
                                                                  (cond->> (abs-labels e rp ox oy)
                                                                    down? (mapv (fn [lb] (assoc lb :rotated true)))))))
                                                   (:edges r))))
                                         (js/Object.keys res)))
                  strip-edges' (if (some? strip-res)
                                 (let [rp (layout-positions strip-res)]
                                   (mapv (fn [e] (root-edge (.get orig (:id e)) (abs-points e rp sx sy) (abs-labels e rp sx sy)))
                                         (or (:edges strip-res) [])))
                                 [])
                  ;; labels ELK placed inside boxes are taken; each cross
                  ;; edge's label then takes the next free spot on its path
                  leaf? (let [m {}]
                          ((fn walk [cs] (doseq [c cs] (assoc! m (:id c) (nil? (:children c))) (walk (or (:children c) []))))
                           children)
                          m)
                  taken (into (mapv (fn [lb] {:x (:x lb) :y (:y lb) :w (:width lb) :h (:height lb)})
                                    (mapcat (fn [e] (:labels e)) (into run-edges strip-edges')))
                              ;; and every node (or collapsed box) on the canvas
                              (keep (fn [[id p]] (when (get leaf? id) {:x (:x p) :y (:y p) :w (:w p) :h (:h p)}))
                                    (js/Object.entries (layout-positions {:children children}))))
                  loop-edges (mapv (fn [e]
                                     (let [id (first (:sources e))
                                           p (get places id)
                                           nd (node-of id)
                                           x (+ (:x p) (:width nd))
                                           y1 (+ (:y p) (* 0.3 (:height nd)))
                                           y2 (+ (:y p) (* 0.7 (:height nd)))]
                                       (root-edge (:id e) [{:x x :y y1} {:x (+ x 16) :y y1} {:x (+ x 16) :y y2} {:x x :y y2}]
                                                  (mapv (fn [lb] (assoc lb :x (+ x 20) :y (- y1 (/ (:height lb) 2))))
                                                        (or (:labels e) [])))))
                                   loops)
                  cross-edges (mapv (fn [[c s t]]
                                      (let [e (:e c)
                                            ps (dedupe-points (into (into (:inner s) (get (:points routed) (:id e))) (:inner t)))
                                            lb (first (or (:labels e) []))]
                                        (root-edge (:id e) ps
                                                   (if (some? lb)
                                                     (let [at (label-at ps (:width lb) (:height lb) taken (:rotate-labels opts))
                                                           w (or (:w at) (:width lb))
                                                           h (or (:h at) (:height lb))]
                                                       (.push taken {:x (:x at) :y (:y at) :w w :h h})
                                                       [(cond-> (assoc lb :x (:x at) :y (:y at) :width w :height h)
                                                          (:rotated at) (assoc :rotated true))])
                                                     []))))
                                    ends)
                  strip-w (if (some? strip-res) (+ sx (:width strip-res) MARGIN) 0)]
              (cond-> {:id "root"
                       :width (max (+ (:end ca) MARGIN) strip-w)
                       :height (+ (if (some? strip-res) (+ sy (:height strip-res)) (:end ra)) MARGIN)
                       :children children
                       :edges (into (into (into run-edges strip-edges') loop-edges) cross-edges)
                       :runs runs}
                (some? prev) (assoc :seeded true)))))))))
