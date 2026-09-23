(ns cli-test
  "The simpleviz CLI run as a real process (`bb --config <repo>/bb.edn -m
  cli ...`) from a temp folder, as the launcher and the jar run it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [proc-util]))

(defn- run-cli
  "Run the CLI with `args` in `dir` (default: a fresh temp folder);
  {:out :err :exit}."
  [args & {:keys [dir]}]
  (let [tmp (or dir (fs/create-temp-dir {:prefix "cli-test"}))]
    (try
      (select-keys
       (apply p/shell {:dir (str tmp) :out :string :err :string :continue true}
              "bb" "--config" (str proc-util/repo-root "/bb.edn") "-m" "cli" args)
       [:out :err :exit])
      (finally (when-not dir (fs/delete-tree tmp))))))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir {:prefix "cli-test"})]
    (try (f tmp) (finally (fs/delete-tree tmp)))))

(deftest help-prints-usage
  (doseq [args [[] ["--help"] ["-h"]]]
    (let [res (run-cli args)]
      (is (= 0 (:exit res)))
      (is (str/starts-with? (:out res) "usage: simpleviz")))))

(deftest unknown-option-is-a-usage-error
  (let [res (run-cli ["-x"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "usage: simpleviz"))))

(deftest version-in-a-checkout-is-dev
  (is (= "simpleviz dev" (str/trim (:out (run-cli ["--version"]))))))

(deftest update-names-the-bbin-command
  (let [res (run-cli ["update"])]
    (is (= 0 (:exit res)))
    (is (str/includes? (:out res) "bbin install https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar"))))

(deftest clean-all-needs-the-launcher
  (let [res (run-cli ["clean-all"])]
    (is (= 1 (:exit res)))
    (is (= "simpleviz: clean-all needs the install.sh launcher (Linux)" (str/trim (:err res))))))

(deftest init-writes-a-clean-starter-and-refuses-to-overwrite
  (with-tmp
    (fn [tmp]
      (let [res (run-cli ["init" "g.edn"] :dir tmp)]
        (is (= 0 (:exit res)) (:err res))
        (is (= "created g.edn — view it with: simpleviz g.edn" (str/trim (:out res)))))
      (is (= "ok" (str/trim (:out (run-cli ["check" "g.edn"] :dir tmp)))))
      (let [res (run-cli ["init" "g.edn"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (str/includes? (:err res) "g.edn already exists"))))))

(deftest fork-and-promote-work-on-relative-paths
  (with-tmp
    (fn [tmp]
      (spit (str (fs/path tmp "g.edn")) "{:nodes {:a {}}}")
      (let [res (run-cli ["fork" "g.edn" "next"] :dir tmp)]
        (is (= 0 (:exit res)) (:err res))
        (is (fs/exists? (fs/path tmp "g-next.edn"))))
      (spit (str (fs/path tmp "g-next.edn")) "{:nodes {:a {} :b {}}}")
      (is (= 0 (:exit (run-cli ["promote" "g.edn" "next"] :dir tmp))))
      (is (str/includes? (slurp (str (fs/path tmp "g.edn"))) ":b")))))

(deftest fork-rejects-wrong-arg-count-and-missing-file
  (let [res (run-cli ["fork" "g.edn"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "usage:")))
  (let [res (run-cli ["promote" "nope.edn" "next"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "file not found: nope.edn"))))

(deftest extract-prints-the-embedded-edn
  (let [res (run-cli ["extract" (str proc-util/repo-root "/test/fixtures/embedded.png")])]
    (is (= 0 (:exit res)) (:err res))
    (is (str/includes? (:out res) ":nodes"))))

(deftest check-exits-1-and-prints-each-problem
  (with-tmp
    (fn [tmp]
      (spit (str (fs/path tmp "warn.edn")) "{:nodes {:a {}} :edges {[:a :zz] {}} :boxes {:b {:components #{:nope}}}}")
      (spit (str (fs/path tmp "broken.edn")) "{:nodes {:a {}")
      (let [res (run-cli ["check" "warn.edn"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (= 2 (count (filter #(str/starts-with? % "warning: ") (str/split-lines (:out res)))))))
      (let [res (run-cli ["check" "broken.edn"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (str/starts-with? (:out res) "error: "))))))

(deftest check-takes-a-file-name-starting-with-a-dash
  (with-tmp
    (fn [tmp]
      (spit (str (fs/path tmp "-g.edn")) "{:nodes {:a {}}}")
      (let [res (run-cli ["check" "-g.edn"] :dir tmp)]
        (is (= 0 (:exit res)) (str (:out res) (:err res)))
        (is (= "ok" (str/trim (:out res))))))))

(deftest check-rejects-wrong-arg-count
  (doseq [args [["check"] ["check" "a.edn" "b.edn"]]]
    (let [res (run-cli args)]
      (is (= 1 (:exit res)))
      (is (str/includes? (:err res) "usage:")))))
