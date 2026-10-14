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

(defn- in? [{:keys [col from to]} r c] (and (= c col) (<= from (inc r) to)))

(defn- value-of
  "A cell's value: its number, else its text; nil when blank."
  [s]
  (let [t (str/trim (str s))]
    (when-not (= "" t) (or (number-of t) t))))

(defn- values
  "Per cell its value as ranges see it: nil for a formula."
  [cells]
  (mapv (fn [row] (mapv (fn [s] (when-not (formula? s) (value-of s))) row)) cells))

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
  "A SCI context whose range functions read the values in `vals`: table
  has every value, the others the numbers only."
  [vals rows cols]
  (let [table (fn [& args]
                (vec (for [{:keys [col from to]} (ranges args rows cols)
                           r (range (dec from) to)
                           :let [v (get-in vals [r col])]
                           :when (some? v)]
                       v)))
        ranged? (fn [args] (keyword? (first args)))
        nums (fn [args] (cond (ranged? args) (filterv number? (apply table args))
                              (and (= 1 (count args)) (coll? (first args))) (first args)
                              :else args))
        sum (fn [& args] (reduce + 0 (nums args)))]
    (sci/init {:namespaces
               {'user {'table table
                       'sum sum
                       'avg (fn [& args] (let [xs (nums args)]
                                           (when (seq xs) (/ (double (reduce + 0 xs)) (count xs)))))
                       'count (fn [& args] (if (ranged? args) (count (nums args)) (apply count args)))
                       'min (fn [& args] (apply min (if (ranged? args) (nums args) args)))
                       'max (fn [& args] (apply max (if (ranged? args) (nums args) args)))}}})))

(defn- eval-in
  "The value of source s in ctx's user namespace — pinned, as SCI
  otherwise reads in the namespace its caller happens to be in."
  [ctx s]
  (:val (sci/eval-string+ ctx s {:ns (sci/find-ns ctx 'user)})))

(defn- rules-of
  "The def block's rules, [{:ranges :f}] top to bottom, its forms
  evaluated in ctx."
  [ctx defs rows cols]
  (let [forms (eval-in ctx (str "[" defs "\n]"))]
    (when (odd? (count forms))
      (fail "the def block takes pairs of a range and a fn"))
    (mapv (fn [[rng f]]
            (when-not (vector? rng) (fail "expected a range like [:a 1 3], got " (pr-str rng)))
            (when-not (fn? f) (fail "expected a fn after " (pr-str rng) ", got " (pr-str f)))
            {:ranges (ranges rng rows cols) :f f})
          (partition 2 forms))))

(def ^:private style-keys #{:color :background :border})

(defn- call
  "(f v ctx), or (f v) when f takes one argument."
  [f v ctx]
  (try (f v ctx)
       (catch clojure.lang.ArityException e
         (if (str/includes? (str (ex-message e)) "(2)") (f v) (throw e)))))

(defn- chain
  "{:v :style} after each of fs, top to bottom: a map's colours add to
  the style and its :text replaces v, anything else replaces v."
  [fs v ctx]
  (reduce (fn [acc f]
            (let [out (call f (:v acc) ctx)]
              (if (map? out)
                (let [bad (first (remove (conj style-keys :text) (keys out)))]
                  (when (some? bad) (fail "a map takes :text :color :background :border, not " (pr-str bad)))
                  (cond-> (update acc :style merge (update-vals (select-keys out style-keys)
                                                                (fn [c] (if (keyword? c) (name c) (str c)))))
                    (contains? out :text) (assoc :v (:text out))))
                (assoc acc :v out))))
          {:v v :style {}}
          fs))

(defn evaluate
  "A table's results: {:results grid} where grid has the shape of
  `cells` (the body rows) and holds nil for a cell left as it is, else
  {:value s} with :style {:color :background :border} when a def block
  fn gave one, or {:error s}; plus :error when the def block fails.
  A cell's value — a formula's result, else the cell's — goes through
  the fns of every pair covering it, top to bottom."
  [{:keys [cells defs]}]
  (let [cells (vec (map vec cells))
        rows (count cells)
        cols (apply max 0 (map count cells))
        blank (mapv (fn [row] (mapv (constantly nil) row)) cells)]
    (if (and (not (some formula? (flatten cells))) (str/blank? defs))
      {:results blank}
      (let [vals (values cells)
            ctx (context vals rows cols)
            [rules err] (try [(if (str/blank? defs) [] (run #(rules-of ctx defs rows cols))) nil]
                             (catch Throwable e [[] (str "def block: " (message e))]))
            cell (fn [r c s]
                   (let [fs (keep (fn [{:keys [ranges f]}] (when (some #(in? % r c) ranges) f)) rules)
                         ctx-map {:row (inc r) :col (col-name c) :raw s}]
                     (cond
                       (and (formula? s) (some? err)) {:error err}
                       (formula? s)
                       (try (run #(let [{:keys [v style]} (chain fs (eval-in (sci/fork ctx) (subs (str/trim s) 1)) ctx-map)]
                                    (cond-> {:value (show v)} (seq style) (assoc :style style))))
                            (catch Throwable e {:error (message e)}))
                       (or (empty? fs) (nil? (value-of s))) nil
                       :else
                       (try (run #(let [{:keys [v style]} (chain fs (value-of s) ctx-map)]
                                    (cond-> {:value (show v)} (seq style) (assoc :style style))))
                            (catch Throwable e {:error (message e)})))))]
        (cond-> {:results (vec (map-indexed (fn [r row] (vec (map-indexed (fn [c s] (cell r c s)) row))) cells))}
          (some? err) (assoc :error err))))))

(defn child
  "The child's main: tables as JSON on stdin, their results as JSON on
  stdout, then exit — whatever a formula left running or altered goes
  with the process. What formulas print goes nowhere, and the reply
  goes to the writer stdout was before any formula ran."
  []
  (let [stdout *out*
        tables (:tables (json/parse-string (slurp *in*) true))
        out (binding [*out* (java.io.StringWriter.)]
              (try (json/generate-string {:tables (mapv evaluate tables)})
                   (catch Throwable e (json/generate-string {:error (message e)}))))]
    (.write ^java.io.Writer stdout (str out "\n"))
    (.flush ^java.io.Writer stdout)
    (System/exit 0)))

(defn- bb-path []
  (or (.orElse (.command (.info (java.lang.ProcessHandle/current))) nil) "bb"))

(defn- classpath
  "This process's classpath, its entries absolute — the child runs
  elsewhere."
  []
  (->> (str/split (cp/get-classpath) (re-pattern java.io.File/pathSeparator))
       (map (fn [e] (.getAbsolutePath (java.io.File. ^String e))))
       (str/join java.io.File/pathSeparator)))

(defn- failed
  "The results of `tables` with every formula failing with `msg`."
  [tables msg]
  (mapv (fn [{:keys [cells]}]
          {:results (mapv (fn [row] (mapv (fn [s] (when (formula? s) {:error msg})) row)) cells)})
        tables))

(def ^:private colour-re
  #"[a-zA-Z]{3,30}|#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|(?:rgba?|hsla?)\(\s*[0-9.%]+(?:\s*[,/\s]\s*[0-9.%]+){2,3}\s*\)")

(defn- checked-cell
  "A child's result cell as the page may get it — the child ran the
  md file's code, so only strings and colours pass: a style value that
  is no colour name, #hex, rgb() or hsl() becomes the cell's error."
  [cell]
  (when (map? cell)
    (let [style (:style cell)
          bad (when (map? style)
                (first (remove (fn [[k v]] (and (style-keys k) (string? v) (re-matches colour-re v))) style)))]
      (cond
        (string? (:error cell)) {:error (:error cell)}
        (some? bad) {:error (str "not a colour: " (pr-str (val bad)))}
        (string? (:value cell)) (cond-> {:value (:value cell)}
                                  (seq style) (assoc :style (select-keys style style-keys)))))))

(defn- checked [tables]
  (mapv (fn [t] (cond-> {:results (mapv (fn [row] (mapv checked-cell row)) (:results t))}
                  (string? (:error t)) (assoc :error (:error t))))
        tables))

(defn evaluate-tables
  "calc/evaluate of each table, run in a child bb that is gone after —
  killed past *child-ms*, by default a second for each formula, each
  def block and each cell one may format, plus two. A table without
  formulas or def block needs no child."
  [tables]
  (let [defs? (fn [t] (not (str/blank? (:defs t))))
        units (reduce + (map (fn [t] (let [cs (flatten (:cells t))]
                                       (if (defs? t) (inc (count cs)) (count (filter formula? cs)))))
                             tables))]
    (if (zero? units)
      (mapv evaluate tables)
      (let [ms (or *child-ms* (min 60000 (+ 2000 (* units *timeout-ms*))))
            proc (p/process {:in (json/generate-string {:tables tables})
                             :out :string :err :string
                             :dir (System/getProperty "java.io.tmpdir")}
                            (bb-path) "-cp" (classpath) "-e" "(require 'calc) (calc/child)")
            done (deref proc ms ::late)]
        (if (= ::late done)
          (do (p/destroy-tree proc)
              (failed tables (str "took too long (over " ms " ms)")))
          (let [out (try (json/parse-string (:out done) true) (catch Exception _ nil))]
            (cond
              (sequential? (:tables out)) (checked (:tables out))
              (some? (:error out)) (failed tables (:error out))
              :else (failed tables (str "the calculation failed" (some->> (:err done) str/trim not-empty (str ": ")))))))))))
