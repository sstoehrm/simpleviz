(ns paths
  "Root-relative path arithmetic shared by refs and pairs. A leaf: it
  requires nothing of this project, so fork, pairs and serve can all
  use it — and squint compiles it for the page too, so the page offers
  exactly the refs the server will serve."
  (:require [clojure.string :as str]))

(defn md-path?
  "Does `path` name a markdown file (.md, any case)?"
  [path]
  (str/ends-with? (str/lower-case (str path)) ".md"))

(defn resolve-ref
  "The root-relative path a `ref` on the file `current` (itself
  root-relative) points to, with `.`/`..`/empty segments collapsed; nil
  when the ref is blank, absolute, or climbs above the root (more `..`
  than `current` has directories)."
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
