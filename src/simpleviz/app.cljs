(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.format :as format]
            [simpleviz.transform :refer [to-elk elk-fingerprint layout-positions seed-layout seedable?
                                         rename-layout-ids]]
            [simpleviz.prune :refer [collapse-boxes collapse-scene]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]
            [simpleviz.png :as png]
            [simpleviz.editor :as editor]
            [simpleviz.grid :as grid]
            [simpleviz.compact :as compact]
            [mdlinks :as mdlinks]
            [themes :as themes]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

(def ^:private theme-key
  "localStorage key of your theme (the old light/dark toggle used it too)."
  "simpleviz-theme")

(defn- stored-theme
  "Your theme from this browser: a built-in name, else nil (follow the
  OS). Storage can be unavailable (private windows) — then nil."
  []
  (try (let [v (js/localStorage.getItem theme-key)]
         (when (some #(= v %) themes/NAMES) v))
       (catch :default _ nil)))

(defn- store-theme!
  "Save your theme (\"\" forgets it: follow the OS); storage failures
  only mean the choice doesn't outlive the page."
  [v]
  (try (if (seq v)
         (js/localStorage.setItem theme-key v)
         (js/localStorage.removeItem theme-key))
       (catch :default _ nil)))

(def ^:private layout-key
  "localStorage key of your layout algorithm."
  "simpleviz-layout")

(defn- stored-layout
  "Your layout algorithm from this browser, nil when none (or storage
  is unavailable)."
  []
  (try (let [v (js/localStorage.getItem layout-key)]
         (when (some (fn [[n _]] (= v n)) editor/LAYOUTS) v))
       (catch :default _ nil)))

(def ^:private toolbar-key
  "localStorage key of your toolbar layout."
  "simpleviz-toolbar")

(defn- stored-toolbar-layout
  "\"grouped\" when you picked it in this browser, else \"flat\" (also
  when storage is unavailable)."
  []
  (try (if (= "grouped" (js/localStorage.getItem toolbar-key)) "grouped" "flat")
       (catch :default _ "flat")))

(defn- store-toolbar-layout! [v]
  (try (js/localStorage.setItem toolbar-key v) (catch :default _ nil)))

(def state (atom {:error nil :notice nil :dismissed-error nil :dismissed-warnings nil
                  :warnings [] :graph nil :layout nil
                  :colors nil :selected nil :collapsed false
                  :collapsed-boxes #{} :layouting false
                  :diff-cursors {}
                  :edit-target "new" :edit-error nil :editing nil
                  :pick nil :pick-hint nil
                  :id-entry nil :chord nil
                  ;; a URL naming an element (focus=n:api) selects and centers it
                  :pending-focus (:focus (editor/parse-nav js/location.search))
                  :focus-center true :flash nil
                  :help false :export-menu false :disconnected false
                  ;; the open :md-ref doc (open-md!), nil when the panel is closed
                  :md nil
                  :nav (editor/parse-nav js/location.search) :nav-error nil
                  ;; your theme (#115), for files without :theme
                  :theme-pref (stored-theme)
                  ;; your layout algorithm, for files without :layout
                  :layout-pref (stored-layout)
                  ;; your toolbar: every tool in a row, or grouped by first key
                  :toolbar-layout (stored-toolbar-layout)
                  ;; light or dark as the OS has it, when you chose none
                  :theme (if (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)"))
                           "dark"
                           "light")}))
(def last-mtime (atom nil))

;; the root file's root-relative name (/api/root), asked once
(def ^:private root-path (atom nil))

;; the query string of what the page shows: back/forward re-push it when
;; leaving would lose unsaved doc text
(def ^:private shown-query (atom ""))

(defn- page?
  "Is the md page showing (not a graph, not just the docked panel)?"
  [st]
  (= true (:page (:md st))))

(defn- current-path
  "The root-relative path of the file the page shows: the md page's,
  else the graph's."
  [st]
  (if (page? st) (:path (:md st)) (:path (:graph st))))

(defn- file-query
  "\"?file=<rel>\" for the graph the page is showing, \"\" for the root."
  []
  (let [f (:file (:nav @state))]
    (if (some? f) (str "?file=" (js/encodeURIComponent f)) "")))

(defn- on-select [payload]
  (swap! state assoc :selected payload :editing nil :id-entry nil :chord nil :nav-error nil))

(defn- start-pick! [pick hint]
  (swap! state assoc :pick pick :pick-hint hint :chord nil))

(defn- cancel-pick! []
  (swap! state assoc :pick nil :pick-hint nil))

(defn- start-id-entry!
  "Open the name prompt for creating a `for-kind`; `extra` carries what
  creation-ops needs beyond the name (:parent, :direction)."
  [for-kind extra]
  (swap! state assoc :id-entry (merge {:for for-kind :text ""} extra)))

(defn- cancel-id-entry! []
  (swap! state assoc :id-entry nil))

(declare relayout! post-edit! delete! current-edit-target-editable? follow-ref! navigate! run-chord-action!
         open-md! save-md! close-md! open-page! root-path! load-nav!
         save-if-dirty! save-before-leaving! follow-link! sync-gutter-scroll!)

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

(defn- current-layout
  "The layout algorithm in use: the file's :layout, else yours."
  []
  (editor/effective-layout (:graph @state) (:layout-pref @state)))

(defn- cache-key
  "Layouts are cached per layout algorithm and collapsed-set:
  \"compact#a|b\"."
  [mode collapsed]
  (str mode "#" (.join (.sort (js/Array.from collapsed)) "|")))

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
  (let [current (cache-key (current-layout) (:collapsed-boxes @state))]
    (doseq [k (js/Array.from (.keys layout-cache))]
      (let [e (.get layout-cache k)]
        (cond (some? (:scene e))
              (.set layout-cache k {:fingerprint (:fingerprint e)
                                    :layout (:layout e)})
              (not= k current)
              (.delete layout-cache k))))))

;; the box being collapsed/expanded and where its top-right corner (the
;; hide-button's anchor) was on screen: when the new layout lands, the
;; view pans so the corner — and the button — stays put
(def ^:private pinned-box (atom nil))

(defn- box-item [sc id]
  (some (fn [it] (when (and (= (:kind it) "box") (= (:id it) id)) it))
        (or (:items sc) [])))

(defn- pin-box! [box-name]
  (let [id (str "b:" box-name)
        it (box-item (:scene @state) id)]
    (reset! pinned-box
            (when (some? it)
              (assoc (canvas/to-screen (+ (:x it) (:w it)) (:y it)) :id id)))))

(defn- keep-pinned!
  "Pan so the pinned box's top-right corner in the freshly applied scene
  sc sits where it was on screen before the toggle; then drop the pin."
  [sc]
  (when-let [{:keys [id x y]} @pinned-box]
    (reset! pinned-box nil)
    (when-let [it (box-item sc id)]
      (canvas/pin! (+ (:x it) (:w it)) (:y it) x y))))

(defn- collapse-box! [box-name]
  (pin-box! box-name)
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
  (pin-box! box-name)
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
                           color (canvas/box-border
                                  (when (and (some? box) (pos? (.-length (:type box))))
                                    (get (:box (:colors st)) (:type box))))]
                       [:button {:key b :class "cp-row" :type "button"
                                 :title "Expand this box"
                                 :on-click (fn [e]
                                             (.stopPropagation e)
                                             (expand-box! b))}
                        [:span {:class "cp-dot" :style {:background color}}]
                        [:span {:class "cp-name"} (or (:label box) b)]
                        [:span {:class "cp-plus"} "+"]]))
                   (vec (sort (js/Array.from collapsed)))))])))

(defn- fmt-val [k v]
  (cond (nil? v) "—"
        (string? v) v
        :else (or (format/attr->text k v) (js/JSON.stringify v))))

(defn- autosize!
  "Grow a textarea to fit its content (and shrink back), so a value
  edits as one line when short and as many as it needs when long."
  [el]
  (set! (.. el -style -height) "auto")
  (set! (.. el -style -height) (str (.-scrollHeight el) "px")))

(declare rename!)

(defn- edit-field
  "The inline textarea for the :editing field k: Enter commits its text
  through commit!, Shift+Enter inserts a line break (never for a
  single-line field), Escape cancels, and blur commits only while this
  field is still the one being edited (see editor/blur-text)."
  [k editing commit! single-line?]
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
                    (and (= (.-key e) "Enter") (or single-line? (not (.-shiftKey e))))
                    (do (.preventDefault e)
                        (commit! (:text (:editing @state))))

                    (= (.-key e) "Escape")
                    (swap! state assoc :editing nil)))
    :on-blur (fn [_] (when-let [text (editor/blur-text (:editing @state) k)]
                      (commit! text)))}])

(defn- start-editing! [k text]
  (swap! state assoc :editing {:attr k :text text}))

(defn- commit-attr!
  "Set attribute k of the selection to text. Setting :name on a node or
  box also renames it to the id the name derives (editor/derived-id),
  in the same batch, so a collision rejects both. `reopen?`: on failure
  reopen the field with the rejected text (inline edits; the add row
  keeps its own inputs instead)."
  [sel tgt k text scalar reopen?]
  (let [op (editor/set-attr-op tgt k text scalar)
        to (when (= k "name") (editor/derived-id tgt text))]
    (if (some? to)
      (rename! sel tgt to (cond-> {:ops [op]} reopen? (assoc :field k :text text)))
      (post-edit! [op]))))

(defn- attr-edit-row [sel tgt k v scalar editing]
  [:dd {:key (str "d" k) :class "attr-row"}
   (if (= k (:attr editing))
     (edit-field k editing (fn [text] (commit-attr! sel tgt k text scalar true)) false)
     [:span {:on-click (fn [_] (start-editing! k (editor/value->edn-text v)))}
      [:span {:class "attr-val"} (format/attr->hiccup k v)]
      [:button {:class "attr-btn" :type "button" :title "Edit value"
                :on-click (fn [e]
                            (.stopPropagation e)
                            (start-editing! k (editor/value->edn-text v)))}
       "✎"]
      [:button {:class "attr-btn attr-del" :type "button" :title "Delete attribute"
                :on-click (fn [e] (.stopPropagation e) (post-edit! [(editor/del-attr-op tgt k)]))}
       "×"]])])

;; :editing key of the id row — `$` is outside the attribute-name
;; alphabet, so no real attribute can collide with it
(def ^:private ID-FIELD "$id")

