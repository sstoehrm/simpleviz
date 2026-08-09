(ns simpleviz.prune)

;; Collapse transform: a collapsed box keeps its place in the graph but
;; loses its contents; edges that crossed its boundary re-attach to the
;; box itself (aggregating parallels). Pure; mutable JS accumulators so
;; large graphs stay linear (squint's persistent assoc/conj copy per
;; call).

(defn- mark-dead
  "Transitive contents of the given boxes: {:boxes Set :nodes Set} of
  names. Includes the given boxes themselves in :boxes."
  [graph box-names]
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
    (doseq [b box-names] (mark b))
    {:boxes dead-boxes :nodes dead-nodes}))

(defn- contents-changed
  "Sorted array of the :diff statuses present in box b's transitive
  contents (member nodes, nested boxes, edges wholly inside); nil when
  none — never an empty array (empty arrays are truthy in JS and the
  canvas dot / stop expansion rely on nil = no hidden changes)."
  [graph b]
  (let [{bs' :boxes ns' :nodes} (mark-dead graph [b])
        acc (js/Set.)]
    (doseq [nm (js/Array.from bs')]
      (let [d (:diff (get (:boxes-by-name graph) nm))]
        (when (and (not= nm b) (some? d)) (.add acc d))))
    (doseq [n (js/Array.from ns')]
      (let [d (:diff (get (:nodes graph) n))]
        (when (some? d) (.add acc d))))
    (doseq [e (:edges graph)]
      (when (and (some? (:diff e))
                 (.has ns' (:source e))
                 (.has ns' (:target e)))
        (.add acc (:diff e))))
    (when (pos? (.-size acc))
      (.sort (js/Array.from acc)))))

(defn- effective-collapsed
  "Collapsed boxes that exist and are not inside another collapsed box
  (those are swallowed as ordinary interior content)."
  [graph collapsed]
  (let [cset (js/Set. (js/Array.from collapsed))
        inside? (fn [bname]
                  (loop [p (get (:parent-of graph) (str "b:" bname))]
                    (cond (nil? p) false
                          (.has cset p) true
                          :else (recur (get (:parent-of graph) (str "b:" p))))))]
    (.filter (js/Array.from cset)
             (fn [b] (and (some? (get (:boxes-by-name graph) b))
                          (not (inside? b)))))))

(defn collapse-boxes
  "graph with the boxes named in `collapsed-names` reduced to empty,
  :collapsed-flagged leaf boxes; their contents are removed and
  boundary-crossing edges re-attach to the box (parallel edges with the
  same endpoints and direction aggregate into one, labeled \"N edges\").
  Edges gain :source-id/:target-id elk ids; :source/:target stay display
  names."
  [graph collapsed-names]
  (let [effective (effective-collapsed graph collapsed-names)]
    (if (zero? (.-length effective))
      graph
      (let [{dead-boxes :boxes dead-nodes :nodes} (mark-dead graph effective)
            ;; the collapsed boxes themselves survive
            _ (doseq [b effective] (.delete dead-boxes b))
            owner (js/Map.)
            _ (doseq [b effective]
                (let [{ns' :nodes} (mark-dead graph [b])]
                  (doseq [n (js/Array.from ns')]
                    (when-not (.has owner n) (.set owner n b)))))
            nodes (let [o {}]
                    (doseq [[k v] (js/Object.entries (:nodes graph))]
                      (when-not (.has dead-nodes k) (assoc! o k v)))
                    o)
            eff-set (js/Set. effective)
            boxes (->> (:boxes graph)
                       (filterv (fn [b] (not (.has dead-boxes (:name b)))))
                       (mapv (fn [b]
                               (if (.has eff-set (:name b))
                                 (assoc b :collapsed true :components []
                                        :diff-inside (contents-changed graph (:name b)))
                                 (assoc b :components
                                        (filterv (fn [c]
                                                   (if (.startsWith c "n:")
                                                     (not (.has dead-nodes (.slice c 2)))
                                                     (not (.has dead-boxes (.slice c 2)))))
                                                 (:components b)))))))
            by-name (let [o {}] (doseq [b boxes] (assoc! o (:name b) b)) o)
            parent-of (let [o {}]
                        (doseq [[k v] (js/Object.entries (:parent-of graph))]
                          (when (and (not (.has dead-boxes v))
                                     (if (.startsWith k "n:")
                                       (not (.has dead-nodes (.slice k 2)))
                                       (not (.has dead-boxes (.slice k 2)))))
                            (assoc! o k v)))
                        o)
            resolve-end (fn [n]
                          (if-let [b (.get owner n)]
                            {:id (str "b:" b) :name b}
                            {:id (str "n:" n) :name n}))
            merged (js/Map.)
            order (js/Array.)
            _ (doseq [e (:edges graph)]
                (let [s (resolve-end (:source e))
                      t (resolve-end (:target e))]
                  (when (not= (:id s) (:id t))
                    (let [k (str (:id s) "→" (:id t)
                                 "|" (:source (:arrows e)) (:target (:arrows e)))]
                      (if-let [m (.get merged k)]
                        (.set merged k (assoc m :aggregated (inc (:aggregated m))
                                              :agg-diff (or (:agg-diff m) (some? (:diff e)))))
                        (do (.push order k)
                            (.set merged k
                                  (assoc e
                                         :source-id (:id s) :target-id (:id t)
                                         :source (:name s) :target (:name t)
                                         :aggregated 1 :agg-diff (some? (:diff e))))))))))
            edges (mapv (fn [k]
                          (let [m (.get merged k)]
                            (dissoc
                             (if (> (:aggregated m) 1)
                               (let [m (assoc m :name (str (:aggregated m) " edges")
                                              :type ""
                                              :attrs {:aggregated (:aggregated m)}
                                              :changed nil)]
                                 (if (:agg-diff m) (assoc m :diff "modified") (dissoc m :diff)))
                               m)
                             :agg-diff)))
                        order)]
        {:nodes nodes :edges edges :boxes boxes :boxes-by-name by-name
         :parent-of parent-of :warnings (:warnings graph)}))))

(defn collapse-scene
  "Instantly filter an existing scene for freshly collapsed boxes: their
  interior items and interior edges disappear, the shells stay (marked
  :collapsed), boundary edges keep pointing into the emptied shell until
  the fresh layout lands."
  [sc graph collapsed-names]
  (let [effective (effective-collapsed graph collapsed-names)]
    (if (zero? (.-length effective))
      sc
      (let [{dead-boxes :boxes dead-nodes :nodes} (mark-dead graph effective)
            _ (doseq [b effective] (.delete dead-boxes b))
            eff-set (js/Set. effective)
            dead-edges (js/Set.)
            items (-> (:items sc)
                      (.filter
                       (fn [it]
                         (case (:kind it)
                           "box" (not (.has dead-boxes (.slice (:id it) 2)))
                           "node" (not (.has dead-nodes (.slice (:id it) 2)))
                           "edge" (if (and (.has dead-nodes (:source it))
                                           (.has dead-nodes (:target it)))
                                    (do (.add dead-edges (:id it)) false)
                                    true)
                           "edge-label" (not (.has dead-edges (:edge-id it)))
                           true)))
                      (.map
                       (fn [it]
                         (if (and (= (:kind it) "box")
                                  (.has eff-set (.slice (:id it) 2)))
                           (assoc it :collapsed true
                                  :diff-inside (contents-changed graph (.slice (:id it) 2)))
                           it))))]
        (assoc sc :items items)))))
