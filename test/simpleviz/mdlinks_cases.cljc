(ns simpleviz.mdlinks-cases
  "Cases for mdlinks, run by both test suites (test/mdlinks_test.clj and
  test/simpleviz/mdlinks_test.cljs), so the scanner answers the same on
  the server and in the page.")

(def LINKS
  "[name text expected-links]"
  [["inline" "see [the graph](pay.edn) here" [{:line 1 :label "the graph" :dest "pay.edn" :kind "inline"}]]
   ["angle + title" "[a](<my file.md> \"T\")\n[b](x.edn 'u') [c](y.edn (p))"
    [{:line 1 :label "a" :dest "my file.md" :kind "inline"} {:line 2 :label "b" :dest "x.edn" :kind "inline"} {:line 2 :label "c" :dest "y.edn" :kind "inline"}]]
   ["nested + escaped" "[a [b] c](n.md) [x\\]y](e.md)" [{:line 1 :label "a [b] c" :dest "n.md" :kind "inline"} {:line 1 :label "x\\]y" :dest "e.md" :kind "inline"}]]
   ["multiline label" "x\n[two\nlines](t.md)" [{:line 2 :label "two\nlines" :dest "t.md" :kind "inline"}]]
   ["image" "![alt](d.png)" [{:line 1 :label "alt" :dest "d.png" :kind "image"}]]
   ["refs" "[full][Id] [coll][] [short]\n\n[id]: a.edn\n  [Coll]: b.md \"t\"\n[short]: <c d.svg>"
    [{:line 1 :label "full" :dest "a.edn" :kind "ref"} {:line 1 :label "coll" :dest "b.md" :kind "ref"} {:line 1 :label "short" :dest "c d.svg" :kind "ref"}
     {:line 3 :label "id" :dest "a.edn" :kind "def"} {:line 4 :label "Coll" :dest "b.md" :kind "def"} {:line 5 :label "short" :dest "c d.svg" :kind "def"}]]
   ["first def wins, label whitespace" "[A  b]\n[a b]: one.md\n[A B]: two.md" [{:line 1 :label "A  b" :dest "one.md" :kind "ref"} {:line 2 :label "a b" :dest "one.md" :kind "def"}]]
   ["undefined ref" "[x][nope] [y]" []]
   ["footnotes are no links" "See[^src].\n\n[^src]: diagrams/api.edn" []]
   ["fragment query" "[a](a.md#sec) [b](b.edn?x=1#y)" [{:line 1 :label "a" :dest "a.md" :kind "inline"} {:line 1 :label "b" :dest "b.edn" :kind "inline"}]]
   ["percent" "[a](my%20file.md) [b](%C3%BCber.md) [c](bad%ZZ.md) [d](%FF.md)"
    [{:line 1 :label "a" :dest "my file.md" :kind "inline"} {:line 1 :label "b" :dest "über.md" :kind "inline"} {:line 1 :label "c" :dest "bad%ZZ.md" :kind "inline"} {:line 1 :label "d" :dest "%FF.md" :kind "inline"}]]
   ["backslash" "[a](x\\_y.md)" [{:line 1 :label "a" :dest "x_y.md" :kind "inline"}]]
   ["fences" "```\n[a](a.md)\n```\n~~~~\n[b](b.md)\n~~~\n[c](c.md)" []]
   ["longer fence closes" "~~~\n[a](a.md)\n~~~~\n[b](b.md)" [{:line 4 :label "b" :dest "b.md" :kind "inline"}]]
   ["fence closed" "```\n[a](a.md)\n```\n[b](b.md)" [{:line 4 :label "b" :dest "b.md" :kind "inline"}]]
   ["code spans" "`[a](a.md)` ``x ` [b](b.md)`` [c](c.md)" [{:line 1 :label "c" :dest "c.md" :kind "inline"}]]
   ["crlf cr" "a\r\n[b](b.md)\rc\r\n[d](d.md)" [{:line 2 :label "b" :dest "b.md" :kind "inline"} {:line 4 :label "d" :dest "d.md" :kind "inline"}]]
   ["not links" "<http://x.md> http://y.md [z] (w.md) [] (v.md)" []]
   ["empty dest" "[a]() [b](<>)" []]
   ["parens in dest" "[a](f(1).md)" [{:line 1 :label "a" :dest "f(1).md" :kind "inline"}]]
   ;; a blank line ends a paragraph: no label, code span, destination or
   ;; title reaches across it
   ["lone backtick" "Press the ` key.\n\n[a](a.md) then `[no](no.md)` and [b](b.edn)\n\nmore [c](c.md)"
    [{:line 3 :label "a" :dest "a.md" :kind "inline"} {:line 3 :label "b" :dest "b.edn" :kind "inline"}
     {:line 5 :label "c" :dest "c.md" :kind "inline"}]]
   ["label across a blank line" "[ref section\n\nmore text](x.md)" []]
   ["label across a blank line of blanks" "[ref section\n \t\nmore text](x.md) [ok](o.md)"
    [{:line 3 :label "ok" :dest "o.md" :kind "inline"}]]
   ["dest across a blank line" "[a](\n\nx.md)" []]
   ["title across a blank line" "[a](x.md \"t\n\nu\")" []]
   ;; ?compare=<suffix> names the fork to compare with, kept apart from :dest
   ["compare" "[a](api.edn?compare=next) ![b](d.svg?x=1&compare=v2#f) [c](c.edn?compare=)"
    [{:line 1 :label "a" :dest "api.edn" :kind "inline" :compare "next"}
     {:line 1 :label "b" :dest "d.svg" :kind "image" :compare "v2"}
     {:line 1 :label "c" :dest "c.edn" :kind "inline" :compare ""}]]
   ["compare on a definition and its uses" "[x][d] [y](y.edn#compare=no)\n\n[d]: <a b.edn?compare=next> \"t\""
    [{:line 1 :label "x" :dest "a b.edn" :kind "ref" :compare "next"} {:line 1 :label "y" :dest "y.edn" :kind "inline"}
     {:line 3 :label "d" :dest "a b.edn" :kind "def" :compare "next"}]]])

