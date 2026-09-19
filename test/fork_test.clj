(ns fork-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [fork]
            [serve]))

(deftest resolve-ref-collapses-segments-and-refuses-escapes
  (is (= "sub/api.edn" (fork/resolve-ref "root.edn" "sub/api.edn")))
  (is (= "sub/deep/db.edn" (fork/resolve-ref "sub/api.edn" "deep/db.edn")))
  (is (= "root.edn" (fork/resolve-ref "sub/api.edn" "../root.edn")))
  (is (= "sub/x.edn" (fork/resolve-ref "sub/api.edn" "./x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "../x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "/etc/x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "  ")))
  (is (nil? (fork/resolve-ref "root.edn" nil))))

(deftest ref-targets-collects-string-refs-on-every-element-kind
  (is (= #{"sub/api.edn" "x.edn" "y.edn"}
         (set (fork/ref-targets
               (str "{:nodes {:a {:ref \"sub/api.edn\"} :b {:ref 7} :c {:ref \"\"}}"
                    " :edges {[:a :b] {:ref \"x.edn\"}}"
                    " :boxes {:z {:components #{:a} :ref \"y.edn\"}}}")))))
  ;; vector forms work too
  (is (= ["v.edn"] (fork/ref-targets "{:nodes {:a nil} :boxes [{:name \"g\" :components #{:a} :ref \"v.edn\"}]}")))
  (is (thrown? Exception (fork/ref-targets "{:unclosed"))))

(defn- tree!
  "Write {rel text} under a fresh temp dir; returns its canonical File."
  [files]
  (let [dir (.getCanonicalFile (.toFile (fs/create-temp-dir {:prefix "fork-test"})))]
    (doseq [[rel text] files]
      (let [f (io/file dir rel)]
        (.mkdirs (.getParentFile f))
        (spit f text)))
    dir))

(defn- reader [root] (fn [rel] (serve/read-source (.getPath (serve/resolve-path root rel)))))

(deftest closure-walks-refs-depth-first-once-and-skips-bad-ones
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"} :b {:ref \"sub/api.edn\"}}}"
                     "sub/api.edn" "{:nodes {:h {:ref \"deep/db.edn\"} :up {:ref \"../root.edn\"} :out {:ref \"../../x.edn\"} :gone {:ref \"nope.edn\"} :txt {:ref \"../notes.txt\"}}}"
                     "sub/deep/db.edn" "{:nodes {:t {:ref \"../../root.edn\"}}}"
                     "notes.txt" "not a graph"})
        warnings (atom [])
        out (fork/closure "root.edn" (reader root) #(swap! warnings conj %))]
    (is (= "root.edn" (first out)))
    (is (= #{"root.edn" "sub/api.edn" "sub/deep/db.edn"} (set out)))
    (is (= 3 (count out)))
    (is (= 3 (count @warnings)))
    (is (some #(clojure.string/includes? % "\"../../x.edn\" leaves the root folder") @warnings))
    (is (some #(clojure.string/includes? % "\"nope.edn\"") @warnings))
    (is (some #(clojure.string/includes? % "\"../notes.txt\"") @warnings))
    (fs/delete-tree root)))

(deftest closure-names-the-file-on-a-parse-error
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"bad.edn\"}}}" "bad.edn" "{:unclosed"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^bad\.edn: "
                          (fork/closure "root.edn" (reader root) (fn [_]))))
    (fs/delete-tree root)))
