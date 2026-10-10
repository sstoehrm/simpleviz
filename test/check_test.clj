(ns check-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [check]
            [serve]))

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
  (is (= {:error nil :warnings []} (check/check "test/fixtures/embedded.png")))
  (is (= {:error nil :warnings []} (check/check "test/fixtures/embedded.svg"))))

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

(defn- temp-md-folder
  "A fresh folder (a subfolder of a temp folder, so ../ stays inside the
  temp tree) holding `files`; [outer folder]."
  [files]
  (let [outer (fs/create-temp-dir {:prefix "check-test"})
        dir (fs/file outer "served")]
    (fs/create-dirs dir)
    (doseq [[nm text] files] (spit (fs/file dir nm) text))
    [outer dir]))

(deftest check-md-reports-broken-links
  (let [[outer dir] (temp-md-folder
                     {"notes.md" (str "[ok](g.edn) [gone](nope.md)\n![x](../up.png)\n[r][d] [r2][d]\n\n"
                                      "[d]: missing.edn\n[web](http://x.md) [pic](p.jpg)\n")
                      "g.edn" "{:nodes {}}"})]
    (try
      (is (= {:error nil :warnings ["line 1: nope.md not found"
                                    "line 2: ../up.png leaves the served folder"
                                    "line 5: missing.edn not found"]}
             (check/check (str (fs/file dir "notes.md")))))
      (finally (fs/delete-tree outer)))))

(deftest check-md-clean-and-unreadable
  (let [[outer dir] (temp-md-folder {"ok.md" "[g](g.edn)" "g.edn" "{}"})]
    (try
      (is (= {:error nil :warnings []} (check/check (str (fs/file dir "ok.md")))))
      (spit (fs/file dir "big.md") (apply str (repeat (inc (* 1024 1024)) "x")))
      (is (re-find #"over 1 MiB" (:error (check/check (str (fs/file dir "big.md"))))))
      (is (= "no such file: nope.md" (:error (check/check "nope.md"))))
      (finally (fs/delete-tree outer)))))

(deftest check-md-reports-a-bad-path-per-link
  ;; %00 decodes to a NUL, on which the path check throws an IOException:
  ;; that link's warning, not the file's error
  (let [[outer dir] (temp-md-folder {"notes.md" "[nul](a%00.md)\n[gone](gone.md)\n[ok](g.edn)"
                                     "g.edn" "{}"})]
    (try
      (fs/create-sym-link (fs/file dir "gone.md") (fs/file outer "nowhere.md"))
      (is (= {:error nil :warnings ["line 1: a\u0000.md: Invalid file path"
                                    "line 2: gone.md is a dangling symlink"]}
             (check/check (str (fs/file dir "notes.md")))))
      (finally (fs/delete-tree outer)))))

(deftest md-warnings-name-the-dest-once
  ;; in a subfolder the path checked is docs/gone.md; the warning says gone.md
  (let [[outer dir] (temp-md-folder {})]
    (try
      (fs/create-dirs (fs/file dir "docs"))
      (fs/create-sym-link (fs/file dir "docs/gone.md") (fs/file outer "nowhere.md"))
      (is (= ["line 1: gone.md is a dangling symlink" "line 2: ../../up.md leaves the served folder"]
             (serve/md-warnings (str dir) "docs/notes.md" "[gone](gone.md)\n[up](../../up.md)")))
      (finally (fs/delete-tree outer)))))

(deftest check-md-not-utf-8
  (let [[outer dir] (temp-md-folder {})]
    (try
      (with-open [o (java.io.FileOutputStream. (fs/file dir "bad.md"))]
        (.write o (byte-array [0x5B -1 -2 0x5D])))
      (is (re-find #"not UTF-8" (:error (check/check (str (fs/file dir "bad.md"))))))
      (finally (fs/delete-tree outer)))))
