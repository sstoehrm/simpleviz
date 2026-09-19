(ns fork
  "simpleviz fork / promote: copy a graph file and every file it
  transitively refs to `<stem>-<suffix>.<ext>` siblings, and move such
  forks back over their bases. Forks are byte copies; refs are never
  rewritten, so both sides of a comparison name the same files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [graph]
            [serve]))

(defn resolve-ref
  "The root-relative path a `ref` on the file `current` (itself
  root-relative) points to, with `.`/`..`/empty segments collapsed; nil
  when the ref is blank, absolute, or climbs above the root. Mirrors
  editor/resolve-ref on the page."
  [current ref]
  (let [ref (str/trim (str ref))]
    (when (and (not= ref "")
               (not (str/starts-with? ref "/"))
               (nil? (re-find #"^[A-Za-z]:" ref)))
      (loop [acc (vec (butlast (str/split (str current) #"/" -1)))
             segs (str/split ref #"/" -1)]
        (if (empty? segs)
          (when (seq acc) (str/join "/" acc))
          (let [[seg & more] segs]
            (cond
              (or (= seg "") (= seg ".")) (recur acc more)
              (= seg "..") (when (seq acc) (recur (pop acc) more))
              :else (recur (conj acc seg) more))))))))

(defn ref-targets
  "The non-blank string :ref attrs on the nodes, edges and boxes in the
  EDN text of a graph file, distinct. Throws on a parse error."
  [text]
  (let [g (graph/normalize (edn/read-string text))]
    (->> (concat (map :attrs (vals (:nodes g)))
                 (map :attrs (:edges g))
                 (map :attrs (:boxes g)))
         (map :ref)
         (filter #(and (string? %) (not (str/blank? %))))
         distinct
         vec)))

(defn closure
  "Root-relative paths reachable by refs from `start`: `start` first,
  depth-first, each once. `read-rel` returns the EDN text of a
  root-relative path or throws; `warn!` takes one message. A ref that
  leaves the root or whose file cannot be read is reported and skipped;
  a parse error propagates as ex-info \"<rel>: <msg>\"."
  [start read-rel warn!]
  (let [seen (atom [])]
    (letfn [(targets [rel text]
              (try (ref-targets text)
                   (catch Exception e (throw (ex-info (str rel ": " (ex-message e)) {})))))
            (visit! [rel text]
              (swap! seen conj rel)
              (doseq [r (targets rel text)]
                (let [target (resolve-ref rel r)]
                  (cond
                    (nil? target)
                    (warn! (str rel ": ref " (pr-str r) " leaves the root folder, skipped"))

                    (some #{target} @seen) nil

                    :else
                    (let [text (try (read-rel target)
                                    (catch Exception e
                                      (warn! (str rel ": ref " (pr-str r) ": " (ex-message e) ", skipped"))
                                      nil))]
                      (when (some? text) (visit! target text)))))))]
      (visit! start (read-rel start))
      @seen)))

(defn valid-suffix? [s]
  (boolean (and (string? s) (re-matches serve/suffix-re s))))

(defn- root-and-start
  "[canonical root dir, root-relative name] of a file path."
  [file]
  (let [f (.getCanonicalFile (io/file file))]
    [(.getParentFile f) (.getName f)]))

(defn- read-base [root]
  (fn [rel] (serve/read-source (.getPath (serve/resolve-path root rel)))))

(defn fork!
  "Copy `file` and every file in its ref closure to their `suffix`
  forks. Returns the created paths. Throws ex-info naming the first
  existing target before anything is written."
  [file suffix warn!]
  (let [[root start] (root-and-start file)
        rels (closure start (read-base root) warn!)
        pairs (mapv (fn [rel] [(io/file root rel) (io/file root (serve/fork-name rel suffix))]) rels)]
    (doseq [[_ to] pairs]
      (when (.exists to)
        (throw (ex-info (str (.getPath to) " already exists") {}))))
    (doseq [[from to] pairs]
      (io/copy from to))
    (mapv (fn [[_ to]] (.getPath to)) pairs)))

(defn promote!
  "Walk the closure of `file`, reading each file's `suffix` fork when
  it exists (else the base), and move every fork found over its base.
  Returns the promoted base paths; throws ex-info \"nothing to promote\"
  when no fork was found."
  [file suffix warn!]
  (let [[root start] (root-and-start file)
        fork-file (fn [rel] (io/file root (serve/fork-name rel suffix)))
        read-rel (fn [rel]
                   (let [fk (serve/fork-name rel suffix)]
                     (serve/read-source
                      (.getPath (serve/resolve-path root (if (.isFile (fork-file rel)) fk rel))))))
        rels (closure start read-rel warn!)
        moved (reduce (fn [acc rel]
                        (let [fk (fork-file rel)
                              base (io/file root rel)]
                          (if (.isFile fk)
                            (do (java.nio.file.Files/move
                                 (.toPath fk) (.toPath base)
                                 (into-array java.nio.file.CopyOption
                                             [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                                (conj acc (.getPath base)))
                            acc)))
                      [] rels)]
    (when (empty? moved)
      (throw (ex-info "nothing to promote" {})))
    moved))

(defn -main
  "bb fork|promote <graph.edn> <suffix> (the task passes the command)."
  [& [cmd file suffix & extra]]
  (when (or (not (contains? #{"fork" "promote"} cmd)) (nil? file) (nil? suffix) (seq extra))
    (println "usage: bb fork|promote <graph.edn> <suffix>")
    (System/exit 1))
  (when-not (valid-suffix? suffix)
    (println (str "invalid suffix: " suffix))
    (System/exit 1))
  (try
    (let [warn! (fn [m] (binding [*out* *err*] (println (str "warning: " m))))
          paths (if (= cmd "fork") (fork! file suffix warn!) (promote! file suffix warn!))
          verb (if (= cmd "fork") "created " "promoted ")]
      (doseq [p paths] (println (str verb p))))
    (catch Exception e
      (println (str cmd ": " (ex-message e)))
      (System/exit 1))))
