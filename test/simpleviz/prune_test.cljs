(ns simpleviz.prune-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.prune :refer [prune-hidden]]))

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
