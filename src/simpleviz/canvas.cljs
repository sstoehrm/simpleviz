(ns simpleviz.canvas
  (:require [simpleviz.colors :as colors]
            [simpleviz.scene :as scene]
            [simpleviz.svg :as svg]
            [simpleviz.transform :refer [NODE-FONT SUB-FONT]]
            [themes :as themes]))

;; HiDPI canvas painter + view state + pan/zoom. DOM-only namespace —
;; never imported by node tests.

(def ^:private measure-ctx
  (.getContext (js/document.createElement "canvas") "2d"))

(defn measure [text font]
  (set! (.-font measure-ctx) font)
  (.-width (.measureText measure-ctx text)))

;; the painter's theme: a complete theme map (server/themes.cljc) plus
;; its type-color tables, swapped whole by set-theme!
(defn- with-tables [theme] (assoc theme :tables (colors/tables theme)))

(def ^:private palette (atom (with-tables (get themes/THEMES :light))))

(defn set-theme!
  "Paint with theme, a complete theme map (every themes/KEYS key)."
  [theme]
  (reset! palette (with-tables theme)))

(defn- node-color
  "A node item's name color: its type's slot, or the untyped grey."
  [item]
  (let [t (:tables @palette)
        i (:color-idx item)]
    (if (some? i) (nth (:node t) i) (:neutral-node t))))

(defn- box-color
  "A box item's {:border :fill}: its type's slot, or the untyped grey."
  [item]
  (let [t (:tables @palette)
        i (:color-idx item)]
    (if (some? i) (nth (:box t) i) (:neutral-box t))))

(defn box-border
  "The current theme's border color for box type slot idx (nil: untyped)
  — the collapsed-boxes panel's dots."
  [idx]
  (:border (box-color {:color-idx idx})))

;; Mutated in place (assoc!), outside the state atom so pan/zoom repaints
;; without re-rendering the DOM.
(def view {:x 0 :y 0 :k 1 :initialized false})

(def ^:private repaint-cb (atom nil))
(def ^:private dirty (atom false))

(defn set-repaint! [cb] (reset! repaint-cb cb))

(defn request-paint! []
  (when-not @dirty
    (reset! dirty true)
    (js/requestAnimationFrame
     (fn [_]
       (reset! dirty false)
       (when-let [cb @repaint-cb] (cb))))))

(defn refit-next!
  "Forget that the view was fitted, so the next fit-view-once! fits
  again — for showing a different graph, not for a live reload of the
  same one (where keeping the user's pan/zoom is right)."
  []
  (assoc! view :initialized false))

(defn fit-view-once! [scene]
  (when-not (:initialized view)
    (assoc! view :initialized true)
    (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
          w (js/Math.max (:width scene) 1)
          h (js/Math.max (:height scene) 1)
          k (js/Math.min 1.25 (* 0.9 (js/Math.min (/ (.-width rect) w)
                                                  (/ (.-height rect) h))))]
      (assoc! view
              :k k
              :x (/ (- (.-width rect) (* w k)) 2)
              :y (/ (- (.-height rect) (* h k)) 2)))))

(defn center-on!
  "Pan so the item's bbox center is view-centered. Zoom is raised (never
  lowered, capped at 1.0) only when the item would render under 40px in
  its larger dimension."
  [item]
  (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
        bb (:bbox item)
        cx (/ (+ (:x0 bb) (:x1 bb)) 2)
        cy (/ (+ (:y0 bb) (:y1 bb)) 2)
        dim (js/Math.max (- (:x1 bb) (:x0 bb)) (- (:y1 bb) (:y0 bb)))
        k0 (:k view)
        k (if (< (* dim k0) 40)
            (js/Math.max k0 (js/Math.min 1.0 (/ 40 dim)))
            k0)]
    (assoc! view
            :k k
            :x (- (/ (.-width rect) 2) (* cx k))
            :y (- (/ (.-height rect) 2) (* cy k)))
    (request-paint!)))

(defn- rounded-rect [ctx x y w h r]
  (.beginPath ctx)
  (.roundRect ctx x y w h r))

(def ^:private diff-glyphs {"added" "+" "modified" "~" "removed" "−"})

