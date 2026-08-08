(ns diff
  "Two normalized graphs (graph/normalize output) -> one union graph with
  per-element :diff status (\"added\"/\"removed\"/\"modified\"; absent key =
  unchanged) and :changed {attr {:old v :new v}}. Layout structure follows
  the new graph; removed elements keep their old parent, which always
  exists in the union because removed boxes are included too.")

(defn- changed-attrs
  "attr -> {:old v :new v} for keys whose values differ (missing key = nil).
  Pseudo-attrs added by callers use string keys so they can never collide
  with EDN keyword attrs."
  [old-attrs new-attrs]
  (into {}
        (keep (fn [k]
                (let [o (get old-attrs k)
                      n (get new-attrs k)]
                  (when (not= o n)
                    [k {:old o :new n}]))))
        (distinct (concat (keys old-attrs) (keys new-attrs)))))

(defn- node-parent [g id] (get (:parent-of g) (str "n:" id)))

(defn- diff-nodes [old-g new-g]
  (let [old-nodes (:nodes old-g)
        new-nodes (:nodes new-g)
        matched+added
        (reduce-kv
         (fn [acc id n]
           (assoc acc id
                  (if-let [o (get old-nodes id)]
                    (let [ch (cond-> (changed-attrs (:attrs o) (:attrs n))
                               (not= (node-parent old-g id) (node-parent new-g id))
                               (assoc "box membership"
                                      {:old (node-parent old-g id)
                                       :new (node-parent new-g id)}))]
                      (if (seq ch) (assoc n :diff "modified" :changed ch) n))
                    (assoc n :diff "added"))))
         {} new-nodes)]
    (reduce-kv
     (fn [acc id o]
       (if (contains? new-nodes id)
         acc
         (assoc acc id (assoc o :diff "removed"))))
     matched+added old-nodes)))

(defn union
  "old-g/new-g are graph/normalize outputs; old-name/new-name label the
  files in warnings and the frontend legend."
  [old-g new-g old-name new-name]
  {:nodes (diff-nodes old-g new-g)
   :edges (:edges new-g)          ; diffed in Task 3
   :boxes (:boxes new-g)          ; diffed in Task 2
   :parent-of (:parent-of new-g)  ; union built in Task 2
   :compare {:old old-name :new new-name}
   :warnings (into (mapv (fn [w] (str old-name ": " w)) (:warnings old-g))
                   (mapv (fn [w] (str new-name ": " w)) (:warnings new-g)))})
