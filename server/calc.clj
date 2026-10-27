(ns calc
  "Formulas in md tables. A body cell starting with =( is a formula, run
  in SCI — no files, no interop, no namespaces beyond SCI's own — with
  the table at hand: (table :a 4 8 :b 1 4) is the values of column a,
  rows 4 to 8, then column b, rows 1 to 4; sum, avg, count, min and max
  take the same ranges. Columns are letters, rows count the body rows
  from 1 — or the records of the data the table's def block links
  (with-data). A cell's value is its number, else its text; formula
  cells are in no range. The def block's other pairs, a range and a fn,
  format the cells they cover: formulas always see the plain values.

  SCI under bb shares bb's own clojure.* vars, so a formula could alter
  the functions of the process it runs in: the server runs formulas
  only in a child bb of their own (evaluate-tables), killed when late."
  (:require [babashka.classpath :as cp]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [mdlinks]
            [sci.core :as sci]
            [themes]))

(def ^:dynamic *timeout-ms*
  "How long a formula, a cell's formatting or the def block may run."
  1000)

(def ^:dynamic *child-ms*
  "How long the child may run; nil for evaluate-tables' estimate."
  nil)

(def ^:dynamic *child-heap-mb*
  "The child's heap, in MiB: past it an allocation fails. A 50 MiB data
  file, the most the server reads, needs about 1.5 GiB."
  2048)

(def ^:private max-reply-bytes
  "The most the child may answer: past it the child is killed. A chart
  alone's data is the parent's own read, never sent back."
  (* 4 1024 1024))

