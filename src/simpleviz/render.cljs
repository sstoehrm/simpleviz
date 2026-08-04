(ns simpleviz.render)

;; Hiccup views + imperative pan/zoom. DOM-only namespace: never imported
;; by node tests.

(def ^:private measure-ctx
  (.getContext (js/document.createElement "canvas") "2d"))

(defn measure [text font]
  (set! (.-font measure-ctx) font)
  (.-width (.measureText measure-ctx text)))

;; Pan/zoom state survives re-renders so live reload keeps the view.
;; Mutated in place (assoc!) — deliberately outside the reagami state atom
;; so pointermove does not trigger re-renders.
(def view {:x 0 :y 0 :k 1 :initialized false})
(def suppress-click (atom false))

(defn view-transform []
  (str "translate(" (:x view) "," (:y view) ") scale(" (:k view) ")"))

(defn- apply-view! []
  (when-let [vp (js/document.getElementById "viewport")]
    (.setAttribute vp "transform" (view-transform))))

(defn fit-view-once! [layout]
  (when-not (:initialized view)
    (assoc! view :initialized true)
    (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
          w (or (:width layout) 1)
          h (or (:height layout) 1)
          k (js/Math.min 1.25 (* 0.9 (js/Math.min (/ (.-width rect) w)
                                                  (/ (.-height rect) h))))]
      (assoc! view
              :k k
              :x (/ (- (.-width rect) (* w k)) 2)
              :y (/ (- (.-height rect) (* h k)) 2)))))

(defn setup-pan-zoom!
  "Attach wheel/pointer listeners once to the static wrapper element
  (outside reagami's tree, so re-renders never stack handlers)."
  [wrap]
  (.addEventListener wrap "wheel"
    (fn [e]
      (when-not (.closest (.-target e) "#details, #banner")
        (.preventDefault e)
        (let [factor (if (< (.-deltaY e) 0) 1.1 (/ 1 1.1))
              rect (.getBoundingClientRect wrap)
              mx (- (.-clientX e) (.-left rect))
              my (- (.-clientY e) (.-top rect))]
          (assoc! view
                  :x (- mx (* (- mx (:x view)) factor))
                  :y (- my (* (- my (:y view)) factor))
                  :k (* (:k view) factor))
          (apply-view!))))
    {:passive false})
  (let [drag (atom nil)]
    (.addEventListener wrap "pointerdown"
      (fn [e]
        (when-not (.closest (.-target e) "#details, #banner")
          (reset! drag {:x (.-clientX e) :y (.-clientY e)
                        :vx (:x view) :vy (:y view) :moved false})
          (.setPointerCapture wrap (.-pointerId e)))))
    (.addEventListener wrap "pointermove"
      (fn [e]
        (when-let [d @drag]
          (let [dx (- (.-clientX e) (:x d))
                dy (- (.-clientY e) (:y d))]
            (when (> (+ (js/Math.abs dx) (js/Math.abs dy)) 3)
              (swap! drag assoc :moved true))
            (assoc! view :x (+ (:vx d) dx) :y (+ (:vy d) dy))
            (apply-view!)))))
    (.addEventListener wrap "pointerup"
      (fn [_]
        (when (and @drag (:moved @drag)) (reset! suppress-click true))
        (reset! drag nil)))
    (.addEventListener wrap "pointercancel" (fn [_] (reset! drag nil)))))

(defn- selectable-attrs [payload on-select]
  {:on-click (fn [e]
               (.stopPropagation e)
               (if @suppress-click
                 (reset! suppress-click false)
                 (on-select payload)))})

(defn- node-view [child x y node color on-select selected-id]
  (let [cx (/ (:width child) 2)
        sel? (= selected-id (:id child))]
    [:g (assoc (selectable-attrs {:kind "node" :elk-id (:id child)
                                  :title (:name node) :subtitle (:type node)
                                  :attrs (:attrs node)}
                                 on-select)
               :key (:id child)
               :class (str "node selectable" (when sel? " selected"))
               :transform (str "translate(" x "," y ")"))
     [:rect {:class "node-bg" :width (:width child) :height (:height child) :rx 6}]
     [:text {:class "node-name" :x cx :y 19 :text-anchor "middle" :fill color}
      (:name node)]
     (when (pos? (.-length (:type node)))
       [:text {:class "node-sub" :x cx :y 35 :text-anchor "middle"}
        (str "(" (:type node) ")")])]))

(defn- box-view [child x y box c on-select selected-id]
  (let [sel? (= selected-id (:id child))]
    [:g (assoc (selectable-attrs {:kind "box" :elk-id (:id child)
                                  :title (:name box) :subtitle (:type box)
                                  :attrs (:attrs box)}
                                 on-select)
               :key (:id child)
               :class (str "box selectable" (when sel? " selected"))
               :transform (str "translate(" x "," y ")"))
     [:rect {:class "box-bg" :width (:width child) :height (:height child) :rx 10
             :fill (:fill c) :stroke (:border c)}]
     [:text {:class "box-name" :x 12 :y 24 :fill (:border c)}
      (:name box)
      (when (pos? (.-length (:type box)))
        [:tspan {:class "box-sub"} (str " (" (:type box) ")")])]]))

(defn- section-points [sec]
  (into [(:startPoint sec)]
        (conj (vec (or (:bendPoints sec) [])) (:endPoint sec))))

(defn- midpoint [pts]
  (let [segs (mapv (fn [i]
                     (js/Math.hypot (- (:x (nth pts (inc i))) (:x (nth pts i)))
                                    (- (:y (nth pts (inc i))) (:y (nth pts i)))))
                   (range (dec (.-length pts))))
        total (reduce + 0 segs)]
    (loop [i 0 acc 0]
      (if (>= i (.-length segs))
        (nth pts 0)
        (if (>= (+ acc (nth segs i)) (/ total 2))
          (let [t (/ (- (/ total 2) acc) (max (nth segs i) 1e-9))
                p0 (nth pts i)
                p1 (nth pts (inc i))]
            {:x (+ (:x p0) (* t (- (:x p1) (:x p0))))
             :y (+ (:y p0) (* t (- (:y p1) (:y p0))))})
          (recur (inc i) (+ acc (nth segs i))))))))

(defn- edge-view [elk-edge e origin on-select selected-id]
  (let [sections (or (:sections elk-edge) [])
        offset-pts (fn [sec]
                     (mapv (fn [p] {:x (+ (:x p) (:x origin))
                                    :y (+ (:y p) (:y origin))})
                           (section-points sec)))
        pts (vec (mapcat offset-pts sections))
        d (.join (mapv (fn [sec]
                         (.join (vec (map-indexed
                                      (fn [i p] (str (if (zero? i) "M " "L ") (:x p) " " (:y p)))
                                      (offset-pts sec)))
                                " "))
                       sections)
                 " ")
        label (.join (filterv (fn [s] (pos? (.-length s)))
                              [(:name e) (if (pos? (.-length (:type e)))
                                           (str "(" (:type e) ")") "")])
                     " ")
        sel? (= selected-id (:id elk-edge))]
    (when (pos? (.-length pts))
      [:g (assoc (selectable-attrs {:kind "edge" :elk-id (:id elk-edge)
                                    :title (if (pos? (.-length (:name e)))
                                             (:name e)
                                             (str (:source e) " → " (:target e)))
                                    :subtitle (:type e) :attrs (:attrs e)}
                                   on-select)
                 :key (:id elk-edge)
                 :class (str "edge selectable" (when sel? " selected")))
       [:path (cond-> {:class "edge-line" :d d :fill "none"}
                (:target (:arrows e)) (assoc :marker-end "url(#arrow)")
                (:source (:arrows e)) (assoc :marker-start "url(#arrow)"))]
       [:path {:class "edge-hit" :d d :fill "none"}]
       (when (pos? (.-length label))
         (let [mid (midpoint pts)]
           [:text {:class "edge-label" :x (:x mid) :y (- (:y mid) 5)
                   :text-anchor "middle"}
            label]))])))

(defn- walk-layout
  "Flatten the ELK result: absolute positions for nodes/boxes plus each
  box's absolute origin (edge sections are relative to their :container)."
  [layout]
  (let [nodes (atom []) boxes (atom []) origins (atom {})]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (do (swap! boxes conj {:child child :x x :y y})
                 (swap! origins assoc (:id child) {:x x :y y})
                 (walk child x y))
             (swap! nodes conj {:child child :x x :y y})))))
     layout 0 0)
    {:nodes @nodes :boxes @boxes :origins @origins}))

