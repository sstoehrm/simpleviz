(ns simpleviz.prune-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.prune :refer [collapse-boxes collapse-scene]]))

(defn gnode [id] {:id id :name id :type "" :attrs {}})

(defn edge [id a b]
  {:id id :source a :target b :arrows {:source false :target true}
   :name "" :type "" :attrs {}})

(defn graph []
  (let [outer {:id "b:outer" :name "outer" :type "" :components ["b:inner" "n:a"] :attrs {}}
        inner {:id "b:inner" :name "inner" :type "" :components ["n:b" "n:d"] :attrs {}}]
    {:nodes {"a" (gnode "a") "b" (gnode "b") "c" (gnode "c") "d" (gnode "d")}
     :edges [(edge "e0" "c" "b")     ; outside -> inside inner
             (edge "e1" "b" "c")     ; inside inner -> outside
             (edge "e2" "b" "d")     ; wholly inside inner
             (edge "e3" "a" "b")]    ; outer-interior -> inner-interior
     :boxes [outer inner]
     :boxes-by-name {"outer" outer "inner" inner}
     :parent-of {"b:inner" "outer" "n:a" "outer" "n:b" "inner" "n:d" "inner"}
     :warnings []}))

(test "empty collapsed set returns the graph unchanged"
  (fn []
    (let [g (graph)]
      (assert/equal (collapse-boxes g #{}) g))))

(test "collapsing a box empties it and re-targets boundary edges"
  (fn []
    (let [g (collapse-boxes (graph) #{"inner"})]
      ;; interior nodes gone, others stay
      (assert/deepEqual (sort (js/Object.keys (:nodes g))) ["a" "c"])
      ;; inner survives as a collapsed, empty box, still inside outer
      (let [inner (get (:boxes-by-name g) "inner")]
        (assert/ok (:collapsed inner))
        (assert/deepEqual (:components inner) []))
      (assert/deepEqual (:components (get (:boxes-by-name g) "outer"))
                        ["b:inner" "n:a"])
      (assert/equal (get (:parent-of g) "b:inner") "outer")
      ;; e0: c -> box; e1: box -> c; e2 interior: gone; e3: a -> box
      (let [by-id (reduce (fn [acc e] (assoc acc (:id e) e)) {} (:edges g))]
        (assert/equal (:target-id (get by-id "e0")) "b:inner")
        (assert/equal (:target (get by-id "e0")) "inner")
        (assert/equal (:source-id (get by-id "e1")) "b:inner")
        (assert/equal (:source-id (get by-id "e3")) "n:a")
        (assert/equal (:target-id (get by-id "e3")) "b:inner")
        (assert/ok (nil? (get by-id "e2")))))))

(test "parallel edges into a collapsed box aggregate with a count"
  (fn []
    (let [raw (update (graph) :edges (fn [es] (conj es (edge "e4" "c" "d"))))
          g (collapse-boxes raw #{"inner"})
          agg (first (filterv (fn [e] (= (:id e) "e0")) (:edges g)))]
      ;; e0 (c->b) and e4 (c->d) both become c -> inner: merged into e0
      (assert/equal (:name agg) "2 edges")
      (assert/equal (:aggregated agg) 2)
      (assert/deepEqual (filterv (fn [e] (= (:id e) "e4")) (:edges g)) []))))

(test "a collapsed box inside a collapsed box is swallowed by the outer one"
  (fn []
    (let [g (collapse-boxes (graph) #{"outer" "inner"})]
      (assert/deepEqual (sort (js/Object.keys (:nodes g))) ["c"])
      ;; only outer remains, collapsed; inner is interior content
      (assert/deepEqual (mapv (fn [b] (:name b)) (:boxes g)) ["outer"])
      (assert/ok (:collapsed (first (:boxes g))))
      ;; e0 c->b and e1 b->c retarget to outer (different directions: two edges)
      (let [ids (sort (mapv (fn [e] (:id e)) (:edges g)))]
        (assert/deepEqual ids ["e0" "e1"]))
      (assert/equal (:target-id (first (filterv (fn [e] (= (:id e) "e0")) (:edges g))))
                    "b:outer"))))

(test "collapsing an unknown box name is a no-op on content"
  (fn []
    (let [g (collapse-boxes (graph) #{"ghost"})]
      (assert/deepEqual (sort (js/Object.keys (:nodes g))) ["a" "b" "c" "d"])
      (assert/equal (.-length (:edges g)) 4))))

(test "collapse-scene keeps the shell and boundary edges, drops interior"
  (fn []
    (let [items [{:kind "box" :id "b:outer" :x 1}
                 {:kind "box" :id "b:inner" :x 5}
                 {:kind "node" :id "n:a"} {:kind "node" :id "n:b"}
                 {:kind "node" :id "n:c"} {:kind "node" :id "n:d"}
                 {:kind "edge" :id "e0" :source "c" :target "b"}
                 {:kind "edge" :id "e2" :source "b" :target "d"}
                 {:kind "edge-label" :id "e0-label" :edge-id "e0"}
                 {:kind "edge-label" :id "e2-label" :edge-id "e2"}]
          sc {:items items :width 9 :height 9}
          out (collapse-scene sc (graph) #{"inner"})
          ids (mapv (fn [it] (:id it)) (:items out))]
      ;; inner stays (as collapsed shell), its interior nodes b/d go,
      ;; interior edge e2 + label go, boundary edge e0 stays
      (assert/deepEqual ids ["b:outer" "b:inner" "n:a" "n:c" "e0" "e0-label"])
      (let [inner (first (filterv (fn [it] (= (:id it) "b:inner")) (:items out)))]
        (assert/ok (:collapsed inner))))))

(test "collapse-scene with empty set returns scene unchanged"
  (fn []
    (let [sc {:items [] :width 1 :height 1}]
      (assert/equal (collapse-scene sc (graph) #{}) sc))))

(test "collapsed box rolls up hidden diff statuses as :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner")) ["added"]))
    ;; nested: change inside inner, collapse outer
    (let [raw (assoc-in (graph) [:nodes "b" :diff] "removed")
          g (collapse-boxes raw #{"outer"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "outer")) ["removed"]))
    ;; multiple statuses: sorted array
    (let [raw (-> (graph)
                  (assoc-in [:nodes "b" :diff] "removed")
                  (assoc-in [:nodes "d" :diff] "added"))
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner"))
                        ["added" "removed"]))
    ;; no changes -> nil, not []
    (let [g (collapse-boxes (graph) #{"inner"})]
      (assert/ok (nil? (:diff-inside (get (:boxes-by-name g) "inner")))))))

(test "fully-interior diff edge sets :diff-inside"
  (fn []
    (let [raw (update (graph) :edges
                      (fn [es] (mapv (fn [e] (if (= (:id e) "e2")
                                               (assoc e :diff "removed")
                                               e))
                                     es)))
          g (collapse-boxes raw #{"inner"})]
      (assert/deepEqual (:diff-inside (get (:boxes-by-name g) "inner")) ["removed"]))))

(test "aggregated edge with a changed constituent becomes modified"
  (fn []
    (let [raw (update (graph) :edges
                      (fn [es] (conj es (assoc (edge "e4" "c" "d") :diff "added"))))
          g (collapse-boxes raw #{"inner"})
          agg (first (filterv (fn [e] (= (:id e) "e0")) (:edges g)))]
      (assert/equal (:name agg) "2 edges")
      (assert/equal (:diff agg) "modified"))
    ;; without changed constituents it stays unmarked
    (let [raw (update (graph) :edges
                      (fn [es] (conj es (edge "e4" "c" "d"))))
          g (collapse-boxes raw #{"inner"})
          agg (first (filterv (fn [e] (= (:id e) "e0")) (:edges g)))]
      (assert/ok (not (:diff agg))))))

(test "collapse-scene marks freshly collapsed shells with :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          sc {:items [{:kind "box" :id "b:inner"}
                      {:kind "box" :id "b:outer"}
                      {:kind "node" :id "n:b"}]}
          out (collapse-scene sc raw #{"inner"})
          shell (first (filterv (fn [it] (= (:id it) "b:inner")) (:items out)))]
      (assert/ok (:collapsed shell))
      (assert/deepEqual (:diff-inside shell) ["added"]))))
