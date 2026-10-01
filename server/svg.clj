(ns svg
  "Read the EDN embedded in an exported SVG (the simpleviz:source
  elements src/simpleviz/svg.cljs writes into <metadata>), as png reads
  a PNG's iTXt chunks."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private NS "https://github.com/sstoehrm/simpleviz")

(def ^:private SVG-NS "http://www.w3.org/2000/svg")

(def ^:private openings
  "How an SVG file can begin, past a BOM and whitespace. A bare < is not
  enough: an .edn mid-merge starts with <<<<<<<."
  ["<?xml" "<svg" "<!--" "<!DOCTYPE"])

(defn svg?
  "Does the file, past a BOM and whitespace, open like XML or an <svg>?
  Content sniffing, as png/png?. False for missing or empty files."
  [path]
  (let [f (io/file path)]
    (and (.isFile f)
         (with-open [r (io/reader f :encoding "UTF-8")]
           (let [buf (char-array 256)
                 n (.read r buf 0 256)
                 head (if (pos? n) (String. buf 0 n) "")
                 head (str/triml (str/replace head #"^﻿" ""))]
             (boolean (some #(str/starts-with? head %) openings)))))))

(defn- tag? [el uri local]
  (and (map? el)
       (= uri (xml/qname-uri (:tag el)))
       (= local (xml/qname-local (:tag el)))))

(defn extract
  "Text of the simpleviz:source element with the given key, or nil.
  Throws when the file is not a valid SVG. DTDs are not processed, so no
  entity is declared or expanded; exports carry none. Only the
  <metadata> leading the root is read — the export writes it before the
  drawing — so a big diagram is not parsed to the end."
  [path kw]
  (when-not (svg? path)
    (throw (ex-info (str path " is not an SVG file") {})))
  (try
    (with-open [in (io/input-stream (io/file path))]
      (let [root (xml/parse in :support-dtd false :supporting-external-entities false)]
        (when-not (tag? root SVG-NS "svg")
          (throw (ex-info (str path " is not an SVG file") {})))
        (some (fn [md]
                (some (fn [el]
                        (when (and (tag? el NS "source") (= kw (get-in el [:attrs :key])))
                          (apply str (filter string? (:content el)))))
                      (:content md)))
              ;; the parse is lazy: this stops reading at the first
              ;; element that isn't <metadata>
              (->> (:content root)
                   (remove string?)
                   (take-while #(tag? % SVG-NS "metadata"))))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (ex-info (str path " is not a valid SVG file: " (ex-message e)) {})))))
