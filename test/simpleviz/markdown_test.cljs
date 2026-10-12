(ns simpleviz.markdown-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.markdown :refer [render]]))

(defn- link [l kids] (into [:a {:href (:dest l) :kind (:kind l)}] kids))

(defn- md [text] (render text link))

(test "headings, levels 1 to 6, closing hashes dropped"
  (fn []
    (assert/deepEqual (md "# One\n\n### Three ##\n###### Six")
                      [[:h1 "One"] [:h3 "Three"] [:h6 "Six"]])
    (assert/deepEqual (md "#no space") [[:p "#no space"]])))

(test "paragraphs join their lines; blank lines split them"
  (fn []
    (assert/deepEqual (md "one\ntwo\n\nthree")
                      [[:p "one\ntwo"] [:p "three"]])))

(test "code spans, with as many backticks as open them"
  (fn []
    (assert/deepEqual (md "a `b` c ``d`e`` `open")
                      [[:p "a " [:code "b"] " c " [:code "d`e"] " `open"]])
    (assert/deepEqual (md "`` `x` ``") [[:p [:code "`x`"]]])))

(test "backslash escapes"
  (fn []
    (assert/deepEqual (md "\\# not \\`code\\`") [[:p "# not `code`"]])))

(test "inline links, images and links around code"
  (fn []
    (assert/deepEqual (md "see [the graph](sub/a.edn) and ![pic](x.png)")
                      [[:p "see " [:a {:href "sub/a.edn" :kind "inline"} "the graph"]
                        " and " [:a {:href "x.png" :kind "image"} "pic"]]])
    (assert/deepEqual (md "[`f r`](<a b.edn> \"title\")")
                      [[:p [:a {:href "a b.edn" :kind "inline"} [:code "f r"]]]])
    (assert/deepEqual (md "[a](x.md#part) [b](https://e.org/p?q=1#f)")
                      [[:p [:a {:href "x.md" :kind "inline"} "a"] " "
                        [:a {:href "https://e.org/p?q=1#f" :kind "inline"} "b"]]])))

(test "an unclosed bracket stays text"
  (fn []
    (assert/deepEqual (md "[a] [b](c") [[:p "[a] [b](c"]])))

(test "reference links resolve against definitions anywhere, which don't show"
  (fn []
    (assert/deepEqual (md "[full][Tok] [coll][] [tok]\n\n[tok]: d/tokens.edn \"T\"\n[coll]: c.md")
                      [[:p [:a {:href "d/tokens.edn" :kind "ref"} "full"] " "
                        [:a {:href "c.md" :kind "ref"} "coll"] " "
                        [:a {:href "d/tokens.edn" :kind "ref"} "tok"]]])))

(test "autolinks"
  (fn []
    (assert/deepEqual (md "<https://e.org>")
                      [[:p [:a {:href "https://e.org" :kind "inline"} "https://e.org"]]])))

(test "fenced code keeps its text verbatim"
  (fn []
    (assert/deepEqual (md "```clojure\n(+ 1 2)\n\n# no heading\n```\nafter")
                      [[:pre [:code "(+ 1 2)\n\n# no heading"]] [:p "after"]])
    (assert/deepEqual (md "~~~\n```\n~~~") [[:pre [:code "```"]]])
    (assert/deepEqual (md "```\nopen to the end") [[:pre [:code "open to the end"]]])))

(test "indented code, its blank lines inside kept"
  (fn []
    (assert/deepEqual (md "text\n\n    a\n\n      b\n\nmore")
                      [[:p "text"] [:pre [:code "a\n\n  b"]] [:p "more"]])
    (assert/deepEqual (md "para\n    still para") [[:p "para\nstill para"]])))

(test "tight lists, nested, ordered with a start"
  (fn []
    (assert/deepEqual (md "- a\n- b `c`\n  - d\n- e\n  continued")
                      [[:ul [:li "a"] [:li "b " [:code "c"] [:ul [:li "d"]]] [:li "e\ncontinued"]]])
    (assert/deepEqual (md "1. one\n2. two") [[:ol [:li "one"] [:li "two"]]])
    (assert/deepEqual (md "3) three") [[:ol {:start 3} [:li "three"]]])))

(test "a list item's lazy line continues it; a blank line and text end the list"
  (fn []
    (assert/deepEqual (md "- a\nlazy\n\nafter")
                      [[:ul [:li "a\nlazy"]] [:p "after"]])))

(test "a loose list keeps its paragraphs; code inside an item"
  (fn []
    (assert/deepEqual (md "- a\n\n- b\n\n      code")
                      [[:ul [:li [:p "a"]] [:li [:p "b"] [:pre [:code "code"]]]]])))

