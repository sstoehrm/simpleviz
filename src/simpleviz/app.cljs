(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.format :as format]
            [simpleviz.transform :refer [to-elk elk-fingerprint layout-positions seed-layout seedable?]]
            [simpleviz.prune :refer [collapse-boxes collapse-scene]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]
            [simpleviz.png :as png]
            [simpleviz.editor :as editor]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

(def state (atom {:error nil :warnings [] :graph nil :layout nil
                  :colors nil :selected nil :collapsed false
                  :collapsed-boxes #{} :layouting false
                  :diff-cursors {}
                  :edit-target "new" :edit-error nil :editing nil
                  :pick nil :pick-hint nil
                  :id-entry nil :pending-focus nil
                  :help false :disconnected false
                  :theme (or (js/localStorage.getItem "simpleviz-theme")
                             (if (.-matches (js/window.matchMedia
                                             "(prefers-color-scheme: dark)"))
                               "dark"
                               "light"))}))
(def last-mtime (atom nil))

(defn- on-select [payload]
  (swap! state assoc :selected payload :editing nil :id-entry nil))

(defn- start-pick! [pick hint]
  (swap! state assoc :pick pick :pick-hint hint))

(defn- cancel-pick! []
  (swap! state assoc :pick nil :pick-hint nil))

(defn- start-id-entry! [for-kind]
  (swap! state assoc :id-entry {:for for-kind :text ""}))

(defn- cancel-id-entry! []
  (swap! state assoc :id-entry nil))

(declare relayout! post-edit! delete! current-edit-target-editable?)

;; layouts per collapsed-set, so expanding (or re-collapsing a seen
;; combination) is instant instead of a multi-second ELK run; demoted
;; (not cleared) on file reload — see demote-layout-cache!
(def ^:private layout-cache (js/Map.))

;; bumped on every fresh graph from the server; an async relayout
;; captures it at start and discards its result (no state apply, no
;; cache write) if a reload superseded it mid-flight — otherwise a slow
;; pre-edit ELK run resolving after an instant fingerprint-reuse
;; relayout would overwrite the fresh scene and poison the cache
(def ^:private graph-gen (atom 0))

(defn- cache-key [collapsed]
  (.join (.sort (js/Array.from collapsed)) "|"))

(defn- demote-layout-cache!
  "The file changed: cached colors/scenes are stale, but each layout
  stays reusable — relayout! skips the ELK run when the new structural
  fingerprint still matches (an attribute-only edit) and otherwise
  seeds the next run from it. Keep only fingerprint + layout per entry;
  dropping :scene routes the next relayout! through the rebuild path.
  An entry that is still demoted from the previous file version was
  never revisited in a whole version — delete it, so stale layouts are
  retained for at most one version window. The current collapsed-set's
  entry is exempt: it is the seed of the relayout this reload triggers,
  possibly still demoted only because the previous one is in flight."
  []
  (let [current (cache-key (:collapsed-boxes @state))]
    (doseq [k (js/Array.from (.keys layout-cache))]
      (let [e (.get layout-cache k)]
        (cond (some? (:scene e))
              (.set layout-cache k {:fingerprint (:fingerprint e)
                                    :layout (:layout e)})
              (not= k current)
              (.delete layout-cache k))))))

(defn- collapse-box! [box-name]
  (swap! state (fn [st]
                 (let [collapsed (conj (:collapsed-boxes st) box-name)]
                   (assoc st
                          :collapsed-boxes collapsed
                          :selected nil
                          :diff-cursors {}
                          ;; instant feedback: empty the shell right away
                          ;; (boundary edges snap to it on the re-layout)
                          :scene (if (some? (:scene st))
                                   (collapse-scene (:scene st) (:graph st) collapsed)
                                   (:scene st))))))
  (relayout!))

(defn- expand-box! [box-name]
  (swap! state (fn [st] (assoc st :collapsed-boxes (disj (:collapsed-boxes st) box-name)
                               :selected nil)))
  (relayout!))

(defn- toggle-collapse! [box-name]
  (if (contains? (:collapsed-boxes @state) box-name)
    (expand-box! box-name)
    (collapse-box! box-name)))

(defn- yield-paint!
  "Resolves after the browser painted the current DOM/canvas state —
  lets the pruned scene and indicator show before ELK blocks the thread."
  []
  (js/Promise. (fn [res]
                 (js/requestAnimationFrame
                  (fn [_] (js/setTimeout res 0))))))

