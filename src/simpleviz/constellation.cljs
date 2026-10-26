(ns simpleviz.constellation)

;; The load screen's mark: tiny triangles strewn over a brain seen from
;; the side — knowledge as many small points, not one block. Drawn from a
;; seed, so the same seed gives the same constellation every time.

(def W "The view box's width." 240)
(def H "The view box's height." 170)

;; [cx cy r]: frontal, parietal, occipital and temporal lobes, the
;; cerebellum, the stem — each well inside the view box
(def ^:private LOBES
  [[72 78 50] [128 60 46] [178 84 40] [124 108 38] [180 128 24] [150 148 9]])

(def ^:private CEREBELLUM (nth LOBES 4))

(def ^:private COUNT 420)

(defn- rng
  "A Park-Miller generator from seed (a positive integer): (fn []) -> a
  number in [0, 1)."
  [seed]
  (let [s (atom (inc (js-mod seed 2147483646)))]
    (fn []
      (swap! s (fn [v] (js-mod (* v 16807) 2147483647)))
      (/ (dec @s) 2147483646))))

(defn- dist [x y [cx cy]]
  (js/Math.hypot (- x cx) (- y cy)))

(defn- depth
  "How far inside the outline x, y lies: negative outside."
  [x y]
  (reduce (fn [m [_ _ r :as lobe]] (js/Math.max m (- r (dist x y lobe))))
          (- js/Infinity) LOBES))

(defn- groove?
  "On the lateral fissure above the temporal lobe, or the cleft between
  cerebrum and cerebellum: left empty, so the shape reads."
  [x y]
  (or (and (< 92 x 168) (< (js/Math.abs (- y (- 100 (* 0.17 (- x 92))))) 3))
      (and (< y 136) (< (js/Math.abs (- (dist x y CEREBELLUM) (nth CEREBELLUM 2))) 2.5))))

(defn- keep-chance
  "How likely a point at x, y is kept: every point near the outline, the
  inside banded like the brain's folds, nothing outside or in a groove."
  [x y]
  (let [d (depth x y)]
    (cond (or (neg? d) (groove? x y)) 0
          (< d 5) 1
          :else (+ 0.1 (* 0.9 (js/Math.abs (js/Math.sin (+ (* x 0.09) (* 2 (js/Math.sin (* y 0.07)))))))))))

(defn- tone
  "Mostly the accent, then white, amber and three more sparks."
  [r]
  (cond (< r 0.42) 0 (< r 0.6) 1 (< r 0.72) 2 (< r 0.82) 3 (< r 0.9) 4 :else 5))

(defn- round1 [v] (/ (js/Math.round (* v 10)) 10))

(defn particles
  "The constellation of `seed`: COUNT triangles, each {:points [[x y] ×3]
  :tone 0-5 (which color, see style.css .cs-*) :twinkle 0-3 (0 holds
  still)}, in a W×H view box."
  [seed]
  (let [rnd (rng seed)]
    (loop [out []]
      (if (>= (count out) COUNT)
        out
        (let [x (* W (rnd))
              y (* H (rnd))]
          (if (< (rnd) (keep-chance x y))
            (let [r (rnd)
                  s (+ 0.8 (* 2.2 r r r))
                  a (* 2 js/Math.PI (rnd))
                  points (mapv (fn [k]
                                 (let [t (+ a (/ (* 2 js/Math.PI k) 3))]
                                   [(round1 (+ x (* s (js/Math.cos t))))
                                    (round1 (+ y (* s (js/Math.sin t))))]))
                               [0 1 2])]
              (recur (conj out {:points points
                                :tone (tone (rnd))
                                :twinkle (js/Math.floor (* 4 (rnd)))})))
            (recur out)))))))
