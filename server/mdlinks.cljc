(ns mdlinks
  "The links in a markdown text, for the md page's » gutter and
  `simpleviz check` — squint compiles this file too (as it does
  themes.cljc), so it keeps to what both platforms share: strings
  through subs, count and .charAt, no regexes, and one reader
  conditional that turns a code point into a string."
  (:require [clojure.string :as str]))

(defn normalize-breaks
  "\\r\\n and lone \\r as \\n, so every line break is one character."
  [s]
  (str/replace (str/replace s "\r\n" "\n") "\r" "\n"))

(defn- ch
  "The character at i as a one-character string, \"\" past the end."
  [s i]
  (if (< i (count s)) (str (.charAt s i)) ""))

(defn- blank-char? [c] (or (= c " ") (= c "\t")))

(defn- line-starts
  "The offset each line of s starts at."
  [s]
  (loop [from 0 acc [0]]
    (let [i (.indexOf s "\n" from)]
      (if (neg? i) acc (recur (inc i) (conj acc (inc i)))))))

(defn- line-at
  "The 1-based line of offset i, for line-starts `starts`."
  [starts i]
  (loop [lo 0 hi (dec (count starts))]
    (if (>= lo hi)
      (inc lo)
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (nth starts mid) i) (recur mid hi) (recur lo (dec mid)))))))

(defn- cp->str [cp]
  #?(:clj (String. (Character/toChars cp))
     :cljs (js/String.fromCodePoint cp)))

(defn- hex-val [c]
  (.indexOf "0123456789abcdef" (str/lower-case c)))

(defn- utf8->str
  "The string UTF-8 `bytes` (ints 0-255) encode, nil when they are not
  valid UTF-8."
  [bytes]
  (loop [i 0 acc []]
    (if (>= i (count bytes))
      (apply str acc)
      (let [b (nth bytes i)
            [n init] (cond (< b 0x80) [0 b]
                           (= (bit-and b 0xE0) 0xC0) [1 (bit-and b 0x1F)]
                           (= (bit-and b 0xF0) 0xE0) [2 (bit-and b 0x0F)]
                           (= (bit-and b 0xF8) 0xF0) [3 (bit-and b 0x07)]
                           :else [nil nil])]
        (when (and (some? n) (< (+ i n) (count bytes)))
          (let [cp (loop [k 1 cp init]
                     (if (> k n)
                       cp
                       (let [c (nth bytes (+ i k))]
                         (when (= (bit-and c 0xC0) 0x80)
                           (recur (inc k) (bit-or (bit-shift-left cp 6) (bit-and c 0x3F)))))))]
            (when (and (some? cp) (<= cp 0x10FFFF))
              (recur (+ i n 1) (conj acc (cp->str cp))))))))))

(defn- percent-decode
  "s with %XX sequences decoded as UTF-8; s itself when one is
  malformed."
  [s]
  (loop [i 0 acc [] bytes []]
    (let [flush (fn [] (if (seq bytes) (utf8->str bytes) ""))]
      (cond
        (>= i (count s))
        (let [tail (flush)] (if (some? tail) (apply str (conj acc tail)) s))

        (= (ch s i) "%")
        (let [h (hex-val (ch s (inc i)))
              l (hex-val (ch s (+ i 2)))]
          (if (and (>= h 0) (>= l 0) (< (+ i 2) (count s)))
            (recur (+ i 3) acc (conj bytes (+ (* 16 h) l)))
            s))

        :else
        (let [tail (flush)]
          (if (some? tail)
            (recur (inc i) (conj acc tail (ch s i)) [])
            s))))))

(def ^:private punct "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")

(defn- unescape
  "s with backslash escapes of ASCII punctuation resolved."
  [s]
  (loop [i 0 acc []]
    (cond
      (>= i (count s)) (apply str acc)
      (and (= (ch s i) "\\") (>= (.indexOf punct (ch s (inc i))) 0) (< (inc i) (count s)))
      (recur (+ i 2) (conj acc (ch s (inc i))))
      :else (recur (inc i) (conj acc (ch s i))))))