(defn graph-view [{:keys [layout graph colors selected-id on-select]}]
  (let [{:keys [nodes boxes origins]} (walk-layout layout)
        edges-by-id (reduce (fn [acc e] (assoc acc (:id e) e)) {} (:edges graph))]
    [:svg {:id "canvas"
           :on-click (fn [_]
                       (if @suppress-click
                         (reset! suppress-click false)
                         (on-select nil)))}
     [:defs
      [:marker {:id "arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                :markerWidth 7 :markerHeight 7 :orient "auto-start-reverse"}
       [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "#555"}]]]
     [:g {:id "viewport" :transform (view-transform)}
      (into [:g {:key "boxes"}]
            (mapv (fn [{:keys [child x y]}]
                    (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                          c (if (pos? (.-length (:type box)))
                              (get (:box colors) (:type box))
                              (:neutral-box colors))]
                      (box-view child x y box c on-select selected-id)))
                  boxes))
      (into [:g {:key "edges"}]
            (vec (filter some?
                  (mapv (fn [elk-edge]
                          (let [e (get edges-by-id (:id elk-edge))
                                origin (or (get origins (:container elk-edge))
                                           {:x 0 :y 0})]
                            (when (some? e)
                              (edge-view elk-edge e origin on-select selected-id))))
                        (or (:edges layout) [])))))
      (into [:g {:key "nodes"}]
            (mapv (fn [{:keys [child x y]}]
                    (let [node (get (:nodes graph) (.slice (:id child) 2))
                          color (if (pos? (.-length (:type node)))
                                  (get (:node colors) (:type node))
                                  (:neutral-node colors))]
                      (node-view child x y node color on-select selected-id)))
                  nodes))]]))
