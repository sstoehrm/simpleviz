(ns simpleviz.hit-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.hit :refer [client->graph hit-test]]))

(defn scene [items] {:items items :width 500 :height 300})

(def node-a {:kind "node" :id "n:a" :x 100 :y 100 :w 60 :h 30})
(def edge-e {:kind "edge" :id "e0"
             :sections [[{:x 0 :y 200} {:x 300 :y 200}]]})
(def outer-box {:kind "box" :id "b:outer" :x 50 :y 50 :w 300 :h 220 :title-h 28})
(def inner-box {:kind "box" :id "b:inner" :x 80 :y 150 :w 120 :h 80 :title-h 28})

(test "client->graph inverts the view transform"
  (fn []
    (let [p (client->graph {:x 100 :y 50 :k 2} 140 90)]
      (assert/deepEqual p {:x 20 :y 20}))))

(test "node beats edge beats box"
  (fn []
    (let [s (scene [outer-box edge-e node-a])]
      (assert/equal (:id (hit-test s {:x 110 :y 110} 6)) "n:a")
      (assert/equal (:id (hit-test s {:x 250 :y 202} 6)) "e0")
      (assert/equal (:id (hit-test s {:x 60 :y 60} 6)) "b:outer"))))

(test "edge tolerance respected"
  (fn []
    (let [s (scene [edge-e])]
      (assert/equal (:id (hit-test s {:x 150 :y 205} 6)) "e0")
      (assert/ok (nil? (hit-test s {:x 150 :y 205} 3))))))

(test "box interior selects nothing; header and border bands select the box"
  (fn []
    (let [s (scene [outer-box])]
      (assert/equal (:id (hit-test s {:x 200 :y 60} 6)) "b:outer")   ; header strip
      (assert/equal (:id (hit-test s {:x 52 :y 150} 6)) "b:outer")   ; left band
      (assert/equal (:id (hit-test s {:x 348 :y 150} 6)) "b:outer")  ; right band
      (assert/equal (:id (hit-test s {:x 200 :y 268} 6)) "b:outer")  ; bottom band
      (assert/ok (nil? (hit-test s {:x 200 :y 150} 6))))))            ; interior

(test "nested boxes: innermost header wins"
  (fn []
    (let [s (scene [outer-box inner-box])]
      (assert/equal (:id (hit-test s {:x 100 :y 160} 6)) "b:inner")
      (assert/equal (:id (hit-test s {:x 200 :y 60} 6)) "b:outer"))))

(test "innermost box wins when hit zones overlap"
  (fn []
    (let [top {:kind "box" :id "b:top" :x 60 :y 52 :w 120 :h 60 :title-h 28}
          s (scene [outer-box top])]
      (assert/equal (:id (hit-test s {:x 100 :y 60} 6)) "b:top"))))
