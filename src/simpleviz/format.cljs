(ns simpleviz.format)

;; Inspector attribute value -> hiccup (issue #32). Strings stay plain;
;; vectors become bullet lists; maps become lists enumerated by their
;; keys; scalars stringify; nesting recurses. Pure — no DOM — so the
;; node tests can cover it.

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
