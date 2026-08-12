(ns diff-test
  (:require [clojure.test :refer [deftest is]]
            [diff]
            [graph]))

(defn norm [raw] (graph/normalize raw))

(defn u [old new] (diff/union (norm old) (norm new) "old.edn" "new.edn"))

(deftest identical-graphs-carry-no-diff
  (let [g (u {:nodes {:a {:name "A"}}} {:nodes {:a {:name "A"}}})]
    (is (nil? (get-in g [:nodes "a" :diff])))
    (is (= {:old "old.edn" :new "new.edn"} (:compare g)))))

(deftest added-node-marked
  (let [g (u {:nodes {:a {}}} {:nodes {:a {} :b {}}})]
    (is (nil? (get-in g [:nodes "a" :diff])))
    (is (= "added" (get-in g [:nodes "b" :diff])))))

(deftest removed-node-kept-in-union
  (let [g (u {:nodes {:a {} :b {:name "B" :type "svc"}}} {:nodes {:a {}}})]
    (is (= "removed" (get-in g [:nodes "b" :diff])))
    (is (= "B" (get-in g [:nodes "b" :name])))
    (is (= "svc" (get-in g [:nodes "b" :type])))))

(deftest modified-node-lists-changed-attrs
  (let [g (u {:nodes {:a {:lang "clojure" :replicas 3}}}
             {:nodes {:a {:lang "rust" :owner "x"}}})]
    (is (= "modified" (get-in g [:nodes "a" :diff])))
    (is (= {:old "clojure" :new "rust"} (get-in g [:nodes "a" :changed :lang])))
    (is (= {:old 3 :new nil} (get-in g [:nodes "a" :changed :replicas])))
    (is (= {:old nil :new "x"} (get-in g [:nodes "a" :changed :owner])))))