(defn- collapsed-view [st]
  (let [collapsed (:collapsed-boxes st)]
    (when (pos? (.-size collapsed))
      [:aside {:id "collapsed-panel"}
       [:div {:class "cp-header"}
        (str "Collapsed boxes (" (.-size collapsed) ")")]
       (into [:div {:class "cp-list"}]
             (mapv (fn [b]
                     (let [box (get (:boxes-by-name (:graph st)) b)
                           color (if (and (some? box)
                                          (pos? (.-length (:type box))))
                                   (:border (get (:box (:colors st)) (:type box)))
                                   (:border (:neutral-box (:colors st))))]
                       [:button {:key b :class "cp-row" :type "button"
                                 :title "Expand this box"
                                 :on-click (fn [e]
                                             (.stopPropagation e)
                                             (expand-box! b))}
                        [:span {:class "cp-dot" :style {:background color}}]
                        [:span {:class "cp-name"} (or (:label box) b)]
                        [:span {:class "cp-plus"} "+"]]))
                   (vec (sort (js/Array.from collapsed)))))])))

;; attrs already represented visually (endpoints/arrow on the canvas,
;; membership by containment) stay out of the inspector
(def ^:private hidden-attrs
  {"edge" #{"nodes" "direction"}
   "box" #{"components"}})

(defn- visible-attrs [sel]
  (let [hidden (get hidden-attrs (:kind sel))]
    (filterv (fn [[k _]] (not (and (some? hidden) (.has hidden k))))
             (js/Object.entries (:attrs sel)))))

(defn- fmt-val [v]
  (cond (nil? v) "—"
        (string? v) v
        :else (js/JSON.stringify v)))

(defn- autosize!
  "Grow a textarea to fit its content (and shrink back), so a value
  edits as one line when short and as many as it needs when long."
  [el]
  (set! (.. el -style -height) "auto")
  (set! (.. el -style -height) (str (.-scrollHeight el) "px")))

(defn- attr-edit-row [tgt k v scalar editing]
  [:dd {:key (str "d" k) :class "attr-row"}
   (if (= k (:attr editing))
     [:textarea
      {:value (:text editing) :class "attr-edit" :rows 1
       ;; reagami >= 0.2.41 passes the hook a single map, not positional args
       :on-render (fn [{:keys [node lifecycle]}]
                    (autosize! node)
                    (when (= lifecycle "mount")
                      (.focus node)
                      (let [n (.-length (.-value node))]
                        (.setSelectionRange node n n))))
       :on-input (fn [e] (swap! state assoc-in [:editing :text] (.. e -target -value)))
       :on-keydown (fn [e]
                     (cond
                       ;; Enter commits; Shift+Enter is the line break
                       (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                       (do (.preventDefault e)
                           (post-edit! [(editor/set-attr-op tgt k (:text (:editing @state)) scalar)]))

                       (= (.-key e) "Escape")
                       (swap! state assoc :editing nil)))
       :on-blur (fn [_] (when-let [ops (editor/blur-op (:editing @state) k tgt scalar)]
                         (post-edit! ops)))}]
     [:span {:on-click (fn [_] (swap! state assoc :editing {:attr k :text (editor/value->edn-text v)}))}
      [:span {:class "attr-val"} (format/value->hiccup v)]
      [:button {:class "attr-btn" :type "button" :title "Edit value"
                :on-click (fn [e]
                            (.stopPropagation e)
                            (swap! state assoc :editing {:attr k :text (editor/value->edn-text v)}))}
       "✎"]
      [:button {:class "attr-btn attr-del" :type "button" :title "Delete attribute"
                :on-click (fn [e] (.stopPropagation e) (post-edit! [(editor/del-attr-op tgt k)]))}
       "×"]])])

(defn- ^:async submit-attr-add! [tgt]
  (let [key-text (.trim (.-value (js/document.getElementById "attr-add-key")))
        val-text (.-value (js/document.getElementById "attr-add-val"))]
    (when (pos? (.-length key-text))
      (js-await (post-edit! [(editor/set-attr-op tgt key-text val-text true)]))
      ;; the inputs are uncontrolled, so a re-render leaves their text in
      ;; place — clear them once the attribute landed, ready for the next
      (when (nil? (:edit-error @state))
        (when-let [key-el (js/document.getElementById "attr-add-key")]
          (set! (.-value key-el) "")
          (set! (.-value (js/document.getElementById "attr-add-val")) "")
          (.focus key-el))))))

(defn- attr-add-row [tgt]
  [:div {:class "attr-add"}
   [:input {:id "attr-add-key" :class "attr-add-key" :type "text" :placeholder "key"
            :on-keydown (fn [e]
                          (when (= (.-key e) "Enter")
                            (.focus (js/document.getElementById "attr-add-val"))))}]
   [:input {:id "attr-add-val" :class "attr-add-val" :type "text" :placeholder "value"
            :on-keydown (fn [e]
                          (when (= (.-key e) "Enter") (submit-attr-add! tgt)))}]
   [:button {:class "attr-add-btn" :type "button" :title "Add attribute"
             :on-click (fn [e] (.stopPropagation e) (submit-attr-add! tgt))}
    "+"]])

(def ^:private direction-choices
  [["→" "->"] ["←" "<-"] ["↔" "<->"] ["—" "-"]])

(defn- direction-btn [tgt current label dir]
  [:button {:key dir :type "button"
            :class (str "dir-btn" (if (= dir current) " active" ""))
            :on-click (fn [e] (.stopPropagation e) (post-edit! [(editor/direction-op tgt dir)]))}
   label])

(defn- pick-btn [label pick hint]
  [:button {:class "action-pick" :type "button"
            :on-click (fn [e] (.stopPropagation e) (start-pick! pick hint))}
   label])

(defn- pick-buttons
  "Extra action-bar buttons that enter pick mode, per selection kind:
  edge → retarget either endpoint; node → add to a box; box → take a
  node or another box as a member."
  [sel tgt]
  (case (:kind sel)
    "edge" [(pick-btn "change source"
                      {:mode "retarget" :edge (:id tgt) :end "source"}
                      "click the new source node or box")
            (pick-btn "change target"
                      {:mode "retarget" :edge (:id tgt) :end "target"}
                      "click the new target node or box")]
    "node" [(pick-btn "add edge"
                      {:mode "connect" :from (:id tgt)}
                      "click the target node or box")
            (pick-btn "add to box"
                      {:mode "into-box" :member (:id tgt)}
                      "click the destination box")]
    "box" [(pick-btn "add edge"
                     {:mode "connect" :from (:id tgt)}
                     "click the target node or box")
           (pick-btn "add node"
                     {:mode "box-take" :box (:id tgt) :want "node"}
                     "click a node to add")
           (pick-btn "add box"
                     {:mode "box-take" :box (:id tgt) :want "box"}
                     "click a box to add")]
    []))

(defn- id-entry-btn [label for-kind]
  [:button {:class "action-pick" :type "button"
            :on-click (fn [e] (.stopPropagation e) (start-id-entry! for-kind))}
   label])

(defn- id-entry-buttons
  "Extra action-bar buttons that open the id-entry input — node only:
  add a freshly-created node connected to this one, or wrap this node
  in a freshly-created box."
  [sel]
  (if (= (:kind sel) "node")
    [(id-entry-btn "create node" "connect")
     (id-entry-btn "new box" "newbox")]
    []))

(defn- submit-id-entry! [tgt]
  (let [entry (:id-entry @state)
        text (.trim (:text entry))]
    (when (pos? (.-length text))
      (case (:for entry)
        "connect" (do (post-edit! (editor/add-connected-ops (:id tgt) text))
                      (swap! state assoc :pending-focus text))
        "newbox" (post-edit! (editor/wrap-in-box-ops (:id tgt) text))
        "node" (do (post-edit! (editor/add-node-ops text))
                   (swap! state assoc :pending-focus text))
        nil)
      (swap! state assoc :id-entry nil))))

(defn- id-entry-row [tgt entry]
  [:div {:class "id-entry"}
   [:input {:class "id-entry-input" :type "text" :value (:text entry)
            :placeholder (if (= (:for entry) "connect") "new node id" "new box id")
            :on-render (fn [{:keys [node lifecycle]}]
                         (when (= lifecycle "mount") (.focus node)))
            :on-input (fn [e] (swap! state assoc-in [:id-entry :text] (.. e -target -value)))
            :on-keydown (fn [e]
                          (case (.-key e)
                            "Enter" (submit-id-entry! tgt)
                            "Escape" (cancel-id-entry!)
                            nil))}]])

(defn- action-bar [sel tgt]
  (into [:div {:class "details-actions"}]
        (concat
         (when (= (:kind sel) "edge")
           (let [current (or (:direction (:attrs sel)) "-")]
             [(into [:div {:class "dir-group"}]
                    (mapv (fn [[label dir]] (direction-btn tgt current label dir))
                          direction-choices))]))
         (pick-buttons sel tgt)
         (id-entry-buttons sel)
         [[:button {:class "action-delete" :type "button"
                    :on-click (fn [e] (.stopPropagation e) (delete! tgt))}
           "Delete"]]
         (when-let [entry (:id-entry @state)] [(id-entry-row tgt entry)]))))

(defn- details-view [st]
  (let [sel (:selected st)
        editable (current-edit-target-editable? st)
        tgt (when editable (editor/target sel))
        editing (:editing st)]
    [:aside {:id "details"}
     [:button {:id "details-close" :type "button" :aria-label "Close details"
               :on-click (fn [e] (.stopPropagation e) (on-select nil))}
      "×"]
     [:h2 (:title sel)]
     [:div {:class "details-type"}
      (str (if (pos? (.-length (:subtitle sel)))
             (str "(" (:subtitle sel) ") — ")
             "")
           (:kind sel)
           (if (some? (:diff sel)) (str " — " (:diff sel)) ""))]
     (when (some? (:changed sel))
       [:div {:class "details-changes"}
        [:div {:class "details-changes-header"} "changes (old → new)"]
        (into [:dl]
              (mapcat (fn [[k v]]
                        [[:dt {:key (str "ct" k)} k]
                         [:dd {:key (str "cd" k)}
                          (str (fmt-val (:old v)) " → " (fmt-val (:new v)))]])
                      (js/Object.entries (:changed sel))))])
     (into [:dl]
           (mapcat (fn [[k v]]
                     [[:dt {:key (str "t" k)} k]
                      (if editable
                        (attr-edit-row tgt k v (editor/scalar? v) editing)
                        [:dd {:key (str "d" k)} (format/value->hiccup v)])])
                   (visible-attrs sel)))
     (when editable (attr-add-row tgt))]))

(defn- selection-toolbar
  "Floating bottom-center toolbar: the selection's edit tools, or — with
  nothing selected — a standalone add-node button. The inspector panel
  itself stays read/data-only."
  [st]
  (let [sel (:selected st)]
    [:div {:id "selection-toolbar"}
     (if (some? sel)
       (action-bar sel (editor/target sel))
       (into [:div {:class "details-actions"}
              (id-entry-btn "create node" "node")]
             (when-let [entry (:id-entry st)] [(id-entry-row nil entry)])))]))

(defn- banner-view [{:keys [error warnings collapsed edit-error disconnected]}]
  (cond
    disconnected
    [:div {:id "banner" :class "error"}
     "Not connected: the simpleviz server is not running. Restart it to resume live reload."]

    (some? edit-error)
    [:div {:id "banner" :class "error"
           :on-click (fn [_] (swap! state assoc :edit-error nil))}
     (str "Edit failed: " edit-error)]

    (some? error)
    [:div {:id "banner" :class "error"} error]

    (pos? (.-length warnings))
    [:div {:id "banner"
           :class (str "warning" (when collapsed " collapsed"))
           :on-click (fn [_] (swap! state update :collapsed not))}
     (.join warnings "\n")]

    :else nil))

(defn- item->payload [item]
  (let [nm (str (if (nil? (:name item)) "" (:name item)))
        fallback (if (= (:kind item) "edge")
                   (str (:source item) " → " (:target item))
                   (:id item))]
    (cond-> {:kind (:kind item)
             :elk-id (:id item)
             :title (if (pos? (.-length nm)) nm fallback)
             :subtitle (str (if (nil? (:type item)) "" (:type item)))
             :attrs (:attrs item)
             :diff (:diff item)
             :changed (:changed item)}
      (= (:kind item) "edge") (assoc :source (:source item) :target (:target item)))))

(defn- refresh-selection
  "st with :selected re-derived from scene sc: the payload is a snapshot,
  so after a reload its attrs would otherwise show the pre-edit values
  until the element is clicked again. Cleared when the element is gone."
  [st sc]
  (if-let [sel (:selected st)]
    (let [item (some (fn [it] (when (= (:id it) (:elk-id sel)) it)) (:items sc))]
      (assoc st :selected (if (some? item) (item->payload item) nil)))
    st))

(defn- cycle-diff! [status]
  (let [stops (get (scene/diff-stops (:scene @state)) status)]
    (when (pos? (.-length stops))
      (let [idx (mod (inc (get (:diff-cursors @state) status -1))
                     (.-length stops))
            item (nth stops idx)]
        (swap! state (fn [st]
                       (-> st
                           (assoc-in [:diff-cursors status] idx)
                           (assoc :selected (item->payload item)))))
        (canvas/center-on! item)))))

(defn- legend-row [st status glyph cls stops]
  (let [n (.-length stops)
        idx (get (:diff-cursors st) status)]
    [:button {:key status :type "button"
              :class (str "dl-row" (if (zero? n) " dl-empty" ""))
              :disabled (zero? n)
              :title (if (zero? n)
                       (str "no " status " elements")
                       (str "jump to the next " status " element"))
              :on-click (fn [e] (.stopPropagation e) (cycle-diff! status))}
     [:span {:class (str "dl-key " cls)} glyph]
     [:span {:class "dl-label"} status]
     [:span {:class "dl-count"}
      (if (some? idx) (str (inc idx) "/" n) (str n))]]))

(defn- edit-target-btn [st side ok]
  (let [active (= (:edit-target st) side)]
    [:button {:key side :type "button"
              :class (str "dl-target" (if active " active" ""))
              :disabled (not ok)
              :title (when-not ok "PNG side is read-only")
              :on-click (fn [e] (.stopPropagation e) (swap! state assoc :edit-target side))}
     side]))

(defn- edit-target-row [st g]
  (when (or (:editable g) (:editable-old g))
    [:div {:class "dl-edit-target"}
     [:span {:class "dl-label"} "edit:"]
     (edit-target-btn st "old" (:editable-old g))
     (edit-target-btn st "new" (:editable g))]))

(defn- legend-view [st]
  (when-let [cmp (:compare (:graph st))]
    (let [stops (scene/diff-stops (:scene st))]
      [:div {:id "diff-legend"}
       [:div {:class "dl-files"}
        (str (format/basename (:old cmp)) " → " (format/basename (:new cmp)))]
       (legend-row st "added" "+" "dl-added" (get stops "added"))
       (legend-row st "modified" "~" "dl-modified" (get stops "modified"))
       (legend-row st "removed" "−" "dl-removed" (get stops "removed"))
       (edit-target-row st (:graph st))])))

(defn- update-hover!
  "Set/clear the canvas title attribute to the hovered element's id —
  the native tooltip reveals what to reference in the EDN file. Direct
  DOM attribute write: no state, no re-render."
  [el mx my]
  (let [p (hit/client->graph canvas/view mx my)
        s (:scene @state)
        item (when (some? s)
               (hit/hit-test s p (/ 8 (:k canvas/view)) (:k canvas/view)))
        t (hit/hover-title item)]
    (if (some? t)
      (.setAttribute el "title" t)
      (.removeAttribute el "title"))))

(defn- canvas-view []
  [:canvas
   {:id "canvas" :key "the-canvas"
    :on-pointermove
    (fn [e]
      (let [el (.-currentTarget e)
            rect (.getBoundingClientRect el)]
        (update-hover! el (- (.-clientX e) (.-left rect))
                       (- (.-clientY e) (.-top rect)))))
    :on-pointerleave
    (fn [e] (.removeAttribute (.-currentTarget e) "title"))
    :on-click
    (fn [e]
      ;; drag-ending clicks never arrive here: pointer capture (acquired
      ;; only mid-drag) retargets them to the wrap
      (let [rect (.getBoundingClientRect (.-currentTarget e))
            p (hit/client->graph canvas/view
                                 (- (.-clientX e) (.-left rect))
                                 (- (.-clientY e) (.-top rect)))
            tol (/ 8 (:k canvas/view))
            s (:scene @state)
            item (when (some? s) (hit/hit-test s p tol (:k canvas/view)))
            pick (:pick @state)]
        (if (some? pick)
          ;; picking: a collapse-button hit is not a valid target (ignore,
          ;; keep picking, no mode toggle); a miss cancels; a valid-kind
          ;; hit sends the op and clears :pick; an invalid-kind hit keeps
          ;; picking.
          (cond
            (nil? item) (cancel-pick!)
            (= (:kind item) "collapse-button") nil
            :else (let [ops (editor/pick-ops pick item)]
                    (when (some? ops)
                      (cancel-pick!)
                      (post-edit! ops))))
          (if (= (:kind item) "collapse-button")
            (toggle-collapse! (.slice (:box-id item) 2))
            (on-select (when (some? item) (item->payload item)))))))}])

(defn- current-edit-target-editable? [st]
  (when-let [g (:graph st)]
    (boolean (if (= (:edit-target st) "old") (:editable-old g) (:editable g)))))

(defn- toggle-help! []
  (swap! state update :help not))

(defn- help-section [title & paras]
  (into [:section [:h3 title]]
        (mapv (fn [p] [:p p]) paras)))

(defn- help-view [st]
  (when (:help st)
    [:aside {:id "help-panel"}
     [:button {:id "help-close" :type "button" :aria-label "Close help"
               :on-click (fn [e] (.stopPropagation e)
                           (swap! state assoc :help false))}
      "×"]
     [:h2 "How to use"]
     (help-section
      "Navigate"
      "Drag to pan, scroll to zoom. Hover an element to see its id in the EDN file; click it to inspect its attributes. The − in a box header collapses the box to a single node — the panel on the left lists collapsed boxes and re-expands them.")
     (help-section
      "Edit"
      "When the served file is editable EDN, the floating toolbar at the bottom holds the tools for the current selection: delete, edge direction, and pick modes such as \"add edge\" (click the other element on the canvas; Esc cancels). With nothing selected it creates a standalone node."
      "In the inspector, click a value or its ✎ to edit it inline — Enter commits, Shift+Enter inserts a line break, Escape cancels. × deletes an attribute; the key/value row at the bottom adds one. Ctrl+Z or ⟲ undoes the last edit.")
     (help-section
      "Compare"
      "Serving two files renders one merged diagram: added elements get a green +, modified an amber ~ (select for an old → new list), removed ones stay as red dashed ghosts. Click a legend row to jump through the changes; the old|new toggle picks which file edits apply to.")
     (help-section
      "Export"
      "⇩ downloads the diagram as a PNG with the source EDN embedded — an exported PNG can be served again, compared, or turned back into EDN with \"simpleviz extract\".")
     (help-section
      "Theme"
      "☀ / 🌙 switches between light and dark mode.")]))

(defn- pick-hint-view [st]
  (when-let [pick (:pick st)]
    [:div {:id "pick-hint"} (:pick-hint st) " — Esc cancels"]))

(defn- load-view [st]
  [:div {:id "loadscreen"}
   [:div {:class "load-spinner"}]
   [:div {:class "load-title"} "simpleviz"]
   [:div {:class "load-stage"} (or (:load-stage st) "loading…")]])

(defn- app-view [st]
  [:div {:id "root" :class (when (some? (:pick st)) "picking")}
   (banner-view st)
   (pick-hint-view st)
   (when (and (nil? (:scene st)) (nil? (:error st)))
     (load-view st))
   (collapsed-view st)
   (when (some? (:graph st)) (legend-view st))
   (when (:layouting st)
     [:div {:id "layouting"} "re-layouting…"])
   (when (some? (:scene st))
     [:button {:id "export-btn" :type "button" :title "Export PNG"
               :on-click (fn [e] (.stopPropagation e) (export-png!))}
      "⇩"])
   (when (current-edit-target-editable? st)
     [:button {:id "undo-btn" :type "button" :title "Undo last edit (Ctrl+Z)"
               :on-click (fn [e] (.stopPropagation e) (post-edit! [{:op "undo"}]))}
      "⟲"])
   (when (:seeded (:layout st))
     [:button {:id "relayout-btn" :type "button"
               :title "Re-layout: edits kept the old arrangement, run a fresh layout"
               :on-click (fn [e] (.stopPropagation e) (relayout! true))}
      "⟳"])
   [:button {:id "theme-toggle" :type "button"
             :title (if (= (:theme st) "dark") "Switch to light mode" "Switch to dark mode")
             :on-click (fn [e] (.stopPropagation e) (toggle-theme!))}
    (if (= (:theme st) "dark") "☀" "🌙")]
   [:button {:id "help-btn" :type "button" :title "Help"
             :on-click (fn [e] (.stopPropagation e) (toggle-help!))}
    "?"]
   (help-view st)
   (canvas-view)
   (when (and (some? (:scene st)) (current-edit-target-editable? st))
     (selection-toolbar st))
   (when (some? (:selected st))
     (details-view st))])

(defn- paint-now! []
  (when-let [canvas-el (js/document.getElementById "canvas")]
    (when-let [s (:scene @state)]
      (canvas/paint! canvas-el s (:elk-id (:selected @state))))))

(defn- rerender! []
  (render app-el (app-view @state))
  (canvas/request-paint!))

(defn- apply-pending-focus!
  "When an add-node flow is in flight, select the freshly created node
  once its scene item lands — found by its scene id `n:<pending>`. The
  view is deliberately not panned: the seeded relayout puts the node
  beside its source, and a jump would undo the arrangement staying
  put. Always clears :pending-focus, found or not: the edit may have
  failed, and either way this is a one-shot request."
  [sc]
  (when-let [pending (:pending-focus @state)]
    (let [want (str "n:" pending)
          item (some (fn [it] (when (= (:id it) want) it)) (:items sc))]
      (when (some? item)
        (on-select (item->payload item))))
    (swap! state assoc :pending-focus nil)))

(defn ^:async relayout!
  "Layout + scene from the stored graph, with collapsed boxes contracted.
  Colors come from the FULL graph so collapsing never shifts type colors.
  Results are cached per collapsed-set; a stale async result (set changed
  meanwhile) is cached but not applied, and one from a superseded graph
  generation (file reloaded mid-flight) is discarded entirely.

  A structural change to a collapsed-set that already has a layout (a
  demoted cache entry: the file was edited) runs ELK in interactive mode
  seeded with that layout, so layers and ordering survive the edit. The
  result is tagged :seeded — the tag travels with the layout through the
  cache, reuse and state, and shows the re-layout button. `clean?` runs
  a fresh layout instead, dropping the whole cache so every collapsed
  set is laid out fresh when next shown."
  [& [clean?]]
  (try
    (when clean? (.clear layout-cache))
    (let [gen @graph-gen
          g0 (:graph @state)
          collapsed (:collapsed-boxes @state)
          ck (cache-key collapsed)
          hit (.get layout-cache ck)]
      (if (and (some? hit) (some? (:scene hit)))
        (do (swap! state (fn [st]
                           (refresh-selection
                            (assoc st :colors (:colors hit) :layout (:layout hit)
                                   :scene (:scene hit) :layouting false :diff-cursors {})
                            (:scene hit))))
            (apply-pending-focus! (:scene hit)))
        (do
          (swap! state assoc
                 :layouting true
                 :load-stage (str "laying out "
                                  (.-length (js/Object.keys (:nodes g0)))
                                  " nodes, " (.-length (:edges g0)) " edges…"))
          (js-await (yield-paint!))
          (let [g (collapse-boxes g0 collapsed)
                cmap {:node (colors/color-map (mapv (fn [n] (:type n))
                                                    (js/Object.values (:nodes g0)))
                                              colors/NODE-TABLE)
                      :box (colors/color-map (mapv (fn [b] (:type b)) (:boxes g0))
                                             colors/BOX-TABLE)
                      :neutral-node colors/NEUTRAL-NODE
                      :neutral-box colors/NEUTRAL-BOX}
                elk-graph (to-elk g canvas/measure)
                fp (elk-fingerprint elk-graph)
                prev (when (some? hit) (:layout hit))
                positions (when (some? prev) (layout-positions prev))
                ;; a demoted entry with a matching fingerprint means the
                ;; reload was attribute-only: the ELK inputs are identical,
                ;; so reuse its layout and skip the expensive ELK run
                layout (cond (and (some? prev) (= fp (:fingerprint hit)))
                             prev

                             (and (some? prev) (seedable? elk-graph positions))
                             (assoc (js-await (.layout elk (seed-layout elk-graph positions)))
                                    :seeded true)

                             :else (js-await (.layout elk elk-graph)))
                sc (scene/build-scene {:layout layout :graph g :colors cmap})]
            (canvas/fit-view-once! sc)
            (when (= gen @graph-gen)
              (when (> (.-size layout-cache) 16) (.clear layout-cache))
              (.set layout-cache ck {:fingerprint fp :colors cmap
                                     :layout layout :scene sc})
              (if (= ck (cache-key (:collapsed-boxes @state)))
                (do (swap! state (fn [st]
                                   (refresh-selection
                                    (assoc st :colors cmap :layout layout :scene sc
                                           :layouting false :diff-cursors {})
                                    sc)))
                    (apply-pending-focus! sc))
                (swap! state assoc :layouting false)))))))
    (catch :default e
      (js/console.error "Relayout failed:" e)
      (swap! state assoc :layouting false
             :error (str "Render error: " (or (.-message e) (str e)))))))

(defn- resolve-edit-target
  "The :edit-target to use for a freshly loaded graph g, given the
  current value. Single-file mode always edits \"new\". In compare
  mode the current target is kept as long as its own editable flag
  (:editable for \"new\", :editable-old for \"old\") is true — the
  user's toggle choice survives every live-reload tick; only when it
  goes stale (that side turned/became a PNG) do we hop to whichever
  side is still editable, falling back to the unchanged current value
  when neither side is."
  [g current]
  (if (:compare g)
    (let [new-ok (:editable g)
          old-ok (:editable-old g)
          cur-ok (if (= current "old") old-ok new-ok)]
      (if cur-ok
        current
        (cond old-ok "old" new-ok "new" :else current)))
    "new"))

(defn ^:async reload! []
  (try
    (when (nil? (:scene @state))
      (swap! state assoc :load-stage "loading graph…"))
    (let [resp (js-await (js/fetch "/api/graph"))
          raw (js-await (.json resp))]
      (if (some? (:error raw))
        (swap! state assoc :error (str "Graph error: " (:error raw)))
        (let [g (assoc raw :boxes-by-name
                       (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes raw)))
              first-load? (nil? (:graph @state))]
          (swap! graph-gen inc)
          (demote-layout-cache!)
          (set! (.-title js/document) (format/tab-title g))
          (swap! state (fn [st]
                         (assoc st :error nil :graph g :warnings (:warnings g)
                                :edit-target (resolve-edit-target g (:edit-target st)))))
          ;; big graphs open as a collapsed overview: all top-level boxes
          ;; start folded, drill in from there (also makes the first ELK
          ;; run cheap). Small graphs open fully expanded.
          (when (and first-load?
                     (> (.-length (js/Object.keys (:nodes g))) 500))
            (swap! state assoc :collapsed-boxes
                   (set (keep (fn [b]
                                (when (nil? (get (:parent-of g)
                                                 (str "b:" (:name b))))
                                  (:name b)))
                              (:boxes g)))))
          (js-await (relayout!)))))
    (catch :default e
      (js/console.error "Reload failed:" e)
      (reset! last-mtime nil)
      (swap! state assoc :error (str "Render error: " (or (.-message e) (str e)))))))

(defn ^:async tick []
  (let [mtime (try
                (let [resp (js-await (js/fetch "/api/version"))
                      v (js-await (.json resp))]
                  (:mtime v))
                (catch :default _ nil))]
    ;; a failed poll means the server is gone (or restarting); the flag
    ;; clears on the next successful poll, so reconnection needs no action
    (when (not= (nil? mtime) (:disconnected @state))
      (swap! state assoc :disconnected (nil? mtime)))
    (when (and (some? mtime) (not= mtime @last-mtime))
      (reset! last-mtime mtime)
      (js-await (reload!)))))

(defn- ^:async post-edit! [ops]
  (let [resp (js-await (js/fetch "/api/edit"
                                 {:method "POST"
                                  :headers {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify
                                         (editor/edit-body (:edit-target @state) ops))}))
        out (js-await (.json resp))]
    (if (some? (:error out))
      ;; a failed edit invalidates any pending-focus jump that was armed
      ;; for it (e.g. add-connected-ops on a duplicate id) — relayout!,
      ;; the only place that consumes/clears :pending-focus, never runs
      ;; on this path, so it must be cleared here or it lingers and can
      ;; fire a stray on-select on some later, unrelated
      ;; relayout if that id ever comes to exist.
      (swap! state assoc :edit-error (:error out) :pending-focus nil)
      (do (swap! state assoc :edit-error nil :editing nil)
          (js-await (tick))))))

(defn- ^:async delete! [tgt]
  (js-await (post-edit! [(editor/delete-op tgt)]))
  ;; :selected is a payload snapshot, not a live lookup — after a
  ;; successful delete the element it describes is gone from the
  ;; reloaded graph, so the panel would keep showing stale data.
  (when (nil? (:edit-error @state))
    (on-select nil)))

(defn- apply-theme! [t]
  (set! (.. js/document -documentElement -dataset -theme) t)
  (canvas/set-theme! t)
  (canvas/request-paint!))

(defn- toggle-theme! []
  (let [t (if (= (:theme @state) "dark") "light" "dark")]
    (js/localStorage.setItem "simpleviz-theme" t)
    (swap! state assoc :theme t)
    (apply-theme! t)))

(defn- ^:async fetch-source
  "Raw EDN text from /api/source (which = \"old\"|\"new\"|nil), or nil on
  any failure — a failed fetch degrades the export to metadata-less."
  [which]
  (try
    (let [resp (js-await (js/fetch (str "/api/source"
                                        (if (some? which)
                                          (str "?which=" which)
                                          ""))))]
      (if (.-ok resp) (js-await (.text resp)) nil))
    (catch :default _ nil)))

(defn- download-blob!
  "Trigger a browser download of blob as <nm>.png via a throwaway
  object URL and anchor click."
  [blob nm]
  (let [url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) (str nm ".png"))
    (.click a)
    (js/setTimeout (fn [] (js/URL.revokeObjectURL url)) 1000)))

(defn- ^:async export-png! []
  (when-let [sc (:scene @state)]
    (let [g (:graph @state)
          nm (let [f (:file g)]
               (if (some? f) (.replace f (js/RegExp. "\\.(edn|png)$") "") "graph"))
          pairs (if (some? (:compare g))
                  (let [o (js-await (fetch-source "old"))
                        n (js-await (fetch-source "new"))]
                    (cond-> []
                      (some? o) (conj ["simpleviz-edn-old" o])
                      (some? n) (conj ["simpleviz-edn-new" n])))
                  (let [s (js-await (fetch-source nil))]
                    (if (some? s) [["simpleviz-edn" s]] [])))
          cnv (canvas/export-canvas sc)]
      (.toBlob cnv
               (fn [blob]
                 (if (some? blob)
                   (-> (.arrayBuffer blob)
                       (.then
                        (fn [buf]
                          (let [out (png/embed-many (js/Uint8Array. buf) pairs)]
                            (download-blob! (js/Blob. [out] {:type "image/png"}) nm))))
                       ;; Embedding metadata failed for some unexpected
                       ;; reason (e.g. embed-many throws) — degrade
                       ;; gracefully to a plain, metadata-less download
                       ;; rather than silently losing the export as an
                       ;; unhandled promise rejection.
                       (.catch (fn [_] (download-blob! blob nm))))
                   (swap! state assoc :error
                          "PNG export failed — the diagram may be too large")))
               "image/png"))))

;; init
(js/window.addEventListener "keydown"
  (fn [e]
    (cond
      (= (.-key e) "Escape") (do (cancel-pick!)
                                 (swap! state assoc :help false))
      (and (or (.-ctrlKey e) (.-metaKey e))
           (= (.toLowerCase (.-key e)) "z")
           (let [tag (.-tagName (.-activeElement js/document))]
             (not (or (= tag "INPUT") (= tag "TEXTAREA"))))
           (current-edit-target-editable? @state))
      (do (.preventDefault e) (post-edit! [{:op "undo"}])))))
(canvas/set-repaint! paint-now!)
(apply-theme! (:theme @state))
(add-watch state :render (fn [_ _ _ _] (rerender!)))
(canvas/setup-pan-zoom! (js/document.getElementById "canvas-wrap"))
(rerender!)
(tick)
(js/setInterval tick 1000)
