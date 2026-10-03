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
