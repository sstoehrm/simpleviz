(ns simpleviz.transform)

;; Builds the ELK JSON graph from a validated graph. Text measurement is
;; injected so this namespace stays DOM-free and testable.

(def NODE-FONT "bold 14px system-ui, sans-serif")
(def SUB-FONT "11px system-ui, sans-serif")

(def ^:private diff-glyphs {"added" "+" "removed" "−" "modified" "~"})

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
                  ;; empty boxes (e.g. compare-mode removed shells) must not
                  ;; reach ELK as childless compounds — those lay out as 0×0
                  (if (or (:collapsed b) (zero? (.-length (:components b))))
                    ;; node-style two-line label + room for the toggle button
                    (let [typed? (pos? (.-length (:type b)))
                          w (max (measure (:name b) NODE-FONT)
                                 (if typed? (measure (str "(" (:type b) ")") SUB-FONT) 0))]
                      {:id (str "b:" (:name b))
                       :width (+ (js/Math.ceil w) (if (:collapsed b) 44 24))
                       :height (if typed? 44 30)})
                    {:id (str "b:" (:name b))
                     :layoutOptions {"elk.padding" "[top=40,left=14,bottom=14,right=14]"}
                     :children (mapv (fn [c]
                                       (if (.startsWith c "n:")
                                         (node-elk (get nodes (.slice c 2)))
                                         (box-elk (get boxes-by-name (.slice c 2)))))
                                     (:components b))}))
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
                     "elk.layered.spacing.nodeNodeBetweenLayers" "80"
                     "elk.spacing.nodeNode" "45"
                     "elk.spacing.edgeNode" "30"
                     "elk.spacing.edgeEdge" "20"
                     "elk.edgeLabels.inline" "true"
                     "elk.padding" "[top=20,left=20,bottom=20,right=20]"}
     :children (into root-nodes root-boxes)
     :edges (mapv (fn [e]
                    (let [glyph (get diff-glyphs (:diff e))
                          parts (filterv (fn [s] (pos? (.-length s)))
                                         [(if (some? glyph) glyph "")
                                          (:name e)
                                          (if (pos? (.-length (:type e)))
                                            (str "(" (:type e) ")")
                                            "")])
                          label (.join parts " ")
                          base {:id (:id e)
                                :sources [(or (:source-id e) (str "n:" (:source e)))]
                                :targets [(or (:target-id e) (str "n:" (:target e)))]}]
                      (if (pos? (.-length label))
                        (assoc base :labels [{:text label
                                              :width (+ (js/Math.ceil (measure label SUB-FONT)) 4)
                                              :height 14}])
                        base)))
                  edges)}))
