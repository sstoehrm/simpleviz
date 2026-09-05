(ns simpleviz.editor-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.editor :refer [target set-attr-op del-attr-op
                                      value->edn-text scalar?
                                      delete-op direction-op pick-ops
                                      add-node-ops add-connected-ops wrap-in-box-ops
                                      edit-body rename-op blur-text retarget-end
                                      chord-action chord-group? chord-for chord-hint]]))

(test "target maps selection payloads to op targets"
  (fn []
    (assert/deepEqual (target {:kind "node" :elk-id "n:web"})
                      {:section "nodes" :id "web"})
    (assert/deepEqual (target {:kind "box" :elk-id "b:grp"})
                      {:section "boxes" :id "grp"})
    (assert/deepEqual (target {:kind "edge" :source "a" :target "b"})
                      {:section "edges" :id ["a" "b"]})))

(test "target keys an edge by the file's pair order, not the displayed one"
  (fn []
    ;; a :<- edge is displayed flipped (source b, target a) but its key in
    ;; the file is still [a b], which is what the server looks up
    (assert/deepEqual (target {:kind "edge" :source "b" :target "a"
                               :attrs {:direction "<-" :nodes ["a" "b"]}})
                      {:section "edges" :id ["a" "b"]})))

(test "retarget-end names the file-key end behind the displayed one"
  (fn []
    (assert/equal (retarget-end {:attrs {:direction "->"}} "source") "source")
    (assert/equal (retarget-end {:attrs {:direction "<-"}} "source") "target")
    (assert/equal (retarget-end {:attrs {:direction "<-"}} "target") "source")))

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




(test "delete-op and direction-op shapes"
  (fn []
    (assert/deepEqual (delete-op {:section "nodes" :id "web"})
                      {:op "delete" :section "nodes" :id "web"})
    (assert/deepEqual (direction-op {:section "edges" :id ["a" "b"]} "<->")
                      {:op "set-direction" :edge ["a" "b"] :direction "<->"})))

(test "pick-ops builds ops only for valid targets"
  (fn []
    ;; retarget: any node or box works
    (assert/deepEqual (pick-ops {:mode "retarget" :edge ["a" "b"] :end "target"}
                                {:kind "node" :id "n:c"})
                      [{:op "retarget-edge" :edge ["a" "b"] :end "target" :to "c"}])
    ;; into-box: only boxes are valid
    (assert/equal (pick-ops {:mode "into-box" :member "web"} {:kind "node" :id "n:x"}) nil)
    (assert/deepEqual (pick-ops {:mode "into-box" :member "web"} {:kind "box" :id "b:grp"})
                      [{:op "box-add" :box "grp" :member "web"}])
    ;; box-take node: only nodes
    (assert/deepEqual (pick-ops {:mode "box-take" :box "grp" :want "node"}
                                {:kind "node" :id "n:web"})
                      [{:op "box-add" :box "grp" :member "web"}])
    (assert/equal (pick-ops {:mode "box-take" :box "grp" :want "box"}
                            {:kind "box" :id "b:grp"}) nil)   ; itself: invalid
    (assert/deepEqual (pick-ops {:mode "box-take" :box "grp" :want "box"}
                                {:kind "box" :id "b:other"})
                      [{:op "box-add" :box "grp" :member "other"}])))

(test "pick-ops ignores a collapse-button hit — no valid target, keep picking"
  (fn []
    (assert/equal (pick-ops {:mode "retarget" :edge ["a" "b"] :end "source"}
                            {:kind "collapse-button" :box-id "b:grp"})
                  nil)
    (assert/equal (pick-ops {:mode "into-box" :member "web"}
                            {:kind "collapse-button" :box-id "b:grp"})
                  nil)
    (assert/equal (pick-ops {:mode "box-take" :box "grp" :want "node"}
                            {:kind "collapse-button" :box-id "b:grp"})
                  nil)))

