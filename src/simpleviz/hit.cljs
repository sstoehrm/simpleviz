(ns simpleviz.hit
  (:require [simpleviz.scene :as scene]
            [simpleviz.format :refer [visible-attrs]]))

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

(defn- near-bbox?
  "Could p be within tol of an item in bounding box bb? True without one
  (hand-built scenes)."
  [p bb tol]
  (or (nil? bb)
      (and (<= (- (:x0 bb) tol) (:x p) (+ (:x1 bb) tol))
           (<= (- (:y0 bb) tol) (:y p) (+ (:y1 bb) tol)))))

;; hit-test runs on every pointermove: a scene's items split by kind once,
;; not per event (scenes are replaced, never changed in place)
(def ^:private parts-cache (js/WeakMap.))

(defn- parts [scene]
  (or (.get parts-cache scene)
      (let [nodes [] edges [] labels [] boxes []
            edge-by-id (js/Map.)
            labeled (js/Set.)]
        (doseq [it (:items scene)]
          (case (:kind it)
            "node" (.push nodes it)
            "edge" (do (.push edges it) (.set edge-by-id (:id it) it))
            "edge-label" (do (.push labels it) (.add labeled (:edge-id it)))
            "box" (.push boxes it)
            nil))
        (let [v {:nodes nodes :edges edges :labels labels :boxes-rev (.reverse boxes)
                 :labeled labeled :edge-by-id edge-by-id}]
          (.set parts-cache scene v)
          v))))

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

(defn hover-title
  "Tooltip text for a hit item: the id you would reference in the EDN
  file (edges as their [source target] key), nil for no item."
  [item]
  (case (:kind item)
    "node" (.slice (:id item) 2)
    "box" (.slice (:id item) 2)
    "collapse-button" (.slice (:box-id item) 2)
    "edge" (str "[" (:source item) " " (:target item) "]")
    nil))

(defn hover-tip
  "Tooltip model for a hit item: {:title name :attrs [[k v] ..]}, nil for
  no item. The display name heads it and so leaves the attrs; an item
  without one (unnamed edge, collapse button) falls back to its
  hover-title."
  [item]
  (let [t (hover-title item)
        nm (:name item)]
    (if (some? t)
      {:title (if (and (string? nm) (pos? (.-length nm))) nm t)
       ;; an element's :text is shown inside it already
       :attrs (filterv (fn [[k _]] (and (not= k "name")
                                        (not (and (= k "text") (seq (:text-lines item))))))
                       (visible-attrs item))}
      nil)))

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
  (let [{:keys [nodes edges labels boxes-rev labeled edge-by-id]} (parts scene)]
    (or (some (fn [it] (when (in-rect? p (:x it) (:y it) (:w it) (:h it)) it))
              nodes)
        (some (fn [l] (when (in-rect? p (- (:x l) 3) (- (:y l) 3)
                                      (+ (:w l) 6) (+ (:h l) 6))
                        (.get edge-by-id (:edge-id l))))
              labels)
        (some (fn [it] (when (and (not (.has labeled (:id it)))
                                  (near-bbox? p (:bbox it) tol)
                                  (near-sections? p (:sections it) tol))
                         it))
              edges)
        (let [btn? (>= (* k 11) scene/TEXT-MIN-PX)]
          (some (fn [it]
                  (or (when (and btn?
                                 (not (:empty it))
                                 (in-rect? p
                                           (- (+ (:x it) (:w it)) scene/HIDE-BTN-RIGHT)
                                           (+ (:y it) scene/HIDE-BTN-TOP)
                                           scene/HIDE-BTN-SIZE scene/HIDE-BTN-SIZE))
                        {:kind "collapse-button" :box-id (:id it)})
                      (when (box-hit? p it) it)))
                boxes-rev))))))

(defn ids-in-rect
  "The ids of the nodes and boxes wholly inside the graph-space rect
  spanned by (x0, y0) and (x1, y1), in draw order; the ghosts of
  removed elements (compare mode) are not there to select."
  [scene {:keys [x0 y0 x1 y1]}]
  (let [lx (min x0 x1) hx (max x0 x1) ly (min y0 y1) hy (max y0 y1)]
    (vec (keep (fn [it]
                 (when (and (contains? #{"node" "box"} (:kind it))
                            (not= "removed" (:diff it))
                            (<= lx (:x it)) (<= (+ (:x it) (:w it)) hx)
                            (<= ly (:y it)) (<= (+ (:y it) (:h it)) hy))
                   (:id it)))
               (:items scene)))))
