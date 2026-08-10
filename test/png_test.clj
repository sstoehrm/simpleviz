(ns png-test
  (:require [clojure.test :refer [deftest is]]
            [png]))

(deftest extracts-embedded-edn
  (is (= "{:nodes {:a {}}}"
         (png/extract "test/fixtures/embedded.png" "simpleviz-edn"))))

(deftest missing-keyword-returns-nil
  (is (nil? (png/extract "test/fixtures/embedded.png" "simpleviz-edn-old")))
  (is (nil? (png/extract "test/fixtures/plain-1x1.png" "simpleviz-edn"))))

(deftest non-png-throws
  (is (thrown? Exception (png/extract "README.md" "simpleviz-edn"))))