(defn- id-edit-row
  "The selected node's or box's id, editable inline like a value but
  single-line and without delete; a commit renames the element."
  [sel tgt editing]
  (let [id (:id tgt)]
    [:dd {:key "d$id" :class "attr-row"}
     (if (= ID-FIELD (:attr editing))
       (edit-field ID-FIELD editing (fn [text] (rename! sel tgt text nil)) true)
       [:span {:on-click (fn [_] (start-editing! ID-FIELD id))}
        [:span {:class "attr-val"} id]
        [:button {:class "attr-btn" :type "button" :title "Rename (updates every reference)"
                  :on-click (fn [e] (.stopPropagation e) (start-editing! ID-FIELD id))}
         "✎"]])]))

(defn- ^:async submit-attr-add! [sel tgt]
  (let [key-text (.trim (.-value (js/document.getElementById "attr-add-key")))
        val-text (.-value (js/document.getElementById "attr-add-val"))]
    (when (pos? (.-length key-text))
      (js-await (commit-attr! sel tgt key-text val-text true false))
      ;; the inputs are uncontrolled, so a re-render leaves their text in
      ;; place — clear them once the attribute landed, ready for the next
      (when (nil? (:edit-error @state))
        (when-let [key-el (js/document.getElementById "attr-add-key")]
          (set! (.-value key-el) "")
          (let [val-el (js/document.getElementById "attr-add-val")]
            (set! (.-value val-el) "")
            (autosize! val-el))
          (.focus key-el))))))

(defn- attr-add-row [sel tgt]
  [:div {:class "attr-add"}
   [:input {:id "attr-add-key" :class "attr-add-key" :type "text" :placeholder "key"
            :on-keydown (fn [e]
                          (when (= (.-key e) "Enter")
                            (.focus (js/document.getElementById "attr-add-val"))))}]
   ;; a textarea sized like the inline edit field, so a long or
   ;; multi-line value grows with its content instead of scrolling
   ;; inside one line; Shift+Enter inserts a line break
   [:textarea {:id "attr-add-val" :class "attr-edit attr-add-val" :rows 1 :placeholder "value"
               :on-render (fn [{:keys [node]}] (autosize! node))
               :on-input (fn [e] (autosize! (.-target e)))
               :on-keydown (fn [e]
                             (when (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                               (.preventDefault e)
                               (submit-attr-add! sel tgt)))}]
   [:button {:class "attr-add-btn" :type "button" :title "Add attribute"
             :on-click (fn [e] (.stopPropagation e) (submit-attr-add! sel tgt))}
    "+"]])

(def ^:private direction-choices
  [["→" "->"] ["←" "<-"] ["↔" "<->"] ["—" "-"]])

(defn- key-hint
  "The chord that triggers a toolbar button, shown inside it."
  [chord]
  (when (some? chord) [:kbd {:class "key-hint"} chord]))

(defn- direction-btn [tgt current label dir]
  [:button {:key dir :type "button"
            :class (str "dir-btn" (if (= dir current) " active" ""))
            :on-click (fn [e] (.stopPropagation e) (post-edit! [(editor/direction-op tgt dir)]))}
   label (key-hint (editor/chord-for "edge" ["direction" dir]))])

(defn- action-spec
  "What toolbar action `action` does for selection sel (tgt its op
  target): a pick mode to start, or a name prompt to open. One table for
  the buttons and the chords, so both always agree."
  [sel tgt action]
  (let [id (:id tgt)
        pof (:parent-of (:graph @state))
        here (when (some? sel) (get pof (:elk-id sel)))]
    (if (vector? action)
      (case (first action)
        "retarget" (let [end (second action)]
                     {:label (str "change " end)
                      :pick {:mode "retarget" :edge id :end (editor/retarget-end sel end)}
                      :hint (str "click the new " end " node or box")})
        "follow-pair" (when-let [p (get (working-pairs sel) (second action))]
                        {:label "follow pair" :go-pair p})
        nil)
      (case action
        "add-edge" {:label "add edge" :pick {:mode "connect" :from id}
                    :hint "click the target node or box"}
        "add-to-box" {:label "add to box" :pick {:mode "into-box" :member id}
                      :hint "click the destination box"}
        "add-node-member" {:label "add node" :pick {:mode "box-take" :box id :want "node"}
                           :hint "click a node to add"}
        "add-box-member" {:label "add box" :pick {:mode "box-take" :box id :want "box"}
                          :hint "click a box to add"}
        "remove-node-member" {:label "remove node" :pick {:mode "box-drop" :box id}
                              :hint "click a node in this box to take it out"}
        "new-node" {:label "new node" :id-entry "node"}
        "new-connected-node" {:label "new node" :id-entry "connect"}
        "new-node-in-box" {:label "new node" :id-entry "inbox"}
        "new-box" {:label "wrap in box" :id-entry "newbox"}
        "new-free-box" {:label "new box" :id-entry "box"}
        "new-box-in-box" {:label "new box inside" :id-entry "box-inbox"}
        "new-sibling" {:label "new sibling node" :id-entry "sibling" :parent (or here nil)}
        "new-connected-here" {:label "new connected node, same box" :id-entry "connect-here" :parent (or here nil)}
        "new-incoming" {:label "new incoming node" :id-entry "incoming" :parent (or here nil)}
        "split-edge" {:label "split edge" :id-entry "split"
                      :parent (editor/split-parent pof id)
                      :direction (or (:direction (:attrs sel)) nil)}
        ;; only for a node inside a box: nil hides the button and the chord
        "remove-from-box" (when-let [parent (get (:parent-of (:graph @state)) (:elk-id sel))]
                            {:label "remove from box" :post (editor/box-remove-op parent id)})
        ;; only for a selection with a string :ref, when the server can
        ;; navigate (single-file or suffix compare: the payload has :path)
        "follow-ref" (when-let [r (editor/ref-of sel)]
                       (when (some? (:path (:graph @state)))
                         {:label "follow ref" :go r}))
        ;; only with exactly one pair to follow; several are picked in the inspector
        "follow-pair" (let [ps (working-pairs sel)]
                        (when (= 1 (count ps)) {:label "follow pair" :go-pair (first ps)}))
        ;; a node or box with a string :md-ref, where the server can
        ;; navigate (the payload has :path); a bad path explains itself
        ;; in a banner when clicked rather than hiding the button
        "open-md" (when (and (some? (editor/ref-of sel :md-ref))
                             (some? (:path (:graph @state))))
                    {:label "open md" :md true})
        nil))))

(defn- available?
  "Whether `action` does something for selection sel right now — what
  the toolbar and the pop-out offer, and what a chord may run."
  [sel tgt action]
  (or (= action "delete") (= action "rename")
      (and (vector? action) (= "direction" (first action)))
      (some? (action-spec sel tgt action))))

(defn- start-action!
  "Do what the toolbar button for `action` does."
  [sel tgt action]
  (let [{:keys [pick hint id-entry post go go-pair md] :as spec} (action-spec sel tgt action)]
    (cond
      (some? pick) (start-pick! pick hint)
      (some? id-entry) (start-id-entry! id-entry (select-keys spec [:parent :direction]))
      (some? post) (post-edit! post)
      (some? md) (open-md! sel)
      (some? go) (follow-ref! go)
      (some? go-pair) (follow-pair! go-pair))))

(defn- avail-fn
  "available? for selection sel, as the one-argument predicate the
  chord menus take."
  [sel]
  (let [tgt (when (some? sel) (editor/target sel))]
    (fn [action] (available? sel tgt action))))

(defn- toolbar-items
  "The toolbar's buttons as chord items: every available chord (flat)
  or one per first key (grouped). Edge direction has its own row, and
  several pairs are followed by number, so flat leaves both out."
  [st]
  (let [sel (:selected st)
        kind (:kind sel)
        pairs (working-pairs sel)]
    (if (= "grouped" (:toolbar-layout st))
      (filterv (fn [it] (not (and (= kind "edge") (= "e" (first (:keys it))))))
               (editor/chord-menu kind [] (avail-fn sel) pairs))
      (filterv (fn [it] (let [a (:action it)]
                          (not (and (vector? a) (contains? #{"direction" "follow-pair"} (first a))))))
               (editor/chord-leaves kind (avail-fn sel) pairs)))))

(defn- choose-item!
  "Act on a toolbar or pop-out item reached from the pending keys
  `path` (nil from the toolbar): run its chord, or open its group —
  closing it when it is already the open one."
  [path item]
  (let [ks (into (or path []) (:keys item))]
    (cond
      (some? (:action item)) (do (swap! state assoc :chord nil)
                                 (run-chord-action! (:selected @state) (:action item)))
      (= ks (:chord @state)) (swap! state assoc :chord nil)
      :else (swap! state assoc :chord ks))))

(defn- item-btn
  "A toolbar or pop-out button for chord item `it` below `path`."
  [path it]
  [:button {:class (if (= "delete" (:action it)) "action-delete" "action-pick") :type "button"
            :on-click (fn [e] (.stopPropagation e) (choose-item! path it))}
   (:label it) (key-hint (str (.join (:keys it) " ") (if (= true (:group it)) " ▸" "")))])

(defn- chord-popout
  "What the pending keys can become, as buttons — the keyboard and the
  mouse share it."
  [st]
  (let [sel (:selected st)
        path (:chord st)]
    [:div {:id "chord-popout"}
     [:div {:class "chord-path"} (.join path " ") " … — Esc cancels"]
     (into [:div {:class "chord-items"}]
           (mapv (fn [it] (item-btn path it))
                 (editor/chord-menu (:kind sel) path (avail-fn sel) (working-pairs sel))))]))

(defn- layout-toggle
  "Switches the toolbar between flat and grouped; saved in this browser."
  [st]
  (let [grouped (= "grouped" (:toolbar-layout st))]
    [:button {:class "toolbar-layout" :type "button"
              :title (if grouped
                       "Grouped by first key — click for every tool in a row"
                       "Every tool in a row — click to group them by first key")
              :on-click (fn [e] (.stopPropagation e)
                          (let [v (if grouped "flat" "grouped")]
                            (store-toolbar-layout! v)
                            (swap! state assoc :toolbar-layout v :chord nil)))}
     (if grouped "grouped" "flat")]))

(defn- submit-id-entry!
  "Create what the open prompt is for (editor/creation-ops); a name that
  yields no id keeps the prompt open."
  [tgt]
  (when-let [{:keys [ops focus]} (editor/creation-ops (:id-entry @state) tgt)]
    (post-edit! ops)
    (swap! state assoc :pending-focus focus :focus-center false :id-entry nil)))

(defn- id-entry-row [tgt entry]
  [:div {:class "id-entry"}
   [:input {:class "id-entry-input" :type "text" :value (:text entry)
            :placeholder (case (:for entry)
                           ("newbox" "box" "box-inbox") "new box name, or name::type"
                           "edge" "new edge name, or name::type (optional)"
                           "new node name, or name::type")
            :on-render (fn [{:keys [node lifecycle]}]
                         (when (= lifecycle "mount") (.focus node)))
            :on-input (fn [e] (swap! state assoc-in [:id-entry :text] (.. e -target -value)))
            :on-keydown (fn [e]
                          (case (.-key e)
                            "Enter" (submit-id-entry! tgt)
                            "Escape" (cancel-id-entry!)
                            nil))}]])

(defn- action-bar [st sel tgt]
  (into [:div {:class "details-actions"}]
        (concat
         (when (= (:kind sel) "edge")
           (let [current (or (:direction (:attrs sel)) "-")]
             [(into [:div {:class "dir-group"}]
                    (mapv (fn [[label dir]] (direction-btn tgt current label dir))
                          direction-choices))]))
         (mapv (fn [it] (item-btn nil it)) (toolbar-items st))
         [(layout-toggle st)]
         (when-let [entry (:id-entry st)] [(id-entry-row tgt entry)]))))

(defn- pairs-view
  "The selection's pairs: → for those it declares, ← for those pointing
  at it; a broken one dimmed with its problem. Clicking a row follows it."
  [sel]
  (let [ps (or (:pairs sel) [])
        numbered (> (count (working-pairs sel)) 1)
        num-of (fn [i] (inc (count (filter (fn [q] (nil? (:problem q))) (take i ps)))))]
    (when (pos? (.-length ps))
      (into [:div {:class "details-pairs"} [:div {:class "details-pairs-header"} "pairs"]]
            (vec (map-indexed
                  (fn [i p]
                    (let [in? (= (:dir p) "in")
                          label (str (if in? "← " "→ ") (:file p) "#" (:id p)
                                     (if in? " (points here)" ""))]
                      (if (some? (:problem p))
                        [:div {:class "pair-row broken" :title (:problem p)}
                         label [:div {:class "pair-problem"} (:problem p)]]
                        [:button {:class "pair-row" :type "button"
                                  :on-click (fn [e] (.stopPropagation e) (follow-pair! p))}
                         (when (and numbered (<= (num-of i) 9))
                           [:kbd {:class "pair-num" :title (str "f p " (num-of i))} (str (num-of i))])
                         label])))
                  ps))))))

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
                          (str (fmt-val k (:old v)) " → " (fmt-val k (:new v)))]])
                      (js/Object.entries (:changed sel))))])
     (pairs-view sel)
     (into [:dl]
           (concat
            (when (not= (:kind sel) "edge")
              [[:dt {:key "t$id"} "id"]
               (if editable
                 (id-edit-row sel tgt editing)
                 [:dd {:key "d$id"} (.slice (:elk-id sel) 2)])])
            (mapcat (fn [[k v]]
                      [[:dt {:key (str "t" k)} k]
                       (if editable
                         (attr-edit-row sel tgt k v (editor/scalar? v) editing)
                         [:dd {:key (str "d" k)} (format/attr->hiccup k v)])])
                    (format/visible-attrs sel))))
     (when editable (attr-add-row sel tgt))]))

