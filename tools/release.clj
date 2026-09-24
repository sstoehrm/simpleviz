(ns release
  "Cutting a release: `bb release vX.Y.Z` writes the version into both
  plugin manifests (so the skill only reaches users together with the
  release it documents), commits, tags and pushes; the tag's CI workflow
  then builds and publishes. `bb release:check vX.Y.Z` is that workflow's
  guard against a tag whose manifests carry another version."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def manifests
  "The plugin manifests whose version follows the release: Claude Code
  updates a plugin only when this version changes."
  ["plugins/simpleviz/.claude-plugin/plugin.json"
   "plugins/simpleviz/.codex-plugin/plugin.json"])

(defn valid-tag? [tag]
  (boolean (re-matches #"v\d+\.\d+\.\d+" (str tag))))

(def ^:private version-re #"\"version\"\s*:\s*\"[^\"]*\"")

(defn set-version
  "The manifest text with its top-level \"version\" set to `version`:
  replaced in place, or inserted after the \"name\" line. Everything else
  keeps its formatting."
  [text version]
  (let [kv (str "\"version\": \"" version "\"")]
    (if (re-find version-re text)
      (str/replace-first text version-re kv)
      (let [out (str/replace-first text #"(?m)^(\s*)(\"name\"\s*:\s*\"[^\"]*\",)$"
                                   (fn [[_ indent name-line]]
                                     (str indent name-line "\n" indent kv ",")))]
        (when (= out text)
          (throw (ex-info "no \"name\" line to put the version after" {})))
        out))))

(defn manifest-versions
  "{manifest path -> its version, or nil}"
  []
  (into {} (map (fn [f] [f (get (json/parse-string (slurp f)) "version")])) manifests))

(defn version-mismatches
  "One message per manifest whose version is not the tag's."
  [versions tag]
  (let [want (subs tag 1)]
    (vec (for [[f v] (sort versions)
               :when (not= v want)]
           (if v
             (str f " has " v ", the tag says " want)
             (str f " has no version, the tag says " want))))))

(defn- die [& parts]
  (binding [*out* *err*] (println (apply str "release: " parts)))
  (System/exit 1))

(defn check
  "bb release:check vX.Y.Z — exit 1 unless both manifests carry the tag's version."
  [tag]
  (when-not (valid-tag? tag) (die "usage: bb release:check vX.Y.Z"))
  (let [problems (version-mismatches (manifest-versions) tag)]
    (when (seq problems)
      (doseq [m problems] (binding [*out* *err*] (println (str "release: " m))))
      (die "cut releases with: bb release " tag))
    (println (str "ok: plugin manifests carry " (subs tag 1)))))

(defn- git [& args]
  (str/trim (:out (apply p/shell {:out :string} "git" args))))

(defn -main
  "bb release vX.Y.Z — from a clean main that matches origin/main."
  [& [tag & extra]]
  (when (or (nil? tag) (seq extra)) (die "usage: bb release vX.Y.Z"))
  (when-not (valid-tag? tag) (die "a release tag looks like v1.2.3, not " tag))
  (when-not (= "main" (git "rev-parse" "--abbrev-ref" "HEAD"))
    (die "check out main first"))
  (when (seq (git "status" "--porcelain"))
    (die "the working tree has uncommitted changes"))
  (git "fetch" "--quiet" "--tags" "origin" "main")
  (when-not (= (git "rev-parse" "HEAD") (git "rev-parse" "origin/main"))
    (die "main differs from origin/main — pull or push first"))
  (when (seq (git "tag" "--list" tag))
    (die tag " already exists"))
  (let [version (subs tag 1)]
    (doseq [f manifests]
      (spit f (set-version (slurp f) version)))
    (apply git "add" manifests)
    (when (seq (git "status" "--porcelain"))
      (git "commit" "--quiet" "-m" (str "release: " tag)))
    (git "tag" "-a" tag "-m" (str "simpleviz " tag))
    (git "push" "--atomic" "origin" "main" tag)
    (println (str "pushed main and " tag " — the release workflow builds and publishes it"))))
