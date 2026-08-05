(ns graph
  "Parsed EDN -> normalized, render-ready graph. Shape checks via malli
  (lenient: humanized warning + skip), semantics in plain Clojure. Never
  throws on any input value."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private EdgeShape
  [:map [:nodes [:tuple [:or :string :keyword] [:or :string :keyword]]]])

(def ^:private directions
  {:-> :-> :<- :<- :<-> :<-> :- :-
   "->" :-> "<-" :<- "<->" :<-> "-" :-})

(defn- ident->str
  "Coerce a node/box/component identifier to a display string. Keywords lose
  their leading colon (matching the old EDN->cheshire->frontend pipeline);
  anything else falls back to str."
  [x]
  (if (keyword? x) (name x) (str x)))

(defn- coerce-str [x fallback]
  (ident->str (if (nil? x) fallback x)))

(defn- explain-str
  "nil when value matches schema, else the humanized malli explanation
  rendered as a string."
  [schema value]
  (when-let [expl (m/explain schema value)]
    (pr-str (me/humanize expl))))

(defn- top-level [raw warn! k pred coerce-empty msg]
  (let [v (get raw k)]
    (cond (nil? v) coerce-empty
          (pred v) (if (set? v) (vec v) v)
          :else (do (warn! msg) coerce-empty))))

(defn- boxes-map->seq
  "Map-form boxes {:id {..}} -> box maps with the key as :name. Sorted by
  name so contested memberships resolve deterministically (EDN maps above
  8 entries do not preserve file order)."
  [m warn!]
  (->> (sort-by (fn [[k _]] (ident->str k)) (seq m))
       (keep (fn [[k v]]
               (let [nm (ident->str k)]
                 (cond
                   (nil? v) {:name nm}
                   (map? v) (do (when (some? (:name v))
                                  (warn! (str "box \"" nm "\": :name in value ignored (the key is the name)")))
                                (assoc v :name nm))
                   :else (do (warn! (str "box \"" nm "\": value must be a map, skipped")) nil)))))
       vec))

(defn- edges-map->seq
  "Map-form edges {[:a :b] {..}} -> edge maps with the key as :nodes.
  Sorted by the coerced endpoint pair so edge ids stay stable regardless
  of EDN map iteration order."
  [m warn!]
  (->> (sort-by (fn [[k _]] (if (vector? k) (pr-str (mapv ident->str k)) (pr-str k)))
                (seq m))
       (keep (fn [[k v]]
               (cond
                 (not (and (vector? k) (= 2 (count k))))
                 (do (warn! (str "edge key " (pr-str k) ": must be a 2-element vector, skipped")) nil)

                 (and (some? v) (not (map? v)))
                 (do (warn! (str "edge " (pr-str k) ": value must be a map, skipped")) nil)

                 :else
                 (let [v (or v {})]
                   (when (some? (:nodes v))
                     (warn! (str "edge " (pr-str k) ": :nodes in value ignored (the key defines the endpoints)")))
                   (assoc v :nodes (mapv ident->str k))))))
       vec))

(defn- warn-reversed-pairs!
  "One warning per unordered pair that appears in both orientations —
  usually the same connection written twice."
  [edges-in warn!]
  (let [pairs (keep (fn [e] (when (and (map? e) (vector? (:nodes e)) (= 2 (count (:nodes e))))
                              (mapv ident->str (:nodes e))))
                    edges-in)
        present (set pairs)]
    (doseq [[a b] (distinct pairs)]
      (when (and (not= a b) (contains? present [b a]) (pos? (compare a b)))
        (warn! (str "edges [" b " " a "] and [" a " " b "] describe the same connection"))))))

(defn- build-nodes [nodes-in warn!]
  (reduce-kv
   (fn [acc k v]
     (let [k (ident->str k)
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
            (if-let [humanized (explain-str EdgeShape e)]
              (do (warn! (str "edge " i ": :nodes must be a vector of exactly 2 node names ("
                              humanized ")"))
                  nil)
              (let [[a0 b0] (:nodes e)
                    a (ident->str a0)
                    b (ident->str b0)
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
                (let [c (ident->str c)
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
        edges-in (let [e (:edges raw)]
                   (if (map? e)
                     (edges-map->seq e warn!)
                     (top-level raw warn! :edges #(or (sequential? %) (set? %)) []
                                ":edges must be a map or vector, ignoring it")))
        boxes-in (let [b (:boxes raw)]
                   (if (map? b)
                     (boxes-map->seq b warn!)
                     (top-level raw warn! :boxes #(or (sequential? %) (set? %)) []
                                ":boxes must be a map or vector, ignoring it")))
        _ (warn-reversed-pairs! edges-in warn!)
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
