(ns analyze-test
  (:require [analyze]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [serve]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- tree!
  "A temp folder holding `files` (rel → text); its canonical File."
  [files]
  (let [dir (.getCanonicalFile (.toFile (fs/create-temp-dir)))]
    (doseq [[rel text] files]
      (let [f (java.io.File. dir ^String rel)]
        (.mkdirs (.getParentFile f))
        (spit f text)))
    dir))

(defn- block [& lines]
  (str "# doc\n\n```simpleviz-analyzer\n" (str/join "\n" lines) "\n```\n"))

(def ^:private src
  {"src/app/core.clj" "(ns app.core (:require [app.util :as u])) (defn run [] (u/go))"
   "src/app/util.clj" "(ns app.util) (defn go [] 1)"})

(deftest a-block-is-a-folder-then-pattern-fn-pairs
  (is (= {:source "./src/" :globs [["**.clj"] ["**.cljs" "**.cljc"]]}
         (select-keys (analyze/parse-block [">> ./src/"
                                            "\"**.clj\" #(-> % clj-module public-namespace-graph)"
                                            "[\"**.cljs\" \"**.cljc\"] identity"])
                      [:source :globs])))
  (is (= "src" (:source (analyze/parse-block [">> [the code](src)" "\"*\" identity"]))))
  (testing "mistakes say what a block holds"
    (is (thrown-with-msg? Exception #">> " (analyze/parse-block ["\"*\" identity"])))
    (is (thrown-with-msg? Exception #"pairs" (analyze/parse-block [">> src" "\"*\""])))
    (is (thrown-with-msg? Exception #"glob" (analyze/parse-block [">> src" "[pattern] identity"])))
    (is (thrown-with-msg? Exception #"read" (analyze/parse-block [">> src" "\"*\" (identity"])))))

(deftest blocks-are-numbered-from-one
  (let [text (str (block ">> a" "\"*\" identity") "\n> ```simpleviz-analyzer\n> >> q\n> ```\n"
                  (block ">> b" "\"*\" identity"))]
    (is (= [">> a" "\"*\" identity"] (analyze/block-of text 1)))
    (is (= [">> b" "\"*\" identity"] (analyze/block-of text 2)) "one in a quote isn't counted")
    (is (thrown-with-msg? Exception #"no analyzer block 3" (analyze/block-of text 3)))))

(deftest files-come-from-the-folder-under-the-root
  (let [root (tree! (merge src {"src/.hidden/x.clj" "(ns h)"
                                "src/node_modules/m.clj" "(ns m)"
                                "src/notes.md" "# hi"
                                "secret.clj" "(ns secret)"}))]
    (fs/create-sym-link (fs/path root "src" "link.clj") (fs/path root "secret.clj"))
    (is (= ["app/core.clj" "app/util.clj"]
           (mapv :path (first (analyze/matching root "docs/doc.md" "../src/" [["**.clj"]])))))
    (is (= ["notes.md"] (mapv :path (first (analyze/matching root "doc.md" "src" [["*.md"]])))))
    (is (thrown-with-msg? Exception #"leaves the served folder"
                          (analyze/matching root "doc.md" "../" [["**"]])))
    (is (thrown-with-msg? Exception #"no folder"
                          (analyze/matching root "doc.md" "gone" [["**"]])))))

(deftest a-block-runs-into-a-graph
  (let [root (tree! (assoc src "doc.md" (block ">> src" "\"**.clj\" #(->> % clj-module public-namespace-graph)")))
        g (edn/read-string (analyze/graph-text root "doc.md" 1))]
    (is (= #{"app.core/run" "app.util/go"} (set (keys (:nodes g)))))
    (is (= {["app.core/run" "app.util/go"] {:direction :->}} (:edges g)))))

(deftest pairs-merge-into-one-graph
  (let [root (tree! (assoc src "doc.md" (block ">> src"
                                               "\"app/core.clj\" #(->> % clj-module public-namespace-graph)"
                                               "\"app/util.clj\" (fn [fs] {:nodes {:n {:name (:path (first fs))}}})")))
        g (edn/read-string (analyze/graph-text root "doc.md" 1))]
    (is (= #{"app.core/run" :n} (set (keys (:nodes g)))))))

(deftest a-failing-block-says-why
  (let [run (fn [line] (let [root (tree! (assoc src "doc.md" (block ">> src" line)))]
                         (try (analyze/graph-text root "doc.md" 1) nil
                              (catch Exception e (ex-message e)))))]
    (is (re-find #"boom" (run "\"**\" (fn [_] (throw (ex-info \"boom\" {})))")))
    (is (re-find #"graph" (run "\"**\" (fn [_] [1 2])")) "a result that is no map")
    (testing "a fn reads no files itself"
      (is (re-find #"slurp" (run "\"**\" (fn [_] (slurp \"/etc/hostname\"))"))))
    (testing "a fn that runs on is stopped"
      (binding [analyze/*child-ms* 1500]
        (is (re-find #"too long" (run "\"**\" (fn [_] (loop [] (recur)))")))))))

(deftest the-stamp-moves-with-the-files
  (let [root (tree! (assoc src "doc.md" (block ">> src" "\"**.clj\" identity")))
        f (java.io.File. root "src/app/util.clj")
        s1 (analyze/stamp root "doc.md" 1)]
    (is (= s1 (analyze/stamp root "doc.md" 1)))
    (spit f "(ns app.util) (defn go [] 2) (defn more [] 3)")
    (.setLastModified f (+ 5000 (.lastModified f)))
    (is (= s1 (analyze/stamp root "doc.md" 1)) "a second look within *stamp-ms* is the first")
    (binding [analyze/*stamp-ms* 0]
      (is (not= s1 (analyze/stamp root "doc.md" 1))))))

;; ---------------------------------------------------------------- the routes

(defn- serve-at! [root]
  (reset! serve/files {:root (str (java.io.File. ^java.io.File root "root.edn")) :suffix nil})
  (reset! serve/root-dir root))

(defn- get-body [uri q] (:body (serve/handler {:uri uri :request-method :get :query-string q})))

(defn- get-json [uri q] (json/parse-string (get-body uri q) true))

(deftest the-graph-route-serves-a-block
  (let [root (tree! (assoc src "docs/doc.md" (block ">> ../src" "\"**.clj\" #(->> % clj-module public-namespace-graph)")))
        q "file=docs%2Fdoc.md%23analyzer-1"]
    (serve-at! root)
    (let [out (get-json "/api/graph" q)]
      (is (nil? (:error out)))
      (is (= #{:app.core/run :app.util/go} (set (keys (:nodes out)))))
      (is (false? (:editable out)) "a generated graph isn't edited"))
    (is (string? (:mtime (get-json "/api/version" q))))
    (is (str/includes? (get-body "/api/source" q) "app.core/run") "its source is the generated EDN")
    (testing "refusals"
      (is (re-find #"no analyzer block 2" (:error (get-json "/api/graph" "file=docs%2Fdoc.md%23analyzer-2"))))
      (is (re-find #"no such file" (:error (get-json "/api/graph" "file=gone.md%23analyzer-1"))))
      (is (re-find #"leaves" (:error (get-json "/api/graph" "file=..%2Fx.md%23analyzer-1")))))))
