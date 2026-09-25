(ns simpleviz.layout-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.transform :refer [to-elk layout-positions seed-layout]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(defn node [id type] {:id id :name id :type type :attrs {}})

(defn graph [g]
  {:nodes (or (:nodes g) {})
   :edges (or (:edges g) [])
   :boxes (or (:boxes g) [])
   :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (or (:boxes g) []))
   :parent-of (or (:parent-of g) {})
   :warnings []})

(defn edge [i a b arrows]
  {:id (str "e" i) :source a :target b :arrows arrows :name "" :type "" :attrs {}})

(defn measure [text _font] (* (.-length text) 7))

(test "ELK lays out a nested boxed graph end to end"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "svc") "b" (node "b" "db") "c" (node "c" "")}
                    :edges [(edge 0 "a" "b" {:source false :target true})
                            (edge 1 "a" "c" {:source false :target true})
                            (edge 2 "b" "c" {:source true :target true})
                            (edge 3 "a" "c" {:source false :target false})]
                    :boxes [{:id "b:grp" :name "grp" :type ""
                             :components ["n:a" "n:b"] :attrs {}}]
                    :parent-of {"n:a" "grp" "n:b" "grp"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (assert/ok (and (pos? (:width layout)) (pos? (:height layout))))
                   (let [grp (first (filterv (fn [c] (= (:id c) "b:grp")) (:children layout)))]
                     (assert/ok grp "box present in layout")
                     (assert/equal (.-length (:children grp)) 2)
                     (doseq [child (:children grp)]
                       (assert/ok (and (some? (:x child)) (some? (:y child))))))
                   (assert/equal (.-length (:edges layout)) 4)
                   (doseq [e (:edges layout)]
                     (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                (str "edge " (:id e) " has sections")))))))))

(test "edges wholly inside a box get container-relative section coordinates"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "") "b" (node "b" "")}
                    :edges [(edge 0 "a" "b" {:source false :target true})]
                    :boxes [{:id "b:grp" :name "grp" :type ""
                             :components ["n:a" "n:b"] :attrs {}}]
                    :parent-of {"n:a" "grp" "n:b" "grp"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   ;; the renderer must offset by the container's absolute origin — this
                   ;; documents the contract it relies on
                   (assert/equal (:container (nth (:edges layout) 0)) "b:grp")))))))

(test "ELK returns label coordinates for labeled edges"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "") "b" (node "b" "")}
                    :edges [(assoc (edge 0 "a" "b" {:source false :target true})
                                   :name "calls" :type "http")]})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (let [lbl (first (:labels (first (:edges layout))))]
                     (assert/ok lbl "label present in layout output")
                     (assert/ok (and (some? (:x lbl)) (some? (:y lbl)))))))))))

