(ns simpleviz.deck-cases
  "Cases for deck/warnings, run by both test suites (test/deck_test.clj
  and test/simpleviz/deck_test.cljs), so a deck's questions parse the
  same in `simpleviz check` and on the page.")

(def WARNINGS
  "[name text expected-warnings], each [line error]"
  [["clean" "?? storage\n# Q\n- [ ] a\n- [x] b" []]
   ["one choice" "# Deck\n---\n?? storage\n# Where?\n- [x] Redis"
    [[3 "?? storage takes 2 to 4 choices, - [ ] lines; it has 1"]]]
   ["five choices" "??? q\n- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [x] e"
    [[1 "??? q takes 2 to 4 choices, - [ ] lines; it has 5"]]]
   ["no name" "??\n- [ ] a\n- [x] b" [[1 "?? needs a name, as in ?? storage"]]]
   ["__proto__" "?? __proto__\n- [ ] a\n- [x] b" [[1 "?? __proto__ can't be a question's name"]]]
   ["after >> and blank lines" "\n>> [g](g.edn)\n\n??? q\n- [x] a"
    [[4 "??? q takes 2 to 4 choices, - [ ] lines; it has 1"]]]
   ["every slide" "?? a\n---\n?? b\n- [ ] x\n- [x] y\n---\n\n?? c"
    [[1 "?? a takes 2 to 4 choices, - [ ] lines; it has 0"]
     [8 "?? c takes 2 to 4 choices, - [ ] lines; it has 0"]]]
   ["crlf and cr" "a\r\n---\r\n?? q\r\n- [x] a\r---\r?? r"
    [[3 "?? q takes 2 to 4 choices, - [ ] lines; it has 1"]
     [6 "?? r takes 2 to 4 choices, - [ ] lines; it has 0"]]]
   ["fenced" "```\n---\n?? x\n```" []]
   ;; fences as the page reads them (mdlinks/fence-open, fence-close?)
   ["a shorter fence closes no longer one" "````\n```\n---\n?? x\n````" []]
   ["a ``` with a ` in its info opens no fence" "``` a`b\n---\n?? x"
    [[3 "?? x takes 2 to 4 choices, - [ ] lines; it has 0"]]]
   ["four spaces in, ``` is code, no fence" "    ```\n---\n?? x"
    [[3 "?? x takes 2 to 4 choices, - [ ] lines; it has 0"]]]
   ["choices in a longer fence are code" "?? q\n- [x] a\n````\n```\n- [ ] b\n- [ ] c\n````"
    [[1 "?? q takes 2 to 4 choices, - [ ] lines; it has 1"]]]
   ["not on a first line" "# T\n?? x" []]
   ["a nested task item is a note" "?? q\n- [x] a\n  - [ ] b"
    [[1 "?? q takes 2 to 4 choices, - [ ] lines; it has 1"]]]
   ["a tab-indented one too" "?? q\n- [x] a\n\t- [ ] b\n- [ ] c" []]
   ["ordered items are no choices" "?? q\n1. [x] a\n2. [ ] b"
    [[1 "?? q takes 2 to 4 choices, - [ ] lines; it has 0"]]]
   ["five blanks after the marker are no choice" "?? q\n-     [x] a\n- [ ] b"
    [[1 "?? q takes 2 to 4 choices, - [ ] lines; it has 1"]]]
   ["* and + markers, a tab, [X]" "?? q\n*\t[X] a\n+ [ ] b" []]])