(defn- selection-toolbar
  "Floating bottom-center toolbar: the selection's edit tools (or, with
  nothing selected, what can be created), the layout toggle, and above
  them the pop-out while keys are pending. The inspector panel itself
  stays read/data-only."
  [st]
  (let [sel (:selected st)]
    [:div {:id "selection-toolbar"}
     (when (some? (:chord st)) (chord-popout st))
     (action-bar st sel (when (some? sel) (editor/target sel)))]))

(defn- banner-close
  "The banner's × — dismisses it without the click reaching the banner."
  [dismiss!]
  [:button {:class "banner-close" :type "button" :title "Dismiss" :aria-label "Dismiss"
            :on-click (fn [e] (.stopPropagation e) (dismiss!))}
   "×"])

(defn- banner
  "A banner: its text, and × when `dismiss!` is given (clicking the text
  does `on-click`, default the same dismissal)."
  [cls text dismiss! & [on-click]]
  [:div {:id "banner" :class (str cls (when (some? dismiss!) " dismissible"))
         :on-click (fn [_] (when-let [f (or on-click dismiss!)] (f)))}
   [:span {:class "banner-text"} text]
   (when (some? dismiss!) (banner-close dismiss!))])

(defn- banner-view
  "The one banner shown, most urgent first. Every one but \"not
  connected\" can be dismissed: an edit, navigation or notice error
  clears; the graph error and the warnings hide until their text changes
  (editor/banner-visible?)."
  [{:keys [error warnings collapsed edit-error disconnected nav-error notice
           dismissed-error dismissed-warnings]}]
  (let [warn-text (.join warnings "\n")]
    (cond
      disconnected
      (banner "error" "Not connected: the simpleviz server is not running. Restart it to resume live reload." nil)

      (some? edit-error)
      (banner "error" (str "Edit failed: " edit-error) #(swap! state assoc :edit-error nil))

      (some? nav-error)
      (banner "error" nav-error #(swap! state assoc :nav-error nil))

      (some? notice)
      (banner "error" notice #(swap! state assoc :notice nil))

      (editor/banner-visible? error dismissed-error)
      (banner "error" error #(swap! state assoc :dismissed-error error))

      (editor/banner-visible? warn-text dismissed-warnings)
      (banner (str "warning" (when collapsed " collapsed")) warn-text
              #(swap! state assoc :dismissed-warnings warn-text)
              #(swap! state update :collapsed not))

      :else nil)))

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
             :changed (:changed item)
             :pairs (:pairs item)}
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
              :title (when-not ok "PNG or SVG side is read-only")
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

(defn- trail-view
  "The files followed to reach the one shown, root first, each a
  button back to it; the current file last as plain text. Shown only
  once a ref has been followed (or the page loaded with a trail) — also
  when the file failed to open, so the crumbs lead back."
  [st]
  (let [trail (:trail (:nav st))]
    (when (and (seq trail) (some? (or (current-path st) (:file (:nav st)))))
      (into [:div {:id "trail"}]
            (concat
             (apply concat
                    (map-indexed
                     (fn [i f]
                       [[:button {:class "trail-crumb" :type "button" :key (str "c" i)
                                  :title (str "back to " f)
                                  :on-click (fn [e]
                                              (.stopPropagation e)
                                              (navigate! (editor/crumb-url trail i)))}
                         f]
                        [:span {:class "trail-sep" :key (str "s" i)} "›"]])
                     trail))
             [[:span {:class "trail-current" :key "cur"}
               (or (current-path st) (:file (:nav st)))]])))))

(def ^:private tooltip-el (js/document.getElementById "tooltip"))
;; the item the tooltip currently shows — its content is re-rendered only
;; when the pointer crosses onto another item, not on every move
(def ^:private tooltip-item (atom nil))

(defn- hide-tooltip! []
  (reset! tooltip-item nil)
  (set! (.-hidden tooltip-el) true))

(defn- tooltip-view [tip]
  [:div
   [:div {:class "tip-title"} (:title tip)]
   (when (pos? (.-length (:attrs tip)))
     (into [:dl]
           (mapcat (fn [[k v]]
                     [[:dt {:key (str "t" k)} k]
                      [:dd {:key (str "d" k)} (format/attr->hiccup k v)]])
                   (:attrs tip))))])

(defn- place-tooltip!
  "Below-right of the pointer, flipped to the other side where it would
  leave the viewport."
  [cx cy]
  (let [w (.-offsetWidth tooltip-el)
        h (.-offsetHeight tooltip-el)
        x (if (> (+ cx 14 w) js/window.innerWidth) (- cx 14 w) (+ cx 14))
        y (if (> (+ cy 14 h) js/window.innerHeight) (- cy 14 h) (+ cy 14))]
    (set! (.-left (.-style tooltip-el)) (str (js/Math.max 0 x) "px"))
    (set! (.-top (.-style tooltip-el)) (str (js/Math.max 0 y) "px"))))

(defn- update-hover!
  "Show the hovered element's name and attrs in the tooltip. Direct DOM
  writes: no state, no re-render of the app."
  [mx my cx cy]
  (let [p (hit/client->graph canvas/view mx my)
        s (:scene @state)
        item (when (some? s)
               (hit/hit-test s p (/ 8 (:k canvas/view)) (:k canvas/view)))
        tip (hit/hover-tip item)]
    (if (some? tip)
      (do (when-not (identical? item @tooltip-item)
            (reset! tooltip-item item)
            (render tooltip-el (tooltip-view tip)))
          (set! (.-hidden tooltip-el) false)
          (place-tooltip! cx cy))
      (hide-tooltip!))))

(defn- canvas-view []
  [:canvas
   {:id "canvas" :key "the-canvas"
    :on-pointermove
    (fn [e]
      ;; a held button is a pan or a click in progress — no tooltip then
      (if (pos? (.-buttons e))
        (hide-tooltip!)
        (let [rect (.getBoundingClientRect (.-currentTarget e))]
          (update-hover! (- (.-clientX e) (.-left rect))
                         (- (.-clientY e) (.-top rect))
                         (.-clientX e) (.-clientY e)))))
    :on-pointerleave (fn [_] (hide-tooltip!))
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
            :else (let [parent (get (:parent-of (:graph @state)) (:id item))
                        ops (editor/pick-ops pick (assoc item :parent parent))]
                    (when (some? ops)
                      (cancel-pick!)
                      ;; a new edge is named first: the prompt posts the ops
                      (if (= (:mode pick) "connect")
                        (swap! state assoc :id-entry {:for "edge" :ops ops :text ""})
                        (post-edit! ops)))))
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
      "Drag to pan, scroll to zoom. Hover an element to see its name and attributes; click it to inspect and edit them. A node of type database is drawn as a cylinder; :text on a node or box shows inside it. A double border marks a node with a :ref, a dotted border a node or box with an :md-ref (a linked markdown doc — "open md", f m, edits it); the mark on a node's corner is its :state — grey disc new, blue half disc in-progress, red square blocked, green check done. The − in a box header collapses the box to a single node — the panel on the left lists collapsed boxes and re-expands them. Top-level nodes and boxes with :grid [col row] sit on that grid cell; the rest arranges itself around them."
      "A :pair (\"views/deploy.edn#api\", or a vector of them) links a node or box to the same thing in another graph. The ⇄ mark on an element's bottom-left corner shows its pairs — red when one is broken; the inspector lists them, those declared here and those pointing here. Click one, or use \"follow pair\" (f p, or f p 1–9 with several — the inspector numbers them), to open that graph with the element selected.")
     (help-section
      "Edit"
      "When the served file is editable EDN, the floating toolbar at the bottom holds the tools for the current selection — every tool in a row, or grouped by first key with options that pop out (the toggle at its right end switches; saved in this browser): delete, edge direction, and pick modes such as \"add edge\" (click the other element on the canvas, then name the edge; Esc cancels). New nodes and boxes are created by name: the prompt types a name, and the id is derived from it — lowercased, illegal characters turned into dashes; name::type also sets the type. With nothing selected it creates a standalone node or box. A :ref attribute naming another graph file (relative path) makes \"follow ref\" open it — in a suffix comparison (simpleviz graph.edn next) it opens that file's own comparison; the trail at the top leads back. Following a ref to an .edn file that does not exist yet creates it as an empty graph — in a comparison the side picked by the old|new toggle."
      "In the inspector, click a value or its ✎ to edit it inline — Enter commits, Shift+Enter inserts a line break, Escape cancels. × deletes an attribute; the key/value row at the bottom adds one. Ctrl+Z or ↶ undoes the last edit."
      "\"open md\" (f m) opens the markdown file a node's or box's :md-ref names in a text panel on the right; ⤢ makes it fill the window, Esc docks it again. Ctrl+S or Save writes it, and so does closing it (×) or opening another; a missing file is created on the first save. If the file changes on disk while you have unsaved edits, the panel says so — Reload takes the file, Overwrite keeps yours.")
     (help-section
      "md pages"
      "simpleviz notes.md opens a markdown file in the doc editor, filling the window. A » in the gutter on the right marks each line with a link this page can follow — [text](sub/graph.edn), ![img](x.png), [text](other.md), reference links too; relative to the md file, never above the served folder. Click » to open it (»2 lists the links of that line); the trail at the top leads back. Unsaved text is saved before you leave the page — when that fails you stay. A :ref to an .md file opens it the same way; the doc panel of :md-ref has the » gutter too.")
     (help-section
      "Keys"
      "Chords act on the selection, when no text field has focus (the toolbar buttons show them); the first key opens a pop-out of what comes next, which can be clicked too: d d delete · e 1/2/3/4 edge direction → ← ↔ — · c s / c t change an edge's source / target · a e add edge · a b add to box (node) or add a box as member (box) · a n add a node as member (box) — either moves it out of the box it was in · n n n new node (connected to the selected node, or inside the selected box) · n n b new box (inside the selected box) · n n s new node in the selection's box · n n c / n n i new node in the selection's box, connected from / to it · n n e split the selected edge with a new node · n b wrap the selection in a new box · r r rename the id · r n take a node out of the selected box · r b take the selected node out of its box · f r follow the selection's :ref · f p follow the selection's pair (f p 1–9 with several) · f m open the selection's :md-ref doc (Ctrl+S saves it). Esc cancels a pending chord; ? toggles this help; Ctrl+Z undoes.")
     (help-section
      "Compare"
      "Serving a file with a suffix (simpleviz graph.edn next) renders it against its fork graph-next.edn as one merged diagram: added elements get a green +, modified an amber ~ (select for an old → new list), removed ones stay as red dashed ghosts. Click a legend row to jump through the changes; the old|new toggle picks which file edits apply to.")
     (help-section
      "Export"
      "⇩ opens the export menu: PNG downloads the diagram as an image, SVG as a vector drawing, both with the source EDN embedded. Either can be served again, compared, or turned back into EDN with \"simpleviz extract\".")
     (help-section
      "Layout and theme"
      "The layout menu at the top picks the layout: layered (left to right), compact — top-level boxes on a grid, edges leaving at the top and bottom too, wide boxes turned top to bottom, labels on vertical edges turned — or tiled, the same grid with every box left to right inside; compact and tiled spread big diagrams down as well as across. Your choice is saved in this browser; a file's :layout wins."
      "The theme menu at the top picks your theme, one of the twelve built-ins, for every graph without :theme; it's saved in this browser, and default follows your system's light or dark. A graph file can set its own theme instead — :theme :nord, or overrides on one such as {:base :nord :accent \"#b58900\"} — which wins: the menu then shows it, marked (file), and you change it in the file.")]))

(defn- hint-view
  "The line above the toolbar: the pending chord's completions, else the
  active pick mode's instruction, else the open edge-name prompt's."
  [st]
  (cond
    (some? (:flash st))
    [:div {:id "pick-hint"} (:flash st)]

    (some? (:pick st))
    [:div {:id "pick-hint"} (:pick-hint st) " — Esc cancels"]
    (= "edge" (:for (:id-entry st)))
    [:div {:id "pick-hint"} "name the new edge — Enter creates it, Esc cancels"]))

(defn- theme-menu-view
  "Your theme, for every graph without :theme (see editor/theme-menu):
  picking one saves it in this browser and repaints. A file's :theme
  wins — the menu then shows it, marked (file), disabled. Options carry
  :selected — reagami sets it as a property, so the menu follows."
  [g pref]
  (let [{:keys [value disabled title file]} (editor/theme-menu g pref)
        opt (fn [v label & [off]]
              [:option {:value v :selected (= v value) :disabled (boolean off)}
               (if (and file (= v value)) (str label " (file)") label)])]
    (into [:select {:id "theme-select" :title title :disabled disabled
                    :on-click (fn [e] (.stopPropagation e))
                    :on-change (fn [e]
                                 (let [el (.-target e)
                                       v (.-value el)]
                                   (store-theme! v)
                                   ;; paint first: the swap re-renders synchronously
                                   (apply-theme! (effective-theme (:graph @state) v (:theme @state)))
                                   (swap! state assoc :theme-pref (if (seq v) v nil))
                                   ;; hand the keys back to the chords
                                   (.blur el)))}
           (opt "" "default (follow the OS)")]
          (cond-> (mapv (fn [n] (opt n n)) themes/NAMES)
            (= value "custom") (conj (opt "custom" "custom" true))))))

(defn- layout-menu-view
  "Your layout algorithm, for every graph without :layout (see
  editor/layout-menu): picking one saves it in this browser and lays the
  graph out again, fitted to the window. A file's :layout wins — the
  menu then shows it, marked (file), disabled."
  [g pref]
  (let [{:keys [value disabled title file]} (editor/layout-menu g pref)]
    (into [:select {:id "layout-select" :title title :disabled disabled
                    :on-click (fn [e] (.stopPropagation e))
                    :on-change (fn [e]
                                 (let [el (.-target e)
                                       v (.-value el)]
                                   (try (js/localStorage.setItem layout-key v) (catch :default _ nil))
                                   (swap! state assoc :layout-pref v)
                                   (canvas/refit-next!)
                                   (relayout!)
                                   ;; hand the keys back to the chords
                                   (.blur el)))}]
          (mapv (fn [[v label]]
                  [:option {:value v :selected (= v value)}
                   (if (and file (= v value)) (str label " (file)") label)])
                editor/LAYOUTS))))

(defn- load-view [st]
  [:div {:id "loadscreen"}
   [:div {:class "load-spinner"}]
   [:div {:class "load-title"} "simpleviz"]
   [:div {:class "load-stage"} (or (:load-stage st) "loading…")]])

(defn- export-menu-view
  "The ⇩ button's menu: one item per export format. Choosing one closes
  it; so do Esc and a press outside it (see the listeners at init)."
  []
  (let [item (fn [label hint export!]
               [:button {:class "em-item" :type "button" :role "menuitem"
                         :on-click (fn [e]
                                     (.stopPropagation e)
                                     (swap! state assoc :export-menu false)
                                     (export!))}
                label [:span {:class "em-hint"} hint]])]
    [:div {:id "export-menu" :role "menu"}
     (item "PNG" "image" export-png!)
     (item "SVG" "vector" export-svg!)
     [:div {:class "em-note"} "Both embed the source EDN."]]))

(defn- gutter-view [st]
  (let [{:keys [marks height]} (:md-marks st)
        menu (:link-menu st)]
    [:div {:id "md-gutter"}
     (into [:div {:class "md-gutter-inner" :style {:height (str (or height 0) "px")}}]
           (map (fn [m]
                  (let [ls (:links m)
                        one (= 1 (count ls))]
                    [:button {:class (str "md-mark" (when (:bad m) " bad")) :type "button"
                              :key (str "m" (:line m))
                              :style {:top (str (:top m) "px")}
                              :title (:title m)
                              :on-click (fn [e]
                                          (.stopPropagation e)
                                          (if one
                                            (follow-link! (first ls))
                                            (let [body (.closest (.-currentTarget e) ".md-body")
                                                  top (- (.-top (.getBoundingClientRect (.-currentTarget e)))
                                                         (.-top (.getBoundingClientRect body)))]
                                              (swap! state assoc :link-menu
                                                     (when-not (= (:line menu) (:line m)) {:line (:line m) :top top})))))}
                     (if one "»" (str "»" (count ls)))]))
                (or marks [])))]))

(defn- link-menu-view [st]
  (when-let [menu (:link-menu st)]
    (let [md (:md st)
          m (some (fn [m] (when (= (:line m) (:line menu)) m)) (:marks (:md-marks st)))]
      (when (some? m)
        (into [:div {:class "md-link-menu" :role "menu" :style {:top (str (:top menu) "px")}}]
              (map-indexed (fn [i l]
                             [:button {:class (str "md-link-item"
                                                   (when (some? (:error (editor/link-target (:path md) (:dest l)))) " bad"))
                                       :type "button" :role "menuitem" :key (str "l" i)
                                       :title (editor/link-title (:path md) l)
                                       :on-click (fn [e] (.stopPropagation e) (follow-link! l))}
                              (str (:label l) " → " (:dest l))])
                           (:links m)))))))

(defn- md-panel [st]
  (let [md (:md st)]
    [:aside {:id "md-panel" :class (cond (:page md) "page" (:full md) "full" :else "")}
     [:div {:class "md-head"}
      [:span {:class "md-path" :title (:path md)} (:path md)]
      (when (editor/md-dirty? md) [:span {:class "md-dirty" :title "unsaved changes"} "●"])
      (when-not (:exists md) [:span {:class "md-new"} "new file"])
      [:span {:class "md-spacer"}]
      (when-not (:page md)
        [:button {:class "md-btn" :type "button"
                  :title (if (:full md) "Dock (Esc)" "Fullscreen")
                  :on-click (fn [e] (.stopPropagation e)
                              (swap! state assoc-in [:md :full] (not (:full (:md @state)))))}
         (if (:full md) "⤡" "⤢")])
      [:button {:class "md-btn" :type "button" :title "Save (Ctrl+S)" :disabled (= true (:saving md))
                :on-click (fn [e] (.stopPropagation e) (save-md!))}
       "Save"]
      (when-not (:page md)
        [:button {:class "md-btn" :type "button" :title "Save and close" :aria-label "Save and close"
                  :on-click (fn [e] (.stopPropagation e) (close-md!))}
         "×"])]
     (when (some? (:error md))
       [:div {:class "md-error"}
        [:span {:class "md-error-text"} (:error md)]
        (when (some? (:conflict md))
          [:button {:class "md-btn" :type "button" :title "Discard your edits, show the file on disk"
                    :on-click (fn [e] (.stopPropagation e)
                                (swap! state update :md editor/adopt-doc (:conflict md)))}
           "Reload"])
        (when (some? (:conflict md))
          [:button {:class "md-btn" :type "button" :title "Save your text over the file on disk"
                    :on-click (fn [e] (.stopPropagation e) (save-md! true))}
           "Overwrite"])])
     [:div {:class "md-body"}
      [:textarea {:id "md-text" :spellcheck "false" :value (:text md)
                  :on-scroll (fn [_] (sync-gutter-scroll!) (when (some? (:link-menu @state))
                                                              (swap! state assoc :link-menu nil)))
                  :on-input (fn [e] (swap! state assoc-in [:md :text] (.. e -target -value)))}]
      (gutter-view st)
      (link-menu-view st)]]))

(defn- app-view [st]
  (let [page (page? st)]
  [:div {:id "root" :class (str (when (some? (:pick st)) "picking")
                                (cond page " md-page"
                                      (some? (:md st)) " inspecting md-open"
                                      (some? (:selected st)) " inspecting"))}
   (hint-view st)
   (when (and (not page) (nil? (:scene st)) (nil? (:error st)))
     (load-view st))
   (when-not page (collapsed-view st))
   [:div {:id "top-center"}
    (trail-view st)
    (when (some? (:graph st)) (legend-view st))]
   (when (:layouting st)
     [:div {:id "layouting"} "re-layouting…"])
   ;; the banner and the controls share the top: the banner takes the room
   ;; left of the controls, so it never covers them (#111); both move left
   ;; of the inspector while one is open
   [:div {:id "top-bar"}
    (banner-view st)
   ;; one row, so hidden buttons leave no holes
   [:div {:id "top-right"}
    ;; always there and first, so it never shifts the others; disabled
    ;; until an edit keeps the old arrangement
    (when-not page
      (let [seeded (boolean (:seeded (:layout st)))]
        [:button {:id "relayout-btn" :type "button" :disabled (not seeded)
                  :title (if seeded
                           "Re-layout: edits kept the old arrangement, run a fresh layout"
                           "Re-layout: the layout is already fresh")
                  :on-click (fn [e] (.stopPropagation e) (relayout! true))}
         "▦"]))
    (when (some? (:graph st)) (layout-menu-view (:graph st) (:layout-pref st)))
    (when (or page (some? (:graph st))) (theme-menu-view (:graph st) (:theme-pref st)))
    (when (current-edit-target-editable? st)
      [:button {:id "undo-btn" :type "button" :title "Undo last edit (Ctrl+Z)"
                :on-click (fn [e] (.stopPropagation e) (post-edit! [{:op "undo"}]))}
       "↶"])
    (when (some? (:scene st))
      [:div {:class "export-anchor"}
       [:button {:id "export-btn" :type "button" :title "Export…"
                 :aria-haspopup "menu" :aria-expanded (if (:export-menu st) "true" "false")
                 :on-click (fn [e] (.stopPropagation e) (swap! state update :export-menu not))}
        "⇩"]
       (when (:export-menu st) (export-menu-view))])
    [:button {:id "help-btn" :type "button" :title "Help"
              :on-click (fn [e] (.stopPropagation e) (toggle-help!))}
     "?"]
    (help-view st)]]
   (canvas-view)
   (when (and (some? (:scene st)) (current-edit-target-editable? st))
     (selection-toolbar st))
   (cond (some? (:md st)) (md-panel st)
         (some? (:selected st)) (details-view st))]))

(defn- paint-now! []
  (when-let [canvas-el (js/document.getElementById "canvas")]
    (when-let [s (:scene @state)]
      (canvas/paint! canvas-el s (:elk-id (:selected @state))))))

(defn- rerender! []
  (render app-el (app-view @state))
  (canvas/request-paint!))

(defn- apply-pending-focus!
  "Select the element :pending-focus names (a scene id) once its scene
  item lands: a freshly created node after an edit, or the element a
  URL's focus names after navigating. Only the navigation case
  (:focus-center) pans to it and reports an element that isn't there —
  after an edit the seeded relayout puts the node beside its source,
  and a jump would undo the arrangement staying put. Always clears both
  keys: this is a one-shot request."
  [sc]
  (let [{:keys [pending-focus focus-center]} @state]
    (when (some? pending-focus)
      (let [item (some (fn [it] (when (= (:id it) pending-focus) it)) (:items sc))]
        (if (some? item)
          (do (on-select (item->payload item))
              (when focus-center (canvas/center-on! item)))
          (when focus-center
            (swap! state assoc :nav-error
                   (str "no node or box " (.slice pending-focus 2) " in "
                        (or (:file (:nav @state)) (:path (:graph @state)))))))))
    (swap! state assoc :pending-focus nil :focus-center false)))

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
  cache, reuse and state, and enables the re-layout button. `clean?` runs
  a fresh layout instead, dropping the whole cache so every collapsed
  set is laid out fresh when next shown."
  [& [clean?]]
  (try
    (when clean? (.clear layout-cache))
    (let [gen @graph-gen
          g0 (:graph @state)
          collapsed (:collapsed-boxes @state)
          mode (current-layout)
          ck (cache-key mode collapsed)
          hit (.get layout-cache ck)]
      (if (and (some? hit) (some? (:scene hit)))
        (do (canvas/fit-view-once! (:scene hit))
            (keep-pinned! (:scene hit))
            (swap! state (fn [st]
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
                cmap {:node (colors/assign-indices (mapv (fn [n] (:type n))
                                                         (js/Object.values (:nodes g0))))
                      :box (colors/assign-indices (mapv (fn [b] (:type b)) (:boxes g0)))}
                elk-graph (to-elk g canvas/measure)
                grid? (grid/grid-mode? g)
                ;; compact turns wide boxes top to bottom, tiled doesn't
                compact? (or (= mode "compact") (= mode "tiled"))
                ;; in grid (and compact) mode the cells shape the layout too
                fp (elk-fingerprint (if (or grid? compact?) {:elk elk-graph :cells (grid/grid-cells g)} elk-graph))
                prev (when (some? hit) (:layout hit))
                positions (when (some? prev) (layout-positions prev))
                ;; a demoted entry with a matching fingerprint means the
                ;; reload was attribute-only: the ELK inputs are identical,
                ;; so reuse its layout and skip the expensive ELK run
                layout (cond (and (some? prev) (= fp (:fingerprint hit)))
                             prev

                             ;; every top-level box on a cell (compact: wide ones
                             ;; turned top to bottom); unchanged boxes reuse prev's runs
                             compact?
                             (js-await (compact/layout-compact g elk-graph (fn [input] (.layout elk input)) prev
                                                               (= mode "compact")))

                             ;; boxes on grid cells; unchanged boxes reuse prev's runs
                             grid?
                             (js-await (grid/layout-grid g elk-graph (fn [input] (.layout elk input)) prev))

                             (and (some? prev) (seedable? elk-graph positions))
                             (assoc (js-await (.layout elk (seed-layout elk-graph positions)))
                                    :seeded true)

                             :else (js-await (.layout elk elk-graph)))
                sc (scene/build-scene {:layout layout :graph g :colors cmap :measure canvas/measure})]
            (when (= gen @graph-gen)
              (canvas/fit-view-once! sc)
              (when (> (.-size layout-cache) 16) (.clear layout-cache))
              (.set layout-cache ck {:fingerprint fp :colors cmap
                                     :layout layout :scene sc})
              (if (= ck (cache-key (current-layout) (:collapsed-boxes @state)))
                (do (keep-pinned! sc)
                    (swap! state (fn [st]
                                   (refresh-selection
                                    (assoc st :colors cmap :layout layout :scene sc
                                           :layouting false :diff-cursors {})
                                    sc)))
                    (apply-pending-focus! sc))
                (swap! state assoc :layouting false)))))))
    (catch :default e
      (js/console.error "Relayout failed:" e)
      ;; with a diagram on screen the failure isn't blocking: a notice
      (swap! state assoc :layouting false
             (if (some? (:scene @state)) :notice :error)
             (str "Render error: " (or (.-message e) (str e)))))))

(defn- resolve-edit-target
  "The :edit-target to use for a freshly loaded graph g, given the
  current value. Single-file mode always edits \"new\". In compare
  mode the current target is kept as long as its own editable flag
  (:editable for \"new\", :editable-old for \"old\") is true — the
  user's toggle choice survives every live-reload tick; only when it
  goes stale (that side turned/became a PNG or SVG) do we hop to whichever
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
    (let [resp (js-await (js/fetch (str "/api/graph" (file-query))))
          raw (js-await (.json resp))]
      (if (some? (:error raw))
        (do (when (nil? (:graph @state))
              ;; no graph to show (a navigation landed on a broken file):
              ;; back to your theme (or the OS's), which the menu shows again
              (apply-theme! (effective-theme nil (:theme-pref @state) (:theme @state))))
            (swap! state assoc :error (str "Graph error: " (:error raw)) :dismissed-error nil))
        (let [g (assoc raw :boxes-by-name
                       (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes raw)))
              first-load? (nil? (:graph @state))]
          (swap! graph-gen inc)
          (demote-layout-cache!)
          (set! (.-title js/document) (format/tab-title g))
          (apply-theme! (effective-theme g (:theme-pref @state) (:theme @state)))
          (swap! state (fn [st]
                         (assoc st :error nil :graph g :warnings (:warnings g)
                                :edit-target (resolve-edit-target g (:edit-target st)))))
          ;; big graphs open as a collapsed overview: all top-level boxes
          ;; start folded, drill in from there (also makes the first ELK
          ;; run cheap). Small graphs open fully expanded. The box holding
          ;; a URL's focus stays open, so the focused element has a scene
          ;; item to select (load-nav! clears :graph: every navigation
          ;; comes through here).
          (when (and first-load?
                     (> (.-length (js/Object.keys (:nodes g))) 500))
            (let [pf (:pending-focus @state)
                  keep-open (when (some? pf) (editor/top-box-of (:parent-of g) pf))]
              (swap! state assoc :collapsed-boxes
                     (set (keep (fn [b]
                                  (when (and (nil? (get (:parent-of g)
                                                        (str "b:" (:name b))))
                                             (not= (:name b) keep-open))
                                    (:name b)))
                                (:boxes g))))))
          (js-await (relayout!)))))
    (catch :default e
      (js/console.error "Reload failed:" e)
      (reset! last-mtime nil)
      (swap! state assoc :error (str "Render error: " (or (.-message e) (str e)))))))

(defn ^:async tick []
  (cond
    ;; the first load could not ask what the root is: try again
    (and (nil? (:file (:nav @state))) (nil? @root-path))
    (when (some? (js-await (root-path!))) (js-await (load-nav!)))

    ;; an md page (decided by the URL, so a failed open stays one): the
    ;; doc polls itself (poll-md!); this notices the server going away and
    ;; coming back, and retries an open that failed for want of a server
    (= "md" (editor/doc-kind (or (:file (:nav @state)) @root-path)))
    (let [ok (try (.-ok (js-await (js/fetch "/api/root"))) (catch :default _ false))
          st @state]
      (when (not= (not ok) (:disconnected st))
        (swap! state assoc :disconnected (not ok)))
      (when (and ok (nil? (:md st)) (nil? (:error st)))
        (js-await (open-page! (or (:file (:nav st)) @root-path)))))

    :else
    (let [mtime (try
                  (let [resp (js-await (js/fetch (str "/api/version" (file-query))))
                        v (js-await (.json resp))]
                    (:mtime v))
                  (catch :default _ nil))]
      ;; a failed poll means the server is gone (or restarting); the flag
      ;; clears on the next successful poll, so reconnection needs no action
      (when (not= (nil? mtime) (:disconnected @state))
        (swap! state assoc :disconnected (nil? mtime)))
      (when (and (some? mtime) (not= mtime @last-mtime))
        (reset! last-mtime mtime)
        (js-await (reload!))))))

(defn- ^:async load-nav!
  "The URL changed (follow, crumb, browser back) or the page loaded:
  re-read the navigation state, drop everything that belongs to the
  previous file — selection, edits in progress, collapsed boxes, cached
  layouts, the graph itself, the md page — and load what the URL now
  names: an md page or a graph. A docked doc stays open between graphs;
  an md page replaces it (navigate! and on-popstate! saved it), so an
  open that fails leaves no docked doc that would stop tick's retry."
  []
  (let [nav (editor/parse-nav js/location.search)
        md-target? (= "md" (editor/doc-kind (or (:file nav) @root-path)))]
    (reset! shown-query js/location.search)
    (swap! graph-gen inc)
    (swap! state (fn [st]
                   (assoc st :nav nav
                          :nav-error nil :error nil :notice nil :dismissed-error nil :dismissed-warnings nil
                          :warnings [] :graph nil :scene nil :layout nil
                          :selected nil :editing nil :edit-error nil :pick nil :pick-hint nil
                          :chord nil :id-entry nil :pending-focus (:focus nav) :focus-center true
                          :collapsed-boxes #{} :export-menu false
                          :md (when-not (or (page? st) md-target?) (:md st)))))
    (.clear layout-cache)
    (reset! last-mtime nil)
    (canvas/refit-next!)
    (let [path (or (:file nav) (js-await (root-path!)))]
      (if (= "md" (editor/doc-kind path))
        (js-await (open-page! path))
        (js-await (tick))))))

(defn- ^:async save-before-leaving!
  "Showing root-relative `target` (nil = the root) closes the open doc
  when it is the md page, or when an md page replaces the docked panel:
  save its unsaved text first. True when nothing unsaved is lost."
  [target]
  (let [md (:md @state)
        ;; a session opened on ?file=… has not asked for the root yet
        target (or target (js-await (root-path!)))]
    (if (and (some? md) (or (:page md) (= "md" (editor/doc-kind target))))
      (js-await (save-if-dirty!))
      true)))

(defn- ^:async navigate!
  "Show another file of the served folder: push its query string onto
  the browser history (so back returns here) and load it. A doc that
  would close with unsaved text is saved first; when that fails nothing
  moves and the doc panel shows why. Resolves true when it navigated."
  [query]
  (if (js-await (save-before-leaving! (:file (editor/parse-nav query))))
    (do (js/history.pushState nil "" (str js/location.pathname query))
        (js-await (load-nav!))
        true)
    false))

(defn- ^:async follow-ref!
  "Follow the selection's ref: resolve it against the file shown and
  navigate there, the current file joining the trail. While the file
  being edited is editable, the server is first asked to create the
  target (an empty graph, with its folders) in case nothing is there
  yet. A ref that climbs above the served folder is refused here with
  a banner; one the server refuses (wrong type, missing on a read-only
  side) shows as the graph error after navigating, with the trail
  intact to go back — which is also where a failed create ends up.
  A second follow while the first still waits on the server is dropped,
  or it would push the same URL onto the history twice."
  [ref]
  (let [st @state
        {:keys [trail]} (:nav st)
        current (current-path st)
        target (editor/resolve-ref current ref)]
    (cond
      (:following st) nil
      (nil? target)
      (swap! state assoc :nav-error (str "ref " (pr-str ref) " leaves the served folder"))
      :else
      (do
        (swap! state assoc :following true)
        (try
          (when (and (current-edit-target-editable? st) (= "graph" (editor/doc-kind target)))
            (try
              (js-await (js/fetch "/api/create"
                                  {:method "POST"
                                   :headers {"Content-Type" "application/json"}
                                   :body (js/JSON.stringify
                                          (editor/create-body (:edit-target st) target))}))
              (catch :default _ nil)))
          (js-await (navigate! (editor/follow-url current trail target)))
          (finally (swap! state assoc :following false)))))))

(defn- working-pairs
  "The pairs of selection payload sel that can be followed."
  [sel]
  (filterv (fn [p] (nil? (:problem p))) (or (:pairs sel) [])))

(defn- flash!
  "Show msg in the hint line above the toolbar for a few seconds."
  [msg]
  (swap! state assoc :flash msg)
  (js/setTimeout (fn [] (when (= msg (:flash @state)) (swap! state assoc :flash nil))) 3000))

(defn- ^:async follow-pair!
  "Open the file on the other end of pair p (a payload :pairs entry,
  :file root-relative) with its element selected and centered; the
  current file joins the trail. Unlike a ref, nothing is created: a
  pair names an element, which an empty graph doesn't have."
  [p]
  (let [st @state]
    (when-not (:following st)
      (swap! state assoc :following true)
      (try
        (js-await (navigate! (editor/follow-url (current-path st) (:trail (:nav st)) (:file p)
                                                (str (if (= (:kind p) "box") "b:" "n:") (:id p)))))
        (finally (swap! state assoc :following false))))))

