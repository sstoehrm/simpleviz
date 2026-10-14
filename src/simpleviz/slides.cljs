(ns simpleviz.slides
  "A presentation from an md page's text: lines of just --- split it into
  slides (not inside a fenced code block), and a slide whose first line
  is `>> [text](path)` — or `>> ![alt](path)`, or `>> path` — shows that
  file beside its text. Empty slides are dropped, so a deck may open and
  close with ---.

  A slide whose first lines hold `?? name` asks a question: its task
  list (- [ ] lines, [x] the right ones) gives the 2 to 4 choices, the
  lines indented under a choice its explanation. `??? name` asks for a
  submit before the deck goes on."
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

(defn side-dest
  "What the rest of a `>>` line names: the destination of its first link
  — [text](path) or ![alt](path), as a link means it — else the rest as
  a plain path; nil when there is nothing."
  [rest]
  (let [t (.trim rest)
        l (first (filter (fn [l] (or (= "inline" (:kind l)) (= "image" (:kind l))))
                         (mdlinks/links t)))]
    (cond (some? l) (:dest l)
          (= t "") nil
          :else t)))

(def ^:private choice-re #"^ {0,3}[-*+][ \t]+\[([ xX])\][ \t]+(.*)$")

(defn- indent [line] (- (count line) (count (.trimStart line))))

(defn- untab
  "The line with tabs in its indent as 4 spaces, as markdown reads them."
  [line]
  (.replace line #"^[ \t]+" (fn [ws] (.replaceAll ws "\t" "    "))))

(defn- dedent
  "The lines without the indent they share, blank ends dropped; nil when
  nothing is left."
  [lines]
  (let [n (apply min 1000 (map indent (remove blank? lines)))
        t (trim-blank-lines (map (fn [l] (if (blank? l) "" (.slice l n))) lines))]
    (if (= t "") nil t)))

(defn- split-choices
  "The body lines of a question as [text-lines choices]: each task item
  outside a code fence is a choice {:text :correct :note}, the blank and
  indented lines under it its note — a nested list too."
  [lines]
  (loop [ls (map untab lines) fence nil text [] choices [] cur nil]
    (let [close (fn [] (if (some? cur)
                         (conj choices (assoc (:choice cur) :note (dedent (:note cur))))
                         choices))]
      (if (empty? ls)
        [text (close)]
        (let [line (first ls)
              mark (fence-mark line)
              m (when (nil? fence) (.exec choice-re line))]
          (cond
            (some? fence) (recur (rest ls) (if (= mark fence) nil fence) (conj text line) choices cur)
            (and (some? cur) (or (blank? line) (>= (indent line) 2)))
            (recur (rest ls) nil text choices (update cur :note conj line))
            (some? m) (recur (rest ls) nil text (close)
                             {:choice {:text (.trim (aget m 2)) :correct (not= " " (aget m 1))} :note []})
            :else (recur (rest ls) mark (conj text line) (close) nil)))))))

(defn- ask
  "The question `?? name` (`???` when `submit`) asks over body `lines`:
  [text {:id :submit :choices :error}]. Without a name, or without 2 to 4
  choices, :error says so and the lines stay text."
  [marker id lines]
  (let [[text choices] (split-choices lines)
        n (count choices)
        error (cond (= id "") (str marker " needs a name, as in " marker " storage")
                    (not (<= 2 n 4)) (str marker " " id " takes 2 to 4 choices, - [ ] lines; it has " n)
                    :else nil)]
    (if (some? error)
      [(trim-blank-lines lines) {:id id :submit (= marker "???") :error error :choices []}]
      [(trim-blank-lines text) {:id id :submit (= marker "???") :error nil :choices choices}])))

(defn- slide
  "One slide from its lines: {:text :side}, :side the `>>` target of its
  first lines (nil when there is none); with `?? name` there too, :ask
  the question."
  [lines]
  (loop [ls (vec (drop-while blank? lines)) side nil q nil]
    (let [head (first ls)]
      (cond
        (and (nil? side) (some? head) (.startsWith head ">>"))
        (recur (subvec ls 1) {:dest (side-dest (.slice head 2))} q)

        (and (nil? q) (some? head) (.startsWith head "??"))
        (let [marker (if (.startsWith head "???") "???" "??")]
          (recur (subvec ls 1) side {:marker marker :id (.trim (.slice head (count marker)))}))

        :else
        (let [base {:text (trim-blank-lines ls) :side (if (some? side) (:dest side) nil)}]
          (if (nil? q)
            base
            (let [[text a] (ask (:marker q) (:id q) ls)]
              (assoc base :text text :ask a))))))))

(defn slides
  "The slides of md `text`, in order: [{:text md :side path-or-nil}]."
  [text]
  (filterv (fn [s] (or (some? (:side s)) (some? (:ask s)) (not= (:text s) "")))
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

(defn answer
  "The answer to question `id` in `answers`, or nil — its own entry
  only, never one an object inherits (a question named constructor)."
  [answers id]
  (if (and (some? answers) (js/Object.hasOwn answers id)) (get answers id) nil))

(defn- waiting?
  "Does slide `s` wait for its ??? answer to be submitted?"
  [s answers]
  (let [a (:ask s)]
    (and (some? a) (:submit a) (nil? (:error a)) (nil? (answer answers (:id a))))))

(defn stop-at
  "Where a step from slide `from` to slide `to` of deck `ds` lands: at the
  first ??? on the way still waiting for its submit, else at `to`."
  [ds answers from to]
  (or (first (filter (fn [k] (waiting? (nth ds k) answers)) (range from to)))
      to))