(def ^:private max-err-bytes
  "The most of the child's stderr kept for the failure message."
  (* 64 1024))

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
         (doseq [r (if (seq nums) [from to] [])]
           (cond (not (integer? r)) (fail "row " r " is no whole number")
                 (< r 1) (fail "row " r " — rows count from 1")
                 (> r rows) (fail "row " r " is past the last row (" rows ")")))
         (when (and (seq nums) (> from to)) (fail "row range " from " > " to))
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
  has every value, the others the numbers only; (rows) is every row as
  a map of column keyword to value. Columns are unchecked while there
  are no rows to count them by."
  [vals]
  (let [rows (count vals)
        cols (when (seq vals) (apply max 0 (map count vals)))
        records (delay (let [ks (mapv col-name (range (or cols 0)))]
                         (mapv #(zipmap ks %) vals)))
        table (fn [& args]
                (vec (for [{:keys [col from to]} (ranges args rows cols)
                           r (range (dec from) to)
                           :let [v (get-in vals [r col])]
                           :when (some? v)]
                       v)))
        ranged? (fn [args] (keyword? (first args)))
        nums (fn [args] (cond (ranged? args) (filterv number? (apply table args))
                              (and (= 1 (count args)) (coll? (first args))) (filterv number? (first args))
                              :else args))]
    (sci/init {:namespaces
               {'user {'table table
                       'rows (fn [] @records)
                       'sum (fn [& args] (reduce + 0 (nums args)))
                       'avg (fn [& args] (let [xs (nums args)]
                                           (when (seq xs) (/ (double (reduce + 0 xs)) (count xs)))))
                       'count (fn [& args] (if (ranged? args) (count (nums args)) (apply count args)))
                       'min (fn [& args] (apply min (nums args)))
                       'max (fn [& args] (apply max (nums args)))}}})))

(defn- eval-in
  "The value of source s in ctx's user namespace — pinned, as SCI
  otherwise reads in the namespace its caller happens to be in."
  [ctx s]
  (:val (sci/eval-string+ ctx s {:ns (sci/find-ns ctx 'user)})))

(defn- two-args?
  "Whether form, as written, is a fn one of whose arities takes two:
  (fn [v ctx] ...), #(... %2). Read off the source, as SCI's fn objects
  don't tell their arities."
  [form]
  (and (seq? form) (contains? '#{fn fn*} (first form))
       (let [body (cond-> (rest form) (symbol? (second form)) rest)]
         (some #(= 2 (count %)) (if (vector? (first body)) [(first body)] (map first body))))))

(defn- rules-of
  "The def block's rules, [{:ranges :f}] top to bottom, its forms
  evaluated in ctx; each :f takes (v ctx) and hands ctx on only to a
  fn of two arguments, so str, inc and #(...) of %1 get v alone."
  [ctx defs rows cols]
  (let [src (str "[" defs "\n]")
        forms (eval-in ctx src)]
    (when (odd? (count forms))
      (fail "the def block takes pairs of a range and a fn"))
    (mapv (fn [[rng f] [_ form]]
            (when-not (vector? rng) (fail "expected a range like [:a 1 3], got " (pr-str rng)))
            (when-not (fn? f) (fail "expected a fn after " (pr-str rng) ", got " (pr-str f)))
            {:ranges (ranges rng rows cols) :f (if (two-args? form) f (fn [v _] (f v)))})
          (partition 2 forms)
          (partition 2 (sci/parse-string ctx src)))))

(def ^:private style-keys #{:color :background :border})

(defn- chain
  "{:v :style} after each of fs, top to bottom: a map's colours add to
  the style and its :text replaces v, anything else replaces v."
  [fs v ctx]
  (reduce (fn [acc f]
            (let [out (f (:v acc) ctx)]
              (if (map? out)
                (let [bad (first (remove (conj style-keys :text) (keys out)))]
                  (when (some? bad) (fail "a map takes :text :color :background :border, not " (pr-str bad)))
                  (cond-> (update acc :style merge (update-vals (select-keys out style-keys)
                                                                (fn [c] (if (keyword? c) (name c) (str c)))))
                    (contains? out :text) (assoc :v (:text out))))
                (assoc acc :v out))))
          {:v v :style {}}
          fs))

(defn- table-results
  "A table's results: {:results grid} where grid has the shape of
  `cells` (the body rows) and holds nil for a cell left as it is, else
  {:value s} with :style {:color :background :border} when a def block
  fn gave one, or {:error s}; plus :error when the def block fails.
  A cell's value — a formula's result, else the cell's — goes through
  the fns of every pair covering it, top to bottom. With `data` (rows of
  values, with-data) formula ranges address the data, not the table;
  `data-error` fails every formula, as a failing def block does. With
  `num?` a formula's number is in its result too, as :num, for a chart."
  [{:keys [cells defs data data-error]} num?]
  (let [cells (vec (map vec cells))
        rows (count cells)
        cols (apply max 0 (map count cells))
        blank (mapv (fn [row] (mapv (constantly nil) row)) cells)]
    (if (and (not (some formula? (flatten cells))) (str/blank? defs) (nil? data-error))
      {:results blank}
      (let [ctx (context (if (some? data) (mapv vec data) (values cells)))
            [rules block-err] (if (str/blank? defs)
                                [[] nil]
                                (try [(run #(rules-of ctx defs rows cols)) nil]
                                     (catch Throwable e [[] (str "def block: " (message e))])))
            err (some->> (remove nil? [data-error block-err]) seq (str/join "; "))
            cell (fn [r c s]
                   (let [f? (formula? s)
                         fs (keep (fn [{:keys [ranges f]}] (when (some #(in? % r c) ranges) f)) rules)]
                     (cond
                       (and f? (some? err)) {:error err}
                       (and (not f?) (or (empty? fs) (nil? (value-of s)))) nil
                       :else
                       ;; a plain value the fns leave as it was keeps the cell as written
                       (try (run #(let [v0 (if f? (eval-in (sci/fork ctx) (subs (str/trim s) 1)) (value-of s))
                                        {:keys [v style]} (chain fs v0 {:row (inc r) :col (col-name c) :raw s})]
                                    (not-empty (cond-> {}
                                                 (or f? (not= v v0)) (assoc :value (show v))
                                                 (and num? f? (number? v0)) (assoc :num (if (ratio? v0) (double v0) v0))
                                                 (seq style) (assoc :style style)))))
                            (catch Throwable e {:error (message e)})))))]
        (cond-> {:results (vec (map-indexed (fn [r row] (vec (map-indexed (fn [c s] (cell r c s)) row))) cells))}
          (some? err) (assoc :error err))))))

;; ---------------------------------------------------------------- charts

(def ^:private chart-keys [:kind :x :y :rows :stack :horizontal :title])

(def ^:private chart-flags [:stack :horizontal])

(def ^:private max-chart-rows
  "The rows a chart draws at most: past them the page would stall."
  20000)

(defn- flags-of
  "The chart flags of m that are true, each as true."
  [m]
  (into {} (for [k chart-flags :when (true? (get m k))] [k true])))

(defn chart-spec
  "A chart block's map, read and checked against a table of `cols`
  columns and `rows` rows: {:kind \"bar\" :x 0 :y [1]}, columns as
  indexes from 0, plus :rows [from to], :stack, :horizontal and :title
  when it has them."
  [text cols rows]
  (let [m (try (edn/read-string (str text))
               (catch StackOverflowError _ (fail "the chart spec doesn't read: it nests too deeply"))
               (catch Exception e (fail "the chart spec doesn't read: " (message e))))
        col (fn [k]
              (let [i (col-index k)]
                (cond (nil? i) (fail "expected a column keyword like :a, got " (pr-str k))
                      (>= i cols) (fail "no column " k " — the chart has " cols)
                      :else i)))]
    (when-not (map? m) (fail "a chart is a map, like {:kind :bar :x :a :y [:b]}"))
    (let [ys (let [y (:y m)] (if (vector? y) y [y]))]
      (when-let [bad (first (remove (set chart-keys) (keys m)))]
        (fail "a chart takes " (str/join " " chart-keys) ", not " (pr-str bad)))
      (when-not (#{:bar :line :scatter} (:kind m)) (fail ":kind is :bar, :line or :scatter"))
      (when (nil? (:x m)) (fail "a chart names its :x column"))
      (when (or (empty? ys) (some nil? ys)) (fail ":y names the columns to chart, like :y [:b :c]"))
      (when-some [r (:rows m)]
        (when-not (and (vector? r) (= 2 (count r)) (every? integer? r)) (fail ":rows is [from to], like [1 4]"))
        (ranges (into [:a] r) rows))
      (when (and (contains? m :title) (not (string? (:title m)))) (fail ":title is a string"))
      (doseq [k chart-flags]
        (when (and (contains? m k) (not (boolean? (get m k)))) (fail k " is true or false")))
      (when (zero? rows) (fail "there are no rows to chart"))
      (when (> rows max-chart-rows) (fail "there are " rows " rows; a chart draws " max-chart-rows " at most"))
      (cond-> (merge {:kind (name (:kind m)) :x (col (:x m)) :y (mapv col ys)} (flags-of m))
        (some? (:rows m)) (assoc :rows (:rows m))
        (some? (:title m)) (assoc :title (:title m))))))

(defn- field-name [f]
  (cond (keyword? f) (name f)
        (vector? f) (str/join "." (map #(if (keyword? %) (name %) (str %)) f))
        :else (str f)))

(defn- with-nums
  "A chart table's results with each plain number cell's number as :num,
  as a formula's has: the page charts every number the way the server
  reads it."
  [results cells]
  (mapv (fn [res-row row]
          (mapv (fn [res s] (if-let [n (when-not (formula? s) (number-of s))] (assoc res :num n) res))
                res-row row))
        results cells))

(defn- solo-chart
  "A :solo chart's result: no table, its spec over its block's data, the
  rows as :data and the names of the fields its second >> line lists as
  :fields."
  [{:keys [data data-error fields chart]}]
  (let [names (cond (some? data-error) (fail data-error)
                    (nil? data) (fail "a chart alone links its data first: >> [sales](sales.csv)")
                    (empty? fields) (fail "a chart alone names its fields: >> [\"units\" \"price\"]")
                    :else (mapv field-name fields))]
    {:results [] :chart (chart-spec chart (count names) (count data)) :data data :fields names}))

(defn evaluate
  "table-results of a table; for one with a :chart block below it, every
  number cell's :num too and its spec as chart-spec reads it, or
  :chart-error. A :solo chart is solo-chart's, or its :chart-error."
  [t]
  (cond
    (:solo t) (try (solo-chart t) (catch Exception e {:results [] :chart-error (message e)}))
    (nil? (:chart t)) (table-results t false)
    :else (let [cells (:cells t)
                out (update (table-results t true) :results with-nums cells)]
            (try (assoc out :chart (chart-spec (:chart t) (apply max 0 (map count cells)) (count cells)))
                 (catch Exception e (assoc out :chart-error (message e)))))))

;; ---------------------------------------------------------------- data

(defn split-block
  "A def block's data lines and the rest: >> [text](data.csv) first,
  then optionally >> [field ...] — keywords, strings or get-in paths."
  [defs]
  (let [lines (str/split-lines (str defs))
        head (take-while #(str/starts-with? (str/trim %) ">>") lines)
        rest-text (str/join "\n" (drop (count head) lines))]
    (when (> (count head) 2) (fail "a block takes two >> lines at most: the data, then its fields"))
    (if (empty? head)
      {:source nil :fields nil :defs defs}
      (let [link (first (filter #(= "inline" (:kind %)) (mdlinks/links (subs (str/trim (first head)) 2))))
            fields (when-let [l (second head)]
                     (let [f (try (edn/read-string (subs (str/trim l) 2)) (catch Exception _ nil))]
                       (when-not (vector? f) (fail "the second >> line is a vector of fields, like >> [:item :qty]"))
                       f))]
        (when (nil? link) (fail "the first >> line links the data: >> [sales](data/sales.csv)"))
        {:source (:dest link) :fields fields :defs rest-text}))))

(defn- lookup [m k]
  (cond (map? m) (let [v (get m k)]
                   (cond (some? v) v
                         (keyword? k) (get m (name k))
                         (string? k) (get m (keyword k))))
        (and (sequential? m) (integer? k)) (nth m k nil)))

(defn- plain? [v] (or (nil? v) (number? v) (string? v) (boolean? v)))

(defn- plain [v] (if (plain? v) v (pr-str v)))

(defn records
  "The rows of values in data file text of type ext: a csv's columns,
  all or those `fields` names, its first line the names; each record of
  a json array, jsonl lines or edn vector the values at `fields`."
  [ext text fields]
  (let [pick (fn [ms] (mapv (fn [m] (mapv (fn [f] (plain (reduce lookup m (if (vector? f) f [f])))) fields)) ms))
        need-fields #(when (empty? fields) (fail "list the fields of a ." ext " file: >> [:item :qty]"))]
    (case ext
      "csv" (let [[header & body] (csv/read-csv text)
                  idx (if (empty? fields)
                        (range (count header))
                        (mapv (fn [f] (let [n (if (keyword? f) (name f) (str f))
                                            i (.indexOf ^java.util.List (vec header) n)]
                                        (when (neg? i) (fail "no column " (pr-str n) " in the csv"))
                                        i))
                              fields))]
              (mapv (fn [row] (mapv (fn [i] (value-of (get row i))) idx))
                    (remove #(every? str/blank? %) body)))
 ("json" "edn") (do (need-fields)
                         (let [v (if (= "json" ext) (json/parse-string text) (edn/read-string text))]
                           (when-not (and (sequential? v) (every? map? v))
                             (fail (if (= "json" ext) "a json file holds an array of objects" "an edn file holds a vector of maps")))
                           (pick v)))
      "jsonl" (do (need-fields)
                  (pick (keep-indexed (fn [i l]
                                        (when-not (str/blank? l)
                                          (let [m (try (json/parse-string l) (catch Exception _ nil))]
                                            (when-not (map? m) (fail "line " (inc i) " is no json object"))
                                            m)))
                                      (str/split-lines text))))
      (fail "." ext " files can't be read — csv, jsonl, json or edn"))))

(defn with-data
  "The tables with their def block's data read: (read dest) is [ext
  text] for the block's >> link or throws; a table gets :data, its rows,
  and :defs without the >> lines — or :data-error. Tables linking the
  same data with the same fields share one read."
  [tables read]
  (let [data (memoize (fn [source fields] (let [[ext text] (read source)] (records ext text fields))))]
   (mapv (fn [t]
          (let [{:keys [source fields defs error]} (try (split-block (:defs t))
                                                        (catch Exception e {:error (message e) :defs ""}))]
            (cond
              (some? error) (assoc t :defs defs :data-error error)
              (nil? source) t
              :else (try (cond-> (assoc t :defs defs :data (data source fields))
                           (:solo t) (assoc :fields fields))
                         ;; a data file nested past the stack is an Error
                         (catch StackOverflowError _ (assoc t :defs defs :data-error "the data file nests too deeply to read"))
                         (catch Exception e (assoc t :defs defs :data-error (message e)))))))
        tables)))

;; ---------------------------------------------------------------- checking

(def ^:private sep-re #"^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$")

(defn- table-above
  "{:cols :rows} of the table ending right above line i, blank lines
  between allowed — its columns by the separator line — or nil. Its
  lines are those with a pipe, as a table's header has; a body row
  without one is rare enough to miss."
  [lines i]
  (let [j (loop [j (dec i)] (if (and (>= j 0) (str/blank? (nth lines j))) (recur (dec j)) j))
        piped? (fn [k] (str/includes? (nth lines k) "|"))
        s (loop [s j] (if (and (pos? s) (piped? (dec s))) (recur (dec s)) s))]
    (when (and (>= j 0) (piped? j) (< s j) (re-matches sep-re (nth lines (inc s))))
      (let [sep (str/replace (str/trim (nth lines (inc s))) #"^\||\|$" "")]
        {:cols (count (str/split sep #"\|")) :rows (- j s 1)}))))

(defn chart-warnings
  "What is wrong with the chart blocks of md `text`, as the page would
  show it, one [N message] each, N the line of the block's fence: a
  simpleviz-chart-table block not right below a table, a spec that
  doesn't read or names a column or row the table lacks, a
  simpleviz-chart block whose data (read as with-data does) or fields
  are missing, or a block never closed. Formulas don't run."
  [text read]
  (if-not (str/includes? (str text) "simpleviz-chart")
    []
    (let [lines (vec (str/split (mdlinks/normalize-breaks (str text)) #"\n" -1))]
      (vec (for [{:keys [line info body closed]} (mdlinks/fences text)
                 :when (#{"simpleviz-chart" "simpleviz-chart-table"} info)
                 :let [w (cond
                           (not closed) (str "the " info " block is never closed")
                           (= info "simpleviz-chart-table")
                           (if-let [{:keys [cols rows]} (table-above lines (dec line))]
                             (try (chart-spec (str/join "\n" body) cols rows) nil
                                  (catch Exception e (str "chart: " (message e))))
                             "a simpleviz-chart-table block goes right below a table")
                           :else
                           (let [head (take-while #(str/starts-with? (str/trim %) ">>") body)
                                 [t] (with-data [{:cells [] :defs (str/join "\n" head) :solo true
                                                  :chart (str/join "\n" (drop (count head) body))}]
                                                read)]
                             (some->> (:chart-error (evaluate t)) (str "chart: "))))]
                 :when w]
             [line w])))))

(defn child
  "The child's main: tables as JSON on stdin, their results as JSON on
  stdout, then exit — whatever a formula left running or altered goes
  with the process. What formulas print goes nowhere, and the reply
  goes to the writer stdout was before any formula ran."
  []
  (let [stdout *out*
        tables (:tables (json/parse-string (slurp *in*) true))
        out (binding [*out* (java.io.StringWriter.)]
              (try (json/generate-string {:tables (mapv #(dissoc (evaluate %) :data :fields) tables)})
                   (catch Throwable e (json/generate-string {:error (message e)}))))]
    (.write ^java.io.Writer stdout (str out "\n"))
    (.flush ^java.io.Writer stdout)
    (System/exit 0)))

(defn bb-path
  "The bb this process runs on, for a child of its own (analyze's too)."
  []
  (or (.orElse (.command (.info (java.lang.ProcessHandle/current))) nil) "bb"))

(defn classpath
  "This process's classpath, its entries absolute — the child runs
  elsewhere."
  []
  (->> (str/split (cp/get-classpath) (re-pattern java.io.File/pathSeparator))
       (map (fn [e] (.getAbsolutePath (java.io.File. ^String e))))
       (str/join java.io.File/pathSeparator)))

(defn- failed
  "The results of `tables` with every formula and chart failing with
  `msg`."
  [tables msg]
  (mapv (fn [{:keys [cells chart]}]
          (cond-> {:results (mapv (fn [row] (mapv (fn [s] (when (formula? s) {:error msg})) row)) cells)}
            (some? chart) (assoc :chart-error msg)))
        tables))

(defn- colour? [v]
  (and (string? v) (or (re-matches #"[a-zA-Z]{3,30}" v) (re-matches themes/color-re v))))

(defn- checked-cell
  "A child's result cell as the page may get it — the child ran the
  md file's code, so only strings and colours pass: a style value that
  is no colour name, #hex, rgb() or hsl() becomes the cell's error."
  [cell]
  (when (map? cell)
    (let [style (:style cell)
          bad (when (map? style)
                (first (remove (fn [[k v]] (and (style-keys k) (colour? v))) style)))]
      (cond
        (string? (:error cell)) {:error (:error cell)}
        (some? bad) {:error (str "not a colour: " (pr-str (val bad)))}
        :else
        (not-empty (cond-> {}
                     (string? (:value cell)) (assoc :value (:value cell))
                     (number? (:num cell)) (assoc :num (:num cell))
                     (seq style) (assoc :style (select-keys style style-keys))))))))

(defn- checked-chart
  "A child's chart spec as the page may get it: the keys chart-spec
  writes, with the types it writes them in; nil for anything else."
  [c]
  (when (and (map? c) (string? (:kind c)) (int? (:x c)) (sequential? (:y c)) (every? int? (:y c)))
    (cond-> (select-keys c [:kind :x :y])
      (and (sequential? (:rows c)) (every? int? (:rows c))) (assoc :rows (vec (:rows c)))
      (string? (:title c)) (assoc :title (:title c))
      true (merge (flags-of c)))))

(defn- checked
  "The child's results `out` for `tables` as the page may get them; a
  chart alone takes its data and field names from its table."
  [tables out]
  (mapv (fn [t o] (let [chart (checked-chart (:chart o))]
                    (cond-> {:results (mapv (fn [row] (mapv checked-cell row)) (:results o))}
                      (string? (:error o)) (assoc :error (:error o))
                      (some? chart) (assoc :chart chart)
                      (string? (:chart-error o)) (assoc :chart-error (:chart-error o))
                      (and (:solo t) (some? chart) (sequential? (:data t))
                           (every? #(and (sequential? %) (every? plain? %)) (:data t)))
                      (assoc :data (:data t) :fields (mapv field-name (:fields t))))))
        tables out))

(defn- read-capped
  "Reads stream `in` to its end in a future: {:text its first `limit`
  bytes, :over? whether there were more}; (over) runs once when there
  are."
  [^java.io.InputStream in limit over]
  (future
    (let [buf (java.io.ByteArrayOutputStream.)
          chunk (byte-array 8192)]
      (loop [over? false]
        (let [n (.read in chunk)]
          (if (neg? n)
            {:text (.toString buf "UTF-8") :over? over?}
            (let [room (- limit (.size buf))]
              (.write buf chunk 0 (int (max 0 (min n room))))
              (when (and (not over?) (> n room)) (over))
              (recur (or over? (> n room))))))))))

(defn evaluate-tables
  "calc/evaluate of each table, run in a child bb that is gone after —
  killed past *child-ms*, by default a second for each formula, each
  def block and each cell one may format, plus two, or once its reply
  passes max-reply-bytes; its heap is *child-heap-mb*. A table without
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
                             :dir (System/getProperty "java.io.tmpdir")}
                            (bb-path) (str "-Xmx" *child-heap-mb* "m")
                            "-cp" (classpath) "-e" "(require 'calc) (calc/child)")
            reply (read-capped (:out proc) max-reply-bytes #(p/destroy-tree proc))
            err (read-capped (:err proc) max-err-bytes (constantly nil))
            done (deref proc ms ::late)]
        (cond
          (= ::late done)
          (do (p/destroy-tree proc)
              (failed tables (str "took too long (over " ms " ms)")))
          (:over? @reply)
          (failed tables (str "the results are over " (quot max-reply-bytes (* 1024 1024)) " MiB"))
          :else
          (let [out (try (json/parse-string (:text @reply) true) (catch Exception _ nil))]
            (cond
              (sequential? (:tables out)) (checked tables (:tables out))
              (some? (:error out)) (failed tables (:error out))
              :else (failed tables (str "the calculation failed" (some->> (:text @err) str/trim not-empty (str ": ")))))))))))