(defn- ^:async on-popstate!
  "Back or forward moved the URL already: save a doc that would close
  with unsaved text; when that fails, put back the URL of what is shown
  and stay — the doc panel shows why."
  []
  (if (js-await (save-before-leaving! (:file (editor/parse-nav js/location.search))))
    (js-await (load-nav!))
    (js/history.pushState nil "" (str js/location.pathname @shown-query))))

(js/window.addEventListener "popstate" (fn [_] (on-popstate!)))

;; ---- the doc panel (:md-ref) ----

;; bumped by every open and save: a poll that started before is stale
(def ^:private md-gen (atom 0))

(defn- ^:async fetch-doc [path]
  (let [resp (js-await (js/fetch (str "/api/text?path=" (js/encodeURIComponent path))))]
    (js-await (.json resp))))

(defn- ^:async root-path!
  "The served root file's root-relative name, nil while the server
  can't be reached."
  []
  (or @root-path
      (try (let [out (js-await (.json (js-await (js/fetch "/api/root"))))]
             (reset! root-path (:path out)))
           (catch :default _ nil))))

(defn- ^:async open-page!
  "Show md file `path` as the page: the doc editor, full window. A path
  the server refuses shows as the error banner, the trail intact."
  [path]
  (try
    (let [out (js-await (fetch-doc path))]
      (if (some? (:error out))
        (swap! state assoc :md nil :error (str "Doc error: " (:error out)) :dismissed-error nil)
        (do (swap! md-gen inc)
            (apply-theme! (effective-theme nil (:theme-pref @state) (:theme @state)))
            (set! (.-title js/document) (str (format/basename path) " — simpleviz"))
            (swap! state assoc :error nil :disconnected false
                   :md (editor/adopt-doc {:path path :page true :full false :saving false} out))
            (focus-md-start!))))
    (catch :default _
      (swap! state assoc :disconnected true))))

