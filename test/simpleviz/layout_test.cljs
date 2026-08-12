(ns simpleviz.layout-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.transform :refer [to-elk]]))

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
