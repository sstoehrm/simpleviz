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
   ["title across a blank line" "[a](x.md \"t\n\nu\")" []]])

(def FOLLOWABLE
  "[dest expected]"
  [["a.md" true] ["A.MD" true] ["x/b.edn" true] ["c.PNG" true] ["../d.svg" true]
   ["http://x.md" false] ["mailto:a@b.md" false] ["/abs.md" false] ["photo.jpg" false]
   ["" false] ["C:\\x.md" false] ["dir/" false]])
