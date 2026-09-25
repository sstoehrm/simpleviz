(ns paths
  "Root-relative path arithmetic shared by refs and pairs. A leaf: it
  requires nothing of this project, so fork, pairs and serve can all
  use it."
  (:require [clojure.string :as str]))

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
