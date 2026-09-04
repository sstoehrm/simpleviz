(ns simpleviz.transform-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.transform :refer [to-elk elk-fingerprint layout-positions seed-layout seedable?]]))

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

(test "elk-fingerprint: equal for identical inputs and attribute-only edits"
  (fn []
    (let [g1 (graph {:nodes {"a" (assoc (node "a" "svc") :attrs {:team "core"})
                             "b" (node "b")}
                     :edges [{:id "e0" :source "a" :target "b"
                              :arrows {:source false :target true}
                              :name "" :type "" :attrs {}}]})
          ;; hidden attrs and arrow direction feed the inspector/canvas,
          ;; not ELK — the fingerprint must not move
          g2 (-> g1
                 (assoc-in [:nodes "a" :attrs] {:team "growth" :lang "go"})
                 (assoc-in [:edges 0 :arrows] {:source true :target true}))]
      (assert/equal (elk-fingerprint (to-elk g1 measure))
                    (elk-fingerprint (to-elk g1 measure)))
      (assert/equal (elk-fingerprint (to-elk g1 measure))
                    (elk-fingerprint (to-elk g2 measure))))))

(test "elk-fingerprint: size, label and topology changes move it"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "" :type "" :attrs {}}]})
          fp (elk-fingerprint (to-elk g measure))
          renamed (assoc-in g [:nodes "a" :name] "a renamed node")
          typed (assoc-in g [:nodes "a" :type] "svc")
          labeled (assoc-in g [:edges 0 :name] "calls")
          extra-edge (update g :edges conj {:id "e1" :source "b" :target "a"
                                            :arrows {:source false :target true}
                                            :name "" :type "" :attrs {}})]
      (assert/notEqual fp (elk-fingerprint (to-elk renamed measure)))
      (assert/notEqual fp (elk-fingerprint (to-elk typed measure)))
      (assert/notEqual fp (elk-fingerprint (to-elk labeled measure)))
      (assert/notEqual fp (elk-fingerprint (to-elk extra-edge measure))))))

;; ---- layout seeding (stable relayout after edits) ----

(defn prev-layout
  "A fake ELK result: root > n:a, b:grp > (n:b, b:inner > n:c)."
  []
  {:id "root" :x 0 :y 0 :width 500 :height 300
   :children [{:id "n:a" :x 20 :y 40 :width 60 :height 30}
              {:id "b:grp" :x 160 :y 20 :width 300 :height 200
               :children [{:id "n:b" :x 14 :y 40 :width 60 :height 30}
                          {:id "b:inner" :x 100 :y 40 :width 150 :height 120
                           :children [{:id "n:c" :x 14 :y 40 :width 60 :height 30}]}]}]})

(test "layout-positions flattens a nested layout into absolute boxes"
  (fn []
    (let [pos (layout-positions (prev-layout))]
      (assert/deepEqual (get pos "n:a") {:x 20 :y 40 :w 60 :h 30 :parent "root"})
      (assert/deepEqual (get pos "n:b") {:x 174 :y 60 :w 60 :h 30 :parent "b:grp"})
      (assert/deepEqual (get pos "b:inner") {:x 260 :y 60 :w 150 :h 120 :parent "b:grp"})
      (assert/deepEqual (get pos "n:c") {:x 274 :y 100 :w 60 :h 30 :parent "b:inner"})
      (assert/ok (nil? (get pos "root"))))))

(defn edge' [id a b]
  {:id id :source a :target b :arrows {} :name "" :type "" :attrs {}})

(defn seeded-graph
  "The prev-layout graph plus a new node n:d in grp, fed by n:b."
  []
  (to-elk (graph {:nodes {"a" (node "a") "b" (node "b") "c" (node "c") "d" (node "d")}
                  :edges [(edge' "e0" "a" "b") (edge' "e1" "b" "d")]
                  :boxes [{:id "b:grp" :name "grp" :type "" :components ["n:b" "b:inner" "n:d"] :attrs {}}
                          {:id "b:inner" :name "inner" :type "" :components ["n:c"] :attrs {}}]
                  :parent-of {"n:b" "grp" "b:inner" "grp" "n:c" "inner" "n:d" "grp"}})
          measure))

