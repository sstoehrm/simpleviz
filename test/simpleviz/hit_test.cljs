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

(def labeled-edge {:kind "edge" :id "e1"
                   :sections [[{:x 0 :y 300} {:x 300 :y 300}]]})
(def e1-label {:kind "edge-label" :id "e1-label" :edge-id "e1"
               :x 120 :y 280 :w 60 :h 14})

(test "labeled edge selects via its label, not its line"
  (fn []
    (let [s (scene [labeled-edge e1-label])]
      (assert/equal (:id (hit-test s {:x 150 :y 287} 6)) "e1")
      (assert/equal (:kind (hit-test s {:x 150 :y 287} 6)) "edge")
      (assert/ok (nil? (hit-test s {:x 150 :y 301} 6))))))

(test "label hit zone has a small padding"
  (fn []
    (let [s (scene [labeled-edge e1-label])]
      (assert/equal (:id (hit-test s {:x 118 :y 278} 6)) "e1"))))

(test "unlabeled edge still selects via its line"
  (fn []
    (let [s (scene [edge-e])]
      (assert/equal (:id (hit-test s {:x 150 :y 202} 6)) "e0"))))

(test "hide button hit when zoomed in; header when zoomed out"
  (fn []
    (let [s (scene [outer-box])
          btn-p {:x 330 :y 60}]           ; button rect: x 328-343, y 57-72
      (let [it (hit-test s btn-p 6 1.0)]
        (assert/equal (:kind it) "hide-button")
        (assert/equal (:box-id it) "b:outer"))
      ;; zoomed far out: button not drawn, so the same point is a header hit
      (assert/equal (:kind (hit-test s btn-p 40 0.2)) "box"))))
