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

(deftest unmatched-brackets-scan-in-linear-time
  ;; each [ once scanned to the end of the text: 4 s for these 8 KB
  (let [text (apply str (repeat 2000 "x[y\n"))
        t0 (System/nanoTime)]
    (is (= [] (mdlinks/links text)))
    (is (< (/ (- (System/nanoTime) t0) 1e6) 1000))))
