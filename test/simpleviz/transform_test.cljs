(ns simpleviz.transform-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.validate :refer [validate]]
            [simpleviz.transform :refer [to-elk]]))

(defn measure [text _font] (* (.-length text) 7))

(test "node sizing uses label widths; typed nodes are taller"
  (fn []
    (let [g (validate {:nodes {"a" {:name "Hello" :type "svc"} "b" {}}})
          elk (to-elk g measure)
          a (first (filterv (fn [c] (= (:id c) "n:a")) (:children elk)))
          b (first (filterv (fn [c] (= (:id c) "n:b")) (:children elk)))]
      (assert/ok (>= (:width a) (measure "Hello" nil)))
      (assert/equal (:height a) 44)
      (assert/equal (:height b) 30))))

(test "boxes nest components; contained elements not repeated at root"
  (fn []
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :boxes [{:name "outer" :components ["inner" "a"]}
                               {:name "inner" :components ["b"]}]})
          elk (to-elk g measure)]
      (assert/deepEqual (mapv (fn [c] (:id c)) (:children elk)) ["b:outer"])
      (let [outer (nth (:children elk) 0)
            inner (first (filterv (fn [c] (= (:id c) "b:inner")) (:children outer)))]
        (assert/deepEqual (sort (mapv (fn [c] (:id c)) (:children outer))) ["b:inner" "n:a"])
        (assert/deepEqual (mapv (fn [c] (:id c)) (:children inner)) ["n:b"])
        (assert/ok (.includes (get (:layoutOptions outer) "elk.padding") "top=40"))))))

(test "edges use prefixed ids and live at the root"
  (fn []
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}]})
          elk (to-elk g measure)]
      (assert/deepEqual (:edges elk)
                        [{:id "e0" :sources ["n:a"] :targets ["n:b"]}]))))

(test "root layout options select hierarchical layered layout"
  (fn []
    (let [elk (to-elk (validate {}) measure)]
      (assert/equal (get (:layoutOptions elk) "elk.algorithm") "layered")
      (assert/equal (get (:layoutOptions elk) "elk.direction") "RIGHT")
      (assert/equal (get (:layoutOptions elk) "elk.hierarchyHandling") "INCLUDE_CHILDREN"))))
