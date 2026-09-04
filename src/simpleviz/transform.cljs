(ns simpleviz.transform)

;; Builds the ELK JSON graph from a validated graph. Text measurement is
;; injected so this namespace stays DOM-free and testable.

(def NODE-FONT "bold 14px system-ui, sans-serif")
(def SUB-FONT "11px system-ui, sans-serif")

(def ^:private diff-glyphs {"added" "+" "removed" "−" "modified" "~"})

;; room inside a box: header on top, hairline elsewhere. Shared with the
;; seeding below, which derives a new box's origin from its contents.
(def ^:private BOX-PADDING {:top 40 :left 14 :bottom 14 :right 14})

(defn- padding-str [{:keys [top left bottom right]}]
  (str "[top=" top ",left=" left ",bottom=" bottom ",right=" right "]"))

(defn elk-fingerprint
  "Structural fingerprint of an ELK input graph: its serialized JSON,
  compared with = so reuse is exact (no hash collisions). The ELK input
  carries everything the layout depends on (node sizes, edge label
  sizes, hierarchy), so an equal fingerprint means a previous layout
  result can be reused. Cache entries already retain the larger layout
  object, so keeping the input text costs comparatively little."
  [elk-graph]
  (js/JSON.stringify elk-graph))

(defn to-elk [graph measure]
  (let [{:keys [nodes boxes boxes-by-name parent-of edges]} graph
        node-elk (fn [n]
                   (let [typed? (pos? (.-length (:type n)))
                         w (max (measure (:name n) NODE-FONT)
                                (if typed? (measure (str "(" (:type n) ")") SUB-FONT) 0))]
                     {:id (str "n:" (:id n))
                      :width (+ (js/Math.ceil w) 24)
                      :height (if typed? 44 30)}))
        box-elk (fn box-elk [b]
                  ;; empty boxes (e.g. compare-mode removed shells) must not
                  ;; reach ELK as childless compounds — those lay out as 0×0
                  (if (or (:collapsed b) (zero? (.-length (:components b))))
                    ;; node-style two-line label + room for the toggle button
                    (let [typed? (pos? (.-length (:type b)))
                          w (max (measure (or (:label b) (:name b)) NODE-FONT)
                                 (if typed? (measure (str "(" (:type b) ")") SUB-FONT) 0))]
                      {:id (str "b:" (:name b))
                       :width (+ (js/Math.ceil w) (if (:collapsed b) 44 24))
                       :height (if typed? 44 30)})
                    {:id (str "b:" (:name b))
                     :layoutOptions {"elk.padding" (padding-str BOX-PADDING)}
                     :children (mapv (fn [c]
                                       (if (.startsWith c "n:")
                                         (node-elk (get nodes (.slice c 2)))
                                         (box-elk (get boxes-by-name (.slice c 2)))))
                                     (:components b))}))
        root-nodes (vec (filter some?
                         (mapv (fn [n] (when (nil? (get parent-of (str "n:" (:id n))))
                                         (node-elk n)))
                               (js/Object.values nodes))))
        root-boxes (vec (filter some?
                         (mapv (fn [b] (when (nil? (get parent-of (str "b:" (:name b))))
                                         (box-elk b)))
                               boxes)))]
    {:id "root"
     :layoutOptions {"elk.algorithm" "layered"
                     "elk.direction" "RIGHT"
                     "elk.hierarchyHandling" "INCLUDE_CHILDREN"
                     "elk.layered.spacing.nodeNodeBetweenLayers" "80"
                     "elk.spacing.nodeNode" "45"
                     "elk.spacing.edgeNode" "30"
                     "elk.spacing.edgeEdge" "20"
                     "elk.edgeLabels.inline" "true"
                     "elk.padding" "[top=20,left=20,bottom=20,right=20]"}
     :children (into root-nodes root-boxes)
     :edges (mapv (fn [e]
                    (let [glyph (get diff-glyphs (:diff e))
                          parts (filterv (fn [s] (pos? (.-length s)))
                                         [(if (some? glyph) glyph "")
                                          (:name e)
                                          (if (pos? (.-length (:type e)))
                                            (str "(" (:type e) ")")
                                            "")])
                          label (.join parts " ")
                          base {:id (:id e)
                                :sources [(or (:source-id e) (str "n:" (:source e)))]
                                :targets [(or (:target-id e) (str "n:" (:target e)))]}]
                      (if (pos? (.-length label))
                        (assoc base :labels [{:text label
                                              :width (+ (js/Math.ceil (measure label SUB-FONT)) 4)
                                              :height 14}])
                        base)))
                  edges)}))

