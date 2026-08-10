(ns docs-test
  "The data-format examples in the README and the plugin skill must be
  self-contained: normalize accepts them with zero warnings. Guards
  against docs drifting from behavior (issue #29)."
  (:require [clojure.edn :as edn]
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

(defn- heredoc-block
  "Body of the first <<'MARKER' heredoc in the text."
  [text marker]
  (->> (str/split-lines text)
       (drop-while (fn [l] (not (str/includes? l (str "<<'" marker "'")))))
       (rest)
       (take-while (fn [l] (not= l marker)))
       (str/join "\n")))

(deftest init-template-is-functional
  (assert-example-clean
   "install.sh init template"
   (heredoc-block (slurp "install.sh") "TEMPLATE")))

(deftest third-party-notices-cover-vendored-components
  (let [notices (slurp "THIRD-PARTY-NOTICES.md")
        vendored (concat ["elk.bundled.js"]
                         (if-let [fs (seq (.listFiles (java.io.File. "public/js/vendor")))]
                           (map (fn [f] (.getName f)) fs)
                           ;; before a build the copied packages are absent;
                           ;; pin the known ones so the check still bites
                           ["reagami" "squint-cljs"]))]
    (doseq [v vendored]
      (is (str/includes? notices v)
          (str "THIRD-PARTY-NOTICES.md must mention vendored component " v)))
    (is (str/includes? notices "Eclipse Public License - v 2.0"))))

(deftest bundle-ships-license-files
  (let [bb (slurp "bb.edn")]
    (is (str/includes? bb "THIRD-PARTY-NOTICES.md")
        "bundle task must copy THIRD-PARTY-NOTICES.md")
    (is (str/includes? bb "\"LICENSE\"")
        "bundle task must copy LICENSE")))

(deftest readme-data-format-example-is-functional
  (assert-example-clean
   "README"
   (indented-block-after (slurp "README.md") "## Data format")))

(deftest skill-data-format-example-is-functional
  (assert-example-clean
   "SKILL.md"
   (fenced-block-after (slurp "plugins/simpleviz/skills/simpleviz/SKILL.md")
                       "## Data format (canonical map forms)")))
