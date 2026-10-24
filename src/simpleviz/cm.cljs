(ns simpleviz.cm
  "The doc's text editor: CodeMirror 6 on the markdown tree of
  simpleviz.spell. The browser checks the spelling of the prose; code,
  link targets, URLs, HTML and table formulas are left out. The page's
  state holds the text: every typed change goes to `on-change`, and
  set-text! takes a change made elsewhere (disk, Format, vi keys)."
  (:require ["@codemirror/state" :refer [EditorState EditorSelection Annotation RangeSetBuilder Prec]]
            ["@codemirror/view" :refer [EditorView ViewPlugin Decoration keymap]]
            ["@codemirror/commands" :refer [history defaultKeymap historyKeymap]]
            ["@codemirror/language" :refer [Language defineLanguageFacet syntaxTree]]
            [simpleviz.spell :as spell]))

(def ^:private markdown
  (Language. (defineLanguageFacet) spell/md-parser [] "markdown"))

(def ^:private own
  "Marks the changes the page makes itself: on-change never hears of
  them, and the edit filter lets them through."
  (.define Annotation))

(def ^:private no-spell (.mark Decoration {:attributes {:spellcheck "false"}}))

(defn- no-spell-marks [view]
  (let [st (.-state view)
        doc (.-doc st)
        b (RangeSetBuilder.)]
    (doseq [[a z] (spell/skip-ranges (syntaxTree st) (fn [a z] (.sliceString doc a z)))]
      (when (< a z) (.add b a z no-spell)))
    (.finish b)))

;; the parse runs lazily, so a tree that grew without an edit counts too
(defn- spell-value [view]
  (let [v #js {:decorations (no-spell-marks view)
               :tree (syntaxTree (.-state view))}]
    (set! (.-update v)
          (fn [u]
            (let [tree (syntaxTree (.-state u))]
              (when (or (.-docChanged u) (not (identical? tree (.-tree v))))
                (set! (.-tree v) tree)
                (set! (.-decorations v) (no-spell-marks (.-view u)))))))
    v))

(def ^:private spell-marks
  (.define ViewPlugin spell-value {:decorations (fn [v] (.-decorations v))}))

(defn create!
  "A new editor in element `parent` holding `text`. Options:
  :on-change (fn [text]) after a typed change; :on-key (fn [e view]),
  true when it took the key; :may-edit? (fn []), false to drop typed
  changes (vi's normal mode); :on-scroll (fn []); :on-geometry (fn []),
  when lines may have moved."
  [parent text {:keys [on-change on-key may-edit? on-scroll on-geometry]}]
  (let [exts [(.of (.-contentAttributes EditorView) {:spellcheck "true" :autocorrect "off"
                                                      :autocapitalize "off" :aria-label "Doc text"})
              (.-lineWrapping EditorView)
              markdown
              spell-marks
              (history)
              (.highest Prec (.domEventHandlers EditorView {:keydown (fn [e view] (boolean (on-key e view)))}))
              (.of keymap (into [] (concat defaultKeymap historyKeymap)))
              (.of (.-transactionFilter EditorState)
                   (fn [tr] (if (and (.-docChanged tr) (nil? (.annotation tr own)) (not (may-edit?)))
                              [] tr)))
              (.of (.-updateListener EditorView)
                   (fn [u]
                     (when (and (.-docChanged u)
                                (some (fn [tr] (nil? (.annotation tr own))) (.-transactions u)))
                       (on-change (.toString (.. u -state -doc))))
                     (when (or (.-docChanged u) (.-geometryChanged u) (.-heightChanged u))
                       (on-geometry))))]
        view (EditorView. {:parent parent
                           :state (.create EditorState {:doc text :extensions exts})})]
    (.addEventListener (.-scrollDOM view) "scroll" (fn [_] (on-scroll)))
    view))

(defn text [view] (.toString (.. view -state -doc)))

(defn set-text!
  "Make the editor hold `t`, changing only the part between the common
  start and end, so the selection and undo stay sensible."
  [view t]
  (let [old (text view)]
    (when (not= old t)
      (let [n (min (count old) (count t))
            pre (loop [i 0] (if (and (< i n) (= (.charCodeAt old i) (.charCodeAt t i))) (recur (inc i)) i))
            suf (loop [i 0] (if (and (< i (- n pre))
                                     (= (.charCodeAt old (- (count old) 1 i)) (.charCodeAt t (- (count t) 1 i))))
                              (recur (inc i)) i))]
        (.dispatch view {:changes {:from pre :to (- (count old) suf) :insert (subs t pre (- (count t) suf))}
                         :annotations (.of own true)})))))

(defn caret
  "The selection's start — the char vi's block sits on."
  [view]
  (.. view -state -selection -main -from))

(defn selection [view]
  (let [m (.. view -state -selection -main)] [(.-from m) (.-to m)]))

(defn select!
  "Select a..b and scroll the caret's line into view, at the nearest
  edge. The first line scrolls to the very top, its padding too: its
  margin is more than the padding, so the scroll stops at 0 (a margin
  must stay less than the editor's height)."
  [view a b]
  (let [doc (.. view -state -doc)
        a (min a (.-length doc)) b (min b (.-length doc))
        first? (= 1 (.-number (.lineAt doc a)))]
    (.dispatch view {:selection (.range EditorSelection a b)
                     :effects (.scrollIntoView EditorView a {:y "nearest" :yMargin (if first? 50 5)})
                     :annotations (.of own true)})))

(defn focus! [view] (.focus view))
(defn focused? [view] (.-hasFocus view))

(defn line-tops
  "Each 1-based line in `lines`, mapped to its top in the scroller's
  content (what scrollTop counts), or nil when the line isn't there."
  [view lines]
  (let [doc (.. view -state -doc)
        scroller (.-scrollDOM view)
        off (+ (- (.-documentTop view) (.-top (.getBoundingClientRect scroller))) (.-scrollTop scroller))]
    (into {} (keep (fn [ln]
                     (when (<= ln (.-lines doc))
                       [ln (+ off (.-top (.lineBlockAt view (.-from (.line doc ln)))))])))
          lines)))
