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

(deftest readme-data-format-example-is-functional
  (assert-example-clean
   "README"
   (indented-block-after (slurp "README.md") "## Data format")))

(deftest skill-data-format-example-is-functional
  (assert-example-clean
   "SKILL.md"
   (fenced-block-after (slurp "plugins/simpleviz/skills/simpleviz/SKILL.md")
                       "## Data format (canonical map forms)")))
