(ns simpleviz.manual-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.manual :refer [GRID manual? place snapshot top-at with-pos cell->xy xy->cell]]))

;; a layered result: a and b at the top level, box x holding c; e0 a
;; self-loop on a, e1 from a into the box, e2 inside the box
(def layout
  {:id "root"
   :children [{:id "n:a" :x 20 :y 20 :width 40 :height 30}
              {:id "n:b" :x 100 :y 20 :width 40 :height 30}
              {:id "b:x" :x 200 :y 20 :width 100 :height 80
               :children [{:id "n:c" :x 10 :y 30 :width 40 :height 30}]}]
   :edges [{:id "e0" :container "root" :sources ["n:a"] :targets ["n:a"]
            :sections [{:startPoint {:x 30 :y 20} :bendPoints [{:x 30 :y 10} {:x 50 :y 10}] :endPoint {:x 50 :y 20}}]
            :labels [{:text "again" :x 25 :y 0 :width 30 :height 14}]}
           {:id "e1" :container "root" :sources ["n:a"] :targets ["n:c"]
            :sections [{:startPoint {:x 60 :y 35} :bendPoints [{:x 130 :y 35}] :endPoint {:x 210 :y 65}}]
            :labels [{:text "calls" :x 120 :y 20 :width 30 :height 14}]}
           {:id "e2" :container "b:x" :sources ["n:c"] :targets ["n:c"]
            :sections [{:startPoint {:x 10 :y 40} :endPoint {:x 20 :y 40}}]}]
   :width 320 :height 120})

(def graph
  {:nodes {"a" {:id "a" :pos [0 0]} "b" {:id "b"} "c" {:id "c"}}
   :boxes [{:name "x" :pos [10 5]}]
   :boxes-by-name {"x" {:name "x" :pos [10 5]}}
   :parent-of {"n:c" "x"}})

(defn- child [l id] (first (filter #(= id (:id %)) (:children l))))
(defn- edge [l id] (first (filter #(= id (:id %)) (:edges l))))

(test "a graph is manual once a top-level node or box has :pos"
  (fn []
    (assert/equal (manual? graph) true)
    (assert/equal (manual? {:nodes {"a" {:id "a"}} :boxes []}) false)
    (assert/equal (manual? {:nodes {} :boxes [{:name "x" :pos [0 0]}]}) true)))

(test "place puts top-level elements on their cells, contents along"
  (fn []
    (let [p (place layout graph)]
      (assert/deepEqual (select-keys (child p "n:a") [:x :y]) (cell->xy [0 0]))
      (assert/deepEqual (select-keys (child p "b:x") [:x :y]) (cell->xy [10 5]))
      (assert/deepEqual (select-keys (first (:children (child p "b:x"))) [:x :y]) {:x 10 :y 30}
                        "a box's contents keep their place inside it"))))

(test "place lines up elements without :pos in a row under the others"
  (fn []
    (let [p (place layout graph)
          b (child p "n:b")]
      ;; the lowest placed element, box x, ends 80 under its cell's top
      (assert/equal (:x b) (:x (cell->xy [0 0])))
      (assert/ok (>= (:y b) (+ (:y (cell->xy [10 5])) 80 GRID)))
      (assert/equal (mod (:y b) GRID) 0))))

(test "an edge between elements that share no rows or columns bends once, at a right angle"
  (fn []
    (let [p (place layout graph)
          e (edge p "e1")
          [s] (:sections e)]
      ;; a at 40,40 (40×30), c at 250,170 (40×30): wider apart than
      ;; high, so out of a's side, into c's top
      (assert/equal (count (:sections e)) 1)
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 80 :y 55} [{:x 270 :y 55}] {:x 270 :y 170}])
      ;; the label sits on the middle of the longer leg
      (let [lbl (first (:labels e))]
        (assert/equal (+ (:x lbl) (/ (:width lbl) 2)) 175)
        (assert/equal (+ (:y lbl) (/ (:height lbl) 2)) 55)))))

(test "higher apart than wide: out of the bottom, into the side"
  (fn []
    ;; a at 40,40 (to 80,70); t at 140,240 (to 180,340)
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [0 0] [5 10]))
          [s] (:sections (edge p "e"))]
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 60 :y 70} [{:x 60 :y 290}] {:x 140 :y 290}]))))

(test "a self-loop moves with its element; an edge inside a box is left alone"
  (fn []
    (let [p (place layout graph)]
      (assert/deepEqual (:sections (edge p "e0"))
                        [{:startPoint {:x 50 :y 40} :bendPoints [{:x 50 :y 30} {:x 70 :y 30}] :endPoint {:x 70 :y 40}}])
      (assert/deepEqual (:labels (edge p "e0")) [{:text "again" :x 45 :y 20 :width 30 :height 14}]
                        "its label moves along, text and size kept")
      (assert/deepEqual (edge p "e2") (edge layout "e2")))))

