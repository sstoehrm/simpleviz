(ns simpleviz.colors)

;; Fixed 255-entry color tables. Entry i uses hue i * golden angle, so
;; ADJACENT indices are visually distinct — that makes linear probing on
;; hash collision a safe "next best" choice.

(def TABLE-SIZE 255)
(def GOLDEN-ANGLE 137.508)

(defn fnv1a [s]
  (loop [i 0
         h 0x811c9dc5]
    (if (< i (.-length s))
      (recur (inc i)
             (js/Math.imul (bit-xor h (.charCodeAt s i)) 0x01000193))
      (unsigned-bit-shift-right h 0))))

(defn- hue [i]
  (.toFixed (js-mod (* i GOLDEN-ANGLE) 360) 1))

(def NODE-TABLE
  (mapv (fn [i] (str "hsl(" (hue i) " 65% 38%)")) (range TABLE-SIZE)))

(def BOX-TABLE
  (mapv (fn [i] {:border (str "hsl(" (hue i) " 45% 55%)")
                 :fill (str "hsl(" (hue i) " 45% 55% / 0.1)")})
        (range TABLE-SIZE)))

(def NEUTRAL-NODE "hsl(0 0% 40%)")
(def NEUTRAL-BOX {:border "hsl(0 0% 65%)" :fill "hsl(0 0% 65% / 0.1)"})

(defn assign-indices [types]
  (let [sorted (sort (js/Array.from
                      (js/Set. (filterv (fn [t] (and t (pos? (.-length t)))) types))))
        taken (js/Set.)]
    (reduce (fn [acc t]
              (let [start (js-mod (fnv1a t) TABLE-SIZE)]
                (if (>= (.-size taken) TABLE-SIZE)
                  (assoc acc t start)
                  (loop [idx start]
                    (if (.has taken idx)
                      (recur (js-mod (inc idx) TABLE-SIZE))
                      (do (.add taken idx)
                          (assoc acc t idx)))))))
            {}
            sorted)))

(defn color-map [types table]
  (reduce (fn [acc [t i]] (assoc acc t (nth table i)))
          {}
          (js/Object.entries (assign-indices types))))