(defn find-child [parent id]
  (first (filterv (fn [c] (= (:id c) id)) (:children parent))))

(test "seed-layout adds parent-relative position hints from the previous layout"
  (fn []
    (let [elk (seeded-graph)
          seeded (seed-layout elk (layout-positions (prev-layout)))
          a (find-child seeded "n:a")
          grp (find-child seeded "b:grp")
          b (find-child grp "n:b")
          inner (find-child grp "b:inner")
          c (find-child inner "n:c")]
      (assert/deepEqual [(:x a) (:y a)] [20 40])
      (assert/deepEqual [(:x grp) (:y grp)] [160 20])
      (assert/deepEqual [(:x b) (:y b)] [14 40])
      (assert/deepEqual [(:x inner) (:y inner)] [100 40])
      (assert/deepEqual [(:x c) (:y c)] [14 40]))))

(test "seed-layout places a new node right of its positioned source"
  (fn []
    (let [elk (seeded-graph)
          seeded (seed-layout elk (layout-positions (prev-layout)))
          d (find-child (find-child seeded "b:grp") "n:d")]
      ;; n:b sits at absolute (174,60) w=60 → right of it, relative to grp (160,20)
      (assert/ok (> (:x d) (- (+ 174 60) 160)) "x is right of the source")
      (assert/equal (:y d) 40))))

(test "seed-layout sets interactive strategies on root and boxes, positions on all"
  (fn []
    (let [elk (seeded-graph)
          seeded (seed-layout elk (layout-positions (prev-layout)))
          grp (find-child seeded "b:grp")
          inner (find-child grp "b:inner")
          a (find-child seeded "n:a")
          semi (fn [n] (get (:layoutOptions n) "elk.layered.crossingMinimization.semiInteractive"))
          layering (fn [n] (get (:layoutOptions n) "elk.layered.layering.strategy"))]
      (assert/equal (semi seeded) "true")
      (assert/equal (semi grp) "true")
      (assert/equal (semi inner) "true")
      (assert/equal (layering inner) "INTERACTIVE")
      ;; fully interactive crossing minimization would misplace ports
      (assert/ok (nil? (get (:layoutOptions seeded) "elk.layered.crossingMinimization.strategy")))
      (assert/ok (nil? (semi a)))
      ;; the in-layer order hint mirrors the parent-relative x/y
      (assert/equal (get (:layoutOptions a) "elk.position") "(20,40)")
      (assert/equal (get (:layoutOptions grp) "elk.position") "(160,20)")
      (assert/equal (get (:layoutOptions inner) "elk.position") "(100,40)")
      ;; the box keeps its own padding
      (assert/ok (some? (get (:layoutOptions grp) "elk.padding"))))))

(test "seed-layout leaves the input graph (and its fingerprint) untouched"
  (fn []
    (let [elk (seeded-graph)
          fp (elk-fingerprint elk)]
      (seed-layout elk (layout-positions (prev-layout)))
      (assert/equal (elk-fingerprint elk) fp))))

(test "seed-layout falls back to a gap when no sibling layer lies beyond the source"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b") "c" (node "c") "e" (node "e")}
                    :edges [(edge' "e0" "c" "e")]
                    :boxes [{:id "b:grp" :name "grp" :type "" :components ["n:b" "b:inner"] :attrs {}}
                            {:id "b:inner" :name "inner" :type "" :components ["n:c" "n:e"] :attrs {}}]
                    :parent-of {"n:b" "grp" "b:inner" "grp" "n:c" "inner" "n:e" "inner"}})
          seeded (seed-layout (to-elk g measure) (layout-positions (prev-layout)))
          e (find-child (find-child (find-child seeded "b:grp") "b:inner") "n:e")]
      ;; n:c is inner's only placed child, at absolute (274,100) w=60: nothing
      ;; lies right of it, so the hint is a gap past it, relative to inner (260,60)
      (assert/equal (:x e) (- (+ 274 60 80) 260))
      (assert/equal (:y e) 40))))

