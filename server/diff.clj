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

(defn- box-parent [g nm] (get (:parent-of g) (str "b:" nm)))

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

(defn- diff-boxes [old-g new-g]
  (let [old-by (into {} (map (juxt :name identity)) (:boxes old-g))
        new-names (into #{} (map :name) (:boxes new-g))
        matched+added
        (mapv (fn [b]
                (if-let [o (get old-by (:name b))]
                  (let [ch (cond-> (changed-attrs (dissoc (:attrs o) :components)
                                                  (dissoc (:attrs b) :components))
                             (not= (box-parent old-g (:name b))
                                   (box-parent new-g (:name b)))
                             (assoc "box membership"
                                    {:old (box-parent old-g (:name b))
                                     :new (box-parent new-g (:name b))})
                             (not= (set (:components o)) (set (:components b)))
                             (assoc "components"
                                    {:old (vec (sort (:components o)))
                                     :new (vec (sort (:components b)))}))]
                    (if (seq ch) (assoc b :diff "modified" :changed ch) b))
                  (assoc b :diff "added")))
              (:boxes new-g))
        removed (into []
                      (comp (remove (fn [b] (contains? new-names (:name b))))
                            (map (fn [b] (assoc b :diff "removed"))))
                      (:boxes old-g))]
    (into matched+added removed)))

(defn- union-parent-of
  "New file's structure, plus old-parent entries for removed elements."
  [old-g new-g union-nodes union-boxes]
  (let [removed-ids (concat (keep (fn [[id n]]
                                    (when (= "removed" (:diff n)) (str "n:" id)))
                                  union-nodes)
                            (keep (fn [b]
                                    (when (= "removed" (:diff b)) (str "b:" (:name b))))
                                  union-boxes))]
    (merge (:parent-of new-g)
           (select-keys (:parent-of old-g) removed-ids))))

(defn- with-components
  "Rebuild each box's :components from the union parent-of: keep the box's
  own order for members that still live here, append removed members that
  point here (sorted for determinism)."
  [boxes parent-of]
  (mapv (fn [b]
          (let [kept (filterv (fn [cid] (= (:name b) (get parent-of cid)))
                              (:components b))
                kept-set (set kept)
                extra (vec (sort (for [[cid p] parent-of
                                       :when (and (= p (:name b))
                                                  (not (contains? kept-set cid)))]
                                   cid)))]
            (assoc b :components (into kept extra))))
        boxes))

(defn union
  "old-g/new-g are graph/normalize outputs; old-name/new-name label the
  files in warnings and the frontend legend."
  [old-g new-g old-name new-name]
  (let [nodes (diff-nodes old-g new-g)
        boxes0 (diff-boxes old-g new-g)
        parent-of (union-parent-of old-g new-g nodes boxes0)]
    {:nodes nodes
     :edges (:edges new-g)          ; diffed in Task 3
     :boxes (with-components boxes0 parent-of)
     :parent-of parent-of
     :compare {:old old-name :new new-name}
     :warnings (into (mapv (fn [w] (str old-name ": " w)) (:warnings old-g))
                     (mapv (fn [w] (str new-name ": " w)) (:warnings new-g)))}))
