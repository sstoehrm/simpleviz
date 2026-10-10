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
