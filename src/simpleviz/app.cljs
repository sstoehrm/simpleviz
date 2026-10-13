(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.format :as format]
            [simpleviz.transform :refer [to-elk elk-fingerprint layout-positions seed-layout seedable?
                                         rename-layout-ids diff-glyphs]]
            [simpleviz.prune :refer [collapse-boxes collapse-scene]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]
            [simpleviz.png :as png]
            [simpleviz.editor :as editor]
            [simpleviz.markdown :as markdown]
            [simpleviz.slides :as slides]
            [simpleviz.vim :as vim]
            [simpleviz.manual :as manual]
            [mdlinks :as mdlinks]
            [paths :as paths]
            [themes :as themes]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

;; your choices (theme, vi mode) live on the server (/api/prefs), the same
;; for every port — localStorage is per origin, and the port is random
(defn- save-pref!
  "Store your choices, {:theme v} or {:vi v} (\"\" forgets one); a
  failed save only means the choice doesn't outlive the page."
  [m]
  (-> (js/fetch "/api/prefs" {:method "POST"
                              :headers {"Content-Type" "application/json"}
                              :body (js/JSON.stringify m)})
      (.catch (fn [_] nil))))

(def ^:private per-file
  "The state that belongs to the file shown, as it starts: load-nav!
  restores it on every navigation."
  {:nav-error nil :error nil :notice nil :dismissed-error nil :dismissed-warnings nil
   :graph nil :scene nil :layout nil
   ;; the layout drawn: :layout placed by hand (manual/place) in a manual
   ;; file; :arrange — dragging moves nodes and boxes (the ✥ button)
   :placed nil :arrange false
   :selected nil :editing nil :edit-error nil :pick nil :pick-hint nil
   :chord nil :id-entry nil :focus-center true
   ;; select mode (s s, s a, s r): {:ids the scene ids of the nodes and
   ;; boxes selected, :rect true while the next drag spans a rectangle}
   :multi nil
   :collapsed-boxes #{} :export-menu false})

(def state (atom (merge per-file
                 {:colors nil :collapsed false :layouting false
                  :diff-cursors {}
                  :edit-target "new"
                  ;; a URL naming an element (focus=n:api) selects and centers it
                  :pending-focus (:focus (editor/parse-nav js/location.search))
                  :flash nil
                  :help false :disconnected false
                  ;; the open :md-ref doc (open-md!), nil when the panel is closed
                  :md nil
                  ;; the graph an md page shows beside it (open-side!), root-relative
                  :side nil
                  ;; the md page presented (show-slide!): {:slide i :dest its >> target
                  ;; :side what shows beside it when that is no graph}, nil when not
                  :present nil
                  :nav (editor/parse-nav js/location.search) :nav-error nil
                  ;; your theme (#115), for files without :theme (load-prefs!)
                  :theme-pref nil
                  ;; vi mode in the doc's text (toggle-vi!), and its state
                  :vi-pref false
                  :vim (vim/init)
                  ;; light or dark as the OS has it, when you chose none
                  :theme (if (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)"))
                           "dark"
                           "light")})))
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

(defn- split?
  "Does the md page show a graph beside it?"
  [st]
  (and (page? st) (some? (:side st))))

(defn- presenting?
  "Is the md page being presented?"
  [st]
  (and (page? st) (some? (:present st))))

;; the slides of the last text parsed: views ask on every render
(def ^:private deck-cache (atom {:text nil :slides []}))

(defn- deck
  "The slides of the md page's text."
  [st]
  (let [text (:text (:md st))
        hit @deck-cache]
    (if (= text (:text hit))
      (:slides hit)
      (let [ds (slides/slides text)]
        (reset! deck-cache {:text text :slides ds})
        ds))))

(defn- graph-shown?
  "Is a graph on screen: the page's own, or the one beside an md page?"
  [st]
  (or (not (page? st)) (some? (:side st))))

(defn- graph-file
  "The root-relative path of the graph shown, nil for the root: the side
  graph on an md page, else the navigation's file."
  [st]
  (if (page? st) (:side st) (:file (:nav st))))

(defn- current-path
  "The root-relative path of the file the page shows: the md page's,
  else the graph's."
  [st]
  (if (page? st) (:path (:md st)) (:path (:graph st))))

(defn- shown-path
  "The root-relative path navigation state `nav` names: its file, else
  the root's (nil until /api/root has answered)."
  [nav]
  (or (:file nav) @root-path))

(defn- file-query
  "\"?file=<rel>\" for the graph the page is showing, \"\" for the root."
  []
  (let [f (graph-file @state)]
    (if (some? f) (str "?file=" (js/encodeURIComponent f)) "")))

(defn- on-select
  "Select one element (nil: none); selecting one ends select mode."
  [payload]
  (swap! state (fn [st] (assoc st :selected payload :editing nil :id-entry nil :chord nil :nav-error nil
                               :multi (when (nil? payload) (:multi st))))))

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
         save-if-dirty! save-before-leaving! follow-link! sync-gutter-scroll!
         follow-to! close-side! focus-md-start! show-slide! leave-present!
         enter-select! select-all! copy-selected! delete-selected! paste!)

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

;; bumped by every slide change and navigation: what a slide's >> loads
;; after the next slide (or another page) came is dropped
(def ^:private present-gen (atom 0))

(defn- cache-key
  "Layouts are cached per collapsed-set: \"a|b\"."
  [collapsed]
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
      [:span {:class "attr-val"} (format/value->hiccup v)]
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

(defn- absent-status
  "The compare status of the elements the file edited does not have:
  the old file lacks what was added, the new one what was removed."
  [st]
  (if (= "old" (:edit-target st)) "added" "removed"))

(defn- action-spec
  "What toolbar action `action` does for selection sel (tgt its op
  target): a pick mode to start, a name prompt to open, ops to post, a
  file to follow or a thunk to run; nil when it does nothing for sel.
  One table for the buttons and the chords, so both always agree."
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
        "direction" {:post [(editor/direction-op tgt (second action))]}
        nil)
      (case action
        "delete" {:run #(delete! tgt)}
        ;; the selection comes along — unless the file edited lacks it
        "select-mode" {:run #(enter-select! (if (and (contains? #{"node" "box"} (:kind sel))
                                                     (not= (absent-status @state) (:diff sel)))
                                              #{(:elk-id sel)}
                                              #{})
                                            false)}
        "select-all" {:run select-all!}
        "select-rect" {:run #(enter-select! (or (:ids (:multi @state)) #{}) true)}
        ;; what acts on the selection waits for something selected
        ("copy" "cut" "delete-selected")
        (when (seq (:ids (:multi @state)))
          {:run (case action "copy" #(copy-selected! false) "cut" #(copy-selected! true) delete-selected!)})
        "paste" {:run #(paste! sel)}
        "rename" {:run #(start-editing! ID-FIELD id)}
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
  (some? (action-spec sel tgt action)))

(defn- start-action!
  "Do what the toolbar button for `action` does."
  [sel tgt action]
  (let [{:keys [pick hint id-entry post go go-pair md run] :as spec} (action-spec sel tgt action)]
    (cond
      (some? run) (run)
      (some? pick) (start-pick! pick hint)
      (some? id-entry) (start-id-entry! id-entry (select-keys spec [:parent :direction]))
      (some? post) (post-edit! post)
      (some? md) (open-md! sel)
      (some? go) (follow-ref! go)
      (some? go-pair) (follow-pair! go-pair))))

(defn- chord-kind
  "The kind the chords and the toolbar go by: \"select\" in select
  mode, else the selection's (nil for none)."
  [st]
  (if (some? (:multi st)) "select" (:kind (:selected st))))

(defn- avail-fn
  "available? for selection sel, as the one-argument predicate the
  chord menus take."
  [sel]
  (let [tgt (when (some? sel) (editor/target sel))]
    (fn [action] (available? sel tgt action))))

(defn- toolbar-items
  "The toolbar's buttons as chord items: one per first key, whose
  options pop out. Edge direction has its own row, so an edge's \"e\"
  group is left out."
  [st]
  (let [sel (:selected st)
        kind (chord-kind st)]
    (filterv (fn [it] (not (and (= kind "edge") (= "e" (first (:keys it))))))
             (editor/chord-menu kind [] (avail-fn sel) (working-pairs sel)))))

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
  [:button {:class (if (contains? #{"delete" "delete-selected"} (:action it)) "action-delete" "action-pick") :type "button"
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
                 (editor/chord-menu (chord-kind st) path (avail-fn sel) (working-pairs sel))))]))

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
         (when-let [entry (:id-entry st)] [(id-entry-row tgt entry)]))))

(defn- pairs-view
  "The selection's pairs: → for those it declares, ← for those pointing
  at it; a broken one dimmed with its problem. Clicking a row follows it
  — unless `follow?` is false (presenting: following would leave)."
  [sel follow?]
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
                      (cond
                        (some? (:problem p))
                        [:div {:class "pair-row broken" :title (:problem p)}
                         label [:div {:class "pair-problem"} (:problem p)]]
                        (not follow?)
                        [:div {:class "pair-row"} label]
                        :else
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
     (pairs-view sel (not (presenting? st)))
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
                         [:dd {:key (str "d" k)} (format/value->hiccup v)])])
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
  [{:keys [error graph collapsed edit-error disconnected nav-error notice
           dismissed-error dismissed-warnings]}]
  (let [warn-text (.join (or (:warnings graph) []) "\n")]
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
  until the element is clicked again. Cleared when the element is gone —
  and so is what select mode holds."
  [st sc]
  (let [st (if-let [sel (:selected st)]
             (let [item (some (fn [it] (when (= (:id it) (:elk-id sel)) it)) (:items sc))]
               (assoc st :selected (if (some? item) (item->payload item) nil)))
             st)]
    (if-let [m (:multi st)]
      (let [here (set (map :id (:items sc)))]
        (assoc st :multi (assoc m :ids (set (filter (fn [id] (contains? here id)) (:ids m))))))
      st)))

(defn- cycle-diff! [status]
  (let [stops (get (scene/diff-stops (:scene @state)) status)]
    (when (pos? (.-length stops))
      (let [idx (mod (inc (get (:diff-cursors @state) status -1))
                     (.-length stops))
            item (nth stops idx)]
        (swap! state (fn [st]
                       (-> st
                           (assoc-in [:diff-cursors status] idx)
                           ;; one element selected: select mode ends
                           (assoc :selected (item->payload item) :multi nil))))
        (canvas/center-on! item)))))

(defn- legend-row [st status stops]
  (let [n (.-length stops)
        idx (get (:diff-cursors st) status)]
    [:button {:key status :type "button"
              :class (str "dl-row" (if (zero? n) " dl-empty" ""))
              :disabled (zero? n)
              :title (if (zero? n)
                       (str "no " status " elements")
                       (str "jump to the next " status " element"))
              :on-click (fn [e] (.stopPropagation e) (cycle-diff! status))}
     [:span {:class (str "dl-key dl-" status)} (get diff-glyphs status)]
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
       (legend-row st "added" (get stops "added"))
       (legend-row st "modified" (get stops "modified"))
       (legend-row st "removed" (get stops "removed"))
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
                                              (navigate! (editor/crumb-url trail i (or (current-path st) (:file (:nav st))))))}
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
                      [:dd {:key (str "d" k)} (format/value->hiccup v)]])
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

(defn- item-at
  "The scene item (or collapse button) under the canvas pointer event e."
  [e]
  (when-let [s (:scene @state)]
    (let [rect (.getBoundingClientRect (.-currentTarget e))
          k (:k canvas/view)]
      (hit/hit-test s (hit/client->graph canvas/view
                                         (- (.-clientX e) (.-left rect))
                                         (- (.-clientY e) (.-top rect)))
                    (/ 8 k) k))))

(defn- update-hover!
  "Show the hovered element's name and attrs in the tooltip. Direct DOM
  writes: no state, no re-render of the app; the tip is rebuilt only
  when the hovered item changes."
  [e]
  (let [item (item-at e)
        ;; tooltip-item is set only while the tip shows it
        same? (and (some? item) (identical? item @tooltip-item))
        tip (when-not same? (hit/hover-tip item))]
    (cond
      same? (place-tooltip! (.-clientX e) (.-clientY e))
      (some? tip) (do (reset! tooltip-item item)
                      (render tooltip-el (tooltip-view tip))
                      (set! (.-hidden tooltip-el) false)
                      (place-tooltip! (.-clientX e) (.-clientY e)))
      :else (hide-tooltip!))))

(defn- canvas-view []
  [:canvas
   {:id "canvas" :key "the-canvas"
    :on-pointermove
    (fn [e]
      ;; a held button is a pan or a click in progress — no tooltip then
      (if (pos? (.-buttons e))
        (hide-tooltip!)
        (update-hover! e)))
    :on-pointerleave (fn [_] (hide-tooltip!))
    :on-click
    (fn [e]
      ;; drag-ending clicks never arrive here: pointer capture (acquired
      ;; only mid-drag) retargets them to the wrap
      (let [item (item-at e)
            pick (:pick @state)]
        (cond
          ;; select mode: a node or box joins the selection, or leaves it
          (and (nil? pick) (some? (:multi @state)))
          (cond (= (:kind item) "collapse-button") (toggle-collapse! (.slice (:box-id item) 2))
                (and (contains? #{"node" "box"} (:kind item)) (not= (absent-status @state) (:diff item)))
                (swap! state update-in [:multi :ids]
                       (fn [ids] (if (contains? ids (:id item)) (disj ids (:id item)) (conj ids (:id item))))))

          (some? pick)
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
          (= (:kind item) "collapse-button")
          (toggle-collapse! (.slice (:box-id item) 2))
          :else
          (on-select (when (some? item) (item->payload item))))))}])

(defn- current-edit-target-editable?
  "Can the graph shown be edited? Never while presenting."
  [st]
  (when-let [g (:graph st)]
    (and (not (presenting? st))
         (boolean (if (= (:edit-target st) "old") (:editable-old g) (:editable g))))))

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
      "Drag to pan, scroll to zoom. Hover an element to see its name and attributes; click it to inspect and edit them. A node of type database is drawn as a cylinder; :text on a node or box shows inside it. A double border marks a node with a :ref, a dotted border a node or box with an :md-ref (a linked markdown doc — \"open md\", f m, edits it); the mark on a node's corner is its :state — grey disc new, blue half disc in-progress, red square blocked, green check done. The − in a box header collapses the box to a single node — the panel on the left lists collapsed boxes and re-expands them."
      "A :pair (\"views/deploy.edn#api\", or a vector of them) links a node or box to the same thing in another graph. The ⇄ mark on an element's bottom-left corner shows its pairs — red when one is broken; the inspector lists them, those declared here and those pointing here. Click one, or use \"follow pair\" (f p, or f p 1–9 with several — the inspector numbers them), to open that graph with the element selected.")
     (help-section
      "Edit"
      "When the served file is editable EDN, the floating toolbar at the bottom holds the tools for the current selection, grouped by first key with options that pop out: delete, edge direction, and pick modes such as \"add edge\" (click the other element on the canvas, then name the edge; Esc cancels). New nodes and boxes are created by name: the prompt types a name, and the id is derived from it — lowercased, illegal characters turned into dashes; name::type also sets the type. With nothing selected it creates a standalone node or box. A :ref attribute naming another graph file (relative path) makes \"follow ref\" open it — in a suffix comparison (simpleviz graph.edn next) it opens that file's own comparison; the trail at the top leads back. Following a ref to an .edn file that does not exist yet creates it as an empty graph — in a comparison the side picked by the old|new toggle."
      "In the inspector, click a value or its ✎ to edit it inline — Enter commits, Shift+Enter inserts a line break, Escape cancels. × deletes an attribute; the key/value row at the bottom adds one. Ctrl+Z or ↶ undoes the last edit."
      "\"open md\" (f m) opens the markdown file a node's or box's :md-ref names in a text panel on the right; ⤢ makes it fill the window, Esc docks it again. Ctrl+S or Save writes it, and so does closing it (×) or opening another; a missing file is created on the first save. If the file changes on disk while you have unsaved edits, the panel says so — Reload takes the file, Overwrite keeps yours."
      "vi (top of a doc's text) turns on vi keys for the text of every doc, until you turn them off: Esc for normal mode, i a I A o O for insert; h j k l w b e W B E 0 ^ $ gg G f t F T ; , { } / ? n N move, with a count too; d c y take a motion or a text object (iw aw i\" a\" i( a( i[ i{ …), dd cc yy D C Y; x X s S r J ~ p P edit, v and V select, u and Ctrl+R undo and redo, . repeats. :w saves, :q closes the doc panel — on an md page it shows the text read — :wq does both, :12 goes to line 12. A bare Esc in normal mode still docks the panel; :q! drops unsaved edits first.")
     (help-section
      "md pages"
      "simpleviz notes.md opens a markdown file rendered, in a centered column — headings, emphasis, quotes, lists and task lists, links, pictures, tables, footnotes, code; Edit switches to the plain text, Read back. Its links to graphs and md files follow on click — [text](sub/graph.edn), ![img](x.png), [text](other.md), reference links too; relative to the md file, never above the served folder. In the plain text a » in the gutter marks each line with such a link (»2 lists the links of that line). A graph or exported image opens to the right of the text, its link and » highlighted, and works like any graph; ⤢ full view opens it on its own, × closes it. An md file opens in place of this one; the trail at the top leads back. Unsaved text is saved before you leave the page — when that fails you stay. A :ref to an .md file opens it the same way; the doc panel of :md-ref has the » gutter too."
      "Present shows the page as slides: a line of just --- starts the next one, and a slide whose first line is >> [text](path) — >> and a link — shows that graph, md file or picture beside its text. → or space steps forward, ← back, Home and End jump; Esc leaves. The graph can be hovered, inspected, panned and collapsed, not edited.")
     (help-section
      "Keys"
      "Chords act on the selection, when no text field has focus (the toolbar buttons show them); the first key opens a pop-out of what comes next, which can be clicked too: d d delete · e 1/2/3/4 edge direction → ← ↔ — · c s / c t change an edge's source / target · a e add edge · a b add to box (node) or add a box as member (box) · a n add a node as member (box) — either moves it out of the box it was in · n n n new node (connected to the selected node, or inside the selected box) · n n b new box (inside the selected box) · n n s new node in the selection's box · n n c / n n i new node in the selection's box, connected from / to it · n n e split the selected edge with a new node · n b wrap the selection in a new box · r r rename the id · r n take a node out of the selected box · r b take the selected node out of its box · f r follow the selection's :ref · f p follow the selection's pair (f p 1–9 with several) · f m open the selection's :md-ref doc (Ctrl+S saves it) · s s select mode: click nodes and boxes to add or drop them · s a select all · s r select by dragging a rectangle · then y y copy / x x cut / d d delete them — a box with what it holds, and the edges among them all · p p paste what was copied or cut, here or in another graph (into the selected box; a taken id gets -1). Esc cancels a pending chord and ends select mode; ? toggles this help; Ctrl+Z undoes.")
     (help-section
      "Compare"
      "Serving a file with a suffix (simpleviz graph.edn next) renders it against its fork graph-next.edn as one merged diagram: added elements get a green +, modified an amber ~ (select for an old → new list), removed ones stay as red dashed ghosts. Click a legend row to jump through the changes; the old|new toggle picks which file edits apply to.")
     (help-section
      "Export"
      "⇩ opens the export menu: PNG downloads the diagram as an image, SVG as a vector drawing, both with the source EDN embedded. Either can be served again, compared, or turned back into EDN with \"simpleviz extract\".")
     (help-section
      "Manual layout"
      "✥ (top right) places nodes and boxes by hand: the first time, every top-level node and box gets a :pos [col row] on a grid of 20 px cells, where the layout had it (Ctrl+Z takes that back). While ✥ is on, drag a top-level node or box — a box by any part of it, its contents along — and it snaps cell by cell; letting go writes its :pos. ✥ or Esc ends arranging. Boxes still lay out their insides; edges between top-level elements run straight. auto drops every :pos, after asking.")
     (help-section
      "Theme"
      "The theme menu at the top picks your theme, one of the twelve built-ins, for every graph without :theme; it's kept in ~/.simpleviz, and default follows your system's light or dark. A graph file can set its own theme instead — :theme :nord, or overrides on one such as {:base :nord :accent \"#b58900\"} — which wins: the menu then shows it, marked (file), and you change it in the file.")]))