(defn- focus-md! []
  (when-let [el (js/document.getElementById "md-text")] (.focus el)))

(defn- focus-md-start!
  "Focus a doc just opened at its start: the swap that adopted it has
  rendered the textarea, whose value — set as a property — left the
  caret at the end, where focusing would scroll."
  []
  (when-let [el (js/document.getElementById "md-text")]
    (.setSelectionRange el 0 0)
    (.focus el {:preventScroll true})
    (set! (.-scrollTop el) 0)))

(defn- ^:async save-md!
  "Write the open doc; `overwrite?` saves over the version the conflict
  banner found on disk instead of the one the edit started from.
  Resolves true when the file now holds the text sent — trivially when
  nothing is unsaved in an existing file. The text sent becomes :saved,
  so typing during the save stays unsaved."
  [& [overwrite?]]
  (let [md (:md @state)]
    (cond
      (nil? md) true
      (and (not overwrite?) (:exists md) (not (editor/md-dirty? md))) true
      (:saving md) false
      :else
      (let [text (:text md)
            base (if overwrite? (:version (:conflict md)) (:base md))]
        (swap! state assoc-in [:md :saving] true)
        (try
          (let [resp (js-await (js/fetch "/api/text/save"
                                         {:method "POST"
                                          :headers {"Content-Type" "application/json"}
                                          :body (js/JSON.stringify
                                                 {:path (:path md)
                                                  :text (editor/to-disk text (:crlf md))
                                                  :base (or base nil)})}))
                out (js-await (.json resp))]
            (swap! md-gen inc)
            (swap! state update :md editor/save-result (:path md) text out)
            (and (nil? (:error out)) (some? (:version out))))
          (catch :default _
            (swap! state update :md editor/save-result (:path md) text
                   {:error "save failed: not connected"})
            false))))))