(defn clean-dest
  "A raw destination as the link means it: escapes resolved, #fragment
  and ?query cut, %XX decoded."
  [raw]
  (let [d (unescape raw)
        cut (reduce (fn [end c] (let [k (.indexOf d c)] (if (and (>= k 0) (< k end)) k end)))
                    (count d) ["#" "?"])]
    (percent-decode (subs d 0 cut))))

(defn dest-compare
  "The fork suffix raw destination `raw` asks to compare with: the value
  of its first `compare` query parameter, %XX decoded (\"\" when it is
  empty), or nil when there is none. Escapes resolve and a #fragment
  ends the query, as clean-dest has it."
  [raw]
  (let [d (unescape raw)
        h (.indexOf d "#")
        d (if (>= h 0) (subs d 0 h) d)
        q (.indexOf d "?")]
    (when (>= q 0)
      (loop [from (inc q)]
        (when (<= from (count d))
          (let [amp (.indexOf d "&" from)
                end (if (neg? amp) (count d) amp)
                kv (subs d from end)]
            (cond (= kv "compare") ""
                  (str/starts-with? kv "compare=") (percent-decode (subs kv 8))
                  :else (recur (inc end)))))))))

(defn- with-compare
  "Link map l with :compare from raw destination `raw`, when it has one."
  [l raw]
  (if-let [c (dest-compare raw)] (assoc l :compare c) l))

(defn- normalize-label
  "A reference label as definitions match it: trimmed, whitespace runs
  collapsed to one space, lower case."
  [s]
  (loop [i 0 acc [] gap false]
    (if (>= i (count s))
      (str/lower-case (str/trim (apply str acc)))
      (let [c (ch s i)]
        (if (or (blank-char? c) (= c "\n"))
          (recur (inc i) acc true)
          (recur (inc i) (conj acc (if (and gap (seq acc)) (str " " c) c)) false))))))

(defn- skip-blanks
  "The first offset at or after i that is not a space or tab; with
  newline? also past at most one line break."
  [s i newline?]
  (loop [i i seen false]
    (let [c (ch s i)]
      (cond (blank-char? c) (recur (inc i) seen)
            (and newline? (not seen) (= c "\n")) (recur (inc i) true)
            :else i))))

(defn- blank-line-at?
  "Does a blank line start at offset i: a \\n, then only spaces and tabs
  up to another \\n or the end of the text? CommonMark's paragraph
  bound, which no label, code span or title crosses."
  [s i]
  (and (= (ch s i) "\n")
       (let [k (skip-blanks s (inc i) false)]
         (or (= (ch s k) "\n") (>= k (count s))))))

(defn- next-bound
  "The offset of the first blank line at or after `from`, else the
  text's length."
  [s from]
  (loop [k (.indexOf s "\n" from)]
    (cond (neg? k) (count s)
          (blank-line-at? s k) k
          :else (recur (.indexOf s "\n" (inc k))))))

