(ns simpleviz.scene-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.scene :refer [build-scene]]))

(def colors
  {:node {"svc" "hsl(120 65% 38%)"}
   :box {"zone" {:border "hsl(1 45% 55%)" :fill "hsl(1 45% 55% / 0.1)"}}
   :neutral-node "hsl(0 0% 40%)"
   :neutral-box {:border "hsl(0 0% 65%)" :fill "hsl(0 0% 65% / 0.1)"}})

(defn gnode [id type] {:id id :name id :type type :attrs {}})

(def graph
  {:nodes {"a" (gnode "a" "svc") "b" (gnode "b" "")}
   :edges [{:id "e0" :source "a" :target "b"
            :arrows {:source false :target true} :name "calls" :type "http"
            :attrs {:nodes ["a" "b"]}}]
   :boxes-by-name {"grp" {:id "b:grp" :name "grp" :type "" :components ["n:a"] :attrs {}}}})

(def layout
  {:width 500 :height 300
   :children [{:id "b:grp" :x 10 :y 20 :width 200 :height 150
               :children [{:id "n:a" :x 14 :y 40 :width 60 :height 30}]}
              {:id "n:b" :x 300 :y 50 :width 60 :height 30}]
   :edges [{:id "e0" :container "b:grp"
            :sections [{:startPoint {:x 1 :y 2} :bendPoints [{:x 3 :y 4}]
                        :endPoint {:x 5 :y 6}}
                       {:startPoint {:x 7 :y 8} :endPoint {:x 9 :y 10}}]
            :labels [{:x 2 :y 3 :width 40 :height 14 :text "calls (http)"}]}]})

(defn scene [] (build-scene {:layout layout :graph graph :colors colors}))

(defn items-of [kind] (filterv (fn [it] (= (:kind it) kind)) (:items (scene))))

(test "nodes get absolute positions and resolved colors"
  (fn []
    (let [[a] (filterv (fn [it] (= (:id it) "n:a")) (items-of "node"))
          [b] (filterv (fn [it] (= (:id it) "n:b")) (items-of "node"))]
      (assert/equal (:x a) 24)   ; 10 + 14
      (assert/equal (:y a) 60)   ; 20 + 40
      (assert/equal (:color a) "hsl(120 65% 38%)")
      (assert/equal (:x b) 300)
      (assert/equal (:color b) "hsl(0 0% 40%)"))))

(test "boxes carry absolute rect, title-h, and neutral colors when untyped"
  (fn []
    (let [[box] (items-of "box")]
      (assert/equal (:x box) 10)
      (assert/equal (:w box) 200)
      (assert/equal (:title-h box) 28)
      (assert/equal (:border box) "hsl(0 0% 65%)"))))

(test "edge sections are container-offset with pen-lifts preserved"
  (fn []
    (let [[e] (items-of "edge")]
      (assert/equal (.-length (:sections e)) 2)
      (assert/deepEqual (nth (:sections e) 0)
                        [{:x 11 :y 22} {:x 13 :y 24} {:x 15 :y 26}])
      (assert/deepEqual (nth (:sections e) 1)
                        [{:x 17 :y 28} {:x 19 :y 30}])
      (assert/equal (.-length (:points e)) 5)
      (assert/deepEqual (:arrows e) {:source false :target true}))))

(test "edge labels are container-offset"
  (fn []
    (let [[lbl] (items-of "edge-label")]
      (assert/equal (:x lbl) 12)   ; 2 + 10
      (assert/equal (:y lbl) 23)   ; 3 + 20
      (assert/equal (:text lbl) "calls (http)")
      (assert/equal (:edge-id lbl) "e0"))))

(test "draw order: boxes, edges, labels, nodes; scene carries size"
  (fn []
    (let [kinds (mapv (fn [it] (:kind it)) (:items (scene)))]
      (assert/deepEqual kinds ["box" "edge" "edge-label" "node" "node"])
      (assert/equal (:width (scene)) 500))))

(test "edges without sections or without graph entry are skipped"
  (fn []
    (let [l2 (assoc layout :edges [{:id "e0" :container "b:grp" :sections []}
                                   {:id "ghost" :sections [{:startPoint {:x 0 :y 0}
                                                            :endPoint {:x 1 :y 1}}]}])
          s (build-scene {:layout l2 :graph graph :colors colors})]
      (assert/deepEqual (filterv (fn [it] (= (:kind it) "edge")) (:items s)) []))))
