(ns simpleviz.mdsource
  "Highlighting for the doc editor's text: markdown in, one vector of
  hiccup children per line out — plain strings and [:span {:class
  \"hl-<kind>\"} text], whose texts joined are the line again. The kinds:
  heading; mark for list, task and quote markers, emphasis delimiters,
  rules, fences and table pipes; code; link for link text and labels;
  url for link targets; comment and tag for HTML. A fence's body is
  highlight's, by its language. A small scanner, not the parser: the
  text lies under the textarea's, so it only colors, never moves, a
  character."
  (:require ["./mdparse.js" :as mp]
            [simpleviz.highlight :as hl]))

(defn- sticky [src] (js/RegExp. src "y"))

(defn- match-at [re s i]
  (set! (.-lastIndex re) i)
  (when-let [m (.exec re s)] (aget m 0)))

(defn- add!
  "Append s to line out, classed kind, or as plain text joined to the
  plain text before it."
  [out kind s]
  (when-not (= "" s)
    (if (some? kind)
      (.push out [:span {:class (str "hl-" kind)} s])
      (let [k (dec (.-length out))]
        (if (and (>= k 0) (string? (aget out k)))
          (aset out k (str (aget out k) s))
          (.push out s))))))

(def ^:private item-re (sticky "[ \\t]*(?:[-*+]|\\d{1,9}[.)])(?:[ \\t]+|$)"))
(def ^:private task-re (sticky "\\[[ xX]\\](?=[ \\t])"))
(def ^:private quote-re (sticky " {0,3}> ?"))
(def ^:private indent-re (sticky "[ \\t]*"))
(def ^:private autolink-re (sticky "<(?:https?|mailto):[^\\s<>]+>"))
(def ^:private tag-re (sticky "</?[A-Za-z][\\w-]*(?:\\s[^<>]*)?/?>"))
(def ^:private url-re (sticky "https?://[^\\s<>()\\[\\]]+"))
(def ^:private def-label-re (sticky " {0,3}\\[(?:[^\\]\\\\]|\\\\.)+\\]:"))
(def ^:private word-re (js/RegExp. "[\\p{L}\\p{N}]" "u"))

(defn- word? [c] (and (not= "" c) (.test word-re c)))
(defn- space? [c] (or (= "" c) (.test #"\s" c)))

(defn- run-end
  "The end of the run of character c starting at i in s."
  [s i c]
  (loop [j i] (if (= c (.charAt s j)) (recur (inc j)) j)))

(defn- closers
  "For each [ and ( of s, the index of the bracket closing it, past
  escapes and nested pairs of its kind, else -1 — in one pass, so a line
  of brackets that never close stays linear."
  [s]
  (let [n (count s)
        out (.fill (js/Int32Array. n) -1)
        squares #js []
        parens #js []
        close! (fn [opens j] (when (pos? (.-length opens)) (aset out (.pop opens) j)))]
    (loop [j 0]
      (when (< j n)
        (let [ch (.charAt s j)]
          (if (= ch "\\")
            (recur (+ j 2))
            (do (case ch
                  "[" (.push squares j)
                  "(" (.push parens j)
                  "]" (close! squares j)
                  ")" (close! parens j)
                  nil)
                (recur (inc j)))))))
    out))

(defn- link-at
  "The link or image at i — [text](dest), [text][ref] or a note [^label]
  — as [tokens end], or nil when none is there."
  [s cl i]
  (let [open (if (= "!" (.charAt s i)) 2 1)
        close (aget cl (+ i open -1))]
    (when (pos? close)
      (let [dest-end (if (#{"(" "["} (.charAt s (inc close))) (aget cl (inc close)) -1)]
        (cond (pos? dest-end)
              [[["mark" (subs s i (+ i open))] ["link" (subs s (+ i open) close)] ["mark" "]"]
                ["url" (subs s (inc close) (inc dest-end))]]
               (inc dest-end)]
              (and (= open 1) (= "^" (.charAt s (inc i))))
              [[["link" (subs s i (inc close))]] (inc close)])))))

(defn- delim?
  "Is the run of * or _ from i to j an emphasis delimiter: not spaced on
  both sides, and a _ not inside a word?"
  [s i j]
  (let [before (if (pos? i) (.charAt s (dec i)) "")
        after (.charAt s j)]
    (and (not (and (space? before) (space? after)))
         (or (not= "_" (.charAt s i)) (not (and (word? before) (word? after)))))))

(defn- code-end
  "The end of the code span whose opening backticks run from i to j, or
  nil: the next run of as many backticks."
  [s i j]
  (let [mark (subs s i j)]
    (loop [k (.indexOf s mark j)]
      (cond (neg? k) nil
            (= "`" (.charAt s (+ k (count mark)))) (recur (.indexOf s mark (run-end s k "`")))
            :else (+ k (count mark))))))

(defn- token-at
  "The token at i of s, not in an HTML comment: [tokens end], tokens nil
  for plain text up to end. cl is s's closers; table? makes pipes marks."
  [s cl i table?]
  (let [c (.charAt s i)
        one (fn [kind end] [[[kind (subs s i end)]] end])]
    (cond
      (= c "\\") [nil (min (count s) (+ i 2))]
      (= c "`") (let [j (run-end s i "`")]
                  (if-let [e (code-end s i j)] (one "code" e) [nil j]))
      (= c "<") (if-let [m (match-at autolink-re s i)]
                  (one "url" (+ i (count m)))
                  (if-let [m (match-at tag-re s i)] (one "tag" (+ i (count m))) [nil (inc i)]))
      (or (= c "[") (and (= c "!") (= "[" (.charAt s (inc i)))))
      (or (link-at s cl i) [nil (inc i)])
      (and (= c "h") (not (word? (if (pos? i) (.charAt s (dec i)) ""))) (match-at url-re s i))
      (one "url" (+ i (count (match-at url-re s i))))
      (or (= c "*") (= c "_"))
      (let [j (run-end s i c)] (if (delim? s i j) (one "mark" j) [nil j]))
      (and (= c "~") (= "~" (.charAt s (inc i)))) (one "mark" (run-end s i "~"))
      (and table? (= c "|")) (one "mark" (inc i))
      :else [nil (inc i)])))

(defn- inline!
  "Add the inline tokens of s from i to out; comment? says an HTML
  comment is open. Returns whether one still is."
  [out s i table? comment?]
  (let [n (count s)
        cl (closers s)]
    (loop [i i comment? comment? plain i]
      (cond
        (>= i n) (do (add! out nil (subs s plain i)) comment?)
        (or comment? (.startsWith s "<!--" i))
        (let [e (.indexOf s "-->" (if comment? i (+ i 4)))
              end (if (neg? e) n (+ e 3))]
          (add! out nil (subs s plain i))
          (add! out "comment" (subs s i end))
          (recur end (neg? e) end))
        :else
        (let [[toks end] (token-at s cl i table?)]
          (if (some? toks)
            (do (add! out nil (subs s plain i))
                (doseq [[kind t] toks] (add! out kind t))
                (recur end false end))
            (recur end false plain)))))))

(defn- prefix!
  "Add the quote, list and task markers at the start of line l to out;
  the index after them."
  [out l]
  (loop [i 0]
    (if-let [m (match-at quote-re l i)]
      (do (add! out "mark" m) (recur (+ i (count m))))
      (if-let [m (match-at item-re l i)]
        (let [ind (match-at indent-re l i)
              e (+ i (count m))]
          (add! out nil ind)
          (add! out "mark" (subs m (count ind)))
          (if-let [t (match-at task-re l e)]
            (do (add! out "mark" t) (+ e (count t)))
            (recur e)))
        i))))

(defn- fence-lines
  "The lines of a fence's body in language lang, each its tokens."
  [lang body]
  (let [toks (when (seq body) (hl/highlight lang (.join body "\n")))]
    (if (nil? toks)
      (mapv (fn [l] (let [o #js []] (add! o "code" l) o)) body)
      (let [out #js [#js []]]
        (doseq [t toks]
          (let [kind (when-not (string? t) (.slice (:class (nth t 1)) 3))
                parts (.split (if (string? t) t (nth t 2)) "\n")]
            (dotimes [k (count parts)]
              (when (pos? k) (.push out #js []))
              (add! (aget out (dec (.-length out))) kind (nth parts k)))))
        (vec out)))))

(defn lines
  "Each line of text, as hiccup children."
  [text]
  (let [ls (.split text "\n")
        n (count ls)
        out #js []]
    (loop [i 0 table-end -1 comment? false]
      (when (< i n)
        (let [l (nth ls i)
              ;; the reader shows <!-- as text: an unclosed one colors
              ;; up to its paragraph's end, not the rest of the doc
              comment? (and comment? (not (mp/blank l)))
              o #js []
              f (when-not comment? (mp/fenceOpen l))]
          (cond
            (some? f)
            (let [close (loop [j (inc i)]
                          (cond (>= j n) j
                                (mp/fenceClose (nth ls j) (.-mark f)) j
                                :else (recur (inc j))))]
              (add! o "mark" l)
              (.push out o)
              (doseq [b (fence-lines (.-lang f) (.slice ls (inc i) close))] (.push out b))
              (when (< close n)
                (let [c #js []] (add! c "mark" (nth ls close)) (.push out c)))
              (recur (inc close) table-end false))

            (and (not comment?) (.test mp/headingRe l))
            (do (add! o "heading" l) (.push out o) (recur (inc i) table-end false))

            (and (not comment?) (or (mp/isHr l) (.test mp/setextRe l)))
            (do (add! o "mark" l) (.push out o) (recur (inc i) table-end false))

            :else
            (let [table-end (if (and (not comment?) (> i table-end) (mp/tableStart ls i))
                              (mp/tableEnd ls i)
                              table-end)
                  table? (< i table-end)
                  sep? (and table? (> i 0) (mp/tableStart ls (dec i)))
                  label (when-not comment? (match-at def-label-re l 0))]
              (cond
                sep? (do (add! o "mark" l) (.push out o) (recur (inc i) table-end false))
                (and label (or (.test mp/defRe l) (.test mp/noteDefRe l)))
                (do (add! o "link" label)
                    (add! o (when (.test mp/defRe l) "url") (subs l (count label)))
                    (.push out o)
                    (recur (inc i) table-end false))
                :else
                (let [start (if comment? 0 (prefix! o l))
                      comment? (inline! o l start table? comment?)]
                  (.push out o)
                  (recur (inc i) table-end comment?))))))))
    (vec out)))
