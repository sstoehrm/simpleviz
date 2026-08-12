(ns simpleviz.scene-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.scene :as scene :refer [build-scene]]))

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
      (assert/deepEqual (:arrows e) {:source false :target true})
      (assert/equal (:source e) "a")
      (assert/equal (:target e) "b"))))

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

(test "items carry padded bounding boxes"
  (fn []
    (let [[a] (filterv (fn [it] (= (:id it) "n:a")) (items-of "node"))
          [box] (items-of "box")
          [e] (items-of "edge")]
      ;; node n:a at 24,60 60x30, pad 10
      (assert/deepEqual (:bbox a) {:x0 14 :y0 50 :x1 94 :y1 100})
      (assert/deepEqual (:bbox box) {:x0 0 :y0 10 :x1 220 :y1 180})
      ;; edge points span 11..19 x 22..30, pad 10
      (assert/deepEqual (:bbox e) {:x0 1 :y0 12 :x1 29 :y1 40}))))

(test "visible? intersects bbox with a view rect"
  (fn []
    (let [it {:bbox {:x0 10 :y0 10 :x1 50 :y1 50}}]
      (assert/ok (scene/visible? it {:x0 0 :y0 0 :x1 20 :y1 20}))
      (assert/ok (scene/visible? it {:x0 49 :y0 49 :x1 100 :y1 100}))
      (assert/ok (not (scene/visible? it {:x0 51 :y0 0 :x1 100 :y1 100})))
      (assert/ok (not (scene/visible? it {:x0 0 :y0 51 :x1 100 :y1 100}))))))

(test "build-scene stays linear at 10k nodes + 10k edges (perf regression guard)"
  (fn []
    (let [n 10000
          idxs (js/Array.from (js/Array. n) (fn [_ i] i))
          nodes (js/Object.fromEntries
                 (.map idxs (fn [i] [(str "n" i)
                                     {:id (str "n" i) :name (str "n" i) :type "" :attrs {}}])))
          children (.map idxs
                         (fn [i] {:id (str "n:n" i) :x (* i 10) :y 0 :width 8 :height 8}))
          gedges (.map idxs
                       (fn [i] {:id (str "e" i) :source (str "n" i) :target (str "n" i)
                                :arrows {:source false :target true}
                                :name "" :type "" :attrs {}}))
          ledges (.map idxs
                       (fn [i] {:id (str "e" i)
                                :sections [{:startPoint {:x (* i 10) :y 4}
                                            :endPoint {:x (+ 8 (* i 10)) :y 4}}]}))
          layout {:width (* n 10) :height 100 :children children :edges ledges}
          g {:nodes nodes :edges gedges :boxes [] :boxes-by-name {} :parent-of {} :warnings []}
          t0 (js/performance.now)
          sc (build-scene {:layout layout :graph g :colors {:neutral-node "x" :neutral-box {}}})
          elapsed (- (js/performance.now) t0)]
      (assert/equal (.-length (:items sc)) (* 2 n))
      (assert/ok (< elapsed 2000)
                 (str "build-scene took " (js/Math.round elapsed) "ms for 20k items")))))

(def diff-graph
  {:nodes {"a" (assoc (gnode "a" "svc") :diff "added")
           "b" (assoc (gnode "b" "") :diff "removed")}
   :edges [{:id "e0" :source "a" :target "b"
            :arrows {:source false :target true} :name "" :type ""
            :diff "modified" :changed {:direction {:old "->" :new "<->"}}
            :attrs {}}]
   :boxes-by-name {"grp" {:id "b:grp" :name "grp" :type "" :components ["n:a"]
                          :diff "modified" :diff-inside true :attrs {}}}})

(def diff-layout
  {:width 500 :height 300
   :children [{:id "b:grp" :x 10 :y 20 :width 200 :height 150
               :children [{:id "n:a" :x 14 :y 40 :width 60 :height 30}]}
              {:id "n:b" :x 300 :y 50 :width 60 :height 30}]
   :edges [{:id "e0" :container "root"
            :sections [{:startPoint {:x 1 :y 2} :endPoint {:x 5 :y 6}}]
            :labels [{:x 2 :y 3 :width 20 :height 14 :text "~"}]}]})

