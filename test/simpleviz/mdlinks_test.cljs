(ns simpleviz.mdlinks-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
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
