(ns simpleviz.deck-cases
  "Cases for deck/warnings, run by both test suites (test/deck_test.clj
  and test/simpleviz/deck_test.cljs), so a deck's questions parse the
  same in `simpleviz check` and on the page.")

(def WARNINGS
  "[name text expected-warnings]"
  [["clean" "?? storage\n# Q\n- [ ] a\n- [x] b" []]
   ["one choice" "# Deck\n---\n?? storage\n# Where?\n- [x] Redis"
    ["line 3: ?? storage takes 2 to 4 choices, - [ ] lines; it has 1"]]
   ["five choices" "??? q\n- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [x] e"
    ["line 1: ??? q takes 2 to 4 choices, - [ ] lines; it has 5"]]
   ["no name" "??\n- [ ] a\n- [x] b" ["line 1: ?? needs a name, as in ?? storage"]]
   ["__proto__" "?? __proto__\n- [ ] a\n- [x] b" ["line 1: ?? __proto__ can't be a question's name"]]
   ["after >> and blank lines" "\n>> [g](g.edn)\n\n??? q\n- [x] a"
    ["line 4: ??? q takes 2 to 4 choices, - [ ] lines; it has 1"]]
   ["every slide" "?? a\n---\n?? b\n- [ ] x\n- [x] y\n---\n\n?? c"
    ["line 1: ?? a takes 2 to 4 choices, - [ ] lines; it has 0"
     "line 8: ?? c takes 2 to 4 choices, - [ ] lines; it has 0"]]
   ["crlf and cr" "a\r\n---\r\n?? q\r\n- [x] a\r---\r?? r"
    ["line 3: ?? q takes 2 to 4 choices, - [ ] lines; it has 1"
     "line 6: ?? r takes 2 to 4 choices, - [ ] lines; it has 0"]]
   ["fenced" "```\n---\n?? x\n```" []]
   ["not on a first line" "# T\n?? x" []]
   ["a nested task item is a note" "?? q\n- [x] a\n  - [ ] b"
    ["line 1: ?? q takes 2 to 4 choices, - [ ] lines; it has 1"]]
   ["a tab-indented one too" "?? q\n- [x] a\n\t- [ ] b\n- [ ] c" []]
   ["ordered items are no choices" "?? q\n1. [x] a\n2. [ ] b"
    ["line 1: ?? q takes 2 to 4 choices, - [ ] lines; it has 0"]]
   ["five blanks after the marker are no choice" "?? q\n-     [x] a\n- [ ] b"
    ["line 1: ?? q takes 2 to 4 choices, - [ ] lines; it has 1"]]
   ["* and + markers, a tab, [X]" "?? q\n*\t[X] a\n+ [ ] b" []]])
