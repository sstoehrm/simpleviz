(ns calc-test
  (:require [calc]
            [clojure.test :refer [deftest is testing]]))

(defn- results
  "The results grid of one table: body rows `cells`, def block `defs`."
  ([cells] (results cells nil))
  ([cells defs] (calc/evaluate {:cells cells :defs defs})))

(defn- value-at [out r c] (get-in out [:results r c :value]))

(defn- error-at [out r c] (get-in out [:results r c :error]))

(deftest columns-are-letters
  (is (= 0 (calc/col-index :a)))
  (is (= 25 (calc/col-index :z)))
  (is (= 26 (calc/col-index :aa)))
  (is (nil? (calc/col-index :A)))
  (is (nil? (calc/col-index :x/a)))
  (is (nil? (calc/col-index :a1))))

(deftest ranges-are-flat-groups
  (is (= [{:col 0 :from 1 :to 5}] (calc/ranges [:a] 5)))
  (is (= [{:col 0 :from 4 :to 4}] (calc/ranges [:a 4] 5)))
  (is (= [{:col 0 :from 4 :to 5} {:col 1 :from 1 :to 4}] (calc/ranges [:a 4 5 :b 1 4] 5)))
  (testing "mistakes are errors, not clipped"
    (is (thrown-with-msg? Exception #"row 9" (calc/ranges [:a 9] 5)))
    (is (thrown-with-msg? Exception #"row 0" (calc/ranges [:a 0 2] 5)))
    (is (thrown-with-msg? Exception #"3 > 2" (calc/ranges [:a 3 2] 5)))
    (is (thrown-with-msg? Exception #":price" (calc/ranges [:price] 5 3)))
    (is (thrown-with-msg? Exception #"has 3" (calc/ranges [:d] 5 3)))
    (is (thrown-with-msg? Exception #"1 2 3" (calc/ranges [:a 1 2 3] 5)))
    (is (thrown-with-msg? Exception #"keyword" (calc/ranges [1 2] 5)))))

(deftest plain-numbers-only
  (is (= 1 (calc/number-of "1")))
  (is (= -3 (calc/number-of "-3")))
  (is (= 1.5 (calc/number-of "1.5")))
  (is (= 2 (calc/number-of " 2 ")))
  (is (nil? (calc/number-of "1,000")))
  (is (nil? (calc/number-of "12%")))
  (is (nil? (calc/number-of "")))
  (is (nil? (calc/number-of "abc"))))

(deftest sum-and-friends-over-ranges
  (let [cells [["1" "x"] ["2" "10"] ["3" "20"] ["=(sum :a)" ""]]
        f (fn [formula] (value-at (results (assoc-in cells [3 1] formula)) 3 1))]
    (is (= "6" (value-at (results cells) 3 0)) "the formula cell is left out of its own range")
    (is (= "15" (f "=(sum :a 2 3 :b 2 2)")))
    (is (= "2.0" (f "=(avg :a 1 3)")))
    (is (= "30" (f "=(sum :b)")) "text is skipped")
    (is (= "2" (f "=(count :b)")))
    (is (= "1" (f "=(min :a)")))
    (is (= "20" (f "=(max :b)")))
    (is (= "[1 2 3]" (f "=(table :a 1 3)")))
    (is (= "6" (f "=(reduce + (table :a))")))
    (testing "core behaviour without a keyword"
      (is (= "2" (f "=(count [1 2])")))
      (is (= "7" (f "=(max 1 7)")))
      (is (= "6" (f "=(sum [1 2 3])"))))
    (is (= "3.50" (f "=(format \"%.2f\" 3.5)")))))

(deftest non-formula-cells-have-no-result
  (let [out (results [["1" "=(sum :a)"] ["= see note" "=>"]])]
    (is (nil? (get-in out [:results 0 0])))
    (is (nil? (get-in out [:results 1 0])))
    (is (nil? (get-in out [:results 1 1])))
    (is (= "1" (value-at out 0 1)))))

(deftest formula-errors-stay-in-their-cell
  (let [out (results [["1" "=(sum :a 9)" "=(sum :a" "=(slurp \"/etc/hosts\")" "=(sum :a)"]])]
    (is (re-find #"row 9" (error-at out 0 1)))
    (is (some? (error-at out 0 2)))
    (is (re-find #"slurp" (error-at out 0 3)))
    (is (= "1" (value-at out 0 4)))))

(deftest a-runaway-formula-times-out-alone
  (binding [calc/*timeout-ms* 200]
    (let [out (results [["1" "=(loop [] (recur))" "=(sum :a)"]])]
      (is (re-find #"too long" (error-at out 0 1)))
      (is (= "1" (value-at out 0 2))))))

(deftest def-block-parsers
  (let [cells [["12%" "$ 3" "a"] ["4 %" "$5" "b"] ["=(sum :a)" "=(sum :b)" "=(sum :c)"]]]
    (testing "a string parser strips a prefix or suffix"
      (let [out (results cells "[:a] \"%\"\n[:b 1 2] \"$\"")]
        (is (= "16" (value-at out 2 0)))
        (is (= "8" (value-at out 2 1)))))
    (testing "a fn parser gets the raw cell"
      (let [out (results cells "[:c] (fn [s] (if (= s \"a\") 1 2)) ; comment")]
        (is (= "3" (value-at out 2 2)))))
    (testing "a later rule wins"
      (let [out (results cells "[:a] (fn [s] 100)\n[:a 2] \"%\"")]
        (is (= "104" (value-at out 2 0)))))
    (testing "a bad block is the table's error"
      (is (re-find #"pairs" (:error (results cells "[:a]"))))
      (is (re-find #"row 9" (:error (results cells "[:a 9] \"%\""))))
      (is (re-find #"string or a fn" (:error (results cells "[:a] 3"))))
      (is (re-find #"row 1, :a" (:error (results cells "[:a] (fn [s] (throw (ex-info \"boom\" {})))")))))))

(deftest results-print-plainly
  (let [f (fn [formula] (value-at (results [[formula]]) 0 0))]
    (is (= "0.5" (f "=(/ 1 2)")) "a ratio shows as a decimal")
    (is (= "hi" (f "=(str \"hi\")")))
    (is (= "" (f "=(first [])")))
    (is (= ":x" (f "=(keyword \"x\")")))))

(deftest a-table-without-formulas-is-left-alone
  (is (= {:results [[nil]]} (results [["1"]] "[:a] (fn [s] (loop [] (recur)))"))))

(deftest formulas-run-in-user-whatever-the-callers-ns
  (binding [*ns* (create-ns 'calc-test.elsewhere)]
    (is (= "1" (value-at (results [["1" "=(sum :a)"]]) 0 1)))
    (is (= "1" (value-at (results [["x" "=(sum :a)"]] "[:a] (fn [s] (count [s]))") 0 1)))))

(deftest a-stack-overflow-stays-in-its-cell
  (let [out (results [["1" "=((fn f [x] (f x)) 1)" "=(sum :a)"]])]
    (is (some? (error-at out 0 1)))
    (is (= "1" (value-at out 0 2))))
  (is (some? (:error (results [["1" "=(sum :a)"]] "[:a] (fn f [s] (f s))")))))

(deftest big-whole-numbers-are-numbers
  (is (= "100000000000000000001" (value-at (results [["99999999999999999999"] ["2"] ["=(sum :a)"]]) 2 0))))

;; through the child process, as the server runs them

(deftest formulas-cannot-touch-the-server
  (let [out (calc/evaluate-tables
             [{:cells [["=(alter-var-root #'clojure.string/starts-with? (fn [f] (fn [s p] true)))"
                        "=(sum :a)"]]}])]
    (is (false? (clojure.string/starts-with? "abc" "/")) "the server's own var is untouched")
    (is (some? (get-in out [0 :results 0 0])))))

(deftest the-child-runs-every-table
  (is (= [{:results [[nil {:value "1"}]]} {:results [[nil]]} {:results [[{:value "12"}]]}]
         (calc/evaluate-tables [{:cells [["1" "=(sum :a)"]]}
                                {:cells [["2"]]}
                                {:cells [["=(+ 5 7)"]]}]))))

(deftest a-child-past-its-deadline-is-killed
  (binding [calc/*child-ms* 300]
    (let [t0 (System/currentTimeMillis)
          out (calc/evaluate-tables [{:cells [["1" "=(loop [] (recur))" "x"]]}])]
      (is (< (- (System/currentTimeMillis) t0) 900))
      (is (re-find #"too long" (get-in out [0 :results 0 1 :error])))
      (is (nil? (get-in out [0 :results 0 2]))))))
