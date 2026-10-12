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

(test "an edge between top-level elements runs straight, border to border"
  (fn []
    (let [p (place layout graph)
          e (edge p "e1")
          [s] (:sections e)]
      (assert/equal (count (:sections e)) 1)
      (assert/ok (empty? (or (:bendPoints s) [])))
      ;; a sits at 40,40 (40×30): the line leaves it on its border
      (assert/ok (or (= 80 (:x (:startPoint s))) (= 70 (:y (:startPoint s)))))
      ;; c is at 250,170 (40×30) absolute: the line ends on its border
      (let [{:keys [x y]} (:endPoint s)]
        (assert/ok (or (= x 250) (= y 170)) (str "ends on c's border, at " x "," y)))
      ;; the label sits on the middle of the line
      (let [lbl (first (:labels e))
            mx (/ (+ (:x (:startPoint s)) (:x (:endPoint s))) 2)
            my (/ (+ (:y (:startPoint s)) (:y (:endPoint s))) 2)]
        (assert/equal (+ (:x lbl) (/ (:width lbl) 2)) mx)
        (assert/equal (+ (:y lbl) (/ (:height lbl) 2)) my)))))

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
