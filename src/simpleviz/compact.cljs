(ns simpleviz.compact
  "The compact layout: big diagrams spread down as well as across. Every
  top-level box gets a cell of a roughly square grid — connected boxes
  side by side, a box's own :grid kept — and the grid layout does the
  rest: edges between boxes leave on whichever side faces their other
  end, top and bottom too. A box laid out much wider than tall turns
  top to bottom inside. A graph without boxes wraps its rows instead.
  Pure: ELK is passed in."
  (:require [simpleviz.grid :as grid]))

(defn- top-boxes
  "Names of the top-level boxes, in the graph's order."
  [graph]
  (let [po (:parent-of graph)]
    (vec (keep (fn [b] (when (nil? (get po (str "b:" (:name b)))) (:name b))) (:boxes graph)))))

(defn- box-weights
  "{box {other-box n}}: how many edges join two top-level boxes."
  [graph]
  (let [po (:parent-of graph)
        out {}
        bump! (fn [a b] (let [m (or (get out a) (let [m {}] (assoc! out a m) m))]
                          (assoc! m b (inc (or (get m b) 0)))))]
    (doseq [e (:edges graph)]
      (let [[s t] (grid/edge-ends e)
            ts (grid/top-of po s)
            tt (grid/top-of po t)]
        (when (and (not= ts tt) (.startsWith ts "b:") (.startsWith tt "b:"))
          (bump! (.slice ts 2) (.slice tt 2))
          (bump! (.slice tt 2) (.slice ts 2)))))
    out))

(defn auto-cells
  "{box-name {:col :row :w 1 :h 1}} for the top-level boxes without a
  :grid: `ncols` columns, by default as many as a square of all
  top-level boxes needs (at least as many as the :grid cells use), and
  as few rows as hold them all. Boxes take cells one by one —
  the most connected first, then those joined to boxes already placed —
  each the free cell closest (by edges × distance) to the boxes it is
  joined to, else the first free one in reading order."
  [graph & [ncols]]
  (let [names (top-boxes graph)
        by-name (into {} (map (fn [b] [(:name b) b])) (:boxes graph))
        fixed (filterv (fn [n] (some? (:grid (get by-name n)))) names)
        auto (filterv (fn [n] (nil? (:grid (get by-name n)))) names)
        taken (js/Set.)
        _ (doseq [n fixed]
            (let [{:keys [col row w h]} (:grid (get by-name n))]
              (doseq [c (range col (+ col w)) r (range row (+ row h))] (.add taken (str c "," r)))))
        ncols (max (or ncols (js/Math.ceil (js/Math.sqrt (count names))))
                   (reduce max 1 (map (fn [n] (let [g (:grid (get by-name n))] (+ (:col g) (:w g)))) fixed)))
        ;; as few rows as hold every box; one more only when they're full
        nrows (atom (js/Math.ceil (/ (+ (count auto) (.-size taken)) ncols)))
        weights (box-weights graph)
        degree (fn [n] (reduce + 0 (js/Object.values (or (get weights n) {}))))
        at {}
        _ (doseq [n fixed] (let [g (:grid (get by-name n))] (assoc! at n {:col (:col g) :row (:row g)})))
        placed (js/Set.)
        free (fn [] (loop []
                      (let [cells (vec (for [r (range @nrows) c (range ncols)
                                             :when (not (.has taken (str c "," r)))]
                                         {:col c :row r}))]
                        (if (seq cells) cells (do (swap! nrows inc) (recur))))))
        cost (fn [n cell]
               (reduce + 0 (keep (fn [[m w]]
                                   (when-let [p (get at m)]
                                     (* w (+ (js/Math.abs (- (:col p) (:col cell)))
                                             (js/Math.abs (- (:row p) (:row cell)))))))
                                 (js/Object.entries (or (get weights n) {})))))
        ;; next: joined to a placed box the most, else the most connected
        pick (fn []
               (let [left (filterv (fn [n] (not (.has placed n))) auto)
                     pull (fn [n] (reduce + 0 (keep (fn [[m w]] (when (some? (get at m)) w))
                                                    (js/Object.entries (or (get weights n) {})))))]
                 (reduce (fn [best n]
                           (if (or (nil? best)
                                   (> (pull n) (pull best))
                                   (and (= (pull n) (pull best)) (> (degree n) (degree best))))
                             n best))
                         nil left)))
        out {}]
    (loop []
      (when-let [n (pick)]
        (let [cells (free)
              best (reduce (fn [b cell] (if (or (nil? b) (< (cost n cell) (cost n b))) cell b))
                           nil cells)]
          (.add placed n)
          (.add taken (str (:col best) "," (:row best)))
          (assoc! at n best)
          (assoc! out n (assoc best :w 1 :h 1))
          (recur))))
    out))