;; ---- manual layout (simpleviz.manual) ----

(defn- arrangeable?
  "Can the graph shown be placed by hand: an editable file, not a
  comparison (an element the edited side lacks would fail the edit)."
  [st]
  (and (some? (:scene st)) (current-edit-target-editable? st) (nil? (:compare (:graph st)))))

(defn- arranging?
  "Does dragging move elements: ✥ is on and the graph is placed by hand
  (an undone or failed first placement leaves ✥ on over an automatic
  layout)."
  [st]
  (and (:arrange st) (arrangeable? st) (manual/manual? (:graph st))))

(defn- show-saved-layout!
  "Back to the layout the file says, after a drag that wrote nothing."
  []
  (when-let [e (.get layout-cache (cache-key (:collapsed-boxes @state)))]
    (when (some? (:scene e)) (show-layout! e))))

(defn- elk-target
  "The edit op target of top-level elk id `id` (\"n:a\", \"b:grp\")."
  [id]
  {:section (if (.startsWith id "n:") "nodes" "boxes") :id (.slice id 2)})

(defn- pos-op [id [col row]]
  (editor/set-attr-op (elk-target id) "pos" (str "[" col " " row "]") false))

(defn- ^:async toggle-arrange!
  "✥: arrange on or off. Turned on in a file laid out automatically, it
  first gives every top-level node and box the cell nearest where the
  layout shown put it — one edit, Ctrl+Z takes it back — and arranges
  once that went through."
  []
  (let [st @state
        entry (.get layout-cache (cache-key (:collapsed-boxes st)))]
    (cond
      (arranging? st) (swap! state assoc :arrange false)
      (manual/manual? (:graph st)) (swap! state assoc :arrange true)
      ;; only from the layout on screen: a demoted one may name
      ;; elements an edit since removed
      (some? (:scene entry))
      (do (js-await (post-edit! (mapv (fn [[id cell]] (pos-op id cell)) (manual/snapshot (:layout entry)))))
          (when (nil? (:edit-error @state)) (swap! state assoc :arrange true))))))

