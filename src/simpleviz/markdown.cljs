(ns simpleviz.markdown
  "Markdown as hiccup, for the md page's read view: headings, paragraphs,
  lists, links (inline, reference, autolinks), images (as links), tables,
  fenced and indented code blocks and code spans. Anything else shows as
  the text it is. Links render through the caller's link-fn, so what a
  click does stays the page's business."
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
         :node ((:link-fn ctx) {:dest (link-dest raw) :label label :kind kind}
                (if img? [(if (= label "") raw label)] (inline label ctx)))}))))

(defn- inline
  "Hiccup children for inline text s."
  [s ctx]
  (let [n (count s)]
    (loop [i 0 text "" out []]
      (if (>= i n)
        (if (= text "") out (conj out text))
        (let [c (.charAt s i)
              put (fn [x] (conj (if (= text "") out (conj out text)) x))]
          (cond
            (and (= c "\\") (>= (.indexOf punct (.charAt s (inc i))) 0) (< (inc i) n))
            (recur (+ i 2) (str text (.charAt s (inc i))) out)

            (= c "`")
            (let [r (run-len s i "`")
                  e (code-close s (+ i r) r)]
              (if (some? e)
                (recur (+ e r) "" (put [:code (code-text (subs s (+ i r) e))]))
                (recur (+ i r) (str text (subs s i (+ i r))) out)))

            (or (= c "[") (and (= c "!") (= (.charAt s (inc i)) "[")))
            (let [img? (= c "!")
                  b (if img? (inc i) i)
                  hit (link-at s b img? ctx)]
              (if (some? hit)
                (recur (:end hit) "" (put (:node hit)))
                (recur (inc b) (str text (subs s i (inc b))) out)))

            (and (= c "<") (some? (.exec autolink-re (subs s i))))
            (let [m (.exec autolink-re (subs s i))
                  u (aget m 1)]
              (recur (+ i (count (aget m 0))) ""
                     (put ((:link-fn ctx) {:dest u :label u :kind "inline"} [u]))))

            :else (recur (inc i) (str text c) out)))))))

;; ---------------------------------------------------------------- blocks

(defn- fence-open [l]
  (when-let [m (.exec fence-re l)]
    (let [mark (aget m 2)]
      (when-not (and (= "`" (.charAt mark 0)) (.includes (aget m 3) "`"))
        {:indent (count (aget m 1)) :mark mark}))))

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

(defn- interrupts?
  "Does line i end the paragraph before it?"
  [lines i]
  (let [l (nth lines i)]
    (or (blank? l) (.test heading-re l) (some? (fence-open l)) (some? (item-of l))
        (table-start? lines i))))

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
              (some? it) (if (and (= (:ordered it) (:ordered it0)) (= (:delim it) (:delim it0)))
                           (recur (inc j) (:content it) [(:first it)] (done) false (or loose blank-seen))
                           [(done) loose j])
              ;; a lazy line goes on with the item's text
              (and (not blank-seen) (not (interrupts? lines j)))
              (recur (inc j) c (conj cur (.trim l)) items false loose)
              :else [(done) loose j])))))))

(declare blocks)

(defn- list-block [lines i ctx]
  (let [it0 (item-of (nth lines i))
        [items loose end] (list-items lines i)
        lis (mapv (fn [ls]
                    (let [bs (blocks ls ctx)]
                      (into [:li] (if loose
                                    bs
                                    (mapcat (fn [b] (if (= "p" (first b)) (rest b) [b])) bs)))))
                  items)
        tag (cond (not (:ordered it0)) [:ul]
                  (= 1 (:start it0)) [:ol]
                  :else [:ol {:start (:start it0)}])]
    [(into tag lis) end]))

(defn- table-block [lines i ctx]
  (let [n (count lines)
        aligns (mapv (fn [c] (let [l (.startsWith c ":") r (.endsWith c ":")]
                               (cond (and l r) "center" r "right" l "left" :else nil)))
                     (cells (nth lines (inc i))))
        row (fn [tag l]
              (let [cs (cells l)]
                (into [:tr] (map-indexed (fn [k a]
                                           (into (if (some? a) [tag {:class (str "al-" a)}] [tag])
                                                 (inline (or (get cs k) "") ctx)))
                                         aligns))))
        end (loop [j (+ i 2)]
              (if (and (< j n) (let [l (nth lines j)]
                                 (not (or (blank? l) (.test heading-re l) (some? (fence-open l))
                                          (some? (item-of l))))))
                (recur (inc j))
                j))]
    [[:table [:thead (row :th (nth lines i))]
      (into [:tbody] (map (fn [j] (row :td (nth lines j))) (range (+ i 2) end)))]
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
              heading (.exec heading-re l)]
          (cond
            (blank? l) (recur (inc i) out)

            (some? fence)
            (let [end (loop [j (inc i)]
                        (if (or (>= j n) (fence-close? (nth lines j) (:mark fence))) j (recur (inc j))))
                  body (mapv (fn [ln] (subs ln (min (indent ln) (:indent fence))))
                             (.slice lines (inc i) end))]
              (recur (inc end) (conj out [:pre [:code (.join body "\n")]])))

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

            (.test def-re l) (recur (inc i) out)

            (table-start? lines i)
            (let [[t end] (table-block lines i ctx)] (recur end (conj out t)))

            (some? (item-of l))
            (let [[t end] (list-block lines i ctx)] (recur end (conj out t)))

            :else
            (let [end (loop [j (inc i)] (if (and (< j n) (not (interrupts? lines j))) (recur (inc j)) j))
                  text (.join (mapv (fn [ln] (.trim ln)) (.slice lines i end)) "\n")]
              (recur end (conj out (into [:p] (inline text ctx)))))))))))

(defn- defs-of
  "The link reference definitions in lines: label key → raw destination,
  the first definition of a label winning."
  [lines]
  (reduce (fn [m l]
            (when-let [r (.exec def-re l)]
              (let [k (label-key (aget r 1))]
                (when-not (.has m k) (.set m k (strip-angles (aget r 2))))))
            m)
          (js/Map.) lines))

(defn render
  "Hiccup blocks for markdown text. (link-fn link children) renders each
  link, link {:dest :label :kind}: :dest as mdlinks cleans a path (or a
  URL kept whole), :kind \"inline\", \"image\" or \"ref\"."
  [text link-fn]
  (let [lines (mapv (fn [l] (.replace l #"^[ \t]+" (fn [ws] (.replaceAll ws "\t" "    "))))
                    (.split (.replaceAll (str text) "\r\n" "\n") "\n"))]
    (blocks lines {:defs (defs-of lines) :link-fn link-fn})))