(test "a list interrupts a paragraph; a heading too"
  (fn []
    (assert/deepEqual (md "text\n- a\n## H")
                      [[:p "text"] [:ul [:li "a"]] [:h2 "H"]])))

(test "tables: header, alignment, cells padded to the header, escaped pipes"
  (fn []
    (assert/deepEqual (md "| a | b | c |\n|:--|:-:|--:|\n| 1 | `x\\|y` |\n| [l](g.edn) | 2 | 3 | 4 |\n\nafter")
                      [[:table
                        [:thead [:tr [:th {:class "al-left"} "a"] [:th {:class "al-center"} "b"]
                                 [:th {:class "al-right"} "c"]]]
                        [:tbody
                         [:tr [:td {:class "al-left"} "1"] [:td {:class "al-center"} [:code "x|y"]]
                          [:td {:class "al-right"}]]
                         [:tr [:td {:class "al-left"} [:a {:href "g.edn" :kind "inline"} "l"]]
                          [:td {:class "al-center"} "2"] [:td {:class "al-right"} "3"]]]]
                       [:p "after"]])
    (assert/deepEqual (md "a | b\n--|--\n1 | 2")
                      [[:table [:thead [:tr [:th "a"] [:th "b"]]] [:tbody [:tr [:td "1"] [:td "2"]]]]])
    (assert/deepEqual (md "a | b\n--|--|--") [[:p "a | b\n--|--|--"]])))

(test "the intro's shapes: list items with links across lines"
  (fn []
    (assert/deepEqual (md "- ⇩ exports; try\n  [an export](overview.png): it\n  opens.")
                      [[:ul [:li "⇩ exports; try\n" [:a {:href "overview.png" :kind "inline"} "an export"]
                             ": it\nopens."]]])))

(test "nothing renders nothing"
  (fn [] (assert/deepEqual (md "") []) (assert/deepEqual (md "\n\n") [])))

(test "emphasis: * and _, one for em, two for strong, three for both"
  (fn []
    (assert/deepEqual (md "**bold** and *it* and __b__ and _i_")
                      [[:p [:strong "bold"] " and " [:em "it"] " and " [:strong "b"] " and " [:em "i"]]])
    (assert/deepEqual (md "***both***") [[:p [:em [:strong "both"]]]])
    (assert/deepEqual (md "*a **b** c*") [[:p [:em "a " [:strong "b"] " c"]]])
    (assert/deepEqual (md "*a\nb*") [[:p [:em "a\nb"]]])))

(test "emphasis needs flanking delimiters; _ never works inside a word"
  (fn []
    (assert/deepEqual (md "2 * 3 * 4") [[:p "2 * 3 * 4"]])
    (assert/deepEqual (md "snake_case_name") [[:p "snake_case_name"]])
    (assert/deepEqual (md "a*b*c") [[:p "a" [:em "b"] "c"]])
    (assert/deepEqual (md "**bold* x") [[:p "*" [:em "bold"] " x"]])
    (assert/deepEqual (md "*open") [[:p "*open"]])))

(test "escaped delimiters and code stay text; emphasis works in link text"
  (fn []
    (assert/deepEqual (md "\\*not\\* `*x*`") [[:p "*not* " [:code "*x*"]]])
    (assert/deepEqual (md "[*a*](x.md)") [[:p [:a {:href "x.md" :kind "inline"} [:em "a"]]]])
    (assert/deepEqual (md "*[a](x.md)*") [[:p [:em [:a {:href "x.md" :kind "inline"} "a"]]]])))

(test "strikethrough takes two tildes"
  (fn []
    (assert/deepEqual (md "~~gone~~ ~one~ ~~~three~~~")
                      [[:p [:del "gone"] " ~one~ ~~~three~~~"]])))

(test "hard line breaks: two spaces or a backslash at the end of a line"
  (fn []
    (assert/deepEqual (md "one  \ntwo\\\nthree\nfour  ")
                      [[:p "one" [:br] "two" [:br] "three\nfour"]])))

(test "blockquotes: nested, lazy, with blocks inside; they interrupt a paragraph"
  (fn []
    (assert/deepEqual (md "> quote\n> more\n\n> - item")
                      [[:blockquote [:p "quote\nmore"]] [:blockquote [:ul [:li "item"]]]])
    (assert/deepEqual (md "> a\nlazy") [[:blockquote [:p "a\nlazy"]]])
    (assert/deepEqual (md "text\n> q") [[:p "text"] [:blockquote [:p "q"]]])
    (assert/deepEqual (md ">> deep\n>\n> # H") [[:blockquote [:blockquote [:p "deep"]] [:h1 "H"]]])))

