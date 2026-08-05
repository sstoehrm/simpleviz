(ns simpleviz.prune)

;; Removes hidden boxes (by name) plus everything transitively inside
;; them from a normalized graph, ahead of layout. Pure; uses mutable JS
;; accumulators so large graphs stay linear (squint's persistent
;; assoc/conj copy per call).

(defn prune-hidden
  "graph with the boxes named in `hidden-names` (seq or set) removed,
  including their nested boxes, contained nodes, and touching edges."
  [graph hidden-names]
  (let [hidden (js/Array.from hidden-names)]
    (if (zero? (.-length hidden))
      graph
      (let [boxes-by-name (:boxes-by-name graph)
            dead-boxes (js/Set.)
            dead-nodes (js/Set.)
            mark (fn mark [bname]
                   (when (and (some? (get boxes-by-name bname))
                              (not (.has dead-boxes bname)))
                     (.add dead-boxes bname)
                     (doseq [c (:components (get boxes-by-name bname))]
                       (if (.startsWith c "n:")
                         (.add dead-nodes (.slice c 2))
                         (mark (.slice c 2))))))]
        (doseq [b hidden] (mark b))
        (let [live-comp? (fn [c]
                           (if (.startsWith c "n:")
                             (not (.has dead-nodes (.slice c 2)))
                             (not (.has dead-boxes (.slice c 2)))))
              nodes (let [o {}]
                      (doseq [[k v] (js/Object.entries (:nodes graph))]
                        (when-not (.has dead-nodes k) (assoc! o k v)))
                      o)
              boxes (->> (:boxes graph)
                         (filterv (fn [b] (not (.has dead-boxes (:name b)))))
                         (mapv (fn [b]
                                 (assoc b :components
                                        (filterv live-comp? (:components b))))))
              by-name (let [o {}]
                        (doseq [b boxes] (assoc! o (:name b) b))
                        o)
              edges (filterv (fn [e] (and (not (.has dead-nodes (:source e)))
                                          (not (.has dead-nodes (:target e)))))
                             (:edges graph))
              parent-of (let [o {}]
                          (doseq [[k v] (js/Object.entries (:parent-of graph))]
                            (when (and (not (.has dead-boxes v)) (live-comp? k))
                              (assoc! o k v)))
                          o)]
          {:nodes nodes :edges edges :boxes boxes :boxes-by-name by-name
           :parent-of parent-of :warnings (:warnings graph)})))))
