(ns simpleviz.slides
  "A presentation from an md page's text: lines of just --- split it into
  slides (not inside a fenced code block), and a slide whose first line
  is `>> [text](path)` — or `>> ![alt](path)`, or `>> path` — shows that
  file beside its text. Empty slides are dropped, so a deck may open and
  close with ---."
  (:require [mdlinks :as mdlinks]))

(defn- fence-mark
  "The fence a line opens or closes (\"```\" or \"~~~\"), or nil."
  [line]
  (let [t (.trimStart line)]
    (cond (.startsWith t "```") "```"
          (.startsWith t "~~~") "~~~"
          :else nil)))

(defn- chunks
  "The text's lines, split at the separators outside code fences."
  [text]
  (loop [lines (.split (.replace text (js/RegExp. "\\r\\n?" "g") "\n") "\n")
         fence nil
         cur []
         out []]
    (if (empty? lines)
      (conj out cur)
      (let [line (first lines)
            mark (fence-mark line)]
        (cond
          (some? fence) (recur (rest lines) (if (= mark fence) nil fence) (conj cur line) out)
          (= (.trim line) "---") (recur (rest lines) nil [] (conj out cur))
          :else (recur (rest lines) mark (conj cur line) out))))))

(defn- blank? [line] (= (.trim line) ""))

(defn- trim-blank-lines
  "The lines without the blank ones at either end, joined."
  [lines]
  (let [ls (drop-while blank? lines)
        ls (reverse (drop-while blank? (reverse ls)))]
    (.join (into-array ls) "\n")))

(defn side-target
  "What the rest of a `>>` line names, {:dest} plus :compare when it
  asks for one: the destination of its first link — [text](path) or
  ![alt](path), as a link means it — else the rest as a plain path
  (`path?compare=<suffix>` too); nil when there is nothing."
  [rest]
  (let [t (.trim rest)
        l (first (filter (fn [l] (or (= "inline" (:kind l)) (= "image" (:kind l))))
                         (mdlinks/links t)))
        c (when (and (nil? l) (not= t "")) (mdlinks/dest-compare t))]
    (cond (some? l) (cond-> {:dest (:dest l)} (some? (:compare l)) (assoc :compare (:compare l)))
          (= t "") nil
          (some? c) {:dest (.slice t 0 (.indexOf t "?")) :compare c}
          :else {:dest t})))

(defn- slide
  "One slide from its lines: {:text :side}, :side the `>>` target of its
  first line (nil when there is none), plus :side-compare when that
  asks to compare."
  [lines]
  (let [ls (vec (drop-while blank? lines))
        head (first ls)
        target (when (and (some? head) (.startsWith head ">>")) (side-target (.slice head 2)))]
    (if (and (some? head) (.startsWith head ">>"))
      (cond-> {:text (trim-blank-lines (rest ls)) :side (when (some? target) (:dest target))}
        (some? (:compare target)) (assoc :side-compare (:compare target)))
      {:text (trim-blank-lines ls) :side nil})))

(defn slides
  "The slides of md `text`, in order: [{:text md :side path-or-nil}]."
  [text]
  (filterv (fn [s] (or (some? (:side s)) (not= (:text s) "")))
           (mapv slide (chunks (str (if (nil? text) "" text))))))

(defn side-kind
  "What a `>>` target shows as: \"graph\" (.edn), \"md\", \"export\" (.png
  or .svg: a graph when it carries simpleviz EDN, else a picture),
  \"image\" (.jpg .jpeg .gif .webp); nil for anything else."
  [dest]
  (let [low (.toLowerCase dest)]
    (get {"edn" "graph" "md" "md" "png" "export" "svg" "export"
          "jpg" "image" "jpeg" "image" "gif" "image" "webp" "image"}
         (.slice low (inc (.lastIndexOf low ".")))
         nil)))
