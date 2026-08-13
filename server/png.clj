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

(defn png?
  "Does the file start with the PNG signature? Content sniffing, not
  extension matching; false for missing or too-short files."
  [path]
  (let [f (io/file path)]
    (and (.isFile f)
         (with-open [in (io/input-stream f)]
           (let [buf (byte-array 8)]
             (and (= 8 (.read in buf 0 8))
                  (= signature (mapv ub buf))))))))

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
                  ;; Never trust the chunk's declared length further than
                  ;; the bytes actually present — a truncated/corrupt
                  ;; chunk must not walk the scan past the real array.
                  cap (min len (- (alength bs) data-off))
                  z (loop [j 0]
                      (cond
                        (>= j cap) nil
                        (zero? (aget bs (+ data-off j))) j
                        :else (recur (inc j))))]
              (if (nil? z)
                ;; keyword never NUL-terminated within bounds: malformed,
                ;; skip this chunk
                (recur next-i)
                (let [k (String. bs data-off z "UTF-8")]
                  (if (= k kw)
                    (let [after (loop [j (+ z 3) nulls 0]
                                  (cond
                                    (= nulls 2) j
                                    (>= j cap) nil
                                    (zero? (aget bs (+ data-off j))) (recur (inc j) (inc nulls))
                                    :else (recur (inc j) nulls)))
                          tlen (when (some? after) (- cap after))]
                      (if (or (nil? after) (neg? tlen))
                        ;; terminator NULs never found (or malformed
                        ;; enough to yield a negative length) within
                        ;; bounds: malformed, skip this chunk
                        (recur next-i)
                        (String. bs (+ data-off after) tlen "UTF-8")))
                    (recur next-i)))))
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