(defn- auto-layout!
  "Drop every :pos, after asking: the layout is automatic again."
  []
  (when (js/confirm "Forget where you placed the nodes and boxes and lay the graph out automatically again?")
    (let [g (:graph @state)
          ops (into (vec (keep (fn [n] (when (some? (:pos n)) (editor/del-attr-op (elk-target (str "n:" (:id n))) "pos")))
                               (js/Object.values (:nodes g))))
                    (keep (fn [b] (when (some? (:pos b)) (editor/del-attr-op (elk-target (str "b:" (:name b))) "pos"))))
                    (:boxes g))]
      (swap! state assoc :arrange false)
      (post-edit! ops))))

(defn- arrange-drag
  "The canvas's drag hook: while arranging a hand-placed graph, a press
  on a top-level node or box drags it — the view shows it on the cell
  under the pointer, and letting go writes that cell as its :pos."
  [gx gy]
  (let [st @state
        placed (:placed st)
        entry (.get layout-cache (cache-key (:collapsed-boxes st)))]
    (when (and (arranging? st) (some? placed) (some? entry))
      (when-let [id (manual/top-at placed {:x gx :y gy})]
        (let [c (first (filter (fn [c] (= id (:id c))) (:children placed)))
              g (collapse-boxes (:graph st) (:collapsed-boxes st))
              start (manual/xy->cell (:x c) (:y c))
              cell (atom start)]
          {:move (fn [dx dy]
                   (let [p (manual/xy->cell (+ (:x c) dx) (+ (:y c) dy))]
                     (when (not= p @cell)
                       (reset! cell p)
                       (let [g' (manual/with-pos g id p)
                             placed' (manual/place (:layout entry) g')]
                         (swap! state assoc :placed placed'
                                :scene (scene/build-scene {:layout placed' :graph g' :colors (:colors entry)
                                                           :measure canvas/measure}))))))
           ;; Esc mid-drag ended arranging: nothing is written
           :end (fn []
                  (cond (= @cell start) nil
                        (not (arranging? @state)) (show-saved-layout!)
                        :else (-> (post-edit! [(pos-op id @cell)])
                                  (.then (fn [_] (when (some? (:edit-error @state)) (show-saved-layout!)))))))
           :cancel show-saved-layout!})))))

(defn- hint-view
  "The line above the toolbar: the pending chord's completions, else the
  active pick mode's instruction, else the open edge-name prompt's."
  [st]
  (cond
    (some? (:flash st))
    [:div {:id "pick-hint"} (:flash st)]

    (some? (:multi st))
    [:div {:id "pick-hint"}
     (if (:rect (:multi st))
       "drag a rectangle around nodes and boxes — Esc cancels"
       (str (count (:ids (:multi st))) " selected — click nodes and boxes to add or drop them · "
            "y y copy · x x cut · d d delete — Esc ends"))]

    (some? (:pick st))
    [:div {:id "pick-hint"} (:pick-hint st) " — Esc cancels"]
    (= "edge" (:for (:id-entry st)))
    [:div {:id "pick-hint"} "name the new edge — Enter creates it, Esc cancels"]))

