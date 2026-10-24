(ns simpleviz.markdown
  "Markdown as hiccup, for the md page's read view and the slides:
  headings (# and a line of = under a paragraph), paragraphs, hard line
  breaks, emphasis, strikethrough, blockquotes, horizontal rules, lists
  and task lists, links (inline, reference, autolinks), images, tables,
  footnotes, fenced and indented code blocks and code spans, fenced code
  highlighted by its language (simpleviz.highlight). Anything else shows
  as the text it is. Links and images render through the caller's
  link-fn, so what a click does stays the page's business. The parsing
  is mdparse.js's; this turns its tree into hiccup."
  (:require ["./mdparse.js" :as mp]
            [mdlinks :as mdlinks]
            [simpleviz.chart :as chart]
            [simpleviz.highlight :as highlight]))

(def ^:private scheme-re #"^[a-zA-Z][a-zA-Z0-9+.-]*:")

(defn- link-dest
  "A raw destination as followed: a URL with a scheme kept whole (escapes
  resolved), a path as mdlinks cleans it."
  [raw]
  (if (.test scheme-re raw)
    (.replace raw (js/RegExp. "\\\\([!-/:-@[-`{-~])" "g") "$1")
    (mdlinks/clean-dest raw)))

;; ---------------------------------------------------------------- parses
;; A parse depends on the text alone, so the last few are kept: showing
;; a text asks calc-tables and render of it, a deck its slides' texts.

(def ^:private parses (js/Map.))

(defn- parsed [text]
  (let [doc (or (.get parses text) (mp/parse text))]
    ;; most recently used last
    (.delete parses text)
    (.set parses text doc)
    (when (> (.-size parses) 4) (.delete parses (.-value (.next (.keys parses)))))
    doc))

;; ---------------------------------------------------------------- hiccup

(declare node)

(defn- into-nodes
  "Vector v, pushed onto, with the hiccup of the nodes in array a from
  index i on."
  [v a i ctx]
  (loop [i i]
    (when (< i (.-length a))
      (.push v (node (aget a i) ctx))
      (recur (inc i))))
  v)

(defn- note-ref
  "The superscript number of footnote `label`, numbered by first use;
  only the first use carries the id its backlink leads to."
  [label ctx]
  (let [order (:order ctx)
        first? (not (.has order label))]
    (when first? (.set order label (inc (.-size order))))
    [:sup [:a (cond-> {:href (str "#fn-" label) :class "footnote-ref"}
                first? (assoc :id (str "fnref-" label)))
           (str (.get order label))]]))

(defn- link-node
  "A link or image node [\"link\" raw label kind kids] by the link-fn;
  an image's children are its text, or its destination without one."
  [x ctx]
  (let [raw (aget x 1)
        label (aget x 2)
        kids (aget x 4)
        c (when-not (.test scheme-re raw) (mdlinks/dest-compare raw))]
    ((:link-fn ctx) (cond-> {:dest (link-dest raw) :label label :kind (aget x 3)}
                      (some? c) (assoc :compare c))
     (if (nil? kids) [(if (= label "") raw label)] (into-nodes [] kids 0 ctx)))))

(defn- fence-node [lang code ctx]
  (let [hl (when (:highlight ctx) (highlight/highlight lang code))]
    [:pre (if (some? hl)
            (into [:code {:class (str "language-" lang)}] hl)
            [:code code])]))

(defn- formula? [c] (.startsWith c "=("))

(def ^:private tables-shown
  "The chart tables whose table shows instead of their chart, so a
  render keeps what the button chose. Keyed by the table's place among
  the text's tables and its chart's spec, which Format leaves alone:
  tidy compares renders, so a key on the table's text would stop it
  aligning a toggled table."
  (js/Set.))

(defn- toggle-table! [k e]
  (if (.has tables-shown k) (.delete tables-shown k) (.add tables-shown k))
  (some-> (.closest (.-currentTarget e) "figure") .-classList (.toggle "show-table")))

(defn- chart-part
  "A chart block's chart, by the server's reply `mine`: pending until it
  comes, its error, or the chart of `grid` with columns `names`."
  [mine failed names grid]
  (if (and (nil? failed) (nil? (:chart-error mine)) (some? (:chart mine)))
    (chart/view {:spec (:chart mine) :names names :grid grid})
    (let [[cls text] (cond (some? failed) ["error" failed]
                           (some? (:chart-error mine)) ["error" (:chart-error mine)]
                           :else ["pending" "…"])]
      [:div {:class (str "chart " cls)} text])))

(defn- chart-figure
  "Table `tbl`, the at-th of the text, shown as the chart its block
  `chart` draws of its `body` rows under `header`, by the server's reply
  `mine` (or `failed`), with a button turning it to the table and back;
  a broken chart shows the table."
  [tbl at chart header body mine failed]
  (let [k (str at "\n" chart)
        grid (vec (map-indexed (fn [r cs]
                                 (vec (map-indexed (fn [c x]
                                                     (let [res (get-in mine [:results r c])]
                                                       {:num (:num res) :label (or (:value res) (.trim x))}))
                                                   cs)))
                               body))
        broken? (or (some? failed) (some? (:chart-error mine)))]
    [:figure {:class (str "chart-table" (when (or broken? (.has tables-shown k)) " show-table"))}
     [:button {:class "chart-toggle" :title "Show the table or the chart"
               :on-click (fn [e] (toggle-table! k e))}
      [:span {:class "to-table"} "Table"] [:span {:class "to-chart"} "Chart"]]
     (chart-part mine failed header grid)
     tbl]))

(defn- style-of
  "A result's :style as the cell's inline style, in longhands, so a value
  can only ever be a colour; the server checked them too."
  [style]
  (cond-> {}
    (some? (:color style)) (assoc :color (:color style))
    (some? (:background style)) (assoc :background-color (:background style))
    (some? (:border style)) (assoc :border-color (:border style))))

(defn- result-cell
  "A body cell `c` with something from the server — or, a formula,
  waiting for it: its error, else its value or the cell as written (a
  style alone keeps it; `inl` its parsed text), styled; … while a
  formula runs. A formula is marked calc and has itself as its title, a
  formatted value the cell as written."
  [c inl res al f? ctx]
  (let [[cls kids] (cond (some? (:error res)) ["calc error" [(:error res)]]
                         (some? (:value res)) [(when f? "calc") [(:value res)]]
                         f? ["calc pending" ["…"]]
                         :else [nil (into-nodes [] inl 0 ctx)])
        cls (if (and (some? al) (some? cls)) (str al " " cls) (or al cls))]
    (into [:td (cond-> {}
                 (some? cls) (assoc :class cls)
                 (or f? (some? (:error res)) (some? (:value res))) (assoc :title c)
                 (some? (:style res)) (assoc :style (style-of (:style res))))]
          kids)))


(defn- calc-table
  "Table or solo chart node x as the server calculates it."
  [x]
  (if (= "solo" (aget x 0))
    {:cells [] :defs (aget x 1) :chart (aget x 2) :solo true}
    (cond-> {:cells (mapv vec (aget x 4)) :defs (aget x 6)}
      (some? (aget x 7)) (assoc :chart (aget x 7)))))

(defn- table-node
  "Table node x [\"table\" aligns header header-inline body body-inline
  defs chart], counted in (:tables ctx): its place among the text's
  tables (calc-tables) is where (:calc ctx) holds the server's results
  for it. Its cells show them — {:failed msg} fails every
  formula, the other cells as written — and its own :error is its
  caption. With `chart`, the text of the chart block below it, it shows
  as that chart, a button turning it to the table and back."
  [x ctx]
  (let [aligns (aget x 1)
        header (aget x 2)
        body (aget x 4)
        body-inl (aget x 5)
        chart (aget x 7)
        failed (:failed (:calc ctx))
        at (count (:tables ctx))
        mine (when (nil? failed) (get (:calc ctx) at))
        row (fn [tag cs inl r]
              (into [:tr] (map-indexed (fn [k a]
                                         (let [c (nth cs k)
                                               al (when (some? a) (str "al-" a))
                                               f? (and (= tag :td) (formula? c))
                                               res (when (= tag :td)
                                                     (if (and f? (some? failed)) {:error failed} (get-in mine [:results r k])))]
                                           (if (or f? (some? res))
                                             (result-cell c (aget inl k) res al f? ctx)
                                             (into-nodes (if (some? al) [tag {:class al}] [tag]) (aget inl k) 0 ctx))))
                                       aligns)))]
    (.push (:tables ctx) x)
    (let [tbl (-> (cond-> [:table] (some? (:error mine)) (conj [:caption {:class "calc-error"} (:error mine)]))
                  (conj [:thead (row :th header (aget x 3) nil)]
                        (into [:tbody] (map-indexed (fn [r cs] (row :td cs (aget body-inl r) r)) body))))]
      (if (nil? chart) tbl (chart-figure tbl at chart header body mine failed)))))