;; ---- stable relayout: seed ELK with the previous layout ----

;; ELK layered keeps an existing arrangement when its phases read
;; position hints: INTERACTIVE cycle breaking and layering use the x/y
;; on the input nodes, and semi-interactive crossing minimization keeps
;; the in-layer order given by "elk.position". Fully INTERACTIVE
;; crossing minimization would also order ports by (absent) positions
;; and route edges over the top of everything, so it stays LAYER_SWEEP;
;; node placement stays the default so the result is as compact as a
;; clean run. Compound nodes need the same options as the root.
(def ^:private interactive-options
  {"elk.layered.cycleBreaking.strategy" "INTERACTIVE"
   "elk.layered.layering.strategy" "INTERACTIVE"
   "elk.layered.crossingMinimization.semiInteractive" "true"})

(def ^:private LAYER-GAP 80)

(defn- walk-positions! [out parent ox oy]
  (doseq [c (or (:children parent) [])]
    (let [x (+ ox (:x c))
          y (+ oy (:y c))]
      (assoc! out (:id c) {:x x :y y :w (:width c) :h (:height c) :parent (:id parent)})
      (walk-positions! out c x y))))

(defn layout-positions
  "Absolute {elk-id {:x :y :w :h :parent}} of every node and box in an
  ELK layout result, whose own coordinates are parent-relative."
  [layout]
  (let [out {}]
    (walk-positions! out layout 0 0)
    out))

(defn- all-ids [parent acc]
  (doseq [c (or (:children parent) [])]
    (.push acc (:id c))
    (all-ids c acc))
  acc)

(defn seedable?
  "True when at least half of elk-graph's elements have a previous
  position — a layout of some unrelated graph (file replaced wholesale)
  is no seed, and hinting from it would scramble the result."
  [elk-graph positions]
  (let [ids (all-ids elk-graph [])
        placed (.-length (filterv (fn [id] (some? (get positions id))) ids))]
    (and (pos? (.-length ids)) (>= (* 2 placed) (.-length ids)))))

(defn- placed-at
  "c's previous box when it stayed under `parent-id`; nil when it is new
  or moved to another parent (its old position is meaningless there)."
  [c parent-id positions]
  (let [p (get positions (:id c))]
    (when (and (some? p) (= (:parent p) parent-id)) p)))

(defn- hinted
  "Hints already assigned to the elements in `kids`."
  [kids out]
  (filterv some? (mapv (fn [k] (get out (:id k))) kids)))

(defn- bbox [boxes]
  (when (pos? (.-length boxes))
    (let [x0 (apply min (mapv (fn [b] (:x b)) boxes))
          y0 (apply min (mapv (fn [b] (:y b)) boxes))
          x1 (apply max (mapv (fn [b] (+ (:x b) (:w b))) boxes))
          y1 (apply max (mapv (fn [b] (+ (:y b) (:h b))) boxes))]
      {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)})))