(defn- diff-color [d]
  (case d
    "added" (:diff-added @palette)
    "modified" (:diff-modified @palette)
    "removed" (:diff-removed @palette)
    nil))

(defn- draw-diff-ring
  "Status ring + glyph just outside an element's top-left corner. Rings
  sit inside the scene bbox pad; the glyph can clip one frame early at
  the viewport edge, which is acceptable."
  [ctx item r text?]
  (let [d (:diff item)
        c (diff-color d)]
    (when (= d "removed") (.setLineDash ctx [5 4]))
    (rounded-rect ctx (- (:x item) 3) (- (:y item) 3)
                  (+ (:w item) 6) (+ (:h item) 6) r)
    (set! (.-strokeStyle ctx) c)
    (set! (.-lineWidth ctx) 2)
    (.stroke ctx)
    (.setLineDash ctx [])
    (when text?
      (set! (.-font ctx) "bold 11px system-ui, sans-serif")
      (set! (.-fillStyle ctx) c)
      (set! (.-textAlign ctx) "left")
      (.fillText ctx (get diff-glyphs d) (- (:x item) 2) (- (:y item) 6)))))

(defn- draw-box [ctx item sel? text?]
  (let [removed? (= (:diff item) "removed") c (box-color item)]
  (when removed? (set! (.-globalAlpha ctx) 0.45))
  (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 10)
  (set! (.-fillStyle ctx) (:fill c))
  (.fill ctx)
  (set! (.-strokeStyle ctx) (if sel? (:accent @palette) (:border c)))
  (set! (.-lineWidth ctx) (if sel? 2 1))
  (.stroke ctx)
  (when text?
  (if (:collapsed item)
    ;; collapsed: node-style two lines, centered left of the button zone
    ;; (empty shells have no button, so their label centers fully)
    (let [cx (+ (:x item) (/ (- (:w item) (if (:empty item) 0 18)) 2))]
      (set! (.-textAlign ctx) "center")
      (set! (.-font ctx) "bold 13px system-ui, sans-serif")
      (set! (.-fillStyle ctx) (:border c))
      (.fillText ctx (:name item) cx (+ (:y item) 18))
      (when (pos? (.-length (:type item)))
        (set! (.-font ctx) SUB-FONT)
        (set! (.-fillStyle ctx) (:sub @palette))
        (.fillText ctx (str "(" (:type item) ")") cx (+ (:y item) 33))))
    ;; expanded: header line, name + inline (type)
    (do
      (set! (.-textAlign ctx) "left")
      (set! (.-font ctx) "bold 13px system-ui, sans-serif")
      (set! (.-fillStyle ctx) (:border c))
      (.fillText ctx (:name item) (+ (:x item) 12) (+ (:y item) 20))
      (when (pos? (.-length (:type item)))
        (let [nw (.-width (.measureText ctx (:name item)))
              label (str "(" (:type item) ")")
              _ (set! (.-font ctx) SUB-FONT)
              tw (.-width (.measureText ctx label))]
          ;; only draw the inline type if it fits left of the button
          (when (< (+ 12 nw 5 tw) (- (:w item) 26))
            (set! (.-fillStyle ctx) (:sub @palette))
            (.fillText ctx label (+ (:x item) 12 nw 5) (+ (:y item) 20)))))))
  (when-not (:empty item)
  (let [bx (- (+ (:x item) (:w item)) scene/HIDE-BTN-RIGHT)
        by (+ (:y item) scene/HIDE-BTN-TOP)
        s scene/HIDE-BTN-SIZE]
    (rounded-rect ctx bx by s s 3)
    (set! (.-fillStyle ctx) (:btn-fill @palette))
    (.fill ctx)
    (set! (.-strokeStyle ctx) (:border c))
    (set! (.-lineWidth ctx) 1)
    (.stroke ctx)
    (.beginPath ctx)
    (.moveTo ctx (+ bx 4) (+ by (/ s 2)))
    (.lineTo ctx (+ bx s -4) (+ by (/ s 2)))
    (.stroke ctx)
    (when (:collapsed item)
      (.beginPath ctx)
      (.moveTo ctx (+ bx (/ s 2)) (+ by 4))
      (.lineTo ctx (+ bx (/ s 2)) (+ by s -4))
      (.stroke ctx))
    (when (and (:collapsed item) (:diff-inside item))
      (.beginPath ctx)
      (.arc ctx (- bx 8) (+ by (/ s 2)) 3.5 0 (* 2 js/Math.PI))
      (set! (.-fillStyle ctx) (:diff-modified @palette))
      (.fill ctx)))))
  (when (some? (:diff item))
    (draw-diff-ring ctx item 12 text?))
  (when removed? (set! (.-globalAlpha ctx) 1))))

