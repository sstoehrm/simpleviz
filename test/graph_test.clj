(ns graph-test
  (:require [clojure.test :refer [deftest is]]
            [graph]))

(defn base []
  {:nodes {"a" {:name "A" :type "svc"} "b" {:name "B"}}
   :edges []
   :boxes []})

(deftest empty-input
  (let [g (graph/normalize {})]
    (is (= {} (:nodes g)))
    (is (= [] (:edges g)))
    (is (= [] (:boxes g)))
    (is (= [] (:warnings g)))))

(deftest non-map-root-warns
  (let [g (graph/normalize [1 2 3])]
    (is (= {} (:nodes g)))
    (is (= 1 (count (:warnings g))))))

(deftest node-name-falls-back-to-key
  (let [g (graph/normalize {:nodes {"a" {}}})]
    (is (= "a" (get-in g [:nodes "a" :name])))
    (is (= "" (get-in g [:nodes "a" :type])))))

(deftest numeric-name-type-coerced
  (let [g (graph/normalize {:nodes {"a" {:name 7 :type 3}}
                            :edges [{:nodes ["a" "a"] :name 1 :type 2}]
                            :boxes [{:name "x" :type 9 :components ["a"]}]})]
    (is (= "7" (get-in g [:nodes "a" :name])))
    (is (= "3" (get-in g [:nodes "a" :type])))
    (is (= "2" (:type (first (:edges g)))))
    (is (= "9" (:type (first (:boxes g)))))))

(deftest direction-forward
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :->}]))]
    (is (= "a" (:source (first (:edges g)))))
    (is (= "b" (:target (first (:edges g)))))
    (is (= {:source false :target true} (:arrows (first (:edges g)))))))

(deftest direction-backward-swaps
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :<-}]))]
    (is (= "b" (:source (first (:edges g)))))
    (is (= "a" (:target (first (:edges g)))))
    (is (= {:source false :target true} (:arrows (first (:edges g)))))))

(deftest direction-both-and-none
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :<->}
                                                 {:nodes ["a" "b"]}]))]
    (is (= {:source true :target true} (:arrows (first (:edges g)))))
    (is (= {:source false :target false} (:arrows (second (:edges g)))))))

(deftest direction-as-string-accepted
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction "<->"}]))]
    (is (= {:source true :target true} (:arrows (first (:edges g)))))
    (is (= [] (:warnings g)))))

(deftest unknown-direction-warns-undirected
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :=>}]))]
    (is (= 1 (count (:edges g))))
    (is (= {:source false :target false} (:arrows (first (:edges g)))))
    (is (= 1 (count (:warnings g))))))

(deftest edge-to-unknown-node-skipped
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "ghost"] :direction :->}]))]
    (is (= [] (:edges g)))
    (is (re-find #"ghost" (first (:warnings g))))))

(deftest edge-nodes-shape-enforced
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a"]}
                                                 {:nodes "ab"}
                                                 {}
                                                 {:nodes #{"a" "b"}}]))]
    (is (= [] (:edges g)))
    (is (= 4 (count (:warnings g))))))

(deftest nil-edge-entries-skipped
  (let [g (graph/normalize (assoc (base) :edges [nil {:nodes ["a" "b"]}]))]
    (is (= 1 (count (:edges g))))
    (is (= 1 (count (:warnings g))))))

(deftest wrong-collection-types-at-top-level
  (let [g1 (graph/normalize {:nodes {"a" {}} :edges {:oops 1}})
        g2 (graph/normalize {:nodes {"a" {}} :boxes "nope"})
        g3 (graph/normalize {:nodes [1 2 3]})]
    (is (and (= [] (:edges g1)) (= 1 (count (:warnings g1)))))
    (is (and (= [] (:boxes g2)) (= 1 (count (:warnings g2)))))
    (is (and (= {} (:nodes g3)) (= 1 (count (:warnings g3)))))))

(deftest components-vector-and-set-prefixed
  (let [gv (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a" "b"]}]))
        gs (graph/normalize (assoc (base) :boxes [{:name "x" :components #{"a" "b"}}]))]
    (is (= ["n:a" "n:b"] (sort (:components (first (:boxes gv))))))
    (is (= ["n:a" "n:b"] (sort (:components (first (:boxes gs))))))
    (is (= "x" (get (:parent-of gv) "n:a")))))

(deftest non-collection-components-warn-empty
  (let [g1 (graph/normalize (assoc (base) :boxes [{:name "x" :components 42}]))
        g2 (graph/normalize (assoc (base) :boxes [{:name "x" :components "abc"}]))]
    (is (and (= [] (:components (first (:boxes g1)))) (= 1 (count (:warnings g1)))))
    (is (and (= [] (:components (first (:boxes g2)))) (= 1 (count (:warnings g2)))))))

(deftest boxes-nest
  (let [g (graph/normalize (assoc (base) :boxes [{:name "outer" :components ["inner"]}
                                                 {:name "inner" :components ["a"]}]))]
    (is (= ["b:inner"] (:components (first (:boxes g)))))
    (is (= "outer" (get (:parent-of g) "b:inner")))))

(deftest duplicate-membership-first-box-wins
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a"]}
                                                 {:name "y" :components ["a" "b"]}]))]
    (is (= "x" (get (:parent-of g) "n:a")))
    (is (= ["n:b"] (:components (second (:boxes g)))))
    (is (= 1 (count (:warnings g))))))

(deftest unknown-component-warns
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["ghost"]}]))]
    (is (= [] (:components (first (:boxes g)))))
    (is (re-find #"ghost" (first (:warnings g))))))

(deftest box-cannot-contain-itself
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["x" "a"]}]))]
    (is (= ["n:a"] (:components (first (:boxes g)))))
    (is (= 1 (count (:warnings g))))))

