(ns embedded
  "The EDN embedded in a simpleviz export, whichever format carries it:
  a PNG's iTXt chunks (png) or an SVG's <metadata> (svg). Both use the
  same keys: \"simpleviz-edn\", or \"simpleviz-edn-old\"/\"-new\" for a
  compare export."
  (:require [clojure.java.io :as io]
            [png]
            [svg]))

(defn export?
  "Is the file a PNG or an SVG, by content? False for missing files."
  [path]
  (boolean (or (png/png? path) (svg/svg? path))))

(defn extract
  "Text embedded under key `kw`, or nil. Throws when the file is neither
  a PNG nor an SVG."
  [path kw]
  (cond
    (png/png? path) (png/extract path kw)
    (svg/svg? path) (svg/extract path kw)
    :else (throw (ex-info (str path " is not a PNG or SVG file") {}))))

(defn -main
  "bb extract <diagram.png|.svg> [out.edn] [--old]"
  [& args]
  (let [old? (boolean (some #{"--old"} args))
        [in out] (vec (remove #{"--old"} args))]
    (when (nil? in)
      (println "usage: bb extract <diagram.png|.svg> [out.edn] [--old]")
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