(defn with-cells
  "graph with every top-level box on a cell: its own :grid, else one
  from auto-cells (with `ncols` columns, when given)."
  [graph & [ncols]]
  (let [cells (auto-cells graph ncols)]
    (assoc graph :boxes (mapv (fn [b] (if-let [c (get cells (:name b))] (assoc b :grid c) b))
                              (:boxes graph)))))

(def ASPECT
  "The width:height the grid aims at — about a screen's."
  1.6)

(defn- columns-used [graph cells]
  (reduce max 1 (map (fn [b] (let [g (or (get cells (:name b)) (:grid b))]
                               (if (some? g) (+ (:col g) (:w g)) 0)))
                     (:boxes graph))))

(defn estimate
  "[width height] of the grid with `ncols` columns, given each top-level
  box's laid-out size (`sizes` {box-name {:w :h}}): every column as
  wide as its widest box, every row as tall as its tallest, gaps
  between. One-cell boxes only; a spanning :grid counts in its first
  cell."
  [graph sizes ncols]
  (let [cells (merge (auto-cells graph ncols)
                     (into {} (keep (fn [b] (when (some? (:grid b)) [(:name b) (:grid b)]))) (:boxes graph)))
        cw {} rh {}]
    (doseq [[n c] (js/Object.entries cells)]
      (when-let [sz (get sizes n)]
        (assoc! cw (:col c) (max (or (get cw (:col c)) 0) (:w sz)))
        (assoc! rh (:row c) (max (or (get rh (:row c)) 0) (:h sz)))))
    (let [sum (fn [m] (let [vs (js/Object.values m)] (+ (reduce + 0 vs) (* grid/GAP (inc (count vs))))))]
      [(sum cw) (sum rh)])))

(defn best-columns
  "The column count (1 .. the number of top-level boxes) whose estimated
  grid comes closest to ASPECT."
  [graph sizes]
  (let [n (max 1 (count (top-boxes graph)))
        off (fn [k] (let [[w h] (estimate graph sizes k)]
                      (js/Math.abs (- (js/Math.log (/ (max 1 w) (max 1 h))) (js/Math.log ASPECT)))))]
    (reduce (fn [best k] (if (< (off k) (off best)) k best)) 1 (range 2 (inc n)))))

(defn ^:async layout-compact
  "The compact layout of `graph` (collapse applied) as an ELK-shaped
  result, like grid/layout-grid (whose arguments these are). A first
  pass on a square of cells gives every box its size; when another
  column count brings the whole closer to ASPECT, a second pass uses it
  (reusing each box's direction). The result keeps :ncols, and an edit
  (`prev`) keeps them, so boxes don't change places while you work."
  [graph elk-graph run-elk prev]
  (if (empty? (top-boxes graph))
    ;; nothing to put on cells: one wrapped left-to-right layout
    (let [copy (fn [x] (js/JSON.parse (js/JSON.stringify x)))]
      (js-await (-> (run-elk (assoc (copy elk-graph) :layoutOptions
                                    (merge (:layoutOptions elk-graph) grid/WRAP-OPTIONS)))
                    ;; should ELK not manage to wrap it: as it comes
                    (.catch (fn [_] (run-elk (copy elk-graph)))))))
    (let [opts {:square true :wrap-strip true :rotate-labels true}
          pass (fn [ncols p] (.then (grid/layout-grid (with-cells graph ncols) elk-graph run-elk p opts)
                                    (fn [l] (assoc l :ncols ncols))))]
      (if (some? (:ncols prev))
        (js-await (pass (:ncols prev) prev))
        (let [n0 (columns-used graph (auto-cells graph))
              l1 (js-await (pass n0 prev))
              sizes (into {} (keep (fn [c] (when (.startsWith (:id c) "b:")
                                             [(.slice (:id c) 2) {:w (:width c) :h (:height c)}])))
                          (:children l1))
              n1 (best-columns graph sizes)]
          (if (= n1 n0)
            l1
            ;; the first pass seeds the second: same directions, no flips
            (cond-> (js-await (pass n1 l1))
              (nil? prev) (dissoc :seeded))))))))
