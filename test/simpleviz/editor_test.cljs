(ns simpleviz.editor-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.editor :refer [target set-attr-op del-attr-op
                                      value->edn-text scalar?
                                      delete-op direction-op pick-ops
                                      add-node-ops add-connected-ops wrap-in-box-ops
                                      edit-body create-body rename-op blur-text retarget-end
                                      chord-action chord-group? chord-for chord-hint
                                      add-node-in-box-ops box-remove-op
                                      name->id derived-id named-edge-ops creation-ops parse-entry
                                      resolve-ref parse-nav nav-query follow-url crumb-url ref-of banner-visible?
                                      theme-menu set-theme-op top-box-of]]))

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

(test "add-connected-ops and wrap-in-box-ops name the new element"
  (fn []
    (assert/deepEqual (add-connected-ops "api" "db" "DB")
                      [{:op "add-node" :id "db"}
                       {:op "set-attr" :section "nodes" :id "db" :attr "name" :value "\"DB\"" :fallback false}
                       {:op "add-edge" :from "api" :to "db" :direction "->"}])
    ;; one atomic server op: the member leaves its old parent for the new box
    (assert/deepEqual (wrap-in-box-ops "api" "backend" "Backend")
                      [{:op "wrap" :box "backend" :member "api"}
                       {:op "set-attr" :section "boxes" :id "backend" :attr "name" :value "\"Backend\"" :fallback false}])))

(test "edit-body routes ops to the chosen file"
  (fn []
    (assert/deepEqual (edit-body "old" [{:op "undo"}])
                      {:file "old" :ops [{:op "undo"}]})))

(test "create-body names the followed file and the side being edited"
  (fn []
    (assert/deepEqual (create-body "new" "sub/api.edn")
                      {:file "new" :path "sub/api.edn"})))

(test "add-node-ops creates a free-standing node and names it"
  (fn []
    (assert/deepEqual (add-node-ops "cache" "Cache")
                      [{:op "add-node" :id "cache"}
                       {:op "set-attr" :section "nodes" :id "cache" :attr "name" :value "\"Cache\"" :fallback false}])))

(test "creation-ops: what the toolbar prompt submits, or nil to keep it open"
  (fn []
    (let [node (creation-ops {:for "node" :text " Web Server "} nil)]
      (assert/deepEqual node {:ops (add-node-ops "web-server" "Web Server") :focus "n:web-server"}))
    ;; a numeric-looking name is still text
    (assert/deepEqual (:ops (creation-ops {:for "node" :text "2024"} nil))
                      [{:op "add-node" :id "2024"}
                       {:op "set-attr" :section "nodes" :id "2024" :attr "name" :value "\"2024\"" :fallback false}])
    (assert/deepEqual (creation-ops {:for "connect" :text "DB"} {:section "nodes" :id "api"})
                      {:ops (add-connected-ops "api" "db" "DB") :focus "n:db"})
    (assert/deepEqual (creation-ops {:for "inbox" :text "DB"} {:section "boxes" :id "grp"})
                      {:ops (add-node-in-box-ops "grp" "db" "DB") :focus "n:db"})
    (assert/deepEqual (creation-ops {:for "newbox" :text "Back end"} {:section "nodes" :id "api"})
                      {:ops (wrap-in-box-ops "api" "back-end" "Back end") :focus "b:back-end"})
    ;; nothing usable in the name: the prompt stays open
    (assert/ok (nil? (creation-ops {:for "node" :text "((("} nil)))
    (assert/ok (nil? (creation-ops {:for "connect" :text ""} {:section "nodes" :id "api"})))
    ;; an edge prompt carries the pick's ops; an empty name creates it unnamed
    (let [edge [{:op "add-edge" :from "api" :to "db" :direction "->"}]]
      (assert/deepEqual (creation-ops {:for "edge" :ops edge :text "Calls"} {:section "nodes" :id "api"})
                        {:ops (named-edge-ops edge "Calls") :focus nil})
      (assert/deepEqual (creation-ops {:for "edge" :ops edge :text ""} nil)
                        {:ops edge :focus nil}))))

(test "parse-entry splits a prompt into name and type at the first ::"
  (fn []
    (assert/deepEqual (parse-entry "Web Server") {:name "Web Server" :type nil})
    (assert/deepEqual (parse-entry " Web Server :: service ") {:name "Web Server" :type "service"})
    (assert/deepEqual (parse-entry "a::b::c") {:name "a" :type "b::c"})
    ;; an empty type is no type; an empty name is still no name
    (assert/deepEqual (parse-entry "Web::") {:name "Web" :type nil})
    (assert/deepEqual (parse-entry "::service") {:name "" :type "service"})))

