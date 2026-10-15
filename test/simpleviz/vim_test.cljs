(ns simpleviz.vim-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.vim :refer [init press selection status interrupt reloaded replaced]]))

;; A buffer is written with | at the cursor: "foo |bar" is the cursor on b.
;; Keys are a string, one char per key, <Esc> <CR> <BS> <C-r> for the rest.

(defn- split-keys [s]
  (loop [s s out []]
    (if (= "" s)
      out
      (if-let [m (.match s #"^<(Esc|CR|BS|C-r)>")]
        (recur (subs s (count (aget m 0)))
               (conj out (get {"Esc" "Escape" "CR" "Enter" "BS" "Backspace" "C-r" "C-r"} (aget m 1))))
        (recur (subs s 1) (conj out (subs s 0 1)))))))

(defn- unmark [s]
  (let [i (.indexOf s "|")]
    {:text (str (subs s 0 i) (subs s (inc i))) :pos i}))

(defn- mark [{:keys [text pos]}]
  (str (subs text 0 pos) "|" (subs text pos)))

(defn- type-key
  "What the textarea does with a key vim leaves to it (insert mode)."
  [{:keys [text pos]} k]
  (case k
    "Backspace" (if (pos? pos)
                  {:text (str (subs text 0 (dec pos)) (subs text pos)) :pos (dec pos)}
                  {:text text :pos pos})
    "Enter" {:text (str (subs text 0 pos) "\n" (subs text pos)) :pos (inc pos)}
    {:text (str (subs text 0 pos) k (subs text pos)) :pos (inc pos)}))

(defn- feed*
  "Run keys from vim state vs over buffer s: [vs buffer effects]."
  [vs s keys]
  (reduce (fn [[vs buf effects] k]
            (let [r (press vs buf k)
                  buf' (if (:handled r) {:text (:text r) :pos (:pos r)} (type-key buf k))]
              [(:vs r) buf' (cond-> effects (some? (:effect r)) (conj (:effect r)))]))
          [vs (unmark s) []]
          (split-keys keys)))

(defn- feed [s keys]
  (mark (second (feed* (init) s keys))))

(defn- mode-after [s keys]
  (:mode (first (feed* (init) s keys))))

;; ---- motions ----

(test "h l move on the line and stop at its ends"
  (fn []
    (assert/equal (feed "a|bc" "l") "ab|c")
    (assert/equal (feed "ab|c" "l") "ab|c")
    (assert/equal (feed "ab|c\nd" "l") "ab|c\nd")
    (assert/equal (feed "ab|c" "2h") "|abc")
    (assert/equal (feed "x\n|abc" "h") "x\n|abc")))

(test "j k keep the column, clamped to shorter lines"
  (fn []
    (assert/equal (feed "ab|cd\nx\nefgh" "j") "abcd\n|x\nefgh")
    (assert/equal (feed "ab|cd\nx\nefgh" "jj") "abcd\nx\nef|gh")
    (assert/equal (feed "abcd\nx\nef|gh" "2k") "ab|cd\nx\nefgh")
    (assert/equal (feed "a|bc\n\nd" "j") "abc\n|\nd")
    (assert/equal (feed "|a\nb" "5j") "a\n|b")))

(test "w b e move by words, punctuation being its own word"
  (fn []
    (assert/equal (feed "|foo bar" "w") "foo |bar")
    (assert/equal (feed "|foo.bar" "w") "foo|.bar")
    (assert/equal (feed "|foo.bar baz" "W") "foo.bar |baz")
    (assert/equal (feed "|foo\n  bar" "w") "foo\n  |bar")
    (assert/equal (feed "|foo\n\nbar" "w") "foo\n|\nbar")
    (assert/equal (feed "foo b|ar" "b") "foo |bar")
    (assert/equal (feed "foo |bar" "b") "|foo bar")
    (assert/equal (feed "foo\n|bar" "b") "|foo\nbar")
    (assert/equal (feed "|foo bar" "e") "fo|o bar")
    (assert/equal (feed "fo|o bar" "e") "foo ba|r")
    (assert/equal (feed "|a b c d" "3w") "a b c |d")))

(test "0 ^ $ gg G move to line ends and file ends"
  (fn []
    (assert/equal (feed "  fo|o" "0") "|  foo")
    (assert/equal (feed "  fo|o" "^") "  |foo")
    (assert/equal (feed "f|oo\nbar" "$") "fo|o\nbar")
    (assert/equal (feed "a\n  b\n|c" "gg") "|a\n  b\nc")
    (assert/equal (feed "|a\n  b\nc" "G") "a\n  b\n|c")
    (assert/equal (feed "|a\n  b\nc" "2G") "a\n  |b\nc")
    (assert/equal (feed "a\n  b\n|c" "2gg") "a\n  |b\nc")))

(test "f t F T find on the line, ; and , repeat"
  (fn []
    (assert/equal (feed "|a-b-c" "f-") "a|-b-c")
    (assert/equal (feed "|a-b-c" "f-;") "a-b|-c")
    (assert/equal (feed "|a-b-c" "t-") "|a-b-c")
    (assert/equal (feed "|xa-b-c" "t-") "x|a-b-c")
    (assert/equal (feed "a-b-|c" "F-") "a-b|-c")
    (assert/equal (feed "a-b-|c" "T-") "a-b-|c")
    (assert/equal (feed "a-b-c|x" "T-") "a-b-|cx")
    (assert/equal (feed "a-b-|c" "F-,") "a-b|-c")
    (assert/equal (feed "|a\n-" "f-") "|a\n-")))

(test "{ } move between paragraphs"
  (fn []
    (assert/equal (feed "|a\nb\n\nc" "}") "a\nb\n|\nc")
    (assert/equal (feed "a\n\nb\n|c" "{") "a\n|\nb\nc")
    (assert/equal (feed "|a\nb" "}") "a\n|b")))

(test "/ ? search, n N repeat, wrapping"
  (fn []
    (assert/equal (feed "|foo bar foo" "/foo<CR>") "foo bar |foo")
    (assert/equal (feed "|foo bar foo" "/foo<CR>n") "|foo bar foo")
    (assert/equal (feed "foo bar |foo" "?bar<CR>") "foo |bar foo")
    (assert/equal (feed "|a x a x" "/x<CR>N") "a x a |x")
    (let [[vs] (feed* (init) "|abc" "/zz<CR>")]
      (assert/equal (:msg vs) "Pattern not found: zz"))))

;; ---- operators ----

(test "d with motions deletes; dw stops at the line's end"
  (fn []
    (assert/equal (feed "|foo bar" "dw") "|bar")
    (assert/equal (feed "foo |bar\nbaz" "dw") "foo| \nbaz")
    (assert/equal (feed "|a b c d" "2dw") "|c d")
    (assert/equal (feed "|a b c d" "d2w") "|c d")
    (assert/equal (feed "f|oo bar" "de") "f| bar")
    (assert/equal (feed "foo b|ar" "db") "foo |ar")
    (assert/equal (feed "fo|o bar" "d$") "f|o")
    (assert/equal (feed "fo|o bar" "D") "f|o")
    (assert/equal (feed "|a-b-c" "dt-") "|-b-c")
    (assert/equal (feed "|a-b-c" "df-") "|b-c")
    (assert/equal (feed "ab|c" "x") "a|b")
    (assert/equal (feed "|abc" "2x") "|c")
    (assert/equal (feed "ab|c" "X") "a|c")))

(test "dd and linewise motions delete whole lines"
  (fn []
    (assert/equal (feed "a\n|b\nc" "dd") "a\n|c")
    (assert/equal (feed "a\nb\n|c" "dd") "a\n|b")
    (assert/equal (feed "|a\nb\nc" "2dd") "|c")
    (assert/equal (feed "|a\nb\nc" "dj") "|c")
    (assert/equal (feed "a\nb\n|c" "dk") "|a")
    (assert/equal (feed "a\n|b\nc" "dG") "|a")
    (assert/equal (feed "|only" "dd") "|")
    (assert/equal (feed "a\n  |b\nc" "dd") "a\n|c")))

(test "c deletes and inserts; cw changes to the word's end"
  (fn []
    (assert/equal (feed "|foo bar" "cwbaz<Esc>") "ba|z bar")
    (assert/equal (feed "|foo bar" "Cx<Esc>") "|x")
    (assert/equal (feed "a\n  |b\nc" "ccx<Esc>") "a\n  |x\nc")
    (assert/equal (feed "|abc" "sx<Esc>") "|xbc")
    (assert/equal (feed "a\n|bc\nd" "Sx<Esc>") "a\n|x\nd")
    (assert/equal (mode-after "|foo" "cw") "insert")))

(test "y yanks, p P put after and before"
  (fn []
    (assert/equal (feed "|foo bar" "ywP") "foo| foo bar")
    (assert/equal (feed "|foo bar" "yw$p") "foo barfoo| ")
    (assert/equal (feed "|a\nb" "yyp") "a\n|a\nb")
    (assert/equal (feed "a\n|b" "yyP") "a\n|b\nb")
    (assert/equal (feed "|a\nb" "ddp") "b\n|a")
    (assert/equal (feed "|ab" "xp") "b|a")
    (assert/equal (feed "|a" "yy3p") "a\n|a\na\na")
    (assert/equal (feed "a\n|b" "Yp") "a\nb\n|b")))

(test "text objects: words, quotes and brackets"
  (fn []
    (assert/equal (feed "foo b|ar baz" "diw") "foo | baz")
    (assert/equal (feed "foo b|ar baz" "daw") "foo |baz")
    (assert/equal (feed "say \"h|i there\" ok" "di\"") "say \"|\" ok")
    (assert/equal (feed "say \"h|i there\" ok" "da\"") "say |ok")
    (assert/equal (feed "f(a, (|b), c)" "di(") "f(a, (|), c)")
    (assert/equal (feed "f(a, (b)|, c)" "di(") "f(|)")
    (assert/equal (feed "f(a, (b)|, c)" "da)") "|f")
    (assert/equal (feed "[x |y]" "ci[z<Esc>") "[|z]")
    (assert/equal (feed "**bo|ld**" "ci*") "**bo|ld**")))

;; ---- simple edits ----

(test "r J ~ change in place"
  (fn []
    (assert/equal (feed "|abc" "rx") "|xbc")
    (assert/equal (feed "|abc" "2rx") "x|xc")
    (assert/equal (feed "|abc" "5rx") "|abc")
    (assert/equal (feed "|a\n  b\nc" "J") "a| b\nc")
    (assert/equal (feed "|a\n  b\nc" "3J") "a b| c")
    (assert/equal (feed "|a\n\nc" "J") "|a\nc")
    (assert/equal (feed "|aBc" "~") "A|Bc")
    (assert/equal (feed "|aBc" "3~") "Ab|C")))

(test "i a I A o O enter insert at their places"
  (fn []
    (assert/equal (feed "a|c" "ib<Esc>") "a|bc")
    (assert/equal (feed "|ac" "ab<Esc>") "a|bc")
    (assert/equal (feed "  fo|o" "Ix<Esc>") "  |xfoo")
    (assert/equal (feed "fo|o\nbar" "Ax<Esc>") "foo|x\nbar")
    (assert/equal (feed "  |a\nb" "ox<Esc>") "  a\n  |x\nb")
    (assert/equal (feed "a\n|b" "Ox<Esc>") "a\n|x\nb")
    (assert/equal (feed "|" "ihi<CR>there<Esc>") "hi\nther|e")
    (assert/equal (mode-after "|a" "i") "insert")
    (assert/equal (mode-after "|a" "i<Esc>") "normal")))

(test "a count on i a I A o O types the text that many times, o O each on a new line"
  (fn []
    (assert/equal (feed "|a" "3ix<Esc>") "xx|xa")
    (assert/equal (feed "|ab" "3ax<Esc>") "axx|xb")
    (assert/equal (feed "|a" "2ox<Esc>") "a\nx\n|x")
    (assert/equal (feed "|a" "2Ox<Esc>") "x\n|x\na")
    (assert/equal (feed "  |a\nb" "2ox<Esc>") "  a\n  x\n  |x\nb")
    (assert/equal (feed "|abc" "2ix<Esc>u") "|abc")
    (assert/equal (feed "|foo bar baz" "2cwx<Esc>") "|x baz")))

(test "a count on i types nothing more after a click or with the caret moved off the typing"
  (fn []
    (let [[vs buf] (feed* (init) "|z" "3iab")
          r (press vs (update buf :pos dec) "Escape")]
      (assert/equal (mark r) "|abz"))
    (let [[vs buf] (feed* (init) "|z" "3iab")
          r (press (interrupt vs) buf "Escape")]
      (assert/equal (mark r) "a|bz"))))

(test "Esc in insert mode steps the cursor back, not past the line start"
  (fn []
    (assert/equal (feed "ab|c" "i<Esc>") "a|bc")
    (assert/equal (feed "a\n|b" "i<Esc>") "a\n|b")))

;; ---- undo, redo, repeat ----

(test "u undoes a change, an insert as one step; C-r redoes"
  (fn []
    (assert/equal (feed "|foo bar" "dwu") "|foo bar")
    (assert/equal (feed "|foo bar" "dwdwuu") "|foo bar")
    (assert/equal (feed "|foo bar" "dwu<C-r>") "|bar")
    (assert/equal (feed "|foo" "cwbar<Esc>u") "|foo")
    (assert/equal (feed "|a" "ixyz<Esc>ix<Esc>u") "xy|za")
    (assert/equal (feed "|a" "u") "|a")
    (assert/equal (feed "|a b" "xlu") "|a b")))

(test ". repeats the last change, inserted text included"
  (fn []
    (assert/equal (feed "|a b c d" "dw.") "|c d")
    (assert/equal (feed "|a\nb\nc" "dd.") "|c")
    (assert/equal (feed "|foo foo" "cwbar<Esc>w.") "bar ba|r")
    (assert/equal (feed "|a\nb" "A;<Esc>j.") "a;\nb|;")
    (assert/equal (feed "|abcd" "x2.") "|d")
    (assert/equal (feed "|a b c" "dw.u") "|b c")))

(test "a count on . replaces the change's count, kept for the next ."
  (fn []
    (assert/equal (feed "|abcdef" "2x3.") "|f")
    (assert/equal (feed "|abcdefghi" "2x3..") "|i")
    (assert/equal (feed "|a b c d e" "d2w.") "|e")
    (assert/equal (feed "|a b c d e f g h" "2d2w2.") "|g h")
    (assert/equal (feed "|a\nb\nc\nd" "dd2.") "|d")
    (assert/equal (feed "|abcd" "r5l2.") "55|5d")
    (assert/equal (feed "|a-b-c-d-e" "d2f-.") "|e")))

;; ---- visual ----

(test "v selects characters, V lines; d y c ~ act on it"
  (fn []
    (assert/equal (feed "|foo bar" "vlld") "| bar")
    (assert/equal (feed "|foo bar" "vey$p") "foo barfo|o")
    (assert/equal (feed "foo |bar" "vhhd") "fo|ar")
    (assert/equal (feed "a\n|b\nc\nd" "Vjd") "a\n|d")
    (assert/equal (feed "a\n|b\nc" "Vyjp") "a\nb\nc\n|b")
    (assert/equal (feed "|foo bar" "ve~") "|FOO bar")
    (assert/equal (feed "|foo bar" "vecx<Esc>") "|x bar")
    (assert/equal (feed "foo b|ar baz" "viwd") "foo | baz")
    (assert/equal (feed "|ab" "vlo<Esc>") "|ab")
    (assert/equal (mode-after "|a" "v") "visual")
    (assert/equal (mode-after "|a" "V") "vline")
    (assert/equal (mode-after "|a" "v<Esc>") "normal")
    (assert/equal (mode-after "|a" "vV") "vline")))

(test "selection: a block on the cursor char in normal mode, the range in visual"
  (fn []
    (let [{:keys [text pos]} (unmark "a|bc\nd")]
      (assert/deepEqual (selection (init) text pos) [1 2]))
    (assert/deepEqual (selection (init) "a\n\nb" 2) [2 2])
    (let [[vs buf] (feed* (init) "|abc d" "vl")]
      (assert/deepEqual (selection vs (:text buf) (:pos buf)) [0 2]))
    (let [[vs buf] (feed* (init) "a|bc\nd" "V")]
      (assert/deepEqual (selection vs (:text buf) (:pos buf)) [0 4]))
    (let [[vs buf] (feed* (init) "a|bc" "i")]
      (assert/deepEqual (selection vs (:text buf) (:pos buf)) [1 1]))))

;; ---- the command line ----

(test ": commands save, close, discard and jump to a line"
  (fn []
    (assert/deepEqual (nth (feed* (init) "|a" ":w<CR>") 2) ["save"])
    (assert/deepEqual (nth (feed* (init) "|a" ":q<CR>") 2) ["close"])
    (assert/deepEqual (nth (feed* (init) "|a" ":q!<CR>") 2) ["discard"])
    (assert/deepEqual (nth (feed* (init) "|a" ":wq<CR>") 2) ["save-close"])
    (assert/deepEqual (nth (feed* (init) "|a" ":x<CR>") 2) ["save-close"])
    (assert/equal (feed "|a\nb\nc" ":3<CR>") "a\nb\n|c")
    (assert/equal (feed "|a" ":wx<BS><BS><Esc>") "|a")
    (let [[vs] (feed* (init) "|a" ":zap<CR>")]
      (assert/equal (:msg vs) "Not an editor command: zap")
      (assert/equal (:mode vs) "normal"))))

(test "status names the mode, pending keys and the command line"
  (fn []
    (assert/equal (status (first (feed* (init) "|a" "")))  "")
    (assert/equal (status (first (feed* (init) "|a" "i"))) "-- INSERT --")
    (assert/equal (status (first (feed* (init) "|a" "v"))) "-- VISUAL --")
    (assert/equal (status (first (feed* (init) "|a" "V"))) "-- VISUAL LINE --")
    (assert/equal (status (first (feed* (init) "|a" "2d"))) "2d")
    (assert/equal (status (first (feed* (init) "|a" ":w"))) ":w")
    (assert/equal (status (first (feed* (init) "|a" "/fo"))) "/fo")))

;; ---- keys vim leaves alone ----

(test "insert mode leaves keys to the textarea; normal mode takes them all"
  (fn []
    (let [vs (:vs (press (init) {:text "a" :pos 0} "i"))]
      (assert/equal (:handled (press vs {:text "a" :pos 0} "x")) false)
      (assert/equal (:handled (press vs {:text "a" :pos 0} "Escape")) true))
    (assert/equal (:handled (press (init) {:text "a" :pos 0} "q")) true)
    (assert/equal (:handled (press (init) {:text "a" :pos 0} "Escape")) false
                  "a bare Esc in normal mode is left to the page (it docks the panel)")
    (let [vs (:vs (press (init) {:text "a" :pos 0} "d"))]
      (assert/equal (:handled (press vs {:text "a" :pos 0} "Escape")) true))))

(test "interrupt: a click or a reload ends visual mode; an interrupted insert isn't repeated"
  (fn []
    (let [[vs] (feed* (init) "|abc" "vl")]
      (assert/equal (:mode (interrupt vs)) "normal"))
    (let [[vs] (feed* (init) "|abc" "2d")]
      (assert/deepEqual (:keys (interrupt vs)) []))
    ;; x is the last change; an insert typed in two places after a click
    ;; doesn't take its place, and still undoes as one step
    (let [[vs buf] (feed* (init) "|ab cd" "xi")
          vs (interrupt vs)
          [vs buf] (feed* vs (str (subs (:text buf) 0 1) "|" (subs (:text buf) 1)) "Y<Esc>")]
      (assert/equal (:mode vs) "normal")
      (assert/equal (mark buf) "b|Y cd")
      (assert/equal (mark (second (feed* vs (mark buf) "."))) "b| cd")
      (assert/equal (mark (second (feed* vs (mark buf) "u"))) "|b cd"))))

(test ". repeats typed text that looks like its neighbours as typed"
  (fn []
    (assert/equal (feed "th|e cat\nthe dog" "a big<Esc>j.") "the big cat\nthe dog bi|g")
    (assert/equal (feed "|ab" "aba<Esc>.") "abab|ab")))

(test "words take letters beyond ASCII"
  (fn []
    (assert/equal (feed "|Größe ist" "w") "Größe |ist")
    (assert/equal (feed "Gr|öße ist" "diw") "| ist")
    (assert/equal (feed "|héllo w" "e") "héll|o w")))

(test "/ ? in visual mode extend the selection; : leaves it"
  (fn []
    (assert/equal (feed "|abc def" "v/def<CR>d") "|ef")
    (assert/equal (mode-after "|abc" "v/c<CR>") "visual")
    (assert/equal (mode-after "|abc" "v/c<Esc>") "visual")
    (assert/equal (feed "|abc" "v/zz<CR>d") "|bc")
    (let [[vs _ fx] (feed* (init) "|abc" "vl:w<CR>")]
      (assert/equal (:mode vs) "normal")
      (assert/deepEqual fx ["save"]))
    (let [[vs buf] (feed* (init) "|abc def" "v/de")]
      (assert/deepEqual (selection vs (:text buf) (:pos buf)) [0 1]))))

(test "visual p with nothing yanked leaves the text"
  (fn []
    (assert/equal (feed "a\n|b\nc" "Vp") "a\n|b\nc")
    (assert/equal (mode-after "a\n|b\nc" "Vp") "normal")))

(test "a caret on a line's end (a click past it) counts as on its last char"
  (fn []
    (assert/equal (feed "abc|\nd" "x") "a|b\nd")
    (assert/deepEqual (selection (init) "abc\nd" 3) [2 3])))

(test "reloaded: undo never brings back the text before a reload"
  (fn []
    (let [[vs] (feed* (init) "|abc" "x")
          vs (reloaded vs "new text" 0)]
      (assert/equal (mark (second (feed* vs "|new text" "u"))) "|new text"))
    ;; an insert going on when the file came: Esc undoes to the new text
    (let [[vs] (feed* (init) "|abc" "i")
          vs (reloaded vs "new" 0)
          [vs] (feed* vs "|new" "x<Esc>")]
      (assert/equal (mark (second (feed* vs "|xnew" "u"))) "|new"))))

(test "replaced: u brings back the text from before a change made outside vi mode"
  (fn []
    (let [vs (replaced (init) "old text" 2)
          r (press vs {:text "new" :pos 0} "u")]
      (assert/equal (:text r) "old text")
      (assert/equal (:mode (:vs r)) "normal"))
    (let [[vs buf] (feed* (init) "|ab" "ixy")
          vs (replaced vs (:text buf) (:pos buf))
          r (press vs {:text "tidy" :pos 0} "u")
          r2 (press (:vs r) {:text (:text r) :pos (:pos r)} "u")]
      (assert/equal (:mode vs) "normal")
      (assert/equal (:text r) "xyab")
      (assert/equal (:text r2) "ab"))))