(defn- bracket-pairs
  "The ] closing each [ of s, as {open close}: brackets nest, \\ escapes
  (but not a line break), and a blank line closes every [ still open.
  One pass, so an unmatched [ costs nothing per later one."
  [s]
  (let [n (count s)]
    ;; the stack of open offsets as nested [top rest] pairs, nil when empty
    (loop [k 0 stack nil pairs (transient {})]
      (if (>= k n)
        (persistent! pairs)
        (let [c (ch s k)]
          (cond (= c "\\") (recur (if (= (ch s (inc k)) "\n") (inc k) (+ k 2)) stack pairs)
                (= c "[") (recur (inc k) [k stack] pairs)
                (= c "]") (if (some? stack)
                            (recur (inc k) (second stack) (assoc! pairs (first stack) k))
                            (recur (inc k) stack pairs))
                (blank-line-at? s k) (recur (inc k) nil pairs)
                :else (recur (inc k) stack pairs)))))))

(defn- parse-dest
  "The destination starting at i: [raw end] — <…> form or a run without
  spaces whose parentheses balance — or nil."
  [s i]
  (if (= (ch s i) "<")
    (loop [k (inc i)]
      (let [c (ch s k)]
        (cond (or (= c "") (= c "\n") (= c "<")) nil
              (= c "\\") (recur (+ k 2))
              (= c ">") [(subs s (inc i) k) (inc k)]
              :else (recur (inc k)))))
    (loop [k i depth 0]
      (let [c (ch s k)]
        (cond (or (= c "") (blank-char? c) (= c "\n"))
              (when (and (> k i) (zero? depth)) [(subs s i k) k])
              (= c "\\") (recur (+ k 2) depth)
              (= c "(") (recur (inc k) (inc depth))
              (= c ")") (if (zero? depth)
                          (when (> k i) [(subs s i k) k])
                          (recur (inc k) (dec depth)))
              :else (recur (inc k) depth))))))

(defn- skip-title
  "The offset after an optional title (\"…\", '…' or (…)) at i, i when
  there is none, nil when one starts but does not end."
  [s i]
  (let [open (ch s i)
        close (get {"\"" "\"" "'" "'" "(" ")"} open)]
    (if (nil? close)
      i
      (loop [k (inc i)]
        (let [c (ch s k)]
          (cond (or (= c "") (blank-line-at? s k)) nil
                (= c "\\") (recur (+ k 2))
                (= c close) (inc k)
                :else (recur (inc k))))))))

(defn- inline-tail
  "The (dest \"title\") after a label, starting at the ( at i: [raw end]
  or nil."
  [s i]
  (when-let [[raw k] (parse-dest s (skip-blanks s (inc i) true))]
    (let [k (skip-blanks s k true)
          k (skip-title s k)]
      (when (some? k)
        (let [k (skip-blanks s k true)]
          (when (= (ch s k) ")") [raw (inc k)]))))))

(defn- fence-of
  "[char length] when line opens or closes a fence (``` or ~~~, up to
  three spaces of indent), else nil."
  [line]
  (let [k (skip-blanks line 0 false)
        c (ch line k)]
    (when (and (<= k 3) (or (= c "`") (= c "~")))
      (let [n (loop [n 0] (if (= (ch line (+ k n)) c) (recur (inc n)) n))]
        (when (>= n 3) [c n])))))

(defn fences
  "The fenced code blocks of md `text` outside quotes and lists, as the
  page reads them: [{:line (1-based, of the opening fence) :info (the
  rest of that line, trimmed) :body [lines] :closed}]. A ``` fence whose
  info holds a ` opens none."
  [text]
  (let [lines (vec (str/split (normalize-breaks (str text)) #"\n" -1))
        n (count lines)
        closes? (fn [line [c len]]
                  (when-let [[c2 len2] (fence-of line)]
                    (and (= c c2) (>= len2 len)
                         (= "" (str/trim (subs line (+ (skip-blanks line 0 false) len2)))))))]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [line (nth lines i)
              [c len :as f] (fence-of line)
              info (when (some? f) (str/trim (subs line (+ (skip-blanks line 0 false) len))))]
          (if (or (nil? f) (and (= c "`") (str/includes? info "`")))
            (recur (inc i) out)
            (let [end (loop [j (inc i)] (cond (>= j n) nil (closes? (nth lines j) f) j :else (recur (inc j))))]
              (recur (if (some? end) (inc end) n)
                     (conj out {:line (inc i) :info info :closed (some? end)
                                :body (subvec lines (inc i) (or end n))})))))))))

(defn- parse-def
  "[label raw-dest] when line is a reference definition, else nil."
  [line]
  (let [k (skip-blanks line 0 false)]
    (when (and (<= k 3) (= (ch line k) "["))
      (when-let [e (get (bracket-pairs line) k)]
        ;; [^label]: is a footnote, no link
        (when (and (> e (inc k)) (= (ch line (inc e)) ":") (not= (ch line (inc k)) "^"))
          (when-let [[raw d] (parse-dest line (skip-blanks line (+ e 2) false))]
            (let [t (skip-blanks line d false)
                  t (if (> t d) (skip-title line t) t)]
              (when (and (some? t) (= (skip-blanks line t false) (count line)))
                [(subs line (inc k) e) raw]))))))))

