(ns edit-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn]
            [clojure.string]
            [edit]
            [graph]))

(def nodes-file
  "{:nodes {:web {:name \"Web\" ;; keep me\n               :type \"frontend\"}\n         :api nil}\n :edges {[:web :api] {:direction :->}}}")

(def small-file "{:nodes {:a nil}\n :edges {}\n :boxes {:grp {:components #{:a}}}}")

(def no-boxes-file "{:nodes {:a nil}\n :edges {}}")

(def nil-box-file "{:nodes {:a nil}\n :edges {}\n :boxes {:empty nil}}")

(def vec-box-file "{:nodes {:a nil :b nil}\n :edges {}\n :boxes {:zone {:components [:a]}}}")

(def tri-file "{:nodes {:a nil :b nil :c nil}\n :edges {[:a :b] {:direction :-> :name \"x\"}}}")

(deftest set-attr-replaces-value-preserving-comment
  (is (= "{:nodes {:web {:name \"Web UI\" ;; keep me\n               :type \"frontend\"}\n         :api nil}\n :edges {[:web :api] {:direction :->}}}"
         (edit/set-attr nodes-file {:section :nodes :id "web" :attr :name
                                    :value "\"Web UI\"" :fallback false}))))

(deftest set-attr-appends-missing-attr
  (is (= "{:nodes {:web {:name \"Web\" ;; keep me\n               :type \"frontend\" :lang \"clojure\"}\n         :api nil}\n :edges {[:web :api] {:direction :->}}}"
         (edit/set-attr nodes-file {:section :nodes :id "web" :attr :lang
                                    :value "\"clojure\"" :fallback false}))))

(deftest set-attr-on-nil-value-creates-map
  (is (= "{:nodes {:web {:name \"Web\" ;; keep me\n               :type \"frontend\"}\n         :api {:type \"svc\"}}\n :edges {[:web :api] {:direction :->}}}"
         (edit/set-attr nodes-file {:section :nodes :id "api" :attr :type
                                    :value "\"svc\"" :fallback false}))))

(deftest set-attr-fallback-rules
  ;; :fallback true: bare word -> string; :fallback false: bad EDN -> error
  (is (clojure.string/includes?
       (edit/set-attr nodes-file {:section :nodes :id "web" :attr :owner
                                  :value "platform team" :fallback true})
       ":owner \"platform team\""))
  (is (thrown-with-msg? Exception #"does not parse as EDN"
        (edit/set-attr nodes-file {:section :nodes :id "web" :attr :owner
                                   :value "{:unclosed" :fallback false}))))

(deftest set-attr-on-edge-by-endpoint-pair
  (is (= "{:nodes {:web {:name \"Web\" ;; keep me\n               :type \"frontend\"}\n         :api nil}\n :edges {[:web :api] {:direction :-> :name \"REST\"}}}"
         (edit/set-attr nodes-file {:section :edges :id ["web" "api"] :attr :name
                                    :value "\"REST\"" :fallback false}))))

(deftest del-attr-removes-pair
  (is (= "{:nodes {:web {:name \"Web\" ;; keep me\n}\n         :api nil}\n :edges {[:web :api] {:direction :->}}}"
         (edit/del-attr nodes-file {:section :nodes :id "web" :attr :type}))))

(deftest unknown-targets-fail-with-named-errors
  (is (thrown-with-msg? Exception #"unknown node \"ghost\""
        (edit/set-attr nodes-file {:section :nodes :id "ghost" :attr :a :value "1" :fallback false})))
  (is (thrown-with-msg? Exception #"unknown edge \[a b\]"
        (edit/set-attr nodes-file {:section :edges :id ["a" "b"] :attr :a :value "1" :fallback false}))))

(deftest vector-form-refused
  (is (thrown-with-msg? Exception #"pre-v2 vector form"
        (edit/set-attr "{:nodes {:a nil} :edges [{:nodes [:a :a]}]}"
                       {:section :edges :id ["a" "a"] :attr :x :value "1" :fallback false}))))