(defn- theme-menu-view
  "Your theme, for every graph without :theme (see editor/theme-menu):
  picking one saves it for every graph and repaints. A file's :theme
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
                                   (save-pref! {:theme v})
                                   ;; paint first: the swap re-renders synchronously
                                   (apply-theme! (effective-theme (:graph @state) v (:theme @state)))
                                   (swap! state assoc :theme-pref (if (seq v) v nil))
                                   ;; hand the keys back to the chords
                                   (.blur el)))}
           (opt "" "default (follow the OS)")]
          (cond-> (mapv (fn [n] (opt n n)) themes/NAMES)
            (= value "custom") (conj (opt "custom" "custom" true))))))

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
  (let [{:keys [marks height scrollbar]} (:md-marks st)
        menu (:link-menu st)
        side (when (page? st) (:side st))]
    [:div {:id "md-gutter" :style {:right (str (or scrollbar 0) "px")}}
     (into [:div {:class "md-gutter-inner" :style {:height (str (or height 0) "px")}}]
           (map (fn [m]
                  (let [ls (:links m)
                        one (= 1 (count ls))]
                    [:button {:class (str "md-mark" (when (:bad m) " bad")
                                          (when (editor/mark-open? (:path (:md st)) m side) " open"))
                              :type "button"
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

(defn- picture
  "Image link `l` of the md file at `md-path` as the picture: a web URL as
  it is, a picture of the served folder through /api/file; nil for
  anything else, which stays a link."
  [md-path l]
  (let [dest (:dest l)]
    (if (.test #"^https?:" dest)
      [:img {:class "md-img" :src dest :alt (:label l)}]
      (let [{:keys [path]} (editor/link-target md-path dest)
            kind (when (some? path) (slides/side-kind path))]
        (when (or (= kind "image") (= kind "export"))
          [:img {:class "md-img" :alt (:label l)
                 :src (str "/api/file?path=" (js/encodeURIComponent path))}])))))

(defn- in-page-click
  "A click on a link within the page — a footnote and the way back:
  scroll to its target. The URL's # stays as it is: navigation reads the
  URL."
  [e]
  (when-let [a (.closest (.-target e) "a[href^='#']")]
    (.preventDefault e)
    ;; in its own container: a slide and the md file beside it may both
    ;; have a footnote 1
    (when-let [el (.querySelector (.-currentTarget e)
                                  (str "#" (js/CSS.escape (.slice (.getAttribute a "href") 1))))]
      (.scrollIntoView el {:block "center" :behavior "smooth"}))))

(defn- md-link-fn
  "Renders a link of the md page's read view: a followable one follows
  as its » does (pressed while its graph shows beside the page), a URL
  opens in a new tab, anything else is text that says why it isn't a
  link. An image shows as the picture — one that also follows (an
  exported graph) inside its link."
  [md-path side]
  (fn [l kids]
    (let [dest (:dest l)
          pic (when (= "image" (:kind l)) (picture md-path l))
          kids (if (some? pic) [pic] kids)]
      (cond
        (and (some? pic) (not (mdlinks/followable? dest))) pic
        (.test #"^(https?|mailto):" dest)
        (into [:a {:href dest :target "_blank" :rel "noopener noreferrer" :title dest}] kids)
        (mdlinks/followable? dest)
        (into [:a {:href "#"
                   :class (str "md-link"
                               (when (some? (:error (editor/link-target md-path dest))) " bad")
                               (when (editor/mark-open? md-path {:links [l]} side) " open"))
                   :title (editor/link-title md-path l)
                   :on-click (fn [e] (.preventDefault e) (.stopPropagation e) (follow-link! l))}]
              kids)
        :else
        (into [:span {:class "md-link dead"
                      :title (str (if (= dest "") "a link within the page" dest)
                                  " — not a file simpleviz opens")}]
              kids)))))

;; the read view's hiccup for the last [path text side], so rerenders
;; for anything else neither re-parse nor rebuild its handlers
(def ^:private md-view-cache (atom nil))

(defn- md-view [st]
  (let [md (:md st)
        k [(:path md) (:text md) (:side st)]
        hit @md-view-cache]
    (if (and (some? hit) (= k (:key hit)))
      (:view hit)
      (let [bs (markdown/render (:text md) (md-link-fn (:path md) (:side st)))
            view (into [:div {:id "md-view" :on-click in-page-click}]
                       (if (seq bs) bs [[:p {:class "md-empty"} "Nothing here yet — Edit to write."]]))]
        (reset! md-view-cache {:key k :view view})
        view))))

(defn- toggle-md-edit! []
  (let [editing (not (:editing (:md @state)))]
    (swap! state assoc-in [:md :editing] editing)
    (when editing (focus-md-start!))))

(defn- md-panel [st]
  (let [md (:md st)
        reading (and (:page md) (not (:editing md)))]
    [:aside {:id "md-panel" :class (cond (:page md) "page" (:full md) "full" :else "")}
     [:div {:class "md-head"}
      [:span {:class "md-path" :title (:path md)} (:path md)]
      (when (editor/md-dirty? md) [:span {:class "md-dirty" :title "unsaved changes"} "●"])
      (when-not (:exists md) [:span {:class "md-new"} "new file"])
      [:span {:class "md-spacer"}]
      (when (and (:page md) (seq (deck st)))
        [:button {:class "md-btn md-present" :type "button" :title "Present the text as slides, split at --- lines"
                  :on-click (fn [e] (.stopPropagation e) (.blur (.-currentTarget e)) (show-slide! 0))}
         "Present"])
      (when-not reading
        [:button {:class (if (:vi-pref st) "md-btn md-vi on" "md-btn md-vi") :type "button"
                  :aria-pressed (if (:vi-pref st) "true" "false")
                  :title (if (:vi-pref st) "Vi mode is on — click to type plainly" "Edit with vi keys")
                  :on-click (fn [e] (.stopPropagation e) (toggle-vi!))}
         "vi"])
      (when (:page md)
        [:button {:class "md-btn md-mode" :type "button"
                  :title (if reading "Edit the text" "Show it rendered")
                  :on-click (fn [e] (.stopPropagation e) (toggle-md-edit!))}
         (if reading "Edit" "Read")])
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
     (if reading
       [:div {:class "md-body"} (md-view st)]
       [:div {:class "md-body"}
        [:textarea {:id "md-text" :spellcheck "false" :value (:text md)
                    :on-scroll (fn [_] (sync-gutter-scroll!) (when (some? (:link-menu @state))
                                                                (swap! state assoc :link-menu nil)))
                    :on-input (fn [e] (swap! state assoc-in [:md :text] (.. e -target -value)))
                    :on-keydown vim-key!
                    :on-beforeinput vim-before-input!
                    :on-mouseup vim-mouse!}]
        (gutter-view st)
        (link-menu-view st)])
     (when (and (:vi-pref st) (not reading))
       (let [s (vim/status (:vim st))]
         [:div {:class "md-vim"} (if (= s "") "\u00a0" s)]))]))

(defn- present-link-fn
  "Renders the links on a slide of the md file at `md-path`: an image as
  the picture, a URL opening in a new tab, anything else as its text —
  following a file would leave the presentation."
  [md-path]
  (fn [l kids]
    (let [pic (when (= "image" (:kind l)) (picture md-path l))]
      (cond
        (some? pic) pic
        (.test #"^(https?|mailto):" (:dest l))
        (into [:a {:href (:dest l) :target "_blank" :rel "noopener noreferrer" :title (:dest l)}] kids)
        :else (into [:span] kids)))))

(defn- present-panel
  "The slide presented, in the md page's place: the window, or its left
  half while something shows beside it. The first slide, alone, is the
  title slide, centered."
  [st]
  (let [ds (deck st)
        n (count ds)
        i (:slide (:present st))
        s (nth ds i nil)]
    [:aside {:id "md-panel" :class "page"}
     [:div {:id "present-slide" :class (if (and (= i 0) (nil? (:side s))) "title" "")}
      (into [:div {:class "md-doc" :on-click in-page-click}]
            (if (some? s)
              (markdown/render (:text s) (present-link-fn (:path (:md st))))
              [[:p {:class "md-empty"} "No slides left — the text is empty."]]))]
     [:div {:id "present-counter"} (str (min (inc i) n) " / " n)]]))

(defn- present-side-view
  "Beside the slide: the md file or picture its >> names, or why it
  can't show. A graph shows on the canvas instead."
  [st]
  (when-let [{:keys [kind path text]} (:side (:present st))]
    [:div {:id "present-side" :class kind}
     (case kind
       "image" [:img {:src (str "/api/file?path=" (js/encodeURIComponent path)) :alt path
                      :on-error (fn [_]
                                  (when (= path (:path (:side (:present @state))))
                                    (swap! state assoc-in [:present :side]
                                           {:kind "error" :text (str path " — no such picture, or not one the browser shows")})))}]
       "md" (into [:div {:class "md-doc" :on-click in-page-click}]
                  (if (some? text)
                    (markdown/render text (present-link-fn path))
                    [[:p {:class "md-empty"} "loading…"]]))
       [:div {:class "present-side-error"} text])]))

(defn- side-view
  "Over the graph beside an md page: its path, a button that opens it
  in full view and one that closes it. Before one is open, a hint where
  it will show."
  [st]
  (if-let [side (:side st)]
    [:div {:id "side-bar"}
     [:span {:class "side-path" :title side} side]
     (when (and (nil? (:scene st)) (nil? (:error st)))
       [:span {:class "side-loading"} "loading…"])
     [:button {:class "side-btn" :type "button" :title "Open this graph in full view"
               :on-click (fn [e] (.stopPropagation e) (follow-to! side {}))}
      "⤢ full view"]
     [:button {:class "side-btn" :type "button" :title "Close" :aria-label "Close"
               :on-click (fn [e] (.stopPropagation e) (close-side!))}
      "×"]]
    [:div {:id "side-empty"} "a linked graph opens here"]))

(defn- app-view [st]
  (let [page (page? st)
        split (split? st)
        graph (graph-shown? st)
        pres (presenting? st)]
  [:div {:id "root" :class (str (when (some? (:pick st)) "picking")
                                (cond page (str " md-page" (when split " split")
                                                (when pres " presenting")
                                                (when (and pres (or split (some? (:side (:present st))))) " halves")
                                                (when (and split (some? (:selected st))) " inspecting"))
                                      (some? (:md st)) " inspecting md-open"
                                      (some? (:selected st)) " inspecting")
                                (when (arranging? st) " arranging"))}
   (when-not pres (hint-view st))
   (when (and (not page) (nil? (:scene st)) (nil? (:error st)))
     (load-view st))
   (when graph (collapsed-view st))
   (when (and page (some? (:md st)) (not pres)) (side-view st))
   (when-not pres
     [:div {:id "top-center"}
      (trail-view st)
      (when (some? (:graph st)) (legend-view st))])
   (when (:layouting st)
     [:div {:id "layouting"} "re-layouting…"])
   ;; the banner and the controls share the top: the banner takes the room
   ;; left of the controls, so it never covers them (#111); both move left
   ;; of the inspector while one is open
   [:div {:id "top-bar"}
    (banner-view st)
   (if pres
     [:div {:id "top-right"}
      [:button {:id "present-leave" :type "button" :title "Leave the presentation (Esc)"
                :on-click (fn [e] (.stopPropagation e) (leave-present!))}
       "Leave presentation"]]
   ;; one row, so hidden buttons leave no holes
   [:div {:id "top-right"}
    ;; always there and first, so it never shifts the others; disabled
    ;; until an edit keeps the old arrangement
    (when graph
      (let [seeded (boolean (:seeded (:layout st)))]
        [:button {:id "relayout-btn" :type "button" :disabled (not seeded)
                  :title (if seeded
                           "Re-layout: edits kept the old arrangement, run a fresh layout"
                           "Re-layout: the layout is already fresh")
                  :on-click (fn [e] (.stopPropagation e) (relayout! true))}
         "▦"]))
    (when (arrangeable? st)
      (let [on (arranging? st)]
        [:button {:id "arrange-btn" :type "button" :class (if on "on" "")
                  :aria-pressed (if on "true" "false")
                  :title (cond on "Done arranging: dragging pans again"
                               (manual/manual? (:graph st)) "Arrange: drag nodes and boxes, snapped to the grid"
                               :else "Manual layout: place nodes and boxes by hand, snapped to a grid")
                  :on-click (fn [e] (.stopPropagation e) (toggle-arrange!))}
         "✥"]))
    (when (arranging? st)
      [:button {:id "auto-btn" :type "button" :title "Automatic layout: forget where you placed things"
                :on-click (fn [e] (.stopPropagation e) (auto-layout!))}
       "auto"])
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
    (help-view st)])]
   (canvas-view)
   (when (and (some? (:scene st)) (current-edit-target-editable? st))
     (selection-toolbar st))
   (when (some? (:md st)) (if pres (present-panel st) (md-panel st)))
   (when pres (present-side-view st))
   ;; the inspector: beside the graph of an md page too, not beside a docked doc
   (when (and (some? (:selected st)) (or (nil? (:md st)) split))
     (details-view st))]))

(defn- paint-now! []
  (when-let [canvas-el (js/document.getElementById "canvas")]
    (if-let [s (:scene @state)]
      (canvas/paint! canvas-el s (let [st @state]
                                   (cond (some? (:multi st)) (:ids (:multi st))
                                         (some? (:selected st)) #{(:elk-id (:selected st))}
                                         :else #{})))
      ;; no graph (an md page, a graph that failed): no stale picture either
      (canvas/clear! canvas-el))))

(declare sync-split!)

(defn- rerender! []
  (render app-el (app-view @state))
  (sync-split!)
  (canvas/request-paint!))

;; the md page's column, watched for its width (the window resizing)
(def ^:private observed-panel (atom nil))
(def ^:private panel-observer (js/ResizeObserver. (fn [_] (sync-split!))))

(defn- sync-split!
  "Keep the canvas beside the md page's column: --md-w is the column's
  width, --md-r its right edge, and #canvas-wrap starts where it ends
  while a graph is beside it — so fitting, panning and hits measure the
  graph's own area."
  []
  (let [st @state
        panel (when (page? st) (js/document.getElementById "md-panel"))
        w (if (some? panel) (.-offsetWidth panel) 0)
        left (str (if (split? st) w 0) "px")
        wrap (js/document.getElementById "canvas-wrap")]
    (when-not (identical? panel @observed-panel)
      (.disconnect panel-observer)
      (when (some? panel) (.observe panel-observer panel))
      (reset! observed-panel panel))
    (.setProperty (.-style js/document.documentElement) "--md-w" (str w "px"))
    (.setProperty (.-style js/document.documentElement) "--md-r"
                  (str (if (some? panel) (+ (.-offsetLeft panel) w) 0) "px"))
    (when (not= (.. wrap -style -left) left)
      (set! (.. wrap -style -left) left)
      (canvas/request-paint!))))

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
                        (or (graph-file @state) (:path (:graph @state)))))))))
    (swap! state assoc :pending-focus nil :focus-center false)))

(defn- show-layout!
  "Show a layout-cache entry: its colors, layout and scene."
  [{:keys [colors layout placed scene]}]
  (keep-pinned! scene)
  (swap! state (fn [st]
                 (refresh-selection
                  (assoc st :colors colors :layout layout :placed placed :scene scene
                         :layouting false :diff-cursors {})
                  scene)))
  (apply-pending-focus! scene))

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
          ck (cache-key collapsed)
          hit (.get layout-cache ck)]
      (if (and (some? hit) (some? (:scene hit)))
        (do (canvas/fit-view-once! (:scene hit))
            (show-layout! hit))
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
                placed (if (manual/manual? g) (manual/place layout g) layout)
                sc (scene/build-scene {:layout placed :graph g :colors cmap :measure canvas/measure})]
            (when (= gen @graph-gen)
              (canvas/fit-view-once! sc)
              (when (> (.-size layout-cache) 16) (.clear layout-cache))
              (let [entry {:fingerprint fp :colors cmap :layout layout :placed placed :scene sc}]
                (.set layout-cache ck entry)
                (if (= ck (cache-key (:collapsed-boxes @state)))
                  (show-layout! entry)
                  (swap! state assoc :layouting false))))))))
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

(defn ^:async reload!
  "Load the graph of file query `q` (file-query when it was asked) — and
  show it only when the page still shows that file: a side graph closed
  or replaced meanwhile must not land, nor the root's error in its place."
  [q]
  (try
    (when (nil? (:scene @state))
      (swap! state assoc :load-stage "loading graph…"))
    (let [resp (js-await (js/fetch (str "/api/graph" q)))
          raw (js-await (.json resp))]
      (cond
        (not= q (file-query)) nil
        (some? (:error raw))
        (do (when (nil? (:graph @state))
              ;; no graph to show (a navigation landed on a broken file):
              ;; back to your theme (or the OS's), which the menu shows again
              (apply-theme! (effective-theme nil (:theme-pref @state) (:theme @state))))
            (swap! state assoc :error (str "Graph error: " (:error raw)) :dismissed-error nil))
        :else
        (let [g (assoc raw :boxes-by-name
                       (let [o {}] (doseq [b (:boxes raw)] (assoc! o (:name b) b)) o))
              first-load? (nil? (:graph @state))]
          (swap! graph-gen inc)
          (demote-layout-cache!)
          (when-not (page? @state) (set! (.-title js/document) (format/tab-title g)))
          (apply-theme! (effective-theme g (:theme-pref @state) (:theme @state)))
          (swap! state (fn [st]
                         (assoc st :error nil :graph g
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

(defn- ^:async poll-graph!
  "Reload the graph shown when its file changed since the last look —
  unless the page went on to another file while the server answered."
  []
  (let [q (file-query)
        mtime (try
                (let [resp (js-await (js/fetch (str "/api/version" q)))
                      v (js-await (.json resp))]
                  (:mtime v))
                (catch :default _ nil))]
    ;; a failed poll means the server is gone (or restarting); the flag
    ;; clears on the next successful poll, so reconnection needs no action
    (when (not= (nil? mtime) (:disconnected @state))
      (swap! state assoc :disconnected (nil? mtime)))
    (when (and (some? mtime) (not= mtime @last-mtime) (= q (file-query)))
      (reset! last-mtime mtime)
      (js-await (reload! q)))))

(defn ^:async tick []
  (cond
    ;; the first load could not ask what the root is: try again
    (and (nil? (:file (:nav @state))) (nil? @root-path))
    (when (some? (js-await (root-path!))) (js-await (load-nav!)))

    ;; an md page (decided by the URL, so a failed open stays one): the
    ;; doc polls itself (poll-md!); this notices the server going away and
    ;; coming back, and retries an open that failed for want of a server;
    ;; the graph beside it polls like any graph
    (= "md" (editor/doc-kind (shown-path (:nav @state))))
    (if (some? (:side @state))
      (js-await (poll-graph!))
      (let [ok (try (.-ok (js-await (js/fetch "/api/root"))) (catch :default _ false))
            st @state]
        (when (not= (not ok) (:disconnected st))
          (swap! state assoc :disconnected (not ok)))
        (when (and ok (nil? (:md st)) (nil? (:error st)))
          (js-await (open-page! (shown-path (:nav st)))))))

    :else (js-await (poll-graph!))))

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
        md-target? (= "md" (editor/doc-kind (shown-path nav)))]
    (reset! shown-query js/location.search)
    (swap! graph-gen inc)
    ;; a slide's >> still loading belongs to the page left
    (swap! present-gen inc)
    (swap! state (fn [st]
                   (merge st per-file
                          {:nav nav :pending-focus (:focus nav) :side nil :present nil
                           :md (when-not (or (page? st) md-target?) (:md st))})))
    (.clear layout-cache)
    (reset! last-mtime nil)
    (canvas/refit-next!)
    (let [path (or (:file nav) (js-await (root-path!)))]
      (if (= "md" (editor/doc-kind path))
        (do (js-await (open-page! path))
            ;; the slide the URL presents, else the graph it shows beside
            ;; the page, once the page is there
            (when (some? (:md @state))
              (cond
                (some? (:slide nav)) (js-await (show-slide! (dec (:slide nav))))
                (some? (:side nav)) (do (swap! state assoc :side (:side nav))
                                        (js-await (tick))))))
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

(defn- post-json [url body]
  (js/fetch url {:method "POST"
                 :headers {"Content-Type" "application/json"}
                 :body (js/JSON.stringify body)}))

(defn- ^:async follow-to!
  "Navigate to root-relative `path` (selecting `focus`, when given), the
  file shown joining the trail. With `create?` the server is first asked
  to create it (an empty graph, with its folders) in case nothing is
  there yet; a failed create, like a path the server refuses, shows as
  the graph error after navigating, with the trail intact to go back.
  A second follow while the first still waits on the server is dropped,
  or it would push the same URL onto the history twice."
  [path {:keys [create? focus]}]
  (let [st @state]
    (when-not (:following st)
      (swap! state assoc :following true)
      (try
        (when create?
          (try (js-await (post-json "/api/create" (editor/create-body (:edit-target st) path)))
               (catch :default _ nil)))
        (js-await (navigate! (editor/follow-url (current-path st) (:trail (:nav st)) path focus)))
        (finally (swap! state assoc :following false))))))

(defn- replace-query!
  "Show query string `q` in the URL, without a history entry."
  [q]
  (js/history.replaceState nil "" (str js/location.pathname q))
  (reset! shown-query q))

(defn- show-side!
  "Put `side` (root-relative, nil for none) beside the md page: drop the
  graph shown before, with everything that belongs to it, and keep the
  side in the URL without a history entry, so a reload shows it again."
  [side]
  (swap! graph-gen inc)
  (swap! state (fn [st] (merge st per-file {:side side})))
  (.clear layout-cache)
  (reset! last-mtime nil)
  (canvas/refit-next!)
  (replace-query! (editor/with-side js/location.search side)))

(defn- ^:async open-side!
  "Show the graph at root-relative `path` beside the md page, creating
  it first when `create?` (as following a link to it would)."
  [path create?]
  (when-not (= path (:side @state))
    (when create?
      (try (js-await (post-json "/api/create" (editor/create-body "new" path)))
           (catch :default _ nil)))
    (show-side! path)
    (js-await (tick))))

(defn- close-side!
  "Close the graph beside the md page; the page takes back its theme."
  []
  (show-side! nil)
  (apply-theme! (effective-theme nil (:theme-pref @state) (:theme @state))))

(defn- ^:async follow-ref!
  "Follow the selection's ref: resolve it against the graph shown and
  follow it, creating it while the file being edited is editable. A ref
  that climbs above the served folder is refused here with a banner."
  [ref]
  (let [st @state
        target (paths/resolve-ref (:path (:graph st)) ref)]
    (if (nil? target)
      (swap! state assoc :nav-error (str "ref " (pr-str ref) " leaves the served folder"))
      (js-await (follow-to! target {:create? (and (current-edit-target-editable? st)
                                                  (= "graph" (editor/doc-kind target)))})))))

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
  (js-await (follow-to! (:file p) {:focus (str (if (= (:kind p) "box") "b:" "n:") (:id p))})))

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
                   :md (editor/adopt-doc {:path path :page true :full false :saving false
                                          :editing (not (:exists out))} out))
            (focus-md-start!))))
    (catch :default _
      (swap! state assoc :disconnected true))))

(defn- focus-md! []
  (when-let [el (js/document.getElementById "md-text")] (.focus el)))

(defn- focus-md-start!
  "Focus a doc just opened at its start: the swap that adopted it has
  rendered the textarea, whose value — set as a property — left the
  caret at the end, where focusing would scroll. Vi mode starts over in
  normal mode."
  []
  (swap! state update :vim vim/fresh)
  (when-let [el (js/document.getElementById "md-text")]
    (vim-select! el 0)
    (.focus el {:preventScroll true})
    (set! (.-scrollTop el) 0)))

;; ---- vi mode in the doc's text (simpleviz.vim) ----

(defn- vim-select!
  "Put the textarea's selection where vi mode shows caret p: a block on
  its char in normal mode, the range in visual mode — a plain caret with
  vi mode off."
  [el p]
  (let [[a b] (if (:vi-pref @state) (vim/selection (:vim @state) (.-value el) p) [p p])]
    (.setSelectionRange el a b)))

(defn- vim-effect!
  "A vi command line's effect: :w saves; :q closes the doc panel, or
  shows an md page read; :wq both — closing saves anyway, a page reads
  only when its save went through; :q! first puts back the text last
  loaded or saved, so nothing is left to save."
  [effect]
  (let [page (:page (:md @state))]
    (when (= effect "discard")
      (swap! state update :md (fn [md] (assoc md :text (:saved md)))))
    (case effect
      "save" (save-md!)
      ("close" "discard") (if page (toggle-md-edit!) (close-md!))
      "save-close" (if page
                     (-> (save-md!) (.then (fn [ok] (when ok (toggle-md-edit!)))))
                     (close-md!))
      nil)))

(defn- vim-key!
  "The doc text's keydown with vi mode on: vim/press takes the key or
  leaves it to the textarea. Ctrl, Cmd and Alt keys stay the page's
  (Ctrl+S saves), but Ctrl+R, which redoes."
  [e]
  (when (:vi-pref @state)
    (let [k (cond (and (.-ctrlKey e) (not (or (.-metaKey e) (.-altKey e))) (= "r" (.-key e))) "C-r"
                  (or (.-metaKey e) (.-isComposing e)) nil
                  ;; AltGr (Ctrl+Alt on Windows) and Option on a Mac type characters
                  (and (.-altKey e) (= 1 (.-length (.-key e)))) (.-key e)
                  (or (.-ctrlKey e) (.-altKey e)) nil
                  :else (.-key e))
          el (.-currentTarget e)]
      (when (some? k)
        (let [r (vim/press (:vim @state) {:text (.-value el) :pos (.-selectionStart el)} k)]
          (when (:handled r)
            (.preventDefault e)
            (.stopPropagation e)
            (swap! state (fn [st] (cond-> (assoc st :vim (:vs r))
                                    (not= (:text r) (.-value el)) (assoc-in [:md :text] (:text r)))))
            (vim-select! el (:pos r))
            (scroll-to-caret! el (:pos r))
            (when-let [fx (:effect r)] (vim-effect! fx))))))))

(defn- vim-before-input!
  "Outside insert mode only vi keys change the text: a paste, a dropped
  text or the browser's own undo would replace the block caret's char."
  [e]
  (when (and (:vi-pref @state) (not= "insert" (:mode (:vim @state))))
    (.preventDefault e)))

(defn- vim-mouse!
  "A click in the doc text leaves visual mode and puts the block where it
  landed; a drag keeps its selection, insert mode its caret."
  [e]
  (let [el (.-currentTarget e)]
    (when (:vi-pref @state)
      (swap! state update :vim vim/interrupt)
      (when (= (.-selectionStart el) (.-selectionEnd el))
        (vim-select! el (.-selectionStart el))))))

(defn- toggle-vi!
  "Turn vi mode on or off for every doc, kept with your choices."
  []
  (let [on (not (:vi-pref @state))]
    (save-pref! {:vi (if on "on" "")})
    (swap! state assoc :vi-pref on :vim (vim/fresh (:vim @state)))
    (when-let [el (js/document.getElementById "md-text")]
      (.focus el {:preventScroll true})
      (vim-select! el (.-selectionStart el)))))

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
          (let [resp (js-await (post-json "/api/text/save"
                                          {:path (:path md)
                                           :text (editor/to-disk text (:crlf md))
                                           :base (or base nil)}))
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
      ;; beside an md page the doc takes the page's place
      (split? st) (js-await (follow-to! path {}))
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
    (let [t (:text (:md @state))
          p (min (or pos 0) (.-length t))]
      (swap! state update :vim vim/reloaded t p)
      (when focused? (vim-select! el p)))))

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

;; ---- presenting the md page ----

(defn- drop-side!
  "No graph beside the page: close the one shown, else drop a side the
  URL may still name (a slide that changed since, a hand-made URL)."
  []
  (if (some? (:side @state))
    (close-side!)
    (replace-query! (editor/with-side js/location.search nil))))

(defn- ^:async export-kind
  "\"image\" when the .png or .svg at root-relative `path` is a plain
  picture, else \"graph\" — a failure then shows as the graph's error."
  [path]
  (try (let [out (js-await (.json (js-await (js/fetch (str "/api/graph?file=" (js/encodeURIComponent path))))))]
         (if (= true (:image out)) "image" "graph"))
       (catch :default _ "graph")))

(defn- ^:async side-of
  "What `>> dest` on a slide of the md page at `md-path` shows: {:kind
  \"graph\"|\"md\"|\"image\" :path root-relative}, or {:kind \"error\"
  :text why} when it can't."
  [md-path dest]
  (let [{:keys [path error]} (editor/link-target md-path dest)
        kind (when (some? path) (slides/side-kind path))]
    (cond (some? error) {:kind "error" :text error}
          (nil? kind) {:kind "error" :text (str dest " — not a graph, an md file or an image")}
          (= kind "export") {:kind (js-await (export-kind path)) :path path}
          :else {:kind kind :path path})))

(defn- ^:async show-md-side!
  "Fetch the md file of slide side `side` into it, unless slide change
  `gen` was overtaken."
  [side gen]
  (let [out (try (js-await (fetch-doc (:path side)))
                 (catch :default _ {:error "not connected"}))]
    (when (= gen @present-gen)
      (swap! state assoc-in [:present :side]
             (cond (some? (:error out)) {:kind "error" :text (str (:path side) ": " (:error out))}
                   (not (:exists out)) {:kind "error" :text (str "no such file: " (:path side))}
                   :else (assoc side :text (:text out)))))))

(defn- ^:async show-slide!
  "Present slide i of the md page, clamped to the deck: keep its number
  in the URL (no history entry) and show what its >> names beside it — a
  graph as the side graph, an md file or a picture in the canvas's
  place. A slide with the same >> as the one before keeps it as it is."
  [i]
  (let [st @state
        ds (deck st)
        i (max 0 (min i (dec (count ds))))
        dest (:side (nth ds i nil))
        same? (and (some? (:present st)) (= dest (:dest (:present st))))]
    (replace-query! (editor/with-slide js/location.search (inc i)))
    (swap! state assoc :present {:slide i :dest dest :side (when same? (:side (:present st)))})
    ;; the same >> stays — and so does a load of it still under way
    (when-not same?
      (let [gen (swap! present-gen inc)
            side (when (some? dest) (js-await (side-of (:path (:md st)) dest)))]
        (when (= gen @present-gen)
          (if (= "graph" (:kind side))
            (js-await (open-side! (:path side) false))
            (do (drop-side!)
                (swap! state assoc-in [:present :side] side)
                (when (= "md" (:kind side))
                  (js-await (show-md-side! side gen))))))))))

(defn- leave-present!
  "Back to the md page, the slide's side closed."
  []
  (swap! present-gen inc)
  (swap! state assoc :present nil :selected nil)
  (drop-side!)
  (replace-query! (editor/with-slide js/location.search nil)))

;; the text presented changed (taken from disk): show the slide again when
;; its >> changed or it is gone
(add-watch state :present
  (fn [_ _ old new]
    (when (and (presenting? new) (not= (:text (:md old)) (:text (:md new))))
      (let [{:keys [slide dest]} (:present new)
            ds (deck new)]
        (when (or (>= slide (count ds)) (not= dest (:side (nth ds slide nil))))
          (show-slide! slide))))))

(defn- present-key!
  "A key while presenting: → ↓ space and Page Down step forward, ← ↑ and
  Page Up back, Home and End jump to the ends; Esc closes the inspector,
  else leaves. Other keys do nothing."
  [e]
  (let [st @state
        i (:slide (:present st))
        to (case (.-key e)
             ("ArrowRight" "ArrowDown" "PageDown" " ") (inc i)
             ("ArrowLeft" "ArrowUp" "PageUp") (dec i)
             "Home" 0
             "End" (dec (count (deck st)))
             nil)]
    (cond
      (some? to) (do (.preventDefault e) (show-slide! to))
      (= (.-key e) "Escape") (if (some? (:selected st)) (on-select nil) (leave-present!))
      :else nil)))

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

(defn- style-mirror!
  "The mirror, emptied and styled and sized like textarea ta."
  [ta]
  (let [mirror (mirror-el)
        cs (js/getComputedStyle ta)]
    (doseq [p ["fontFamily" "fontSize" "fontWeight" "lineHeight" "letterSpacing" "tabSize"
               "paddingTop" "paddingRight" "paddingBottom" "paddingLeft"]]
      (aset (.-style mirror) p (aget cs p)))
    (set! (.. mirror -style -width) (str (.-clientWidth ta) "px"))
    (set! (.-textContent mirror) "")
    mirror))

(defn- scroll-to-caret!
  "Scroll textarea ta so caret p shows — setting the selection doesn't:
  p's top, laid out in the mirror, and a line below it inside the view."
  [ta p]
  (let [mirror (style-mirror! ta)
        mark (js/document.createElement "span")
        cs (js/getComputedStyle ta)]
    (.appendChild mirror (js/document.createTextNode (subs (.-value ta) 0 p)))
    (set! (.-textContent mark) "\u200b")
    (.appendChild mirror mark)
    (let [top (.-offsetTop mark)
          h (.-offsetHeight mark)
          view (.-scrollTop ta)
          pad-top (js/parseFloat (.-paddingTop cs))
          pad-bottom (js/parseFloat (.-paddingBottom cs))]
      ;; near the top the span's half-leading would leave a sliver
      (cond (< (- top pad-top) view) (set! (.-scrollTop ta) (if (< top (* 2 pad-top)) 0 (- top pad-top)))
            (> (+ top h pad-bottom) (+ view (.-clientHeight ta)))
            (set! (.-scrollTop ta) (- (+ top h pad-bottom) (.-clientHeight ta)))))))

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
      (let [mirror (style-mirror! ta)
            lines (.split (:text md) "\n")]
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
          ;; the gutter sits in the text's right padding, left of its scrollbar
          (swap! state assoc :md-marks {:marks marks :height height
                                        :scrollbar (- (.-offsetWidth ta) (.-clientWidth ta))})
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
        (or (not= (:page o) (:page n)) (not= (:full o) (:full n)) (not= (:editing o) (:editing n)))
        (queue-measure!)))))

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
      (some? error) (swap! state assoc :nav-error error)
      ;; the md page shows a graph beside it rather than leaving
      (and (:page md) (= kind "graph"))
      (js-await (open-side! path (.endsWith (.toLowerCase path) ".edn")))
      :else
      (js-await (follow-to! path {:create? (and (= kind "graph") (.endsWith (.toLowerCase path) ".edn")
                                                (or (:page md) (current-edit-target-editable? st)))})))))