(defn- ^:async save-if-dirty!
  "Close and switch save only unsaved text: an untouched new file is
  not created (an explicit Save does create it). Resolves true when
  nothing is left unsaved."
  []
  (if (editor/md-dirty? (:md @state))
    (js-await (save-md!))
    true))

(defn- ^:async open-md!
  "Open the doc the selection's :md-ref names in the panel; the doc
  open before is saved first and stays open when that fails."
  [sel]
  (let [st @state
        {:keys [path error]} (or (editor/md-target (:path (:graph st)) sel) {})]
    (cond
      (some? error) (swap! state assoc :nav-error error)
      (nil? path) nil
      (= path (:path (:md st))) (focus-md!)
      (js-await (save-if-dirty!))
      (try
        (let [out (js-await (fetch-doc path))]
          (if (some? (:error out))
            (swap! state assoc :nav-error (str "Can't open " path ": " (:error out)))
            (do (swap! md-gen inc)
                (swap! state assoc :md (editor/adopt-doc {:path path :full false :saving false} out))
                (focus-md-start!))))
        (catch :default _
          (swap! state assoc :nav-error (str "Can't open " path ": not connected")))))))

(defn- ^:async close-md! []
  (when (js-await (save-if-dirty!))
    (swap! state assoc :md nil)))