;; add-edge/retarget-edge consult edge-pairs (a (keys ..) walk) before the
;; usual sect-val vector-form guard runs; box-add/add-box consult exists?
;; on :boxes the same way. Both must refuse cleanly instead of leaking a
;; raw ClassCastException from (keys <vector>).
(def vec-edges-file "{:nodes {:a nil :b nil}\n :edges [{:nodes [:a :b]}]}")
(def vec-boxes-file "{:nodes {:a nil}\n :boxes [{:name \"g\" :components #{:a}}]}")

(deftest add-edge-refuses-vector-form-edges-before-walking-keys
  (let [e (try (edit/add-edge vec-edges-file {:from "a" :to "b" :direction nil})
               (catch Exception e e))]
    (is (some? e))
    (is (true? (:edit-error (ex-data e))))
    (is (= "pre-v2 vector form: convert edges to map form to edit" (.getMessage e)))))

(deftest retarget-edge-refuses-vector-form-edges-before-walking-keys
  (let [e (try (edit/retarget-edge vec-edges-file {:edge ["a" "b"] :end "target" :to "b"})
               (catch Exception e e))]
    (is (some? e))
    (is (true? (:edit-error (ex-data e))))
    (is (= "pre-v2 vector form: convert edges to map form to edit" (.getMessage e)))))

(deftest add-box-refuses-vector-form-boxes-before-walking-keys
  (let [e (try (edit/add-box vec-boxes-file {:id "h"})
               (catch Exception e e))]
    (is (some? e))
    (is (true? (:edit-error (ex-data e))))
    (is (= "pre-v2 vector form: convert boxes to map form to edit" (.getMessage e)))))

(deftest box-add-refuses-vector-form-boxes-before-walking-keys
  ;; member "ghost" isn't a node, so the :nodes exists? check short-circuits
  ;; false and falls through to the :boxes branch, hitting the same guard.
  (let [e (try (edit/box-add vec-boxes-file {:box "g" :member "ghost"})
               (catch Exception e e))]
    (is (some? e))
    (is (true? (:edit-error (ex-data e))))
    (is (= "pre-v2 vector form: convert boxes to map form to edit" (.getMessage e)))))

(deftest add-node-appends-entry
  (let [out (edit/add-node small-file {:id "b" :attrs-text nil})]
    (is (clojure.string/includes? out ":b nil"))
    ;; still parses and normalizes clean
    (is (contains? (:nodes (graph/normalize (clojure.edn/read-string out))) "b"))))

(deftest add-node-duplicate-fails
  (is (thrown-with-msg? Exception #"node \"a\" already exists"
        (edit/add-node small-file {:id "a" :attrs-text nil}))))

(deftest add-edge-appends-with-direction
  (let [out (-> small-file
                (edit/add-node {:id "b" :attrs-text nil})
                (edit/add-edge {:from "a" :to "b" :direction "->"}))]
    (is (clojure.string/includes? out "[:a :b] {:direction :->}"))))

(deftest add-edge-requires-known-endpoints-and-no-duplicate
  (is (thrown-with-msg? Exception #"unknown node or box \"ghost\""
        (edit/add-edge small-file {:from "a" :to "ghost" :direction nil})))
  (let [out (-> small-file (edit/add-node {:id "b" :attrs-text nil})
                (edit/add-edge {:from "a" :to "b" :direction nil}))]
    (is (thrown-with-msg? Exception #"edge \[a b\] already exists"
          (edit/add-edge out {:from "b" :to "a" :direction nil})))))

(deftest add-box-and-box-add
  (let [out (-> small-file
                (edit/add-box {:id "zone"})
                (edit/box-add {:box "zone" :member "a"}))]
    (is (clojure.string/includes? out ":zone {:components [:a]}"))
    (is (thrown-with-msg? Exception #"box \"grp\" already exists"
          (edit/add-box out {:id "grp"})))))

