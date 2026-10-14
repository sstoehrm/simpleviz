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

(deftest text-cells-are-text-and-the-maths-skips-them
  (let [out (results [["2" "x" "" "=(table :a 1 1 :b :c)" "=(count :a :b)" "=(max :a :b)"]])]
    (is (= "[2 \"x\"]" (value-at out 0 3)))
    (is (= "1" (value-at out 0 4)))
    (is (= "2" (value-at out 0 5)))))

(deftest def-block-formatters
  (let [cells [["0.12" "3.5" "a" ""] ["0.5" "-2" "b" ""] ["=(sum :a)" "=(sum :b)" "c" "=(sum :b)"]]
        cell (fn [defs r c] (get-in (results cells defs) [:results r c]))]
    (testing "a fn's string or number replaces the value; formulas see the raw one"
      (is (= {:value "12.0%"} (cell "[:a] #(str (* %1 100) \"%\")" 0 0)))
      (is (= {:value "62.0%"} (cell "[:a] #(str (* %1 100) \"%\")" 2 0)))
      (is (= {:value "1,5 €"} (cell "[:b] #(str (clojure.string/replace (str %1) \".\" \",\") \" €\")" 2 1))))
    (testing "the second argument is the cell's place"
      (is (= {:value "2 :b -2"} (cell "[:b 2] (fn [v ctx] (str (:row ctx) \" \" (:col ctx) \" \" (:raw ctx)))" 1 1))))
    (testing "a map styles the cell, its :text replaces the value"
      (is (= {:style {:color "red"}}
             (cell "[:b] (fn [v _] (if (neg? v) {:color \"red\"} v))" 1 1)))
      (is (= {:value "neg" :style {:background "#fee" :border "rgb(200, 0, 0)"}}
             (cell "[:b] (fn [v _] {:text \"neg\" :background \"#fee\" :border \"rgb(200, 0, 0)\"})" 1 1))))
    (testing "pairs chain from top to bottom"
      (is (= {:value "-200%" :style {:color "red"}}
             (cell "[:b] #(* %1 100)\n[:b 2] #(str %1 \"%\")\n[:b] (fn [v _] {:color \"red\"})" 1 1))))
    (testing "blank cells and cells outside every range are left alone"
      (is (nil? (cell "[:d] (fn [v _] {:color \"red\"})" 0 3)))
      (is (nil? (cell "[:a] str" 0 2))))
    (testing "a failing fn or a bad map is the cell's error"
      (is (some? (:error (cell "[:c] #(* %1 2)" 0 2))))
      (is (re-find #":size" (:error (cell "[:a] (fn [v _] {:size 3})" 0 0)))))
    (testing "a bad block is the table's error"
      (is (re-find #"pairs" (:error (results cells "[:a]"))))
      (is (re-find #"row 9" (:error (results cells "[:a 9] str"))))
      (is (re-find #"a fn" (:error (results cells "[:a] \"%\"")))))))

(deftest results-print-plainly
  (let [f (fn [formula] (value-at (results [[formula]]) 0 0))]
    (is (= "0.5" (f "=(/ 1 2)")) "a ratio shows as a decimal")
    (is (= "hi" (f "=(str \"hi\")")))
    (is (= "" (f "=(first [])")))
    (is (= ":x" (f "=(keyword \"x\")")))))

(deftest a-table-without-formulas-or-block-is-left-alone
  (is (= {:results [[nil]]} (results [["1"]]))))

(deftest formulas-run-in-user-whatever-the-callers-ns
  (binding [*ns* (create-ns 'calc-test.elsewhere)]
    (is (= "1" (value-at (results [["1" "=(sum :a)"]]) 0 1)))
    (is (= "1!" (value-at (results [["1" "=(sum :a)"]] "[:b] #(str %1 \"!\")") 0 1)))))

(deftest a-stack-overflow-stays-in-its-cell
  (let [out (results [["1" "=((fn f [x] (f x)) 1)" "=(sum :a)"]])]
    (is (some? (error-at out 0 1)))
    (is (= "1" (value-at out 0 2))))
  (is (some? (error-at (results [["1"]] "[:a] (fn f [s] (f s))") 0 0))))

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
  (is (= [{:results [[nil {:value "1"}]]} {:results [[nil]]} {:results [[{:value "12"}]]}
          {:results [[{:style {:color "green"}}]]}]
         (calc/evaluate-tables [{:cells [["1" "=(sum :a)"]]}
                                {:cells [["2"]]}
                                {:cells [["=(+ 5 7)"]]}
                                {:cells [["2"]] :defs "[:a] (fn [v _] {:color :green})"}]))))

(deftest the-server-lets-only-colours-through
  (doseq [bad ["red; background: url(http://x)" "url(http://x)" "#12" "expression(1)" "rgb(1,2,3);x"]]
    (is (re-find #"colour" (get-in (calc/evaluate-tables
                                     [{:cells [["2"]] :defs (str "[:a] (fn [v _] {:color " (pr-str bad) "})")}])
                                    [0 :results 0 0 :error]))
        bad))
  (is (= {:style {:color "hsl(120deg 50% 50%)"}}
         (get-in (calc/evaluate-tables [{:cells [["2"]] :defs "[:a] (fn [v _] {:color \"hsl(120deg 50% 50%)\"})"}])
                 [0 :results 0 0])))
  (is (= {:style {:background "#ffeedd" :border "rgba(0, 0, 0, .5)" :color "Crimson"}}
         (get-in (calc/evaluate-tables
                  [{:cells [["2"]] :defs "[:a] (fn [v _] {:color \"Crimson\" :background \"#ffeedd\" :border \"rgba(0, 0, 0, .5)\"})"}])
                 [0 :results 0 0]))))

(deftest a-child-past-its-deadline-is-killed
  (binding [calc/*child-ms* 300]
    (let [t0 (System/currentTimeMillis)
          out (calc/evaluate-tables [{:cells [["1" "=(loop [] (recur))" "x"]]}])]
      (is (< (- (System/currentTimeMillis) t0) 3000))
      (is (re-find #"too long" (get-in out [0 :results 0 1 :error])))
      (is (nil? (get-in out [0 :results 0 2]))))))

(deftest a-formula-that-prints-breaks-nothing
  (is (= [{:results [[nil {:value "5"} {:value "1"}]]}]
         (calc/evaluate-tables [{:cells [["1" "=(do (println \"hi\") 5)" "=(sum :a)"]]}]))))

;; data behind a table

(deftest the-block-names-its-data-first
  (is (= {:source "data/s.csv" :fields nil :defs "[:a] str"}
         (calc/split-block ">> [sales](data/s.csv)\n[:a] str")))
  (is (= {:source "s.jsonl" :fields [:item [:price :net]] :defs ""}
         (calc/split-block ">> [s](s.jsonl)\n>> [:item [:price :net]]")))
  (is (= {:source nil :fields nil :defs "[:a] str"} (calc/split-block "[:a] str")))
  (is (thrown-with-msg? Exception #"link" (calc/split-block ">> data.csv")))
  (is (thrown-with-msg? Exception #"vector" (calc/split-block ">> [s](s.json)\n>> :item")))
  (is (thrown-with-msg? Exception #"two" (calc/split-block ">> [s](s.json)\n>> [:a]\n>> [:b]"))))

(deftest records-become-rows-of-values
  (testing "csv: its first line names the columns"
    (is (= [["tea" 2 3.5] ["milk" nil 1.2]]
           (calc/records "csv" "item,qty,price\ntea,2,3.5\nmilk,,1.2\n" nil)))
    (is (= [[3.5 "tea"] [1.2 "milk, fresh"]]
           (calc/records "csv" "item,qty,price\ntea,2,3.5\n\"milk, fresh\",1,1.2" ["price" "item"])))
    (is (thrown-with-msg? Exception #"no column \"cost\"" (calc/records "csv" "a,b\n1,2" ["cost"]))))
  (testing "json, jsonl and edn: records, the fields picked by path"
    (let [fields [:item "qty" [:price :net] :gone]]
      (is (= [["tea" 2 3.5 nil] ["milk" "1" nil nil]]
             (calc/records "json" "[{\"item\":\"tea\",\"qty\":2,\"price\":{\"net\":3.5}},{\"item\":\"milk\",\"qty\":\"1\"}]" fields)))
      (is (= [["tea" 2 3.5 nil]]
             (calc/records "jsonl" "{\"item\":\"tea\",\"qty\":2,\"price\":{\"net\":3.5}}\n\n" fields)))
      (is (= [["tea" nil 3.5 nil] [":x" nil nil nil]]
             (calc/records "edn" "[{:item \"tea\" :price {:net 3.5}} {:item :x}]" fields)))))
  (testing "the wrong shape is an error"
    (is (thrown-with-msg? Exception #"list the fields" (calc/records "json" "[]" nil)))
    (is (thrown-with-msg? Exception #"array of objects" (calc/records "json" "{\"a\":1}" [:a])))
    (is (thrown-with-msg? Exception #"line 2" (calc/records "jsonl" "{\"a\":1}\n[1]" [:a])))
    (is (thrown-with-msg? Exception #"maps" (calc/records "edn" "[1 2]" [:a])))
    (is (thrown-with-msg? Exception #"\.txt" (calc/records "txt" "" [:a])))))

(deftest formulas-address-the-data
  (let [data [["tea" 2 3.5] ["milk" nil 1.2] ["tea" 1 3.0]]
        out (calc/evaluate {:cells [["n" "=(sum :b)"] ["€" "=(reduce + (map #(* (or (:b %) 0) (:c %)) (rows)))"]
                                    ["tea" "=(count (filter #(= \"tea\" (:a %)) (rows)))"] ["last" "=(table :a 3)"]]
                            :data data
                            :defs "[:b 1] #(str %1 \" items\")"})]
    (is (= "3 items" (value-at out 0 1)) "formatter ranges address the table, formula ranges the data")
    (is (= "10.0" (value-at out 1 1)))
    (is (= "2" (value-at out 2 1)))
    (is (= "[\"tea\"]" (value-at out 3 1))))
  (is (re-find #"row 4" (error-at (calc/evaluate {:cells [["=(sum :b 4)"]] :data [[1 2]]}) 0 0))))

(deftest data-errors-are-the-tables
  (let [out (calc/evaluate {:cells [["x" "=(sum :a)"]] :data-error "no such file: s.csv"})]
    (is (re-find #"no such file" (:error out)))
    (is (re-find #"no such file" (error-at out 0 1)))))

(deftest with-data-reads-each-source-through-the-reader
  (let [read (fn [dest] (case dest "s.csv" ["csv" "a,b\n1,2"] (throw (ex-info (str "no such file: " dest) {}))))
        [t1 t2 t3] (calc/with-data [{:cells [["=(sum :b)"]] :defs ">> [s](s.csv)\n[:a] str"}
                                    {:cells [["=(sum :b)"]] :defs ">> [s](gone.csv)"}
                                    {:cells [["1"]]}]
                     read)]
    (is (= {:cells [["=(sum :b)"]] :defs "[:a] str" :data [[1 2]]} t1))
    (is (re-find #"no such file: gone.csv" (:data-error t2)))
    (is (= {:cells [["1"]]} t3))
    (is (= [{:results [[{:value "2"}]]}]
           (calc/evaluate-tables (calc/with-data [{:cells [["=(sum :b)"]] :defs ">> [s](s.csv)"}] read))))))

;; review findings

(deftest a-colour-only-fn-keeps-the-cell-as-written
  (let [out (results [["007" "[a](b.md)"] ["1.50" "x"]] "[:a 1 :a 2 :b 1] (fn [v _] {:color \"red\"})\n[:b 2] (fn [v _] v)")]
    (is (= {:style {:color "red"}} (get-in out [:results 0 0])))
    (is (= {:style {:color "red"}} (get-in out [:results 0 1])))
    (is (= {:style {:color "red"}} (get-in out [:results 1 0])))
    (is (nil? (get-in out [:results 1 1])) "an unchanged value without style is no result")
    (is (= [{:results [[{:style {:color "red"}}]]}]
           (calc/evaluate-tables [{:cells [["007"]] :defs "[:a] (fn [v _] {:color \"red\"})"}])))))

(deftest min-and-max-take-a-collection-like-sum
  (let [f (fn [formula] (value-at (results [["3" "5" formula]]) 0 2))]
    (is (= "5" (f "=(max (table :a :b))")))
    (is (= "3" (f "=(min [5 nil 3])")))
    (is (= "8" (f "=(sum [5 nil 3])")))
    (is (= "7" (f "=(max 1 7)")))))

(deftest empty-data-sums-to-nothing
  (let [out (calc/evaluate {:cells [["=(sum :a)" "=(count :b)" "=(table :a)"]] :data []})]
    (is (= "0" (value-at out 0 0)))
    (is (= "0" (value-at out 0 1)))
    (is (= "[]" (value-at out 0 2)))))

(deftest an-arity-error-inside-the-fn-is-its-own
  (is (re-find #"inc" (error-at (results [["1"]] "[:a] (fn [v ctx] (inc v ctx))") 0 0))))

(deftest a-failing-data-link-keeps-the-formatting
  (let [[t] (calc/with-data [{:cells [["1"]] :defs ">> [s](gone.csv)\n[:a] str"}]
              (fn [d] (throw (ex-info (str "no such file: " d) {}))))]
    (is (= "[:a] str" (:defs t)))
    (is (some? (:data-error t)))
    (is (re-find #"no such file" (:error (calc/evaluate t))))))

(deftest core-fns-format-with-the-value-alone
  (is (= {:value "2"} (get-in (results [["1"]] "[:a] inc") [:results 0 0])))
  (is (= {:value "1"} (get-in (results [["1"]] "[:a] str") [:results 0 0])) "no place map appended")
  (is (= {:value "1!"} (get-in (results [["1"]] "[:a] (fn ([v] (str v \"?\")) ([v ctx] (str v \"!\")))") [:results 0 0]))))

(deftest a-data-link-reads-as-any-md-link
  (is (= "a b.csv" (:source (calc/split-block ">> [s](<a b.csv> \"the sales\")")))))

(deftest a-data-error-shows-without-formulas
  (let [[t] (calc/with-data [{:cells [["1"]] :defs ">> [s](gone.csv)"}] (fn [d] (throw (ex-info (str "no such file: " d) {}))))]
    (is (re-find #"no such file" (:error (first (calc/evaluate-tables [t])))))))
