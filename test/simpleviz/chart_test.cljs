(ns simpleviz.chart-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.chart :refer [compact runs ticks view]]))

(test "ticks are round steps covering the values"
  (fn []
    (assert/deepEqual (ticks 0 950 5) [0 200 400 600 800 1000])
    (assert/deepEqual (ticks -3 7 5) [-4 -2 0 2 4 6 8])
    (assert/deepEqual (ticks 0.1 0.35 5) [0.1 0.15 0.2 0.25 0.3 0.35])
    (assert/deepEqual (ticks 5 5 5) [0 2 4 6 8 10])))

(test "tick numbers are compact past ten thousand"
  (fn []
    (assert/equal (compact 950) "950")
    (assert/equal (compact 12000) "12K")
    (assert/equal (compact 3400000) "3.4M")
    (assert/equal (compact 0.30000000000000004) "0.3")))

(test "a gap in the values breaks a line"
  (fn []
    (assert/deepEqual (runs [1 nil 3 4 nil] [0 1 2 3 4]) [[0] [2 3]])
    (assert/deepEqual (runs [1 2] [1 0]) [[1 0]])))

(test "a chart has a head with its title or legend, and an svg"
  (fn []
    (let [grid [[{:label "a"} {:num 1} {:num 2}] [{:label "b"} {:num 3} {:num nil}]]
          one (view {:spec {:kind "bar" :x 0 :y [1]} :names ["item" "x" "y"] :grid grid})
          two (view {:spec {:kind "line" :x 0 :y [1 2] :title "T"} :names ["item" "x" "y"] :grid grid})]
      (assert/deepEqual (nth one 2) [:div {:class "chart-head"} [:span {:class "chart-title"} "x"]])
      (assert/equal (count (nth two 2)) 5 "title and two keys")
      (assert/equal (first (nth two 3)) :svg))))

(test "a record without its x is left out; the others keep a numeric axis"
  (fn []
    (let [grid [[{:num 120 :label "120"} {:num 3.5}] [{:num nil :label ""} {:num nil}] [{:num 80 :label "80"} {:num 4.2}]]
          svg (nth (view {:spec {:kind "scatter" :x 0 :y [1]} :names ["units" "price"] :grid grid}) 3)
          dots (rest (last svg))]
      (assert/equal (count dots) 2)
      (assert/deepEqual (mapv (fn [t] (last t)) (drop 2 (nth svg 3))) ["80" "90" "100" "110" "120"]))))

(test "no rows draw an empty chart; the most rows the server lets through draw too"
  (fn []
    (assert/equal (first (nth (view {:spec {:kind "bar" :x 0 :y [1]} :names ["a" "b"] :grid []}) 3)) :svg)
    (let [grid (mapv (fn [i] [{:num i :label (str i)} {:num (* i i)}]) (range 20000))]
      (doseq [kind ["bar" "line" "scatter"]]
        (assert/equal (first (nth (view {:spec {:kind kind :x 0 :y [1]} :names ["a" "b"] :grid grid}) 3)) :svg)))))