(test "the placed layout's size covers every element"
  (fn []
    (let [p (place layout graph)
          b (child p "n:b")]
      (assert/ok (>= (:width p) (+ (:x (cell->xy [10 5])) 100)))
      (assert/ok (>= (:height p) (+ (:y b) 30))))))

(test "snapshot: each top-level element's cell in a layout"
  (fn []
    (assert/deepEqual (snapshot layout) {"n:a" [0 0] "n:b" [3 0] "b:x" [8 0]})))

(test "with-pos gives one element another cell"
  (fn []
    (let [g (with-pos graph "b:x" [2 3])]
      (assert/deepEqual (:pos (get (:boxes-by-name g) "x")) [2 3])
      (assert/deepEqual (:pos (get (:nodes (with-pos graph "n:b" [1 1])) "b")) [1 1]))))

(test "top-at: the top-level element under a point, a node before a box"
  (fn []
    (let [p (place layout graph)]
      (assert/equal (top-at p {:x 45 :y 45}) "n:a")
      ;; inside box x, on its node c: the box is what moves
      (assert/equal (top-at p {:x 255 :y 175}) "b:x")
      (assert/equal (top-at p {:x 330 :y 210}) "b:x")
      (assert/ok (nil? (top-at p {:x 150 :y 150}))))))

(defn- extent
  "[min-x min-y] over a layout's elements, edge points and labels."
  [l]
  (let [pts (concat (map (fn [c] {:x (:x c) :y (:y c)}) (:children l))
                    (mapcat (fn [e] (concat (mapcat (fn [s] (concat [(:startPoint s) (:endPoint s)] (or (:bendPoints s) [])))
                                                    (:sections e))
                                            (map (fn [lb] {:x (:x lb) :y (:y lb)}) (or (:labels e) []))))
                            (:edges l)))]
    [(reduce min (map :x pts)) (reduce min (map :y pts))]))

(test "on cell [0 0] a self-loop and its label stay inside the drawing"
  (fn []
    (let [l {:id "root"
             :children [{:id "n:a" :x 20 :y 40 :width 40 :height 30}]
             :edges [{:id "e0" :container "root" :sources ["n:a"] :targets ["n:a"]
                      :sections [{:startPoint {:x 30 :y 40} :bendPoints [{:x 30 :y 30} {:x 50 :y 30}] :endPoint {:x 50 :y 40}}]
                      :labels [{:text "self" :x 25 :y 14 :width 30 :height 14}]}]
             :width 80 :height 90}
          p (place l {:nodes {"a" {:id "a" :pos [0 0]}} :boxes [] :boxes-by-name {} :parent-of {}})
          [x0 y0] (extent p)]
      (assert/ok (>= x0 0) (str "min x " x0))
      (assert/ok (>= y0 0) (str "min y " y0)))))

(test "cells and pixels map both ways"
  (fn []
    (let [{:keys [x y]} (cell->xy [3 2])]
      (assert/deepEqual (xy->cell x y) [3 2])
      (assert/deepEqual (xy->cell (+ x 9) (- y 9)) [3 2] "the nearest cell")
      (assert/deepEqual (xy->cell -500 -500) [0 0] "never left of or above cell 0"))))

(test "a removed element's :pos doesn't make a comparison manual"
  (fn []
    (assert/equal (manual? {:nodes {"a" {:id "a"} "gone" {:id "gone" :pos [20 0] :diff "removed"}}
                            :boxes [{:name "x" :pos [1 1] :diff "removed"}]})
                  false)
    (assert/equal (manual? {:nodes {"a" {:id "a" :pos [0 0] :diff "modified"}} :boxes []}) true)))

;; ---- edges through the shared range ----------------------------------------

;; a 40×30 node a and a tall 40×100 node t, top-level; box x holding c
(defn- pair-layout [edges & [a-h]]
  {:id "root"
   :children [{:id "n:a" :x 0 :y 0 :width 40 :height (or a-h 30)}
              {:id "n:t" :x 100 :y 0 :width 40 :height 100}
              {:id "b:x" :x 200 :y 0 :width 100 :height 80
               :children [{:id "n:c" :x 10 :y 30 :width 40 :height 30}]}]
   :edges edges :width 320 :height 120})

(defn- pair-graph [a-pos t-pos]
  {:nodes {"a" {:id "a" :pos a-pos} "t" {:id "t" :pos t-pos} "c" {:id "c"}}
   :boxes [{:name "x" :pos [10 5]}]
   :boxes-by-name {"x" {:name "x" :pos [10 5]}}
   :parent-of {"n:c" "x"}})

