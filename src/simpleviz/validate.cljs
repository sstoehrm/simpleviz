(ns simpleviz.validate)

;; Normalizes the raw server JSON into a validated graph. Never throws on
;; bad data — problems become entries in :warnings and the offending
;; element is skipped.

(def ^:private directions #{"->" "<-" "<->" "-"})

(defn- plain-map? [x]
  (and (some? x) (= (.-constructor x) js/Object) (not (js/Array.isArray x))))

(defn- ->str [x fallback]
  (js/String (if (nil? x) fallback x)))

(defn validate [raw]
  (let [warnings (atom [])
        warn! (fn [msg] (swap! warnings conj msg))

        raw-nodes (let [n (:nodes raw)]
                    (cond (plain-map? n) n
                          (nil? n) {}
                          :else (do (warn! ":nodes must be a map, ignoring it") {})))
        raw-edges (let [e (:edges raw)]
                    (cond (js/Array.isArray e) e
                          (nil? e) []
                          :else (do (warn! ":edges must be a vector, ignoring it") [])))
        raw-boxes (let [b (:boxes raw)]
                    (cond (js/Array.isArray b) b
                          (nil? b) []
                          :else (do (warn! ":boxes must be a vector, ignoring it") [])))

        nodes (reduce (fn [acc [k v]]
                        (let [attrs (if (plain-map? v) v {})]
                          (assoc acc k {:id k
                                        :name (->str (:name attrs) k)
                                        :type (->str (:type attrs) "")
                                        :attrs attrs})))
                      {}
                      (js/Object.entries raw-nodes))

        edges (vec (filter some?
                (map-indexed
                 (fn [i e]
                   (if-not (plain-map? e)
                     (do (warn! (str "edge " i ": not a map, skipped")) nil)
                     (let [ends (:nodes e)]
                       (if (or (not (js/Array.isArray ends)) (not= 2 (.-length ends)))
                         (do (warn! (str "edge " i ": :nodes must be a vector of exactly 2 node names")) nil)
                         (let [missing (filterv (fn [n] (nil? (get nodes n))) ends)]
                           (if (pos? (.-length missing))
                             (do (warn! (str "edge " i " [" (.join ends " ") "]: unknown node(s): " (.join missing ", "))) nil)
                             (let [dir0 (if (nil? (:direction e)) "-" (:direction e))
                                   dir (if (.has directions dir0)
                                         dir0
                                         (do (warn! (str "edge " i ": unknown direction \"" dir0 "\", treating as undirected")) "-"))
                                   source (if (= dir "<-") (nth ends 1) (nth ends 0))
                                   target (if (= dir "<-") (nth ends 0) (nth ends 1))]
                               {:id (str "e" i)
                                :source source
                                :target target
                                :arrows {:source (= dir "<->") :target (not= dir "-")}
                                :name (->str (:name e) "")
                                :type (->str (:type e) "")
                                :attrs e})))))))
                 raw-edges)))

        boxes (atom [])
        boxes-by-name (atom {})
        _ (doseq [[i b] (map-indexed vector raw-boxes)]
            (cond
              (or (not (plain-map? b)) (nil? (:name b)))
              (warn! (str "box " i ": missing :name, skipped"))

              (some? (get @boxes-by-name (->str (:name b) "")))
              (warn! (str "box \"" (:name b) "\": duplicate name, later definition skipped"))

              :else
              (let [nm (->str (:name b) "")
                    comps (let [c (:components b)]
                            (cond (js/Array.isArray c) (vec c)
                                  (nil? c) []
                                  :else (do (warn! (str "box \"" nm "\": :components must be a collection, skipped")) [])))
                    box {:id (str "b:" nm)
                         :name nm
                         :type (->str (:type b) "")
                         :components comps
                         :attrs b}]
                (swap! boxes conj box)
                (swap! boxes-by-name assoc nm box))))

        ;; Membership: ELK needs a strict hierarchy — first box in file
        ;; order wins. Box objects are mutated in place (assoc!) so
        ;; :boxes and :boxes-by-name stay the same objects.
        parent-of (atom {})
        _ (doseq [box @boxes]
            (let [kept (atom [])]
              (doseq [c (:components box)]
                (let [is-node (some? (get nodes c))
                      is-box (some? (get @boxes-by-name c))]
                  (cond
                    (and (not is-node) (not is-box))
                    (warn! (str "box \"" (:name box) "\": unknown component \"" c "\""))

                    (and is-box (not is-node) (= c (:name box)))
                    (warn! (str "box \"" (:name box) "\" cannot contain itself"))

                    :else
                    (let [_ (when (and is-node is-box)
                              (warn! (str "\"" c "\" names both a node and a box; box \"" (:name box) "\" gets the node")))
                          id (if is-node (str "n:" c) (str "b:" c))]
                      (if (some? (get @parent-of id))
                        (warn! (str "\"" c "\" is already in box \"" (get @parent-of id) "\"; membership in \"" (:name box) "\" ignored"))
                        (do (swap! parent-of assoc id (:name box))
                            (swap! kept conj id)))))))
              (assoc! box :components @kept)))

        ;; Break containment cycles (a in b, b in a) by detaching one link.
        _ (doseq [box @boxes]
            (let [seen (js/Set. [(:name box)])]
              (loop [p (get @parent-of (str "b:" (:name box)))]
                (when (some? p)
                  (if (.has seen p)
                    (let [parent-name (get @parent-of (str "b:" (:name box)))
                          parent (get @boxes-by-name parent-name)]
                      (warn! (str "box containment cycle: detaching \"" (:name box) "\" from \"" parent-name "\""))
                      (assoc! parent :components
                              (filterv (fn [c] (not= c (str "b:" (:name box)))) (:components parent)))
                      (swap! parent-of dissoc (str "b:" (:name box))))
                    (do (.add seen p)
                        (recur (get @parent-of (str "b:" p)))))))))]

    {:nodes nodes
     :edges edges
     :boxes @boxes
     :boxes-by-name @boxes-by-name
     :parent-of @parent-of
     :warnings @warnings}))
