(ns simpleviz.mdlinks-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["./mdparse.js" :as mp]
            [mdlinks]
            [simpleviz.mdlinks-cases :as cases]))

(doseq [[nm text want] cases/LINKS]
  (test (str "links: " nm)
    (fn [] (assert/deepEqual (mdlinks/links text) want))))

(doseq [[d want] cases/FOLLOWABLE]
  (test (str "followable?: " (pr-str d))
    (fn [] (assert/equal (mdlinks/followable? d) want))))

(doseq [[rest want] cases/QUOTE_TARGET]
  (test (str "quote-target: " (pr-str rest))
    (fn [] (assert/deepEqual (or (mdlinks/quote-target rest) nil) want))))

(doseq [[raw want] cases/DEST_COMPARE]
  (test (str "dest-compare: " (pr-str raw))
    (fn [] (assert/equal (or (mdlinks/dest-compare raw) nil) want))))

(doseq [[raw want] cases/DEST_STEP]
  (test (str "dest-step: " (pr-str raw))
    (fn [] (assert/equal (or (mdlinks/dest-step raw) nil) want))))

(def FENCE_LINES
  "Lines to open or close a fence with, as the page reads them."
  ["```" "````" "~~~" "~~~~~" "``" "~~" "   ```" "    ```" "\t```" " \t```" "```js" "``` a`b"
   "~~~ a`b" "```   " "``` x ```" "`````" "```~" "~~~`" " ~~~~ " "\t~~~" "    ~~~" "x```" ""])

(test "fences open and close as the page's parser has them"
  (fn []
    (doseq [l FENCE_LINES]
      (let [page (mp/fenceOpen l)
            ours (mdlinks/fence-open l)]
        (assert/equal (some? ours) (some? page) (str "opens: " (pr-str l)))
        (when (some? page)
          (assert/equal (apply str (repeat (second ours) (first ours))) (.-mark page) (pr-str l))
          (assert/equal (nth ours 2) (.-info page) (pr-str l))))
      (doseq [o ["```" "````" "~~~" "~~~~"]]
        (assert/equal (boolean (mdlinks/fence-close? l (mdlinks/fence-open o)))
                      (boolean (mp/fenceClose l o))
                      (str (pr-str l) " closes " o))))))
