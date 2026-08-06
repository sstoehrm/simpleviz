(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.transform :refer [to-elk]]
            [simpleviz.prune :refer [collapse-boxes collapse-scene]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

(def state (atom {:error nil :warnings [] :graph nil :layout nil
                  :colors nil :selected nil :collapsed false
                  :collapsed-boxes #{} :layouting false}))
(def last-mtime (atom nil))

(defn- on-select [payload]
  (swap! state assoc :selected payload))

(declare relayout!)

;; layouts per collapsed-set, so expanding (or re-collapsing a seen
;; combination) is instant instead of a multi-second ELK run; cleared on
;; file reload
(def ^:private layout-cache (js/Map.))

(defn- cache-key [collapsed]
  (.join (.sort (js/Array.from collapsed)) "|"))

(defn- collapse-box! [box-name]
  (swap! state (fn [st]
                 (let [collapsed (conj (:collapsed-boxes st) box-name)]
                   (assoc st
                          :collapsed-boxes collapsed
                          :selected nil
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

(defn- collapsed-view [collapsed]
  (when (pos? (.-size collapsed))
    (into [:div {:id "collapsed-boxes"}]
          (mapv (fn [b]
                  [:button {:key b :class "chip" :type "button"
                            :title "Expand this box"
                            :on-click (fn [e] (.stopPropagation e) (expand-box! b))}
                   (str b " +")])
                (vec (sort (js/Array.from collapsed)))))))

;; attrs already represented visually (endpoints/arrow on the canvas,
;; membership by containment) stay out of the inspector
(def ^:private hidden-attrs
  {"edge" #{"nodes" "direction"}
   "box" #{"components"}})

(defn- visible-attrs [sel]
  (let [hidden (get hidden-attrs (:kind sel))]
    (filterv (fn [[k _]] (not (and (some? hidden) (.has hidden k))))
             (js/Object.entries (:attrs sel)))))

(defn- details-view [sel]
  [:aside {:id "details"}
   [:button {:id "details-close" :type "button" :aria-label "Close details"
             :on-click (fn [e] (.stopPropagation e) (on-select nil))}
    "×"]
   [:h2 (:title sel)]
   [:div {:class "details-type"}
    (if (pos? (.-length (:subtitle sel)))
      (str "(" (:subtitle sel) ") — " (:kind sel))
      (:kind sel))]
   (into [:dl]
         (mapcat (fn [[k v]]
                   [[:dt {:key (str "t" k)} k]
                    [:dd {:key (str "d" k)}
                     (if (string? v) v (js/JSON.stringify v nil 2))]])
                 (visible-attrs sel)))])

(defn- banner-view [{:keys [error warnings collapsed]}]
  (cond
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
    {:kind (:kind item)
     :elk-id (:id item)
     :title (if (pos? (.-length nm)) nm fallback)
     :subtitle (str (if (nil? (:type item)) "" (:type item)))
     :attrs (:attrs item)}))

(defn- canvas-view []
  [:canvas
   {:id "canvas" :key "the-canvas"
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
            item (when (some? s) (hit/hit-test s p tol (:k canvas/view)))]
        (if (= (:kind item) "collapse-button")
          (toggle-collapse! (.slice (:box-id item) 2))
          (on-select (when (some? item) (item->payload item))))))}])

(defn- load-view [st]
  [:div {:id "loadscreen"}
   [:div {:class "load-spinner"}]
   [:div {:class "load-title"} "simpleviz"]
   [:div {:class "load-stage"} (or (:load-stage st) "loading…")]])

(defn- app-view [st]
  [:div {:id "root"}
   (banner-view st)
   (when (and (nil? (:scene st)) (nil? (:error st)))
     (load-view st))
   (collapsed-view (:collapsed-boxes st))
   (when (:layouting st)
     [:div {:id "layouting"} "re-layouting…"])
   (canvas-view)
   (when (some? (:selected st))
     (details-view (:selected st)))])

(defn- paint-now! []
  (when-let [canvas-el (js/document.getElementById "canvas")]
    (when-let [s (:scene @state)]
      (canvas/paint! canvas-el s (:elk-id (:selected @state))))))

(defn- rerender! []
  (render app-el (app-view @state))
  (canvas/request-paint!))

(defn ^:async relayout!
  "Layout + scene from the stored graph, with collapsed boxes contracted.
  Colors come from the FULL graph so collapsing never shifts type colors.
  Results are cached per collapsed-set; a stale async result (set changed
  meanwhile) is cached but not applied."
  []
  (try
    (let [g0 (:graph @state)
          collapsed (:collapsed-boxes @state)
          ck (cache-key collapsed)]
      (if-let [hit (.get layout-cache ck)]
        (swap! state assoc :colors (:colors hit) :layout (:layout hit)
               :scene (:scene hit) :layouting false)
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
                layout (js-await (.layout elk (to-elk g canvas/measure)))
                sc (scene/build-scene {:layout layout :graph g :colors cmap})]
            (canvas/fit-view-once! sc)
            (when (> (.-size layout-cache) 16) (.clear layout-cache))
            (.set layout-cache ck {:colors cmap :layout layout :scene sc})
            (if (= ck (cache-key (:collapsed-boxes @state)))
              (swap! state assoc :colors cmap :layout layout :scene sc
                     :layouting false)
              (swap! state assoc :layouting false))))))
    (catch :default e
      (js/console.error "Relayout failed:" e)
      (swap! state assoc :layouting false
             :error (str "Render error: " (or (.-message e) (str e)))))))

(defn ^:async reload! []
  (try
    (when (nil? (:scene @state))
      (swap! state assoc :load-stage "loading graph…"))
    (let [resp (js-await (js/fetch "/api/graph"))
          raw (js-await (.json resp))]
      (if (some? (:error raw))
        (swap! state assoc :error (str "Graph error: " (:error raw)))
        (let [g (assoc raw :boxes-by-name
                       (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes raw)))]
          (.clear layout-cache)
          (swap! state assoc :error nil :graph g :warnings (:warnings g))
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
    (when (and (some? mtime) (not= mtime @last-mtime))
      (reset! last-mtime mtime)
      (js-await (reload!)))))

;; init
(canvas/set-repaint! paint-now!)
(add-watch state :render (fn [_ _ _ _] (rerender!)))
(canvas/setup-pan-zoom! (js/document.getElementById "canvas-wrap"))
(rerender!)
(tick)
(js/setInterval tick 1000)
