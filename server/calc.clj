(ns calc
  "Formulas in md tables. A body cell starting with =( is a formula, run
  in SCI — no files, no interop, no namespaces beyond SCI's own — with
  the table at hand: (table :a 4 8 :b 1 4) is the values of column a,
  rows 4 to 8, then column b, rows 1 to 4; sum, avg, count, min and max
  take the same ranges. Columns are letters, rows count the body rows
  from 1. A cell is a plain number or nil, unless the table's def block
  — pairs of a range and a parser, a string to strip or a (fn [s] ...)
  — says otherwise. Formula cells are in no range.

  SCI under bb shares bb's own clojure.* vars, so a formula could alter
  the functions of the process it runs in: the server runs formulas
  only in a child bb of their own (evaluate-tables), killed when late."
  (:require [babashka.classpath :as cp]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [sci.core :as sci]))

(def ^:dynamic *timeout-ms*
  "How long a formula, or a def block with the values it parses, may run."
  1000)

(def ^:dynamic *child-ms*
  "How long the child may run, nil for a second per formula plus two."
  nil)

(defn col-index
  "Column keyword :a → 0, :z → 25, :aa → 26; nil for anything else."
  [k]
  (when (and (keyword? k) (nil? (namespace k)) (re-matches #"[a-z]+" (name k)))
    (dec (reduce (fn [n c] (+ (* n 26) (- (int c) 96))) 0 (name k)))))

(defn- col-name [i]
  (loop [i (inc i) s ""]
    (if (zero? i) (keyword s) (recur (quot (dec i) 26) (str (char (+ 97 (mod (dec i) 26))) s)))))

(defn- fail [& msg] (throw (ex-info (apply str msg) {:calc true})))

(defn- message [^Throwable e] (or (ex-message e) (.getSimpleName (class e))))

(defn ranges
  "The flat range groups of args — a column keyword, then no row (the
  whole column), one, or from and to — as [{:col :from :to}], checked
  against the table's `rows` (and `cols`, when given)."
  ([args rows] (ranges args rows nil))
  ([args rows cols]
   (loop [args (seq args) out []]
     (if-not args
       out
       (let [k (first args)
             col (col-index k)
             nums (take-while number? (rest args))
             [from to] (case (count nums)
                         0 [1 rows]
                         1 [(first nums) (first nums)]
                         2 nums
                         (fail "too many rows after " k ": " (str/join " " nums)))]
         (cond
           (not (keyword? k)) (fail "expected a column keyword like :a, got " (pr-str k))
           (nil? col) (fail "no column " k " — columns are :a, :b, …")
           (and (some? cols) (>= col cols)) (fail "no column " k " — the table has " cols))
         (doseq [r [from to]]
           (cond (not (integer? r)) (fail "row " r " is no whole number")
                 (< r 1) (fail "row " r " — rows count from 1")
                 (> r rows) (fail "row " r " is past the last row (" rows ")")))
         (when (> from to) (fail "row range " from " > " to))
         (recur (seq (drop (inc (count nums)) args)) (conj out {:col col :from from :to to})))))))

(defn number-of
  "The number cell text s is, in plain English notation, else nil."
  [s]
  (let [t (str/trim (str s))]
    (cond (re-matches #"-?\d+" t) (or (parse-long t) (bigint t))
          (re-matches #"-?\d+\.\d+" t) (parse-double t))))

(defn- formula? [s] (str/starts-with? (str/trim (str s)) "=("))

(defn- strip [s p]
  (let [t (str/trim s)]
    (cond (str/starts-with? t p) (subs t (count p))
          (str/ends-with? t p) (subs t 0 (- (count t) (count p)))
          :else t)))

(defn- in? [{:keys [col from to]} r c] (and (= c col) (<= from (inc r) to)))

(defn- values
  "Per cell its value: nil for a formula, else what the last rule
  covering it parses, else number-of."
  [cells rules]
  (vec (map-indexed
        (fn [r row]
          (vec (map-indexed
                (fn [c s]
                  (when-not (formula? s)
                    (let [p (:parser (last (filter (fn [rule] (some #(in? % r c) (:ranges rule))) rules)))]
                      (try (cond (nil? p) (number-of s)
                                 (string? p) (number-of (strip s p))
                                 :else (p s))
                           (catch Throwable e
                             (fail "row " (inc r) ", " (col-name c) ": " (message e)))))))
                row)))
        cells)))

(defn- run
  "(f) within *timeout-ms*, its exception rethrown as it was thrown."
  [f]
  (let [fut (future (f))
        v (try (deref fut *timeout-ms* ::timeout)
               (catch java.util.concurrent.ExecutionException e (throw (or (ex-cause e) e))))]
    (when (= ::timeout v)
      (future-cancel fut)
      (fail "took too long (over " *timeout-ms* " ms)"))
    v))

(defn- show
  "A result as the cell shows it."
  [v]
  (cond (nil? v) ""
        (string? v) v
        (ratio? v) (str (double v))
        (instance? clojure.lang.BigInt v) (str v)
        :else (binding [*print-length* 100] (pr-str v))))

(defn- context
  "A SCI context whose range functions read the values in `vals`."
  [vals rows cols]
  (let [table (fn [& args]
                (vec (for [{:keys [col from to]} (ranges args rows cols)
                           r (range (dec from) to)
                           :let [v (get-in @vals [r col])]
                           :when (some? v)]
                       v)))
        ranged? (fn [args] (keyword? (first args)))
        nums (fn [args] (cond (ranged? args) (apply table args)
                              (and (= 1 (count args)) (coll? (first args))) (first args)
                              :else args))
        sum (fn [& args] (reduce + 0 (nums args)))]
    (sci/init {:namespaces
               {'user {'table table
                       'sum sum
                       'avg (fn [& args] (let [xs (nums args)]
                                           (when (seq xs) (/ (double (reduce + 0 xs)) (count xs)))))
                       'count (fn [& args] (if (ranged? args) (count (apply table args)) (apply count args)))
                       'min (fn [& args] (apply min (if (ranged? args) (apply table args) args)))
                       'max (fn [& args] (apply max (if (ranged? args) (apply table args) args)))}}})))

(defn- eval-in
  "The value of source s in ctx's user namespace — pinned, as SCI
  otherwise reads in the namespace its caller happens to be in."
  [ctx s]
  (:val (sci/eval-string+ ctx s {:ns (sci/find-ns ctx 'user)})))

(defn- rules-of
  "The def block's rules, [{:ranges :parser}], its forms evaluated in ctx."
  [ctx defs rows cols]
  (let [forms (eval-in ctx (str "[" defs "\n]"))]
    (when (odd? (count forms))
      (fail "the def block takes pairs of a range and a parser"))
    (mapv (fn [[rng p]]
            (when-not (vector? rng) (fail "expected a range like [:a 1 3], got " (pr-str rng)))
            (when-not (or (string? p) (fn? p)) (fail "a parser is a string or a fn, got " (pr-str p)))
            {:ranges (ranges rng rows cols) :parser p})
          (partition 2 forms))))

(defn evaluate
  "A table's formula results: {:results grid} where grid has the shape
  of `cells` (the body rows), nil but at formulas, which hold {:value s}
  or {:error s}; plus :error when the def block fails."
  [{:keys [cells defs]}]
  (let [cells (vec (map vec cells))
        rows (count cells)
        cols (apply max 0 (map count cells))
        blank (mapv (fn [row] (mapv (constantly nil) row)) cells)]
    (if-not (some formula? (flatten cells))
      {:results blank}
      (let [vals (atom nil)
            ctx (context vals rows cols)
            err (try (reset! vals (run #(values cells (if (str/blank? defs) [] (rules-of ctx defs rows cols)))))
                     nil
                     (catch Throwable e (str "def block: " (message e))))
            cell (fn [s]
                   (when (formula? s)
                     (if (some? err)
                       {:error err}
                       (try {:value (run #(show (eval-in (sci/fork ctx) (subs (str/trim s) 1))))}
                            (catch Throwable e {:error (message e)})))))]
        (cond-> {:results (mapv (fn [row] (mapv cell row)) cells)}
          (some? err) (assoc :error err))))))

(defn child
  "The child's main: tables as JSON on stdin, their results as JSON on
  stdout, then exit — whatever a formula left running or altered goes
  with the process."
  []
  (let [tables (:tables (json/parse-string (slurp *in*) true))
        out (try (json/generate-string {:tables (mapv evaluate tables)})
                 (catch Throwable e (json/generate-string {:error (message e)})))]
    (println out)
    (flush)
    (System/exit 0)))

(defn- bb-path []
  (or (.orElse (.command (.info (java.lang.ProcessHandle/current))) nil) "bb"))

(defn- failed
  "The results of `tables` with every formula failing with `msg`."
  [tables msg]
  (mapv (fn [{:keys [cells]}]
          {:results (mapv (fn [row] (mapv (fn [s] (when (formula? s) {:error msg})) row)) cells)})
        tables))

(defn evaluate-tables
  "calc/evaluate of each table, run in a child bb that is gone after —
  killed past *child-ms*. A table without formulas needs no child."
  [tables]
  (let [n (count (filter formula? (mapcat flatten (map :cells tables))))]
    (if (zero? n)
      (mapv evaluate tables)
      (let [ms (or *child-ms* (min 30000 (+ 2000 (* n *timeout-ms*))))
            proc (p/process {:in (json/generate-string {:tables tables})
                             :out :string :err :string
                             :dir (System/getProperty "java.io.tmpdir")}
                            (bb-path) "-cp" (cp/get-classpath) "-e" "(require 'calc) (calc/child)")
            done (deref proc ms ::late)]
        (if (= ::late done)
          (do (p/destroy-tree proc)
              (failed tables (str "took too long (over " ms " ms)")))
          (let [out (try (json/parse-string (:out done) true) (catch Exception _ nil))]
            (cond
              (some? (:tables out)) (:tables out)
              (some? (:error out)) (failed tables (:error out))
              :else (failed tables (str "the calculation failed" (some->> (:err done) str/trim not-empty (str ": ")))))))))))
