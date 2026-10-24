(ns simpleviz.spell-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.spell :refer [md-parser skip-ranges]]))

(defn- skipped
  "The texts of s that the spell check skips."
  [s]
  (mapv (fn [[a b]] (.slice s a b))
        (skip-ranges (.parse md-parser s) (fn [a b] (.slice s a b)))))

(test "prose, headings and emphasis are checked"
  (fn []
    (assert/deepEqual (skipped "# A Title\n\nSome *prose* and **more**.\n\n- a list item\n") [])))

(test "code is skipped: inline, fenced and indented"
  (fn []
    (assert/deepEqual (skipped "Run `bb dev` now.\n") ["`bb dev`"])
    (assert/deepEqual (skipped "Text.\n\n```clojure\n(defn f [x])\n```\n\nMore.\n")
                      ["```clojure\n(defn f [x])\n```"])
    (assert/deepEqual (skipped "Text.\n\n    indented cde\n\nMore.\n") ["indented cde"])))

(test "link targets and bare URLs are skipped, link texts are checked"
  (fn []
    (assert/deepEqual (skipped "See [the graph](sub/grph.edn) here.\n") ["sub/grph.edn"])
    (assert/deepEqual (skipped "See ![a pic](imgs/x.png).\n") ["imgs/x.png"])
    (assert/deepEqual (skipped "Go to https://exmple.com/a now.\n") ["https://exmple.com/a"])
    (assert/deepEqual (skipped "See [the docs][dcs].\n\n[dcs]: https://x.io/y\n")
                      ["[dcs]" "[dcs]" "https://x.io/y"])))

(test "HTML is skipped"
  (fn []
    (assert/deepEqual (skipped "A <kbd>Ctrl</kbd> key.\n") ["<kbd>" "</kbd>"])
    (assert/deepEqual (skipped "<div class=\"nte\">\n\nx\n") ["<div class=\"nte\">"])))

(test "a table's formula cells are skipped, its other cells are checked"
  (fn []
    (assert/deepEqual (skipped "| item | qty |\n|------|----:|\n| tea  | 2   |\n| sum  | =(sum :b) |\n")
                      ["=(sum :b)"])))
