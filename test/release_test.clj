(ns release-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [proc-util]
            [release]))

(def ^:private without-version
  "{\n  \"name\": \"simpleviz\",\n  \"description\": \"x\",\n  \"skills\": [\"./skills/\"]\n}\n")

(deftest set-version-inserts-a-missing-version-after-the-name
  (is (= "{\n  \"name\": \"simpleviz\",\n  \"version\": \"1.2.3\",\n  \"description\": \"x\",\n  \"skills\": [\"./skills/\"]\n}\n"
         (release/set-version without-version "1.2.3"))))

(deftest set-version-replaces-an-existing-version-in-place
  (let [text "{\n  \"name\": \"simpleviz\",\n  \"version\": \"0.3.0\",\n  \"x\": [\"a\", \"b\"]\n}\n"]
    (is (= "{\n  \"name\": \"simpleviz\",\n  \"version\": \"2.0.0\",\n  \"x\": [\"a\", \"b\"]\n}\n"
           (release/set-version text "2.0.0")))))

(deftest version-mismatches-name-each-manifest-that-differs-from-the-tag
  (is (= [] (release/version-mismatches {"a.json" "0.19.0" "b.json" "0.19.0"} "v0.19.0")))
  (is (= ["b.json has 0.3.0, the tag says 0.19.0"]
         (release/version-mismatches {"a.json" "0.19.0" "b.json" "0.3.0"} "v0.19.0")))
  (is (= ["a.json has no version, the tag says 0.19.0"]
         (release/version-mismatches {"a.json" nil} "v0.19.0"))))

(deftest release-tags-are-vX-Y-Z
  (is (release/valid-tag? "v0.19.0"))
  (doseq [bad ["0.19.0" "v0.19" "v0.19.0-rc1" "v01.2.3x" ""]]
    (is (not (release/valid-tag? bad)) bad)))

(deftest the-plugin-manifests-carry-one-release-version
  (let [versions (map (fn [f] (get (json/parse-string (slurp f)) "version"))
                      release/manifests)]
    (is (= 2 (count versions)))
    (is (apply = versions) (str/join ", " versions))
    (is (re-matches #"\d+\.\d+\.\d+" (str (first versions))))))

(defn- sh
  "Run a command in `dir`; {:exit :out :err}, never throws."
  [dir & args]
  (select-keys (apply p/shell {:dir (str dir) :out :string :err :string :continue true} args)
               [:exit :out :err]))

(defn- git-out [dir & args]
  (str/trim (:out (apply sh dir "git" args))))

(deftest bb-release-bumps-commits-tags-and-pushes
  ;; a throwaway origin, so the real task runs end to end without GitHub
  (let [tmp (fs/create-temp-dir {:prefix "release-test"})
        origin (fs/path tmp "origin.git")
        work (fs/path tmp "work")
        claude "plugins/simpleviz/.claude-plugin/plugin.json"
        codex "plugins/simpleviz/.codex-plugin/plugin.json"
        release (fn [& args]
                  (apply sh work "bb" "--config" (str proc-util/repo-root "/bb.edn") "release" args))]
    (try
      (sh tmp "git" "init" "--quiet" "--bare" "-b" "main" (str origin))
      (sh tmp "git" "clone" "--quiet" (str origin) (str work))
      (sh work "git" "config" "user.name" "Release Test")
      (sh work "git" "config" "user.email" "release-test@example.invalid")
      (sh work "git" "checkout" "--quiet" "-b" "main")
      (fs/create-dirs (fs/parent (fs/path work claude)))
      (fs/create-dirs (fs/parent (fs/path work codex)))
      (spit (str (fs/path work claude)) "{\n  \"name\": \"simpleviz\",\n  \"description\": \"x\"\n}\n")
      (spit (str (fs/path work codex)) "{\n  \"name\": \"simpleviz\",\n  \"version\": \"0.1.0\"\n}\n")
      (sh work "git" "add" ".")
      (sh work "git" "commit" "--quiet" "-m" "init")
      (sh work "git" "push" "--quiet" "-u" "origin" "main")
      (let [res (release "v1.2.3")]
        (is (= 0 (:exit res)) (:err res)))
      (is (= "release: v1.2.3" (git-out origin "log" "-1" "--format=%s" "main")))
      (is (= "simpleviz v1.2.3" (git-out origin "for-each-ref" "refs/tags/v1.2.3" "--format=%(contents:subject)")))
      (is (= "tag" (git-out origin "cat-file" "-t" "v1.2.3")) "an annotated tag")
      (doseq [f [claude codex]]
        (is (= "1.2.3" (get (json/parse-string (git-out origin "show" (str "main:" f))) "version")) f))
      (let [res (release "v1.2.3")]
        (is (= 1 (:exit res)))
        (is (str/includes? (:err res) "v1.2.3 already exists")))
      (spit (str (fs/path work "stray.txt")) "x")
      (let [res (release "v1.2.4")]
        (is (= 1 (:exit res)))
        (is (str/includes? (:err res) "uncommitted changes")))
      (finally (fs/delete-tree tmp)))))