(deftest box-add-appends-to-existing-set
  (let [out (-> small-file (edit/add-node {:id "b" :attrs-text nil})
                (edit/box-add {:box "grp" :member "b"}))]
    (is (contains? (set (:components (first (:boxes (graph/normalize (clojure.edn/read-string out))))))
                   "n:b"))))

(deftest add-node-on-malformed-edn-fails-wrapped
  (let [e (try (edit/add-node "{:nodes {:a" {:id "b" :attrs-text nil})
               (catch Exception e e))]
    (is (some? e))
    (is (true? (:edit-error (ex-data e))))
    (is (re-find #"does not parse as EDN" (.getMessage e)))))

(deftest add-box-creates-missing-boxes-section
  (let [out (edit/add-box no-boxes-file {:id "zone"})]
    (is (clojure.string/includes? out ":boxes {:zone {:components []}}"))
    (is (some #(= (:name %) "zone")
              (:boxes (graph/normalize (clojure.edn/read-string out)))))))

(deftest box-add-unknown-member-fails
  (is (thrown-with-msg? Exception #"unknown node or box \"ghost\""
        (edit/box-add small-file {:box "grp" :member "ghost"}))))

(deftest box-add-self-containment-fails
  (is (thrown-with-msg? Exception #"a box cannot contain itself"
        (edit/box-add small-file {:box "grp" :member "grp"}))))

(deftest box-add-duplicate-member-set-form-fails
  ;; grp's :components is set-form #{:a}; "a" is already a member
  (is (thrown-with-msg? Exception #"\"a\" is already in box \"grp\""
        (edit/box-add small-file {:box "grp" :member "a"})))
  ;; the failed attempt didn't mutate/consume small-file — a fresh,
  ;; genuinely new box-add against the same original text still
  ;; succeeds cleanly, proving there's no corruption path
  (let [out (-> small-file (edit/add-node {:id "b" :attrs-text nil})
                (edit/box-add {:box "grp" :member "b"}))]
    (is (contains? (set (:components (first (:boxes (graph/normalize (clojure.edn/read-string out))))))
                   "n:b"))))

(deftest box-add-duplicate-member-vector-form-fails
  ;; zone's :components is vector-form [:a]; "a" is already a member
  (is (thrown-with-msg? Exception #"\"a\" is already in box \"zone\""
        (edit/box-add vec-box-file {:box "zone" :member "a"})))
  (let [out (edit/box-add vec-box-file {:box "zone" :member "b"})]
    (is (contains? (set (:components (first (:boxes (graph/normalize (clojure.edn/read-string out))))))
                   "n:b"))))

(deftest box-add-materializes-nil-box-entry
  (let [out (edit/box-add nil-box-file {:box "empty" :member "a"})]
    (is (clojure.string/includes? out ":empty {:components [:a]}"))
    (is (contains? (set (:components (first (filter #(= (:name %) "empty")
                                                      (:boxes (graph/normalize (clojure.edn/read-string out)))))))
                   "n:a"))))

(deftest retarget-edge-rewrites-key-only
  (is (= "{:nodes {:a nil :b nil :c nil}\n :edges {[:a :c] {:direction :-> :name \"x\"}}}"
         (edit/retarget-edge tri-file {:edge ["a" "b"] :end "target" :to "c"}))))

(deftest retarget-edge-validates
  (is (thrown-with-msg? Exception #"unknown node or box \"ghost\""
        (edit/retarget-edge tri-file {:edge ["a" "b"] :end "source" :to "ghost"})))
  (let [two (edit/add-edge tri-file {:from "a" :to "c" :direction nil})]
    (is (thrown-with-msg? Exception #"edge \[a c\] already exists"
          (edit/retarget-edge two {:edge ["a" "b"] :end "target" :to "c"}))))
  (is (thrown-with-msg? Exception #"cannot connect an element to itself"
        (edit/retarget-edge tri-file {:edge ["a" "b"] :end "target" :to "a"}))))

(deftest set-direction-sets-attr-never-swaps-key
  (is (= "{:nodes {:a nil :b nil :c nil}\n :edges {[:a :b] {:direction :<- :name \"x\"}}}"
         (edit/set-direction tri-file {:edge ["a" "b"] :direction "<-"})))
  ;; edge with nil value gets a map
  (let [f "{:nodes {:a nil :b nil}\n :edges {[:a :b] nil}}"]
    (is (= "{:nodes {:a nil :b nil}\n :edges {[:a :b] {:direction :<->}}}"
           (edit/set-direction f {:edge ["a" "b"] :direction "<->"})))))

(def casc-file
  (str "{:nodes {:a nil :b nil :c nil}\n"
       " :edges {[:a :b] {:direction :->}\n"
       "         [:c :grp] nil}\n"
       " :boxes {:grp {:components #{:a :inner}}\n"
       "         :inner {:components [:b]}}}"))

(deftest delete-edge-removes-only-that-entry
  (let [out (edit/delete casc-file {:section :edges :id ["a" "b"]})]
    (is (not (clojure.string/includes? out "[:a :b]")))
    (is (clojure.string/includes? out "[:c :grp]"))))

(deftest delete-node-cascades-edges-and-memberships
  (let [out (edit/delete casc-file {:section :nodes :id "a"})
        g (graph/normalize (clojure.edn/read-string out))]
    (is (not (contains? (:nodes g) "a")))
    (is (empty? (filter (fn [e] (= "a" (:source e))) (:edges g))))
    (is (= [] (:warnings g)))))     ; no dangling references left behind

(deftest delete-box-releases-members-and-references
  (let [out (edit/delete casc-file {:section :boxes :id "grp"})
        g (graph/normalize (clojure.edn/read-string out))]
    (is (nil? (some (fn [b] (when (= "grp" (:name b)) b)) (:boxes g))))
    ;; inner survives, unboxed; c's edge to grp is gone; a-b (unrelated to
    ;; grp) is untouched
    (is (some (fn [b] (= "inner" (:name b))) (:boxes g)))
    (is (empty? (filter (fn [e] (or (= "grp" (:source e)) (= "grp" (:target e))))
                        (:edges g))))
    (is (some (fn [e] (and (= "a" (:source e)) (= "b" (:target e)))) (:edges g)))
    (is (= [] (:warnings g)))))

(deftest apply-ops-batch-is-atomic
  (let [ops [{:op "add-node" :id "x"}
             {:op "add-edge" :from "x" :to "ghost"}]]   ; second op fails
    (is (= {:error "unknown node or box \"ghost\""}
           (edit/apply-ops small-file ops))))
  (let [{:keys [text]} (edit/apply-ops small-file
                                       [{:op "add-node" :id "x"}
                                        {:op "add-edge" :from "a" :to "x" :direction "->"}])]
    (is (clojure.string/includes? text "[:a :x] {:direction :->}"))))

(deftest apply-ops-normalizes-string-payloads
  (let [{:keys [text]} (edit/apply-ops small-file
                                       [{:op "set-attr" :section "nodes" :id "a"
                                         :attr "type" :value "\"svc\"" :fallback false}])]
    (is (clojure.string/includes? text ":type \"svc\""))))

(deftest apply-ops-unknown-op
  (is (= {:error "unknown op \"frobnicate\""}
         (edit/apply-ops small-file [{:op "frobnicate"}]))))

(deftest apply-ops-unknown-section-fails-cleanly
  ;; ledger finding from Task 1: `unknown!` throws a raw
  ;; IllegalArgumentException for a section outside :nodes/:edges/:boxes.
  ;; apply-ops must catch this at dispatch time, not let it escape raw.
  (is (= {:error "unknown section \"bogus\""}
         (edit/apply-ops small-file [{:op "set-attr" :section "bogus" :id "a"
                                       :attr "x" :value "1" :fallback false}]))))
