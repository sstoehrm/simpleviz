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

(deftest fork-copies-the-closure-verbatim-and-refuses-existing-targets
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"}}} ; keep me"
                     "sub/api.edn" "{:nodes {:h {:ref \"deep/db.edn\"}}}"
                     "sub/deep/db.edn" "{:nodes {:t nil}}"
                     "unrelated.edn" "{}"})
        file (.getPath (io/file root "root.edn"))
        created (fork/fork! file "next" (fn [_]))]
    (is (= (mapv #(.getPath (io/file root %)) ["root-next.edn" "sub/api-next.edn" "sub/deep/db-next.edn"])
           created))
    (is (= (slurp (io/file root "root.edn")) (slurp (io/file root "root-next.edn"))))
    (is (= (slurp (io/file root "sub/api.edn")) (slurp (io/file root "sub/api-next.edn"))))
    (is (not (.exists (io/file root "unrelated-next.edn"))))
    ;; a second fork refuses and writes nothing
    (spit (io/file root "sub/deep/db-next.edn") "{:nodes {:changed nil}}")
    (.delete (io/file root "root-next.edn"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub/deep/db-next\.edn already exists"
                          (fork/fork! file "next" (fn [_]))))
    (is (not (.exists (io/file root "root-next.edn"))))
    (fs/delete-tree root)))

(deftest promote-moves-every-fork-in-the-forks-closure
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"}}}"
                     "root-next.edn" "{:nodes {:a {:ref \"sub/api.edn\"} :n {:ref \"new.edn\"}}}"
                     "sub/api.edn" "{:nodes {:h nil}}"
                     "sub/api-next.edn" "{:nodes {:h nil :extra nil}}"
                     "new-next.edn" "{:nodes {:only-in-fork nil}}"
                     "other.edn" "{}"
                     "other-next.edn" "{:nodes {:x nil}}"})
        file (.getPath (io/file root "root.edn"))
        moved (fork/promote! file "next" (fn [_]))]
    (is (= #{(.getPath (io/file root "root.edn"))
             (.getPath (io/file root "sub/api.edn"))
             (.getPath (io/file root "new.edn"))}
           (set moved)))
    (is (= "{:nodes {:a {:ref \"sub/api.edn\"} :n {:ref \"new.edn\"}}}" (slurp (io/file root "root.edn"))))
    (is (= "{:nodes {:h nil :extra nil}}" (slurp (io/file root "sub/api.edn"))))
    (is (= "{:nodes {:only-in-fork nil}}" (slurp (io/file root "new.edn"))))
    (is (not (.exists (io/file root "root-next.edn"))))
    (is (not (.exists (io/file root "sub/api-next.edn"))))
    (is (not (.exists (io/file root "new-next.edn"))))
    ;; outside the closure: untouched
    (is (.exists (io/file root "other-next.edn")))
    (is (= "{}" (slurp (io/file root "other.edn"))))
    ;; nothing left to promote
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to promote"
                          (fork/promote! file "next" (fn [_]))))
    (fs/delete-tree root)))

(deftest promote-follows-an-unforked-file-through-to-forked-ones
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"mid.edn\"}}}"
                     "root-next.edn" "{:nodes {:a {:ref \"mid.edn\"}}}"
                     "mid.edn" "{:nodes {:m {:ref \"leaf.edn\"}}}"
                     "leaf.edn" "{}"
                     "leaf-next.edn" "{:nodes {:l nil}}"})
        moved (fork/promote! (.getPath (io/file root "root.edn")) "next" (fn [_]))]
    (is (= #{(.getPath (io/file root "root.edn")) (.getPath (io/file root "leaf.edn"))} (set moved)))
    (is (= "{:nodes {:l nil}}" (slurp (io/file root "leaf.edn"))))
    (fs/delete-tree root)))

(deftest suffix-validation
  (is (fork/valid-suffix? "next"))
  (is (fork/valid-suffix? "v2.1_rc-1"))
  (is (not (fork/valid-suffix? "a/b")))
  (is (not (fork/valid-suffix? "")))
  (is (not (fork/valid-suffix? nil))))
