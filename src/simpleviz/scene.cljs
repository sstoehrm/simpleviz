(ns simpleviz.scene)

;; layout + graph + colors -> flat, back-to-front draw list with absolute
;; coordinates: boxes, edges, edge labels, nodes. Pure data; the canvas
;; painter draws it and hit-testing walks it. No DOM.

(def TITLE-H 28)

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

(defn build-scene [{:keys [layout graph colors]}]
  (let [boxes (atom [])
        nodes (atom [])
        origins (atom {})]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                   c (box-colors box colors)]
               (swap! boxes conj {:kind "box" :id (:id child)
                                  :x x :y y :w (:width child) :h (:height child)
                                  :title-h TITLE-H
                                  :border (:border c) :fill (:fill c)
                                  :name (:name box) :type (:type box)
                                  :attrs (:attrs box)})
               (swap! origins assoc (:id child) {:x x :y y})
               (walk child x y))
             (let [node (get (:nodes graph) (.slice (:id child) 2))]
               (swap! nodes conj {:kind "node" :id (:id child)
                                  :x x :y y :w (:width child) :h (:height child)
                                  :color (node-color node colors)
                                  :name (:name node) :type (:type node)
                                  :attrs (:attrs node)}))))))
     layout 0 0)
    (let [edges-by-id (reduce (fn [acc e] (assoc acc (:id e) e)) {} (:edges graph))
          origin-of (fn [elk-edge] (or (get @origins (:container elk-edge)) {:x 0 :y 0}))
          edge-items
          (vec (filter some?
                (mapv (fn [elk-edge]
                        (let [e (get edges-by-id (:id elk-edge))
                              origin (origin-of elk-edge)
                              sections (mapv (fn [sec]
                                               (offset-pts (section-points sec) origin))
                                             (or (:sections elk-edge) []))]
                          (when (and (some? e) (pos? (.-length sections)))
                            {:kind "edge" :id (:id elk-edge)
                             :sections sections
                             :points (vec (apply concat sections))
                             :arrows (:arrows e)
                             :source (:source e)
                             :target (:target e)
                             :name (:name e) :type (:type e) :attrs (:attrs e)})))
                      (or (:edges layout) []))))
          label-items
          (vec (filter some?
                (mapv (fn [elk-edge]
                        (let [origin (origin-of elk-edge)
                              lbl (first (or (:labels elk-edge) []))]
                          (when (some? lbl)
                            {:kind "edge-label" :id (str (:id elk-edge) "-label")
                             :edge-id (:id elk-edge)
                             :x (+ (:x lbl) (:x origin))
                             :y (+ (:y lbl) (:y origin))
                             :w (:width lbl) :h (:height lbl)
                             :text (:text lbl)})))
                      (or (:edges layout) []))))]
      {:items (vec (concat @boxes edge-items label-items @nodes))
       :width (or (:width layout) 0)
       :height (or (:height layout) 0)})))
