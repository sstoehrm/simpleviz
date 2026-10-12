(ns simpleviz.slides-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.slides :refer [slides side-kind]]))

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

(test ">> on a slide's first line names what shows beside it"
  (fn []
    (assert/deepEqual (slides "---\n\n>> state.edn\n# Title\n\ntext\n---\n>>pic.jpg")
                      [{:text "# Title\n\ntext" :side "state.edn"}
                       {:text "" :side "pic.jpg"}])
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
