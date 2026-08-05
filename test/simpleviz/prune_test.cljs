(ns simpleviz.prune-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.prune :refer [prune-hidden prune-scene]]))

(defn gnode [id] {:id id :name id :type "" :attrs {}})

(defn graph []
  (let [outer {:id "b:outer" :name "outer" :type "" :components ["b:inner" "n:a"] :attrs {}}
        inner {:id "b:inner" :name "inner" :type "" :components ["n:b"] :attrs {}}]
    {:nodes {"a" (gnode "a") "b" (gnode "b") "c" (gnode "c")}
     :edges [{:id "e0" :source "a" :target "b" :arrows {:source false :target true}
              :name "" :type "" :attrs {}}
             {:id "e1" :source "a" :target "c" :arrows {:source false :target true}
              :name "" :type "" :attrs {}}]
     :boxes [outer inner]
     :boxes-by-name {"outer" outer "inner" inner}
     :parent-of {"b:inner" "outer" "n:a" "outer" "n:b" "inner"}
     :warnings []}))

(test "empty hidden set returns the graph unchanged"
  (fn []
    (let [g (graph)]
      (assert/equal (prune-hidden g #{}) g))))

(test "hiding a nested box removes its nodes, edges, and membership"
  (fn []
    (let [g (prune-hidden (graph) #{"inner"})]
      (assert/deepEqual (sort (js/Object.keys (:nodes g))) ["a" "c"])
      (assert/deepEqual (mapv (fn [e] (:id e)) (:edges g)) ["e1"])
      (assert/deepEqual (mapv (fn [b] (:name b)) (:boxes g)) ["outer"])
      (assert/deepEqual (:components (first (:boxes g))) ["n:a"])
      (assert/equal (get (:parent-of g) "n:a") "outer")
      (assert/ok (nil? (get (:parent-of g) "n:b")))
      (assert/ok (nil? (get (:parent-of g) "b:inner"))))))

(test "hiding an outer box removes everything inside transitively"
  (fn []
    (let [g (prune-hidden (graph) #{"outer"})]
      (assert/deepEqual (js/Object.keys (:nodes g)) ["c"])
      (assert/deepEqual (:edges g) [])
      (assert/deepEqual (:boxes g) [])
      (assert/deepEqual (js/Object.keys (:parent-of g)) []))))

(test "hiding an unknown box name is a no-op on content"
  (fn []
    (let [g (prune-hidden (graph) #{"ghost"})]
      (assert/deepEqual (sort (js/Object.keys (:nodes g))) ["a" "b" "c"])
      (assert/equal (.-length (:boxes g)) 2))))

(test "prune-scene drops hidden items instantly, keeping positions"
  (fn []
    (let [items [{:kind "box" :id "b:inner" :x 5 :y 5}
                 {:kind "box" :id "b:other" :x 9 :y 9}
                 {:kind "edge" :id "e0" :source "a" :target "b"}
                 {:kind "edge" :id "e1" :source "a" :target "c"}
                 {:kind "edge-label" :id "e0-label" :edge-id "e0"}
                 {:kind "edge-label" :id "e1-label" :edge-id "e1"}
                 {:kind "node" :id "n:a" :x 1}
                 {:kind "node" :id "n:b" :x 2}]
          sc {:items items :width 100 :height 100}
          out (prune-scene sc (graph) #{"inner"})]
      (assert/deepEqual (mapv (fn [it] (:id it)) (:items out))
                        ["b:other" "e1" "e1-label" "n:a"])
      (assert/equal (:x (first (:items out))) 9)
      (assert/equal (:width out) 100))))

(test "prune-scene with empty hidden returns scene unchanged"
  (fn []
    (let [sc {:items [] :width 1 :height 1}]
      (assert/equal (prune-scene sc (graph) #{}) sc))))
