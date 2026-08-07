(ns simpleviz.hit
  (:require [simpleviz.scene :as scene]))

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
         (or (:collapsed item)
             (<= (:y p) (+ y title-h))
             (<= (:x p) (+ x 4))
             (>= (:x p) (- (+ x w) 4))
             (>= (:y p) (- (+ y h) 4))))))

(defn hit-test
  "Returns the hit scene item or nil. Priority: nodes, then edge labels
  (resolving to their edge), then label-less edges within tol of their
  line, then boxes innermost-first (reverse draw order). Labeled edges
  are deliberately NOT selectable via their line. A box's collapse button
  (drawn only when text is legible, see scene/TEXT-MIN-PX) yields
  {:kind \"hide-button\" :box-id ..}. The 3-arity derives the zoom from
  tol (the app passes tol = 8/k)."
  ([scene p tol] (hit-test scene p tol (/ 8 tol)))
  ([scene p tol k]
  (let [items (:items scene)
        by-kind (fn [k] (filterv (fn [it] (= (:kind it) k)) items))
        edges (by-kind "edge")
        labels (by-kind "edge-label")
        ;; looked up lazily on an actual label hit — building an id map on
        ;; every click is the dominant cost at 10k edges
        edge-of (fn [id] (some (fn [e] (when (= (:id e) id) e)) edges))
        labeled (js/Set. (mapv (fn [l] (:edge-id l)) labels))]
    (or (some (fn [it] (when (in-rect? p (:x it) (:y it) (:w it) (:h it)) it))
              (by-kind "node"))
        (some (fn [l] (when (in-rect? p (- (:x l) 3) (- (:y l) 3)
                                      (+ (:w l) 6) (+ (:h l) 6))
                        (edge-of (:edge-id l))))
              labels)
        (some (fn [it] (when (and (not (.has labeled (:id it)))
                                  (near-sections? p (:sections it) tol))
                         it))
              edges)
        (let [btn? (>= (* k 11) scene/TEXT-MIN-PX)]
          (some (fn [it]
                  (or (when (and btn?
                                 (in-rect? p
                                           (- (+ (:x it) (:w it)) scene/HIDE-BTN-RIGHT)
                                           (+ (:y it) scene/HIDE-BTN-TOP)
                                           scene/HIDE-BTN-SIZE scene/HIDE-BTN-SIZE))
                        {:kind "collapse-button" :box-id (:id it)})
                      (when (box-hit? p it) it)))
                (reverse (by-kind "box"))))))))
