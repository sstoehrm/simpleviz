(ns simpleviz.svg)

;; SVG export (issue #87): a stand-in for CanvasRenderingContext2D that
;; records the painter's calls as SVG elements instead of pixels, and the
;; document around them with the source EDN embedded under the PNG's
;; keys. No DOM — text is measured by the function the caller passes —
;; so node tests cover it.

(def ^:private NS "https://github.com/sstoehrm/simpleviz")

(defn- round2
  "v rounded to at most 2 decimals, so big graphs stay small."
  [v]
  (/ (js/Math.round (* v 100)) 100))

(def ^:private xml-escapes {"&" "&amp;" "<" "&lt;" ">" "&gt;" "\"" "&quot;" "\r" "&#13;"})

(defn- esc
  "s escaped for XML text and double-quoted attributes. A CR becomes a
  character reference so a parser doesn't normalize it away; control
  characters XML 1.0 forbids outright become U+FFFD."
  [s]
  (.replace (str s) (js/RegExp. "[&<>\"\\r\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]" "g")
            (fn [c] (get xml-escapes c "\uFFFD"))))

(defn- attrs
  "` name=\"value\"` for each [name value] pair whose value is not nil."
  [pairs]
  (.join (mapv (fn [[k v]] (if (some? v) (str " " k "=\"" (esc v) "\"") "")) pairs) ""))

;; --- transform: canvas's [a b c d e f], applied as points are added -------

(defn- xf [m x y]
  [(+ (* (nth m 0) x) (* (nth m 2) y) (nth m 4))
   (+ (* (nth m 1) x) (* (nth m 3) y) (nth m 5))])

(defn- pt [[x y]] (str (round2 x) " " (round2 y)))

;; --- path state -----------------------------------------------------------
;;
;; st holds the path being built: :d (SVG path data so far), :cur (the
;; canvas current point, nil before the first subpath), :start (the
;; subpath's first point, where closePath returns) and :pending (a moveTo
;; not yet written — emitted only once something is drawn from it, so a
;; path that never draws stays empty). Points are in document coordinates.

(defn- move-to! [st p]
  (assoc! st :pending p :cur p :start p :last-fill nil))

(defn- draw!
  "Append drawing commands, first writing the moveTo they start from."
  [st cmds]
  (when-let [p (:pending st)]
    (assoc! st :d (str (:d st) "M" (pt p)) :pending nil))
  (assoc! st :d (str (:d st) cmds) :last-fill nil))

(defn- line-to! [st p]
  (if (some? (:cur st))
    (do (draw! st (str "L" (pt p)))
        (assoc! st :cur p))
    ;; canvas: a lineTo without a subpath only starts one
    (move-to! st p)))

;; --- paint ----------------------------------------------------------------

(defn- opacity [rec]
  (let [a (:globalAlpha rec)]
    (when (not= a 1) (round2 a))))

(defn- stroke-attrs [rec dash]
  {:stroke (:strokeStyle rec)
   :stroke-width (round2 (:lineWidth rec))
   :dash (when (pos? (count dash)) (.join (mapv round2 dash) " "))})

(defn- path-el [{:keys [d fill stroke stroke-width dash opacity]}]
  (str "<path" (attrs [["d" d] ["fill" fill] ["stroke" stroke] ["stroke-width" stroke-width]
                       ["stroke-dasharray" dash] ["opacity" opacity]])
       "/>"))

(defn- parse-font
  "Style, weight, size (px, as a number) and family of a canvas font
  shorthand like \"bold 13px system-ui, sans-serif\"; nil when absent."
  [font]
  (when-let [parts (re-matches #"\s*(.*?)(\d+(?:\.\d+)?)px(?:/\S+)?\s+(.+)" font)]
    (let [[_ pre size family] parts
          words (.split (.trim pre) (js/RegExp. "\\s+"))]
      {:style (some (fn [w] (when (or (= w "italic") (= w "oblique")) w)) words)
       :weight (some (fn [w] (when (re-matches #"bold(?:er)?|lighter|\d+" w) w)) words)
       :size (js/Number size)
       :family family})))

(def ^:private anchors {"center" "middle" "right" "end" "end" "end"})

(defn- text-el
  "A <text> at the transformed (x, y). The glyphs themselves are never
  rotated — the painter draws no text under a rotation."
  [rec m txt x y paint]
  (let [[px py] (xf m x y)
        f (parse-font (:font rec))]
    (str "<text" (attrs (-> [["x" (round2 px)] ["y" (round2 py)]
                             ["text-anchor" (get anchors (:textAlign rec) "start")]
                             ["font-style" (:style f)] ["font-weight" (:weight f)]
                             ["font-size" (:size f)] ["font-family" (:family f)]
                             ;; canvas draws every space and a newline or tab
                             ;; as one; SVG collapses and trims them unless
                             ;; the <text> itself (Chromium ignores an
                             ;; ancestor's) says preserve
                             ["xml:space" (when (re-find #"^ | $|  |[\t\n\r]" (str txt)) "preserve")]]
                            (into paint)
                            (conj ["opacity" (opacity rec)])))
         ">" (esc txt) "</text>")))

;; canvas state that save/restore carry besides the transform and dash
(def ^:private PROPS ["fillStyle" "strokeStyle" "lineWidth" "font" "textAlign" "globalAlpha"])

(defn recorder
  "An object that stands in for a CanvasRenderingContext2D, implementing
  every member the painter uses (canvas/paint-items! and the draw-*
  fns): the settable fillStyle, strokeStyle, lineWidth, font, textAlign
  and globalAlpha, and beginPath, moveTo, lineTo, arc, rect, roundRect
  (positive size, one radius), closePath, fill, stroke, fillText,
  strokeText, measureText, setLineDash, save, restore, translate and
  rotate (translate/rotate only, so the transform stays rigid). Each
  fill, stroke, fillText and strokeText records an SVG element with the
  paint state of that moment; a stroke right after a fill of the same
  opaque path joins the fill's element. measure is (fn [text font] width),
  as canvas/measure."
  [measure]
  (let [out []
        st {:m [1 0 0 1 0 0] :stack [] :dash []
            :d "" :cur nil :start nil :pending nil :last-fill nil}
        rec {:fillStyle "#000" :strokeStyle "#000" :lineWidth 1
             :font "10px sans-serif" :textAlign "start" :globalAlpha 1
             :svg-elements out}]
    (js/Object.assign
     rec
     {:beginPath (fn [] (assoc! st :d "" :cur nil :start nil :pending nil :last-fill nil))
      :moveTo (fn [x y] (move-to! st (xf (:m st) x y)))
      :lineTo (fn [x y] (line-to! st (xf (:m st) x y)))
      :closePath (fn []
                   (when (some? (:cur st))
                     ;; a lone moveTo has nothing to close
                     (when (nil? (:pending st)) (draw! st "Z"))
                     (assoc! st :cur (:start st))))
      :rect (fn [x y w h]
              (let [m (:m st)
                    p (xf m x y)]
                ;; Z returns to p, where canvas's new subpath after a
                ;; rect starts too
                (move-to! st p)
                (draw! st (str "L" (pt (xf m (+ x w) y)) "L" (pt (xf m (+ x w) (+ y h)))
                               "L" (pt (xf m x (+ y h))) "Z"))))
      :roundRect (fn [x y w h radius]
                   ;; canvas shrinks radii that don't fit their side
                   (let [r (js/Math.min radius (/ w 2) (/ h 2))
                         m (:m st)
                         p (fn [px py] (pt (xf m px py)))
                         corner (fn [px py] (str "A" (round2 r) " " (round2 r) " 0 0 1 " (p px py)))]
                     (move-to! st (xf m (+ x r) y))
                     (draw! st (str "L" (p (- (+ x w) r) y) (corner (+ x w) (+ y r))
                                    "L" (p (+ x w) (- (+ y h) r)) (corner (- (+ x w) r) (+ y h))
                                    "L" (p (+ x r) (+ y h)) (corner x (- (+ y h) r))
                                    "L" (p x (+ y r)) (corner (+ x r) y) "Z"))
                     ;; like rect, it leaves a new subpath at (x, y)
                     (move-to! st (xf m x y))))
      :arc (fn [x y r a0 a1 ccw]
             (let [m (:m st)
                   at (fn [a] (xf m (+ x (* r (js/Math.cos a))) (+ y (* r (js/Math.sin a)))))
                   s (at a0)
                   tau (* 2 js/Math.PI)
                   arc-to (fn [large p]
                            (str "A" (round2 r) " " (round2 r) " 0 " large " " (if ccw 0 1) " " (pt p)))]
               ;; canvas: an arc joins the current subpath with a line
               (line-to! st s)
               (if (>= (if ccw (- a0 a1) (- a1 a0)) tau)
                 ;; a full circle: SVG can't arc to its own start, so two halves
                 (draw! st (str (arc-to 0 (at (if ccw (- a0 js/Math.PI) (+ a0 js/Math.PI))))
                                (arc-to 0 s)))
                 (let [sweep (mod (if ccw (- a0 a1) (- a1 a0)) tau)
                       e (at a1)]
                   (when (pos? sweep)
                     (draw! st (arc-to (if (> sweep js/Math.PI) 1 0) e))
                     (assoc! st :cur e))))))
      :fill (fn []
              (when (pos? (count (:d st)))
                (let [el {:d (:d st) :fill (:fillStyle rec) :opacity (opacity rec)}]
                  (.push out (path-el el))
                  (assoc! st :last-fill (when (nil? (:opacity el))
                                          {:i (dec (count out)) :el el})))))
      :stroke (fn []
                (when (pos? (count (:d st)))
                  (let [lf (:last-fill st)
                        s (stroke-attrs rec (:dash st))]
                    ;; fill-then-stroke is SVG's own paint order, so one
                    ;; element — unless translucent: one element's opacity
                    ;; composites fill and stroke as a group, canvas doesn't
                    (if (and (some? lf) (= (:i lf) (dec (count out))) (= 1 (:globalAlpha rec)))
                      (aset out (:i lf) (path-el (merge (:el lf) s)))
                      (.push out (path-el (merge {:d (:d st) :fill "none" :opacity (opacity rec)} s))))
                    (assoc! st :last-fill nil))))
      :fillText (fn [txt x y]
                  (.push out (text-el rec (:m st) txt x y [["fill" (:fillStyle rec)]])))
      :strokeText (fn [txt x y]
                    (let [s (stroke-attrs rec (:dash st))]
                      (.push out (text-el rec (:m st) txt x y
                                          [["fill" "none"] ["stroke" (:stroke s)]
                                           ["stroke-width" (:stroke-width s)]
                                           ["stroke-linejoin" "round"]
                                           ["stroke-dasharray" (:dash s)]]))))
      :measureText (fn [txt] {:width (measure txt (:font rec))})
      :setLineDash (fn [segments] (assoc! st :dash (vec segments)))
      :save (fn [] (.push (:stack st) [(:m st) (:dash st) (mapv (fn [k] (get rec k)) PROPS)]))
      :restore (fn []
                 (when-let [saved (.pop (:stack st))]
                   (let [[m dash vals] saved]
                     (assoc! st :m m :dash dash)
                     (dotimes [i (count PROPS)]
                       (aset rec (nth PROPS i) (nth vals i))))))
      :translate (fn [tx ty]
                   (let [[a b c d e f] (:m st)]
                     (assoc! st :m [a b c d (+ e (* a tx) (* c ty)) (+ f (* b tx) (* d ty))])))
      :rotate (fn [angle]
                (let [[a b c d e f] (:m st)
                      cos (js/Math.cos angle)
                      sin (js/Math.sin angle)]
                  (assoc! st :m [(+ (* a cos) (* c sin)) (+ (* b cos) (* d sin))
                                 (- (* c cos) (* a sin)) (- (* d cos) (* b sin)) e f])))})))

(defn markup
  "The recorded elements, in drawing order, one per line."
  [rec]
  (.join (:svg-elements rec) "\n"))

(defn svg-document
  "The complete SVG document: width x height user units on a background
  rect, with each [key text] pair of sources embedded in <metadata> (which
  declares the simpleviz namespace) as a simpleviz:source element — keys
  as the PNG export's iTXt keywords (\"simpleviz-edn\", or
  \"simpleviz-edn-old\"/\"simpleviz-edn-new\"), the text XML-escaped
  rather than in CDATA, since EDN may hold \"]]>\".
  stroke-miterlimit is canvas's default 10, not SVG's 4."
  [rec {:keys [width height background sources]}]
  (let [w (round2 width)
        h (round2 height)]
    (str (.join (-> ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                     (str "<svg xmlns=\"http://www.w3.org/2000/svg\"" (attrs [["width" w] ["height" h]
                                                                               ["viewBox" (str "0 0 " w " " h)]])
                          " stroke-miterlimit=\"10\">")
                     (str "<metadata xmlns:simpleviz=\"" NS "\">")]
                    (into (mapv (fn [[k text]]
                                  (str "<simpleviz:source key=\"" (esc k) "\">"
                                       (esc text) "</simpleviz:source>"))
                                sources))
                    (conj "</metadata>"
                          (str "<rect" (attrs [["width" w] ["height" h] ["fill" background]]) "/>"))
                    (into (:svg-elements rec))
                    (conj "</svg>"))
                "\n")
         "\n")))