(def ^:private STATE-R 5)

(defn- draw-state-mark
  "The node's :state as a mark centred on its top-right corner. Shape
  carries the state as well as color: new = disc, in-progress = half
  disc, blocked = square, done = disc with a check."
  [ctx item]
  (let [s (:state item)
        ;; state colors are flat keys :state-new … — keywords are strings here
        c (get @palette (str "state-" s))
        cx (+ (:x item) (:w item))
        cy (:y item)
        r STATE-R]
    ;; node-fill backing, so the node border and edges don't show through
    (.beginPath ctx)
    (if (= s "blocked")
      (.rect ctx (- cx r) (- cy r) (* 2 r) (* 2 r))
      (.arc ctx cx cy r 0 (* 2 js/Math.PI)))
    (set! (.-fillStyle ctx) (if (= s "in-progress") (:node-fill @palette) c))
    (.fill ctx)
    (set! (.-strokeStyle ctx) c)
    (set! (.-lineWidth ctx) 1.5)
    (.stroke ctx)
    (case s
      "in-progress" (do (.beginPath ctx)
                        (.moveTo ctx cx cy)
                        (.arc ctx cx cy r (* -0.5 js/Math.PI) (* 0.5 js/Math.PI))
                        (.closePath ctx)
                        (set! (.-fillStyle ctx) c)
                        (.fill ctx))
      "done" (do (.beginPath ctx)
                 (.moveTo ctx (- cx 2.5) cy)
                 (.lineTo ctx (- cx 0.5) (+ cy 2))
                 (.lineTo ctx (+ cx 2.5) (- cy 2))
                 (set! (.-strokeStyle ctx) (:node-fill @palette))
                 (.stroke ctx))
      nil)))

(defn- draw-node [ctx item sel? text?]
  (let [removed? (= (:diff item) "removed")]
    (when removed? (set! (.-globalAlpha ctx) 0.45))
    (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 6)
    (set! (.-fillStyle ctx) (:node-fill @palette))
    (.fill ctx)
    (set! (.-strokeStyle ctx) (if sel? (:accent @palette) (:node-stroke @palette)))
    (set! (.-lineWidth ctx) (if sel? 2 1))
    (.stroke ctx)
    ;; a :ref node reads as a container: a second border inside the first
    (when (:ref? item)
      (rounded-rect ctx (+ (:x item) 3) (+ (:y item) 3) (- (:w item) 6) (- (:h item) 6) 4)
      (set! (.-strokeStyle ctx) (if sel? (:accent @palette) (:sub @palette)))
      (set! (.-lineWidth ctx) 1)
      (.stroke ctx))
    (when (and text? (some? (:state item)))
      (draw-state-mark ctx item))
    (when text?
    (set! (.-textAlign ctx) "center")
    (set! (.-font ctx) NODE-FONT)
    (set! (.-fillStyle ctx) (node-color item))
    (.fillText ctx (:name item) (+ (:x item) (/ (:w item) 2)) (+ (:y item) 19))
    (when (pos? (.-length (:type item)))
      (set! (.-font ctx) SUB-FONT)
      (set! (.-fillStyle ctx) (:sub @palette))
      (.fillText ctx (str "(" (:type item) ")")
                 (+ (:x item) (/ (:w item) 2)) (+ (:y item) 35))))
    (when (some? (:diff item))
      (draw-diff-ring ctx item 8 text?))
    (when removed? (set! (.-globalAlpha ctx) 1))))

(defn- draw-arrowhead [ctx from to color]
  (let [angle (js/Math.atan2 (- (:y to) (:y from)) (- (:x to) (:x from)))
        size 8]
    (.save ctx)
    (.translate ctx (:x to) (:y to))
    (.rotate ctx angle)
    (.beginPath ctx)
    (.moveTo ctx 0 0)
    (.lineTo ctx (- size) (/ size 2.2))
    (.lineTo ctx (- size) (/ size -2.2))
    (.closePath ctx)
    (set! (.-fillStyle ctx) color)
    (.fill ctx)
    (.restore ctx)))