(test "creation-ops with name::type also sets the type"
  (fn []
    (assert/deepEqual (creation-ops {:for "node" :text "Web Server::frontend"} nil)
                      {:ops [{:op "add-node" :id "web-server"}
                             {:op "set-attr" :section "nodes" :id "web-server" :attr "name" :value "\"Web Server\"" :fallback false}
                             {:op "set-attr" :section "nodes" :id "web-server" :attr "type" :value "\"frontend\"" :fallback false}]
                       :focus "n:web-server"})
    (assert/deepEqual (:ops (creation-ops {:for "newbox" :text "Backend :: zone"} {:section "nodes" :id "api"}))
                      [{:op "wrap" :box "backend" :member "api"}
                       {:op "set-attr" :section "boxes" :id "backend" :attr "name" :value "\"Backend\"" :fallback false}
                       {:op "set-attr" :section "boxes" :id "backend" :attr "type" :value "\"zone\"" :fallback false}])
    (assert/deepEqual (:ops (creation-ops {:for "inbox" :text "DB::database"} {:section "boxes" :id "grp"}))
                      [{:op "add-node" :id "db"}
                       {:op "set-attr" :section "nodes" :id "db" :attr "name" :value "\"DB\"" :fallback false}
                       {:op "box-add" :box "grp" :member "db"}
                       {:op "set-attr" :section "nodes" :id "db" :attr "type" :value "\"database\"" :fallback false}])
    ;; a type alone does not make a node: no name, no id
    (assert/ok (nil? (creation-ops {:for "node" :text "::service"} nil)))
    ;; an edge may carry only a type
    (let [edge [{:op "add-edge" :from "api" :to "db" :direction "->"}]]
      (assert/deepEqual (:ops (creation-ops {:for "edge" :ops edge :text "Calls::http"} nil))
                        [{:op "add-edge" :from "api" :to "db" :direction "->"}
                         {:op "set-attr" :section "edges" :id ["api" "db"] :attr "name" :value "\"Calls\"" :fallback false}
                         {:op "set-attr" :section "edges" :id ["api" "db"] :attr "type" :value "\"http\"" :fallback false}])
      (assert/deepEqual (:ops (creation-ops {:for "edge" :ops edge :text "::http"} nil))
                        [{:op "add-edge" :from "api" :to "db" :direction "->"}
                         {:op "set-attr" :section "edges" :id ["api" "db"] :attr "type" :value "\"http\"" :fallback false}]))))

(test "named-edge-ops appends the edge name to an add-edge op, unless blank"
  (fn []
    (let [edge [{:op "add-edge" :from "api" :to "db" :direction "->"}]]
      (assert/deepEqual (named-edge-ops edge "Calls")
                        [{:op "add-edge" :from "api" :to "db" :direction "->"}
                         {:op "set-attr" :section "edges" :id ["api" "db"] :attr "name" :value "\"Calls\"" :fallback false}])
      (assert/deepEqual (named-edge-ops edge "  ") edge))))

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

(test "name->id lowercases and dashes out illegal id characters"
  (fn []
    (assert/equal (name->id "Web Server") "web-server")
    (assert/equal (name->id "  Web   Server (v2) ") "web-server-v2")
    ;; characters the server accepts in a keyword survive untouched
    (assert/equal (name->id "api.v1/Users?") "api.v1/users?")
    ;; a legal dash next to generated ones still collapses to one
    (assert/equal (name->id "API - Gateway") "api-gateway")
    (assert/equal (name->id "Web -- Server") "web-server")
    (assert/equal (name->id "Ünïcode ünd Umlaute") "n-code-nd-umlaute")
    (assert/equal (name->id "(((") "")
    (assert/equal (name->id "") "")))

(test "derived-id is the id a name edit should rename to, or nil"
  (fn []
    (assert/equal (derived-id {:section "nodes" :id "web"} "Web Server") "web-server")
    (assert/equal (derived-id {:section "boxes" :id "grp"} "Backend") "backend")
    ;; already that id: nothing to rename
    (assert/ok (nil? (derived-id {:section "nodes" :id "web-server"} "Web Server")))
    ;; nothing legal left in the name: keep the id
    (assert/ok (nil? (derived-id {:section "nodes" :id "web"} "(((")))
    ;; edges have no id
    (assert/ok (nil? (derived-id {:section "edges" :id ["a" "b"]} "Calls")))))

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

