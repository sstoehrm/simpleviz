(ns simpleviz.canvas
  (:require [simpleviz.scene :as scene]
            [simpleviz.transform :refer [NODE-FONT SUB-FONT]]))

;; HiDPI canvas painter + view state + pan/zoom. DOM-only namespace —
;; never imported by node tests.

(def ^:private measure-ctx
  (.getContext (js/document.createElement "canvas") "2d"))

(defn measure [text font]
  (set! (.-font measure-ctx) font)
  (.-width (.measureText measure-ctx text)))

(def ACCENT "#2563eb")

(def ^:private TEXT-MIN-PX 4.5)

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

(defn- rounded-rect [ctx x y w h r]
  (.beginPath ctx)
  (.roundRect ctx x y w h r))

(defn- draw-box [ctx item sel? text?]
  (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 10)
  (set! (.-fillStyle ctx) (:fill item))
  (.fill ctx)
  (set! (.-strokeStyle ctx) (if sel? ACCENT (:border item)))
  (set! (.-lineWidth ctx) (if sel? 2 1))
  (.stroke ctx)
  (when text?
  (set! (.-textAlign ctx) "left")
  (set! (.-font ctx) "bold 13px system-ui, sans-serif")
  (set! (.-fillStyle ctx) (:border item))
  (.fillText ctx (:name item) (+ (:x item) 12) (+ (:y item) 20))
  (when (pos? (.-length (:type item)))
    (let [nw (.-width (.measureText ctx (:name item)))]
      (set! (.-font ctx) SUB-FONT)
      (set! (.-fillStyle ctx) "#888")
      (.fillText ctx (str "(" (:type item) ")")
                 (+ (:x item) 12 nw 5) (+ (:y item) 20))))))

(defn- draw-node [ctx item sel? text?]
  (rounded-rect ctx (:x item) (:y item) (:w item) (:h item) 6)
  (set! (.-fillStyle ctx) "#fff")
  (.fill ctx)
  (set! (.-strokeStyle ctx) (if sel? ACCENT "#ddd"))
  (set! (.-lineWidth ctx) (if sel? 2 1))
  (.stroke ctx)
  (when text?
  (set! (.-textAlign ctx) "center")
  (set! (.-font ctx) NODE-FONT)
  (set! (.-fillStyle ctx) (:color item))
  (.fillText ctx (:name item) (+ (:x item) (/ (:w item) 2)) (+ (:y item) 19))
  (when (pos? (.-length (:type item)))
    (set! (.-font ctx) SUB-FONT)
    (set! (.-fillStyle ctx) "#888")
    (.fillText ctx (str "(" (:type item) ")")
               (+ (:x item) (/ (:w item) 2)) (+ (:y item) 35)))))

(defn- draw-arrowhead [ctx from to]
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
    (set! (.-fillStyle ctx) "#555")
    (.fill ctx)
    (.restore ctx)))

(defn- draw-edge [ctx item sel? detail?]
  (set! (.-strokeStyle ctx) (if sel? ACCENT "#555"))
  (set! (.-lineWidth ctx) (if sel? 2.5 1.5))
  (doseq [pts (:sections item)]
    (.beginPath ctx)
    (.moveTo ctx (:x (nth pts 0)) (:y (nth pts 0)))
    (doseq [p (rest pts)]
      (.lineTo ctx (:x p) (:y p)))
    (.stroke ctx))
  (let [sections (:sections item)
        first-sec (nth sections 0)
        last-sec (nth sections (dec (.-length sections)))]
    (when (and detail? (:target (:arrows item)))
      (draw-arrowhead ctx
                      (nth last-sec (- (.-length last-sec) 2))
                      (nth last-sec (dec (.-length last-sec)))))
    (when (and detail? (:source (:arrows item)))
      (draw-arrowhead ctx (nth first-sec 1) (nth first-sec 0)))))

(defn- draw-edge-label [ctx item]
  (set! (.-textAlign ctx) "center")
  (set! (.-font ctx) SUB-FONT)
  (let [cx (+ (:x item) (/ (:w item) 2))
        cy (+ (:y item) (:h item) -3)]
    (set! (.-lineWidth ctx) 3)
    (set! (.-strokeStyle ctx) "#fafafa")
    (.strokeText ctx (:text item) cx cy)
    (set! (.-fillStyle ctx) "#444")
    (.fillText ctx (:text item) cx cy)))

(defn paint! [canvas-el sc2 selected-id]
  (let [ctx (.getContext canvas-el "2d")
        dpr (or (.-devicePixelRatio js/window) 1)
        pw (js/Math.round (* (.-clientWidth canvas-el) dpr))
        ph (js/Math.round (* (.-clientHeight canvas-el) dpr))]
    (when (or (not= (.-width canvas-el) pw) (not= (.-height canvas-el) ph))
      (set! (.-width canvas-el) pw)
      (set! (.-height canvas-el) ph))
    (.setTransform ctx 1 0 0 1 0 0)
    (set! (.-fillStyle ctx) "#fafafa")
    (.fillRect ctx 0 0 pw ph)
    (.setTransform ctx (* dpr (:k view)) 0 0 (* dpr (:k view))
                   (* dpr (:x view)) (* dpr (:y view)))
    ;; cull to the visible graph-space rect; below TEXT-MIN-PX of rendered
    ;; font height, skip text entirely (unreadable, dominates paint cost)
    (let [k (:k view)
          vr {:x0 (/ (- 0 (:x view)) k) :y0 (/ (- 0 (:y view)) k)
              :x1 (/ (- (.-clientWidth canvas-el) (:x view)) k)
              :y1 (/ (- (.-clientHeight canvas-el) (:y view)) k)}
          text? (>= (* k 11) TEXT-MIN-PX)]
      (doseq [item (:items sc2)]
        (when (scene/visible? item vr)
          (let [sel? (= selected-id (:id item))]
            (case (:kind item)
              "box" (draw-box ctx item sel? text?)
              "edge" (draw-edge ctx item sel? text?)
              "edge-label" (when text? (draw-edge-label ctx item))
              "node" (draw-node ctx item sel? text?)
              nil)))))))

(defn setup-pan-zoom! [wrap]
  (.addEventListener wrap "wheel"
    (fn [e]
      (when-not (.closest (.-target e) "#details, #banner")
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
        (when-not (.closest (.-target e) "#details, #banner")
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
