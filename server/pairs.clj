(ns pairs
  "Pairs: a :pair on a node or box names the same thing in another graph
  file, \"path#id\" (or a vector of them), path relative to the file the
  pair is in. Pure: parsing, the reverse index, and attaching both
  directions to a normalized graph. Reading files, the served-folder
  boundary and forks come in through attach's ctx (serve supplies them),
  so this requires neither serve nor fork."
  (:require [clojure.string :as str]
            [paths]))

(def ^:private shape-problem ":pair must be \"file#id\" or a vector of them")

(defn parse-value
  "The pairs a :pair value declares: {:pairs [{:raw :path :id}]
  :problems [message]}. nil declares none; the last # splits path from
  id."
  [v]
  (let [strs (cond (nil? v) []
                   (string? v) [v]
                   (and (vector? v) (every? string? v)) v
                   :else nil)]
    (if (nil? strs)
      {:pairs [] :problems [shape-problem]}
      (reduce
       (fn [acc s]
         (let [i (str/last-index-of s "#")
               path (when i (str/trim (subs s 0 i)))
               id (when i (str/trim (subs s (inc i))))]
           (cond
             (and (some? i) (= path "") (not= id ""))
             (update acc :problems conj
                     (str "pair " (pr-str s) " needs a file; pairs within one file aren't supported"))
             (or (nil? i) (= path "") (= id ""))
             (update acc :problems conj
                     (str "pair " (pr-str s) " needs a file and an id, as in \"deploy.edn#api\""))
             :else (update acc :pairs conj {:raw s :path path :id id}))))
       {:pairs [] :problems []} strs))))

(defn- elements
  "[kind id attrs] of every node and box of normalized graph g."
  [g]
  (concat (map (fn [n] ["node" (:id n) (:attrs n)]) (vals (:nodes g)))
          (map (fn [b] ["box" (:name b) (:attrs b)]) (:boxes g))))

(defn pair-files
  "The raw paths of g's well-formed pairs, distinct, in element order."
  [g]
  (->> (elements g)
       (mapcat (fn [[_ _ attrs]] (:pairs (parse-value (:pair attrs)))))
       (map :path)
       distinct
       vec))

(defn index
  "The reverse index over `entries`, a seq of [root-relative path,
  normalized graph]: \"<target file>#<id>\" -> [{:file :id :kind}], the
  elements pairing into it, in entry order. Pairs whose path climbs out
  of the root are left out; malformed ones too (their own file warns)."
  [entries]
  (reduce
   (fn [idx [rel g]]
     (reduce
      (fn [idx [kind id attrs]]
        (reduce
         (fn [idx {:keys [path] target-id :id}]
           (if-let [target (paths/resolve-ref rel path)]
             (update idx (str target "#" target-id) (fnil conj [])
                     {:file rel :id id :kind kind})
             idx))
         idx (:pairs (parse-value (:pair attrs)))))
      idx (elements g)))
   {} entries))

(defn- target-kind [tg id]
  (cond (contains? (:nodes tg) id) "node"
        (some #(= id (:name %)) (:boxes tg)) "box"))

(defn- outgoing
  "[pair entries, warnings] for the :pair value v of the element
  labelled `label` (\"node \\\"api\\\"\")."
  [{:keys [path read fork-of]} label v]
  (let [{:keys [pairs problems]} (parse-value v)]
    (reduce
     (fn [[out warns] {:keys [raw id] p :path}]
       (let [target (paths/resolve-ref path p)
             fail (fn [msg]
                    [(conj out {:dir "out" :file (or target p) :id id :raw raw :problem msg})
                     (conj warns (str label ": pair " (pr-str raw) ": " msg))])]
         (cond
           (nil? target) (fail (str p " leaves the served folder"))
           (some? (fork-of target))
           (fail (str target " is the fork of " (fork-of target) " — pair with the original"))
           :else
           (let [[tg err] (try [(read target) nil]
                               (catch Exception e [nil (ex-message e)]))]
             (cond
               (some? err) (fail err)
               (target-kind tg id)
               [(conj out {:dir "out" :file target :id id :kind (target-kind tg id) :raw raw}) warns]
               :else (fail (str "no node or box " id " in " target)))))))
     [[] (mapv (fn [m] (str label ": " m)) problems)]
     pairs)))

(defn attach
  "g (normalized) with :pairs on each paired node and box — \"out\"
  entries for the pairs it declares (with the target's :kind, or a
  :problem), \"in\" entries for pairs elsewhere naming it — and one
  warning per broken outgoing pair, malformed :pair, or :pair on an
  edge. ctx: :path g's root-relative path; :read root-relative path ->
  normalized graph, throwing with the problem as its message; :fork-of
  path -> the original it forks, or nil; :index from `index`. A node
  and a box sharing an id: incoming pairs mean the node."
  [g {:keys [path index] :as ctx}]
  (let [warns (atom [])
        node-ids (set (keys (:nodes g)))
        with-pairs (fn [el label id incoming?]
                     (let [[out ws] (outgoing ctx label (:pair (:attrs el)))
                           in (when incoming?
                                (mapv #(assoc % :dir "in") (get index (str path "#" id))))
                           ps (into out in)]
                       (swap! warns into ws)
                       (if (seq ps) (assoc el :pairs ps) el)))
        nodes (into {} (map (fn [[id n]] [id (with-pairs n (str "node " (pr-str id)) id true)]))
                    (:nodes g))
        boxes (mapv (fn [b] (with-pairs b (str "box " (pr-str (:name b))) (:name b)
                              (not (contains? node-ids (:name b)))))
                    (:boxes g))
        edge-warns (keep (fn [e]
                           (when (contains? (:attrs e) :pair)
                             (str "edge [" (:source e) " " (:target e) "]: :pair applies to nodes and boxes")))
                         (:edges g))]
    (-> g
        (assoc :nodes nodes :boxes boxes)
        (update :warnings (fnil into []) (concat @warns edge-warns)))))
