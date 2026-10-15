(ns simpleviz.vim
  "A vi mode for the md editor's textarea, as a pure state machine: the
  textarea holds the text and the caret, `press` takes one key and says
  what the textarea holds next. Insert mode leaves keys to the textarea;
  normal and visual mode take every key they know.")

(defn init []
  {:mode "normal" :keys [] :reg {:text "" :line false} :undo [] :redo []})

;; ---- lines and characters ----

(defn line-start [t p]
  (if (pos? p) (inc (.lastIndexOf t "\n" (dec p))) 0))

(defn- line-end
  "The index of the \\n that ends p's line, or the text's length."
  [t p]
  (let [i (.indexOf t "\n" p)] (if (neg? i) (.-length t) i)))

(defn- blank? [ch] (or (= ch " ") (= ch "\t")))

(defn- first-nonblank [t p]
  (let [e (line-end t p)]
    (loop [i (line-start t p)]
      (if (and (< i e) (blank? (.charAt t i))) (recur (inc i)) i))))

(defn- clamp
  "p as a normal-mode caret: on a character of its line, never on the \\n
  that ends it — except on an empty line."
  [t p]
  (let [p (max 0 (min p (.-length t)))
        s (line-start t p)
        e (line-end t p)]
    (if (> e s) (min p (dec e)) s)))

(defn- line-of [t p]
  (loop [i 0 n 0]
    (let [j (.indexOf t "\n" i)]
      (if (or (neg? j) (>= j p)) n (recur (inc j) (inc n))))))

(defn- start-of-line
  "Where line n starts (0-based), the last line's start past the end."
  [t n]
  (loop [i 0 k 0]
    (if (= k n)
      i
      (let [j (.indexOf t "\n" i)]
        (if (neg? j) i (recur (inc j) (inc k)))))))

(defn- last-line [t] (line-of t (inc (.-length t))))

(defn- col-of [t p] (- p (line-start t p)))

(defn- at-col [t s col]
  (let [e (line-end t s)] (if (> e s) (min (+ s col) (dec e)) s)))

(def ^:private word-char (js/RegExp. "[\\p{L}\\p{N}_]" "u"))

(defn- cls
  "0 blank, 1 word (letters in any script, digits, _), 2 punctuation;
  with big, every non-blank is 1."
  [ch big]
  (cond (or (= ch "") (.test #"\s" ch)) 0
        big 1
        (.test word-char ch) 1
        :else 2))

(defn- empty-line? [t i]
  (and (= "\n" (.charAt t i)) (or (zero? i) (= "\n" (.charAt t (dec i))))))

;; ---- motions: each gives the target, or nil when it can't move ----

(defn- word-fwd [t p big]
  (let [n (.-length t)
        c (cls (.charAt t p) big)
        i (if (zero? c) p (loop [i p] (if (and (< i n) (= c (cls (.charAt t i) big))) (recur (inc i)) i)))]
    (loop [i i]
      (cond (>= i n) n
            (pos? (cls (.charAt t i) big)) i
            (and (not= i p) (empty-line? t i)) i
            :else (recur (inc i))))))

(defn- word-end [t p big]
  (let [n (.-length t)
        i (loop [i (inc p)] (if (and (< i n) (zero? (cls (.charAt t i) big))) (recur (inc i)) i))]
    (when (< i n)
      (let [c (cls (.charAt t i) big)]
        (loop [i i] (if (and (< (inc i) n) (= c (cls (.charAt t (inc i)) big))) (recur (inc i)) i))))))

(defn- word-back [t p big]
  (when (pos? p)
    (let [i (loop [i (dec p)]
              (cond (<= i 0) 0
                    (empty-line? t i) i
                    (zero? (cls (.charAt t i) big)) (recur (dec i))
                    :else i))]
      (if (empty-line? t i)
        i
        (let [c (cls (.charAt t i) big)]
          (loop [i i] (if (and (pos? i) (= c (cls (.charAt t (dec i)) big))) (recur (dec i)) i)))))))

(defn- repeat-step
  "Step f count times from p; nil when the first step can't move."
  [f p n]
  (loop [p p k 0]
    (if (= k n)
      p
      (let [q (f p)]
        (if (or (nil? q) (= q p)) (when (pos? k) p) (recur q (inc k)))))))

(defn- find-char
  "The k-th ch after p (f t) or before it (F T) on p's line, as f/t/F/T
  land; nil without one. again: a ; or , repeat, which a t next to its
  char must not get stuck on."
  [t p kind ch n again]
  (let [s (line-start t p)
        e (line-end t p)
        fwd (or (= kind "f") (= kind "t"))
        from (if (and again (or (= kind "t") (= kind "T"))) (if fwd (inc p) (dec p)) p)
        idx (loop [q from k 0]
              (if (= k n)
                q
                (let [i (if fwd (.indexOf t ch (inc q)) (if (pos? q) (.lastIndexOf t ch (dec q)) -1))]
                  (when (and (>= i s) (< i e) (not (neg? i))) (recur i (inc k))))))]
    (when (some? idx)
      (case kind "f" idx "F" idx "t" (dec idx) "T" (inc idx)))))

(defn- para-fwd
  "The next empty line after a paragraph, or the text's end."
  [t p]
  (let [n (.-length t)]
    (loop [s (line-start t p) seen (not= (line-start t p) (line-end t p))]
      (let [e (line-end t s)]
        (if (>= e n)
          n
          (let [s2 (inc e) empty (= s2 (line-end t s2))]
            (if (and empty seen) s2 (recur s2 (or seen (not empty))))))))))

(defn- para-back
  "The empty line before a paragraph, or the text's start."
  [t p]
  (loop [s (line-start t p) seen (not= (line-start t p) (line-end t p))]
    (if (zero? s)
      0
      (let [s2 (line-start t (dec s)) empty (= s2 (line-end t s2))]
        (if (and empty seen) s2 (recur s2 (or seen (not empty))))))))

(defn- search [t p pat back]
  (when (seq pat)
    (let [i (if back
              (let [j (if (pos? p) (.lastIndexOf t pat (dec p)) -1)]
                (if (neg? j) (.lastIndexOf t pat) j))
              (let [j (.indexOf t pat (inc p))]
                (if (neg? j) (.indexOf t pat) j)))]
      (when-not (neg? i) i))))

(defn- line-motion [t p n]
  (let [l (max 0 (min (last-line t) (+ (line-of t p) n)))]
    (when-not (= l (line-of t p))
      {:pos (first-nonblank t (start-of-line t l)) :line true})))

(defn- motion
  "Where motion m (a map {:k .. :ch ..}) moves the caret p, n times:
  {:pos .. :line linewise? :incl inclusive? :col sticky column}, or nil."
  [vs t p m n]
  (let [cnt (or n 1)
        s (line-start t p)
        e (line-end t p)
        k (:k m)
        jk (fn [d]
             (let [l (max 0 (min (last-line t) (+ (line-of t p) d)))]
               (when-not (= l (line-of t p))
                 (let [col (or (:col vs) (col-of t p))]
                   {:pos (at-col t (start-of-line t l) col) :line true :col col}))))
        word (fn [f] (when-let [q (repeat-step f p cnt)] {:pos q}))]
    (case k
      ("h" "Backspace") (let [q (max s (- p cnt))] (when-not (= q p) {:pos q}))
      ("l" " ") (let [q (min e (+ p cnt))] (when-not (= q p) {:pos q}))
      "j" (jk cnt)
      "k" (jk (- cnt))
      "w" (word #(word-fwd t % false))
      "W" (word #(word-fwd t % true))
      "b" (word #(word-back t % false))
      "B" (word #(word-back t % true))
      "e" (when-let [r (word #(word-end t % false))] (assoc r :incl true))
      "E" (when-let [r (word #(word-end t % true))] (assoc r :incl true))
      "0" {:pos s}
      "^" {:pos (first-nonblank t p)}
      "$" (let [ls (start-of-line t (+ (line-of t p) (dec cnt)))
                le (line-end t ls)]
            {:pos (max ls (dec le)) :incl (> le ls) :col 1e9})
      "gg" {:pos (first-nonblank t (start-of-line t (if (some? n) (dec n) 0))) :line true}
      "G" {:pos (first-nonblank t (start-of-line t (if (some? n) (dec n) (last-line t)))) :line true}
      ("Enter" "+") (line-motion t p cnt)
      "-" (line-motion t p (- cnt))
      "}" {:pos (or (repeat-step #(para-fwd t %) p cnt) p)}
      "{" {:pos (or (repeat-step #(para-back t %) p cnt) 0)}
      ("f" "t" "F" "T") (when-let [q (find-char t p k (:ch m) cnt false)]
                          {:pos q :incl (or (= k "f") (= k "t")) :find {:k k :ch (:ch m)}})
      ("n" "N" ";" ",")
      (let [again (= k ";") flip (or (= k "N") (= k ","))]
        (if (or (= k "n") (= k "N"))
          (when-let [{:keys [pat back]} (:search vs)]
            (when-let [q (repeat-step #(search t % pat (if flip (not back) back)) p cnt)]
              {:pos q}))
          (when-let [{fk :k ch :ch} (:find vs)]
            (let [kind (if flip (get {"f" "F" "F" "f" "t" "T" "T" "t"} fk) fk)]
              (when-let [q (find-char t p kind ch cnt (or again flip))]
                {:pos q :incl (or (= kind "f") (= kind "t"))})))))
      nil)))

;; ---- text objects: [from to) or nil ----

(defn- run-around
  "The run of chars around p that pred accepts, within p's line."
  [t p pred]
  (let [s (line-start t p) e (line-end t p)]
    [(loop [i p] (if (and (> i s) (pred (.charAt t (dec i)))) (recur (dec i)) i))
     (loop [i p] (if (and (< i e) (pred (.charAt t i))) (recur (inc i)) i))]))

(defn- word-object [t p around big]
  (let [ch (.charAt t p)]
    (when-not (or (= ch "") (= ch "\n"))
      (let [c (cls ch big)
            same (fn [x] (if (zero? c) (blank? x) (and (not= x "\n") (= c (cls x big)))))
            [a b] (run-around t p same)]
        (if-not around
          [a b]
          (if (zero? c)
            (let [c2 (cls (.charAt t b) big)]
              [a (if (and (< b (line-end t p)) (pos? c2))
                   (second (run-around t b #(and (not= % "\n") (= c2 (cls % big)))))
                   b)])
            (let [[_ b2] (run-around t b blank?)]
              (if (> b2 b)
                [a b2]
                [(first (run-around t a blank?)) b]))))))))

(defn- quote-object [t p q around]
  (let [s (line-start t p) e (line-end t p)
        qs (loop [i (.indexOf t q s) acc []]
             (if (or (neg? i) (>= i e)) acc (recur (.indexOf t q (inc i)) (conj acc i))))
        pairs (partition 2 qs)
        [a b] (or (first (filter (fn [[a b]] (and (<= a p) (<= p b))) pairs))
                  (first (filter (fn [[a _]] (> a p)) pairs)))]
    (when (some? a)
      (if around
        (let [[_ b2] (run-around t (inc b) blank?)]
          (if (> b2 (inc b)) [a b2] [(first (run-around t a blank?)) (inc b)]))
        [(inc a) b]))))

(defn- bracket-object [t p open close around]
  (let [n (.-length t)
        ch (.charAt t p)
        o (if (= ch open)
            p
            (loop [i (if (= ch close) (dec p) p) d 0]
              (when (>= i 0)
                (let [c (.charAt t i)]
                  (cond (= c close) (recur (dec i) (inc d))
                        (and (= c open) (zero? d)) i
                        (= c open) (recur (dec i) (dec d))
                        :else (recur (dec i) d))))))
        c (when (some? o)
            (loop [i (inc o) d 0]
              (when (< i n)
                (let [x (.charAt t i)]
                  (cond (= x open) (recur (inc i) (inc d))
                        (and (= x close) (zero? d)) i
                        (= x close) (recur (inc i) (dec d))
                        :else (recur (inc i) d))))))]
    (when (some? c)
      (if around [o (inc c)] [(inc o) c]))))

(defn- text-object [t p obj around]
  (case obj
    "w" (word-object t p around false)
    "W" (word-object t p around true)
    ("\"" "'" "`") (quote-object t p obj around)
    ("(" ")" "b") (bracket-object t p "(" ")" around)
    ("[" "]") (bracket-object t p "[" "]" around)
    ("{" "}" "B") (bracket-object t p "{" "}" around)
    ("<" ">") (bracket-object t p "<" ">" around)
    nil))

;; ---- parsing the keys of a command ----

(def ^:private motion-keys
  #{"h" "j" "k" "l" " " "Backspace" "w" "b" "e" "W" "B" "E" "0" "^" "$" "G"
    "{" "}" "n" "N" ";" "," "Enter" "+" "-"})

(defn- digit? [k] (.test #"^[0-9]$" k))

(defn- parse-count [ks]
  (let [i (loop [i 0]
            (if (and (< i (count ks)) (digit? (nth ks i)) (or (pos? i) (not= "0" (nth ks i))))
              (recur (inc i)) i))]
    [(when (pos? i) (min 9999 (js/parseInt (apply str (take i ks)) 10)))
     (vec (drop i ks))]))

(defn- parse-motion
  "\"more\" while keys are missing, nil for no motion, else the motion map."
  [ks]
  (let [k (first ks)]
    (cond (empty? ks) "more"
          (contains? motion-keys k) {:k k}
          (contains? #{"f" "t" "F" "T"} k) (cond (< (count ks) 2) "more"
                                                (= 1 (.-length (second ks))) {:k k :ch (second ks)})
          (= k "g") (cond (< (count ks) 2) "more"
                          (= "g" (second ks)) {:k "gg"}))))

(defn- times [a b] (when (or (some? a) (some? b)) (* (or a 1) (or b 1))))

(def ^:private simple-keys
  #{"x" "X" "D" "C" "Y" "s" "S" "J" "p" "P" "o" "O" "i" "a" "I" "A" "~"
    "u" "C-r" "." "v" "V" ":" "/" "?"})

(defn- parse
  "The command the pending keys spell: \"more\", nil (none) or a map."
  [ks visual]
  (let [[c1 r] (parse-count ks)
        k (first r)]
    (cond
      (empty? r) "more"
      (and (contains? #{"d" "c" "y"} k) (not visual))
      (let [[c2 r2] (parse-count (vec (rest r)))
            k2 (first r2)
            n (times c1 c2)]
        (cond (empty? r2) "more"
              (= k2 k) {:op k :lines true :n n}
              (contains? #{"i" "a"} k2) (if (< (count r2) 2) "more" {:op k :obj (second r2) :around (= k2 "a")})
              :else (let [m (parse-motion r2)]
                      (if (map? m) {:op k :motion m :n n} m))))
      (and visual (contains? #{"i" "a"} k))
      (if (< (count r) 2) "more" {:obj (second r) :around (= k "a")})
      (= k "r") (cond (< (count r) 2) "more"
                      (= 1 (.-length (second r))) {:cmd "r" :ch (second r) :n c1})
      (contains? simple-keys k) {:cmd k :n c1}
      (and visual (contains? #{"d" "c" "y" "o" "Escape"} k)) {:cmd k :n c1}
      :else (let [m (parse-motion r)]
              (if (map? m) {:motion m :n c1} m)))))

;; ---- changes ----

(defn- push-undo [vs t p]
  (let [u (conj (:undo vs) {:text t :pos p})]
    (assoc vs :undo (if (> (count u) 500) (vec (rest u)) u) :redo [])))

(defn- splice [t a b s] (str (subs t 0 a) s (subs t b)))

(defn- line-range
  "[start end) of the whole lines from p's line to q's, with the \\n
  that follows them — or, at the text's end, the one before."
  [t p q]
  (let [ls (line-start t (min p q))
        le (line-end t (max p q))
        n (.-length t)]
    (cond (< le n) [ls (inc le)]
          (pos? ls) [(dec ls) le]
          :else [ls le])))

(defn- indent-of [t p]
  (subs t (line-start t p) (first-nonblank t p)))

(defn- leave-insert
  "Esc in insert mode: one undo step for the command and its typing, and
  the typed text kept for the dot."
  [vs t p]
  (let [{:keys [undo text dot at rep]} (or (:ins vs) {:undo {:text t :pos p} :text t :at p})
        ;; the typing starts where insert mode did, unless it deleted
        ;; back past that: " big" typed after "the" is not "big " after "the "
        a (loop [i 0] (if (and (< i at) (< i (.-length text)) (< i (.-length t)) (= (.charAt text i) (.charAt t i))) (recur (inc i)) i))
        b (loop [j 0] (if (and (< j (- (.-length text) a)) (< j (- (.-length t) a))
                               (= (.charAt text (- (.-length text) j 1)) (.charAt t (- (.-length t) j 1))))
                        (recur (inc j)) j))
        z (- (.-length t) b)
        typed (subs t a z)
        ;; a count on i a I A o O types it again, o O on new lines —
        ;; not after a click broke the insert, nor with the caret moved
        ;; off the typing's end (an arrow key)
        more (if (and rep (= p z))
               (.repeat (str (:sep rep) typed) (dec (:n rep)))
               "")
        t (splice t p p more)
        p (+ p (.-length more))
        vs (cond-> (dissoc vs :ins)
             (not= t (:text undo)) (push-undo (:text undo) (:pos undo))
             (some? dot) (assoc :dot {:keys dot :ins typed}))]
    {:vs (assoc vs :mode "normal")
     :text t
     :pos (if (> p (line-start t p)) (dec p) p)}))

(defn- enter-insert
  "Insert mode at p in t, which a command may have changed from t0/p0."
  [vs t0 p0 t p dot]
  {:vs (assoc vs :mode "insert" :ins {:undo {:text t0 :pos p0} :text t :at p :dot dot})
   :text t :pos p})

(defn- operate
  "Operator op over [a b) of t, or with lines over the whole lines from
  a's to b's; the caret at p."
  [vs t p op a b lines dot]
  (let [ls (line-start t a)
        le (line-end t b)
        [da db] (if lines (line-range t a b) [a b])
        vs (assoc vs :reg (if lines
                            {:text (str (subs t ls le) "\n") :line true}
                            {:text (subs t a b) :line false}))]
    (case op
      "y" {:vs vs :text t
           :pos (cond (not lines) a
                      (< ls (line-start t p)) (clamp t a)
                      :else p)}
      "d" (if (= da db)
            {:vs vs :text t :pos (clamp t p)}
            (let [t2 (splice t da db "")]
              {:vs (push-undo vs t p)
               :text t2
               :pos (cond (not lines) (clamp t2 a)
                          (< da ls) (first-nonblank t2 (line-start t2 da))
                          :else (first-nonblank t2 (min ls (.-length t2))))}))
      "c" (if lines
            (let [ind (indent-of t ls)]
              (enter-insert vs t p (splice t ls le ind) (+ ls (.-length ind)) dot))
            (enter-insert vs t p (splice t a b "") a dot)))))

(defn- toggle-case [s]
  (.join (.map (.split s "") (fn [c] (if (= c (.toUpperCase c)) (.toLowerCase c) (.toUpperCase c)))) ""))

(defn- join-lines
  "Join n lines from p's (at least two): each next line loses its
  indent, and a space joins them unless one of the two is empty.
  [text caret], nil on the last line."
  [t p n]
  (loop [t t k 1 at nil]
    (let [e (line-end t p)]
      (if (or (>= k (max 2 n)) (>= e (.-length t)))
        (when (some? at) [t at])
        (let [fb (first-nonblank t (inc e))
              sep (if (or (= fb (line-end t (inc e))) (= e (line-start t p))) "" " ")]
          (recur (splice t e fb sep) (inc k)
                 (if (= sep "") (max (line-start t p) (dec e)) e)))))))

(defn- paste [vs t p before n]
  (let [{:keys [text line]} (:reg vs)
        s (.repeat text (or n 1))
        e (line-end t p)
        put (fn [t2 at] {:vs (push-undo vs t p) :text t2 :pos at})]
    (cond
      (= "" text) nil
      (and line before) (let [at (line-start t p)
                              t2 (splice t at at s)]
                          (put t2 (first-nonblank t2 at)))
      (and line (< e (.-length t))) (let [t2 (splice t (inc e) (inc e) s)]
                                      (put t2 (first-nonblank t2 (inc e))))
      ;; after the last line: a \n before the lines, not after
      line (let [t2 (str t "\n" (subs s 0 (dec (.-length s))))]
             (put t2 (first-nonblank t2 (inc e))))
      :else (let [at (if (or before (= p e)) p (inc p))]
              (put (splice t at at s) (+ at (.-length s) -1))))))

;; ---- running a command ----

(declare press)

(defn- move [vs t r]
  (let [q (if (:line r) (:pos r) (clamp t (:pos r)))]
    {:vs (cond-> (assoc vs :col (if (some? (:col r)) (:col r) (col-of t q)))
           (some? (:find r)) (assoc :find (:find r)))
     :text t :pos q}))

(defn- dot-repeat [vs t p n]
  (if-let [{:keys [keys ins]} (:dot vs)]
    (let [undo (:undo vs)
          r (reduce (fn [r _]
                      (let [r (reduce (fn [r k] (press (:vs r) r k)) r keys)]
                        (if (= "insert" (:mode (:vs r)))
                          (let [t2 (splice (:text r) (:pos r) (:pos r) ins)]
                            (press (:vs r) {:text t2 :pos (+ (:pos r) (.-length ins))} "Escape"))
                          r)))
                    {:vs (assoc vs :keys []) :text t :pos p}
                    (range (or n 1)))
          new-undo (:undo (:vs r))]
      (assoc r :vs (assoc (:vs r)
                          :dot (:dot vs)
                          :undo (if (> (count new-undo) (count undo)) (conj undo (nth new-undo (count undo))) new-undo))))
    {:vs vs :text t :pos p}))

(defn- undo-step [vs t p from to n]
  (loop [vs vs t t p p k 0]
    (let [stack (get vs from)]
      (if (or (= k (or n 1)) (empty? stack))
        {:vs (cond-> vs (zero? k) (assoc :msg (if (= from :undo) "Already at oldest change" "Already at newest change")))
         :text t :pos (clamp t p)}
        (let [snap (peek stack)]
          (recur (assoc vs from (pop stack) to (conj (get vs to) {:text t :pos p}))
                 (:text snap) (:pos snap) (inc k)))))))

(defn- run-normal [vs t p c keys]
  (let [n (:n c)
        cnt (or n 1)
        s (line-start t p)
        e (line-end t p)
        op (fn [o a b lines] (operate vs t p o a b lines (when-not (= o "y") keys)))
        ;; i a I A o O: typed cnt times on Esc, each after sep
        ins (fn [t2 at sep] (assoc-in (enter-insert vs t p t2 at keys) [:vs :ins :rep] {:n cnt :sep sep}))
        changed (fn [r] (if (and (some? r) (not= (:text r) t))
                          (update r :vs assoc :dot {:keys keys})
                          r))]
    (cond
      (some? (:op c))
      (let [o (:op c)]
        (cond
          (:lines c) (let [l (min (last-line t) (+ (line-of t p) (dec cnt)))
                           r (op o p (start-of-line t l) true)]
                       (if (= o "d") (changed r) r))
          (some? (:obj c)) (if-let [[a b] (text-object t p (:obj c) (:around c))]
                             (let [r (op o a b false)] (if (= o "d") (changed r) r))
                             {:vs vs :text t :pos p})
          :else
          (let [m (:motion c)
                big (= "W" (:k m))
                ;; cw on a word changes to its end, like ce but not past it
                cw (and (= o "c") (contains? #{"w" "W"} (:k m)) (pos? (cls (.charAt t p) big)))
                r (if cw
                    (when-let [q (repeat-step #(word-end t % big) (dec p) cnt)] {:pos q :incl true})
                    (motion vs t p m n))]
            (if (nil? r)
              {:vs vs :text t :pos p}
              (let [q (:pos r)
                    ;; dw on a line's last word stops at the line's end
                    q (if (and (contains? #{"w" "W"} (:k m)) (> q (line-end t p)) (<= q (first-nonblank t q)))
                        (line-end t (dec (line-start t q)))
                        q)
                    res (if (:line r)
                          (op o (min p q) (max p q) true)
                          (op o (min p q) (min (.-length t) (+ (max p q) (if (:incl r) 1 0))) false))]
                (cond-> res
                  (some? (:find r)) (update :vs assoc :find (:find r))
                  (= o "d") changed))))))

      (some? (:motion c))
      (if-let [r (motion vs t p (:motion c) (:n c))] (move vs t r) {:vs vs :text t :pos p})

      :else
      (case (:cmd c)
        "x" (if (< p e) (changed (op "d" p (min e (+ p cnt)) false)) {:vs vs :text t :pos p})
        "X" (if (> p s) (changed (op "d" (max s (- p cnt)) p false)) {:vs vs :text t :pos p})
        "D" (changed (op "d" p (line-end t (start-of-line t (+ (line-of t p) (dec cnt)))) false))
        "C" (op "c" p (line-end t (start-of-line t (+ (line-of t p) (dec cnt)))) false)
        "s" (op "c" p (min e (+ p cnt)) false)
        "S" (op "c" p (start-of-line t (min (last-line t) (+ (line-of t p) (dec cnt)))) true)
        "Y" (op "y" p (start-of-line t (min (last-line t) (+ (line-of t p) (dec cnt)))) true)
        "r" (if (<= (+ p cnt) e)
              (changed {:vs (push-undo vs t p) :text (splice t p (+ p cnt) (.repeat (:ch c) cnt)) :pos (+ p cnt -1)})
              {:vs vs :text t :pos p})
        "J" (if-let [[t2 at] (join-lines t p cnt)]
              (changed {:vs (push-undo vs t p) :text t2 :pos at})
              {:vs vs :text t :pos p})
        "~" (let [b (min e (+ p cnt))]
              (if (< p b)
                (changed {:vs (push-undo vs t p) :text (splice t p b (toggle-case (subs t p b))) :pos (clamp t b)})
                {:vs vs :text t :pos p}))
        ("p" "P") (or (changed (paste vs t p (= "P" (:cmd c)) n)) {:vs vs :text t :pos p})
        "i" (ins t p "")
        "a" (ins t (if (< p e) (inc p) p) "")
        "I" (ins t (first-nonblank t p) "")
        "A" (ins t e "")
        "o" (let [nl (str "\n" (indent-of t p))]
              (ins (splice t e e nl) (+ e (.-length nl)) nl))
        "O" (let [ind (indent-of t p)]
              (ins (splice t s s (str ind "\n")) (+ s (.-length ind)) (str "\n" ind)))
        "u" (undo-step vs t p :undo :redo n)
        "C-r" (undo-step vs t p :redo :undo n)
        "." (dot-repeat vs t p n)
        "v" {:vs (assoc vs :mode "visual" :anchor p) :text t :pos p}
        "V" {:vs (assoc vs :mode "vline" :anchor p) :text t :pos p}
        (":" "/" "?") {:vs (assoc vs :mode "cmd" :cmd {:kind (:cmd c) :text ""}) :text t :pos p}
        {:vs vs :text t :pos p}))))

(defn- run-visual [vs t p c]
  (let [anchor (:anchor vs)
        lines (= "vline" (:mode vs))
        a (min anchor p)
        z (max anchor p)
        b (min (.-length t) (inc z))
        done (fn [r] (update r :vs #(-> % (assoc :mode (if (= "insert" (:mode %)) "insert" "normal")) (dissoc :anchor))))
        op (fn [o lines] (done (operate vs t p o a (if lines z b) lines nil)))
        [ra rb] (if lines [(line-start t a) (line-end t z)] [a b])]
    (cond
      (some? (:motion c))
      (if-let [r (motion vs t p (:motion c) (:n c))] (move vs t r) {:vs vs :text t :pos p})

      (some? (:obj c))
      (if-let [[x y] (text-object t p (:obj c) (:around c))]
        (if (> y x)
          {:vs (assoc vs :mode "visual" :anchor (min x anchor)) :text t :pos (max (dec y) p)}
          {:vs vs :text t :pos p})
        {:vs vs :text t :pos p})

      :else
      (case (:cmd c)
        ("d" "x") (op "d" lines)
        ("c" "s") (op "c" lines)
        "y" (op "y" lines)
        ("D" "X") (op "d" true)
        "Y" (op "y" true)
        ("C" "S") (op "c" true)
        "~" (done {:vs (push-undo vs t p) :text (splice t ra rb (toggle-case (subs t ra rb))) :pos (clamp t ra)})
        "J" (if-let [[t2 at] (join-lines t a (inc (- (line-of t z) (line-of t a))))]
              (done {:vs (push-undo vs t p) :text t2 :pos at})
              (done {:vs vs :text t :pos p}))
        ("p" "P") (if (= "" (:text (:reg vs)))
                    (done {:vs (assoc vs :msg "Nothing in register") :text t :pos p})
                  (let [{:keys [text line]} (:reg vs)
                        s (cond (and line lines) (subs text 0 (dec (.-length text)))
                                line (str "\n" text)
                                :else text)
                        t2 (splice t ra rb s)]
                    (done {:vs (assoc (push-undo vs t p) :reg (if lines
                                                               {:text (str (subs t ra rb) "\n") :line true}
                                                               {:text (subs t ra rb) :line false}))
                           :text t2 :pos (clamp t2 ra)})))
        ;; a search moves the head, the selection kept; : leaves it
        ("/" "?") {:vs (assoc vs :mode "cmd" :cmd {:kind (:cmd c) :text "" :visual (:mode vs)}) :text t :pos p}
        ":" {:vs (-> vs (assoc :mode "cmd" :cmd {:kind ":" :text ""}) (dissoc :anchor :head)) :text t :pos p}
        "o" {:vs (assoc vs :anchor p) :text t :pos anchor}
        "v" (if lines {:vs (assoc vs :mode "visual") :text t :pos p} (done {:vs vs :text t :pos p}))
        "V" (if lines (done {:vs vs :text t :pos p}) {:vs (assoc vs :mode "vline") :text t :pos p})
        "Escape" (done {:vs vs :text t :pos p})
        {:vs vs :text t :pos p}))))

(defn- leave-cmd
  "Out of the command line, back to the mode it was typed in."
  [vs]
  (assoc (dissoc vs :cmd) :mode (or (:visual (:cmd vs)) "normal")))

(defn- run-ex [vs t p]
  (let [{:keys [kind text]} (:cmd vs)
        vs (leave-cmd vs)]
    (if (= kind ":")
      (let [c (.trim text)]
        (cond
          (= c "") {:vs vs :text t :pos p}
          (= c "w") {:vs vs :text t :pos p :effect "save"}
          (= c "q") {:vs vs :text t :pos p :effect "close"}
          (= c "q!") {:vs vs :text t :pos p :effect "discard"}
          (contains? #{"wq" "x" "wq!"} c) {:vs vs :text t :pos p :effect "save-close"}
          (.test #"^[0-9]+$" c) {:vs vs :text t
                                 :pos (first-nonblank t (start-of-line t (max 0 (dec (js/parseInt c 10)))))}
          :else {:vs (assoc vs :msg (str "Not an editor command: " c)) :text t :pos p}))
      (let [pat (if (= "" text) (:pat (:search vs)) text)
            back (= kind "?")
            vs (assoc vs :search {:pat pat :back back})
            q (search t p pat back)]
        (if (some? q)
          {:vs (assoc vs :col (col-of t q)) :text t :pos q}
          {:vs (assoc vs :msg (str "Pattern not found: " pat)) :text t :pos p})))))

(def ^:private key-alias
  {"ArrowLeft" "h" "ArrowRight" "l" "ArrowUp" "k" "ArrowDown" "j"
   "Home" "0" "End" "$" "Delete" "x"})

(defn press
  "Key k (a KeyboardEvent key; C-r for Ctrl+R) on buffer {:text :pos}
  in vim state vs: {:handled false} leaves the key to the textarea,
  else {:handled true :vs :text :pos :effect}, the effect nil or
  \"save\", \"close\", \"save-close\", \"discard\" (:q!)."
  [vs {:keys [text pos]} k]
  (let [vs (dissoc vs :msg)
        t text
        mode (:mode vs)
        visual (or (= mode "visual") (= mode "vline"))
        p (cond (or visual (some? (:visual (:cmd vs)))) (get vs :head pos)
                (= mode "normal") (clamp t pos)
                :else pos)
        known (or (= 1 (.-length k)) (contains? #{"Escape" "Enter" "Backspace" "C-r"} k) (contains? key-alias k))
        handled (fn [r] (cond-> (assoc r :handled true)
                          (contains? #{"visual" "vline"} (:mode (:vs r))) (assoc-in [:vs :head] (:pos r))))]
    (cond
      (= mode "insert")
      (if (= k "Escape") (handled (leave-insert vs t pos)) {:handled false :vs vs})

      (= mode "cmd")
      (let [cmd (:cmd vs)]
        (handled
         (cond
           (= k "Escape") {:vs (leave-cmd vs) :text t :pos p}
           (= k "Enter") (run-ex vs t p)
           (= k "Backspace") (if (= "" (:text cmd))
                               {:vs (leave-cmd vs) :text t :pos p}
                               {:vs (update-in vs [:cmd :text] #(subs % 0 (dec (.-length %)))) :text t :pos p})
           (= 1 (.-length k)) {:vs (update-in vs [:cmd :text] str k) :text t :pos p}
           :else {:vs vs :text t :pos p})))

      (not known) {:handled false :vs vs}

      (and (= k "Escape") (not visual) (empty? (:keys vs)))
      {:handled false :vs vs}

      (and (= k "Escape") (seq (:keys vs)))
      (handled {:vs (assoc vs :keys []) :text t :pos p})

      :else
      (let [ks (conj (:keys vs) (get key-alias k k))
            c (parse ks visual)]
        (cond
          (= c "more") (handled {:vs (assoc vs :keys ks) :text t :pos p})
          (nil? c) (handled {:vs (assoc vs :keys []) :text t :pos p})
          :else (let [vs (assoc vs :keys [])
                      r (if visual (run-visual vs t p c) (run-normal vs t p c ks))]
                  (handled r)))))))

(defn selection
  "The textarea's selection [start end) for vim state vs over text t,
  caret p: a block on the caret's char in normal mode, the selected
  range in visual mode, the caret in insert mode."
  [vs t p]
  (case (if (= "cmd" (:mode vs)) (or (:visual (:cmd vs)) "normal") (:mode vs))
    "insert" [p p]
    "visual" (let [a (min (:anchor vs) p) b (max (:anchor vs) p)]
               [a (min (.-length t) (inc b))])
    "vline" (let [a (min (:anchor vs) p) b (max (:anchor vs) p)]
              [(line-start t a) (min (.-length t) (inc (line-end t b)))])
    (let [p (clamp t p)]
      (if (< p (line-end t p)) [p (inc p)] [p p]))))

(defn status
  "The status line: the mode, the keys pending, the command line, or
  the last message."
  [vs]
  (case (:mode vs)
    "insert" "-- INSERT --"
    "visual" "-- VISUAL --"
    "vline" "-- VISUAL LINE --"
    "cmd" (str (:kind (:cmd vs)) (:text (:cmd vs)))
    (if (seq (:keys vs)) (apply str (:keys vs)) (or (:msg vs) ""))))

(defn interrupt
  "vs after the caret or the text moved under it — a click, the file
  reloaded from disk: visual mode, a command line and pending keys end;
  an insert goes on, but . won't repeat it, nor its count."
  [vs]
  (if (= "insert" (:mode vs))
    (update vs :ins assoc :dot nil :rep nil)
    (-> vs (assoc :mode "normal" :keys []) (dissoc :anchor :head :cmd :msg))))

(defn reloaded
  "vs after the textarea took text t from disk, the caret at p: as after
  a click, and nothing to undo back to the text before."
  [vs t p]
  (cond-> (assoc (interrupt vs) :undo [] :redo [])
    (= "insert" (:mode vs)) (update :ins assoc :undo {:text t :pos p} :text t :at p)))

(defn replaced
  "vs after text t, the caret at p, was replaced from outside vi mode:
  normal mode — an insert's typing its own undo step — and u brings t
  back."
  [vs t p]
  (let [vs (if (= "insert" (:mode vs)) (:vs (leave-insert vs t p)) (interrupt vs))]
    (push-undo vs t p)))

(defn fresh
  "vs for a doc just opened: no undo of another text; the register,
  the dot and the last search and find stay."
  [vs]
  (merge (init) (select-keys vs [:reg :dot :search :find])))
