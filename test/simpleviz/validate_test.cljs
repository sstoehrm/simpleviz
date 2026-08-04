(ns simpleviz.validate-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.validate :refer [validate]]))

(defn base []
  {:nodes {"a" {:name "A" :type "svc"} "b" {:name "B"}}
   :edges []
   :boxes []})

(test "empty input yields empty graph, no warnings"
  (fn []
    (let [g (validate {})]
      (assert/deepEqual (:nodes g) {})
      (assert/deepEqual (:edges g) [])
      (assert/deepEqual (:boxes g) [])
      (assert/deepEqual (:warnings g) []))))

(test "node name falls back to its key; type to empty string"
  (fn []
    (let [g (validate {:nodes {"a" {}}})]
      (assert/equal (:name (get (:nodes g) "a")) "a")
      (assert/equal (:type (get (:nodes g) "a")) ""))))

(test "numeric name/type are coerced to strings, no throw"
  (fn []
    (let [g (validate {:nodes {"a" {:name 7 :type 3}}
                       :edges [{:nodes ["a" "a"] :name 1 :type 2}]
                       :boxes [{:name "x" :type 9 :components ["a"]}]})]
      (assert/equal (:name (get (:nodes g) "a")) "7")
      (assert/equal (:type (get (:nodes g) "a")) "3")
      (assert/equal (:type (nth (:edges g) 0)) "2")
      (assert/equal (:type (nth (:boxes g) 0)) "9"))))

(test "direction -> keeps order, arrow on target only"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "->" :name "x" :type "t"}]))]
      (assert/equal (:source (nth (:edges g) 0)) "a")
      (assert/equal (:target (nth (:edges g) 0)) "b")
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target true}))))

(test "direction <- swaps endpoints"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "<-"}]))]
      (assert/equal (:source (nth (:edges g) 0)) "b")
      (assert/equal (:target (nth (:edges g) 0)) "a")
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target true}))))

(test "<-> arrows both ends; missing direction means none"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "<->"}
                                            {:nodes ["a" "b"]}]))]
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source true :target true})
      (assert/deepEqual (:arrows (nth (:edges g) 1)) {:source false :target false}))))

(test "unknown direction warns and renders undirected"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "=>"}]))]
      (assert/equal (.-length (:edges g)) 1)
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target false})
      (assert/equal (.-length (:warnings g)) 1))))

(test "edge to unknown node is skipped with warning"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "ghost"] :direction "->"}]))]
      (assert/equal (.-length (:edges g)) 0)
      (assert/match (nth (:warnings g) 0) (js/RegExp. "ghost")))))

(test "edge :nodes must be a 2-element vector"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a"]} {:nodes "ab"} {}]))]
      (assert/equal (.-length (:edges g)) 0)
      (assert/equal (.-length (:warnings g)) 3))))

(test "null or undefined edge entries are skipped with warning"
  (fn []
    (let [g (validate (assoc (base) :edges [nil {:nodes ["a" "b"]}]))]
      (assert/equal (.-length (:edges g)) 1)
      (assert/equal (.-length (:warnings g)) 1))))

(test "non-array edges/boxes and non-object nodes warn and act empty"
  (fn []
    (let [g1 (validate {:nodes {"a" {}} :edges {:oops 1}})
          g2 (validate {:nodes {"a" {}} :boxes "nope"})
          g3 (validate {:nodes [1 2 3]})]
      (assert/deepEqual (:edges g1) [])
      (assert/equal (.-length (:warnings g1)) 1)
      (assert/deepEqual (:boxes g2) [])
      (assert/equal (.-length (:warnings g2)) 1)
      (assert/deepEqual (:nodes g3) {})
      (assert/equal (.-length (:warnings g3)) 1))))

(test "box components become prefixed ids"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a" "b"]}]))]
      (assert/deepEqual (sort (:components (get (:boxes-by-name g) "x"))) ["n:a" "n:b"])
      (assert/equal (get (:parent-of g) "n:a") "x"))))

(test "non-array box components warn and act empty"
  (fn []
    (let [g1 (validate (assoc (base) :boxes [{:name "x" :components 42}]))
          g2 (validate (assoc (base) :boxes [{:name "x" :components "abc"}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g1) "x")) [])
      (assert/equal (.-length (:warnings g1)) 1)
      (assert/deepEqual (:components (get (:boxes-by-name g2) "x")) [])
      (assert/equal (.-length (:warnings g2)) 1))))

(test "boxes nest via box-name components"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "outer" :components ["inner"]}
                                            {:name "inner" :components ["a"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "outer")) ["b:inner"])
      (assert/equal (get (:parent-of g) "b:inner") "outer"))))

(test "duplicate membership: first box in file order wins"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a"]}
                                            {:name "y" :components ["a" "b"]}]))]
      (assert/equal (get (:parent-of g) "n:a") "x")
      (assert/deepEqual (:components (get (:boxes-by-name g) "y")) ["n:b"])
      (assert/equal (.-length (:warnings g)) 1))))

(test "unknown component ignored with warning"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["ghost"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "x")) [])
      (assert/match (nth (:warnings g) 0) (js/RegExp. "ghost")))))

(test "box cannot contain itself"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["x" "a"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "x")) ["n:a"])
      (assert/equal (.-length (:warnings g)) 1))))

(test "containment cycle is broken with warning"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["y"]}
                                            {:name "y" :components ["x"]}]))
          links (filterv some? [(get (:parent-of g) "b:x") (get (:parent-of g) "b:y")])]
      (assert/equal (.-length links) 1)
      (assert/ok (>= (.-length (:warnings g)) 1)))))

(test "box with empty :name is skipped with warning"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "" :components ["a"]}]))]
      (assert/equal (.-length (:boxes g)) 0)
      (assert/equal (.-length (:warnings g)) 1)
      (assert/match (nth (:warnings g) 0) (js/RegExp. "missing :name")))))

(test "duplicate box name: later one ignored"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a"]}
                                            {:name "x" :components ["b"]}]))]
      (assert/equal (.-length (:boxes g)) 1)
      (assert/equal (.-length (:warnings g)) 1))))
