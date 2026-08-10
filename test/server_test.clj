(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [serve]))

(deftest graph-json-serves-normalized-graph
  (let [out (json/parse-string
             (serve/graph-json
              (str "{:nodes {\"a\" {:name \"A\" :role [:active :passive]}}"
                   " :edges [{:nodes [\"a\" \"a\"] :direction :<-> :name \"self\"}]"
                   " :boxes [{:name \"g\" :components #{\"a\"}}]}")))]
    (is (= "a" (get-in out ["edges" 0 "source"])))
    (is (= true (get-in out ["edges" 0 "arrows" "source"])))
    (is (= ["active" "passive"] (get-in out ["nodes" "a" "attrs" "role"])))
    (is (= ["n:a"] (get-in out ["boxes" 0 "components"])))
    (is (= "g" (get-in out ["parent-of" "n:a"])))
    (is (= [] (get out "warnings")))))

(deftest parse-error-becomes-error-json
  (let [out (json/parse-string (serve/graph-json "{:unclosed"))]
    (is (contains? out "error"))
    (is (string? (get out "error")))))

(deftest parse-args-uses-default-port
  (is (= {:file "g.edn" :port 7373} (serve/parse-args ["g.edn"]))))

(deftest parse-args-accepts-port-flag-and-alias
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["g.edn" "--port" "9000"])))
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["g.edn" "-p" "9000"])))
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["--port" "9000" "g.edn"]))))

(deftest parse-args-rejects-bad-input
  (is (contains? (serve/parse-args []) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "abc"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "0"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "70000"]) :error)))

(deftest parse-args-two-files-enables-compare
  (is (= {:old-file "a.edn" :file "b.edn" :port 7373}
         (serve/parse-args ["a.edn" "b.edn"])))
  (is (= {:old-file "a.edn" :file "b.edn" :port 9000}
         (serve/parse-args ["a.edn" "b.edn" "-p" "9000"]))))

(deftest parse-args-rejects-three-files
  (is (contains? (serve/parse-args ["a.edn" "b.edn" "c.edn"]) :error)))

(deftest compare-json-diffs-two-graphs
  (let [out (json/parse-string
             (serve/compare-json "{:nodes {:a {}}}"
                                 "{:nodes {:a {} :b {}}}"
                                 "old.edn" "new.edn"))]
    (is (= "added" (get-in out ["nodes" "b" "diff"])))
    (is (nil? (get-in out ["nodes" "a" "diff"])))
    (is (= {"old" "old.edn" "new" "new.edn"} (get out "compare")))))

(deftest compare-json-parse-error-names-the-file
  (let [out (json/parse-string
             (serve/compare-json "{:unclosed" "{}" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "old.edn: ")))
  (let [out (json/parse-string
             (serve/compare-json "{}" "{:unclosed" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "new.edn: "))))

(deftest single-file-json-has-no-compare-keys
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "compare")))
    (is (not (contains? (get-in out ["nodes" "a"]) "diff")))))

(deftest graph-json-includes-file-basename
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}" "demo.edn"))]
    (is (= "demo.edn" (get out "file"))))
  ;; 1-arity unchanged: no :file key
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "file")))))

(deftest compare-json-includes-new-file-basename
  (let [out (json/parse-string
             (serve/compare-json "{}" "{}" "examples/old.edn" "examples/new.edn"))]
    (is (= "new.edn" (get out "file")))))

(deftest api-source-serves-raw-text
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))
    (is (clojure.string/starts-with?
         (get-in resp [:headers "Content-Type"]) "text/plain"))))

(deftest api-source-compare-selects-files
  (reset! serve/files {:old "examples/demo.edn" :new "examples/demo-next.edn"})
  (is (= (slurp "examples/demo.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=new"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source"})))))

(deftest api-source-old-without-compare-404s
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "which=old"})))))
