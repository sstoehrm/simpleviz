(ns simpleviz.format)

;; Inspector attribute value -> hiccup (issue #32). Strings stay plain;
;; vectors become bullet lists; maps become lists enumerated by their
;; keys; scalars stringify; nesting recurses. Pure — no DOM — so the
;; node tests can cover it.

(defn basename [p]
  (let [i (.lastIndexOf p "/")]
    (if (neg? i) p (.slice p (inc i)))))

(defn tab-title
  "Browser tab title for the loaded graph: the served file's name, in
  compare mode \"old → new\" (basenames), plain \"simpleviz\" before a
  graph is loaded."
  [g]
  (cond
    (some? (:compare g)) (str (basename (:old (:compare g))) " → "
                              (basename (:new (:compare g))) " — simpleviz")
    (some? (:file g)) (str (:file g) " — simpleviz")
    :else "simpleviz"))

(defn value->hiccup [v]
  (cond
    (string? v) v
    (nil? v) "—"
    (vector? v) (into [:ul {:class "dd-list"}]
                      (map-indexed
                       (fn [i x] [:li {:key (str i)} (value->hiccup x)])
                       v))
    (map? v) (into [:ol {:class "dd-map"}]
                   (map (fn [[k x]]
                          [:li {:key k}
                           [:span {:class "dd-map-key"} (str k ":")] " "
                           (value->hiccup x)])
                        (js/Object.entries v)))
    :else (str v)))

;; attrs already represented visually (endpoints/arrow on the canvas,
;; membership by containment) stay out of the inspector and the tooltip
(def ^:private hidden-attrs
  {"edge" #{"nodes" "direction"}
   "box" #{"components"}})

(defn attr->text
  "The one-line form of attribute `k`'s value when it has one: a :grid
  cell of numbers reads as written in the file, \"[1 0]\". Nil for
  everything else."
  [k v]
  (when (and (= k "grid") (vector? v) (pos? (count v)) (every? number? v))
    (str "[" (.join v " ") "]")))

(defn attr->hiccup
  "Attribute `k`'s value for the inspector and tooltip: attr->text when
  it has one, else value->hiccup."
  [k v]
  (or (attr->text k v) (value->hiccup v)))

(defn visible-attrs
  "The [key value] attr pairs to show for a selection or scene item —
  anything with :kind and :attrs."
  [sel]
  (let [hidden (get hidden-attrs (:kind sel))]
    (filterv (fn [[k _]] (not (and (some? hidden) (.has hidden k))))
             (js/Object.entries (or (:attrs sel) {})))))
