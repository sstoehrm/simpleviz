(ns simpleviz.chart
  "Charts for md pages, as hiccup: bars (grouped, stacked, horizontal),
  lines and dots over one value axis. A chart draws a grid of cells
  {:num :label} — a table's or a data file's — by a spec the server read
  (calc/chart-spec): {:kind \"bar\" :x 0 :y [1 2] :rows [1 4] :stack
  :horizontal :title}, columns as indexes. Series take the colours
  --series-1 … --series-8 in order; text stays in the page's ink.")

(def ^:private W 640)
(def ^:private H 300)
(def ^:private max-bar 24)
(def ^:private gap 2)

(defn- round1 [v] (/ (js/Math.round (* v 10)) 10))

(defn- r1 [v] (str (round1 v)))

(defn- clean [v] (js/Number (.toPrecision v 12)))

(defn- step-of
  "A round step, 1, 2 or 5 times a power of ten, giving about n steps
  over span."
  [span n]
  (let [raw (/ span n)
        mag (js/Math.pow 10 (js/Math.floor (js/Math.log10 raw)))
        f (/ raw mag)]
    (* mag (cond (<= f 1) 1 (<= f 2) 2 (<= f 5) 5 :else 10))))

(defn ticks
  "Round values from at most lo to at least hi, about n steps apart."
  [lo hi n]
  (let [[lo hi] (if (= lo hi) (if (zero? lo) [0 1] [(- lo (js/Math.abs lo)) (+ hi (js/Math.abs hi))]) [lo hi])
        step (step-of (- hi lo) n)
        a (* step (js/Math.floor (clean (/ lo step))))
        b (* step (js/Math.ceil (clean (/ hi step))))]
    (mapv (fn [i] (clean (+ a (* i step)))) (range (inc (js/Math.round (/ (- b a) step)))))))

(defn compact
  "A tick's number: 1.2K from a thousand, 3.4M from a million."
  [v]
  (let [a (js/Math.abs v)
        short (fn [x unit] (str (clean (round1 x)) unit))]
    (cond (>= a 1e9) (short (/ v 1e9) "B")
          (>= a 1e6) (short (/ v 1e6) "M")
          (>= a 1e4) (short (/ v 1e3) "K")
          :else (str (clean v)))))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- bar-path
  "A bar from (x, y), w wide and h high, its data end — side \"top\",
  \"bottom\", \"left\" or \"right\" — rounded."
  [x y w h side]
  (let [r (min 4 (/ w 2) (/ h 2))
        x2 (+ x w) y2 (+ y h)
        p (fn [& xs] (.join (mapv (fn [v] (if (number? v) (r1 v) v)) xs) " "))]
    (case side
      "top" (p "M" x y2 "V" (+ y r) "Q" x y (+ x r) y "H" (- x2 r) "Q" x2 y x2 (+ y r) "V" y2 "Z")
      "bottom" (p "M" x y "V" (- y2 r) "Q" x y2 (+ x r) y2 "H" (- x2 r) "Q" x2 y2 x2 (- y2 r) "V" y "Z")
      "right" (p "M" x y "H" (- x2 r) "Q" x2 y x2 (+ y r) "V" (- y2 r) "Q" x2 y2 (- x2 r) y2 "H" x "Z")
      "left" (p "M" x2 y "H" (+ x r) "Q" x y x (+ y r) "V" (- y2 r) "Q" x y2 (+ x r) y2 "H" x2 "Z")
      (p "M" x y "H" x2 "V" y2 "H" x "Z"))))

(defn- colour
  "The style giving series j its colour: --series-1 … --series-8 in
  turn, which the chart's CSS reads as --c."
  [j]
  {"--c" (str "var(--series-" (inc (mod j 8)) ")")})

(defn- linear
  "The map from values d0..d1 onto positions p0..p1."
  [d0 d1 p0 p1]
  (fn [v] (+ p0 (* (- p1 p0) (/ (- v d0) (- d1 d0))))))

(defn- prepared
  "The spec's rows of the grid, as x cells and series {:name :values}."
  [{:keys [spec names grid]}]
  (let [[from to] (or (:rows spec) [1 (count grid)])
        rows (.slice grid (dec from) to)]
    {:xs (mapv (fn [row] (or (get row (:x spec)) {:label ""})) rows)
     :series (mapv (fn [c] {:name (or (get names c) "")
                            :values (mapv (fn [row] (let [n (:num (get row c))] (when (number? n) n))) rows)})
                   (:y spec))}))

(defn- extent
  "[min max] of the numbers in vs, or nil — by reduce, as apply would
  spread every value into one call."
  [vs]
  (let [vs (filterv number? vs)]
    (when (seq vs) [(reduce min vs) (reduce max vs)])))