(defn- neighbour-hint
  "Hint for a new node so ELK's interactive layering (which groups
  nodes by overlapping x ranges) puts it next to a hinted edge
  neighbour: on the nearest hinted sibling layer right of an edge
  source, else left of an edge target, else a gap past the neighbour.
  Nil when no neighbour is hinted yet."
  [c kids edges out]
  (let [id (:id c)
        w (:width c)
        sibs (hinted kids out)
        xs (fn [pred] (mapv (fn [p] (:x p)) (filterv pred sibs)))
        next-x (fn [x0] (let [xs' (xs (fn [p] (>= (:x p) x0)))]
                          (if (pos? (.-length xs')) (apply min xs') (+ x0 LAYER-GAP))))
        prev-x (fn [x1] (let [xs' (xs (fn [p] (<= (+ (:x p) (:w p)) x1)))]
                          (if (pos? (.-length xs')) (apply max xs') (- x1 LAYER-GAP w))))
        at (fn [x p] {:x x :y (:y p) :w w :h (:height c)})]
    (or (some (fn [e]
                (when (= (nth (:targets e) 0) id)
                  (when-let [p (get out (nth (:sources e) 0))]
                    (at (next-x (+ (:x p) (:w p))) p))))
              edges)
        (some (fn [e]
                (when (= (nth (:sources e) 0) id)
                  (when-let [p (get out (nth (:targets e) 0))]
                    (at (prev-x (:x p)) p))))
              edges))))

(defn- fallback-hint
  "Hint for an element nothing else places: past the right edge of its
  hinted siblings, level with their top — never ELK's default (0,0),
  which would drop it into the leftmost layer and reverse its edges."
  [c kids out]
  (let [sibs (hinted kids out)
        b (bbox sibs)]
    {:x (if (some? b) (+ (:x b) (:w b) LAYER-GAP) 0)
     :y (if (some? b) (:y b) 0)
     :w (or (:width c) 0)
     :h (or (:height c) 0)}))

(defn- hint-children!
  "Absolute hint for every element under `parent` into `out`, children
  before parents so a new box can sit over its contents: a kept
  position, else a new box over its hinted children, else a new node
  beside a hinted neighbour (repeated so chains of new nodes resolve),
  else the fallback past the siblings."
  [out parent edges positions]
  (let [pid (:id parent)
        kids (:children parent)
        unhinted (fn [] (filterv (fn [c] (nil? (get out (:id c)))) kids))]
    (doseq [c kids]
      (when-let [p (placed-at c pid positions)] (assoc! out (:id c) p))
      (when (some? (:children c)) (hint-children! out c edges positions)))
    (doseq [c (unhinted)]
      (when (some? (:children c))
        (when-let [b (bbox (hinted (:children c) out))]
          (assoc! out (:id c) {:x (- (:x b) (:left BOX-PADDING))
                               :y (- (:y b) (:top BOX-PADDING))
                               :w (+ (:w b) (:left BOX-PADDING) (:right BOX-PADDING))
                               :h (+ (:h b) (:top BOX-PADDING) (:bottom BOX-PADDING))}))))
    (loop []
      (let [before (.-length (unhinted))]
        (doseq [c (unhinted)]
          (when (nil? (:children c))
            (when-let [h (neighbour-hint c kids edges out)] (assoc! out (:id c) h))))
        (when (< (.-length (unhinted)) before) (recur))))
    (doseq [c (unhinted)]
      (assoc! out (:id c) (fallback-hint c kids out)))))

(defn- apply-hints
  "c with its hint as x/y relative to the parent's absolute `origin`
  plus the matching elk.position; boxes also get the interactive
  strategies and recurse."
  [c origin out]
  (let [h (get out (:id c))
        x (- (:x h) (:x origin))
        y (- (:y h) (:y origin))
        c' (assoc c :x x :y y
                  :layoutOptions (merge (:layoutOptions c)
                                        {"elk.position" (str "(" x "," y ")")}))]
    (if (some? (:children c))
      (assoc c'
             :layoutOptions (merge (:layoutOptions c') interactive-options)
             :children (mapv (fn [k] (apply-hints k h out)) (:children c)))
      c')))

(defn seed-layout
  "Copy of `elk-graph` carrying the previous layout's positions (see
  layout-positions) as parent-relative x/y hints plus the INTERACTIVE
  strategies, so a relayout after an edit keeps the existing layers and
  ordering. Elements new since that layout (or moved to another box)
  are hinted next to a placed neighbour, over their contents, or past
  their siblings — see hint-children!."
  [elk-graph positions]
  (let [out {}]
    (hint-children! out elk-graph (:edges elk-graph) positions)
    (assoc elk-graph
           :layoutOptions (merge (:layoutOptions elk-graph) interactive-options)
           :children (mapv (fn [c] (apply-hints c {:x 0 :y 0} out))
                           (:children elk-graph)))))
