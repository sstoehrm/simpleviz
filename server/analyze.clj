(ns analyze
  "Analyzer blocks in md files: a ```simpleviz-analyzer fence names a
  folder, >> ./src/, then pairs of a pattern — a glob or a vector of
  them, over the paths in that folder — and a fn of the files it
  matches, [{:path :text}], that returns a graph:

      >> ./src/
      \"**.clj\" #(->> % clj-module public-namespace-graph)

  The server reads the files — under the served folder only, no dot
  files, no symlinks — and hands them to the fns, which run in SCI in a
  child bb of their own, killed when late, as formulas do (calc): they
  read no files, they get data. The tools they call are lang-clj's.
  Each block's graph is cached by stamp: its md file's and its files'
  sizes and times."
  (:require [babashka.process :as p]
            [calc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [edamame.core :as e]
            [lang-clj]
            [mdlinks]
            [paths]
            [sci.core :as sci]))

(def ^:dynamic *child-ms* "How long a block's fns may run, all together." 30000)

(def ^:private max-files 5000)

(def ^:private max-bytes (* 32 1024 1024))

(defn- fail [& msg] (throw (ex-info (apply str msg) {})))

(defn- message [^Throwable ex] (or (ex-message ex) (.getSimpleName (class ex))))

;; ---------------------------------------------------------------- blocks

(defn block-of
  "The body lines of the `n`th (from 1) closed simpleviz-analyzer fence
  of md `text` outside quotes and lists — as the page numbers them."
  [text n]
  (or (:body (nth (filterv #(and (:closed %) (= "simpleviz-analyzer" (:info %))) (mdlinks/fences text))
                  (dec n) nil))
      (fail "no analyzer block " n " in the md file")))

(def ^:private read-opts
  {:fn true :regex true :quote true :syntax-quote true :deref true :var true
   :read-cond :allow :features #{:clj}})

(defn- glob? [s] (and (string? s) (not (str/blank? s))))

(defn parse-block
  "A block's {:source (the >> line's folder, as written) :globs [[glob]
  per pair] :defs (the pairs' text)}; throws on a block that isn't one."
  [body]
  (let [[head & more] body
        line (str/trim (str head))]
    (when-not (str/starts-with? line ">>")
      (fail "an analyzer block starts with its folder: >> ./src/"))
    (let [rest-text (str/trim (subs line 2))
          link (first (filter #(= "inline" (:kind %)) (mdlinks/links rest-text)))
          defs (str/join "\n" more)
          forms (try (e/parse-string-all defs read-opts)
                     (catch Exception ex (fail "the block doesn't read: " (message ex))))]
      (when (or (empty? forms) (odd? (count forms)))
        (fail "after >> the block takes pairs of a pattern and a fn"))
      {:source (if link (:dest link) rest-text)
       :globs (mapv (fn [[pat _]]
                      (cond (glob? pat) [pat]
                            (and (vector? pat) (seq pat) (every? glob? pat)) pat
                            :else (fail "a pattern is a glob or a vector of them, like \"**.clj\" — got " (pr-str pat))))
                    (partition 2 forms))
       :defs defs})))

;; ---------------------------------------------------------------- files

(defn- folder
  "The canonical folder the >> `dest` of root-relative md file `md`
  names, under canonical `root`; throws when it leaves it or is none."
  [root md dest]
  ;; resolve dest/x and drop the x, so . and ./ name the md file's own folder
  (let [rel (or (paths/resolve-ref md (str (str/replace (str/trim dest) #"/+$" "") "/x"))
                (fail dest " leaves the served folder"))
        rel (str/join "/" (butlast (str/split rel #"/")))
        f (.getCanonicalFile (java.io.File. ^java.io.File root ^String rel))]
    (when-not (or (= f root) (str/starts-with? (.getPath f) (str (.getPath root) java.io.File/separator)))
      (fail dest " leaves the served folder"))
    (when-not (.isDirectory f) (fail "no folder " dest))
    f))

(defn- walk
  "The regular files under folder `dir`, as [rel-path File], sorted:
  no dot file, no symlink, nothing under a dot folder or node_modules."
  [^java.io.File dir]
  (let [dp (.toPath dir)
        keep? (fn [^java.io.File f]
                (and (not (str/starts-with? (.getName f) "."))
                     (not (java.nio.file.Files/isSymbolicLink (.toPath f)))))
        descend? (fn [^java.io.File f]
                   (or (= f dir) (and (.isDirectory f) (keep? f) (not= "node_modules" (.getName f)))))]
    (->> (tree-seq descend? (fn [^java.io.File d] (seq (.listFiles d))) dir)
         (filter (fn [^java.io.File f] (and (.isFile f) (keep? f))))
         (map (fn [^java.io.File f] [(str/replace (str (.relativize dp (.toPath f))) java.io.File/separator "/") f]))
         (sort-by first))))

(defn- matcher [globs]
  (let [ms (mapv #(.getPathMatcher (java.nio.file.FileSystems/getDefault) (str "glob:" %)) globs)]
    (fn [rel] (let [p (java.nio.file.Paths/get rel (make-array String 0))]
                (some #(.matches ^java.nio.file.PathMatcher % p) ms)))))

(defn matching
  "Per glob group of `globs`, the files of the >> folder `dest` of md
  file `md` (root-relative, under canonical `root`) whose path in it one
  of the group matches: [[{:path :file}]]."
  [root md dest globs]
  (let [files (walk (folder root md dest))
        out (mapv (fn [g] (let [m? (matcher g)]
                            (vec (for [[rel f] files :when (m? rel)] {:path rel :file f}))))
                  globs)]
    (when (> (count (distinct (mapcat identity out))) max-files)
      (fail "over " max-files " files match — narrow the pattern"))
    out))

(defn- block-files
  "[md-file parsed-block groups] for block `n` of root-relative md file
  `md` under canonical `root`."
  [root md n]
  (let [md-f (java.io.File. ^java.io.File root ^String md)]
    (when-not (.isFile md-f) (fail "no such file: " md))
    (let [b (parse-block (block-of (slurp md-f) n))]
      [md-f b (matching root md (:source b) (:globs b))])))

(defn- stamp-now [root md n]
  (try (let [[^java.io.File md-f _ groups] (block-files root md n)
             fs (cons md-f (distinct (map :file (mapcat identity groups))))]
         (str (count fs) "-" (reduce max 0 (map #(.lastModified ^java.io.File %) fs))
              "-" (reduce + 0 (map #(.length ^java.io.File %) fs))))
       ;; a broken block has a stamp too: its error shows until it changes
       (catch Exception ex (str "error-" (hash (message ex))))))

(def ^:dynamic *stamp-ms* "How long a stamp is taken as it is: the watcher asks every 200 ms." 1000)

(def ^:private stamps (atom {})) ; [root md n] -> {:at ms :stamp s}

(defn stamp
  "What block `n` of md file `md` depends on, as a string that changes
  with it: the md file's and every matched file's time and size — looked
  at again once it is over *stamp-ms* old."
  [root md n]
  (let [k [(.getPath ^java.io.File root) md n]
        now (System/currentTimeMillis)
        hit (get @stamps k)]
    (if (and hit (< (- now (:at hit)) *stamp-ms*))
      (:stamp hit)
      (let [s (stamp-now root md n)]
        (swap! stamps assoc k {:at now :stamp s})
        s))))

;; ---------------------------------------------------------------- the child

(defn- merge-graphs [graphs]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b)) graphs))

(defn evaluate
  "The graph the pairs of `defs` make of their `groups` of files, as EDN
  text: each pattern's fn gets its group, [{:path :text}]."
  [defs groups]
  (let [ctx (sci/init {:namespaces {'user {'clj-module lang-clj/clj-module
                                           'public-namespace-graph lang-clj/public-namespace-graph}}})
        forms (:val (sci/eval-string+ ctx (str "[" defs "\n]") {:ns (sci/find-ns ctx 'user)}))]
    (when-not (= (count groups) (quot (count forms) 2))
      (fail "the block's pairs and its file groups differ"))
    (pr-str
     (merge-graphs
      (map (fn [[pat f] files]
             (when-not (fn? f) (fail "expected a fn after " (pr-str pat) ", got " (pr-str f)))
             (let [g (f files)]
               (when-not (map? g)
                 (fail "the fn after " (pr-str pat) " returns no graph — a map of :nodes, :edges and :boxes"))
               g))
           (partition 2 forms)
           groups)))))

(defn child
  "The child's main: {defs, groups} as JSON on stdin, {edn} or {error}
  as JSON on stdout, then exit. What the fns print goes nowhere."
  []
  (let [stdout *out*
        {:keys [defs groups]} (json/parse-string (slurp *in*) true)
        out (binding [*out* (java.io.StringWriter.) *print-length* nil *print-level* nil]
              (try (json/generate-string {:edn (evaluate defs groups)})
                   (catch StackOverflowError _ (json/generate-string {:error "the fn recursed too deeply"}))
                   (catch Throwable ex (json/generate-string {:error (message ex)}))))]
    (.write ^java.io.Writer stdout (str out "\n"))
    (.flush ^java.io.Writer stdout)
    (System/exit 0)))

(defn- run-child [defs groups]
  (let [proc (p/process {:in (json/generate-string {:defs defs :groups groups})
                         :out :string :err :string
                         :dir (System/getProperty "java.io.tmpdir")}
                        (calc/bb-path) "-cp" (calc/classpath) "-e" "(require 'analyze) (analyze/child)")
        done (deref proc *child-ms* ::late)]
    (if (= ::late done)
      (do (p/destroy-tree proc)
          (fail "the analyzer took too long (over " *child-ms* " ms)"))
      (let [out (try (json/parse-string (:out done) true) (catch Exception _ nil))]
        (cond (string? (:edn out)) (:edn out)
              (string? (:error out)) (fail (:error out))
              :else (fail "the analyzer failed" (some->> (:err done) str/trim not-empty (str ": "))))))))

(def ^:private cache
  "[root md n] -> {:stamp s :edn text} or {:stamp s :error msg}."
  (atom {}))

(defn graph-text
  "The graph block `n` (from 1) of md file `md` — root-relative, under
  canonical folder `root` — makes, as EDN text; throws with what went
  wrong. Run again only when its stamp moved."
  [root md n]
  (let [k [(.getPath ^java.io.File root) md n]
        s (stamp root md n)
        hit (get @cache k)
        entry (if (= s (:stamp hit))
                hit
                (let [e (try (let [[_ b groups] (block-files root md n)
                                   texts (mapv (fn [g]
                                                 (let [total (reduce + 0 (map #(.length ^java.io.File (:file %)) g))]
                                                   (when (> total max-bytes) (fail "the matched files are over 32 MiB"))
                                                   (mapv (fn [{:keys [path file]}] {:path path :text (slurp file)}) g)))
                                               groups)]
                               {:stamp s :edn (run-child (:defs b) texts)})
                             (catch Exception ex {:stamp s :error (message ex)}))]
                  (swap! cache assoc k e)
                  e))]
    (if-let [err (:error entry)]
      (fail err)
      (:edn entry))))