(def DEST_COMPARE
  "[raw-destination expected-compare]"
  [["a.edn" nil] ["a.edn?compare=next" "next"] ["a.edn?x=1&compare=v%202" "v 2"]
   ["a.edn?compare=a&compare=b" "a"] ["a.edn?compared=x" nil] ["a.edn#compare=x" nil]
   ["a.edn?compare=x#y" "x"] ["a.edn?compare" ""] ["a.edn\\?compare=x" "x"]])

(def DEST_STEP
  "[raw-destination expected-step]: the step or step range asked for"
  [["a.edn" nil] ["a.edn?step=2" "2"] ["a.edn?step=1..3" "1..3"]
   ["a.edn?compare=x&step=cache%20on" "cache on"] ["a.edn?step=a&step=b" "a"]
   ["a.edn?steps=2" nil] ["a.edn#step=2" nil] ["a.edn?step=" nil] ["a.edn?step" nil]])

(def QUOTE_TARGET
  "[rest-of-a-> line expected]: a lone link or picture to a file a slide
  shows beside it, else nil — the line is a quote"
  [[" [the flow](pay/flow.edn)" {:dest "pay/flow.edn"}]
   [" ![a cup](pic.jpg \"Cup\")  " {:dest "pic.jpg"}]
   ["[g](g.edn?compare=next)" {:dest "g.edn" :compare "next"}]
   ["[g](g.edn?step=1..2)" {:dest "g.edn" :step "1..2"}]
   [" [b](<b c.md#intro>)" {:dest "b c.md"}]
   [" [n](notes.MD)" {:dest "notes.MD"}]
   [" \"Simplicity is prerequisite for reliability.\" — Dijkstra" nil]
   [" pay/flow.edn" nil]
   [" see [the flow](flow.edn)" nil]
   [" [the flow](flow.edn) and more" nil]
   [" [a](notes.txt)" nil]
   [" [docs](https://example.com)" nil]
   [" [the writeup](https://example.com/notes.md)" nil]
   [" [x](/abs/g.edn)" nil]
   [" [see](md)" nil]
   [" [a](a.edn" nil]
   ["" nil]])

(def FOLLOWABLE
  "[dest expected]"
  [["a.md" true] ["A.MD" true] ["x/b.edn" true] ["c.PNG" true] ["../d.svg" true]
   ["http://x.md" false] ["mailto:a@b.md" false] ["/abs.md" false] ["photo.jpg" false]
   ["" false] ["C:\\x.md" false] ["dir/" false]])
