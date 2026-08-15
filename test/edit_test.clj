(ns edit-test
  (:require [clojure.test :refer [deftest is]]
            [edit]))

(def nodes-file
  "{:nodes {:web {:name \"Web\" ;; keep me\n               :type \"frontend\"}\n         :api nil}\n :edges {[:web :api] {:direction :->}}}")

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