(defn- take-disk!
  "Show the fetched doc, keeping the caret at its offset (clamped)."
  [fetched]
  (let [el (js/document.getElementById "md-text")
        focused? (and (some? el) (= el (.-activeElement js/document)))
        pos (when focused? (.-selectionStart el))]
    (swap! state update :md editor/adopt-doc fetched)
    (when focused?
      (let [p (min pos (.-length (.-value el)))]
        (.setSelectionRange el p p)))))

(defn- ^:async poll-md!
  "The 1 s check of the open doc: take a changed disk text when nothing
  is unsaved, flag a conflict when something is. Skipped while a save
  runs; a result that a save or open overtook is dropped."
  []
  (let [md (:md @state)
        gen @md-gen]
    (when (and (some? md) (not (:saving md)))
      (let [out (try (js-await (fetch-doc (:path md))) (catch :default _ nil))
            cur (:md @state)]
        (when (and (some? out) (some? cur) (= gen @md-gen)
                   (= (:path cur) (:path md)) (not (:saving cur)))
          (if (some? (:error out))
            (swap! state assoc-in [:md :error] (:error out))
            (case (editor/poll-outcome cur out)
              "take" (take-disk! out)
              "gone" (swap! state update :md editor/gone-doc)
              "conflict" (swap! state update :md assoc :error "changed on disk"
                                :conflict {:text (:text out) :version (:version out)
                                           :exists (:exists out)})
              nil)))))))

;; the open doc's links, re-scanned 150 ms after the text stops changing
(def ^:private md-links (atom {:path nil :text nil :links []}))
(def ^:private parse-timer (atom nil))
(def ^:private measure-queued (atom false))
(def ^:private observed-ta (atom nil))

(defn- mirror-el
  "The hidden div that lays doc text out the way the textarea wraps it —
  outside the app root, so rendering never touches its children."
  []
  (or (js/document.getElementById "md-mirror")
      (let [d (js/document.createElement "div")]
        (set! (.-id d) "md-mirror")
        (.setAttribute d "aria-hidden" "true")
        (.appendChild js/document.body d)
        d)))

(defn- sync-gutter-scroll!
  "Scroll the gutter with the textarea."
  []
  (let [ta (js/document.getElementById "md-text")
        g (js/document.getElementById "md-gutter")]
    (when (and (some? ta) (some? g))
      (set! (.-scrollTop g) (.-scrollTop ta)))))

(declare queue-measure!)

(def ^:private resize-observer
  (js/ResizeObserver. (fn [_] (queue-measure!))))

(defn- measure-gutter!
  "Place the » markers: each logical line of the text is a block in the
  mirror, styled and sized like the textarea, and a marker sits at its
  line's block top. Each mark also carries its tooltip and whether its
  one link leaves the served folder, so rendering doesn't resolve links."
  []
  (reset! measure-queued false)
  (let [ta (js/document.getElementById "md-text")
        md (:md @state)]
    (when (and (some? ta) (some? md))
      (when-not (identical? ta @observed-ta)
        (.disconnect resize-observer)
        (.observe resize-observer ta)
        (reset! observed-ta ta))
      (let [mirror (mirror-el)
            cs (js/getComputedStyle ta)
            lines (.split (:text md) "\n")]
        (doseq [p ["fontFamily" "fontSize" "fontWeight" "lineHeight" "letterSpacing" "tabSize"
                   "paddingTop" "paddingRight" "paddingBottom" "paddingLeft"]]
          (aset (.-style mirror) p (aget cs p)))
        (set! (.. mirror -style -width) (str (.-clientWidth ta) "px"))
        (set! (.-textContent mirror) "")
        (doseq [ln lines]
          (let [d (js/document.createElement "div")]
            (set! (.-textContent d) (if (= ln "") "​" ln))
            (.appendChild mirror d)))
        (let [kids (.-children mirror)
              n (.-length kids)
              marks (vec (keep (fn [m]
                                 (when (<= (:line m) n)
                                   (let [ls (:links m)
                                         one (= 1 (count ls))]
                                     (assoc m
                                            :top (.-offsetTop (aget kids (dec (:line m))))
                                            :bad (and one (some? (:error (editor/link-target (:path md) (:dest (first ls))))))
                                            :title (if one (editor/link-title (:path md) (first ls))
                                                       (str (count ls) " links"))))))
                               (editor/line-markers (:links @md-links))))
              height (max (.-scrollHeight mirror) (.-scrollHeight ta))]
          (swap! state assoc :md-marks {:marks marks :height height})
          (sync-gutter-scroll!))))))

(defn- queue-measure!
  "Measure the gutter on the next frame, once however often asked."
  []
  (when-not @measure-queued
    (reset! measure-queued true)
    (js/requestAnimationFrame (fn [_] (measure-gutter!)))))

(defn- reparse-links!
  "Scan the open doc's links: now when `now?` (another doc), else 150 ms
  after the last change; then re-measure."
  [now?]
  (some-> @parse-timer js/clearTimeout)
  (let [run (fn []
              (let [md (:md @state)]
                (reset! md-links {:path (:path md) :text (:text md)
                                  :links (if (some? md) (mdlinks/links (:text md)) [])})
                (queue-measure!)))]
    (if now? (run) (reset! parse-timer (js/setTimeout run 150)))))

;; keep links and markers in step with the open doc, however it changed
;; (typing, a doc taken from disk, open, close, dock ↔ page ↔ fullscreen)
(add-watch state :md-gutter
  (fn [_ _ old new]
    (let [o (:md old) n (:md new)]
      (cond
        (not= (:path o) (:path n)) (do (when (nil? n) (swap! state assoc :md-marks nil :link-menu nil))
                                       (reparse-links! true))
        ;; typing: measure once, after the debounced scan, against fresh links
        (not= (:text o) (:text n)) (reparse-links! false)
        (or (not= (:page o) (:page n)) (not= (:full o) (:full n))) (queue-measure!)))))

(defn- ^:async follow-link!
  "Follow link l (an mdlinks/links entry) of the open doc: its
  destination resolves against the doc's own path; the page shown joins
  the trail. An .edn target is first created as an empty graph when
  missing (from the md page always, from the docked panel when the
  graph shown is editable) — as follow ref does."
  [l]
  (let [st @state
        md (:md st)
        {:keys [path kind error]} (editor/link-target (:path md) (:dest l))]
    (swap! state assoc :link-menu nil)
    (cond
      (:following st) nil
      (some? error) (swap! state assoc :nav-error error)
      :else
      (do
        (swap! state assoc :following true)
        (try
          (when (and (= kind "graph") (.endsWith (.toLowerCase path) ".edn")
                     (or (:page md) (current-edit-target-editable? st)))
            (try
              (js-await (js/fetch "/api/create"
                                  {:method "POST"
                                   :headers {"Content-Type" "application/json"}
                                   :body (js/JSON.stringify (editor/create-body (:edit-target st) path))}))
              (catch :default _ nil)))
          (js-await (navigate! (editor/follow-url (current-path st) (:trail (:nav st)) path)))
          (finally (swap! state assoc :following false)))))))

