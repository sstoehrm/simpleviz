(ns simpleviz.mdsource-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.mdsource :refer [lines]]))

(defn- text [toks] (.join (mapv (fn [t] (if (string? t) t (nth t 2))) toks) ""))

(defn- spans
  "The classed tokens of line i of s's highlighting, as [class text]."
  [s i]
  (->> (nth (lines s) i)
       (remove string?)
       (mapv (fn [t] [(.slice (:class (nth t 1)) 3) (nth t 2)]))))

(def SAMPLE
  (str "# Title\n\nSome *em* and **strong**, `code`, a_b_c and 2 * 3.\n"
       "- [ ] task with [a link](sub/g.edn \"t\") and ![img](x.png)\n"
       "  1. nested <https://x.org> or https://y.org/p\n"
       "> quote with [ref][r] and a note[^1] \\*not em\\*\n"
       "<!-- a\ncomment --> after <b>bold</b>\n"
       "| a | b |\n|---|:-:|\n| `x|y` | 2 \\| 3 |\n\n"
       "```clojure\n(defn f [x] \"s\n t\")\n```\n"
       "~~~\nplain ~~~ body\n~~~\n"
       "[r]: https://r.org\n[^1]: The note.\n---\n"
       "```js\nunclosed // c\n"))

(test "every line's tokens join to the line again"
      (fn []
        (let [ls (.split SAMPLE "\n")
              out (lines SAMPLE)]
          (assert/equal (count out) (count ls))
          (dotimes [i (count ls)]
            (assert/equal (text (nth out i)) (nth ls i) (str "line " i))))))

(test "the empty text is one empty line"
      (fn [] (assert/deepEqual (lines "") [[]])))

(test "a heading is heading"
      (fn [] (assert/deepEqual (spans SAMPLE 0) [["heading" "# Title"]])))

(test "emphasis delimiters and code spans; intraword _ and a lone * are text"
      (fn []
        (assert/deepEqual (spans SAMPLE 2)
                          [["mark" "*"] ["mark" "*"] ["mark" "**"] ["mark" "**"] ["code" "`code`"]])))

(test "list and task markers, link text and targets"
      (fn []
        (assert/deepEqual (spans SAMPLE 3)
                          [["mark" "- "] ["mark" "[ ]"]
                           ["mark" "["] ["link" "a link"] ["mark" "]"] ["url" "(sub/g.edn \"t\")"]
                           ["mark" "!["] ["link" "img"] ["mark" "]"] ["url" "(x.png)"]])
        (assert/deepEqual (spans SAMPLE 4)
                          [["mark" "1. "] ["url" "<https://x.org>"] ["url" "https://y.org/p"]])))

(test "quote markers, reference links and notes; escapes stay text"
      (fn []
        (assert/deepEqual (spans SAMPLE 5)
                          [["mark" "> "] ["mark" "["] ["link" "ref"] ["mark" "]"] ["url" "[r]"]
                           ["link" "[^1]"]])))

(test "an HTML comment runs across lines; tags are tag"
      (fn []
        (assert/deepEqual (spans SAMPLE 6) [["comment" "<!-- a"]])
        (assert/deepEqual (spans SAMPLE 7) [["comment" "comment -->"] ["tag" "<b>"] ["tag" "</b>"]])))

(test "a table's pipes and separator row are marks, not a pipe inside code"
      (fn []
        (assert/deepEqual (spans SAMPLE 8) [["mark" "|"] ["mark" "|"] ["mark" "|"]])
        (assert/deepEqual (spans SAMPLE 9) [["mark" "|---|:-:|"]])
        (assert/deepEqual (spans SAMPLE 10) [["mark" "|"] ["code" "`x|y`"] ["mark" "|"] ["mark" "|"]])))

(test "a fence's body is highlighted by its language, a string across lines split"
      (fn []
        (assert/deepEqual (spans SAMPLE 12) [["mark" "```clojure"]])
        (assert/deepEqual (spans SAMPLE 13) [["keyword" "defn"] ["string" "\"s"]])
        (assert/deepEqual (spans SAMPLE 14) [["string" " t\""]])
        (assert/deepEqual (spans SAMPLE 15) [["mark" "```"]])))

(test "a fence without a language is code, closed only by its own mark"
      (fn []
        (assert/deepEqual (spans SAMPLE 17) [["code" "plain ~~~ body"]])
        (assert/deepEqual (spans SAMPLE 18) [["mark" "~~~"]])))

(test "definitions and rules"
      (fn []
        (assert/deepEqual (spans SAMPLE 19) [["link" "[r]:"] ["url" " https://r.org"]])
        (assert/deepEqual (spans SAMPLE 20) [["link" "[^1]:"]])
        (assert/deepEqual (spans SAMPLE 21) [["mark" "---"]])))

(test "an unclosed fence runs to the end"
      (fn [] (assert/deepEqual (spans SAMPLE 23) [["comment" "// c"]])))

(test "an unclosed HTML comment ends at a blank line"
      (fn []
        (let [s "a <!-- open\nstill\n\n# Heading"]
          (assert/deepEqual (spans s 1) [["comment" "still"]])
          (assert/deepEqual (spans s 3) [["heading" "# Heading"]]))))

(test "unmatched brackets stay linear"
      (fn []
        (let [s (str (.repeat "[" 20000) (.repeat "[x](" 3000))
              t0 (js/performance.now)
              out (lines s)]
          (assert/equal (text (first out)) s)
          (assert/ok (< (- (js/performance.now) t0) 200)))))