(defn- value-axis
  "Gridlines and tick labels for ticks ts at (pos v): across the plot,
  or, with across? (bars on their side), up it with the labels below."
  [ts pos across? {:keys [l r t b]}]
  (into [:g {:class "chart-axis"}]
        (mapcat (fn [v]
                  (let [q (r1 (pos v))
                        cls (if (zero? v) "chart-base" "chart-grid")]
                    (if across?
                      [[:line {:class cls :x1 q :x2 q :y1 t :y2 (- H b)}]
                       [:text {:x q :y (- H b -16) :text-anchor "middle"} (compact v)]]
                      [[:line {:class cls :x1 l :x2 (- W r) :y1 q :y2 q}]
                       [:text {:x (- l 8) :y (r1 (+ (pos v) 4)) :text-anchor "end"} (compact v)]])))
                ts)))

(defn- tip [& parts] [:title (.join (into-array (remove nil? parts)) "")])

(defn- cat-labels
  "The xs' labels at (center i), every few so they keep apart: below
  the plot, or left of it with side? (bars on their side)."
  [xs band center side? pad]
  (let [every (max 1 (js/Math.ceil (/ (if side? 16 64) (max band 1))))]
    (into [:g {:class "chart-labels"}]
          (keep (fn [i]
                  (when (zero? (mod i every))
                    (let [label (:label (nth xs i))
                          c (center i)]
                      (if side?
                        [:text {:x (- (:l pad) 8) :y (r1 (+ c 4)) :text-anchor "end"} (clip label 16) (tip label)]
                        [:text {:x (r1 c) :y (- H (:b pad) -18) :text-anchor "middle"} (clip label 12) (tip label)]))))
                (range (count xs))))))

(defn stacked
  "One category's stack, from its values per series: [series value
  start outer?] per value that draws — not blank, not 0 — positive ones
  piled up from 0, negative ones down, the last of each sign outer."
  [vs]
  (let [segs (loop [j 0 up 0 down 0 out []]
               (if (>= j (count vs))
                 out
                 (let [v (nth vs j)]
                   (cond (or (not (number? v)) (zero? v)) (recur (inc j) up down out)
                         (pos? v) (recur (inc j) (+ up v) down (conj out [j v up]))
                         :else (recur (inc j) up (+ down v) (conj out [j v down]))))))
        last-of (fn [sign?] (some (fn [[j v]] (when (sign? v) j)) (reverse segs)))
        outer-up (last-of pos?)
        outer-down (last-of neg?)]
    (mapv (fn [[j v start]] [j v start (= j (if (pos? v) outer-up outer-down))]) segs)))

(defn- bars [{:keys [xs series]} spec]
  (let [side? (:horizontal spec)
        stack? (:stack spec)
        pad (if side? {:l 110 :r 20 :t 8 :b 28} {:l 48 :r 12 :t 12 :b 32})
        n (count xs)
        k (count series)
        stacks (when stack? (mapv (fn [i] (stacked (mapv (fn [s] (nth (:values s) i)) series))) (range n)))
        [lo hi] (or (extent (if stack?
                              (mapcat (fn [segs] (map (fn [[_ v start]] (+ start v)) segs)) stacks)
                              (mapcat :values series)))
                    [0 1])
        ts (ticks (min 0 lo) (max 0 hi) 5)
        [a0 a1] (if side? [(:l pad) (- W (:r pad))] [(- H (:b pad)) (:t pad)])
        pos (linear (first ts) (last ts) a0 a1)
        [c0 c1] (if side? [(:t pad) (- H (:b pad))] [(:l pad) (- W (:r pad))])
        band (/ (- c1 c0) (max 1 n))
        per (if stack? 1 k)
        thick (min max-bar (/ (- (* band 0.72) (* gap (dec per))) per))
        group (+ (* thick per) (* gap (dec per)))
        ;; the gap between stacked segments, as a value
        cut (* gap (/ (- (last ts) (first ts)) (js/Math.abs (- a1 a0))))
        mark (fn [i j start from to rounded?]
               (let [p (pos from) q (pos to)
                     up? (>= to from)
                     end (when rounded? (if side? (if up? "right" "left") (if up? "top" "bottom")))
                     s (nth series j)]
                 [:path {:class "chart-mark" :style (colour j)
                         :d (if side?
                              (bar-path (min p q) start (js/Math.abs (- q p)) thick end)
                              (bar-path start (min p q) thick (js/Math.abs (- q p)) end))}
                  (tip (:label (nth xs i)) " · " (:name s) ": " (compact (nth (:values s) i)))]))
        start-of (fn [i] (+ c0 (* band i) (/ (- band group) 2)))
        marks (if stack?
                (for [i (range n)
                      [j v start outer?] (nth stacks i)
                      ;; a segment past the first leaves the gap, unless thinner than it
                      :let [from (if (or (zero? start) (<= (js/Math.abs v) cut)) start (+ start (* (js/Math.sign v) cut)))]]
                  (mark i j (start-of i) from (+ start v) outer?))
                (for [i (range n)
                      [j s] (map-indexed vector series)
                      :let [v (nth (:values s) i)]
                      :when (number? v)]
                  (mark i j (+ (start-of i) (* j (+ thick gap))) 0 v true)))]
    [(value-axis ts pos side? pad)
     (cat-labels xs band (fn [i] (+ c0 (* band i) (/ band 2))) side? pad)
     (into [:g] marks)]))

