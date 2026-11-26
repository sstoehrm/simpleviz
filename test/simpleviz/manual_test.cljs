(ns simpleviz.manual-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.manual :refer [GRID manual? place snapshot element-at frame pins with-pos with-cells cell->xy xy->cell]]))

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

(test "element-at: the element under a point, the innermost first, a node before a box"
  (fn []
    (let [p (place layout graph)]
      (assert/equal (element-at p {:x 45 :y 45}) "n:a")
      ;; inside box x, on its node c: the node is what moves
      (assert/equal (element-at p {:x 255 :y 175}) "n:c")
      ;; on the box, off its node: the box
      (assert/equal (element-at p {:x 330 :y 210}) "b:x")
      (assert/ok (nil? (element-at p {:x 150 :y 150}))))))

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

(test "higher apart than wide: out of the bottom, into the side"
  (fn []
    ;; a at 40,40 (to 80,70); t at 140,240 (to 180,340)
    (let [p (place (pair-layout [(e "e" "n:a" "n:t")]) (pair-graph [0 0] [5 10]))
          [s] (:sections (edge p "e"))]
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 60 :y 70} [{:x 60 :y 290}] {:x 140 :y 290}]))))

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

(test "a level edge that would run through an element bends round it when it can"
  (fn []
    ;; a at 40,40 (to 80,70), t at 440,40 (to 480,140) share rows 40–70,
    ;; but w at 240,40 sits on them: out of a's bottom, into t's side
    (let [l (update (pair-layout [(e "e" "n:a" "n:t")]) :children conj {:id "n:w" :x 0 :y 0 :width 40 :height 30})
          g (assoc-in (pair-graph [0 0] [20 0]) [:nodes "w"] {:id "w" :pos [10 0]})
          [s] (:sections (edge (place l g) "e"))]
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 60 :y 70} [{:x 60 :y 90}] {:x 440 :y 90}]))))

;; ---- inside boxes ----------------------------------------------------------

;; box x (padding 14, 40 on top) holding c, d and the box y, which holds f;
;; e3 from c to d, e4 a self-loop on c, both inside x; e5 from a to f
(def nested-layout
  {:id "root"
   :children [{:id "n:a" :x 20 :y 20 :width 40 :height 30}
              {:id "b:x" :x 200 :y 20 :width 300 :height 200
               :pad {:top 40 :left 14 :bottom 14 :right 14}
               :children [{:id "n:c" :x 14 :y 40 :width 40 :height 30}
                          {:id "n:d" :x 100 :y 40 :width 40 :height 30}
                          {:id "b:y" :x 14 :y 100 :width 68 :height 84
                           :pad {:top 40 :left 14 :bottom 14 :right 14}
                           :children [{:id "n:f" :x 14 :y 40 :width 40 :height 30}]}]}]
   :edges [{:id "e3" :container "b:x" :sources ["n:c"] :targets ["n:d"]
            :sections [{:startPoint {:x 54 :y 55} :endPoint {:x 100 :y 55}}]}
           {:id "e4" :container "b:x" :sources ["n:c"] :targets ["n:c"]
            :sections [{:startPoint {:x 20 :y 40} :bendPoints [{:x 20 :y 30} {:x 40 :y 30}] :endPoint {:x 40 :y 40}}]}
           {:id "e5" :container "root" :sources ["n:a"] :targets ["n:f"]
            :sections [{:startPoint {:x 60 :y 35} :endPoint {:x 228 :y 175}}]}]
   :width 520 :height 240})

(defn- nested-graph [cells]
  (let [pos (fn [id] (get cells id))
        el (fn [m id] (cond-> m (some? (pos id)) (assoc :pos (pos id))))]
    {:nodes {"a" (el {:id "a"} "n:a") "c" (el {:id "c"} "n:c") "d" (el {:id "d"} "n:d") "f" (el {:id "f"} "n:f")}
     :boxes [(el {:name "x"} "b:x") (el {:name "y"} "b:y")]
     :boxes-by-name {"x" (el {:name "x"} "b:x") "y" (el {:name "y"} "b:y")}
     :parent-of {"n:c" "x" "n:d" "x" "b:y" "x" "n:f" "y"}}))

(defn- kid [l & ids] (reduce child l ids))

(test "a box whose contents have no :pos keeps its layered inside"
  (fn []
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0]}))]
      (assert/deepEqual (kid p "b:x" "n:d") (kid nested-layout "b:x" "n:d"))
      (assert/deepEqual (select-keys (kid p "b:x") [:width :height]) {:width 300 :height 200})
      (assert/deepEqual (edge p "e3") (edge nested-layout "e3") "its inside edges as laid out"))))

