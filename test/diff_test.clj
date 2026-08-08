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