(defn- solo-chart
  "Solo chart node x [\"solo\" head spec], a ```simpleviz-chart block
  whose >> lines `head` name the data and its fields, the rest `spec`
  being the chart, counted in (:tables ctx) like a table: shows as the
  chart of the data the server read."
  [x ctx]
  (let [failed (:failed (:calc ctx))
        mine (when (nil? failed) (get (:calc ctx) (count (:tables ctx))))
        rows (mapv (fn [rec] (mapv (fn [v] {:num (when (number? v) v) :label (if (nil? v) "" (str v))}) rec))
                   (or (:data mine) []))]
    (.push (:tables ctx) x)
    [:figure {:class "chart-solo"} (chart-part mine failed (or (:fields mine) []) rows)]))

(defn- node
  "Hiccup for parsed node x (mdparse.js names them)."
  [x ctx]
  (if (string? x)
    x
    (case (aget x 0)
      "link" (link-node x ctx)
      "autolink" (let [u (aget x 1)] ((:link-fn ctx) {:dest u :label u :kind "inline"} [u]))
      "note" (note-ref (aget x 1) ctx)
      "fence" (fence-node (aget x 1) (aget x 2) ctx)
      "checkbox" [:input {:type "checkbox" :checked (aget x 1) :disabled true}]
      "li-task" (into-nodes [:li {:class "task"}] x 1 ctx)
      "ol-start" (into-nodes [:ol {:start (aget x 1)}] x 2 ctx)
      "table" (table-node x ctx)
      "solo" (solo-chart x ctx)
      (into-nodes [(aget x 0)] x 1 ctx))))