(test "chords for box membership: remove node, new node in box, remove from box"
  (fn []
    (assert/equal (chord-action "box" "r" "n") "remove-node-member")
    (assert/equal (chord-action "box" "c" "n") "new-node-in-box")
    (assert/equal (chord-action "node" "r" "b") "remove-from-box")
    (assert/ok (nil? (chord-action "node" "c" "n")))
    (assert/equal (chord-for "box" "remove-node-member") "r n")
    (assert/equal (chord-hint "box" "r") "r … r rename · n remove node")))

(test "n n on a box is new node inside it, as c n is (#105)"
  (fn []
    (assert/equal (chord-action "box" "n" "n") "new-node-in-box")
    (assert/equal (chord-action "box" "c" "n") "new-node-in-box")
    (assert/equal (chord-for "box" "new-node-in-box") "n n")
    (assert/equal (chord-hint "box" "n") "n … n new node · b new box")))

(test "pick-ops box-drop accepts only a node whose parent is the box"
  (fn []
    (let [pick {:mode "box-drop" :box "g"}]
      (assert/deepEqual (pick-ops pick {:kind "node" :id "n:a" :parent "g"})
                        [{:op "box-remove" :box "g" :member "a"}])
      (assert/ok (nil? (pick-ops pick {:kind "node" :id "n:a" :parent "other"})))
      (assert/ok (nil? (pick-ops pick {:kind "node" :id "n:a"})))
      (assert/ok (nil? (pick-ops pick {:kind "box" :id "b:x" :parent "g"}))))))

(test "add-node-in-box-ops and box-remove-op"
  (fn []
    (assert/deepEqual (add-node-in-box-ops "g" "n1" "N1")
                      [{:op "add-node" :id "n1"}
                       {:op "set-attr" :section "nodes" :id "n1" :attr "name" :value "\"N1\"" :fallback false}
                       {:op "box-add" :box "g" :member "n1"}])
    (assert/deepEqual (box-remove-op "g" "a")
                      [{:op "box-remove" :box "g" :member "a"}])))

(test "resolve-ref joins a ref onto the directory of the current file"
  (fn []
    (assert/equal (resolve-ref "root.edn" "sub/api.edn") "sub/api.edn")
    (assert/equal (resolve-ref "sub/api.edn" "deep/db.edn") "sub/deep/db.edn")
    (assert/equal (resolve-ref "sub/api.edn" "../root.edn") "root.edn")
    (assert/equal (resolve-ref "sub/deep/db.edn" "../../root.edn") "root.edn")
    (assert/equal (resolve-ref "root.edn" "./sub//api.edn") "sub/api.edn")
    ;; climbing above the root, absolute and empty refs resolve to nothing
    (assert/ok (nil? (resolve-ref "root.edn" "../x.edn")))
    (assert/ok (nil? (resolve-ref "sub/api.edn" "../../x.edn")))
    (assert/ok (nil? (resolve-ref "root.edn" "/etc/passwd")))
    (assert/ok (nil? (resolve-ref "root.edn" "C:/x.edn")))
    (assert/ok (nil? (resolve-ref "root.edn" "  ")))
    (assert/ok (nil? (resolve-ref "root.edn" nil)))))

(test "nav-query and parse-nav round-trip file and trail, commas included"
  (fn []
    (assert/equal (nav-query nil []) "")
    (assert/equal (nav-query "sub/api.edn" []) "?file=sub%2Fapi.edn")
    (assert/deepEqual (parse-nav "") {:file nil :trail []})
    (assert/deepEqual (parse-nav "?file=sub%2Fapi.edn") {:file "sub/api.edn" :trail []})
    (let [q (nav-query "sub/deep/db.edn" ["root.edn" "a,b.edn"])]
      (assert/deepEqual (parse-nav q) {:file "sub/deep/db.edn" :trail ["root.edn" "a,b.edn"]}))))

(test "follow-url appends the current file to the trail; crumb-url truncates it"
  (fn []
    (assert/deepEqual (parse-nav (follow-url "root.edn" [] "sub/api.edn"))
                      {:file "sub/api.edn" :trail ["root.edn"]})
    (assert/deepEqual (parse-nav (follow-url "sub/api.edn" ["root.edn"] "sub/deep/db.edn"))
                      {:file "sub/deep/db.edn" :trail ["root.edn" "sub/api.edn"]})
    (assert/deepEqual (parse-nav (crumb-url ["root.edn" "sub/api.edn"] 0))
                      {:file "root.edn" :trail []})
    (assert/deepEqual (parse-nav (crumb-url ["root.edn" "sub/api.edn"] 1))
                      {:file "sub/api.edn" :trail ["root.edn"]})))

