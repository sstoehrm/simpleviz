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
