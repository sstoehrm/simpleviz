(ns simpleviz.slides-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.slides :refer [slides side-kind stop-at answer]]))

(test "--- lines split the slides; empty ones are dropped"
  (fn []
    (assert/deepEqual (slides "---\n# Deck\n## Sub\nme\n\n---\n# One\n\n- a\n---\n")
                      [{:text "# Deck\n## Sub\nme" :side nil}
                       {:text "# One\n\n- a" :side nil}])
    (assert/deepEqual (slides "just text") [{:text "just text" :side nil}])
    (assert/deepEqual (slides "") [])
    (assert/deepEqual (slides "a\r\n---\r\nb") [{:text "a" :side nil} {:text "b" :side nil}])))

(test "a separator is exactly ---, spaces around it allowed"
  (fn []
    (assert/deepEqual (slides "a\n  ---  \nb\n----\nc")
                      [{:text "a" :side nil} {:text "b\n----\nc" :side nil}])))

(test ">> on a slide's first line names what shows beside it: a link, or a path"
  (fn []
    (assert/deepEqual (slides "---\n\n>> [the state](./state.edn)\n# Title\n\ntext\n---\n>> ![a cup](pic.jpg \"Cup\")\n---\n>>plain.edn")
                      [{:text "# Title\n\ntext" :side "./state.edn"}
                       {:text "" :side "pic.jpg"}
                       {:text "" :side "plain.edn"}])
    ;; as a link means it: <> around spaces, %XX decoded, #part cut
    (assert/deepEqual (slides ">> [a](<sub/a b.edn>)\n---\n>> [b](b%20c.md#intro)")
                      [{:text "" :side "sub/a b.edn"} {:text "" :side "b c.md"}])
    ;; anywhere else it is text
    (assert/deepEqual (slides "# T\n>> g.edn") [{:text "# T\n>> g.edn" :side nil}])
    (assert/deepEqual (slides ">>   \n# T") [{:text "# T" :side nil}])))

(test "--- inside a fenced code block is code"
  (fn []
    (assert/deepEqual (slides "```\n---\n```\n---\n~~~\n---\n~~~")
                      [{:text "```\n---\n```" :side nil} {:text "~~~\n---\n~~~" :side nil}])))

(test "side-kind: what a >> target shows as"
  (fn []
    (assert/equal (side-kind "a/g.edn") "graph")
    (assert/equal (side-kind "notes.MD") "md")
    (assert/equal (side-kind "x.png") "export")
    (assert/equal (side-kind "x.svg") "export")
    (assert/equal (side-kind "x.JPG") "image")
    (assert/equal (side-kind "x.jpeg") "image")
    (assert/equal (side-kind "x.gif") "image")
    (assert/equal (side-kind "x.webp") "image")
    (assert/equal (side-kind "x.txt") nil)))

(test "?? names a question: its task list becomes the choices, [x] the right one"
  (fn []
    (assert/deepEqual (slides "?? storage\n# Where?\n\nPick one.\n\n- [ ] Browser\n- [x] Redis\n- [ ] Postgres\n")
                      [{:text "# Where?\n\nPick one."
                        :side nil
                        :ask {:id "storage" :submit false :error nil
                              :choices [{:text "Browser" :correct false :note nil}
                                        {:text "Redis" :correct true :note nil}
                                        {:text "Postgres" :correct false :note nil}]}}])
    ;; ??? asks for a submit; * and + lists and [X] count too
    (let [[s] (slides "???  entry \n# Q\n* [X] gateway\n+ [ ] auth")]
      (assert/equal (:id (:ask s)) "entry")
      (assert/equal (:submit (:ask s)) true)
      (assert/deepEqual (mapv :correct (:choices (:ask s))) [true false]))))

(test "the lines indented under a choice are its explanation"
  (fn []
    (let [[s] (slides "??? q\n# Q\n- [ ] Node memory\n  > Gone on restart.\n  > Really.\n\n- [x] Redis\n    > Right: shared.\n- [ ] Disk\nAfter the list.")]
      (assert/equal (:text s) "# Q\nAfter the list.")
      (assert/deepEqual (mapv :note (:choices (:ask s)))
                        ["> Gone on restart.\n> Really." "> Right: shared." nil]))))