(test "inside a box, :pos is a cell counted from the box's grid-aligned inside"
  (fn []
    ;; x's inside starts 20 in (14 rounded up to the grid), 40 down
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [5 2] "b:y" [0 4]}))]
      (assert/deepEqual (select-keys (kid p "b:x" "n:c") [:x :y]) {:x 20 :y 40})
      (assert/deepEqual (select-keys (kid p "b:x" "n:d") [:x :y]) {:x 120 :y 80})
      (assert/deepEqual (select-keys (kid p "b:x" "b:y") [:x :y]) {:x 20 :y 120})
      (assert/deepEqual (select-keys (kid p "b:x" "b:y" "n:f") [:x :y]) {:x 14 :y 40}
                        "y's own inside is still layered")
      ;; on the canvas's grid: x sits on a cell, so its contents do
      (let [abs-x (+ (:x (kid p "b:x")) (:x (kid p "b:x" "n:d")))]
        (assert/equal (mod abs-x GRID) 0)))))

(test "a box placed inside grows or shrinks to what it holds"
  (fn []
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [5 2] "b:y" [0 4]}))
          x (kid p "b:x")]
      ;; d ends at 160, y at 204: plus the padding, 14 right and bottom
      (assert/deepEqual (select-keys x [:width :height]) {:width 174 :height 218}))))

(test "a box never shrinks under the size its text needs"
  (fn []
    (let [l (update-in nested-layout [:children 1] assoc :min {:width 400 :height 300})
          p (place l (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [1 0] "b:y" [0 4]}))]
      (assert/deepEqual (select-keys (kid p "b:x") [:width :height]) {:width 400 :height 300}))))

(test "inside a placed box, its contents without :pos wait in a row under the rest"
  (fn []
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0]}))
          d (kid p "b:x" "n:d")]
      (assert/ok (>= (:y d) (+ 40 30 GRID)))
      (assert/equal (mod (:y d) GRID) 0))))

(test "edges inside a placed box are routed like those between top-level elements"
  (fn []
    ;; x at 240,40: c at 260,80 (to 300,110), d at 360,120 (to 400,150)
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [5 2] "b:y" [0 4]}))
          e3 (edge p "e3")
          [s] (:sections e3)]
      (assert/equal (:container e3) "root" "routed in the root's coordinates")
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 300 :y 95} [{:x 380 :y 95}] {:x 380 :y 120}]))))

(test "a self-loop inside a placed box moves with its node"
  (fn []
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [1 1] "n:d" [5 2] "b:y" [0 4]}))
          e4 (edge p "e4")]
      ;; c went from 14,40 to 40,60 inside x
      (assert/equal (:container e4) "b:x")
      (assert/deepEqual (:sections e4)
                        [{:startPoint {:x 46 :y 60} :bendPoints [{:x 46 :y 50} {:x 66 :y 50}] :endPoint {:x 66 :y 60}}]))))

(test "an edge into a nested box's node is routed to where it is now, round what's in the way"
  (fn []
    ;; y at 260,160 inside x; its f at 274,200 (to 314,230); a at 40,40.
    ;; Into f's top would cross c (260,80 to 300,110): into its side
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [5 2] "b:y" [0 4]}))
          [s] (:sections (edge p "e5"))]
      (assert/deepEqual [(:startPoint s) (:bendPoints s) (:endPoint s)]
                        [{:x 60 :y 70} [{:x 60 :y 215}] {:x 274 :y 215}]))))

(test "frame: an element's cell and the origin its cells count from"
  (fn []
    (let [p (place nested-layout (nested-graph {"n:a" [0 0] "b:x" [10 0] "n:c" [0 0] "n:d" [5 2] "b:y" [0 4]}))]
      (assert/deepEqual (frame p "n:a") {:origin {:x 40 :y 40} :at {:x 40 :y 40} :cell [0 0]})
      (assert/deepEqual (frame p "n:d") {:origin {:x 260 :y 80} :at {:x 360 :y 120} :cell [5 2]}))))

(test "pins: the cells a drag inside a box writes with it, so nothing else jumps"
  (fn []
    (let [g (nested-graph {"n:a" [0 0] "b:x" [10 0]})
          p (place nested-layout g)]
      ;; x's inside is layered: all it holds gets its cell there
      (assert/deepEqual (pins p g "n:d") {"n:c" [0 0] "n:d" [4 0] "b:y" [0 3]})
      ;; deeper: y's inside too
      (assert/deepEqual (pins p g "n:f") {"n:c" [0 0] "n:d" [4 0] "b:y" [0 3] "n:f" [0 0]})
      ;; top level: nothing more
      (assert/deepEqual (pins p g "n:a") {})
      ;; a box in the row of the unplaced gets its cell first
      (let [g2 (nested-graph {"n:a" [0 0]})
            p2 (place nested-layout g2)]
        (assert/deepEqual (get (pins p2 g2 "n:c") "b:x") (:cell (frame p2 "b:x")))))))

(test "with-cells gives several elements their cells"
  (fn []
    (let [g (with-cells (nested-graph {}) {"n:c" [1 2] "b:y" [3 4]})]
      (assert/deepEqual (get-in g [:nodes "c" :pos]) [1 2])
      (assert/deepEqual (get-in g [:boxes-by-name "y" :pos]) [3 4]))))
