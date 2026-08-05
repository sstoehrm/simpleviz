(ns simpleviz.scene)

;; layout + graph + colors -> flat, back-to-front draw list with absolute
;; coordinates: boxes, edges, edge labels, nodes. Pure data; the canvas
;; painter draws it and hit-testing walks it. No DOM.
;;
;; Hot path for large graphs: accumulation uses mutating JS arrays/Maps —
;; squint's persistent conj/assoc copy on every call, which turns 10k-item
;; builds quadratic (guarded by a perf test).

(def TITLE-H 28)

(def ^:private BBOX-PAD 10)

(defn- node-color [node colors]
  (if (pos? (.-length (:type node)))
    (get (:node colors) (:type node))
    (:neutral-node colors)))

(defn- box-colors [box colors]
  (if (pos? (.-length (:type box)))
    (get (:box colors) (:type box))
    (:neutral-box colors)))

(defn- section-points [sec]
  (into [(:startPoint sec)]
        (conj (vec (or (:bendPoints sec) [])) (:endPoint sec))))

(defn- offset-pts [pts origin]
  (mapv (fn [p] {:x (+ (:x p) (:x origin)) :y (+ (:y p) (:y origin))}) pts))

(defn- rect-bbox [x y w h]
  {:x0 (- x BBOX-PAD) :y0 (- y BBOX-PAD)
   :x1 (+ x w BBOX-PAD) :y1 (+ y h BBOX-PAD)})

(defn- points-bbox [pts]
  (loop [i 0
         x0 js/Infinity y0 js/Infinity
         x1 (- js/Infinity) y1 (- js/Infinity)]
    (if (< i (.-length pts))
      (let [p (nth pts i)]
        (recur (inc i)
               (js/Math.min x0 (:x p)) (js/Math.min y0 (:y p))
               (js/Math.max x1 (:x p)) (js/Math.max y1 (:y p))))
      {:x0 (- x0 BBOX-PAD) :y0 (- y0 BBOX-PAD)
       :x1 (+ x1 BBOX-PAD) :y1 (+ y1 BBOX-PAD)})))

(defn visible?
  "Does the item's bounding box intersect the view rect {:x0 :y0 :x1 :y1}?"
  [item vr]
  (let [bb (:bbox item)]
    (and (< (:x0 bb) (:x1 vr)) (> (:x1 bb) (:x0 vr))
         (< (:y0 bb) (:y1 vr)) (> (:y1 bb) (:y0 vr)))))

(defn build-scene [{:keys [layout graph colors]}]
  (let [boxes (js/Array.)
        nodes (js/Array.)
        origins (js/Map.)]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                   c (box-colors box colors)]
               (.push boxes {:kind "box" :id (:id child)
                             :x x :y y :w (:width child) :h (:height child)
                             :title-h TITLE-H
                             :bbox (rect-bbox x y (:width child) (:height child))
                             :border (:border c) :fill (:fill c)
                             :name (:name box) :type (:type box)
                             :attrs (:attrs box)})
               (.set origins (:id child) {:x x :y y})
               (walk child x y))
             (let [node (get (:nodes graph) (.slice (:id child) 2))]
               (.push nodes {:kind "node" :id (:id child)
                             :x x :y y :w (:width child) :h (:height child)
                             :bbox (rect-bbox x y (:width child) (:height child))
                             :color (node-color node colors)
                             :name (:name node) :type (:type node)
                             :attrs (:attrs node)}))))))
     layout 0 0)
    (let [edges-by-id (let [m (js/Map.)]
                        (doseq [e (:edges graph)] (.set m (:id e) e))
                        m)
          origin-of (fn [elk-edge] (or (.get origins (:container elk-edge)) {:x 0 :y 0}))
          edge-items (js/Array.)
          label-items (js/Array.)]
      (doseq [elk-edge (or (:edges layout) [])]
        (let [e (.get edges-by-id (:id elk-edge))
              origin (origin-of elk-edge)
              sections (mapv (fn [sec] (offset-pts (section-points sec) origin))
                             (or (:sections elk-edge) []))]
          (when (and (some? e) (pos? (.-length sections)))
            (let [points (vec (apply concat sections))]
              (.push edge-items {:kind "edge" :id (:id elk-edge)
                                 :sections sections
                                 :points points
                                 :bbox (points-bbox points)
                                 :arrows (:arrows e)
                                 :source (:source e)
                                 :target (:target e)
                                 :name (:name e) :type (:type e) :attrs (:attrs e)})))
          (when-let [lbl (first (or (:labels elk-edge) []))]
            (let [lx (+ (:x lbl) (:x origin))
                  ly (+ (:y lbl) (:y origin))]
              (.push label-items {:kind "edge-label" :id (str (:id elk-edge) "-label")
                                  :edge-id (:id elk-edge)
                                  :x lx :y ly
                                  :w (:width lbl) :h (:height lbl)
                                  :bbox (rect-bbox lx ly (:width lbl) (:height lbl))
                                  :text (:text lbl)})))))
      {:items (.concat boxes edge-items label-items nodes)
       :width (or (:width layout) 0)
       :height (or (:height layout) 0)})))
