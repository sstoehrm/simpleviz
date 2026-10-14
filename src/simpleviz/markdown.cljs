(ns simpleviz.markdown
  "Markdown as hiccup, for the md page's read view and the slides:
  headings (# and a line of = under a paragraph), paragraphs, hard line
  breaks, emphasis, strikethrough, blockquotes, horizontal rules, lists
  and task lists, links (inline, reference, autolinks), images, tables,
  footnotes, fenced and indented code blocks and code spans. Anything else
  shows as the text it is. Links and images render through the caller's
  link-fn, so what a click does stays the page's business."
  (:require [mdlinks :as mdlinks]))

(def ^:private punct "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")

(def ^:private fence-re #"^( {0,3})(`{3,}|~{3,})(.*)$")
(def ^:private heading-re #"^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$")
(def ^:private item-re #"^( {0,3})([-*+]|\d{1,9}[.)])(?:([ \t]+)(.*))?$")
(def ^:private sep-re #"^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$")
(def ^:private def-re
  #"^ {0,3}\[((?:[^\]\\]|\\.)+)\]:[ \t]*(<[^>]*>|\S+)(?:[ \t]+(?:\"[^\"]*\"|'[^']*'|\([^)]*\)))?[ \t]*$")
(def ^:private autolink-re #"^<((?:https?|mailto):[^\s<>]+)>")
(def ^:private scheme-re #"^[a-zA-Z][a-zA-Z0-9+.-]*:")
(def ^:private hr-re #"^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$")
(def ^:private quote-re #"^ {0,3}> ?")
(def ^:private setext-re #"^ {0,3}=+[ \t]*$")
(def ^:private note-def-re #"^ {0,3}\[\^([^\]\s]+)\]:[ \t]*(.*)$")
(def ^:private note-ref-re #"^\[\^([^\]\s]+)\]")
(def ^:private task-re #"^\[([ xX])\][ \t]+")
(def ^:private space-re (js/RegExp. "\\s" "u"))
(def ^:private punct-re (js/RegExp. "[\\p{P}\\p{S}]" "u"))

(defn- blank? [l] (= "" (.trim l)))

(defn- indent [l] (- (count l) (count (.trimStart l))))

(defn- label-key
  "A reference label as definitions match it: whitespace runs collapsed,
  lower case."
  [s]
  (.toLowerCase (.replace (.trim s) (js/RegExp. "\\s+" "g") " ")))

(defn- strip-angles [d]
  (if (and (.startsWith d "<") (.endsWith d ">")) (subs d 1 (dec (count d))) d))

(defn- link-dest
  "A raw destination as followed: a URL with a scheme kept whole (escapes
  resolved), a path as mdlinks cleans it."
  [raw]
  (if (.test scheme-re raw)
    (.replace raw (js/RegExp. "\\\\([!-/:-@[-`{-~])" "g") "$1")
    (mdlinks/clean-dest raw)))

;; ---------------------------------------------------------------- inline

(defn- run-len [s i c]
  (loop [k i] (if (= (.charAt s k) c) (recur (inc k)) (- k i))))

(defn- code-close
  "Where the code span opened by `n` backticks closes, searching from
  `from`: the next run of exactly `n`, or nil."
  [s from n]
  (loop [k (.indexOf s "`" from)]
    (when (>= k 0)
      (let [r (run-len s k "`")]
        (if (= r n) k (recur (.indexOf s "`" (+ k r))))))))

(defn- code-text
  "A code span's text: line ends as spaces, one space stripped from both
  ends when both have one (so a span can start or end with a backtick)."
  [s]
  (let [t (.replaceAll s "\n" " ")]
    (if (and (.startsWith t " ") (.endsWith t " ") (not (blank? t)))
      (subs t 1 (dec (count t)))
      t)))

(defn- bracket-end
  "The ] closing the [ at i — nested brackets, escapes and code spans
  skipped — or nil."
  [s i]
  (let [n (count s)]
    (loop [k (inc i) depth 0]
      (when (< k n)
        (let [c (.charAt s k)]
          (cond (= c "\\") (recur (+ k 2) depth)
                (= c "`") (let [r (run-len s k "`")
                                e (code-close s (+ k r) r)]
                            (recur (if (some? e) (+ e r) (+ k r)) depth))
                (= c "[") (recur (inc k) (inc depth))
                (= c "]") (if (zero? depth) k (recur (inc k) (dec depth)))
                :else (recur (inc k) depth)))))))

(defn- link-tail
  "The ( destination \"title\" ) after a link's text, its ( at p:
  [raw-destination end], or nil when it isn't one."
  [s p]
  (let [n (count s)
        skip (fn [k] (loop [k k]
                       (if (and (< k n) (>= (.indexOf " \t\n" (.charAt s k)) 0)) (recur (inc k)) k)))
        k (skip (inc p))
        [dest k] (if (= (.charAt s k) "<")
                   (let [e (.indexOf s ">" k)]
                     (if (and (> e k) (not (.includes (subs s k e) "\n")))
                       [(subs s (inc k) e) (inc e)]
                       [nil k]))
                   (loop [j k depth 0]
                     (let [c (.charAt s j)]
                       (cond (or (= c "") (= c " ") (= c "\t") (= c "\n")) [(subs s k j) j]
                             (= c "\\") (recur (min n (+ j 2)) depth)
                             (= c "(") (recur (inc j) (inc depth))
                             (= c ")") (if (zero? depth) [(subs s k j) j] (recur (inc j) (dec depth)))
                             :else (recur (inc j) depth)))))]
    (when (some? dest)
      (let [k2 (skip k)
            closer (get {"\"" "\"" "'" "'" "(" ")"} (.charAt s k2))
            k3 (if (and (> k2 k) (some? closer))
                 (let [e (.indexOf s closer (inc k2))] (if (>= e 0) (skip (inc e)) k2))
                 k2)]
        (when (= (.charAt s k3) ")") [dest (inc k3)])))))

(declare inline)

(defn- delim?
  "Is token x a run of emphasis delimiters, not yet matched?"
  [x]
  (and (map? x) (some? (:delim x))))

(defn- delim-run
  "The run of `c` (* _ ~) at i of s as a delimiter token: its length and
  whether it can open and close emphasis — CommonMark's flanking rules,
  _ never inside a word."
  [s i c r]
  (let [ws? (fn [ch] (.test space-re ch))
        pn? (fn [ch] (.test punct-re ch))
        before (if (zero? i) " " (.charAt s (dec i)))
        after (if (>= (+ i r) (count s)) " " (.charAt s (+ i r)))
        left (and (not (ws? after)) (or (not (pn? after)) (ws? before) (pn? before)))
        right (and (not (ws? before)) (or (not (pn? before)) (ws? after) (pn? after)))]
    {:delim c :n r :orig r
     :open (if (= c "_") (and left (or (not right) (pn? before))) left)
     :close (if (= c "_") (and right (or (not left) (pn? after))) right)}))

(defn- as-text
  "Tokens with the delimiters left over as the text they are, adjacent
  texts joined."
  [ts]
  (let [acc []]
    (doseq [t ts]
      (let [t (if (delim? t) (.repeat (:delim t) (:n t)) t)
            k (dec (count acc))]
        (if (and (string? t) (>= k 0) (string? (aget acc k)))
          (aset acc k (str (aget acc k) t))
          (conj! acc t))))
    acc))

(defn- closes?
  "Can delimiter run d close opener o: same character, ~~ only with ~~,
  and not a pair the rule of three forbids (one side can do both and the
  lengths add up to a multiple of 3 that isn't one on each side)?"
  [o d]
  (and (= (:delim o) (:delim d))
       (if (= "~" (:delim d))
         (= (:n o) (:n d))
         (not (and (or (:close o) (:open d))
                   (zero? (mod (+ (:orig o) (:orig d)) 3))
                   (not (and (zero? (mod (:orig o) 3)) (zero? (mod (:orig d) 3)))))))))

(defn- emphasize!
  "Take delimiter run d into `out`, the tokens so far: while it closes
  the nearest open run it may, wrap what lies between in em, strong or
  del (runs inside that never closed become text); what is left of it
  opens, or is text. `openers` holds the open runs, `bottom` per kind of
  run how far down a search already failed — so nothing is searched twice."
  [out openers bottom d]
  (let [k (str (:delim d) (:open d) (mod (:orig d) 3))]
    (loop []
      (let [p (when (:close d)
                (loop [i (dec (count openers))]
                  (when (>= i (or (aget bottom k) 0))
                    (if (closes? (aget openers i) d) i (recur (dec i))))))]
        (if (nil? p)
          (do (when (:close d) (aset bottom k (count openers)))
              (if (:open d)
                (do (aset d "pos" (count out)) (conj! out d) (conj! openers d))
                (conj! out d)))
          (let [o (aget openers p)
                use (if (or (= "~" (:delim d)) (and (>= (:n o) 2) (>= (:n d) 2))) 2 1)
                tag (cond (= "~" (:delim d)) :del (= use 2) :strong :else :em)
                node (into [tag] (as-text (.splice out (inc (:pos o)))))]
            (set! (.-length openers) (inc p))
            (aset o "n" (- (:n o) use))
            (aset d "n" (- (:n d) use))
            (when (zero? (:n o)) (.pop out) (.pop openers))
            ;; no failed search reaches above the stack as it now is
            (doseq [bk (js/Object.keys bottom)]
              (aset bottom bk (min (aget bottom bk) (count openers))))
            (conj! out node)
            (when (pos? (:n d)) (recur))))))))

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

(defn- link-at
  "The link (or image, `img?`) whose text's [ is at b: {:node :end}, or
  nil when none starts there."
  [s b img? ctx]
  (when-let [e (bracket-end s b)]
    (let [label (subs s (inc b) e)
          defs (:defs ctx)
          nxt (.charAt s (inc e))
          tail (when (= nxt "(") (link-tail s (inc e)))
          [raw kind end]
          (or (cond (some? tail) [(first tail) (if img? "image" "inline") (second tail)]
                    (= nxt "[") (let [e2 (.indexOf s "]" (+ e 2))
                                      id (when (>= e2 0) (subs s (+ e 2) e2))
                                      d (when (some? id) (.get defs (label-key (if (= id "") label id))))]
                                  (when (some? d) [d "ref" (inc e2)]))
                    :else (when-let [d (.get defs (label-key label))] [d "ref" (inc e)]))
              [])]
      (when (some? raw)
        {:end end
         :node ((:link-fn ctx) (let [c (when-not (.test scheme-re raw) (mdlinks/dest-compare raw))]
                                 (cond-> {:dest (link-dest raw) :label label :kind kind}
                                   (some? c) (assoc :compare c)))
                (if img? [(if (= label "") raw label)] (inline label ctx)))}))))

(defn- inline
  "Hiccup children for inline text s."
  [s ctx]
  (let [n (count s)
        out []
        openers []
        bottom {}
        put! (fn [text x] (when-not (= text "") (conj! out text)) (conj! out x))]
    (loop [i 0 text ""]
      (if (>= i n)
        (as-text (if (= text "") out (conj! out text)))
        (let [c (.charAt s i)]
          (cond
            (and (= c "\\") (= (.charAt s (inc i)) "\n"))
            (do (put! text [:br]) (recur (+ i 2) ""))

            (and (= c "\\") (>= (.indexOf punct (.charAt s (inc i))) 0) (< (inc i) n))
            (recur (+ i 2) (str text (.charAt s (inc i))))

            ;; a line end: soft, or hard after two spaces; spaces before it go
            (= c "\n")
            ;; (the text is flushed at every line end, so it never grows)
            (let [t (.trimEnd text)]
              (if (.endsWith text "  ")
                (do (put! t [:br]) (recur (inc i) ""))
                (do (conj! out (str t "\n")) (recur (inc i) ""))))

            (or (= c "*") (= c "_") (= c "~"))
            (let [r (run-len s i c)]
              (if (and (= c "~") (not= r 2))
                (recur (+ i r) (str text (subs s i (+ i r))))
                (do (when-not (= text "") (conj! out text))
                    (emphasize! out openers bottom (delim-run s i c r))
                    (recur (+ i r) ""))))

            (and (= c "[") (= (.charAt s (inc i)) "^")
                 (some-> (.exec note-ref-re (subs s i)) (aget 1) label-key (->> (.has (:notes ctx)))))
            (let [m (.exec note-ref-re (subs s i))]
              (put! text (note-ref (label-key (aget m 1)) ctx))
              (recur (+ i (count (aget m 0))) ""))

            (= c "`")
            (let [r (run-len s i "`")
                  e (code-close s (+ i r) r)]
              (if (some? e)
                (do (put! text [:code (code-text (subs s (+ i r) e))]) (recur (+ e r) ""))
                (recur (+ i r) (str text (subs s i (+ i r))))))

            (or (= c "[") (and (= c "!") (= (.charAt s (inc i)) "[")))
            (let [img? (= c "!")
                  b (if img? (inc i) i)
                  hit (link-at s b img? ctx)]
              (if (some? hit)
                (do (put! text (:node hit)) (recur (:end hit) ""))
                (recur (inc b) (str text (subs s i (inc b))))))

            (and (= c "<") (some? (.exec autolink-re (subs s i))))
            (let [m (.exec autolink-re (subs s i))
                  u (aget m 1)]
              (put! text ((:link-fn ctx) {:dest u :label u :kind "inline"} [u]))
              (recur (+ i (count (aget m 0))) ""))

            :else (recur (inc i) (str text c))))))))

;; ---------------------------------------------------------------- blocks

(defn- fence-open [l]
  (when-let [m (.exec fence-re l)]
    (let [mark (aget m 2)]
      (when-not (and (= "`" (.charAt mark 0)) (.includes (aget m 3) "`"))
        {:indent (count (aget m 1)) :mark mark :info (.trim (aget m 3))}))))

(defn- fence-close? [l mark]
  (let [t (.trim l)]
    (and (< (indent l) 4) (>= (count t) (count mark))
         (= t (.repeat (.charAt mark 0) (count t))))))

(defn- cells
  "A table row's cells, trimmed; \\| is a pipe inside a cell."
  [l]
  (let [t (.trim l)
        t (if (.startsWith t "|") (subs t 1) t)
        t (if (and (.endsWith t "|") (not (.endsWith t "\\|"))) (subs t 0 (dec (count t))) t)]
    (loop [k 0 cur "" acc []]
      (if (>= k (count t))
        (conj acc (.trim cur))
        (let [c (.charAt t k)]
          (cond (and (= c "\\") (= (.charAt t (inc k)) "|")) (recur (+ k 2) (str cur "|") acc)
                (= c "|") (recur (inc k) "" (conj acc (.trim cur)))
                :else (recur (inc k) (str cur c) acc)))))))

(defn- table-start? [lines i]
  (and (< (inc i) (count lines))
       (.includes (nth lines i) "|")
       (.test sep-re (nth lines (inc i)))
       (= (count (cells (nth lines i))) (count (cells (nth lines (inc i)))))))

(defn- item-of
  "The list item line l starts, or nil: its marker, the column its
  content starts at and its first line."
  [l]
  (when-let [m (.exec item-re l)]
    (let [ind (count (aget m 1))
          mark (aget m 2)
          ws (or (aget m 3) "")
          body (or (aget m 4) "")
          ;; 5+ blanks after the marker: the content starts after one,
          ;; the rest is the content's own indentation
          [ws body] (if (> (count ws) 4) [" " (str (subs ws 1) body)] [ws body])
          empty? (blank? body)]
      {:ordered (not (js/isNaN (js/parseInt mark 10)))
       :delim (.charAt mark (dec (count mark)))
       :start (js/parseInt mark 10)
       :content (+ ind (count mark) (if empty? 1 (count ws)))
       :first (if empty? "" body)})))

(defn- hr? [l] (.test hr-re l))

(defn- quote? [l] (.test quote-re l))

(defn- interrupts?
  "Does line i end the paragraph before it?"
  [lines i]
  (let [l (nth lines i)]
    (or (blank? l) (.test heading-re l) (some? (fence-open l)) (hr? l) (quote? l)
        (.test note-def-re l) (some? (item-of l)) (table-start? lines i))))

(defn- para-line?
  "Could line l be paragraph text, which a lazy line may continue?"
  [l]
  (and (some? l) (not (blank? l)) (< (indent l) 4) (not (.test heading-re l))
       (not (hr? l)) (nil? (fence-open l))))

(defn- code-lines
  "Per line: is it inside a fenced code block, its fences included?"
  [lines]
  (let [acc []]
    (loop [i 0 fence nil]
      (if (>= i (count lines))
        acc
        (let [l (nth lines i)]
          (cond (some? fence) (do (conj! acc true)
                                  (recur (inc i) (when-not (fence-close? l (:mark fence)) fence)))
                (some? (fence-open l)) (do (conj! acc true) (recur (inc i) (fence-open l)))
                :else (do (conj! acc false) (recur (inc i) nil))))))))

(defn- note-end
  "The line after footnote definition i: its own line, then indented
  lines and lazy text lines, blank lines between indented ones."
  [lines i]
  (let [n (count lines)]
    (loop [j (inc i) blank-seen false]
      (if (>= j n)
        j
        (let [l (nth lines j)]
          (cond (blank? l) (recur (inc j) true)
                (>= (indent l) 4) (recur (inc j) false)
                (and (not blank-seen) (not (interrupts? lines j))) (recur (inc j) false)
                :else j))))))

(defn- drop-blank-tail [ls]
  (loop [ls ls] (if (and (seq ls) (blank? (last ls))) (recur (.slice ls 0 -1)) ls)))

(defn- list-items
  "The items of the list starting at line i: [items loose? end], each
  item its lines with the content's indentation removed. A blank line
  followed by more of the list makes it loose."
  [lines i]
  (let [n (count lines)
        it0 (item-of (nth lines i))]
    (loop [j (inc i) c (:content it0) cur [(:first it0)] items [] blank-seen false loose false]
      (let [done #(conj items (drop-blank-tail cur))]
        (if (>= j n)
          [(done) loose j]
          (let [l (nth lines j)
                it (item-of l)]
            (cond
              (blank? l) (recur (inc j) c (conj cur "") items true loose)
              (>= (indent l) c) (recur (inc j) c (conj cur (subs l c)) items false (or loose blank-seen))
              (hr? l) [(done) loose j]
              (some? it) (if (and (= (:ordered it) (:ordered it0)) (= (:delim it) (:delim it0)))
                           (recur (inc j) (:content it) [(:first it)] (done) false (or loose blank-seen))
                           [(done) loose j])
              ;; a lazy line goes on with the item's text
              (and (not blank-seen) (not (interrupts? lines j)) (not (.test setext-re l)))
              (recur (inc j) c (conj cur (.trim l)) items false loose)
              :else [(done) loose j])))))))

(declare blocks)

(defn- task-blocks
  "The blocks of task item lines `ls` — [ ] or [x] first — with the
  checkbox first in its first paragraph; nil when it is no task item."
  [ls ctx]
  (when-let [m (.exec task-re (or (first ls) ""))]
    (let [cb [:input {:type "checkbox" :checked (not= " " (aget m 1)) :disabled true}]
          bs (blocks (into [(subs (first ls) (count (aget m 0)))] (rest ls)) ctx)
          b0 (first bs)]
      (if (= "p" (first b0))
        (into [(into [:p cb] (rest b0))] (rest bs))
        (into [cb] bs)))))

(defn- list-block [lines i ctx]
  (let [it0 (item-of (nth lines i))
        [items loose end] (list-items lines i)
        lis (mapv (fn [ls]
                    (let [task (task-blocks ls ctx)
                          bs (or task (blocks ls ctx))]
                      (into (if (some? task) [:li {:class "task"}] [:li])
                            (if loose
                              bs
                              (mapcat (fn [b] (if (= "p" (first b)) (rest b) [b])) bs)))))
                  items)
        tag (cond (not (:ordered it0)) [:ul]
                  (= 1 (:start it0)) [:ol]
                  :else [:ol {:start (:start it0)}])]
    [(into tag lis) end]))

(defn- table-end
  "The line after the table starting at line i."
  [lines i]
  (let [n (count lines)]
    (loop [j (+ i 2)]
      (if (and (< j n) (let [l (nth lines j)]
                         (not (or (blank? l) (.test heading-re l) (some? (fence-open l))
                                  (hr? l) (quote? l) (.test note-def-re l) (some? (item-of l))))))
        (recur (inc j))
        j))))

(defn- aligns-of
  "The table's column alignments from its separator line: \"left\",
  \"center\", \"right\" or nil."
  [sep]
  (mapv (fn [c] (let [l (.startsWith c ":") r (.endsWith c ":")]
                  (cond (and l r) "center" r "right" l "left" :else nil)))
        (cells sep)))

(defn- fence-body
  "The lines of the fenced block opening at line i, unindented as far as
  its fence, and the line closing it — nil when none does."
  [lines i fence]
  (let [n (count lines)
        close (loop [k (inc i)]
                (cond (>= k n) nil
                      (fence-close? (nth lines k) (:mark fence)) k
                      :else (recur (inc k))))]
    [(mapv (fn [ln] (subs ln (min (indent ln) (:indent fence)))) (.slice lines (inc i) (or close n)))
     close]))

(defn- def-block
  "For the ```simpleviz-table fence opening at line i: its text and the
  line of the table right below it, blank lines between allowed; nil
  when it never closes or no table follows."
  [lines i fence]
  (let [n (count lines)
        [body close] (fence-body lines i fence)
        t (when (some? close)
            (loop [j (inc close)] (if (and (< j n) (blank? (nth lines j))) (recur (inc j)) j)))]
    (when (and (some? t) (< t n)
               (let [l (nth lines t)]
                 (and (< (indent l) 4) (not (.test heading-re l)) (not (quote? l)) (not (hr? l))
                      (table-start? lines t))))
      [(.join body "\n") t])))

(defn- formula? [c] (.startsWith c "=("))

(defn- style-of
  "A result's :style as the cell's inline style, in longhands, so a value
  can only ever be a colour; the server checked them too."
  [style]
  (cond-> {}
    (some? (:color style)) (assoc :color (:color style))
    (some? (:background style)) (assoc :background-color (:background style))
    (some? (:border style)) (assoc :border-color (:border style))))

(defn- result-cell
  "A body cell with something from the server — or, a formula, waiting
  for it: its error, else its value or the cell as written (a style
  alone keeps it), styled; … while a formula runs. A formula is marked
  calc and has itself as its title, a formatted value the cell as
  written."
  [c res al f? ctx]
  (let [[cls kids] (cond (some? (:error res)) ["calc error" [(:error res)]]
                         (some? (:value res)) [(when f? "calc") [(:value res)]]
                         f? ["calc pending" ["…"]]
                         :else [nil (inline c ctx)])
        cls (if (and (some? al) (some? cls)) (str al " " cls) (or al cls))]
    (into [:td (cond-> {}
                 (some? cls) (assoc :class cls)
                 (or f? (some? (:error res)) (some? (:value res))) (assoc :title c)
                 (some? (:style res)) (assoc :style (style-of (:style res))))]
          kids)))

(defn- table-block
  "The table starting at line i and the line after it, its body rows
  also collected in (:tables ctx) with the def block `defs` for the
  server to calculate; its cells show the results (:calc ctx) holds for
  it — {:failed msg} fails every formula, the other cells as written —
  and its own :error is its caption."
  [lines i defs ctx]
  (let [end (table-end lines i)
        aligns (aligns-of (nth lines (inc i)))
        grid (fn [l] (let [cs (cells l)] (mapv (fn [k] (or (get cs k) "")) (range (count aligns)))))
        body (mapv (fn [j] (grid (nth lines j))) (range (+ i 2) end))
        failed (:failed (:calc ctx))
        mine (when (nil? failed) (get (:calc ctx) (count (:tables ctx))))
        row (fn [tag cs r]
              (into [:tr] (map-indexed (fn [k a]
                                         (let [c (nth cs k)
                                               al (when (some? a) (str "al-" a))
                                               f? (and (= tag :td) (formula? c))
                                               res (when (= tag :td)
                                                     (if (and f? (some? failed)) {:error failed} (get-in mine [:results r k])))]
                                           (if (or f? (some? res))
                                             (result-cell c res al f? ctx)
                                             (into (if (some? al) [tag {:class al}] [tag]) (inline c ctx)))))
                                       aligns)))]
    (conj! (:tables ctx) {:cells body :defs defs})
    [(-> (cond-> [:table] (some? (:error mine)) (conj [:caption {:class "calc-error"} (:error mine)]))
         (conj [:thead (row :th (grid (nth lines i)) nil)]
               (into [:tbody] (map-indexed (fn [r cs] (row :td cs r)) body))))
     end]))

(defn- blocks
  "Hiccup for the block-level lines."
  [lines ctx]
  (let [n (count lines)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [l (nth lines i)
              fence (fence-open l)
              table-def (when (= "simpleviz-table" (:info fence)) (def-block lines i fence))
              heading (.exec heading-re l)]
          (cond
            (blank? l) (recur (inc i) out)

            (some? table-def)
            (let [[defs t] table-def
                  [tbl end] (table-block lines t defs ctx)]
              (recur end (conj out tbl)))

            (some? fence)
            (let [[body close] (fence-body lines i fence)]
              (recur (inc (or close n)) (conj out [:pre [:code (.join body "\n")]])))

            (some? heading)
            (recur (inc i) (conj out (into [(str "h" (count (aget heading 1)))]
                                           (inline (or (aget heading 2) "") ctx))))

            (>= (indent l) 4)
            (let [end (loop [j i]
                        (if (and (< j n) (or (blank? (nth lines j)) (>= (indent (nth lines j)) 4)))
                          (recur (inc j))
                          j))
                  body (drop-blank-tail (mapv (fn [ln] (if (blank? ln) "" (subs ln 4)))
                                              (.slice lines i end)))]
              (recur end (conj out [:pre [:code (.join body "\n")]])))

            (.test note-def-re l) (recur (note-end lines i) out)

            (.test def-re l) (recur (inc i) out)

            (hr? l) (recur (inc i) (conj out [:hr]))

            (quote? l)
            (let [[inner end]
                  (loop [j i acc [] fence nil]
                    (if (>= j n)
                      [acc j]
                      (let [ln (nth lines j)]
                        (cond (quote? ln)
                              (let [q (.replace ln quote-re "")]
                                (recur (inc j) (conj acc q)
                                       (if (some? fence)
                                         (when-not (fence-close? q (:mark fence)) fence)
                                         (fence-open q))))
                              ;; a lazy line goes on with the quote's paragraph
                              (and (nil? fence) (para-line? (last acc))
                                   (not (interrupts? lines j)) (not (.test setext-re ln)))
                              (recur (inc j) (conj acc ln) fence)
                              :else [acc j]))))]
              (recur end (conj out (into [:blockquote] (blocks inner ctx)))))

            (table-start? lines i)
            (let [[tbl end] (table-block lines i nil ctx)] (recur end (conj out tbl)))

            (some? (item-of l))
            (let [[t end] (list-block lines i ctx)] (recur end (conj out t)))

            :else
            (let [end (loop [j (inc i)]
                        (if (and (< j n) (not (interrupts? lines j)) (not (.test setext-re (nth lines j))))
                          (recur (inc j))
                          j))
                  text (.trimEnd (.join (mapv (fn [ln] (.trimStart ln)) (.slice lines i end)) "\n"))
                  h1? (and (< end n) (.test setext-re (nth lines end)))]
              (if h1?
                (recur (inc end) (conj out (into [:h1] (inline text ctx))))
                (recur end (conj out (into [:p] (inline text ctx))))))))))))

(defn- defs-of
  "The link reference definitions in lines (not in code, no footnotes):
  label key → raw destination, the first definition of a label winning."
  [lines code]
  (let [m (js/Map.)]
    (doseq [i (range (count lines))]
      (let [l (nth lines i)]
        (when-not (or (nth code i) (.test note-def-re l))
          (when-let [r (.exec def-re l)]
            (let [k (label-key (aget r 1))]
              (when-not (.has m k) (.set m k (strip-angles (aget r 2)))))))))
    m))

(defn- notes-of
  "The footnote definitions in lines (not in code): label key → their
  lines, indentation of the continuation lines removed; the first
  definition of a label wins."
  [lines code]
  (let [m (js/Map.)]
    (doseq [i (range (count lines))]
      (when-let [r (when-not (nth code i) (.exec note-def-re (nth lines i)))]
        (when-not (.has m (label-key (aget r 1)))
          (.set m (label-key (aget r 1))
                (into [(aget r 2)]
                      (map (fn [l] (if (>= (indent l) 4) (subs l 4) (.trimStart l)))
                           (.slice lines (inc i) (note-end lines i))))))))
    m))

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
                                   (with-backref (blocks (.get (:notes ctx) label) ctx) label))))))))))

(defn- untab
  "Line l with tabs in its indentation as four spaces."
  [l]
  (.replace l #"^[ \t]+" (fn [ws] (.replaceAll ws "\t" "    "))))

(defn- rendered
  "[blocks ctx] for markdown text, as render describes them."
  [text link-fn calc]
  (let [lines (mapv untab (.split (.replaceAll (str text) "\r\n" "\n") "\n"))
        code (code-lines lines)
        ctx {:defs (defs-of lines code) :notes (notes-of lines code) :order (js/Map.) :link-fn link-fn
             :tables [] :calc calc}
        bs (blocks lines ctx)
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
  ([text link-fn calc] (first (rendered text link-fn calc))))

(defn calc-tables
  "The text's tables, in the order render meets them, as the server
  calculates them: {:cells (body rows, as wide as the header) :defs
  (the def block's text, or nil)}."
  [text]
  (:tables (second (rendered text (fn [_ kids] kids) nil))))

(defn calc?
  "Has one of `tables` (calc-tables) a formula or a def block?"
  [tables]
  (boolean (some (fn [t] (or (some? (:defs t)) (some (fn [row] (some formula? row)) (:cells t))))
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
  (js/JSON.stringify (render text (fn [l kids] (into [:a {:href (:dest l) :compare (:compare l)}] kids)))))

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
        (let [l (untab (nth lines i))]
          (if (some? fence)
            (let [s (if (:quoted fence) (.replace l quote-prefix-re "") l)
                  close (fence-close? (subs s (min (:col fence) (indent s))) (:mark fence))]
              (conj! acc (if close "close" fence))
              (recur (inc i) (when-not close fence)))
            (let [q (aget (.match l quote-prefix-re) 0)
                  rest (subs l (count q))
                  col (count (aget (.match rest lead-re) 0))
                  f (some-> (fence-open (subs rest col)) (assoc :col col :quoted (not= "" q)))]
              (conj! acc (if (some? f) f false))
              (recur (inc i) f))))))))

(defn- step
  "Text after (f lines fenced), when it renders as text does — its
  shape `sh` — else text."
  [text sh f]
  (let [lines (.split text "\n")
        out (f lines (fenced-lines lines))]
    (if (= sh (shape out)) out text)))

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
            m (when-not (nth fenced i) (.exec heading-re l))]
        (if (nil? m)
          (conj! out l)
          (let [margin (not (.startsWith l " "))]
            (when (and margin (pos? i) (not (blank? (last out)))) (conj! out ""))
            (conj! out (tidy-heading l m))
            (when (and margin (some-> (nth lines (inc i) nil) blank? not)) (conj! out ""))))))
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
  (let [aligns (aligns-of (nth rows 1))
        n (count aligns)
        body (mapv (fn [l]
                     (let [cs (mapv (fn [c] (.replaceAll c "|" "\\|")) (cells l))]
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
    (when (and (not (nth fenced i)) (< (indent (untab l)) 4)
               (not (or (.test heading-re l) (quote? l) (hr? l) (.test def-re l)
                        (.test note-def-re l) (some? (item-of l))))
               (table-start? lines i))
      (let [end (table-end lines i)
            rows (.slice lines i end)
            ind (aget (.match l #"^[ \t]*") 0)]
        (when (every? (fn [r] (and (.startsWith r ind) (not (.test #"^[ \t]" (subs r (count ind))))
                                   (not (.test def-re r))))
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
        all (loop [ls lines i 0]
              (if (>= i (count ls))
                (.join ls "\n")
                (if-let [[out end] (tidied ls i)] (recur out end) (recur ls (inc i)))))]
    (if (or (= all text) (= sh (shape all)))
      all
      (loop [ls lines i 0]
        (if (>= i (count ls))
          (.join ls "\n")
          (if-let [[out end] (tidied ls i)]
            (recur (if (= sh (shape (.join out "\n"))) out ls) end)
            (recur ls (inc i))))))))

(defn- tidy-end
  "Line l without trailing blanks, but for a hard line break (two
  spaces before a line that follows) and a backslash they'd uncover."
  [l nxt]
  (let [t (.trimEnd l)]
    (cond (= t l) l
          (.endsWith t "\\") l
          (and (.endsWith l "  ") (some? nxt) (not (blank? nxt))) (str t "  ")
          :else t)))

(defn- line-ends
  "No trailing blanks but a hard break's — not in what may be code:
  indented, quoted, or with a backtick."
  [lines fenced]
  (.join (vec (map-indexed (fn [i l]
                             (if (or (nth fenced i) (>= (indent (untab l)) 4) (quote? l) (.includes l "`"))
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
        blank-at? (fn [i] (and (not (nth fenced i)) (blank? (nth lines i))))
        code-at? (fn [i] (and (not (nth fenced i)) (>= (indent (untab (nth lines i))) 4)))
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
                             (if (or (nth fenced i) (hr? (.trimStart l))) l (.replace l bullet-re "$1-$2")))
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
