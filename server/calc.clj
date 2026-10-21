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

(def ^:private chart-keys #{:kind :x :y :rows :stack :horizontal :title})

(defn chart-spec
  "A chart block's map, read and checked against a table of `cols`
  columns and `rows` rows: {:kind \"bar\" :x 0 :y [1]}, columns as
  indexes from 0, plus :rows [from to], :stack, :horizontal and :title
  when it has them."
  [text cols rows]
  (let [m (try (edn/read-string (str text))
               (catch Exception e (fail "the chart spec doesn't read: " (message e))))
        col (fn [k]
              (let [i (col-index k)]
                (cond (nil? i) (fail "expected a column keyword like :a, got " (pr-str k))
                      (>= i cols) (fail "no column " k " — the chart has " cols)
                      :else i)))]
    (when-not (map? m) (fail "a chart is a map, like {:kind :bar :x :a :y [:b]}"))
    (when-let [bad (first (remove chart-keys (keys m)))]
      (fail "a chart takes :kind :x :y :rows :stack :horizontal :title, not " (pr-str bad)))
    (when-not (#{:bar :line :scatter} (:kind m)) (fail ":kind is :bar, :line or :scatter"))
    (when (nil? (:x m)) (fail "a chart names its :x column"))
    (when (nil? (:y m)) (fail "a chart names its :y columns, like :y [:b :c]"))
    (when-some [[from to :as r] (:rows m)]
      (when-not (and (vector? r) (= 2 (count r))) (fail ":rows is [from to], like [1 4]"))
      (ranges [:a from to] rows))
    (cond-> {:kind (name (:kind m)) :x (col (:x m)) :y (mapv col (if (vector? (:y m)) (:y m) [(:y m)]))}
      (some? (:rows m)) (assoc :rows (:rows m))
      (:stack m) (assoc :stack true)
      (:horizontal m) (assoc :horizontal true)
      (string? (:title m)) (assoc :title (:title m)))))

(defn- field-name [f]
  (cond (keyword? f) (name f)
        (vector? f) (str/join "." (map #(if (keyword? %) (name %) (str %)) f))
        :else (str f)))

(defn evaluate
  "table-results of a table, plus, for one with a :chart block, its spec
  as chart-spec reads it, or :chart-error. A :solo chart has no table:
  it charts its block's data, its rows as :data and the names of the
  fields its second >> line lists as :fields."
  [t]
  (let [solo (:solo t)
        out (if solo {:results []} (table-results t (some? (:chart t))))]
    (if (nil? (:chart t))
      out
      (try (let [fields (when solo
                          (cond (some? (:data-error t)) (fail (:data-error t))
                                (nil? (:data t)) (fail "a chart alone links its data first: >> [sales](sales.csv)")
                                (empty? (:fields t)) (fail "a chart alone names its fields: >> [\"units\" \"price\"]")
                                :else (mapv field-name (:fields t))))
                 cells (if solo (:data t) (:cells t))
                 cols (if solo (count fields) (apply max 0 (map count cells)))]
             (cond-> (assoc out :chart (chart-spec (:chart t) cols (count cells)))
               solo (assoc :data (:data t) :fields fields)))
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

(defn- plain [v] (if (or (nil? v) (number? v) (string? v) (boolean? v)) v (pr-str v)))

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
                         (catch Exception e (assoc t :defs defs :data-error (message e)))))))
        tables)))

;; ---------------------------------------------------------------- checking

(def ^:private fence-re #"^ {0,3}(`{3,}|~{3,})[ \t]*([^`\s]*)[^`]*$")
(def ^:private sep-re #"^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$")

(defn- fenced-blocks
  "The top-level fenced blocks of md lines: {:at (index of the opening
  fence) :info :body [lines] :closed}."
  [lines]
  (loop [i 0 out []]
    (if (>= i (count lines))
      out
      (if-let [[_ mark info] (re-matches fence-re (nth lines i))]
        (let [close? (fn [l] (let [t (str/trim l)]
                               (and (str/starts-with? t (subs mark 0 1)) (>= (count t) (count mark))
                                    (re-matches #"(`+|~+)" t))))
              end (first (filter #(close? (nth lines %)) (range (inc i) (count lines))))]
          (recur (if end (inc end) (count lines))
                 (conj out {:at i :info info :closed (some? end)
                            :body (subvec lines (inc i) (or end (count lines)))})))
        (recur (inc i) out)))))

(defn- table-above
  "{:cols :rows} of the table ending right above line i, blank lines
  between allowed — its columns by the separator line — or nil."
  [lines i]
  (let [j (loop [j (dec i)] (if (and (>= j 0) (str/blank? (nth lines j))) (recur (dec j)) j))
        s (loop [s j] (if (and (pos? s) (not (str/blank? (nth lines (dec s))))) (recur (dec s)) s))]
    (when (and (>= j 0) (< (inc s) (count lines)) (<= (inc s) j)
               (re-matches sep-re (nth lines (inc s))) (str/includes? (nth lines s) "|"))
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
  (let [lines (vec (str/split-lines (str text)))]
    (vec (for [{:keys [at info body closed]} (fenced-blocks lines)
               :when (#{"simpleviz-chart" "simpleviz-chart-table"} info)
               :let [w (cond
                         (not closed) (str "the " info " block is never closed")
                         (= info "simpleviz-chart-table")
                         (if-let [{:keys [cols rows]} (table-above lines at)]
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
           [(inc at) w]))))

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

(defn- plain-value? [v] (or (nil? v) (number? v) (string? v) (boolean? v)))

(defn- checked-chart
  "A child's chart spec as the page may get it: the keys chart-spec
  writes, with the types it writes them in; nil for anything else."
  [c]
  (when (and (map? c) (string? (:kind c)) (int? (:x c)) (sequential? (:y c)) (every? int? (:y c)))
    (cond-> (select-keys c [:kind :x :y])
      (and (sequential? (:rows c)) (every? int? (:rows c))) (assoc :rows (vec (:rows c)))
      (true? (:stack c)) (assoc :stack true)
      (true? (:horizontal c)) (assoc :horizontal true)
      (string? (:title c)) (assoc :title (:title c)))))

(defn- checked [tables]
  (mapv (fn [t] (cond-> {:results (mapv (fn [row] (mapv checked-cell row)) (:results t))}
                  (string? (:error t)) (assoc :error (:error t))
                  (some? (checked-chart (:chart t))) (assoc :chart (checked-chart (:chart t)))
                  (string? (:chart-error t)) (assoc :chart-error (:chart-error t))
                  (and (sequential? (:data t)) (every? #(and (sequential? %) (every? plain-value? %)) (:data t)))
                  (assoc :data (:data t))
                  (and (sequential? (:fields t)) (every? string? (:fields t))) (assoc :fields (:fields t))))
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