(test "ref-of yields the selection's string :ref, else nil"
  (fn []
    (assert/equal (ref-of {:kind "node" :attrs {:ref "sub/api.edn"}}) "sub/api.edn")
    (assert/ok (nil? (ref-of {:kind "node" :attrs {:ref "  "}})))
    (assert/ok (nil? (ref-of {:kind "node" :attrs {:ref 3}})))
    (assert/ok (nil? (ref-of {:kind "node" :attrs {}})))
    (assert/ok (nil? (ref-of {:kind "edge"})))))

(test "chord f r follows a ref for every selection kind"
  (fn []
    (assert/ok (chord-group? "f"))
    (assert/equal (chord-action "node" "f" "r") "follow-ref")
    (assert/equal (chord-action "edge" "f" "r") "follow-ref")
    (assert/equal (chord-action "box" "f" "r") "follow-ref")
    (assert/ok (nil? (chord-action nil "f" "r")))
    (assert/equal (chord-for "node" "follow-ref") "f r")))

(test "focus rides along in the URL, and only when there is one"
  (fn []
    (assert/equal (nav-query "views/d.edn" ["o.edn"] "n:api-svc")
                  "?file=views%2Fd.edn&trail=o.edn&focus=n%3Aapi-svc")
    (assert/deepEqual (parse-nav "?file=views%2Fd.edn&trail=o.edn&focus=n%3Aapi-svc")
                      {:file "views/d.edn" :trail ["o.edn"] :focus "n:api-svc"})
    (assert/deepEqual (parse-nav "?file=a.edn") {:file "a.edn" :trail []})
    (assert/equal (follow-url "o.edn" [] "views/d.edn" "b:grp")
                  "?file=views%2Fd.edn&trail=o.edn&focus=b%3Agrp")
    (assert/equal (follow-url "o.edn" [] "x.edn") "?file=x.edn&trail=o.edn")))

(test "f p follows a node's or a box's pair"
  (fn []
    (assert/equal (chord-action "node" "f" "p") "follow-pair")
    (assert/equal (chord-action "box" "f" "p") "follow-pair")
    (assert/ok (nil? (chord-action "edge" "f" "p")))
    (assert/equal (chord-hint "node" "f") "f … r follow ref · p follow pair")))

(test "theme-menu follows the file's theme and whether the page may edit it"
  (fn []
    (let [named (theme-menu {:theme {:bg "#000"} :theme-name "nord" :editable true})
          custom (theme-menu {:theme {:bg "#000"} :editable true})
          none (theme-menu {:editable true})
          read-only (theme-menu {:theme {:bg "#000"} :theme-name "nord"})]
      (assert/equal (:value named) "nord")
      (assert/equal (:disabled named) false)
      (assert/equal (:value custom) "custom")
      (assert/equal (:disabled custom) true)
      (assert/equal (:value none) "")
      (assert/equal (:disabled none) false)
      (assert/equal (:value read-only) "nord")
      (assert/equal (:disabled read-only) true))))

(test "set-theme-op names a built-in, or removes the theme for the empty entry"
  (fn []
    (assert/deepEqual (set-theme-op "nord") {:op "set-theme" :theme "nord"})
    (assert/deepEqual (set-theme-op "") {:op "set-theme" :theme nil})))

(test "top-box-of walks a scene id up to its outermost box"
  (fn []
    (let [parent-of {"n:api" "inner" "b:inner" "outer" "n:solo" "outer"}]
      (assert/equal (top-box-of parent-of "n:api") "outer")
      (assert/equal (top-box-of parent-of "b:inner") "outer")
      (assert/equal (top-box-of parent-of "n:solo") "outer")
      (assert/ok (nil? (top-box-of parent-of "n:free")) "a node in no box")
      (assert/ok (nil? (top-box-of parent-of "b:outer")) "a top-level box has no ancestor")
      (assert/ok (nil? (top-box-of nil "n:api")) "no parent-of at all"))))

(test "a dismissed banner stays hidden until its text changes (#111)"
  (fn []
    (assert/equal (banner-visible? "a\nb" nil) true)
    (assert/equal (banner-visible? "a\nb" "a\nb") false)
    (assert/equal (banner-visible? "a\nc" "a\nb") true)
    (assert/equal (banner-visible? "" nil) false)
    (assert/equal (banner-visible? nil nil) false)))