(test "horizontal rules: three or more - * or _; --- ends a paragraph"
  (fn []
    (assert/deepEqual (md "a\n\n***\n- - -\n___\nb\n---\nc")
                      [[:p "a"] [:hr] [:hr] [:hr] [:p "b"] [:hr] [:p "c"]])
    (assert/deepEqual (md "- a\n* * *") [[:ul [:li "a"]] [:hr]])
    (assert/deepEqual (md "a\n--") [[:p "a\n--"]])))

(test "a line of = under a paragraph makes it a heading"
  (fn []
    (assert/deepEqual (md "Title\n=====\ntext") [[:h1 "Title"] [:p "text"]])
    (assert/deepEqual (md "===") [[:p "==="]])))

(test "task lists: [ ] and [x] items get a checkbox"
  (fn []
    (assert/deepEqual (md "- [x] done\n- [ ] todo *now*\n- [y] no")
                      [[:ul
                        [:li {:class "task"} [:input {:type "checkbox" :checked true :disabled true}] "done"]
                        [:li {:class "task"} [:input {:type "checkbox" :checked false :disabled true}]
                         "todo " [:em "now"]]
                        [:li "[y] no"]]])
    (assert/deepEqual (md "- [X] loose\n\n- b")
                      [[:ul
                        [:li {:class "task"} [:p [:input {:type "checkbox" :checked true :disabled true}] "loose"]]
                        [:li [:p "b"]]]])))

(test "footnotes: numbered by first use, listed at the end, unused ones left out"
  (fn []
    (let [ref (fn [k n id?] [:sup [:a (cond-> {:href (str "#fn-" k) :class "footnote-ref"} id? (assoc :id (str "fnref-" k))) n]])
          back (fn [k] [:a {:href (str "#fnref-" k) :class "footnote-backref"} "↩"])]
      (assert/deepEqual (md "A[^b] and[^a], again[^b].\n\n[^a]: First.\n[^b]: Second\n    more.\n[^unused]: never")
                        [[:p "A" (ref "b" "1" true) " and" (ref "a" "2" true) ", again" (ref "b" "1" false) "."]
                         [:section {:class "footnotes"}
                          [:ol [:li {:id "fn-b"} [:p "Second\nmore. " (back "b")]]
                               [:li {:id "fn-a"} [:p "First. " (back "a")]]]]]))
    (assert/deepEqual (md "x[^nope]") [[:p "x[^nope]"]])))

(test "an emphasis closed inside another leaves the later ones of its kind working"
  (fn []
    (assert/deepEqual (md "*(see a_ b)* then _c_") [[:p [:em "(see a_ b)"] " then " [:em "c"]]])
    (assert/deepEqual (md "*a *b c_ d* _e f_") [[:p "*a " [:em "b c_ d"] " " [:em "e f"]]])))

(test "a lazy line continues a quote's paragraph only; = never makes a lazy heading"
  (fn []
    (assert/deepEqual (md "> ```\n> code\n> ```\nafter") [[:blockquote [:pre [:code "code"]]] [:p "after"]])
    (assert/deepEqual (md "> # H\nnext") [[:blockquote [:h1 "H"]] [:p "next"]])
    (assert/deepEqual (md "> a\n===") [[:blockquote [:p "a"]] [:p "==="]])
    (assert/deepEqual (md "- a\n===") [[:ul [:li "a"]] [:p "==="]])))

(test "footnotes: labels ignore case, a definition ends a table, code holds none"
  (fn []
    (let [ref [:sup [:a {:href "#fn-a" :class "footnote-ref" :id "fnref-a"} "1"]]
          notes (fn [txt] [:section {:class "footnotes"}
                           [:ol [:li {:id "fn-a"} [:p (str txt " ") [:a {:href "#fnref-a" :class "footnote-backref"} "↩"]]]]])]
      (assert/deepEqual (md "x[^A]\n\n[^a]: lower") [[:p "x" ref] (notes "lower")])
      (assert/deepEqual (md "| h |\n|---|\n| x[^a] |\n[^a]: n")
                        [[:table [:thead [:tr [:th "h"]]] [:tbody [:tr [:td "x" ref]]]] (notes "n")])
      (assert/deepEqual (md "y[^a]\n\n```\n[^a]: in code\n```") [[:p "y[^a]"] [:pre [:code "[^a]: in code"]]]))))