(defn- with-backref
  "Footnote `label`'s blocks with the link back to its first use at the
  end of the last paragraph."
  [bs label]
  (let [back [:a {:href (str "#fnref-" label) :class "footnote-backref"} "↩"]
        lst (last bs)]
    (if (= "p" (first lst))
      (conj (pop bs) (let [t (last lst)]
                       (if (string? t) (conj (pop lst) (str t " ") back) (conj lst " " back))))
      (conj bs [:p back]))))

(defn- footnotes
  "The footnotes used, in the order of their numbers — a note may use
  another — or nil when none was."
  [ctx]
  (let [order (:order ctx)]
    (when (pos? (.-size order))
      (loop [k 0 lis []]
        (if (>= k (.-size order))
          [:section {:class "footnotes"} (into [:ol] lis)]
          (let [label (nth (js/Array.from (.keys order)) k)]
            (recur (inc k)
                   (conj lis (into [:li {:id (str "fn-" label)}]
                                   (with-backref (into-nodes [] (mp/noteBlocks (:doc ctx) label) 0 ctx) label))))))))))

(defn- rendered
  "[blocks ctx] for markdown text, as render describes them, by opts
  {:link-fn :calc :highlight}; code is highlighted only with :highlight,
  which no structural comparison needs."
  [text opts]
  (let [doc (parsed (str text))
        ctx (merge {:doc doc :order (js/Map.) :tables []} opts)
        bs (into-nodes [] (mp/docBlocks doc) 0 ctx)
        notes (footnotes ctx)]
    [(if (some? notes) (conj bs notes) bs) ctx]))