(test ">> and ?? share a slide, in either order"
  (fn []
    (doseq [deck [">> [g](g.edn)\n?? q\n# Q\n- [ ] a\n- [x] b"
                  "?? q\n>> [g](g.edn)\n# Q\n- [ ] a\n- [x] b"
                  ;; blank lines between them, as decks write a >> line
                  ">> [g](g.edn)\n\n?? q\n\n# Q\n- [ ] a\n- [x] b"
                  "?? q\n\n\n>> [g](g.edn)\n\n# Q\n- [ ] a\n- [x] b"]]
      (let [[s] (slides deck)]
        (assert/equal (:side s) "g.edn")
        (assert/equal (:id (:ask s)) "q")
        (assert/equal (:text s) "# Q")))
    ;; a ?? line further down is text
    (assert/deepEqual (slides "# T\n?? q") [{:text "# T\n?? q" :side nil}])))

(test "a task list in a fenced code block is no choice"
  (fn []
    (let [[s] (slides "?? q\n```\n- [ ] code\n```\n- [ ] a\n- [ ] b")]
      (assert/equal (:text s) "```\n- [ ] code\n```")
      (assert/equal (count (:choices (:ask s))) 2))))

(test "a question without a name, or without 2 to 4 choices, says so and shows as text"
  (fn []
    (let [[s] (slides "?? q\n# Q\n- [x] only")]
      (assert/equal (:text s) "# Q\n- [x] only")
      (assert/deepEqual (:choices (:ask s)) [])
      (assert/equal (:error (:ask s)) "?? q takes 2 to 4 choices, - [ ] lines; it has 1"))
    (assert/equal (:error (:ask (first (slides "?? q\n- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [ ] e"))))
                  "?? q takes 2 to 4 choices, - [ ] lines; it has 5")
    (assert/equal (:error (:ask (first (slides "???\n- [ ] a\n- [ ] b"))))
                  "??? needs a name, as in ??? storage")
    ;; the page keeps answers in an object, where this name is no key
    (assert/equal (:error (:ask (first (slides "??? __proto__\n- [ ] a\n- [ ] b"))))
                  "??? __proto__ can't be a question's name")))

(def ^:private quiz
  (slides (str "# Title\n---\n"
               "?? poll\n# How sure?\n- [ ] Very\n- [ ] Not\n---\n"
               "??? storage\n# Where?\n- [ ] Browser\n- [x] Redis\n---\n"
               "??? entry\n- [x] gateway\n- [ ] auth\n---\n"
               "# End")))

(test "stop-at: stepping forward stops at a ??? not yet submitted"
  (fn []
    (assert/equal (stop-at quiz {} 0 4) 2)
    (assert/equal (stop-at quiz {} 2 3) 2)
    (assert/equal (stop-at quiz {"storage" {:answer "Redis" :num 1}} 2 4) 3)
    (assert/equal (stop-at quiz {"storage" {:answer "Redis" :num 1} "entry" {:answer "auth" :num 1}} 0 4) 4)
    ;; ?? never stops, and back always goes
    (assert/equal (stop-at quiz {} 1 2) 2)
    (assert/equal (stop-at quiz {} 4 0) 0)))

(test "a tab-indented explanation, or a nested list in one, stays the choice's"
  (fn []
    (let [[s] (slides "??? q\n# Q\n- [ ] A\n\t> Gone on restart.\n- [x] B\n  - [ ] not a choice\n- [ ] C")]
      (assert/equal (:text s) "# Q")
      (assert/deepEqual (mapv :text (:choices (:ask s))) ["A" "B" "C"])
      (assert/deepEqual (mapv :note (:choices (:ask s))) ["> Gone on restart." "- [ ] not a choice" nil]))))

(test "a question named like an Object property is still unanswered"
  (fn []
    (let [ds (slides "??? constructor\n- [x] a\n- [ ] b\n---\n# End")]
      (assert/equal (stop-at ds {} 0 1) 0)
      (assert/ok (nil? (answer {} "toString"))))))

(test "a >> link's ?compare= rides along as :side-compare"
  (fn []
    (assert/deepEqual (slides ">> [the change](api.edn?compare=next)\n---\n>> api.edn?compare=v2\n---\n>> [plain](api.edn)")
                      [{:text "" :side "api.edn" :side-compare "next"}
                       {:text "" :side "api.edn" :side-compare "v2"}
                       {:text "" :side "api.edn"}])))

(test "a question slide keeps its >> link's ?compare="
  (fn []
    (let [[s] (slides "?? q\n>> [g](g.edn?compare=next)\n# Q\n- [ ] a\n- [x] b")]
      (assert/equal (:side s) "g.edn")
      (assert/equal (:side-compare s) "next")
      (assert/equal (:id (:ask s)) "q"))))
