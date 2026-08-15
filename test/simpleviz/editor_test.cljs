(ns simpleviz.editor-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.editor :refer [target set-attr-op del-attr-op
                                      value->edn-text scalar? blur-op
                                      delete-op direction-op]]))

(test "target maps selection payloads to op targets"
  (fn []
    (assert/deepEqual (target {:kind "node" :elk-id "n:web"})
                      {:section "nodes" :id "web"})
    (assert/deepEqual (target {:kind "box" :elk-id "b:grp"})
                      {:section "boxes" :id "grp"})
    (assert/deepEqual (target {:kind "edge" :source "a" :target "b"})
                      {:section "edges" :id ["a" "b"]})))

(test "set-attr-op carries EDN text and fallback flag"
  (fn []
    (assert/deepEqual (set-attr-op {:section "nodes" :id "web"} "name" "\"X\"" true)
                      {:op "set-attr" :section "nodes" :id "web"
                       :attr "name" :value "\"X\"" :fallback true})))

(test "del-attr-op shape"
  (fn []
    (assert/deepEqual (del-attr-op {:section "nodes" :id "web"} "lang")
                      {:op "del-attr" :section "nodes" :id "web" :attr "lang"})))

(test "value->edn-text passes strings through raw"
  (fn []
    (assert/equal (value->edn-text "hello") "hello")
    (assert/equal (value->edn-text "with \"quotes\"") "with \"quotes\"")))

(test "value->edn-text renders keyword-keyed maps with a leading colon"
  (fn []
    (assert/equal (value->edn-text {:lang "clojure"}) "{:lang \"clojure\"}")
    (assert/equal (value->edn-text 42) "42")
    (assert/equal (value->edn-text true) "true")))

(test "value->edn-text recurses through a nested vector of maps"
  (fn []
    (assert/equal (value->edn-text [{:a 1} {:b "x"}])
                  "[{:a 1} {:b \"x\"}]")))

(test "scalar? is false for collections, true otherwise"
  (fn []
    (assert/equal (scalar? "x") true)
    (assert/equal (scalar? 1) true)
    (assert/equal (scalar? nil) true)
    (assert/equal (scalar? [1 2]) false)
    (assert/equal (scalar? {:a 1}) false)))

(test "blur-op is nil when nothing is being edited (Escape/Enter already cleared it)"
  (fn []
    (assert/ok (nil? (blur-op nil "name" {:section "nodes" :id "web"} true)))))

(test "blur-op is nil when a DIFFERENT attr field is the one being edited"
  (fn []
    (assert/ok (nil? (blur-op {:attr "lang" :text "x"} "name"
                              {:section "nodes" :id "web"} true)))))

(test "blur-op posts set-attr-op when this attr is still the one being edited"
  (fn []
    (assert/deepEqual (blur-op {:attr "name" :text "\"X\""} "name"
                               {:section "nodes" :id "web"} true)
                      [{:op "set-attr" :section "nodes" :id "web"
                        :attr "name" :value "\"X\"" :fallback true}])))

(test "delete-op and direction-op shapes"
  (fn []
    (assert/deepEqual (delete-op {:section "nodes" :id "web"})
                      {:op "delete" :section "nodes" :id "web"})
    (assert/deepEqual (direction-op {:section "edges" :id ["a" "b"]} "<->")
                      {:op "set-direction" :edge ["a" "b"] :direction "<->"})))