(test "ELK routes edges to expanded boxes and between boxes"
  (fn []
    (let [box-edge (fn [i a b sid tid]
                     (assoc (edge i a b {:source false :target true})
                            :source-id sid :target-id tid))
          g (graph {:nodes {"web" (node "web" "") "api" (node "api" "")
                            "db" (node "db" "")}
                    :edges [(box-edge 0 "web" "backend" "n:web" "b:backend")
                            (box-edge 1 "backend" "storage" "b:backend" "b:storage")]
                    :boxes [{:id "b:backend" :name "backend" :type ""
                             :components ["n:api"] :attrs {}}
                            {:id "b:storage" :name "storage" :type ""
                             :components ["n:db"] :attrs {}}]
                    :parent-of {"n:api" "backend" "n:db" "storage"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (assert/equal (.-length (:edges layout)) 2)
                   (doseq [e (:edges layout)]
                     (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                (str "edge " (:id e) " routes to a box endpoint")))))))))

(test "ELK routes a child-to-ancestor edge (compare-mode union shape)"
  (fn []
    (let [g (graph {:nodes {"web" (node "web" "") "api" (node "api" "")}
                    :edges [(assoc (edge 0 "web" "backend" {:source false :target true})
                                   :source-id "n:web" :target-id "b:backend")]
                    :boxes [{:id "b:backend" :name "backend" :type ""
                             :components ["n:web" "n:api"] :attrs {}}]
                    :parent-of {"n:web" "backend" "n:api" "backend"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (let [e (nth (:edges layout) 0)]
                     (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                "child->ancestor edge has routed sections"))))))))

(test "ELK gives an empty box (compare-mode removed shell) a real size"
  (fn []
    (let [g (graph {:nodes {"api" (node "api" "")}
                    :boxes [{:id "b:backend" :name "backend" :type ""
                             :components ["n:api" "b:storage"] :attrs {}}
                            {:id "b:storage" :name "storage" :type "zone"
                             :components [] :attrs {} :diff "removed"}]
                    :parent-of {"n:api" "backend" "b:storage" "backend"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (let [backend (first (filterv (fn [c] (= (:id c) "b:backend"))
                                                 (:children layout)))
                         storage (first (filterv (fn [c] (= (:id c) "b:storage"))
                                                 (:children backend)))]
                     (assert/ok (pos? (:width storage)) "empty box has width")
                     (assert/ok (pos? (:height storage)) "empty box has height"))))))))

(test "seeded interactive relayout keeps layer order after adding a node"
  (fn []
    (let [box {:id "b:grp" :name "grp" :type "" :components ["n:a" "n:b" "n:c"] :attrs {}}
          g0 (graph {:nodes {"a" (node "a" "") "b" (node "b" "") "c" (node "c" "")}
                     :edges [(edge 0 "a" "b" {:source false :target true})
                             (edge 1 "b" "c" {:source false :target true})]
                     :boxes [box]
                     :parent-of {"n:a" "grp" "n:b" "grp" "n:c" "grp"}})
          box' (assoc box :components ["n:a" "n:b" "n:c" "n:d"])
          g1 (graph {:nodes {"a" (node "a" "") "b" (node "b" "") "c" (node "c" "") "d" (node "d" "")}
                     :edges [(edge 0 "a" "b" {:source false :target true})
                             (edge 1 "b" "c" {:source false :target true})
                             (edge 2 "b" "d" {:source false :target true})]
                     :boxes [box']
                     :parent-of {"n:a" "grp" "n:b" "grp" "n:c" "grp" "n:d" "grp"}})
          elk (ELK.)]
      (-> (.layout elk (to-elk g0 measure))
          (.then (fn [l0]
                   (.layout elk (seed-layout (to-elk g1 measure) (layout-positions l0)))))
          (.then (fn [l1]
                   (let [pos (layout-positions l1)
                         x (fn [id] (:x (get pos id)))]
                     (assert/ok (< (x "n:a") (x "n:b")) "a stays left of b")
                     (assert/ok (< (x "n:b") (x "n:c")) "b stays left of c")
                     (assert/equal (x "n:d") (x "n:c") "new node shares c's layer")
                     (doseq [e (:edges l1)]
                       (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                  (str "edge " (:id e) " has sections"))))))))))

;; #94: the issue's graph. Four top-level boxes of different widths share
;; one column; edits happen inside box3.
(defn- issue-94-graph [edges]
  (let [box (fn [nm members] {:id (str "b:" nm) :name nm :type "" :attrs {}
                              :components (mapv (fn [m] (str "n:" m)) members)})]
    (graph {:nodes {"api" (node "api" "service") "node2" (node "node2" "test")
                    "node3" (node "node3" "test") "node4" (node "node4" "test")
                    "box5" (node "box5" "test") "box6" (node "box6" "test")}
            :edges edges
            :boxes [(box "backend" ["api"]) (box "box2" ["node2"])
                    (box "box3" ["node3" "box5" "box6"]) (box "box4" ["node4"])]
            :parent-of {"n:api" "backend" "n:node2" "box2" "n:node3" "box3"
                        "n:box5" "box3" "n:box6" "box3" "n:node4" "box4"}})))

(def ^:private arrow {:source false :target true})

(defn- relayout
  "Fresh layout of g0, then the seeded relayout of g1 the page runs after
  an edit; [positions before, positions after]."
  [g0 g1]
  (let [elk (ELK.)]
    (-> (.layout elk (to-elk g0 measure))
        (.then (fn [l0]
                 (let [p0 (layout-positions l0)]
                   (.then (.layout elk (seed-layout (to-elk g1 measure) p0))
                          (fn [l1] [p0 (layout-positions l1)]))))))))

(test "a seeded relayout of an unchanged graph moves nothing (#94)"
  (fn []
    ;; boxes reached ELK without a size, so its interactive layering saw
    ;; them as 0 wide and split boxes that shared a column
    (let [g (issue-94-graph [(edge 0 "box6" "box5" arrow) (edge 1 "node3" "box6" arrow)])]
      (.then (relayout g g)
             (fn [[p0 p1]]
               (doseq [id (js/Object.keys p0)]
                 (let [a (get p0 id) b (get p1 id)]
                   (assert/ok (and (< (js/Math.abs (- (:x a) (:x b))) 1)
                                   (< (js/Math.abs (- (:y a) (:y b))) 1))
                              (str id " moved from " (:x a) "," (:y a) " to " (:x b) "," (:y b))))))))))

(test "an edit inside one box keeps the other boxes in their column (#94)"
  (fn []
    (let [g0 (issue-94-graph [(edge 0 "box6" "box5" arrow)])
          g1 (issue-94-graph [(edge 0 "box6" "box5" arrow) (edge 1 "node3" "box6" arrow)])
          others ["b:backend" "b:box2" "b:box4"]
          column? (fn [pos]
                    ;; every pair's x ranges overlap: one layer
                    (every? (fn [[a b]]
                              (let [p (get pos a) q (get pos b)]
                                (and (< (:x p) (+ (:x q) (:w q))) (< (:x q) (+ (:x p) (:w p))))))
                            (for [a others b others :when (not= a b)] [a b])))
          order (fn [pos] (sort-by (fn [id] (:y (get pos id))) others))]
      (.then (relayout g0 g1)
             (fn [[p0 p1]]
               (assert/ok (column? p0) "the fresh layout stacks them in one column")
               (assert/ok (column? p1) "they still share one column after the edit")
               (assert/deepEqual (vec (order p1)) (vec (order p0)) "in the same order"))))))
