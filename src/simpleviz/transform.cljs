(ns simpleviz.transform)

;; Builds the ELK JSON graph from a validated graph. Text measurement is
;; injected so this namespace stays DOM-free and testable.

(def NODE-FONT "bold 14px system-ui, sans-serif")
(def SUB-FONT "11px system-ui, sans-serif")

(defn to-elk [graph measure]
  (let [{:keys [nodes boxes boxes-by-name parent-of edges]} graph
        node-elk (fn [n]
                   (let [typed? (pos? (.-length (:type n)))
                         w (max (measure (:name n) NODE-FONT)
                                (if typed? (measure (str "(" (:type n) ")") SUB-FONT) 0))]
                     {:id (str "n:" (:id n))
                      :width (+ (js/Math.ceil w) 24)
                      :height (if typed? 44 30)}))
        box-elk (fn box-elk [b]
                  {:id (str "b:" (:name b))
                   :layoutOptions {"elk.padding" "[top=40,left=14,bottom=14,right=14]"}
                   :children (mapv (fn [c]
                                     (if (.startsWith c "n:")
                                       (node-elk (get nodes (.slice c 2)))
                                       (box-elk (get boxes-by-name (.slice c 2)))))
                                   (:components b))})
        root-nodes (vec (filter some?
                         (mapv (fn [n] (when (nil? (get parent-of (str "n:" (:id n))))
                                         (node-elk n)))
                               (js/Object.values nodes))))
        root-boxes (vec (filter some?
                         (mapv (fn [b] (when (nil? (get parent-of (str "b:" (:name b))))
                                         (box-elk b)))
                               boxes)))]
    {:id "root"
     :layoutOptions {"elk.algorithm" "layered"
                     "elk.direction" "RIGHT"
                     "elk.hierarchyHandling" "INCLUDE_CHILDREN"
                     "elk.layered.spacing.nodeNodeBetweenLayers" "50"
                     "elk.spacing.nodeNode" "30"
                     "elk.spacing.edgeNode" "20"
                     "elk.padding" "[top=20,left=20,bottom=20,right=20]"}
     :children (into root-nodes root-boxes)
     :edges (mapv (fn [e] {:id (:id e)
                           :sources [(str "n:" (:source e))]
                           :targets [(str "n:" (:target e))]})
                  edges)}))
