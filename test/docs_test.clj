(ns docs-test
  "The data-format examples in docs/diagrams.md and the plugin skill must be
  self-contained: normalize accepts them with zero warnings. Guards
  against docs drifting from behavior (issue #29)."
  (:require [cheshire.core :as json]
            [cli]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [graph]))

(defn- indented-block-after
  "First 4-space-indented block after the given heading line, dedented."
  [text heading]
  (->> (str/split-lines text)
       (drop-while (fn [l] (not= l heading)))
       (rest)
       (drop-while str/blank?)
       (take-while (fn [l] (or (str/blank? l) (str/starts-with? l "    "))))
       (map (fn [l] (if (>= (count l) 4) (subs l 4) l)))
       (str/join "\n")))

(defn- fenced-block-after
  "First ```-fenced block after the given heading line."
  [text heading]
  (->> (str/split-lines text)
       (drop-while (fn [l] (not= l heading)))
       (rest)
       (drop-while (fn [l] (not (str/starts-with? l "```"))))
       (rest)
       (take-while (fn [l] (not (str/starts-with? l "```"))))
       (str/join "\n")))

(defn- assert-example-clean [source example]
  (let [g (graph/normalize (edn/read-string example))]
    (is (= [] (:warnings g)) (str source " example must normalize warning-free"))
    (is (pos? (count (:nodes g))) (str source " example has nodes"))
    (is (pos? (count (:edges g))) (str source " example keeps its edges"))
    (is (pos? (count (:boxes g))) (str source " example keeps its boxes"))))

(deftest shipped-templates-are-functional
  (doseq [t cli/shipped-templates :when (str/ends-with? t ".edn")]
    (assert-example-clean (str "template " t) (slurp (str "templates/" t)))))

(deftest third-party-notices-cover-vendored-components
  (let [notices (slurp "THIRD-PARTY-NOTICES.md")
        vendored (concat ["elk.bundled.js" "elk-worker.min.js"]
                         (if-let [fs (seq (.listFiles (java.io.File. "public/js/vendor")))]
                           (map (fn [f] (.getName f)) fs)
                           ;; before a build the copied packages are absent;
                           ;; pin the known ones so the check still bites
                           ["reagami" "squint-cljs"]))]
    (doseq [v vendored]
      (is (str/includes? notices v)
          (str "THIRD-PARTY-NOTICES.md must mention vendored component " v)))
    (is (str/includes? notices "Eclipse Public License - v 2.0"))))

(deftest the-plugin-carries-its-license-security-policy-and-readme
  ;; a plugin install copies plugins/simpleviz alone, not the repository
  (is (= (slurp "LICENSE") (slurp "plugins/simpleviz/LICENSE"))
      "plugins/simpleviz/LICENSE must be the repository's LICENSE")
  (is (= (slurp "SECURITY.md") (slurp "plugins/simpleviz/SECURITY.md"))
      "plugins/simpleviz/SECURITY.md must be the repository's SECURITY.md")
  (is (.isFile (io/file "plugins/simpleviz/README.md")))
  (doseq [m ["plugins/simpleviz/.claude-plugin/plugin.json" "plugins/simpleviz/.codex-plugin/plugin.json"]]
    (is (= "MIT" (get (json/parse-string (slurp m)) "license")) m)))

(deftest the-codex-listing-names-what-is-there
  ;; the icon and screenshots it names ship in the plugin folder; its
  ;; privacy and terms links point at files of this repository
  (let [m (json/parse-string (slurp "plugins/simpleviz/.codex-plugin/plugin.json"))
        ui (get m "interface")]
    (doseq [rel (concat [(get ui "composerIcon") (get ui "logo")] (get ui "screenshots"))]
      (is (.isFile (io/file "plugins/simpleviz" rel)) rel))
    (doseq [[k f] [["privacyPolicyURL" "PRIVACY.md"] ["termsOfServiceURL" "LICENSE"]]]
      (is (= (str "https://github.com/sstoehrm/simpleviz/blob/main/" f) (get ui k)) k)
      (is (.isFile (io/file f)) f))
    (is (seq (get m "keywords")))))

(deftest bundle-ships-license-files
  (let [bb (slurp "bb.edn")]
    (is (str/includes? bb "THIRD-PARTY-NOTICES.md")
        "bundle task must copy THIRD-PARTY-NOTICES.md")
    (is (str/includes? bb "\"LICENSE\"")
        "bundle task must copy LICENSE")))

(deftest bundle-and-jar-ship-the-templates
  (let [bb (slurp "bb.edn")]
    (is (str/includes? bb "[\"public\" \"server\" \"examples\" \"templates\"]")
        "bundle task must copy templates/")
    (is (str/includes? bb "(fs/copy-tree \"templates\" (fs/path res \"templates\"))")
        "jar task must copy templates/")))

(deftest diagrams-data-format-example-is-functional
  (assert-example-clean
   "docs/diagrams.md"
   (indented-block-after (slurp "docs/diagrams.md") "## Data format")))

(deftest skill-data-format-example-is-functional
  (assert-example-clean
   "SKILL.md"
   (fenced-block-after (slurp "plugins/simpleviz/skills/simpleviz/SKILL.md")
                       "## Data format (canonical map forms)")))
