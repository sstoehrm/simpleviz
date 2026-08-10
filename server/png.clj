(ns png
  "Read the EDN embedded in an exported PNG (iTXt chunks written by
  src/simpleviz/png.cljs). Pure babashka — byte walking, no deps."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]))

(def ^:private signature [137 80 78 71 13 10 26 10])

(defn- ub [b] (bit-and b 0xff))

(defn- be32 [bs i]
  (+ (* (ub (aget bs i)) 16777216)
     (* (ub (aget bs (+ i 1))) 65536)
     (* (ub (aget bs (+ i 2))) 256)
     (ub (aget bs (+ i 3)))))

(defn extract
  "Text of the iTXt chunk with the given keyword, or nil. Throws when
  the file is not a PNG."
  [path kw]
  (let [bs (Files/readAllBytes (.toPath (io/file path)))]
    (when (or (< (alength bs) 8)
              (not= signature (mapv (fn [i] (ub (aget bs i))) (range 8))))
      (throw (ex-info (str path " is not a PNG file") {})))
    (loop [i 8]
      (when (< (+ i 8) (alength bs))
        (let [len (be32 bs i)
              type (String. bs (+ i 4) 4 "US-ASCII")
              next-i (+ i 12 len)]
          (if (= type "iTXt")
            (let [data-off (+ i 8)
                  z (loop [j 0]
                      (if (or (>= j len) (zero? (aget bs (+ data-off j))))
                        j
                        (recur (inc j))))
                  k (String. bs data-off z "UTF-8")]
              (if (= k kw)
                (let [after (loop [j (+ z 3) nulls 0]
                              (if (= nulls 2)
                                j
                                (recur (inc j)
                                       (if (zero? (aget bs (+ data-off j)))
                                         (inc nulls)
                                         nulls))))]
                  (String. bs (+ data-off after) (- len after) "UTF-8"))
                (recur next-i)))
            (recur next-i)))))))

(defn -main
  "bb extract <diagram.png> [out.edn] [--old]"
  [& args]
  (let [old? (boolean (some #{"--old"} args))
        [in out] (vec (remove #{"--old"} args))]
    (when (nil? in)
      (println "usage: bb extract <diagram.png> [out.edn] [--old]")
      (System/exit 1))
    (let [text (try
                 (if old?
                   (extract in "simpleviz-edn-old")
                   (or (extract in "simpleviz-edn-new")
                       (extract in "simpleviz-edn")))
                 (catch Exception e
                   (println (ex-message e))
                   (System/exit 1)))]
      (cond
        (nil? text)
        (do (println (str "no embedded simpleviz EDN"
                          (when old? " (old)") " found in " in))
            (System/exit 1))

        (nil? out) (print text)

        (.exists (io/file out))
        (do (println (str out " already exists")) (System/exit 1))

        :else (do (spit out text) (println (str "wrote " out)))))))
