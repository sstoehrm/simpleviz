(ns mdlinks-test
  (:require [clojure.test :refer [deftest is testing]]
            [mdlinks]
            [simpleviz.mdlinks-cases :as cases]))

(deftest links-cases
  (doseq [[nm text want] cases/LINKS]
    (testing nm (is (= want (mdlinks/links text))))))

(deftest followable-cases
  (doseq [[d want] cases/FOLLOWABLE]
    (testing d (is (= want (mdlinks/followable? d))))))
