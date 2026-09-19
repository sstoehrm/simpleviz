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