(test "diff status flows through to scene items"
  (fn []
    (let [sc (build-scene {:layout diff-layout :graph diff-graph :colors colors})
          by-id (reduce (fn [acc it] (assoc acc (:id it) it)) {} (:items sc))]
      (assert/equal (:diff (get by-id "n:a")) "added")
      (assert/equal (:diff (get by-id "n:b")) "removed")
      (assert/equal (:diff (get by-id "b:grp")) "modified")
      (assert/ok (:diff-inside (get by-id "b:grp")))
      (assert/equal (:diff (get by-id "e0")) "modified")
      (assert/ok (some? (:changed (get by-id "e0"))))
      (assert/equal (:diff (get by-id "e0-label")) "modified"))))

(test "diff-stops groups visible items by status"
  (fn []
    (let [items [{:kind "node" :id "n:a" :diff "added"}
                 {:kind "node" :id "n:b"}
                 {:kind "edge" :id "e0" :diff "removed"}
                 {:kind "edge-label" :id "e0-label" :diff "removed"}
                 {:kind "box" :id "b:x" :diff "modified"}]
          stops (scene/diff-stops {:items items})]
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "added")) ["n:a"])
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "modified")) ["b:x"])
      ;; edge labels are never stops
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "removed")) ["e0"]))))

(test "diff-stops expands collapsed shells once per hidden status"
  (fn []
    (let [shell {:kind "box" :id "b:s" :diff "modified"
                 :collapsed true :diff-inside ["modified" "removed"]}
          stops (scene/diff-stops {:items [shell]})]
      ;; own :diff and hidden "modified" dedupe to ONE stop
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "modified")) ["b:s"])
      (assert/deepEqual (mapv (fn [it] (:id it)) (get stops "removed")) ["b:s"])
      (assert/deepEqual (get stops "added") []))))

(test "diff-stops is nil-safe"
  (fn []
    (assert/deepEqual (get (scene/diff-stops nil) "added") [])))

(test "scene edges carry endpoint ids through"
  (fn []
    (let [g {:nodes {"a" (gnode "a" "")}
             :edges [{:id "e0" :source "a" :target "bx"
                      :source-id "n:a" :target-id "b:bx"
                      :arrows {:source false :target true}
                      :name "" :type "" :attrs {}}]
             :boxes-by-name {"bx" {:id "b:bx" :name "bx" :type "" :components [] :attrs {}}}}
          layout {:width 100 :height 100
                  :children [{:id "n:a" :x 0 :y 0 :width 60 :height 30}
                             {:id "b:bx" :x 70 :y 0 :width 30 :height 30}]
                  :edges [{:id "e0" :container "root"
                           :sections [{:startPoint {:x 0 :y 0} :endPoint {:x 1 :y 1}}]}]}
          sc (build-scene {:layout layout :graph g :colors colors})
          e (first (filterv (fn [it] (= (:kind it) "edge")) (:items sc)))]
      (assert/equal (:source-id e) "n:a")
      (assert/equal (:target-id e) "b:bx"))))

(test "empty boxes render as shells: collapsed style, flagged empty"
  (fn []
    (let [g {:nodes {} :edges []
             :boxes-by-name {"ghost" {:id "b:ghost" :name "ghost" :type ""
                                      :components [] :attrs {} :diff "removed"}}}
          l {:width 100 :height 60 :edges []
             :children [{:id "b:ghost" :x 10 :y 10 :width 80 :height 30}]}
          [box] (filterv (fn [it] (= (:kind it) "box"))
                         (:items (build-scene {:layout l :graph g :colors colors})))]
      (assert/equal (:collapsed box) true)
      (assert/equal (:empty box) true))))

(test "user-collapsed shells are not flagged empty"
  (fn []
    (let [g {:nodes {} :edges []
             :boxes-by-name {"grp2" {:id "b:grp2" :name "grp2" :type ""
                                     :components [] :attrs {} :collapsed true}}}
          l {:width 100 :height 60 :edges []
             :children [{:id "b:grp2" :x 10 :y 10 :width 80 :height 30}]}
          [box] (filterv (fn [it] (= (:kind it) "box"))
                         (:items (build-scene {:layout l :graph g :colors colors})))]
      (assert/equal (:collapsed box) true)
      (assert/ok (not (:empty box))))))