(deftest containment-cycle-broken
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["y"]}
                                                 {:name "y" :components ["x"]}]))
        links (keep #(get (:parent-of g) (str "b:" %)) ["x" "y"])]
    (is (= 1 (count links)))
    (is (>= (count (:warnings g)) 1))))

(deftest duplicate-box-name-later-skipped
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a"]}
                                                 {:name "x" :components ["b"]}]))]
    (is (= 1 (count (:boxes g))))
    (is (= 1 (count (:warnings g))))))

(deftest empty-or-missing-box-name-skipped
  (let [g (graph/normalize (assoc (base) :boxes [{:name "" :components ["a"]}
                                                 {:components ["b"]}]))]
    (is (= [] (:boxes g)))
    (is (= 2 (count (:warnings g))))))

(deftest keyword-identifiers-end-to-end
  (let [g (graph/normalize {:nodes {:api {}}
                            :edges [{:nodes [:api :api]}]
                            :boxes [{:name :backend :components [:api]}]})]
    (is (= ["api"] (keys (:nodes g))))
    (is (= "api" (get-in g [:nodes "api" :name])))
    (is (= 1 (count (:edges g))))
    (is (= "api" (:source (first (:edges g)))))
    (is (= "api" (:target (first (:edges g)))))
    (is (= 1 (count (:boxes g))))
    (is (= "backend" (:name (first (:boxes g)))))
    (is (= ["n:api"] (:components (first (:boxes g)))))
    (is (= "backend" (get (:parent-of g) "n:api")))
    (is (= [] (:warnings g)))))

(deftest edges-as-set-accepted
  (let [g (graph/normalize (assoc (base) :edges #{{:nodes ["a" "b"]}}))]
    (is (= 1 (count (:edges g))))
    (is (= "a" (:source (first (:edges g)))))
    (is (= "b" (:target (first (:edges g)))))
    (is (= [] (:warnings g)))))

;; ---- format v2: maps for boxes and edges ----

(deftest boxes-as-map-with-keyword-ids
  (let [g (graph/normalize {:nodes {:a {} :b {}}
                            :boxes {:x {:components #{:a}}
                                    :outer {:components [:x :b]}}})]
    (is (= 2 (count (:boxes g))))
    (is (= "outer" (get (:parent-of g) "b:x")))
    (is (= "outer" (get (:parent-of g) "n:b")))
    (is (= "x" (get (:parent-of g) "n:a")))
    (is (= [] (:warnings g)))))

(deftest box-map-value-name-ignored-with-warning
  (let [g (graph/normalize {:boxes {:x {:name "other"}}})]
    (is (= "x" (:name (first (:boxes g)))))
    (is (= 1 (count (:warnings g))))))

(deftest box-map-nil-value-allowed
  (let [g (graph/normalize {:boxes {:x nil}})]
    (is (= "x" (:name (first (:boxes g)))))
    (is (= [] (:warnings g)))))

(deftest edges-as-map-keyed-by-endpoints
  (let [g (graph/normalize {:nodes {:a {} :b {} :c {}}
                            :edges {[:a :b] {:direction :-> :name "x"}
                                    [:b :c] nil}})]
    (is (= 2 (count (:edges g))))
    (is (= "a" (:source (first (:edges g)))))
    (is (= {:source false :target true} (:arrows (first (:edges g)))))
    (is (= {:source false :target false} (:arrows (second (:edges g)))))
    (is (= [] (:warnings g)))))

(deftest edge-map-ids-deterministic-sorted
  (let [g (graph/normalize {:nodes {:a {} :b {} :c {}}
                            :edges {[:b :c] {} [:a :c] {}}})]
    (is (= ["a" "c"] [(:source (first (:edges g))) (:target (first (:edges g)))]))
    (is (= "e0" (:id (first (:edges g)))))))

(deftest edge-map-bad-keys-and-values
  (let [g (graph/normalize {:nodes {:a {} :b {}}
                            :edges {:oops {} [:a :b] "nope"}})]
    (is (= [] (:edges g)))
    (is (= 2 (count (:warnings g))))))

(deftest edge-map-nodes-in-value-ignored
  (let [g (graph/normalize {:nodes {:a {} :b {}}
                            :edges {[:a :b] {:nodes [:b :a]}}})]
    (is (= "a" (:source (first (:edges g)))))
    (is (= 1 (count (:warnings g))))))

(deftest reversed-edge-pair-warns
  (let [g (graph/normalize {:nodes {:a {} :b {}}
                            :edges [{:nodes [:a :b]} {:nodes [:b :a]}]})]
    (is (= 2 (count (:edges g))))
    (is (= 1 (count (:warnings g))))
    (is (re-find #"same connection" (first (:warnings g))))))