(defn- draw-edge [ctx item sel? detail?]
  (let [d (:diff item)
        removed? (= d "removed")
        color (if sel? (:accent @palette) (if (some? d) (diff-color d) (:edge @palette)))
        arrow-color (if (some? d) (diff-color d) (:arrow @palette))]
    (when removed?
      (set! (.-globalAlpha ctx) 0.45)
      (.setLineDash ctx [6 4]))
    (set! (.-strokeStyle ctx) color)
    (set! (.-lineWidth ctx) (if sel? 2.5 (if (some? d) 2 1.5)))
    (doseq [pts (:sections item)]
      (.beginPath ctx)
      (.moveTo ctx (:x (nth pts 0)) (:y (nth pts 0)))
      (doseq [p (rest pts)]
        (.lineTo ctx (:x p) (:y p)))
      (.stroke ctx))
    (.setLineDash ctx [])
    (let [sections (:sections item)
          first-sec (nth sections 0)
          last-sec (nth sections (dec (.-length sections)))]
      (when (and detail? (:target (:arrows item)))
        (draw-arrowhead ctx
                        (nth last-sec (- (.-length last-sec) 2))
                        (nth last-sec (dec (.-length last-sec)))
                        arrow-color))
      (when (and detail? (:source (:arrows item)))
        (draw-arrowhead ctx (nth first-sec 1) (nth first-sec 0) arrow-color)))
    (when removed? (set! (.-globalAlpha ctx) 1))))

(defn- draw-edge-label [ctx item]
  (let [removed? (= (:diff item) "removed")]
    (when removed? (set! (.-globalAlpha ctx) 0.45))
    (set! (.-textAlign ctx) "center")
    (set! (.-font ctx) SUB-FONT)
    (let [cx (+ (:x item) (/ (:w item) 2))
          cy (+ (:y item) (:h item) -3)]
      (set! (.-lineWidth ctx) 3)
      (set! (.-strokeStyle ctx) (:bg @palette))
      (.strokeText ctx (:text item) cx cy)
      (set! (.-fillStyle ctx) (:label @palette))
      (.fillText ctx (:text item) cx cy))
    (when removed? (set! (.-globalAlpha ctx) 1))))

(defn- paint-items!
  "Draw every scene item visible in the graph-space rect vr at zoom k.
  text? controls both text rendering and edge arrowheads (draw-edge's
  detail? param)."
  [ctx sc vr k selected-id text?]
  (doseq [item (:items sc)]
    (when (scene/visible? item vr)
      (let [sel? (= selected-id (:id item))]
        (case (:kind item)
          "box" (draw-box ctx item sel? text?)
          "edge" (draw-edge ctx item sel? text?)
          "edge-label" (when text? (draw-edge-label ctx item))
          "node" (draw-node ctx item sel? text?)
          nil)))))

(defn paint! [canvas-el sc2 selected-id]
  (let [ctx (.getContext canvas-el "2d")
        dpr (or (.-devicePixelRatio js/window) 1)
        pw (js/Math.round (* (.-clientWidth canvas-el) dpr))
        ph (js/Math.round (* (.-clientHeight canvas-el) dpr))]
    (when (or (not= (.-width canvas-el) pw) (not= (.-height canvas-el) ph))
      (set! (.-width canvas-el) pw)
      (set! (.-height canvas-el) ph))
    (.setTransform ctx 1 0 0 1 0 0)
    (.clearRect ctx 0 0 pw ph)
    (set! (.-fillStyle ctx) (:bg @palette))
    (.fillRect ctx 0 0 pw ph)
    (.setTransform ctx (* dpr (:k view)) 0 0 (* dpr (:k view))
                   (* dpr (:x view)) (* dpr (:y view)))
    ;; cull to the visible graph-space rect; below TEXT-MIN-PX of rendered
    ;; font height, skip text entirely (unreadable, dominates paint cost)
    (let [k (:k view)
          vr {:x0 (/ (- 0 (:x view)) k) :y0 (/ (- 0 (:y view)) k)
              :x1 (/ (- (.-clientWidth canvas-el) (:x view)) k)
              :y1 (/ (- (.-clientHeight canvas-el) (:y view)) k)}
          text? (>= (* k 11) scene/TEXT-MIN-PX)]
      (paint-items! ctx sc2 vr k selected-id text?))))

