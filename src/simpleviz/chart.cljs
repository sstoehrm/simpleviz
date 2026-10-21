(ns simpleviz.chart
  "Charts for md pages, as hiccup: bars (grouped, stacked, horizontal),
  lines and dots over one value axis. A chart draws a grid of cells
  {:num :label} — a table's or a data file's — by a spec the server read
  (calc/chart-spec): {:kind \"bar\" :x 0 :y [1 2] :rows [1 4] :stack
  :horizontal :title}, columns as indexes. Series take the slots
  --series-1 … --series-8 in order; text stays in the page's ink.")

(def ^:private W 640)
(def ^:private H 300)
(def ^:private max-bar 24)
(def ^:private gap 2)

(defn- r1 [v] (str (/ (js/Math.round (* v 10)) 10)))

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
        short (fn [x unit] (str (clean (/ (js/Math.round (* x 10)) 10)) unit))]
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

(defn- slot [i] (str "s" (inc (mod i 8))))

(defn- prepared
  "The spec's rows of the grid, as x cells and series {:name :values}."
  [{:keys [spec names grid]}]
  (let [[from to] (or (:rows spec) [1 (count grid)])
        rows (.slice grid (dec from) to)]
    {:xs (mapv (fn [row] (or (get row (:x spec)) {:label ""})) rows)
     :series (mapv (fn [c] {:name (or (get names c) "") :col c
                            :values (mapv (fn [row] (let [n (:num (get row c))] (when (number? n) n))) rows)})
                   (:y spec))}))

(defn- extent [vs] (let [vs (filterv number? vs)] (when (seq vs) [(apply min vs) (apply max vs)])))

(defn- value-axis
  "Gridlines and tick labels for ticks ts, at (pos v) along the value
  axis; horizontal? puts the axis at the bottom."
  [ts pos horizontal? {:keys [l r t b]}]
  (into [:g {:class "chart-axis"}]
        (mapcat (fn [v]
                  (let [q (pos v)]
                    (if horizontal?
                      [[:line {:class (if (zero? v) "chart-base" "chart-grid") :x1 (r1 q) :x2 (r1 q) :y1 t :y2 (- H b)}]
                       [:text {:x (r1 q) :y (- H b -16) :text-anchor "middle"} (compact v)]]
                      [[:line {:class (if (zero? v) "chart-base" "chart-grid") :x1 l :x2 (- W r) :y1 (r1 q) :y2 (r1 q)}]
                       [:text {:x (- l 8) :y (r1 (+ q 4)) :text-anchor "end"} (compact v)]])))
                ts)))

(defn- every-nth
  "Which labels, slot-px apart, show: every k-th, so they keep about
  min-px between them."
  [slot-px min-px]
  (max 1 (js/Math.ceil (/ min-px (max slot-px 1)))))

(defn- tip [& parts] [:title (.join (into-array (remove nil? parts)) "")])

(defn- bars [{:keys [xs series]} spec]
  (let [horizontal? (:horizontal spec)
        stack? (:stack spec)
        pad (if horizontal? {:l 110 :r 20 :t 8 :b 28} {:l 48 :r 12 :t 12 :b 32})
        n (max 1 (count xs))
        k (count series)
        sums (fn [up?] (mapv (fn [i] (reduce + 0 (filter (fn [v] (and (number? v) (if up? (pos? v) (neg? v))))
                                                          (map (fn [s] (nth (:values s) i)) series))))
                              (range n)))
        [lo hi] (or (if stack?
                      (extent (concat (sums true) (sums false)))
                      (extent (mapcat :values series)))
                    [0 1])
        ts (ticks (min 0 lo) (max 0 hi) 5)
        t0 (first ts) t1 (last ts)
        [a0 a1] (if horizontal? [(:l pad) (- W (:r pad))] [(- H (:b pad)) (:t pad)])
        pos (fn [v] (+ a0 (* (- a1 a0) (/ (- v t0) (- t1 t0)))))
        [c0 c1] (if horizontal? [(:t pad) (- H (:b pad))] [(:l pad) (- W (:r pad))])
        band (/ (- c1 c0) n)
        per (if stack? 1 k)
        thick (min max-bar (/ (- (* band 0.72) (* gap (dec per))) per))
        group (+ (* thick per) (* gap (dec per)))
        shape (fn [c-start v-from v-to rounded?]
                (let [[p q] [(pos v-from) (pos v-to)]
                      lo-px (min p q) len (js/Math.abs (- q p))
                      up? (>= v-to v-from)
                      side (when rounded? (if horizontal? (if up? "right" "left") (if up? "top" "bottom")))]
                  (if horizontal?
                    (bar-path lo-px c-start len thick side)
                    (bar-path c-start lo-px thick len side))))
        marks (for [i (range n)
                    :let [start (+ c0 (* band i) (/ (- band group) 2))
                          label (:label (nth xs i))]
                    [j s] (map-indexed vector series)
                    :let [v (nth (:values s) i)]
                    :when (number? v)]
                (if stack?
                  (let [before (reduce + 0 (filter (fn [w] (and (number? w) (= (pos? w) (pos? v))))
                                                   (map (fn [s2] (nth (:values s2) i)) (take j series))))
                        outer? (not-any? (fn [s2] (let [w (nth (:values s2) i)] (and (number? w) (not (zero? w)) (= (pos? w) (pos? v)))))
                                         (drop (inc j) series))
                        ;; the gap between segments, cut off the segment's start
                        from (if (zero? before) 0 (+ before (* (if (pos? v) 1 -1) (/ (* gap (- t1 t0)) (js/Math.abs (- a1 a0))))))]
                    [:path {:class (str "chart-mark " (slot j)) :d (shape start from (+ before v) outer?)}
                     (tip label " · " (:name s) ": " (compact v))])
                  [:path {:class (str "chart-mark " (slot j)) :d (shape (+ start (* j (+ thick gap))) 0 v true)}
                   (tip label " · " (:name s) ": " (compact v))]))
        every (every-nth band (if horizontal? 16 64))
        labels (into [:g {:class "chart-labels"}]
                     (keep (fn [i]
                             (when (zero? (mod i every))
                               (let [c (+ c0 (* band i) (/ band 2))
                                     label (:label (nth xs i))]
                                 (if horizontal?
                                   [:text {:x (- (:l pad) 8) :y (r1 (+ c 4)) :text-anchor "end"} (clip label 16) (tip label)]
                                   [:text {:x (r1 c) :y (- H (:b pad) -18) :text-anchor "middle"} (clip label 12) (tip label)]))))
                           (range n)))]
    [(value-axis ts pos horizontal? pad) labels (into [:g] marks)]))

