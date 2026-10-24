(ns simpleviz.spell
  "Where the browser's spell check stays out of a markdown text: its
  code, link targets, URLs, HTML and table formulas — none of them
  prose, all of them full of words no dictionary knows."
  (:require ["@lezer/markdown" :refer [parser GFM]]))

(def md-parser
  "The markdown parser the doc editor uses: CommonMark with GitHub's
  tables, task lists, strikethrough and bare URLs."
  (.configure parser GFM))

(def ^:private SKIP
  "The tree's nodes that are never prose; a link's text is, its
  destination (URL) and reference label are not."
  #{"FencedCode" "CodeBlock" "InlineCode" "URL" "Autolink" "LinkLabel"
    "HTMLBlock" "HTMLTag" "CommentBlock" "ProcessingInstructionBlock"})

(defn skip-ranges
  "The [from to] ranges of a markdown syntax tree to keep out of the
  spell check, in order. `slice` gives the text between two offsets."
  [tree slice]
  (let [out #js []]
    (.iterate tree
              {:enter (fn [node]
                        (let [nm (.-name node) a (.-from node) b (.-to node)]
                          (when (or (contains? SKIP nm)
                                    ;; a cell starting =( is a formula, plain Clojure
                                    (and (= "TableCell" nm) (.startsWith (slice a b) "=(")))
                            (.push out [a b])
                            false)))})
    (vec out)))