(defn- e [id s t] {:id id :container "root" :sources [s] :targets [t]
                   :sections [{:startPoint {:x 0 :y 0} :endPoint {:x 1 :y 1}}]})

(defn- ends [l id]
  (let [[s] (:sections (edge l id))] [(:startPoint s) (:endPoint s)]))

(test "elements sharing rows: the edge runs level through the middle of what they share"
  (fn []
    ;; a at 40,40 (to 80,70); t at 240,40 (to 280,140): rows 40–70 shared
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [0 0] [10 0]))]
      (assert/deepEqual (ends p "e") [{:x 80 :y 55} {:x 240 :y 55}]))
    ;; t left of a: the edge still leaves a's facing side
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [10 1] [0 0]))]
      (assert/deepEqual (ends p "e") [{:x 240 :y 75} {:x 80 :y 75}]))))

(test "elements sharing columns: the edge runs plumb"
  (fn []
    ;; a at 40,40 (to 80,70); t at 40,240: columns 40–80 shared
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [0 0] [0 10]))]
      (assert/deepEqual (ends p "e") [{:x 60 :y 70} {:x 60 :y 240}]))))

(test "an edge into a box's node runs level when the rows are shared, whatever the box's size"
  (fn []
    ;; box x at 240,140, its c at 250,170 (to 290,200); a at 40,180 (to 80,210)
    (let [p (place (pair-layout [(e "e" "n:a" "n:c")]) (pair-graph [0 7] [0 20]))]
      (assert/deepEqual (ends p "e") [{:x 80 :y 190} {:x 250 :y 190}]))))

(test "parallel edges share the range evenly, not one line"
  (fn []
    (let [p (place (pair-layout [(e "e1" "n:a" "n:t") (e "e2" "n:t" "n:a")]) (pair-graph [0 0] [10 0]))]
      (assert/deepEqual (ends p "e1") [{:x 80 :y 50} {:x 240 :y 50}])
      (assert/deepEqual (ends p "e2") [{:x 240 :y 60} {:x 80 :y 60}]))))

(test "a sliver of shared rows is no reason to run level"
  (fn []
    ;; a 22 high at 40,40 (to 62); t at 240,60: 2 px shared
    (let [l (pair-layout [(e "e" "n:a" "n:t")] 22)
          p (place l (pair-graph [0 0] [10 1]))
          [a b] (ends p "e")]
      (assert/ok (not= (:y a) (:y b)) "not level"))))

(test "a bend that would cross another element turns the other way"
  (fn []
    ;; a at 40,40 (to 80,70), t 40×100 at 440,240: wider apart than
    ;; high, but a node w at 220,40 (to 260,70) sits where leaving a level
    ;; would run — so out of a's bottom, into t's side
    (let [l (update (pair-layout [(e "e" "n:a" "n:t")]) :children conj {:id "n:w" :x 0 :y 0 :width 40 :height 30})
          g (assoc-in (pair-graph [0 0] [20 10]) [:nodes "w"] {:id "w" :pos [9 0]})
          p (place l g)
          [s] (:sections (edge p "e"))]
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 60 :y 70} [{:x 60 :y 290}] {:x 440 :y 290}]))))

(test "parallel edges that bend nest, without crossing"
  (fn []
    ;; a at 40,40 (to 80,70); t at 440,240: no rows or columns shared
    (let [p (place (pair-layout [(e "e1" "n:a" "n:t") (e "e2" "n:a" "n:t")]) (pair-graph [0 0] [20 10]))
          legs (fn [id] (let [[s] (:sections (edge p id))] [(:startPoint s) (:bendPoints s) (:endPoint s)]))]
      ;; right then down: the higher leg turns down further out
      (assert/deepEqual (legs "e1") [{:x 80 :y 50} [{:x 466.6666666666667 :y 50}] {:x 466.6666666666667 :y 240}])
      (assert/deepEqual (legs "e2") [{:x 80 :y 60} [{:x 453.3333333333333 :y 60}] {:x 453.3333333333333 :y 240}]))))

(test "level from 6 px of shared rows on"
  (fn []
    ;; a from 40,40; t at 240,60: a 26 high shares 6 px, 25 high 5
    (let [level? (fn [h] (let [[a b] (ends (place (pair-layout [(e "e" "n:a" "n:t")] h) (pair-graph [0 0] [10 1])) "e")]
                           (= (:y a) (:y b))))]
      (assert/equal (level? 26) true)
      (assert/equal (level? 25) false))))

(test "overlapping elements: straight, border to border"
  (fn []
    ;; a at 40,40 (to 80,70) inside t's 60,40 (to 100,140) corner
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [0 0] [1 0]))
          [s] (:sections (edge p "e"))]
      (assert/ok (empty? (or (:bendPoints s) []))))))