(defn- ^:async post-edit!
  "POST ops to the edit target — or to `file` (\"old\"/\"new\") when given."
  [ops & [file]]
  (let [resp (js-await (post-json "/api/edit"
                                  (let [body (editor/edit-body (or file (:edit-target @state)) ops)
                                        f (graph-file @state)]
                                    (if (some? f) (assoc body :path f) body))))
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
  (let [names (if (= k "") [] (.split k "|"))]
    (if (some (fn [n] (= n old)) names)
      (cache-key (mapv (fn [n] (if (= n old) new n)) names))
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

(defn- enter-select!
  "Select mode, with scene ids `ids` selected; with `rect?` the next drag
  spans a rectangle whose nodes and boxes join them."
  [ids rect?]
  (cancel-pick!)
  (swap! state assoc :multi {:ids ids :rect rect?} :selected nil :editing nil :id-entry nil :chord nil))

(defn- select-all!
  "s a: select mode with every node and box selected."
  []
  (let [sc (:scene @state)]
    (enter-select! (set (hit/ids-in-rect sc {:x0 -1e9 :y0 -1e9 :x1 1e9 :y1 1e9} (absent-status @state))) false)))

(defn- rect-drag
  "The canvas's drag hook after s r: the drag spans a rectangle, and
  letting go adds the nodes and boxes wholly inside it to the selection."
  [gx gy]
  (when (:rect (:multi @state))
    (let [r (atom {:x0 gx :y0 gy :x1 gx :y1 gy})]
      {:move (fn [dx dy]
               (swap! r assoc :x1 (+ gx dx) :y1 (+ gy dy))
               ;; Esc mid-drag cancelled it: no rectangle comes back
               (when (:rect (:multi @state)) (canvas/set-marquee! @r)))
       :end (fn []
              (canvas/set-marquee! nil)
              ;; Esc mid-drag cancelled it
              (when-let [m (:multi @state)]
                (when (:rect m)
                  (swap! state assoc :multi {:ids (into (:ids m) (hit/ids-in-rect (:scene @state) @r (absent-status @state))) :rect false}))))
       :cancel (fn [] (canvas/set-marquee! nil))})))

(defn- ^:async fetch-copy
  "What /api/copy answers for scene ids `ids`: {:edn the clipboard text
  :ids the targets it took}; throws its error."
  [ids]
  (let [f (graph-file @state)
        body {:file (:edit-target @state) :ids (editor/selection-targets ids)}
        out (js-await (.json (js-await (post-json "/api/copy" (if (some? f) (assoc body :path f) body)))))]
    (if (some? (:error out)) (throw (js/Error. (:error out))) out)))

(defn- write-clipboard!
  "Put the text that promise `text-p` resolves to on the clipboard. A
  ClipboardItem takes the promise itself, so the write still counts as
  the key press's when the text comes later (Safari insists)."
  [text-p]
  (if (some? (.-ClipboardItem js/window))
    (.write js/navigator.clipboard
            [(js/ClipboardItem. {"text/plain" (.then text-p (fn [t] (js/Blob. [t] {:type "text/plain"})))})])
    (.then text-p (fn [t] (.writeText js/navigator.clipboard t)))))

(defn- ^:async copy-selected!
  "y y (cut false) / x x (cut true): the selected nodes and boxes, what
  the boxes hold and the edges among them all onto the clipboard as EDN,
  for p p here or in another graph. A cut then deletes all of it in one
  edit. Select mode ends."
  [cut]
  (let [out-p (fetch-copy (vec (:ids (:multi @state))))
        ok? (try (js-await (write-clipboard! (.then out-p (fn [o] (:edn o)))))
                 true
                 (catch :default e
                   ;; the server's own error, when it had one, says more
                   ;; than the clipboard's
                   (let [msg (try (js-await out-p) (.-message e) (catch :default e2 (.-message e2)))]
                     (swap! state assoc :notice (str (if cut "Cut" "Copy") " failed: " msg)))
                   false))]
    (when ok?
      (let [n (count (:ids (js-await out-p)))]
        (swap! state assoc :multi nil)
        (if cut
          (do (js-await (post-edit! (mapv editor/delete-op (:ids (js-await out-p)))))
              (when (nil? (:edit-error @state)) (flash! (str "cut " n " — p p pastes them"))))
          (flash! (str "copied " n " — p p pastes them, here or in another graph")))))))

(defn- ^:async delete-selected!
  "d d in select mode: delete the selected nodes and boxes with what the
  boxes hold — what x x would cut — in one edit. Select mode ends."
  []
  (try
    (let [out (js-await (fetch-copy (vec (:ids (:multi @state)))))]
      (swap! state assoc :multi nil)
      (js-await (post-edit! (mapv editor/delete-op (:ids out)))))
    (catch :default e (swap! state assoc :edit-error (.-message e)))))

(defn- ^:async paste!
  "p p: the nodes and boxes on the clipboard into the graph — into the
  selected box, else at the top level. Ids already taken get -1, -2, …"
  [sel]
  (let [text (try (js-await (.readText js/navigator.clipboard))
                  (catch :default e
                    (swap! state assoc :notice (str "Paste failed: the clipboard can't be read (" (.-message e) ")"))
                    nil))]
    (when (some? text)
      (js-await (post-edit! [(editor/paste-op text sel)]))
      (when (nil? (:edit-error @state)) (flash! "pasted — Ctrl+Z takes it back")))))

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

(defn- ^:async load-prefs!
  "Take your stored choices (/api/prefs) into state and paint the theme;
  the valid ones only, and none when the server can't say."
  []
  (let [p (try (js-await (.json (js-await (js/fetch "/api/prefs"))))
               (catch :default _ {}))
        theme (some #(when (= (:theme p) %) %) themes/NAMES)]
    (apply-theme! (effective-theme (:graph @state) theme (:theme @state)))
    (swap! state assoc :theme-pref theme :vi-pref (= "on" (:vi p)))
    ;; a doc focused before the prefs came shows vi mode's block now
    (when-let [el (js/document.getElementById "md-text")]
      (when (identical? el (.-activeElement js/document))
        (vim-select! el (.-selectionStart el))))))

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
  (start-action! sel (when (some? sel) (editor/target sel)) action))

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
        (editor/chord-prefix? (chord-kind st) path (avail-fn sel) pairs)
        (do (.preventDefault e) (swap! state assoc :chord path))

        (some? (:chord st))
        (do (.preventDefault e)
            (swap! state assoc :chord nil)
            (when-let [action (editor/chord-action (chord-kind st) path pairs)]
              (run-chord-action! sel action)))))))

(js/window.addEventListener "keydown"
  (fn [e]
    (cond
      (and (or (.-ctrlKey e) (.-metaKey e))
           (= (.toLowerCase (.-key e)) "s")
           (some? (:md @state)))
      (do (.preventDefault e) (save-md!))

      (and (presenting? @state) (not (typing?))
           (not (or (.-ctrlKey e) (.-metaKey e) (.-altKey e))))
      (present-key! e)

      (= (.-key e) "Escape") (do (cancel-pick!)
                                 ;; select mode ends, unless a chord or a rectangle was what Esc cancelled
                                 (swap! state (fn [st]
                                                (let [m (:multi st)]
                                                  (assoc st :multi (cond (some? (:chord st)) m
                                                                         (:rect m) (assoc m :rect false)
                                                                         :else nil)))))
                                 (canvas/set-marquee! nil)
                                 (swap! state assoc :help false :chord nil :export-menu false :link-menu nil
                                        :arrange false)
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
(canvas/set-drag-hook! (fn [gx gy] (or (rect-drag gx gy) (arrange-drag gx gy))))
;; the grid's dots show while arranging
(add-watch state :arrange-grid
  (fn [_ _ old new]
    (let [on (arranging? new)]
      (when (not= (arranging? old) on)
        (canvas/set-grid! (when on manual/GRID))))))
(rerender!)
;; your theme must be in before the first graph paints
(.then (load-prefs!) (fn [_] (load-nav!)))
(js/setInterval tick 1000)
(js/setInterval poll-md! 1000)
(js/window.addEventListener "resize" (fn [_] (queue-measure!) (sync-split!)))
(.then (.-ready js/document.fonts) (fn [_] (queue-measure!)))
;; unsaved doc edits: the browser asks before the tab closes or reloads
(js/window.addEventListener "beforeunload"
  (fn [e]
    (when (editor/md-dirty? (:md @state))
      (.preventDefault e)
      (set! (.-returnValue e) ""))))