(defn- ^:async post-edit!
  "POST ops to the edit target — or to `file` (\"old\"/\"new\") when given."
  [ops & [file]]
  (let [resp (js-await (js/fetch "/api/edit"
                                 {:method "POST"
                                  :headers {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify
                                         (let [body (editor/edit-body (or file (:edit-target @state)) ops)
                                               f (:file (:nav @state))]
                                           (if (some? f) (assoc body :path f) body)))}))
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

(defn- rename-cache-key
  "Cache key k with collapsed box `old` called `new`, unchanged otherwise."
  [k old new]
  (let [i (.indexOf k "#")
        mode (.slice k 0 i)
        tail (.slice k (inc i))
        names (if (= tail "") [] (.split tail "|"))]
    (if (some (fn [n] (= n old)) names)
      (cache-key mode (mapv (fn [n] (if (= n old) new n)) names))
      k)))

(defn- rename-cached-layouts!
  "Every cached layout now calls element `old` `new`, so the seeded
  relayout after a rename finds it placed instead of treating it as new.
  For a box, `box-names` [old-name new-name] also re-keys the entries
  of collapsed sets that contain it."
  [old new box-names]
  (doseq [k (js/Array.from (.keys layout-cache))]
    (let [e (.get layout-cache k)
          e' (if (some? (:layout e))
               (assoc e :layout (rename-layout-ids (:layout e) old new))
               e)
          k' (if-let [[o n] box-names] (rename-cache-key k o n) k)]
      (when (not= k k') (.delete layout-cache k))
      (.set layout-cache k' e'))))

(defn- rename-collapsed! [old new]
  (swap! state update :collapsed-boxes
         (fn [s] (if (contains? s old) (-> s (disj old) (conj new)) s))))

(defn- ^:async rename!
  "Rename the selected node or box to `to`: keep its place in the layout
  and re-select it under the new id once the reload lands. An empty or
  unchanged id just closes the field. The client-side bookkeeping is
  done up front (the reload runs inside post-edit!) and rolled back when
  the edit fails or the request never returns.
  `with` (or nil) rides another edit along: its :ops go into the same
  batch ahead of the rename, and on failure the field it came from
  (:field, with :text — omit to reopen nothing) reopens instead of the
  id field. Nothing reopens when the selection moved on meanwhile: the
  field would land on the other element seeded with this one's text."
  [sel tgt to with]
  (let [to (.trim to)
        old-id (:id tgt)
        old-elk (:elk-id sel)
        new-elk (str (.slice old-elk 0 2) to)
        box? (= (:kind sel) "box")]
    (if (or (= to "") (= to old-id))
      (swap! state assoc :editing nil)
      (do
        ;; one-shot: close the field now, so the blur the re-render fires
        ;; cannot post a second rename while this one is in flight
        (swap! state assoc :editing nil :pending-focus new-elk :focus-center false)
        (rename-cached-layouts! old-elk new-elk (when box? [old-id to]))
        (when box? (rename-collapsed! old-id to))
        (let [ok? (try
                    (js-await (post-edit! (conj (vec (:ops with)) (editor/rename-op tgt to))))
                    (nil? (:edit-error @state))
                    (catch :default _ false))]
          (when-not ok?
            (rename-cached-layouts! new-elk old-elk (when box? [to old-id]))
            (when box? (rename-collapsed! to old-id))
            (swap! state assoc :pending-focus nil)
            ;; reopen with the rejected text so it can be corrected
            (when (= old-elk (:elk-id (:selected @state)))
              (cond
                (nil? with) (start-editing! ID-FIELD to)
                (some? (:field with)) (start-editing! (:field with) (:text with))))))))))

(defn- ^:async delete! [tgt]
  (js-await (post-edit! [(editor/delete-op tgt)]))
  ;; :selected is a payload snapshot, not a live lookup — after a
  ;; successful delete the element it describes is gone from the
  ;; reloaded graph, so the panel would keep showing stale data.
  (when (nil? (:edit-error @state))
    (on-select nil)))

(defn- effective-theme
  "The theme to show for graph g: the file's :theme (resolved by the
  server), else your theme `pref`, else light or dark as the OS has it."
  [g pref os-theme]
  (or (:theme g) (get themes/THEMES pref) (get themes/THEMES os-theme)
      (get themes/THEMES :light)))

(defn- apply-theme!
  "Paint page and canvas in theme, a complete theme map: the chrome via
  CSS custom properties on <html>, the canvas via the painter's palette.
  Call it before the state change that re-renders — rendering is
  synchronous and the collapsed-panel dots read the palette."
  [theme]
  (let [style (.. js/document -documentElement -style)]
    (doseq [k themes/CSS-KEYS]
      (.setProperty style (str "--" k) (get theme k))))
  (canvas/set-theme! theme)
  (canvas/request-paint!))

(defn- ^:async fetch-source
  "Raw EDN text from /api/source (which = \"old\"|\"new\"|nil), or nil on
  any failure — a failed fetch degrades the export to metadata-less."
  [which]
  (try
    (let [fq (file-query)
          resp (js-await (js/fetch (str "/api/source" fq
                                        (when (some? which)
                                          (str (if (= fq "") "?" "&") "which=" which)))))]
      (if (.-ok resp) (js-await (.text resp)) nil))
    (catch :default _ nil)))

(defn- ^:async export-sources
  "The [key text] pairs an export embeds: the served EDN, or in compare
  mode the old and new EDN — each omitted when its fetch fails."
  [g]
  (if (some? (:compare g))
    (let [o (js-await (fetch-source "old"))
          n (js-await (fetch-source "new"))]
      (cond-> []
        (some? o) (conj ["simpleviz-edn-old" o])
        (some? n) (conj ["simpleviz-edn-new" n])))
    (let [s (js-await (fetch-source nil))]
      (if (some? s) [["simpleviz-edn" s]] []))))

(defn- export-name
  "Download name for an export of g, without extension: the served
  file's name minus .edn/.png/.svg, else \"graph\"."
  [g]
  (let [f (:file g)]
    (if (some? f) (.replace f (js/RegExp. "\\.(edn|png|svg)$") "") "graph")))

(defn- download-blob!
  "Trigger a browser download of blob as <nm>.<ext> via a throwaway
  object URL and anchor click."
  [blob nm ext]
  (let [url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) (str nm "." ext))
    (.click a)
    (js/setTimeout (fn [] (js/URL.revokeObjectURL url)) 1000)))

(defn- ^:async png-bytes
  "The PNG ⇩ downloads, as a Uint8Array, the sources embedded (plain if
  embedding fails for some unexpected reason). Throws when the canvas
  cannot be encoded."
  []
  (let [pairs (js-await (export-sources (:graph @state)))
        cnv (canvas/export-canvas (:scene @state))
        blob (js-await (js/Promise. (fn [res] (.toBlob cnv res "image/png"))))]
    (when (nil? blob)
      (throw (js/Error. "PNG export failed — the diagram may be too large")))
    (let [u8 (js/Uint8Array. (js-await (.arrayBuffer blob)))]
      (try (png/embed-many u8 pairs)
           (catch :default _ u8)))))

(defn- ^:async svg-text
  "The SVG ⇩ downloads, the sources embedded (see svg/svg-document)."
  []
  (canvas/export-svg (:scene @state) (js-await (export-sources (:graph @state)))))

(defn- ^:async export-png!
  "Download the whole diagram as PNG; a failure shows in the error banner."
  []
  (when (some? (:scene @state))
    (let [nm (export-name (:graph @state))]
      (try (download-blob! (js/Blob. [(js-await (png-bytes))] {:type "image/png"}) nm "png")
           (catch :default e (swap! state assoc :notice (.-message e)))))))

(defn- ^:async export-svg!
  "Download the whole diagram as SVG, the source EDN embedded like the
  PNG's. A failure shows in the error banner, as the PNG export's does,
  rather than as an unseen rejected promise."
  []
  (when (some? (:scene @state))
    (let [nm (export-name (:graph @state))]
      (try (download-blob! (js/Blob. [(js-await (svg-text))] {:type "image/svg+xml"}) nm "svg")
           (catch :default e
             (swap! state assoc :notice
                    (str "SVG export failed — " (or (.-message e) (str e)))))))))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- ^:async wait-for!
  "Resolve once (readiness @state) is {:ready true}; reject with its
  :error. Polls every 50 ms — the CLI bounds the wait."
  [readiness]
  (let [r (readiness @state)]
    (cond (some? (:error r)) (throw (js/Error. (:error r)))
          (:ready r) true
          :else (do (js-await (sleep 50)) (js-await (wait-for! readiness))))))

(defn- ^:async headless-export
  "window.simplevizExport: wait for the graph, take `theme` as this page's
  theme preference (never stored), expand every box, wait for the layout,
  then hand back what the ⇩ menu would download — {:data base64} for
  \"png\", {:data svg-text} for \"svg\"."
  [opts]
  (let [fmt (.-format opts)
        theme (.-theme opts)]
    (js-await (wait-for! editor/load-readiness))
    (when (some? theme)
      (apply-theme! (effective-theme (:graph @state) theme (:theme @state)))
      (swap! state assoc :theme-pref theme))
    ;; a render error notice from before must not fail this export
    (swap! state assoc :notice nil)
    (when (pos? (.-size (:collapsed-boxes @state)))
      (swap! state assoc :collapsed-boxes #{} :selected nil)
      (js-await (relayout!)))
    (js-await (wait-for! editor/export-readiness))
    (if (= fmt "svg")
      {:data (js-await (svg-text))}
      {:data (png/bytes->base64 (js-await (png-bytes)))})))

;; init
(defn- typing?
  "True while a text field or a menu has the focus — keys belong to it
  then (a focused menu jumps between options as letters are typed)."
  []
  (let [tag (.-tagName (.-activeElement js/document))]
    (or (= tag "INPUT") (= tag "TEXTAREA") (= tag "SELECT"))))

(defn- run-chord-action!
  "Do what the toolbar button for `action` would do for selection sel."
  [sel action]
  (let [tgt (when (some? sel) (editor/target sel))]
    (cond
      (and (vector? action) (= "direction" (first action)))
      (post-edit! [(editor/direction-op tgt (second action))])

      (= action "delete") (delete! tgt)
      (= action "rename") (start-editing! ID-FIELD (:id tgt))
      :else (start-action! sel tgt action))))

(defn- handle-chord-key!
  "Feed a plain key press into the chords: it extends the pending keys
  (:chord) while they are the start of a longer chord for the
  selection; a complete chord runs and closes; any other key closes.
  Bare modifier, arrow and other named keys are not chord keys."
  [e]
  (let [k (.-key e)
        st @state
        sel (:selected st)
        pairs (working-pairs sel)
        path (conj (or (:chord st) []) k)]
    (when (and (= 1 (.-length k)) (nil? (:pick st)) (some? (:scene st)))
      (cond
        (editor/chord-prefix? (:kind sel) path (avail-fn sel) pairs)
        (do (.preventDefault e) (swap! state assoc :chord path))

        (some? (:chord st))
        (do (.preventDefault e)
            (swap! state assoc :chord nil)
            (when-let [action (editor/chord-action (:kind sel) path pairs)]
              (run-chord-action! sel action)))))))

(js/window.addEventListener "keydown"
  (fn [e]
    (cond
      (and (or (.-ctrlKey e) (.-metaKey e))
           (= (.toLowerCase (.-key e)) "s")
           (some? (:md @state)))
      (do (.preventDefault e) (save-md!))

      (= (.-key e) "Escape") (do (cancel-pick!)
                                 (swap! state assoc :help false :chord nil :export-menu false :link-menu nil)
                                 (when (:full (:md @state))
                                   (swap! state assoc-in [:md :full] false)))
      ;; a held key must not complete its own chord
      (.-repeat e) nil
      (and (= (.-key e) "?") (not (typing?)))
      (do (.preventDefault e) (toggle-help!))
      (and (or (.-ctrlKey e) (.-metaKey e))
           (= (.toLowerCase (.-key e)) "z")
           (not (typing?))
           (current-edit-target-editable? @state))
      (do (.preventDefault e)
          (swap! state assoc :chord nil)
          (post-edit! [{:op "undo"}]))

      (and (not (or (.-ctrlKey e) (.-metaKey e) (.-altKey e)))
           (not (typing?))
           (current-edit-target-editable? @state))
      (handle-chord-key! e))))
;; a press anywhere outside the export menu closes it — ⇩ itself toggles
;; it — one outside the toolbar closes the pop-out, and one outside the
;; doc's link menu and its » markers closes that menu (capture phase, so
;; nothing that stops propagation keeps any of them open)
(js/document.addEventListener "pointerdown"
  (fn [e]
    (when (and (:export-menu @state)
               (not (.closest (.-target e) "#export-menu, #export-btn")))
      (swap! state assoc :export-menu false))
    (when (and (some? (:chord @state))
               (not (.closest (.-target e) "#selection-toolbar")))
      (swap! state assoc :chord nil))
    (when (and (some? (:link-menu @state))
               (not (.closest (.-target e) ".md-link-menu, .md-mark")))
      (swap! state assoc :link-menu nil)))
  true)
(canvas/set-repaint! paint-now!)
;; the headless export's entry point (simpleviz export, server/browser.clj)
(set! (.-simplevizExport js/window) headless-export)
(apply-theme! (effective-theme (:graph @state) (:theme-pref @state) (:theme @state)))
(add-watch state :render (fn [_ _ _ _] (rerender!)))
(canvas/setup-pan-zoom! (js/document.getElementById "canvas-wrap"))
(rerender!)
(load-nav!)
(js/setInterval tick 1000)
(js/setInterval poll-md! 1000)
(js/window.addEventListener "resize" (fn [_] (queue-measure!)))
(.then (.-ready js/document.fonts) (fn [_] (queue-measure!)))
;; unsaved doc edits: the browser asks before the tab closes or reloads
(js/window.addEventListener "beforeunload"
  (fn [e]
    (when (editor/md-dirty? (:md @state))
      (.preventDefault e)
      (set! (.-returnValue e) ""))))