(test "add-connected-ops and wrap-in-box-ops"
  (fn []
    (assert/deepEqual (add-connected-ops "api" "db")
                      [{:op "add-node" :id "db"}
                       {:op "add-edge" :from "api" :to "db" :direction "->"}])
    ;; one atomic server op: the member leaves its old parent for the new box
    (assert/deepEqual (wrap-in-box-ops "api" "backend")
                      [{:op "wrap" :box "backend" :member "api"}])))

(test "edit-body routes ops to the chosen file"
  (fn []
    (assert/deepEqual (edit-body "old" [{:op "undo"}])
                      {:file "old" :ops [{:op "undo"}]})))

(test "add-node-ops creates a single free-standing add-node op"
  (fn []
    (assert/deepEqual (add-node-ops "cache")
                      [{:op "add-node" :id "cache"}])))

(test "pick-ops connect mode wires an edge to a node or box, never itself"
  (fn []
    (assert/deepEqual (pick-ops {:mode "connect" :from "api"} {:kind "node" :id "n:db"})
                      [{:op "add-edge" :from "api" :to "db" :direction "->"}])
    (assert/deepEqual (pick-ops {:mode "connect" :from "api"} {:kind "box" :id "b:grp"})
                      [{:op "add-edge" :from "api" :to "grp" :direction "->"}])
    (assert/ok (nil? (pick-ops {:mode "connect" :from "api"} {:kind "node" :id "n:api"})))
    (assert/ok (nil? (pick-ops {:mode "connect" :from "api"} {:kind "edge" :id "e0"})))))

(test "rename-op carries the target and the new id"
  (fn []
    (assert/deepEqual (rename-op {:section "nodes" :id "web"} "  gateway ")
                      {:section "nodes" :id "web" :op "rename" :to "gateway"})))

(test "blur-text yields the pending text only for the field still being edited"
  (fn []
    (assert/ok (nil? (blur-text nil "$id")))
    (assert/ok (nil? (blur-text {:attr "name" :text "x"} "$id")))
    (assert/equal (blur-text {:attr "$id" :text " gw "} "$id") " gw ")))

(test "chord-action resolves a two-key chord for the selection kind"
  (fn []
    (assert/equal (chord-action "node" "d" "d") "delete")
    (assert/equal (chord-action "edge" "d" "d") "delete")
    (assert/deepEqual (chord-action "edge" "e" "3") ["direction" "<->"])
    (assert/deepEqual (chord-action "edge" "c" "t") ["retarget" "target"])
    (assert/equal (chord-action "node" "a" "b") "add-to-box")
    (assert/equal (chord-action "box" "a" "b") "add-box-member")
    (assert/equal (chord-action "box" "a" "n") "add-node-member")
    (assert/equal (chord-action nil "n" "n") "new-node")
    (assert/equal (chord-action "node" "n" "n") "new-connected-node")
    (assert/equal (chord-action "box" "n" "b") "new-box")
    (assert/equal (chord-action "box" "r" "r") "rename")
    ;; not available for this kind, or no such chord
    (assert/ok (nil? (chord-action "edge" "a" "e")))
    (assert/ok (nil? (chord-action "node" "e" "1")))
    (assert/ok (nil? (chord-action nil "d" "d")))
    (assert/ok (nil? (chord-action "node" "z" "z")))))

(test "chord-group? knows the first keys"
  (fn []
    (assert/ok (chord-group? "d"))
    (assert/ok (chord-group? "a"))
    (assert/ok (not (chord-group? "z")))))

(test "chord-for finds the chord behind an action, for the toolbar hints"
  (fn []
    (assert/equal (chord-for "node" "delete") "d d")
    (assert/equal (chord-for "edge" ["direction" "<-"]) "e 2")
    (assert/equal (chord-for "box" "add-box-member") "a b")
    (assert/ok (nil? (chord-for "edge" "add-to-box")))))

(test "chord-hint lists the completions of a pending group for the selection"
  (fn []
    (assert/equal (chord-hint "edge" "c") "c … s change source · t change target")
    (assert/equal (chord-hint "box" "a") "a … e add edge · b add box · n add node")
    (assert/equal (chord-hint nil "n") "n … n new node")
    (assert/equal (chord-hint "edge" "a") "a … nothing for an edge")))
