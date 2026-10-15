(ns deck-test
  (:require [clojure.test :refer [deftest is testing]]
            [deck]
            [simpleviz.deck-cases :as cases]))

(deftest warnings-cases
  (doseq [[nm text want] cases/WARNINGS]
    (testing nm (is (= want (deck/warnings text))))))
