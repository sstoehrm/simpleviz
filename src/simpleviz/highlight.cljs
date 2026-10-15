(ns simpleviz.highlight
  "Syntax highlighting for fenced code blocks: a fence's language and its
  text in, hiccup children out — plain strings and [:span {:class
  \"hl-<kind>\"} text], whose texts joined are the text again. The kinds:
  comment, string, keyword, number, literal, attr, tag, meta, added,
  removed. Small scanners, not parsers: most languages are one table of
  comment, quote and word rules; diff, markup and css have their own.")

(defn- sticky [src] (js/RegExp. src "y"))

(defn- match-at
  "The match of sticky re at i in s, or nil."
  [re s i]
  (set! (.-lastIndex re) i)
  (when-let [m (.exec re s)] (aget m 0)))

(defn- add!
  "Append s to out, classed kind, or as plain text joined to the plain
  text before it."
  [out kind s]
  (when-not (= "" s)
    (if (some? kind)
      (.push out [:span {:class (str "hl-" kind)} s])
      (let [k (dec (.-length out))]
        (if (and (>= k 0) (string? (aget out k)))
          (aset out k (str (aget out k) s))
          (.push out s))))))

(defn- blank? [l] (= "" (.trim l)))

;; markdown/indent, which can't be required here: markdown requires this
(defn- indent [l] (- (count l) (count (.trimStart l))))

(defn- line-before
  "The text of i's line before i."
  [s i]
  (subs s (inc (.lastIndexOf s "\n" (dec i))) i))

(defn- line-end [s i]
  (let [e (.indexOf s "\n" i)] (if (neg? e) (count s) e)))

(defn- after
  "The index just past the first close at or after i, else the end of s."
  [s close i]
  (let [e (.indexOf s close i)] (if (neg? e) (count s) (+ e (count close)))))

(defn- string-end
  "Where the string whose opening quote q starts at i ends: past its
  closing q (a backslash escapes, unless raw), at the line's end when it
  may not span lines (ml false), else the end of s."
  ([s i q ml] (string-end s i q ml false))
  ([s i q ml raw]
   (let [n (count s)]
     (loop [k (+ i (count q))]
       (cond (>= k n) n
             (and (not raw) (= "\\" (.charAt s k))) (recur (+ k 2))
             (.startsWith s q k) (+ k (count q))
             (and (not ml) (= "\n" (.charAt s k))) k
             :else (recur (inc k)))))))

;; ---------------------------------------------------------------- tables

(def ^:private space-re (sticky "[ \\t\\n]+"))
(def ^:private word-char-re #"[\w$]")
(def ^:private digit-re #"\d")
(def ^:private number-re
  (sticky "-?(?:0[xXbBoO][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)(?:[a-zA-Z%][\\w%]*)?"))

(defn- prev-char [s i] (if (pos? i) (.charAt s (dec i)) ""))

(defn- comment-at [spec s i]
  (or (some (fn [m] (when (and (.startsWith s m i)
                               (or (not= "#" m) (not (:hash-after-space spec))
                                   (blank? (prev-char s i))))
                      ["comment" (line-end s i)]))
            (:line spec))
      (some (fn [[o c]] (when (.startsWith s o i) ["comment" (after s c (+ i (count o)))]))
            (:block spec))))

(defn- key-after? [spec s end]
  (and (some? (:keys spec)) (some? (match-at (:keys spec) s end))))

(defn- string-at [spec s i]
  (let [c (.charAt s i)]
    (when (and (.includes (:quotes spec) c)
               (not (and (:quote-boundary spec) (.test word-char-re (prev-char s i)))))
      (let [triple (.repeat c 3)
            q (if (and (:triple spec) (.startsWith s triple i)) triple c)
            end (string-end s i q (or (= q triple) (.includes (or (:ml spec) "") c))
                            (.includes (or (:raw spec) "") c))]
        [(if (key-after? spec s end) "attr" "string") end]))))

(defn- number-at [s i]
  (let [c (.charAt s i)
        p (prev-char s i)]
    (when (and (not (.test word-char-re p))
               (or (.test digit-re c)
                   (and (= "-" c) (.test digit-re (.charAt s (inc i))) (not (.test #"[)\]]" p)))))
      (when-let [m (match-at number-re s i)] ["number" (+ i (count m))]))))

(defn- word-at [spec s i]
  (when-let [w (match-at (:word spec) s i)]
    (let [end (+ i (count w))
          k (if (:ci spec) (.toLowerCase w) w)]
      [(cond (key-after? spec s end) "attr"
             (contains? (:keywords spec) k) "keyword"
             (contains? (:literals spec) k) "literal")
       end])))

(defn- scan
  "Highlight s by a language table spec: :line and :block comments (a #
  one only after a space with :hash-after-space),
  :quotes (:ml those that span lines, :triple for \"\"\" strings, :raw
  those where a backslash escapes nothing,
  :quote-boundary when a quote right after a word is an apostrophe),
  :word, :keywords and :literals (:ci matching any case), :keys a sticky
  regex after a word or string that makes it a key, :extra more rules."
  [spec s]
  (let [out #js []
        n (count s)]
    (loop [i 0]
      (if (>= i n)
        out
        ;; no rule starts on a space: take a run of them at once
        (let [[kind end] (if-let [sp (match-at space-re s i)]
                           [nil (+ i (count sp))]
                           (or (comment-at spec s i)
                               (string-at spec s i)
                               (some (fn [f] (f s i)) (:extra spec))
                               (number-at s i)
                               (word-at spec s i)))]
          (if (and (some? end) (> end i))
            (do (add! out kind (subs s i end)) (recur end))
            (do (add! out nil (.charAt s i)) (recur (inc i)))))))))

(defn- rule
  "An :extra rule: sticky regex src at i, as kind."
  [kind src]
  (let [re (sticky src)]
    (fn [s i] (when-let [m (match-at re s i)] [kind (+ i (count m))]))))

(defn- words [s] (set (.split s " ")))

(def ^:private c-word (sticky "[A-Za-z_$][\\w$]*"))
(def ^:private dash-word (sticky "[A-Za-z_][\\w-]*"))
(def ^:private key-word (sticky "[A-Za-z_][\\w.-]*"))
(def ^:private annotation (rule "attr" "@[A-Za-z_][\\w.]*"))
(def ^:private shell-var (rule "attr" "\\$(?:\\{[^}\\n]*\\}|[A-Za-z_]\\w*|[0-9@#?*$!-])"))

(def ^:private lisp
  {:line [";"] :quotes "\"" :ml "\""
   :word (sticky "[^\\s()\\[\\]{}\",;@^`~'#:\\\\\\d][^\\s()\\[\\]{}\",;@^`~]*")
   :keywords (words "def defn defn- defmacro defmulti defmethod defprotocol defrecord deftype defonce fn fn* let letfn loop recur if if-let if-some if-not when when-let when-some when-not when-first cond condp case do doseq dotimes for ns require import try catch finally throw quote var new set! and or -> ->> some-> some->> cond-> cond->> as->")
   :literals (words "nil true false")
   :extra [(rule "attr" "::?[^\\s()\\[\\]{}\",;@^`~]+")
           (rule "number" "-?\\d+/\\d+")
           (rule "string" "\\\\(?:newline|space|tab|return|formfeed|backspace|u[0-9a-fA-F]{4}|o[0-7]{1,3}|.)")
           (fn [s i] (when (.startsWith s "#\"" i) ["string" (string-end s (inc i) "\"" true)]))]})

(def ^:private bash
  {:line ["#"] :hash-after-space true :quotes "\"'" :ml "\"'" :word dash-word
   :keywords (words "if then else elif fi for while until do done case esac in function select return break continue export local readonly declare source alias unset set shift trap exit")
   :literals (words "true false")
   :extra [shell-var]})

(def ^:private console (assoc bash :ml "" :quote-boundary true))

(def ^:private python
  {:line ["#"] :quotes "\"'" :triple true :word c-word
   :keywords (words "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case")
   :literals (words "True False None self")
   :extra [annotation]})

(def ^:private block-head-re (sticky "[|>][+-]?[1-9]?[+-]?[ \\t]*(?:#[^\\n]*)?(?=\\n|$)"))

(defn- yaml-block
  "A | or > block scalar after a key or list dash: its head and the
  lines below indented deeper than its own line, as one string."
  [s i]
  (when-let [m (match-at block-head-re s i)]
    (let [before (line-before s i)
          base (indent before)
          n (count s)]
      (when (.test #"[:-][ \t]+$" before)
        ["string" (loop [e (+ i (count m)) end e]
                    (if (>= e n)
                      end
                      (let [le (line-end s (inc e))
                            l (subs s (inc e) le)]
                        (cond (blank? l) (recur le end)
                              (> (indent l) base) (recur le le)
                              :else end))))]))))

(def ^:private yaml
  {:line ["#"] :hash-after-space true :quotes "\"'" :quote-boundary true
   :word key-word :keys (sticky "[ \\t]*:(?=\\s|$)")
   :literals (words "true false null yes no on off True False Null ~")
   :extra [yaml-block]})

(def ^:private toml
  {:line ["#"] :quotes "\"'" :triple true
   :word key-word :keys (sticky "[ \\t]*=")
   :literals (words "true false")})

(def ^:private dockerfile
  {:line ["#"] :hash-after-space true :quotes "\"'" :quote-boundary true :word c-word
   :keywords (words "FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL MAINTAINER AS")
   :extra [shell-var]})

(defn- c-like [kws lits more]
  (merge {:line ["//"] :block [["/*" "*/"]] :quotes "\"'`" :ml "`" :word c-word
          :keywords (words kws) :literals (words lits)}
         more))

(def ^:private flags-re (sticky "[a-z]*"))

(defn- regex-at
  "A JS regex literal at i: a / where a value can't be, as after an
  operator or return, up to its closing / on the same line and flags."
  [s i]
  (when (and (= "/" (.charAt s i))
             (let [before (.trimEnd (subs s (max 0 (- i 40)) i))]
               (or (= "" before)
                   (.test #"[(,=:\[!&|?{};+\-*%<>~^]$" before)
                   (.test #"\b(?:return|typeof|case|do|else|in|of|yield|await|void|delete|throw)$" before))))
    (loop [k (inc i) class false]
      (when (< k (count s))
        (let [c (.charAt s k)]
          (cond (= "\n" c) nil
                (= "\\" c) (recur (+ k 2) class)
                (= "[" c) (recur (inc k) true)
                (= "]" c) (recur (inc k) false)
                (and (= "/" c) (not class)) ["string" (+ k 1 (count (match-at flags-re s (inc k))))]
                :else (recur (inc k) class)))))))

(def ^:private js-kws
  "async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in instanceof let new of return set static super switch this throw try typeof var void while with yield")
(def ^:private js-lits "true false null undefined NaN Infinity")
(def ^:private js-more {:extra [annotation regex-at]})

(def ^:private js (c-like js-kws js-lits js-more))

(def ^:private ts
  (c-like (str js-kws " abstract as declare enum implements interface keyof namespace private protected public readonly type satisfies")
          js-lits js-more))

(def ^:private java
  (c-like "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package permits private protected public record return sealed short static strictfp super switch synchronized this throw throws transient try var void volatile while yield"
          "true false null" {:triple true :extra [annotation]}))

(def ^:private go
  (c-like "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var"
          "true false nil iota" {:raw "`"}))

(def ^:private rust
  (c-like "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while"
          "true false" {:quotes "\"" :ml "\"" :extra [(rule "string" "b?r(#*)\"[\\s\\S]*?\"\\1")]}))

(def ^:private c
  (c-like "auto bool break case catch char class const constexpr continue default delete do double else enum explicit extern float for friend goto if inline int long mutable namespace new noexcept operator private protected public register return short signed sizeof static struct switch template this throw try typedef typename union unsigned using virtual void volatile while override"
          "true false NULL nullptr"
          {:extra [(rule "keyword" "(?<=(?:^|\\n)[ \\t]*)#[ \\t]*[A-Za-z]+")]}))

(def ^:private csharp
  (c-like "abstract as async await base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern finally fixed float for foreach get goto if implicit in init int interface internal is lock long namespace new object operator out override params private protected public readonly record ref return sbyte sealed set short sizeof static string struct switch this throw try typeof uint ulong unchecked unsafe ushort using var virtual void volatile while"
          "true false null" {:extra [(rule "string" "@\"(?:[^\"]|\"\")*\"")]}))

(def ^:private kotlin
  (c-like "as break by catch class companion constructor continue data do else enum finally for fun get if import in init inline interface internal is lateinit object open override package private protected public return sealed set super suspend this throw try typealias val var when while"
          "true false null" {:triple true :extra [annotation]}))

(def ^:private json
  {:line ["//"] :block [["/*" "*/"]] :quotes "\"" :word c-word
   :keys (sticky "[ \\t]*:") :literals (words "true false null")})

(def ^:private sql
  {:line ["--"] :block [["/*" "*/"]] :quotes "'\"`" :ml "'" :raw "'\"`" :word c-word :ci true
   :keywords (words "select from where and or not in is like ilike between join inner left right full outer cross natural on using as group by order having limit offset insert into values update set delete create table view index drop alter add column primary key foreign references unique default check constraint distinct union all exists case when then else end begin commit rollback transaction with recursive returning if replace cascade asc desc grant revoke database schema trigger function procedure return returns declare over partition window int integer bigint smallint serial text varchar char boolean bool date time timestamp timestamptz interval numeric decimal real float double uuid json jsonb")
   :literals (words "null true false")})

;; ---------------------------------------------------------------- diff

(def ^:private hunk-re #"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@")

(defn- diff
  "Lines by their first character. A hunk runs for the old and new line
  counts its @@ header gives (to the next @@ when it gives none); outside
  one, ---, +++, diff and index lines are file headers."
  [s]
  (let [out #js []
        ls (.split s "\n")]
    (loop [k 0 old 0 new 0]
      (when (< k (count ls))
        (let [l (aget ls k)
              hunk (or (pos? old) (pos? new))]
          (when (pos? k) (add! out nil "\n"))
          (cond
            (.startsWith l "@@")
            (let [h (.exec hunk-re l)]
              (add! out "meta" l)
              (recur (inc k)
                     (if h (js/Number (or (aget h 1) 1)) js/Infinity)
                     (if h (js/Number (or (aget h 2) 1)) js/Infinity)))

            (if hunk
              (.startsWith l "diff ")
              (some #(.startsWith l %) ["--- " "+++ " "diff " "index "]))
            (do (add! out "meta" l) (recur (inc k) 0 0))

            :else
            (let [c (.charAt l 0)]
              (add! out (case c "+" "added" "-" "removed" nil) l)
              (recur (inc k)
                     (if (= "+" c) old (dec old))
                     (if (= "-" c) new (dec new))))))))
    out))

;; ---------------------------------------------------------------- markup

(def ^:private tag-open-re (sticky "</?[A-Za-z][\\w:.-]*"))
(def ^:private attr-name-re (sticky "[^\\s=>/\"']+"))
(def ^:private unquoted-re (sticky "[^\\s\"'=<>`]+"))
(def ^:private entity-re (sticky "&#?\\w+;"))

(defn- markup
  "HTML, XML and SVG. tag is nil in text, the tag's name inside a tag (\"\"
  for a closing one), and \"/script\" or \"/style\" in such a body,
  which is passed over as text up to its closing tag."
  [s]
  (let [out #js []
        n (count s)
        low (.toLowerCase s)]
    (loop [i 0 tag nil]
      (when (< i n)
        (let [c (.charAt s i)
              [kind end nxt]
              (cond
                (nil? tag)
                (or (when (.startsWith s "<!--" i) ["comment" (after s "-->" (+ i 4)) nil])
                    (when (or (.startsWith s "<!" i) (.startsWith s "<?" i)) ["meta" (after s ">" i) nil])
                    (when-let [m (match-at tag-open-re s i)]
                      ["tag" (+ i (count m)) (if (.startsWith m "</") "" (.toLowerCase (subs m 1)))])
                    (when-let [m (match-at entity-re s i)] ["literal" (+ i (count m)) nil])
                    [nil (inc i) nil])

                (.startsWith tag "/")
                (let [e (.indexOf low (str "<" tag) i)] [nil (if (neg? e) n e) nil])

                :else
                (or (when (.startsWith s "/>" i) ["tag" (+ i 2) nil])
                    (when (= ">" c) ["tag" (inc i) (when (contains? #{"script" "style"} tag) (str "/" tag))])
                    (when (or (= "\"" c) (= "'" c)) ["string" (after s c (inc i)) tag])
                    (when (= "=" (prev-char s i))
                      (when-let [m (match-at unquoted-re s i)] ["string" (+ i (count m)) tag]))
                    (when-let [m (match-at attr-name-re s i)] ["attr" (+ i (count m)) tag])
                    [nil (inc i) tag]))]
          (add! out kind (subs s i end))
          (recur end nxt))))
    out))

;; ---------------------------------------------------------------- css

(def ^:private selector-re (sticky "[.#]?-?[A-Za-z_][\\w-]*"))
(def ^:private pseudo-re (sticky "::?[A-Za-z-][\\w-]*"))
(def ^:private css-word-re (sticky "-{0,2}[A-Za-z_][\\w-]*"))
(def ^:private hex-re (sticky "#[0-9a-fA-F]{3,8}(?![\\w-])"))
(def ^:private css-number-re (sticky "-?(?:\\d+\\.?\\d*|\\.\\d+)(?:%|[A-Za-z]+)?"))
(def ^:private at-rule-re (sticky "@[\\w-]+"))
(def ^:private important-re (sticky "![ \\t]*important"))
(def ^:private url-re (sticky "(?<=url\\()[^)\"'\\n]+(?=\\))"))

(defn- css-mode
  "What follows i inside braces: a selector when a { comes before the
  next ; or }, else a declaration's property."
  [s i]
  (let [n (count s)]
    (loop [k i]
      (if (>= k n)
        "prop"
        (let [c (.charAt s k)]
          (cond (= "{" c) "sel"
                (or (= ";" c) (= "}" c)) "prop"
                (.startsWith s "/*" k) (recur (after s "*/" (+ k 2)))
                (or (= "\"" c) (= "'" c)) (recur (string-end s k c false))
                :else (recur (inc k))))))))

(defn- css
  "mode: sel (selectors, at depth 0 always), prop or val (a declaration's
  two sides, or an at-rule's prelude)."
  [s]
  (let [out #js []
        n (count s)]
    (loop [i 0 depth 0 mode "sel"]
      (when (< i n)
        (let [c (.charAt s i)
              tok (fn [kind re] (when-let [m (match-at re s i)] [kind (+ i (count m)) depth mode]))
              [kind end d m]
              (or (when (.startsWith s "/*" i) ["comment" (after s "*/" (+ i 2)) depth mode])
                  (when (or (= "\"" c) (= "'" c)) ["string" (string-end s i c false) depth mode])
                  (when (.includes "{};" c)
                    (let [d (case c "{" (inc depth) "}" (max 0 (dec depth)) depth)]
                      [nil (inc i) d (if (pos? d) (css-mode s (inc i)) "sel")]))
                  (when-let [m (match-at at-rule-re s i)] ["keyword" (+ i (count m)) depth "val"])
                  (case mode
                    "sel" (or (tok "keyword" pseudo-re) (tok "tag" selector-re))
                    "prop" (or (when (= ":" c) [nil (inc i) depth "val"]) (tok "attr" css-word-re))
                    (or (tok "string" url-re)
                        (tok "number" hex-re)
                        (when-not (.test #"[\w-]" (prev-char s i)) (tok "number" css-number-re))
                        (tok "keyword" important-re)
                        (tok nil css-word-re)))
                  [nil (inc i) depth mode])]
          (add! out kind (subs s i end))
          (recur end d m))))
    out))

;; ---------------------------------------------------------------- languages

(def ^:private LANGS
  "Fence language name -> a language table to scan by, or a scanner."
  (let [by (fn [v & names] (map (fn [l] [l v]) names))]
    (js/Map.
     (concat (by lisp "clojure" "clj" "cljs" "cljc" "edn")
             (by bash "bash" "sh" "shell" "zsh")
             (by console "console")
             (by python "python" "py")
             (by yaml "yaml" "yml")
             (by toml "toml")
             (by dockerfile "dockerfile" "docker")
             (by js "javascript" "js" "jsx" "mjs")
             (by ts "typescript" "ts" "tsx")
             (by java "java")
             (by go "go" "golang")
             (by rust "rust" "rs")
             (by c "c" "cpp" "c++" "h")
             (by csharp "csharp" "cs" "c#")
             (by kotlin "kotlin" "kt")
             (by json "json" "jsonc")
             (by sql "sql")
             (by diff "diff" "patch")
             (by markup "html" "xml" "svg")
             (by css "css")))))

(defn highlight
  "Hiccup children for text in the fence language lang (any case), or nil
  when lang is none highlighted."
  [lang text]
  (when-let [v (.get LANGS (.toLowerCase (or lang "")))]
    (if (fn? v) (v text) (scan v text))))
