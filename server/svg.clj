(ns svg
  "Read the EDN embedded in an exported SVG (the simpleviz:source
  elements src/simpleviz/svg.cljs writes into <metadata>), as png reads
  a PNG's iTXt chunks."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]))

(def ^:private NS "https://github.com/sstoehrm/simpleviz")

(def ^:private SVG-NS "http://www.w3.org/2000/svg")

(defn svg?
  "Does the file's first character, past a BOM and whitespace, open
  markup? Content sniffing, as png/png?: a graph's EDN never starts with
  <. False for missing or empty files."
  [path]
  (let [f (io/file path)]
    (and (.isFile f)
         (with-open [r (io/reader f :encoding "UTF-8")]
           (loop []
             (let [c (.read r)]
               (cond
                 (neg? c) false
                 (or (= c 0xFEFF) (Character/isWhitespace c)) (recur)
                 :else (= c (int \<)))))))))

(defn- element-seq
  "Every element of the tree, depth first."
  [node]
  (when (map? node)
    (cons node (mapcat element-seq (:content node)))))

(defn extract
  "Text of the simpleviz:source element with the given key, or nil.
  Throws when the file is not an SVG. A DTD is refused outright, so no
  entity — internal or external — is ever expanded; exports carry none."
  [path kw]
  (when-not (svg? path)
    (throw (ex-info (str path " is not an SVG file") {})))
  (let [root (try
               (with-open [in (io/input-stream (io/file path))]
                 ;; the parse is lazy: realize the tree before the stream closes
                 (doto (xml/parse in :support-dtd false :supporting-external-entities false)
                   (-> element-seq dorun)))
               (catch Exception e
                 (throw (ex-info (str path " is not an SVG file: " (ex-message e)) {}))))]
    (when-not (and (map? root)
                   (= SVG-NS (xml/qname-uri (:tag root)))
                   (= "svg" (xml/qname-local (:tag root))))
      (throw (ex-info (str path " is not an SVG file") {})))
    (some (fn [el]
            (when (and (= NS (xml/qname-uri (:tag el)))
                       (= "source" (xml/qname-local (:tag el)))
                       (= kw (get-in el [:attrs :key])))
              (apply str (filter string? (:content el)))))
          (element-seq root))))