(test "seed-layout resolves a chain of new nodes and never leaves one unhinted"
  (fn []
    ;; new: n:d fed by placed n:a, n:f fed by n:d, n:g with no edges at all
    (let [g (graph {:nodes {"a" (node "a") "d" (node "d") "f" (node "f") "g" (node "g")}
                    :edges [(edge' "e0" "a" "d") (edge' "e1" "d" "f")]})
          seeded (seed-layout (to-elk g measure) (layout-positions (prev-layout)))
          x (fn [id] (:x (find-child seeded id)))]
      (assert/ok (> (x "n:d") (x "n:a")) "d right of a")
      (assert/ok (> (x "n:f") (x "n:d")) "f right of d, not in the leftmost layer")
      (assert/ok (> (x "n:g") (x "n:a")) "an edgeless node lands past its siblings"))))

(test "seed-layout treats a node moved to another box as new"
  (fn []
    ;; n:c leaves inner for grp, fed by n:b
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b") "c" (node "c")}
                    :edges [(edge' "e0" "b" "c")]
                    :boxes [{:id "b:grp" :name "grp" :type "" :components ["n:b" "b:inner" "n:c"] :attrs {}}
                            {:id "b:inner" :name "inner" :type "" :components [] :attrs {}}]
                    :parent-of {"n:b" "grp" "b:inner" "grp" "n:c" "grp"}})
          seeded (seed-layout (to-elk g measure) (layout-positions (prev-layout)))
          grp (find-child seeded "b:grp")
          b (find-child grp "n:b")
          c (find-child grp "n:c")]
      (assert/ok (> (:x c) (+ (:x b) 60)) "c hinted right of its source, not at its stale spot")
      (assert/ok (>= (:x c) 0) "hint is inside the new parent"))))

(test "seed-layout places a new box over placed contents nested in new sub-boxes"
  (fn []
    ;; n:a and inner{n:c} kept; outer{mid{inner}} is two new levels around inner
    (let [g (graph {:nodes {"a" (node "a") "c" (node "c")}
                    :boxes [{:id "b:outer" :name "outer" :type "" :components ["b:mid"] :attrs {}}
                            {:id "b:mid" :name "mid" :type "" :components ["b:inner"] :attrs {}}
                            {:id "b:inner" :name "inner" :type "" :components ["n:c"] :attrs {}}]
                    :parent-of {"b:mid" "outer" "b:inner" "mid" "n:c" "inner"}})
          seeded (seed-layout (to-elk g measure) (layout-positions (prev-layout)))
          outer (find-child seeded "b:outer")
          mid (find-child outer "b:mid")
          inner (find-child mid "b:inner")]
      ;; inner sat at absolute (260,60); each new level wraps it with padding
      (assert/deepEqual [(:x mid) (:y mid)] [14 40])
      (assert/deepEqual [(:x inner) (:y inner)] [14 40])
      (assert/deepEqual [(:x outer) (:y outer)] [(- 260 28) (- 60 80)])
      (assert/ok (> (:x outer) (:x (find-child seeded "n:a"))) "outer stays right of a"))))

(test "seedable? needs half the elements placed"
  (fn []
    (let [pos (layout-positions (prev-layout))
          mk (fn [ids] (to-elk (graph {:nodes (reduce (fn [m id] (assoc m id (node id))) {} ids)})
                               measure))]
      (assert/ok (seedable? (mk ["a" "b" "x"]) pos) "2 of 3 placed")
      (assert/ok (not (seedable? (mk ["a" "x" "y"]) pos)) "1 of 3 placed")
      (assert/ok (not (seedable? (mk []) pos)) "empty graph"))))
