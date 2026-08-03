(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [serve]))

(deftest converts-keywords-and-sets-to-plain-json
  (let [out (json/parse-string
             (serve/edn->json
              (str "{:nodes {\"a\" {:name \"A\" :type \"svc\" :role [:active :passive]}}"
                   " :edges [{:nodes [\"a\" \"a\"] :direction :<-> :name \"self\"}]"
                   " :boxes [{:name \"g\" :components #{\"a\"}}]}")))]
    (is (= "<->" (get-in out ["edges" 0 "direction"])))
    (is (= ["active" "passive"] (get-in out ["nodes" "a" "role"])))
    (is (= ["a"] (get-in out ["boxes" 0 "components"])))
    (is (= "svc" (get-in out ["nodes" "a" "type"])))))

(deftest parse-error-becomes-error-json
  (let [out (json/parse-string (serve/edn->json "{:unclosed"))]
    (is (contains? out "error"))
    (is (string? (get out "error")))))
