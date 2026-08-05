(ns graph
  "Parsed EDN -> normalized, render-ready graph. Shape checks via malli
  (lenient: humanized warning + skip), semantics in plain Clojure. Never
  throws on any input value."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private EdgeShape
  [:map [:nodes [:tuple :string :string]]])

(def ^:private directions
  {:-> :-> :<- :<- :<-> :<-> :- :-
   "->" :-> "<-" :<- "<->" :<-> "-" :-})

(defn- coerce-str [x fallback]
  (str (if (nil? x) fallback x)))

(defn- shape-warning
  "nil when value matches schema, else a humanized warning string."
  [schema label value]
  (when-let [expl (m/explain schema value)]
    (str label ": " (pr-str (me/humanize expl)))))

(defn- top-level [raw warn! k pred coerce-empty msg]
  (let [v (get raw k)]
    (cond (nil? v) coerce-empty
          (pred v) v
          :else (do (warn! msg) coerce-empty))))

(defn- build-nodes [nodes-in warn!]
  (reduce-kv
   (fn [acc k v]
     (let [k (str k)
           attrs (if (map? v) v {})]
       (when-not (or (nil? v) (map? v))
         (warn! (str "node \"" k "\": attributes must be a map, using {}")))
       (assoc acc k {:id k
                     :name (coerce-str (:name attrs) k)
                     :type (coerce-str (:type attrs) "")
                     :attrs attrs})))
   {} nodes-in))

(defn- build-edges [edges-in nodes warn!]
  (->> edges-in
       (map-indexed
        (fn [i e]
          (if-not (map? e)
            (do (warn! (str "edge " i ": not a map, skipped")) nil)
            (if-let [w (shape-warning EdgeShape (str "edge " i) e)]
              (do (warn! w) nil)
              (let [[a b] (:nodes e)
                    missing (remove #(contains? nodes %) [a b])]
                (if (seq missing)
                  (do (warn! (str "edge " i " [" a " " b "]: unknown node(s): "
                                  (str/join ", " missing)))
                      nil)
                  (let [dir0 (:direction e)
                        dir (cond
                              (nil? dir0) :-
                              (contains? directions dir0) (get directions dir0)
                              :else (do (warn! (str "edge " i ": unknown direction "
                                                    (pr-str dir0)
                                                    ", treating as undirected"))
                                        :-))
                        [source target] (if (= dir :<-) [b a] [a b])]
                    {:id (str "e" i)
                     :source source
                     :target target
                     :arrows {:source (= dir :<->) :target (not= dir :-)}
                     :name (coerce-str (:name e) "")
                     :type (coerce-str (:type e) "")
                     :attrs e})))))))
       (remove nil?)
       vec))

(defn- build-boxes [boxes-in warn!]
  (let [named (->> boxes-in
                   (map-indexed
                    (fn [i b]
                      (if-not (map? b)
                        (do (warn! (str "box " i ": not a map, skipped")) nil)
                        (let [nm (coerce-str (:name b) "")]
                          (if (str/blank? nm)
                            (do (warn! (str "box " i ": missing :name, skipped")) nil)
                            {:name nm :box b})))))
                   (remove nil?))]
    (first
     (reduce
      (fn [[acc seen] {:keys [name box]}]
        (if (contains? seen name)
          (do (warn! (str "box \"" name "\": duplicate name, later definition skipped"))
              [acc seen])
          (let [components
                (let [c (:components box)]
                  (cond (nil? c) []
                        (or (sequential? c) (set? c)) (vec c)
                        :else (do (warn! (str "box \"" name
                                              "\": :components must be a collection, skipped"))
                                  [])))]
            [(conj acc {:id (str "b:" name)
                        :name name
                        :type (coerce-str (:type box) "")
                        :components components
                        :attrs box})
             (conj seen name)])))
      [[] #{}] named))))

(defn- resolve-membership
  "First box in file order wins; components become prefixed ids."
  [boxes nodes warn!]
  (let [box-names (set (map :name boxes))]
    (reduce
     (fn [[bs parents] box]
       (let [[kept parents]
             (reduce
              (fn [[kept parents] c]
                (let [c (str c)
                      is-node (contains? nodes c)
                      is-box (contains? box-names c)]
                  (cond
                    (and (not is-node) (not is-box))
                    (do (warn! (str "box \"" (:name box) "\": unknown component \"" c "\""))
                        [kept parents])

                    (and is-box (not is-node) (= c (:name box)))
                    (do (warn! (str "box \"" (:name box) "\" cannot contain itself"))
                        [kept parents])

                    :else
                    (let [_ (when (and is-node is-box)
                              (warn! (str "\"" c "\" names both a node and a box; box \""
                                          (:name box) "\" gets the node")))
                          id (if is-node (str "n:" c) (str "b:" c))]
                      (if (contains? parents id)
                        (do (warn! (str "\"" c "\" is already in box \"" (get parents id)
                                        "\"; membership in \"" (:name box) "\" ignored"))
                            [kept parents])
                        [(conj kept id) (assoc parents id (:name box))])))))
              [[] parents] (:components box))]
         [(conj bs (assoc box :components kept)) parents]))
     [[] {}] boxes)))

(defn- break-cycles [boxes parent-of warn!]
  (reduce
   (fn [[bs parents] box]
     (loop [seen #{(:name box)}
            p (get parents (str "b:" (:name box)))]
       (cond
         (nil? p) [bs parents]

         (contains? seen p)
         (let [parent-name (get parents (str "b:" (:name box)))
               child-id (str "b:" (:name box))]
           (warn! (str "box containment cycle: detaching \"" (:name box)
                       "\" from \"" parent-name "\""))
           [(mapv (fn [b]
                    (if (= (:name b) parent-name)
                      (update b :components (fn [cs] (filterv #(not= % child-id) cs)))
                      b))
                  bs)
            (dissoc parents child-id)])

         :else (recur (conj seen p) (get parents (str "b:" p))))))
   [boxes parent-of] boxes))

(defn normalize [raw]
  (let [warnings (atom [])
        warn! (fn [msg] (swap! warnings conj msg))
        raw (if (map? raw)
              raw
              (do (when (some? raw) (warn! "root must be a map, ignoring content")) {}))
        nodes-in (top-level raw warn! :nodes map? {} ":nodes must be a map, ignoring it")
        edges-in (top-level raw warn! :edges sequential? [] ":edges must be a vector, ignoring it")
        boxes-in (top-level raw warn! :boxes sequential? [] ":boxes must be a vector, ignoring it")
        nodes (build-nodes nodes-in warn!)
        edges (build-edges edges-in nodes warn!)
        boxes0 (build-boxes boxes-in warn!)
        [boxes1 parents1] (resolve-membership boxes0 nodes warn!)
        [boxes parent-of] (break-cycles boxes1 parents1 warn!)]
    {:nodes nodes
     :edges edges
     :boxes boxes
     :parent-of parent-of
     :warnings @warnings}))
