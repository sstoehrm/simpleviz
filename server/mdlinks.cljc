(ns mdlinks
  "The links in a markdown text, for the md page's » gutter and
  `simpleviz check` — squint compiles this file too (as it does
  themes.cljc), so it keeps to what both platforms share: strings
  through subs, count and .charAt, no regexes, and one reader
  conditional that turns a code point into a string."
  (:require [clojure.string :as str]))

(defn- normalize-breaks
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

(defn- clean-dest
  "A raw destination as the link means it: escapes resolved, #fragment
  and ?query cut, %XX decoded."
  [raw]
  (let [d (unescape raw)
        cut (reduce (fn [end c] (let [k (.indexOf d c)] (if (and (>= k 0) (< k end)) k end)))
                    (count d) ["#" "?"])]
    (percent-decode (subs d 0 cut))))

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

(defn- label-end
  "The offset of the ] closing the [ at i (brackets nest, \\ escapes),
  or nil."
  [s i]
  (loop [k (inc i) depth 0]
    (let [c (ch s k)]
      (cond (= c "") nil
            (= c "\\") (recur (+ k 2) depth)
            (= c "[") (recur (inc k) (inc depth))
            (= c "]") (if (zero? depth) k (recur (inc k) (dec depth)))
            :else (recur (inc k) depth)))))

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
          (cond (= c "") nil
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

(defn- parse-def
  "[label raw-dest] when line is a reference definition, else nil."
  [line]
  (let [k (skip-blanks line 0 false)]
    (when (and (<= k 3) (= (ch line k) "["))
      (when-let [e (label-end line k)]
        (when (and (> e (inc k)) (= (ch line (inc e)) ":"))
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
                         (assoc defs k {:dest (clean-dest raw) :line (inc li)})
                         (conj order {:line (inc li) :label label :dest (clean-dest raw) :kind "def"}))))
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

(defn links
  "The links in markdown `text`, in text order: [{:line :label :dest
  :kind}], :kind one of \"inline\", \"image\", \"ref\", \"def\".
  A link with an empty destination is left out."
  [text]
  (let [t (normalize-breaks (str text))
        starts (line-starts t)
        {:keys [code defs def-lines]} (scan-lines t starts)
        s (blank-lines t starts code)
        n (count s)
        found (loop [i 0 acc []]
                (if (>= i n)
                  acc
                  (let [c (ch s i)]
                    (cond
                      (= c "\\") (recur (+ i 2) acc)
                      (= c "`")
                      (let [run (loop [k i] (if (= (ch s k) "`") (recur (inc k)) (- k i)))
                            ticks (apply str (repeat run "`"))
                            close (loop [from (+ i run)]
                                    (let [k (.indexOf s ticks from)]
                                      (cond (neg? k) nil
                                            (= (ch s (+ k run)) "`")
                                            (recur (loop [j k] (if (= (ch s j) "`") (recur (inc j)) j)))
                                            :else k)))]
                        (recur (if (some? close) (+ close run) (+ i run)) acc))
                      (or (= c "[") (and (= c "!") (= (ch s (inc i)) "[")))
                      (let [img (= c "!")
                            open (if img (inc i) i)
                            e (label-end s open)]
                        (if (nil? e)
                          (recur (inc open) acc)
                          (let [label (subs s (inc open) e)
                                line (line-at starts i)
                                nxt (ch s (inc e))]
                            (cond
                              (= nxt "(")
                              (if-let [[raw end] (inline-tail s (inc e))]
                                (recur end (conj acc {:line line :label label :dest (clean-dest raw)
                                                      :kind (if img "image" "inline")}))
                                (recur (inc open) acc))
                              (= nxt "[")
                              (let [e2 (label-end s (inc e))
                                    id (when (some? e2) (subs s (+ e 2) e2))
                                    d (when (some? id)
                                        (get defs (normalize-label (if (= id "") label id))))]
                                (if (some? d)
                                  (recur (inc e2) (conj acc {:line line :label label :dest (:dest d) :kind "ref"}))
                                  (recur (inc open) acc)))
                              :else
                              (if-let [d (get defs (normalize-label label))]
                                (recur (inc e) (conj acc {:line line :label label :dest (:dest d) :kind "ref"}))
                                (recur (inc open) acc))))))
                      :else (recur (inc i) acc)))))]
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