(defn export-canvas
  "Offscreen canvas with the WHOLE scene at up to 2x scale (capped so
  the larger pixel dimension stays <= 8000), on the current theme's
  background. No selection ring."
  [sc]
  (let [w (js/Math.max 1 (:width sc))
        h (js/Math.max 1 (:height sc))
        k (js/Math.min 2 (/ 8000 (js/Math.max w h)))
        cnv (js/document.createElement "canvas")
        ctx (.getContext cnv "2d")]
    (set! (.-width cnv) (js/Math.min 8000 (js/Math.ceil (* w k))))
    (set! (.-height cnv) (js/Math.min 8000 (js/Math.ceil (* h k))))
    (set! (.-fillStyle ctx) (:bg @palette))
    (.fillRect ctx 0 0 (.-width cnv) (.-height cnv))
    (.setTransform ctx k 0 0 k 0 0)
    (paint-items! ctx sc {:x0 0 :y0 0 :x1 w :y1 h} k nil true)
    cnv))

(defn export-svg
  "SVG document of the WHOLE scene, painted like export-canvas (current
  theme's background, no selection ring) into an svg/recorder, in graph
  units rather than pixels. sources are [key text] pairs to embed, as
  for the PNG."
  [sc sources]
  (let [w (js/Math.max 1 (:width sc))
        h (js/Math.max 1 (:height sc))
        rec (svg/recorder measure)]
    (paint-items! rec sc {:x0 0 :y0 0 :x1 w :y1 h} 1 nil true)
    (svg/svg-document rec {:width w :height h :background (:bg @palette)
                           :sources sources})))

(defn setup-pan-zoom! [wrap]
  (.addEventListener wrap "wheel"
    (fn [e]
      (when-not (.closest (.-target e) "#details, #banner, #collapsed-panel, #top-right, #diff-legend")
        (.preventDefault e)
        (let [factor (if (< (.-deltaY e) 0) 1.1 (/ 1 1.1))
              rect (.getBoundingClientRect wrap)
              mx (- (.-clientX e) (.-left rect))
              my (- (.-clientY e) (.-top rect))]
          (assoc! view
                  :x (- mx (* (- mx (:x view)) factor))
                  :y (- my (* (- my (:y view)) factor))
                  :k (* (:k view) factor))
          (request-paint!))))
    {:passive false})
  (let [drag (atom nil)]
    (.addEventListener wrap "pointerdown"
      (fn [e]
        (when-not (.closest (.-target e) "#details, #banner, #collapsed-panel, #top-right, #diff-legend")
          ;; NO setPointerCapture here: capturing on pointerdown retargets
          ;; the subsequent click to the wrap, so the canvas onclick
          ;; (selection) would never fire for plain clicks.
          (reset! drag {:x (.-clientX e) :y (.-clientY e)
                        :vx (:x view) :vy (:y view) :moved false
                        :pointer-id (.-pointerId e)}))))
    (.addEventListener wrap "pointermove"
      (fn [e]
        (when-let [d @drag]
          (let [dx (- (.-clientX e) (:x d))
                dy (- (.-clientY e) (:y d))]
            (when (and (not (:moved d))
                       (> (+ (js/Math.abs dx) (js/Math.abs dy)) 3))
              ;; capture only once a real drag starts; the drag-ending
              ;; click then targets the wrap, not the canvas, so it can't
              ;; accidentally select
              (swap! drag assoc :moved true)
              (.setPointerCapture wrap (:pointer-id d)))
            (assoc! view :x (+ (:vx d) dx) :y (+ (:vy d) dy))
            (request-paint!)))))
    (.addEventListener wrap "pointerup" (fn [_] (reset! drag nil)))
    (.addEventListener wrap "pointercancel" (fn [_] (reset! drag nil))))
  (.addEventListener js/window "resize" (fn [_] (request-paint!))))
