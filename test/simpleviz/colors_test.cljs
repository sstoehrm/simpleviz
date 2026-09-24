(ns simpleviz.colors-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.colors :as colors]))

(test "fnv1a is deterministic and unsigned"
  (fn []
    (assert/equal (colors/fnv1a "service") (colors/fnv1a "service"))
    (assert/notEqual (colors/fnv1a "service") (colors/fnv1a "database"))
    (assert/ok (>= (colors/fnv1a "service") 0))))

(def theme {:node-saturation 65 :node-lightness 38 :box-saturation 45 :box-lightness 55
            :box-fill-alpha 0.1 :neutral-node-lightness 40 :neutral-box-lightness 65})

(test "tables have 255 entries in the theme's saturation and lightness"
  (fn []
    (let [t (colors/tables theme)]
      (assert/equal (.-length (:node t)) 255)
      (assert/equal (.-length (:box t)) 255)
      (assert/equal (nth (:node t) 1) "hsl(137.5 65% 38%)")
      (assert/equal (:border (nth (:box t) 1)) "hsl(137.5 45% 55%)")
      (assert/equal (:fill (nth (:box t) 1)) "hsl(137.5 45% 55% / 0.1)"))))

(test "neutral colors and box fill alpha follow the theme"
  (fn []
    (let [t (colors/tables (assoc theme :neutral-node-lightness 70
                                  :neutral-box-lightness 50 :box-fill-alpha 0))]
      (assert/equal (:neutral-node t) "hsl(0 0% 70%)")
      (assert/deepEqual (:neutral-box t) {:border "hsl(0 0% 50%)" :fill "hsl(0 0% 50% / 0)"})
      (assert/equal (:fill (nth (:box t) 1)) "hsl(137.5 45% 55% / 0)"))))

(test "a type keeps its hue across themes"
  (fn []
    (let [a (colors/tables theme)
          b (colors/tables (assoc theme :node-saturation 90 :node-lightness 70))]
      (assert/equal (nth (:node a) 7) "hsl(242.6 65% 38%)")
      (assert/equal (nth (:node b) 7) "hsl(242.6 90% 70%)"))))

(test "assignment is independent of input order"
  (fn []
    (assert/deepEqual (colors/assign-indices ["db" "service" "cache"])
                      (colors/assign-indices ["cache" "db" "service"]))))

(test "empty types are ignored"
  (fn []
    (let [idx (colors/assign-indices ["" "svc" ""])]
      (assert/deepEqual (js/Object.keys idx) ["svc"]))))

(test "up to 255 types all get distinct indices"
  (fn []
    (let [types (mapv (fn [i] (str "type-" i)) (range 255))
          idx (colors/assign-indices types)]
      (assert/equal (.-size (js/Set. (js/Object.values idx))) 255))))

(test "more than 255 types does not hang; extras reuse slots"
  (fn []
    (let [types (mapv (fn [i] (str "type-" i)) (range 300))
          idx (colors/assign-indices types)]
      (assert/equal (.-length (js/Object.keys idx)) 300))))

(test "hash collision probes to the next free slot"
  (fn []
    (let [target (js-mod (colors/fnv1a "alpha") 255)
          other (loop [i 0]
                  (when (< i 1000000)
                    (let [cand (str "t" i)]
                      (if (and (not= cand "alpha")
                               (= (js-mod (colors/fnv1a cand) 255) target))
                        cand
                        (recur (inc i))))))]
      (assert/ok other "no colliding string found")
      (let [idx (colors/assign-indices ["alpha" other])]
        (assert/notEqual (get idx "alpha") (get idx other))))))

