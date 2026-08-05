(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.transform :refer [to-elk]]
            [simpleviz.prune :refer [prune-hidden]]
            [simpleviz.scene :as scene]
            [simpleviz.hit :as hit]
            [simpleviz.canvas :as canvas]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

(def state (atom {:error nil :warnings [] :graph nil :layout nil
                  :colors nil :selected nil :collapsed false
                  :hidden #{}}))
(def last-mtime (atom nil))

(defn- on-select [payload]
  (swap! state assoc :selected payload))

(declare relayout!)

(defn- hide-box! [box-name]
  (swap! state (fn [st] (assoc st :hidden (conj (:hidden st) box-name)
                               :selected nil)))
  (relayout!))

(defn- unhide-box! [box-name]
  (swap! state (fn [st] (assoc st :hidden (disj (:hidden st) box-name))))
  (relayout!))

(defn- hidden-view [hidden]
  (when (pos? (.-size hidden))
    (into [:div {:id "hidden-boxes"}]
          (mapv (fn [b]
                  [:button {:key b :class "chip" :type "button"
                            :title "Show this box again"
                            :on-click (fn [e] (.stopPropagation e) (unhide-box! b))}
                   (str b " ×")])
                (vec (sort (js/Array.from hidden)))))))

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
        (if (= (:kind item) "hide-button")
          (hide-box! (.slice (:box-id item) 2))
          (on-select (when (some? item) (item->payload item))))))}])

(defn- app-view [st]
  [:div {:id "root"}
   (banner-view st)
   (hidden-view (:hidden st))
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
  "Layout + scene from the stored graph, minus hidden boxes. Colors come
  from the FULL graph so hiding never shifts type colors."
  []
  (try
    (let [g0 (:graph @state)
          g (prune-hidden g0 (:hidden @state))
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
      (swap! state assoc :colors cmap :layout layout :scene sc))
    (catch :default e
      (js/console.error "Relayout failed:" e)
      (swap! state assoc :error (str "Render error: " (or (.-message e) (str e)))))))

(defn ^:async reload! []
  (try
    (let [resp (js-await (js/fetch "/api/graph"))
          raw (js-await (.json resp))]
      (if (some? (:error raw))
        (swap! state assoc :error (str "Graph error: " (:error raw)))
        (let [g (assoc raw :boxes-by-name
                       (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes raw)))]
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
