(ns simpleviz.slides
  "A presentation from an md page's text: lines of just --- split it into
  slides (not inside a fenced code block), and a slide whose first line
  is `>> path` shows that file beside its text. Empty slides are dropped,
  so a deck may open and close with ---.")

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

(defn- slide
  "One slide from its lines: {:text :side}, :side the `>>` target of its
  first line (nil when there is none)."
  [lines]
  (let [ls (vec (drop-while blank? lines))
        head (first ls)]
    (if (and (some? head) (.startsWith head ">>"))
      (let [dest (.trim (.slice head 2))]
        {:text (trim-blank-lines (rest ls)) :side (if (= dest "") nil dest)})
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
