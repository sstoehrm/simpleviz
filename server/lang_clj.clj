(ns lang-clj
  "Clojure as an analyzer reads it: clj-module turns source files into
  namespaces, their top-level defs and the calls each makes, and
  public-namespace-graph draws those as a graph. Reading only — no code
  runs. Calls are the symbols a def's body names, resolved through the
  ns form's aliases and refers and the namespace's own defs; a local
  that shadows a def still counts as a call to it."
  (:require [clojure.string :as str]
            [edamame.core :as e]))

(def ^:private exts #{"clj" "cljc" "cljs" "bb"})

(defn- ext-of [path] (str/lower-case (or (second (re-find #"\.([^./]+)$" (str path))) "")))

(defn- read-opts [path]
  {:all true
   :read-cond :allow
   :features (if (= "cljs" (ext-of path)) #{:cljs} #{:clj})
   :auto-resolve (fn [a] (if (= :current a) 'user a))
   ;; #js, #inst, #uuid and any other tag: the form as it is
   :readers (fn [_] identity)
   :row-key :line})

(defn- libspecs
  "The [lib & opts] specs of an ns form's :require, :require-macros and
  :use clauses, prefix lists expanded; string libs (npm) are left out."
  [ns-form]
  (for [clause (rest ns-form)
        :when (and (seq? clause) (#{:require :require-macros :use} (first clause)))
        spec (rest clause)
        s (cond (symbol? spec) [[spec]]
                (and (vector? spec) (or (= 1 (count spec)) (keyword? (second spec)))) [spec]
                ;; a prefix list, (app [util :as u] core) or [app [util :as u] core]
                (and (sequential? spec) (symbol? (first spec)))
                (for [x (rest spec)
                      :let [[lib & opts] (if (vector? x) x [x])]
                      :when (symbol? lib)]
                  (into [(symbol (str (first spec) "." lib))] opts))
                :else [])
        :when (symbol? (first s))]
    s))

(defn- scope
  "{:aliases {alias ns} :refers {name ns}} of an ns form, names as strings."
  [ns-form]
  (reduce (fn [acc [lib & opts]]
            (let [o (apply hash-map (if (odd? (count opts)) (butlast opts) opts))
                  lib (str lib)
                  acc (reduce (fn [a k] (if (symbol? (get o k)) (assoc-in a [:aliases (str (get o k))] lib) a))
                              acc [:as :as-alias])]
              ;; :refer :all resolves nothing: what it brings isn't known here
              (reduce (fn [a s] (assoc-in a [:refers (str s)] lib))
                      acc (concat (when (sequential? (:refer o)) (:refer o))
                                  (when (sequential? (:only o)) (:only o))))))
          {:aliases {} :refers {}}
          (libspecs ns-form)))

(defn- def-form?
  "Is form a top-level (def… name …), its head's name starting with def?"
  [form]
  (and (seq? form) (symbol? (first form)) (str/starts-with? (name (first form)) "def")
       (symbol? (second form))))

(defn- symbols [form]
  (filter symbol? (tree-seq coll? (fn [c] (if (map? c) (mapcat identity c) (seq c))) form)))

(defn- resolver
  "Symbol → its fully qualified name as a string, or nil when it names
  nothing this namespace knows (a local, a core fn, interop)."
  [ns-name {:keys [aliases refers]} own]
  (fn [sym]
    (let [n (name sym)]
      (if-let [q (namespace sym)]
        (when-not (= "js" q) (str (get aliases q q) "/" n))
        (cond (contains? own n) (str ns-name "/" n)
              (contains? refers n) (str (get refers n) "/" n))))))

(defn- method? [f] (= "defmethod" (name (first f))))

(defn- private?
  "defn-, ^:private on the name, or {:private true} in a defn's attr map."
  [kind f]
  (let [attrs (when-not (#{"def" "defonce"} kind)
                (let [[a b] (drop 2 f)] (if (string? a) b a)))]
    (or (= "defn-" kind) (true? (:private (meta (second f))))
        (and (map? attrs) (true? (:private attrs))))))

(defn- module-of
  "The module of one file's forms: {:ns :file :defs [{:name :kind
  :private :line :calls}] :methods [{:multi :line :calls}]}, in file
  order; a method's multi is its qualified name, wherever it is."
  [path forms]
  (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %)) (symbol? (second %))) forms))
        ns-name (some-> ns-form second str)
        tops (filter def-form? forms)
        own (set (keep (fn [f] (when-not (method? f) (name (second f)))) tops))
        resolve (resolver ns-name (scope ns-form) own)
        calls (fn [f] (vec (sort (set (keep resolve (symbols (drop 2 f)))))))]
    {:ns ns-name
     :file path
     :defs (vec (for [f tops :when (not (method? f)) :let [kind (name (first f))]]
                  {:name (name (second f)) :kind kind :private (private? kind f)
                   :line (:line (meta f)) :calls (calls f)}))
     :methods (vec (for [f tops :when (method? f) :let [multi (resolve (second f))] :when multi]
                     {:multi multi :line (:line (meta f)) :calls (calls f)}))}))

(defn clj-module
  "The Clojure files of `files` ([{:path :text}]) as modules, one each:
  {:ns :file :defs [{:name :kind (\"defn\", \"defmacro\", …) :private
  :line :calls [qualified names]}] :methods [{:multi :line :calls}]}; a
  file that doesn't read is {:file :error}. Other files are left out."
  [files]
  (vec (for [{:keys [path text]} files
             :when (contains? exts (ext-of path))]
         (try (module-of path (e/parse-string-all (str text) (read-opts path)))
              (catch StackOverflowError _ {:file path :error "nests too deeply to read"})
              (catch Exception ex {:file path :error (or (ex-message ex) (str (class ex)))})))))

(def ^:private node-types {"defn" "fn" "defmacro" "macro" "defmulti" "multi"})

(defn public-namespace-graph
  "A simpleviz graph of clj-module's `modules`: a box per namespace, in
  it a node per public fn, macro and multimethod, and an edge where one
  calls another — directly, or through private fns and other defs that
  aren't nodes. A multimethod calls what its methods call."
  [modules]
  (let [modules (filter :ns modules)
        defs (reduce (fn [m {:keys [ns file defs]}]
                       (reduce (fn [m d]
                                 (update m (str ns "/" (:name d))
                                         (fn [old] (-> (merge (assoc d :ns ns :file file) old)
                                                       (update :calls (fnil into #{}) (:calls d))))))
                               m defs))
                     {} modules)
        index (reduce (fn [m {:keys [multi calls]}]
                        (if (contains? m multi) (update-in m [multi :calls] into calls) m))
                      defs (mapcat :methods modules))
        node? (fn [id] (let [d (get index id)] (and d (not (:private d)) (contains? node-types (:kind d)))))
        ids (sort (filter node? (keys index)))
        reach (fn [id]
                (loop [todo (vec (:calls (get index id))) seen #{} out #{}]
                  (if-let [c (peek todo)]
                    (let [todo (pop todo)]
                      (cond (contains? seen c) (recur todo seen out)
                            (node? c) (recur todo (conj seen c) (conj out c))
                            (contains? index c) (recur (into todo (:calls (get index c))) (conj seen c) out)
                            :else (recur todo (conj seen c) out)))
                    (disj out id))))
        edges (reduce (fn [es a]
                        (reduce (fn [es b]
                                  (let [[k dir] (if (neg? (compare a b)) [[a b] :->] [[b a] :<-])]
                                    (update es k (fn [old] {:direction (if (and old (not= dir (:direction old))) :<-> dir)}))))
                                es (reach a)))
                      {} ids)]
    {:nodes (into {} (for [id ids :let [d (get index id)]]
                       [id {:name (:name d) :type (get node-types (:kind d)) :file (:file d) :line (:line d)}]))
     :boxes (reduce (fn [bs id] (update-in bs [(:ns (get index id)) :components] conj id))
                    (into {} (map (fn [m] [(:ns m) {:components #{}}])) modules)
                    ids)
     :edges edges}))