(defn render
  "Hiccup blocks for markdown text. (link-fn link children) renders each
  link, link {:dest :label :kind}: :dest as mdlinks cleans a path (or a
  URL kept whole), :kind \"inline\", \"image\" or \"ref\", plus :compare
  when a path asks for one (?compare=<suffix>, mdlinks/dest-compare).
  A table's =( cells show what `calc` — the server's reply to
  calc-tables, per table {:results grid}, or {:failed msg} — holds for
  them."
  ([text link-fn] (render text link-fn nil))
  ([text link-fn calc] (first (rendered text {:link-fn link-fn :calc calc :highlight true}))))

(defn calc-tables
  "The text's tables, in the order render meets them, as the server
  calculates them: {:cells (body rows, as wide as the header) :defs
  (the def block's text, or nil)}."
  [text]
  (mapv calc-table (mp/tables (parsed (str text)))))

(defn may-calc?
  "Can md `text` hold anything the server calculates — a formula, or a
  table or chart block? A cheap test before parsing it."
  [text]
  (.test #"=\(|simpleviz-(table|chart)" text))

(defn calc?
  "Has one of `tables` (calc-tables) a formula or a def block?"
  [tables]
  (boolean (some (fn [t] (or (some? (:defs t)) (some? (:chart t)) (some (fn [row] (some formula? row)) (:cells t))))
                 tables)))

;; ---------------------------------------------------------------- tidy
;; Each step rewrites the text line by line, and counts only when the
;; text still renders the same: a line alone can't tell whether it sits
;; in a list item or a quote, the render can.

(def ^:private quote-prefix-re #"^(?: {0,3}>[ \t]?)*")
(def ^:private lead-re #"^(?:[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)*[ \t]*")
(def ^:private bullet-re #"^([ \t]*)[*+]([ \t]+\S)")

(defn- shape
  "What text renders to, as a string to compare."
  [text]
  (js/JSON.stringify
   (first (rendered text {:link-fn (fn [l kids] (into [:a {:href (:dest l) :compare (:compare l)}] kids))}))))

(defn- fenced-lines
  "Per line: may it be a fenced code block's, fences included — the
  open fence, or \"close\" for the line closing it? A fence counts
  behind quote and list markers and at any indentation, so what may be
  code is left alone; it closes as the renderer closes it."
  [lines]
  (let [acc []]
    (loop [i 0 fence nil]
      (if (>= i (count lines))
        acc
        (let [l (mp/untab (nth lines i))]
          (if (some? fence)
            (let [s (if (:quoted fence) (.replace l quote-prefix-re "") l)
                  close (mp/fenceClose (subs s (min (:col fence) (mp/indent s))) (:mark fence))]
              (conj! acc (if close "close" fence))
              (recur (inc i) (when-not close fence)))
            (let [q (aget (.match l quote-prefix-re) 0)
                  rest (subs l (count q))
                  col (count (aget (.match rest lead-re) 0))
                  f (when-let [m (mp/fenceMark (subs rest col))] {:mark m :col col :quoted (not= "" q)})]
              (conj! acc (if (some? f) f false))
              (recur (inc i) f))))))))

(defn- step
  "Text after (f lines fenced), when it renders as text does — its
  shape `sh` — else text."
  [text sh f]
  (let [lines (.split text "\n")
        out (f lines (fenced-lines lines))]
    (if (or (= out text) (= sh (shape out))) out text)))

(defn- tidy-heading [l m]
  (let [ind (aget (.match l #"^ *") 0)
        text (or (aget m 2) "")]
    (str ind (aget m 1) (when-not (= "" text) (str " " text)))))

(defn- headings
  "One space after a heading's #s, none closing it, a blank line around
  one at the margin."
  [lines fenced]
  (let [out []]
    (doseq [i (range (count lines))]
      (let [l (nth lines i)
            m (when-not (nth fenced i) (.exec mp/headingRe l))]
        (if (nil? m)
          (conj! out l)
          (let [margin (not (.startsWith l " "))]
            (when (and margin (pos? i) (not (mp/blank (last out)))) (conj! out ""))
            (conj! out (tidy-heading l m))
            (when (and margin (some-> (nth lines (inc i) nil) mp/blank not)) (conj! out ""))))))
    (.join out "\n")))

(defn- cell-width [s] (.-length (js/Array.from s)))

(defn- pad-cell [s w align]
  (let [gap (max 0 (- w (cell-width s)))
        left (case align "right" gap "center" (js/Math.floor (/ gap 2)) 0)]
    (str (.repeat " " left) s (.repeat " " (- gap left)))))

(defn- tidy-table
  "The table's lines `rows` (header, separator, body) with columns padded
  to one width and every row framed by pipes, indented by `ind`. Cells
  past the header's stay, unpadded."
  [rows ind]
  (let [aligns (mp/alignsOf (nth rows 1))
        n (count aligns)
        body (mapv (fn [l]
                     (let [cs (mapv (fn [c] (.replaceAll c "|" "\\|")) (mp/cells l))]
                       (into cs (repeat (max 0 (- n (count cs))) ""))))
                   (into [(first rows)] (drop 2 rows)))
        widths (mapv (fn [k] (apply max 3 (map (fn [cs] (cell-width (nth cs k))) body))) (range n))
        line (fn [cs] (str ind "| " (.join (vec (map-indexed (fn [k c] (if (< k n) (pad-cell c (nth widths k) (nth aligns k)) c)) cs)) " | ") " |"))
        sep (vec (map-indexed (fn [k a]
                                (let [w (nth widths k)]
                                  (case a
                                    "left" (str ":" (.repeat "-" (dec w)))
                                    "right" (str (.repeat "-" (dec w)) ":")
                                    "center" (str ":" (.repeat "-" (- w 2)) ":")
                                    (.repeat "-" w))))
                              aligns))]
    (into [(line (first body)) (str ind "| " (.join sep " | ") " |")] (map line (rest body)))))

(defn- table-at
  "The tidied lines of the table starting at line i and its end, or nil
  when no table starts there that a tidy can keep whole."
  [lines fenced i]
  (let [l (nth lines i)]
    (when (and (not (nth fenced i)) (< (mp/indent (mp/untab l)) 4)
               (not (or (.test mp/headingRe l) (mp/isQuote l) (mp/isHr l) (.test mp/defRe l)
                        (.test mp/noteDefRe l) (mp/isItem l)))
               (mp/tableStart lines i))
      (let [end (mp/tableEnd lines i)
            rows (.slice lines i end)
            ind (aget (.match l #"^[ \t]*") 0)]
        (when (every? (fn [r] (and (.startsWith r ind) (not (.test #"^[ \t]" (subs r (count ind))))
                                   (not (.test mp/defRe r))))
                      rows)
          [(tidy-table rows ind) end])))))

(defn- tables
  "Text with its tables' columns aligned: all at once when the text
  still renders the same, else each table that keeps it so."
  [text sh]
  ;; a tidied table has as many lines, so the fences stay where they are
  (let [lines (.split text "\n")
        fenced (fenced-lines lines)
        tidied (fn [lines i] (when-let [[rows end] (table-at lines fenced i)]
                               [(.concat (.slice lines 0 i) (clj->js rows) (.slice lines end)) end]))
        ;; every table tidied whose lines (keep? out) takes
        walk (fn [keep?]
               (loop [ls lines i 0]
                 (if (>= i (count ls))
                   (.join ls "\n")
                   (if-let [[out end] (tidied ls i)]
                     (recur (if (keep? out) out ls) end)
                     (recur ls (inc i))))))
        all (walk (fn [_] true))]
    (if (or (= all text) (= sh (shape all)))
      all
      (walk (fn [out] (= sh (shape (.join out "\n"))))))))

(defn- tidy-end
  "Line l without trailing blanks, but for a hard line break (two
  spaces before a line that follows) and a backslash they'd uncover."
  [l nxt]
  (let [t (.trimEnd l)]
    (cond (= t l) l
          (.endsWith t "\\") l
          (and (.endsWith l "  ") (some? nxt) (not (mp/blank nxt))) (str t "  ")
          :else t)))

(defn- line-ends
  "No trailing blanks but a hard break's — not in what may be code:
  indented, quoted, or with a backtick."
  [lines fenced]
  (.join (vec (map-indexed (fn [i l]
                             (if (or (nth fenced i) (>= (mp/indent (mp/untab l)) 4) (mp/isQuote l) (.includes l "`"))
                               l
                               (tidy-end l (nth lines (inc i) nil))))
                           lines))
         "\n"))

(defn- blank-lines
  "No blank lines at the start and end, one where there were more — but
  in indented code, whose blank lines are its text — and one newline at
  the end, unless the text ends in an open fence, whose end is code."
  [lines fenced]
  (let [n (count lines)
        blank-at? (fn [i] (and (not (nth fenced i)) (mp/blank (nth lines i))))
        code-at? (fn [i] (and (not (nth fenced i)) (>= (mp/indent (mp/untab (nth lines i))) 4)))
        ;; per line, the next line that is no blank
        nxt (let [a (js/Array. (inc n))]
              (aset a n nil)
              (loop [i (dec n)]
                (when (>= i 0)
                  (aset a i (let [j (inc i)] (cond (>= j n) nil (blank-at? j) (aget a j) :else j)))
                  (recur (dec i))))
              a)
        out []]
    (loop [i 0 prev nil last-blank false]
      (when (< i n)
        (if-not (blank-at? i)
          (do (conj! out (nth lines i)) (recur (inc i) i false))
          (let [nx (aget nxt i)
                keep (and (some? prev) (some? nx)
                          (or (not last-blank) (and (code-at? prev) (code-at? nx))))]
            (when keep (conj! out ""))
            (recur (inc i) prev (or last-blank keep))))))
    (cond (empty? out) ""
          (map? (last fenced)) (.join out "\n")
          :else (str (.join out "\n") "\n"))))

(defn- dash-bullets
  "* and + bullets as -."
  [lines fenced]
  (.join (vec (map-indexed (fn [i l]
                             (if (or (nth fenced i) (mp/isHr (.trimStart l))) l (.replace l bullet-re "$1-$2")))
                           lines))
         "\n"))

(defn tidy
  "Markdown text tidied, rendering the same: headings with one space
  after the #s and none closing, a blank line around those at the
  margin; tables with aligned columns; - for bullets; no trailing
  blanks but a hard break's; one blank line between blocks; one newline
  at the end. Code stays as it is. A step that would change how the
  text renders is left out."
  [text]
  (let [text (.replaceAll (str text) "\r\n" "\n")
        sh (shape text)]
    (-> text
        (step sh headings)
        (tables sh)
        (step sh line-ends)
        (step sh blank-lines)
        (step sh dash-bullets))))

(defn tidy-caret
  "Where caret p of text t goes in its tidied text t2: after as many
  characters that are no whitespace, and as much of the whitespace
  before p as t2 has there."
  [t t2 p]
  (let [before (subs t 0 p)
        k (count (.replace before (js/RegExp. "\\s" "g") ""))
        w (- (count before) (count (.trimEnd before)))
        at (loop [i 0 seen 0]
             (cond (>= seen k) i
                   (>= i (count t2)) (count t2)
                   :else (recur (inc i) (if (.test #"\s" (.charAt t2 i)) seen (inc seen)))))]
    (loop [i at]
      (if (and (< i (count t2)) (< (- i at) w) (.test #"\s" (.charAt t2 i))) (recur (inc i)) i))))
