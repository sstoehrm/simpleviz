(ns simpleviz.colors-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.colors :as colors]))

(test "fnv1a is deterministic and unsigned"
  (fn []
    (assert/equal (colors/fnv1a "service") (colors/fnv1a "service"))
    (assert/notEqual (colors/fnv1a "service") (colors/fnv1a "database"))
    (assert/ok (>= (colors/fnv1a "service") 0))))

(test "tables have 255 entries"
  (fn []
    (assert/equal (.-length colors/NODE-TABLE) 255)
    (assert/equal (.-length colors/BOX-TABLE) 255)
    (assert/match (nth colors/NODE-TABLE 0) (js/RegExp. "^hsl\\("))
    (assert/match (:border (nth colors/BOX-TABLE 0)) (js/RegExp. "^hsl\\("))
    (assert/match (:fill (nth colors/BOX-TABLE 0)) (js/RegExp. "/ 0\\.1\\)$"))))

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

(test "color-map maps types to table entries"
  (fn []
    (let [m (colors/color-map ["svc"] colors/NODE-TABLE)]
      (assert/match (get m "svc") (js/RegExp. "^hsl\\(")))))
