(ns simpleviz.transform-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.transform :refer [to-elk]]))

(defn node
  ([id] (node id ""))
  ([id type] {:id id :name id :type type :attrs {}}))

(defn graph [g]
  {:nodes (or (:nodes g) {})
   :edges (or (:edges g) [])
   :boxes (or (:boxes g) [])
   :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (or (:boxes g) []))
   :parent-of (or (:parent-of g) {})
   :warnings []})

(defn measure [text _font] (* (.-length text) 7))

(test "node sizing uses label widths; typed nodes are taller"
  (fn []
    (let [g (graph {:nodes {"a" (assoc (node "a" "svc") :name "Hello")
                            "b" (node "b")}})
          elk (to-elk g measure)
          a (first (filterv (fn [c] (= (:id c) "n:a")) (:children elk)))
          b (first (filterv (fn [c] (= (:id c) "n:b")) (:children elk)))]
      (assert/ok (>= (:width a) (measure "Hello" nil)))
      (assert/equal (:height a) 44)
      (assert/equal (:height b) 30))))

(test "boxes nest components; contained elements not repeated at root"
  (fn []
    (let [boxes [{:id "b:outer" :name "outer" :type "" :components ["b:inner" "n:a"] :attrs {}}
                 {:id "b:inner" :name "inner" :type "" :components ["n:b"] :attrs {}}]
          g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :boxes boxes
                    :parent-of {"b:inner" "outer" "n:a" "outer" "n:b" "inner"}})
          elk (to-elk g measure)]
      (assert/deepEqual (mapv (fn [c] (:id c)) (:children elk)) ["b:outer"])
      (let [outer (nth (:children elk) 0)
            inner (first (filterv (fn [c] (= (:id c) "b:inner")) (:children outer)))]
        (assert/deepEqual (sort (mapv (fn [c] (:id c)) (:children outer))) ["b:inner" "n:a"])
        (assert/deepEqual (mapv (fn [c] (:id c)) (:children inner)) ["n:b"])
        (assert/ok (.includes (get (:layoutOptions outer) "elk.padding") "top=40"))))))

(test "edges use prefixed ids and live at the root"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "" :type "" :attrs {}}]})
          elk (to-elk g measure)]
      (assert/deepEqual (:edges elk)
                        [{:id "e0" :sources ["n:a"] :targets ["n:b"]}]))))

(test "root layout options select hierarchical layered layout"
  (fn []
    (let [elk (to-elk (graph {}) measure)]
      (assert/equal (get (:layoutOptions elk) "elk.algorithm") "layered")
      (assert/equal (get (:layoutOptions elk) "elk.direction") "RIGHT")
      (assert/equal (get (:layoutOptions elk) "elk.hierarchyHandling") "INCLUDE_CHILDREN")
      (assert/equal (get (:layoutOptions elk) "elk.layered.spacing.nodeNodeBetweenLayers") "80")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.nodeNode") "45")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.edgeNode") "30")
      (assert/equal (get (:layoutOptions elk) "elk.spacing.edgeEdge") "20")
      (assert/equal (get (:layoutOptions elk) "elk.edgeLabels.inline") "true"))))

(test "named edges get measured ELK labels; unnamed edges get none"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "calls" :type "http" :attrs {}}
                            {:id "e1" :source "b" :target "a"
                             :arrows {:source false :target true}
                             :name "" :type "" :attrs {}}]})
          elk (to-elk g measure)
          lbl (first (:labels (first (:edges elk))))]
      (assert/equal (:text lbl) "calls (http)")
      (assert/ok (>= (:width lbl) (measure "calls (http)" nil)))
      (assert/equal (:height lbl) 14)
      (assert/equal (:labels (second (:edges elk))) js/undefined))))

(test "diff edges get a glyph-prefixed label"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "calls" :type "http" :diff "added" :attrs {}}
                            {:id "e1" :source "b" :target "a"
                             :arrows {:source false :target true}
                             :name "" :type "" :diff "removed" :attrs {}}]})
          elk (to-elk g measure)
          labels (mapv (fn [e] (:text (first (or (:labels e) [{}])))) (:edges elk))]
      (assert/equal (nth labels 0) "+ calls (http)")
      (assert/equal (nth labels 1) "−"))))

(test "empty boxes become fixed-size leaves, not empty ELK compounds"
  (fn []
    (let [g (graph {:boxes [{:id "b:ghost" :name "ghost" :type "zone"
                             :components [] :attrs {}}]})
          elk (to-elk g measure)
          ghost (nth (:children elk) 0)]
      (assert/equal (:children ghost) js/undefined)
      (assert/ok (pos? (:width ghost)))
      (assert/equal (:height ghost) 44))))

(test "collapsed shells size by the display label, not the identity"
  (fn []
    (let [g (graph {:boxes [{:id "b:x" :name "x" :label "a much longer label"
                             :type "" :components ["n:a"] :attrs {} :collapsed true}]
                    :nodes {"a" (node "a")}
                    :parent-of {"n:a" "x"}})
          elk (to-elk g measure)
          x (first (filterv (fn [c] (= (:id c) "b:x")) (:children elk)))]
      (assert/ok (>= (:width x) (measure "a much longer label" nil))))))