(defn runs
  "The indexes of `order` split where values has no number: a gap in
  the values breaks the line."
  [values order]
  (loop [is (seq order) run [] out []]
    (cond (nil? is) (if (seq run) (conj out run) out)
          (number? (nth values (first is))) (recur (next is) (conj run (first is)) out)
          :else (recur (next is) [] (if (seq run) (conj out run) out)))))

(defn- numeric-xs?
  "Are the xs numbers, leaving out blanks — a record without one?"
  [xs]
  (let [given (remove (fn [x] (and (nil? (:num x)) (= "" (:label x)))) xs)]
    (and (seq given) (every? (fn [x] (number? (:num x))) given))))

(defn- points [{:keys [xs series]} spec names]
  (let [pad {:l 48 :r 16 :t 12 :b 32}
        n (count xs)
        line? (= "line" (:kind spec))
        numeric? (numeric-xs? xs)
        [ylo yhi] (or (extent (mapcat :values series)) [0 1])
        yts (ticks ylo yhi 5)
        ypos (fn [v] (- (- H (:b pad)) (* (- H (:b pad) (:t pad)) (/ (- v (first yts)) (- (last yts) (first yts))))))
        [x0 x1] [(:l pad) (- W (:r pad))]
        [xlo xhi] (when numeric? (extent (keep :num xs)))
        xts (when numeric? (ticks xlo xhi 6))
        band (/ (- x1 x0) (max 1 n))
        xpos (if numeric?
               (fn [i] (+ x0 (* (- x1 x0) (/ (- (:num (nth xs i)) (first xts)) (- (last xts) (first xts))))))
               (fn [i] (+ x0 (* band i) (/ band 2))))
        order (if numeric?
                (vec (sort-by (fn [i] (:num (nth xs i))) (filter (fn [i] (number? (:num (nth xs i)))) (range n))))
                (vec (range n)))
        x-axis (if numeric?
                 (into [:g {:class "chart-axis"}]
                       (map (fn [v] (let [q (+ x0 (* (- x1 x0) (/ (- v (first xts)) (- (last xts) (first xts)))))]
                                      [:text {:x (r1 q) :y (- H (:b pad) -18) :text-anchor "middle"} (compact v)]))
                            xts))
                 (let [every (every-nth band 64)]
                   (into [:g {:class "chart-labels"}]
                         (keep (fn [i] (when (zero? (mod i every))
                                         (let [label (:label (nth xs i))]
                                           [:text {:x (r1 (xpos i)) :y (- H (:b pad) -18) :text-anchor "middle"}
                                            (clip label 12) (tip label)])))
                               (range n)))))
        dots? (or (not line?) (<= n 24))
        marks (when line?
                (for [[j s] (map-indexed vector series)
                      run (runs (:values s) order)
                      :when (> (count run) 1)]
                  [:path {:class (str "chart-line " (slot j))
                          :d (.join (into-array (map-indexed (fn [m i] (str (if (zero? m) "M" "L") (r1 (xpos i)) " " (r1 (ypos (nth (:values s) i)))))
                                                             run))
                                    " ")}]))
        dots (for [[j s] (map-indexed vector series)
                   i order
                   :let [v (nth (:values s) i)]
                   :when (number? v)]
               [:circle {:class (str (if dots? "chart-dot " "chart-hit ") (slot j))
                         :cx (r1 (xpos i)) :cy (r1 (ypos v)) :r (if dots? 4 6)}
                (tip (:label (nth xs i)) " · " (:name s) ": " (compact v))])]
    [(value-axis yts ypos false pad) x-axis (into [:g] marks) (into [:g] dots)]))

(defn view
  "Hiccup for a chart of `grid` (rows of cells {:num :label}) whose
  columns are named `names`, drawn by `spec`."
  [{:keys [spec names] :as chart}]
  (let [{:keys [series] :as data} (prepared chart)
        title (or (:title spec) (when (= 1 (count series)) (:name (first series))))
        body (if (= "bar" (:kind spec)) (bars data spec) (points data spec names))
        x-name (get names (:x spec))]
    [:div {:class "chart"}
     (when (or (some? title) (> (count series) 1))
       (into [:div {:class "chart-head"}]
             (concat (when (some? title) [[:span {:class "chart-title"} title]])
                     (when (> (count series) 1)
                       (map-indexed (fn [j s] [:span {:class "chart-key"} [:i {:class (slot j)}] (:name s)]) series)))))
     (into [:svg {:class "chart-svg" :viewBox (str "0 0 " W " " H) :role "img"
                  :aria-label (str (or title "chart") (when (seq x-name) (str " by " x-name)))}]
           body)
     (when (and (not= "bar" (:kind spec)) (seq x-name))
       [:div {:class "chart-x-name"} x-name])]))
