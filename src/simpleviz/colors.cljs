(ns simpleviz.colors)

;; Fixed 255-slot hue tables. Slot i uses hue i * golden angle, so
;; ADJACENT indices are visually distinct — that makes linear probing on
;; hash collision a safe "next best" choice. A theme sets the tables'
;; saturation and lightness only, so a type keeps its hue in every theme.

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

(defn- hsl
  ([h s l] (str "hsl(" h " " s "% " l "%)"))
  ([h s l a] (str "hsl(" h " " s "% " l "% / " a ")")))

(defn tables
  "A theme's type colors: :node, 255 name colors; :box, 255
  {:border :fill}; :neutral-node and :neutral-box for untyped elements."
  [theme]
  (let [a (:box-fill-alpha theme)
        box (fn [h s l] {:border (hsl h s l) :fill (hsl h s l a)})]
    {:node (mapv (fn [i] (hsl (hue i) (:node-saturation theme) (:node-lightness theme)))
                 (range TABLE-SIZE))
     :box (mapv (fn [i] (box (hue i) (:box-saturation theme) (:box-lightness theme)))
                (range TABLE-SIZE))
     :neutral-node (hsl 0 0 (:neutral-node-lightness theme))
     :neutral-box (box 0 0 (:neutral-box-lightness theme))}))

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
