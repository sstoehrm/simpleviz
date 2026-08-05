(ns simpleviz.hit)

;; Pure hit-testing over the scene display list. All coordinates in graph
;; space; convert mouse coordinates with client->graph first. No DOM.

(defn client->graph [view mx my]
  {:x (/ (- mx (:x view)) (:k view))
   :y (/ (- my (:y view)) (:k view))})

(defn- in-rect? [p x y w h]
  (and (>= (:x p) x) (<= (:x p) (+ x w))
       (>= (:y p) y) (<= (:y p) (+ y h))))

(defn- dist-to-segment [p a b]
  (let [dx (- (:x b) (:x a))
        dy (- (:y b) (:y a))
        len2 (+ (* dx dx) (* dy dy))
        t (if (zero? len2)
            0
            (js/Math.max 0 (js/Math.min 1 (/ (+ (* (- (:x p) (:x a)) dx)
                                                (* (- (:y p) (:y a)) dy))
                                             len2))))
        cx (+ (:x a) (* t dx))
        cy (+ (:y a) (* t dy))]
    (js/Math.hypot (- (:x p) cx) (- (:y p) cy))))

(defn- near-sections? [p sections tol]
  (boolean
   (some (fn [pts]
           (some (fn [i]
                   (<= (dist-to-segment p (nth pts i) (nth pts (inc i))) tol))
                 (range (dec (.-length pts)))))
         sections)))

(defn- box-hit?
  "Header strip or 4px border band only — never the interior content area."
  [p item]
  (let [{:keys [x y w h title-h]} item]
    (and (in-rect? p x y w h)
         (or (<= (:y p) (+ y title-h))
             (<= (:x p) (+ x 4))
             (>= (:x p) (- (+ x w) 4))
             (>= (:y p) (- (+ y h) 4))))))

(defn hit-test
  "Returns the hit scene item or nil. Priority: nodes, then edges within
  tol, then boxes innermost-first (reverse draw order)."
  [scene p tol]
  (let [items (:items scene)
        by-kind (fn [k] (filterv (fn [it] (= (:kind it) k)) items))]
    (or (some (fn [it] (when (in-rect? p (:x it) (:y it) (:w it) (:h it)) it))
              (by-kind "node"))
        (some (fn [it] (when (near-sections? p (:sections it) tol) it))
              (by-kind "edge"))
        (some (fn [it] (when (box-hit? p it) it))
              (reverse (by-kind "box"))))))
