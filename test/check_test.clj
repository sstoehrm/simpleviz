(ns check-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [check]))

(defn- temp-graph
  "Write EDN text to a fresh temp .edn file; returns its path."
  [text]
  (let [f (fs/file (fs/create-temp-file {:prefix "check-test" :suffix ".edn"}))]
    (.deleteOnExit f)
    (spit f text)
    (.getPath f)))

(defn- temp-folder-with
  "A fresh temp folder holding `files` (name -> text); the folder."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "check-test"})]
    (doseq [[nm text] files] (spit (fs/file dir nm) text))
    dir))

(deftest check-clean-file-reports-nothing
  (is (= {:error nil :warnings []}
         (check/check (temp-graph "{:nodes {:a {} :b {}} :edges {[:a :b] {}}}")))))

(deftest check-reports-validation-warnings
  (let [{:keys [error warnings]} (check/check (temp-graph "{:nodes {:a {}} :edges {[:a :zz] {}}}"))]
    (is (nil? error))
    (is (= 1 (count warnings)))
    (is (str/includes? (first warnings) "zz"))))

(deftest check-reports-a-parse-error
  (let [{:keys [error warnings]} (check/check (temp-graph "{:nodes {:a {}"))]
    (is (string? error))
    (is (= [] warnings))))

(deftest check-reports-content-after-the-root-map
  ;; an extra } used to hide the edges without a word (#92)
  (let [{:keys [error warnings]} (check/check (temp-graph "{:nodes {:a {} :b {}}}\n :edges {[:a :b] {}}}"))]
    (is (= "content after the end of the graph: its map closes at line 1 — check there for an extra }" error))
    (is (= [] warnings))))

(deftest check-reads-the-edn-embedded-in-an-exported-png
  (is (= {:error nil :warnings []} (check/check "test/fixtures/embedded.png"))))

(deftest check-missing-file-is-an-error
  (is (string? (:error (check/check "test/fixtures/does-not-exist.edn")))))

(deftest check-reports-a-broken-pair
  (let [dir (temp-folder-with {"g.edn" "{:nodes {:api {:pair \"missing.edn#x\"}}}"})]
    (try
      (is (= {:error nil
              :warnings ["node \"api\": pair \"missing.edn#x\": missing.edn not found"]}
             (check/check (str (fs/file dir "g.edn")))))
      (finally (fs/delete-tree dir)))))

(deftest check-accepts-a-working-pair-next-to-the-file
  (let [dir (temp-folder-with {"g.edn" "{:nodes {:api {:pair \"other.edn#b\"}}}"
                               "other.edn" "{:nodes {:b {}}}"})]
    (try
      (is (= {:error nil :warnings []} (check/check (str (fs/file dir "g.edn")))))
      (finally (fs/delete-tree dir)))))
