(ns simpleviz.themes-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [themes]))

;; WCAG contrast, to hold every built-in to a legibility bar: none may be
;; less legible than the light theme the page shipped with before themes

(def COLOR-RE
  (js/RegExp. "^(#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|(rgba?|hsla?)\\([^()]*\\))$"))

(defn- hex->rgb [h]
  (let [s (.slice h 1)
        s (if (= 3 (.-length s)) (.join (mapv (fn [c] (str c c)) (.split s "")) "") s)]
    (mapv (fn [i] (js/parseInt (.slice s i (+ i 2)) 16)) [0 2 4])))

(defn- hsl->rgb [h s l]
  (let [s (/ s 100)
        l (/ l 100)
        a (* s (js/Math.min l (- 1 l)))
        f (fn [n]
            (let [k (js-mod (+ n (/ h 30)) 12)]
              (js/Math.round
               (* 255 (- l (* a (js/Math.max -1 (js/Math.min (- k 3) (- 9 k) 1))))))))]
    [(f 0) (f 8) (f 4)]))

(defn- luminance [rgb]
  (let [[r g b] (mapv (fn [v]
                        (let [v (/ v 255)]
                          (if (<= v 0.03928)
                            (/ v 12.92)
                            (js/Math.pow (/ (+ v 0.055) 1.055) 2.4))))
                      rgb)]
    (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))

(defn- contrast [a b]
  (let [la (luminance a)
        lb (luminance b)]
    (/ (+ (js/Math.max la lb) 0.05) (+ (js/Math.min la lb) 0.05))))

;; the 255 golden-angle hues of simpleviz.colors
(def HUES (mapv (fn [i] (js-mod (* i 137.508) 360)) (range 255)))

(defn- min-type-contrast
  "The worst contrast of any type hue at saturation s, lightness l on rgb."
  [s l against]
  (reduce (fn [m h] (js/Math.min m (contrast (hsl->rgb h s l) against)))
          js/Infinity HUES))

(defn- kind-ok? [kind v]
  (case kind
    :color (and (string? v) (.test COLOR-RE v))
    :percent (and (number? v) (<= 0 v 100))
    :alpha (and (number? v) (<= 0 v 1))
    false))

(defn- sorted [xs] (vec (sort (vec xs))))

(test "NAMES lists each built-in theme once"
  (fn []
    (assert/equal (count themes/NAMES) 12)
    (assert/deepEqual (sorted (js/Object.keys themes/THEMES)) (sorted themes/NAMES))))

(test "KEYS and KEY-KINDS agree; CSS-KEYS are theme keys"
  (fn []
    (assert/equal (count themes/KEYS) 34)
    (assert/deepEqual (sorted (js/Object.keys themes/KEY-KINDS)) (sorted themes/KEYS))
    (assert/equal (count themes/CSS-KEYS) 16)
    (doseq [k themes/CSS-KEYS]
      (assert/ok (contains? themes/KEY-KINDS k) k))))

(test "every theme defines exactly KEYS, each with a value of its kind"
  (fn []
    (doseq [n themes/NAMES]
      (let [t (get themes/THEMES n)]
        (assert/deepEqual (sorted (js/Object.keys t)) (sorted themes/KEYS) n)
        (doseq [k themes/KEYS]
          (assert/ok (kind-ok? (get themes/KEY-KINDS k) (get t k))
                     (str n " " k " = " (get t k))))))))

(test "no built-in is less legible than the pre-themes light theme"
  (fn []
    (doseq [n themes/NAMES]
      (let [t (get themes/THEMES n)
            rgb (fn [k] (hex->rgb (get t k)))]
        (assert/ok (>= (min-type-contrast (:node-saturation t) (:node-lightness t) (rgb :node-fill)) 2.75)
                   (str n ": node type colors on :node-fill"))
        (assert/ok (>= (min-type-contrast (:box-saturation t) (:box-lightness t) (rgb :bg)) 1.8)
                   (str n ": box titles on :bg"))
        (assert/ok (>= (contrast (rgb :text) (rgb :panel)) 4.5) (str n ": :text on :panel"))
        (assert/ok (>= (contrast (rgb :label) (rgb :bg)) 4.5) (str n ": :label on :bg"))
        (assert/ok (>= (contrast (rgb :on-accent) (rgb :accent)) 2.5) (str n ": :on-accent on :accent"))))))

(test "high-contrast type colors reach 4.5:1"
  (fn []
    (let [t (get themes/THEMES :high-contrast)]
      (assert/ok (>= (min-type-contrast (:node-saturation t) (:node-lightness t) (hex->rgb (:node-fill t))) 4.5))
      (assert/ok (>= (min-type-contrast (:box-saturation t) (:box-lightness t) (hex->rgb (:bg t))) 4.5)))))
