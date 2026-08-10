(ns simpleviz.format-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.format :refer [value->hiccup]]))

(test "strings and scalars render plain"
  (fn []
    (assert/equal (value->hiccup "clojure") "clojure")
    (assert/equal (value->hiccup 3) "3")
    (assert/equal (value->hiccup true) "true")
    (assert/equal (value->hiccup nil) "—")))

(test "a vector becomes a bullet list"
  (fn []
    (assert/deepEqual (value->hiccup ["active" "passive"])
                      [:ul {:class "dd-list"}
                       [:li {:key "0"} "active"]
                       [:li {:key "1"} "passive"]])))

(test "a map becomes a list enumerated by its keys"
  (fn []
    (assert/deepEqual (value->hiccup {:cpu "500m" :mem "1Gi"})
                      [:ol {:class "dd-map"}
                       [:li {:key "cpu"} [:span {:class "dd-map-key"} "cpu:"] " " "500m"]
                       [:li {:key "mem"} [:span {:class "dd-map-key"} "mem:"] " " "1Gi"]])))

(test "nesting recurses"
  (fn []
    (assert/deepEqual (value->hiccup {:tags ["a" "b"]})
                      [:ol {:class "dd-map"}
                       [:li {:key "tags"}
                        [:span {:class "dd-map-key"} "tags:"] " "
                        [:ul {:class "dd-list"}
                         [:li {:key "0"} "a"]
                         [:li {:key "1"} "b"]]]])))
