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