(defn- scan-lines
  "Per line of t: :code (inside or on a fence), or a definition; returns
  {:code #{line-index} :defs {label {:dest :line}} :def-lines [...]}"
  [t starts]
  (let [n (count starts)]
    (loop [li 0 fence nil code #{} defs {} order []]
      (if (>= li n)
        {:code code :defs defs :def-lines order}
        (let [start (nth starts li)
              end (if (< (inc li) n) (dec (nth starts (inc li))) (count t))
              line (subs t start end)
              f (fence-of line)]
          (cond
            (some? fence)
            (let [closes (and (some? f) (= (first f) (first fence)) (>= (second f) (second fence))
                              (= (skip-blanks line (+ (skip-blanks line 0 false) (second f)) false)
                                 (count line)))]
              (recur (inc li) (if closes nil fence) (conj code li) defs order))
            (some? f) (recur (inc li) f (conj code li) defs order)
            :else
            (if-let [[label raw] (parse-def line)]
              (let [k (normalize-label label)]
                (if (contains? defs k)
                  (recur (inc li) nil (conj code li) defs order)
                  (recur (inc li) nil (conj code li)
                         (assoc defs k (with-compare {:dest (clean-dest raw) :line (inc li)} raw))
                         (conj order (with-compare {:line (inc li) :label label :dest (clean-dest raw) :kind "def"} raw)))))
              (recur (inc li) nil code defs order))))))))

(defn- blank-lines
  "t with the characters of the lines in `skip` turned to spaces (line
  breaks kept), so offsets and line numbers stay."
  [t starts skip]
  (if (empty? skip)
    t
    (apply str
           (map-indexed (fn [li start]
                          (let [end (if (< (inc li) (count starts)) (nth starts (inc li)) (count t))
                                seg (subs t start end)]
                            (if (contains? skip li)
                              (apply str (map (fn [c] (if (= (str c) "\n") "\n" " ")) seg))
                              seg)))
                        starts))))

(defn- ref-use
  "A reference use on `line` of definition d, taking its destination."
  [line label d]
  (cond-> {:line line :label label :dest (:dest d) :kind "ref"}
    (contains? d :compare) (assoc :compare (:compare d))))

(defn links
  "The links in markdown `text`, in text order: [{:line :label :dest
  :kind}], :kind one of \"inline\", \"image\", \"ref\", \"def\", plus
  :compare when the destination asks for one (dest-compare; a reference
  use takes its definition's). A link with an empty destination is left
  out."
  [text]
  (let [t (normalize-breaks (str text))
        starts (line-starts t)
        {:keys [code defs def-lines]} (scan-lines t starts)
        s (blank-lines t starts code)
        n (count s)
        pairs (bracket-pairs s)
        ;; `bound`: the next blank line at or after i, which no code span
        ;; crosses (kept in step as i moves, so finding it stays linear)
        found (loop [i 0 bound (next-bound s 0) acc []]
                (if (>= i n)
                  acc
                  (let [c (ch s i)
                        bound (if (> i bound) (next-bound s i) bound)]
                    (cond
                      (= c "\\") (recur (+ i 2) bound acc)
                      (= c "`")
                      (let [run (loop [k i] (if (= (ch s k) "`") (recur (inc k)) (- k i)))
                            ticks (apply str (repeat run "`"))
                            close (loop [from (+ i run)]
                                    (let [k (.indexOf s ticks from)]
                                      (cond (or (neg? k) (>= k bound)) nil
                                            (= (ch s (+ k run)) "`")
                                            (recur (loop [j k] (if (= (ch s j) "`") (recur (inc j)) j)))
                                            :else k)))]
                        (recur (if (some? close) (+ close run) (+ i run)) bound acc))
                      (or (= c "[") (and (= c "!") (= (ch s (inc i)) "[")))
                      (let [img (= c "!")
                            open (if img (inc i) i)
                            e (get pairs open)]
                        (if (nil? e)
                          (recur (inc open) bound acc)
                          (let [label (subs s (inc open) e)
                                line (line-at starts i)
                                nxt (ch s (inc e))]
                            (cond
                              (= nxt "(")
                              (if-let [[raw end] (inline-tail s (inc e))]
                                (recur end bound (conj acc (with-compare {:line line :label label :dest (clean-dest raw)
                                                                    :kind (if img "image" "inline")}
                                                                   raw)))
                                (recur (inc open) bound acc))
                              (= nxt "[")
                              (let [e2 (get pairs (inc e))
                                    id (when (some? e2) (subs s (+ e 2) e2))
                                    d (when (some? id)
                                        (get defs (normalize-label (if (= id "") label id))))]
                                (if (some? d)
                                  (recur (inc e2) bound (conj acc (ref-use line label d)))
                                  (recur (inc open) bound acc)))
                              :else
                              (if-let [d (get defs (normalize-label label))]
                                (recur (inc e) bound (conj acc (ref-use line label d)))
                                (recur (inc open) bound acc))))))
                      :else (recur (inc i) bound acc)))))]
    (vec (remove (fn [l] (= "" (:dest l))) (sort-by :line (concat found def-lines))))))