(defn runs
  "The indexes of `order` split where values has no number: a gap in
  the values breaks the line."
  [values order]
  (loop [k 0 run [] out []]
    (if (>= k (count order))
      (if (seq run) (conj out run) out)
      (let [i (nth order k)]
        (if (number? (nth values i))
          (recur (inc k) (conj run i) out)
          (recur (inc k) [] (if (seq run) (conj out run) out)))))))

(defn- numeric-xs?
  "Are the xs numbers, leaving out blanks — a record without one?"
  [xs]
  (let [given (remove (fn [x] (and (nil? (:num x)) (= "" (:label x)))) xs)]
    (and (seq given) (every? (fn [x] (number? (:num x))) given))))

(defn- points [{:keys [xs series]} spec]
  (let [pad {:l 48 :r 16 :t 12 :b 32}
        n (count xs)
        line? (= "line" (:kind spec))
        numeric? (numeric-xs? xs)
        [ylo yhi] (or (extent (mapcat :values series)) [0 1])
        yts (ticks ylo yhi 5)
        ypos (linear (first yts) (last yts) (- H (:b pad)) (:t pad))
        [x0 x1] [(:l pad) (- W (:r pad))]
        xts (when numeric? (let [[lo hi] (extent (keep :num xs))] (ticks lo hi 6)))
        xval (when numeric? (linear (first xts) (last xts) x0 x1))
        band (/ (- x1 x0) (max 1 n))
        xpos (if numeric?
               (fn [i] (xval (:num (nth xs i))))
               (fn [i] (+ x0 (* band i) (/ band 2))))
        order (if numeric?
                (vec (sort-by (fn [i] (:num (nth xs i))) (filter (fn [i] (number? (:num (nth xs i)))) (range n))))
                (vec (range n)))
        x-axis (if numeric?
                 (into [:g {:class "chart-axis"}]
                       (map (fn [v] [:text {:x (r1 (xval v)) :y (- H (:b pad) -18) :text-anchor "middle"} (compact v)])
                            xts))
                 (cat-labels xs band xpos false pad))
        dots? (or (not line?) (<= n 24))
        lines (when line?
                (for [[j s] (map-indexed vector series)
                      run (runs (:values s) order)
                      :when (> (count run) 1)]
                  [:path {:class "chart-line" :style (colour j)
                          :d (.join (into-array (map-indexed (fn [m i] (str (if (zero? m) "M" "L") (r1 (xpos i)) " " (r1 (ypos (nth (:values s) i)))))
                                                             run))
                                    " ")}]))
        dots (for [[j s] (map-indexed vector series)
                   i order
                   :let [v (nth (:values s) i)]
                   :when (number? v)]
               [:circle {:class (if dots? "chart-dot" "chart-hit") :style (colour j)
                         :cx (r1 (xpos i)) :cy (r1 (ypos v)) :r (if dots? 4 6)}
                (tip (:label (nth xs i)) " · " (:name s) ": " (compact v))])]
    [(value-axis yts ypos false pad) x-axis (into [:g] lines) (into [:g] dots)]))

(defn view
  "Hiccup for a chart of `grid` (rows of cells {:num :label}) whose
  columns are named `names`, drawn by `spec`."
  [{:keys [spec names] :as chart}]
  (let [{:keys [series] :as data} (prepared chart)
        bar? (= "bar" (:kind spec))
        title (or (:title spec) (when (= 1 (count series)) (:name (first series))))
        x-name (get names (:x spec))]
    [:div {:class "chart"}
     (when (or (some? title) (> (count series) 1))
       (into [:div {:class "chart-head"}]
             (concat (when (some? title) [[:span {:class "chart-title"} title]])
                     (when (> (count series) 1)
                       (map-indexed (fn [j s] [:span {:class "chart-key"} [:i {:style (colour j)}] (:name s)]) series)))))
     (into [:svg {:class "chart-svg" :viewBox (str "0 0 " W " " H) :role "img"
                  :aria-label (str (or title "chart") (when (seq x-name) (str " by " x-name)))}]
           (if bar? (bars data spec) (points data spec)))
     (when (and (not bar?) (seq x-name))
       [:div {:class "chart-x-name"} x-name])]))
