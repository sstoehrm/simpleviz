(ns simpleviz.deck-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [deck]
            [simpleviz.deck-cases :as cases]))

(doseq [[nm text want] cases/WARNINGS]
  (test (str "warnings: " nm)
    (fn [] (assert/deepEqual (deck/warnings text) want))))