(defn- scheme? [d]
  (let [c (ch d 0)
        alpha (fn [c] (>= (.indexOf "abcdefghijklmnopqrstuvwxyz" (str/lower-case c)) 0))]
    (and (not= c "") (alpha c)
         (loop [k 1]
           (let [c (ch d k)]
             (cond (= c ":") true
                   (or (= c "") (not (or (alpha c) (>= (.indexOf "0123456789+.-" c) 0)))) false
                   :else (recur (inc k))))))))

(defn followable?
  "Can simpleviz open link destination d: a relative path to an .md,
  .edn, .png or .svg file (case-insensitive)?"
  [d]
  (let [d (str d)
        low (str/lower-case d)]
    (boolean
     (and (not= d "")
          (not (scheme? d))
          (not (str/starts-with? d "/"))
          (some (fn [ext] (str/ends-with? low ext)) [".md" ".edn" ".png" ".svg"])))))

(defn side-target
  "What the rest of a presentation's `>>` line names, {:dest} plus
  :compare when it asks for one: the destination of its first link —
  [text](path) or ![alt](path), as a link means it — else the rest as a
  plain path (`path?compare=<suffix>` too); nil when there is nothing."
  [rest]
  (let [t (str/trim rest)
        l (first (filter (fn [l] (or (= "inline" (:kind l)) (= "image" (:kind l)))) (links t)))
        c (when (and (nil? l) (not= t "")) (dest-compare t))]
    (cond (some? l) (cond-> {:dest (:dest l)} (some? (:compare l)) (assoc :compare (:compare l)))
          (= t "") nil
          (some? c) {:dest (subs t 0 (.indexOf t "?")) :compare c}
          :else {:dest t})))

(defn side-kind
  "What a presentation's side target shows as: \"graph\" (.edn), \"md\",
  \"export\" (.png or .svg: a graph when it carries simpleviz EDN, else a
  picture), \"image\" (.jpg .jpeg .gif .webp); nil for anything else."
  [dest]
  (let [low (str/lower-case dest)]
    (get {"edn" "graph" "md" "md" "png" "export" "svg" "export"
          "jpg" "image" "jpeg" "image" "gif" "image" "webp" "image"}
         (subs low (inc (.lastIndexOf low ".")))
         nil)))

(defn quote-target
  "What the rest of a presentation's `>` line names, as side-target has
  it — only when that rest is one link or picture and nothing else, to
  a file of the folder a slide can show (relative, side-kind); nil
  otherwise, the line then being a quote."
  [rest]
  (let [t (str/trim rest)
        open (if (str/starts-with? t "![") 1 0)
        e (when (= (ch t open) "[") (get (bracket-pairs t) open))
        [raw end] (when (and (some? e) (= (ch t (inc e)) "(")) (inline-tail t (inc e)))
        dest (when (= end (count t)) (clean-dest raw))]
    (when (and (some? dest) (some? (side-kind dest))
               (not (scheme? dest)) (not (str/starts-with? dest "/")))
      (with-compare {:dest dest} raw))))
