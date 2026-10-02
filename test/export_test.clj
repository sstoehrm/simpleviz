(ns export-test
  "simpleviz export end to end: the CLI as a process, a real headless
  browser. Skipped when no browser is found — except on CI, which ships
  Chrome and must run it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [browser]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [embedded]
            [png]
            [proc-util]
            [svg]))

(def ^:private found (browser/find-browser))

(defn- export! [tmp & args]
  (select-keys (apply p/shell {:dir (str tmp) :out :string :err :string :continue true}
                      "bb" "--config" (str proc-util/repo-root "/bb.edn") "-m" "cli" "export" args)
               [:out :err :exit]))

(defmacro ^:private e2e [name & body]
  `(deftest ~name
     (cond (:path found)
           (let [~'tmp (fs/create-temp-dir {:prefix "export-test"})]
             (try (fs/copy-tree (str proc-util/repo-root "/examples") ~'tmp) ~@body
                  (finally (fs/delete-tree ~'tmp))))
           (System/getenv "CI") (is false (str "CI needs a browser: " (:error found)))
           :else (println "export-test: no browser found, skipped"))))

(defn- at [tmp f] (str (fs/path tmp f)))

(e2e png-and-svg-carry-the-source
  (let [r (export! tmp "demo.edn" "demo.png")]
    (is (= 0 (:exit r)) (:err r))
    (is (= "wrote demo.png" (str/trim (:out r)))))
  (is (png/png? (at tmp "demo.png")))
  (is (= (slurp (at tmp "demo.edn")) (embedded/extract (at tmp "demo.png") "simpleviz-edn")))
  (is (= 0 (:exit (export! tmp "demo.edn" "demo.svg"))))
  (is (= (slurp (at tmp "demo.edn")) (svg/extract (at tmp "demo.svg") "simpleviz-edn"))))

(e2e a-compare-export-embeds-both-sides
  (is (= 0 (:exit (export! tmp "demo.edn" "next" "diff.svg"))))
  (is (= (slurp (at tmp "demo.edn")) (svg/extract (at tmp "diff.svg") "simpleviz-edn-old")))
  (is (= (slurp (at tmp "demo-next.edn")) (svg/extract (at tmp "diff.svg") "simpleviz-edn-new"))))

(e2e the-theme-paints-the-export
  (is (= 0 (:exit (export! tmp "demo.edn" "nord.svg" "--theme" "nord"))))
  (is (str/includes? (slurp (at tmp "nord.svg")) "fill=\"#2e3440\"")))

(e2e a-big-graph-exports-expanded
  ;; > 500 nodes opens as a collapsed overview; the export must not
  (let [nodes (into {} (for [i (range 520)] [(keyword (str "n" i)) {}]))]
    (spit (at tmp "big.edn") (pr-str {:nodes nodes :boxes {:big {:components (set (keys nodes))}}}))
    (let [r (export! tmp "big.edn" "big.svg")]
      (is (= 0 (:exit r)) (:err r)))
    (is (str/includes? (slurp (at tmp "big.svg")) ">n519<"))))

(e2e a-graph-error-is-the-pages-message
  (spit (at tmp "broken.edn") "{:nodes {:a {}} :edges [[:a")
  (let [r (export! tmp "broken.edn" "x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: Graph error:") (:err r))
    (is (not (fs/exists? (at tmp "x.png"))))))

(e2e an-unwritable-output-is-a-clear-error
  (let [r (export! tmp "demo.edn" "no/such/dir/x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: cannot write no/such/dir/x.png") (:err r))))
