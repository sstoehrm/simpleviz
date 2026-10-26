(ns deck
  "A presentation from an md page's text: lines of just --- split it into
  slides (not inside a fenced code block), and a slide whose first line
  is `> [text](path)` — or `> ![alt](path)` — shows that file beside its
  text when it is one a slide can show; any other `>` line is a quote.
  `>> [text](path)` and `>> path` work too. Empty slides are dropped, so a deck may open and
  close with ---.

  A slide whose first lines hold `?? name` asks a question: its task
  list (- [ ] lines, [x] the right ones) gives the 2 to 4 choices, the
  lines indented under a choice its explanation. `??? name` asks for a
  submit before the deck goes on.

  The page presents with it (simpleviz.slides) and `simpleviz check`
  reports its malformed questions, so squint compiles this file too (as
  it does mdlinks.cljc): it keeps to what both platforms share."
  (:require [clojure.string :as str]
            [mdlinks :as mdlinks]))

(defn- fence-mark
  "The fence a line opens or closes (\"```\" or \"~~~\"), or nil."
  [line]
  (let [t (str/triml line)]
    (cond (str/starts-with? t "```") "```"
          (str/starts-with? t "~~~") "~~~"
          :else nil)))

(defn- chunks
  "The text's lines (nil reads as \"\"), split at the separators outside
  code fences: [{:start <1-based line of its first line> :lines [...]}]."
  [text]
  (loop [lines (str/split (mdlinks/normalize-breaks (str text)) #"\n" -1)
         n 1
         fence nil
         cur {:start 1 :lines []}
         out []]
    (if (empty? lines)
      (conj out cur)
      (let [line (first lines)
            mark (fence-mark line)
            add (fn [c] (update c :lines conj line))]
        (cond
          (some? fence) (recur (rest lines) (inc n) (if (= mark fence) nil fence) (add cur) out)
          (= (str/trim line) "---") (recur (rest lines) (inc n) nil {:start (inc n) :lines []} (conj out cur))
          :else (recur (rest lines) (inc n) mark (add cur) out))))))

(defn- blank? [line] (= (str/trim line) ""))

(defn- indent [l] (- (count l) (count (str/triml l))))

(defn- untab
  "Line l with tabs in its indentation as four spaces."
  [l]
  (let [ws (re-find #"^[ \t]+" l)]
    (if (some? ws) (str (str/replace ws "\t" "    ") (subs l (count ws))) l)))

(defn- task-item
  "The task item line l starts — a bullet item whose text opens with
  [ ] or [x] — as {:checked :text}, or nil. Five or more blanks after
  the bullet make the [ ] the item's indented code, no task."
  [l]
  (let [m (re-find #"^ {0,3}[-*+][ \t]{1,4}\[([ xX])\][ \t]+" l)]
    (when (some? m)
      {:checked (not= " " (nth m 1)) :text (str/trim (subs l (count (nth m 0))))})))

(defn- trim-blank-lines
  "The lines without the blank ones at either end, joined."
  [lines]
  (let [ls (drop-while blank? lines)
        ls (reverse (drop-while blank? (reverse ls)))]
    (str/join "\n" ls)))

(defn- dedent
  "The lines without the indent they share, blank ends dropped; nil when
  nothing is left."
  [lines]
  (let [n (apply min 1000 (map indent (remove blank? lines)))
        t (trim-blank-lines (map (fn [l] (if (blank? l) "" (subs l n))) lines))]
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
              m (when (nil? fence) (task-item line))]
          (cond
            (some? fence) (recur (rest ls) (if (= mark fence) nil fence) (conj text line) choices cur)
            (and (some? cur) (or (blank? line) (>= (indent line) 2)))
            (recur (rest ls) nil text choices (update cur :note conj line))
            (some? m) (recur (rest ls) nil text (close)
                             {:choice {:text (:text m) :correct (:checked m)} :note []})
            :else (recur (rest ls) mark (conj text line) (close) nil)))))))

(defn- ask
  "The question `?? name` (`???` when `submit`) asks over body `lines`:
  [text {:id :submit :choices :error}]. Without a name, or without 2 to 4
  choices, :error says so and the lines stay text."
  [marker id lines]
  (let [[text choices] (split-choices lines)
        n (count choices)
        error (cond (= id "") (str marker " needs a name, as in " marker " storage")
                    ;; the page keeps the answers in an object, where it is no key
                    (= id "__proto__") (str marker " __proto__ can't be a question's name")
                    (not (<= 2 n 4)) (str marker " " id " takes 2 to 4 choices, - [ ] lines; it has " n)
                    :else nil)]
    [(trim-blank-lines (if (some? error) lines text))
     {:id id :submit (= marker "???") :error error :choices (if (some? error) [] choices)}]))

(defn- slide
  "One slide from its lines: [{:text :side} at], :side the `>` or `>>` target of
  its first lines (nil when there is none), plus :side-compare when that
  asks to compare; with `?? name` there too, :ask the question and `at`
  the index of its line (else nil)."
  [lines]
  (let [lines (vec lines)
        skip (fn [i] (if (and (< i (count lines)) (blank? (nth lines i))) (recur (inc i)) i))]
    (loop [i (skip 0) side nil q nil]
      (let [head (get lines i)]
        (cond
          (and (nil? side) (some? head) (str/starts-with? head ">>"))
          (recur (skip (inc i)) {:target (mdlinks/side-target (subs head 2))} q)

          (and (nil? side) (some? head) (str/starts-with? head ">")
               (some? (mdlinks/quote-target (subs head 1))))
          (recur (skip (inc i)) {:target (mdlinks/quote-target (subs head 1))} q)

          (and (nil? q) (some? head) (str/starts-with? head "??"))
          (let [marker (if (str/starts-with? head "???") "???" "??")]
            (recur (skip (inc i)) side
                   {:marker marker :id (str/trim (subs head (count marker))) :at i}))

          :else
          (let [ls (subvec lines i)
                target (:target side)
                base (cond-> {:text (trim-blank-lines ls) :side (if (some? target) (:dest target) nil)}
                       (some? (:compare target)) (assoc :side-compare (:compare target)))]
            (if (nil? q)
              [base nil]
              (let [[text a] (ask (:marker q) (:id q) ls)]
                [(assoc base :text text :ask a) (:at q)]))))))))

(defn slides
  "The slides of md `text`, in order: [{:text md :side path-or-nil}]."
  [text]
  (filterv (fn [s] (or (some? (:side s)) (some? (:ask s)) (not= (:text s) "")))
           (mapv (fn [c] (first (slide (:lines c)))) (chunks text))))

(defn warnings
  "The malformed questions of md `text`, as the page shows them on their
  slide: one [N error] each, N the line of its ?? or ???."
  [text]
  (vec (keep (fn [{:keys [start lines]}]
               (let [[s at] (slide lines)]
                 (when-let [e (:error (:ask s))] [(+ start at) e])))
             (chunks text))))
