(ns simpleviz.tween)

;; One frame of the animation between two scenes (scene/build-scene) —
;; a graph's step and the next. Nodes and boxes match by id, edges by
;; their ends (edge ids are positions in the file), labels by their
;; edge. A match moves from where it was to where it lands; what only
;; the old scene has fades out (:alpha), what only the new one has
;; fades in where it lands. Pure data: the painter draws the frame like
;; any scene.

;; points an edge is resampled to, so two routes with different bends
;; can blend point by point
(def ^:private EDGE-PTS 24)

(defn ease
  "Cubic ease-in-out of t in [0, 1]."
  [t]
  (if (< t 0.5)
    (* 4 t t t)
    (- 1 (/ (js/Math.pow (+ (* -2 t) 2) 3) 2))))

(defn- lerp [a b t] (+ a (* (- b a) t)))

(defn- edge-key [it] (str (:source-id it) "|" (:target-id it)))

(defn- match-key
  "What item `it` of a scene matches by; `edge-keys` maps the scene's
  edge ids to edge keys, for its labels."
  [it edge-keys]
  (case (:kind it)
    "edge" (str "e|" (edge-key it))
    "edge-label" (str "l|" (get edge-keys (:edge-id it)))
    (str (:kind it) "|" (:id it))))

(defn- edge-keys [sc]
  (let [m {}]
    (doseq [it (:items sc)]
      (when (= (:kind it) "edge") (assoc! m (:id it) (edge-key it))))
    m))

(defn- by-key [sc]
  (let [ks (edge-keys sc)
        m (js/Map.)]
    (doseq [it (:items sc)] (.set m (match-key it ks) it))
    m))

(defn- dist [a b] (js/Math.hypot (- (:x b) (:x a)) (- (:y b) (:y a))))

(defn- resample
  "Polyline pts as n points evenly spaced along it, ends kept."
  [pts n]
  (let [lens (reduce (fn [acc i] (conj acc (+ (peek acc) (dist (nth pts (dec i)) (nth pts i)))))
                     [0] (range 1 (count pts)))
        total (peek lens)]
    (if (or (< (count pts) 2) (zero? total))
      (vec (repeat n (first pts)))
      (mapv (fn [j]
              (let [d (* total (/ j (dec n)))
                    i (loop [i 1] (if (and (< i (dec (count pts))) (> d (nth lens i))) (recur (inc i)) i))
                    seg (- (nth lens i) (nth lens (dec i)))
                    u (if (zero? seg) 0 (/ (- d (nth lens (dec i))) seg))
                    a (nth pts (dec i))
                    b (nth pts i)]
                {:x (lerp (:x a) (:x b) u) :y (lerp (:y a) (:y b) u)}))
            (range n)))))

(defn- route [it] (vec (apply concat (:sections it))))

(defn- lerp-bbox [a b t]
  {:x0 (js/Math.min (:x0 a) (:x0 b)) :y0 (js/Math.min (:y0 a) (:y0 b))
   :x1 (js/Math.max (:x1 a) (:x1 b)) :y1 (js/Math.max (:y1 a) (:y1 b))})

(defn- strength
  "An item's :highlight as a number: true 1, a frame's fade itself, else 0."
  [it]
  (let [h (:highlight it)]
    (cond (true? h) 1 (number? h) h :else 0)))

(defn- between
  "Item `b` (the new scene's) on its way from `a` at t; a highlight
  coming or going fades."
  [a b t]
  (let [b (if (= (strength a) (strength b)) b (assoc b :highlight (lerp (strength a) (strength b) t)))]
    (if (= (:kind b) "edge")
      (let [pa (resample (route a) EDGE-PTS)
            pb (resample (route b) EDGE-PTS)
            pts (mapv (fn [p q] {:x (lerp (:x p) (:x q) t) :y (lerp (:y p) (:y q) t)}) pa pb)]
        (assoc b :sections [pts] :points pts :bbox (lerp-bbox (:bbox a) (:bbox b) t)))
      (assoc b
             :x (lerp (:x a) (:x b) t) :y (lerp (:y a) (:y b) t)
             :w (lerp (:w a) (:w b) t) :h (lerp (:h a) (:h b) t)
             :bbox (lerp-bbox (:bbox a) (:bbox b) t)))))

(def ^:private KINDS ["box" "edge" "edge-label" "node"])

(defn frame
  "The scene between scenes `from` and `to` at t in [0, 1]: by kind,
  boxes, edges, labels and nodes, each kind's fading-out items under
  the others."
  [from to t]
  (let [old (by-key from)
        new-ks (edge-keys to)
        kept (js/Set.)
        moving (mapv (fn [it]
                       (let [k (match-key it new-ks)
                             a (.get old k)]
                         (if (some? a)
                           (do (.add kept k) (between a it t))
                           (assoc it :alpha t))))
                     (:items to))
        leaving (filterv some? (mapv (fn [[k it]] (when-not (.has kept k) (assoc it :alpha (- 1 t))))
                                     (js/Array.from (.entries old))))
        of-kind (fn [items kind] (filterv (fn [it] (= (:kind it) kind)) items))]
    {:items (vec (mapcat (fn [kind] (concat (of-kind leaving kind) (of-kind moving kind))) KINDS))
     :width (lerp (:width from) (:width to) t)
     :height (lerp (:height from) (:height to) t)}))
