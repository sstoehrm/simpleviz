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
