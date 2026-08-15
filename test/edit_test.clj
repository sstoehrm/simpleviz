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

(deftest box-add-materializes-nil-box-entry
  (let [out (edit/box-add nil-box-file {:box "empty" :member "a"})]
    (is (clojure.string/includes? out ":empty {:components [:a]}"))
    (is (contains? (set (:components (first (filter #(= (:name %) "empty")
                                                      (:boxes (graph/normalize (clojure.edn/read-string out)))))))
                   "n:a"))))