(deftest node-membership-change-is-modified
  (let [g (u {:nodes {:a {}} :boxes {:x {:components #{:a}} :y {}}}
             {:nodes {:a {}} :boxes {:x {} :y {:components #{:a}}}})]
    (is (= "modified" (get-in g [:nodes "a" :diff])))
    (is (= {:old "x" :new "y"} (get-in g [:nodes "a" :changed "box membership"])))))

(deftest warnings-prefixed-with-file-name
  (let [g (u {:nodes [1 2]} {:nodes {:a {}}})]
    (is (= [":nodes must be a map, ignoring it"] (:warnings (norm {:nodes [1 2]}))))
    (is (= ["old.edn: :nodes must be a map, ignoring it"] (:warnings g)))))

(deftest added-and-removed-boxes
  (let [g (u {:boxes {:x {}}} {:boxes {:y {}}})
        by-name (into {} (map (juxt :name identity)) (:boxes g))]
    (is (= "added" (:diff (get by-name "y"))))
    (is (= "removed" (:diff (get by-name "x"))))))

(deftest box-attr-and-component-changes-are-modified
  (let [g (u {:nodes {:a {} :b {}} :boxes {:x {:type "zone" :components #{:a}}}}
             {:nodes {:a {} :b {}} :boxes {:x {:type "area" :components #{:a :b}}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "modified" (:diff x)))
    (is (= {:old "zone" :new "area"} (get (:changed x) :type)))
    (is (= {:old ["n:a"] :new ["n:a" "n:b"]} (get (:changed x) "components")))))

(deftest removed-node-stays-in-old-parent-box
  (let [g (u {:nodes {:a {} :gone {}} :boxes {:x {:components #{:a :gone}}}}
             {:nodes {:a {}} :boxes {:x {:components #{:a}}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "x" (get (:parent-of g) "n:gone")))
    (is (some #{"n:gone"} (:components x)))
    ;; the box changed only because a member vanished -> still "modified"
    (is (= "modified" (:diff x)))))

(deftest removed-box-keeps-its-removed-members
  (let [g (u {:nodes {:a {} :b {}} :boxes {:x {:components #{:a :b}}}}
             {:nodes {:a {}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "removed" (:diff x)))
    ;; a survives in new at top level -> no longer inside x; b is removed -> stays
    (is (= ["n:b"] (:components x)))
    (is (nil? (get (:parent-of g) "n:a")))
    (is (= "x" (get (:parent-of g) "n:b")))))

(deftest moved-node-follows-new-structure
  (let [g (u {:nodes {:a {}} :boxes {:x {:components #{:a}} :y {}}}
             {:nodes {:a {}} :boxes {:x {} :y {:components #{:a}}}})]
    (is (= "y" (get (:parent-of g) "n:a")))))

(defn edges-by-endpoints [g]
  (into {} (map (fn [e] [[(:source e) (:target e)] e])) (:edges g)))

(deftest reversed-endpoints-match-as-modified
  (let [g (u {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}
             {:nodes {:a {} :b {}} :edges {[:b :a] {:direction :->}}})
        e (get (edges-by-endpoints g) ["b" "a"])]
    (is (= 1 (count (:edges g))))
    (is (= "modified" (:diff e)))
    (is (= {:old ["a" "b"] :new ["b" "a"]} (get (:changed e) :nodes)))))

(deftest direction-change-is-modified
  (let [g (u {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}
             {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :<->}}})
        e (first (:edges g))]
    (is (= "modified" (:diff e)))
    (is (= {:old :-> :new :<->} (get (:changed e) :direction)))
    ;; orientation and arrows come from the NEW file
    (is (= {:source true :target true} (:arrows e)))))

(deftest added-and-removed-edges
  (let [g (u {:nodes {:a {} :b {} :c {}} :edges {[:a :b] {}}}
             {:nodes {:a {} :b {} :c {}} :edges {[:a :c] {}}})
        by (edges-by-endpoints g)]
    (is (= "added" (:diff (get by ["a" "c"]))))
    (is (= "removed" (:diff (get by ["a" "b"]))))
    (is (= 2 (count (:edges g))))))

(deftest edge-to-removed-node-survives
  (let [g (u {:nodes {:a {} :m {}} :edges {[:a :m] {:name "send"}}}
             {:nodes {:a {}}})
        e (first (:edges g))]
    (is (= "removed" (:diff e)))
    (is (= "removed" (get-in g [:nodes "m" :diff])))
    (is (= "send" (:name e)))))

(deftest union-edge-ids-are-unique-and-sequential
  (let [g (u {:nodes {:a {} :b {} :c {}} :edges {[:a :b] {} [:b :c] {}}}
             {:nodes {:a {} :b {} :c {}} :edges {[:a :c] {} [:a :b] {}}})]
    (is (= 3 (count (:edges g))))
    (is (= (set (map :id (:edges g))) #{"e0" "e1" "e2"}))))

(deftest both-orientations-in-old-one-in-new
  ;; old wrote the pair twice (both directions); new keeps one -> the
  ;; other is removed, deterministically
  (let [g (u {:nodes {:a {} :b {}} :edges [{:nodes [:a :b]} {:nodes [:b :a]}]}
             {:nodes {:a {} :b {}} :edges {[:a :b] {}}})
        statuses (frequencies (map :diff (:edges g)))]
    (is (= 2 (count (:edges g))))
    (is (= 1 (get statuses "removed")))))

(deftest equivalent-spellings-are-not-changes
  ;; pre-v2 vector form (keyword idents, string direction) vs map form:
  ;; canonicalization keeps the edge unchanged
  (let [g (u {:nodes {:a {} :b {}} :edges [{:nodes [:a :b] :direction "->"}]}
             {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}})]
    (is (nil? (:diff (first (:edges g)))))))

(deftest absent-direction-equals-explicit-default
  (let [g (u {:nodes {:a {} :b {}} :edges {[:a :b] {}}}
             {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :-}}})]
    (is (nil? (:diff (first (:edges g)))))))

(deftest box-endpoint-edges-match-by-name
  (let [g (u {:nodes {:web {}} :boxes {:backend {}}
              :edges {[:web :backend] {:direction :->}}}
             {:nodes {:web {}} :boxes {:backend {}}
              :edges {[:web :backend] {:direction :<->}}})]
    (is (= 1 (count (:edges g))))
    (is (= "modified" (:diff (first (:edges g)))))
    (is (= "b:backend" (:target-id (first (:edges g)))))))

(deftest removed-edge-to-removed-box-survives
  (let [g (u {:nodes {:a {}} :boxes {:x {}} :edges {[:a :x] {:name "uses"}}}
             {:nodes {:a {}}})
        e (first (:edges g))]
    (is (= "removed" (:diff e)))
    (is (= "b:x" (:target-id e)))
    (is (= "removed" (:diff (first (filter (fn [b] (= "x" (:name b))) (:boxes g))))))))

(deftest union-keeps-removed-edge-whose-endpoint-moved-into-the-box
  ;; old: web outside backend, edge web->backend. new: web inside backend,
  ;; edge gone. The union edge n:web->b:backend violates the single-file
  ;; containment rule by construction — it must survive as removed.
  (let [g (u {:nodes {:web {}} :boxes {:backend {}}
              :edges {[:web :backend] {:direction :->}}}
             {:nodes {:web {}} :boxes {:backend {:components #{:web}}}})
        e (first (:edges g))]
    (is (= 1 (count (:edges g))))
    (is (= "removed" (:diff e)))
    (is (= "n:web" (:source-id e)))
    (is (= "b:backend" (:target-id e)))
    (is (= "backend" (get (:parent-of g) "n:web")))))

(deftest namespaced-keyword-vs-string-spelling-not-a-change
  (let [g (u {:nodes {:app/web {} :backend.server/database {}}
              :edges [{:nodes [:app/web :backend.server/database] :direction :->}]}
             {:nodes {"app/web" {} "backend.server/database" {}}
              :edges [{:nodes ["app/web" "backend.server/database"] :direction :->}]})]
    (is (nil? (get-in g [:nodes "backend.server/database" :diff])))
    (is (= 1 (count (:edges g))))
    (is (nil? (:diff (first (:edges g)))))))

(deftest box-label-rename-is-a-modification
  (let [g (u {:nodes {:a {}} :boxes {:x {:name "Old Label" :components #{:a}}}}
             {:nodes {:a {}} :boxes {:x {:name "New Label" :components #{:a}}}})
        box (first (:boxes g))]
    (is (= 1 (count (:boxes g))))
    (is (= "modified" (:diff box)))
    (is (= "New Label" (:label box)))
    (is (= {:old "Old Label" :new "New Label"} (get-in box [:changed :name])))))
