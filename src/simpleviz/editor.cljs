(ns simpleviz.editor)

;; Pure op-payload builders — the DOM-facing code in app.cljs stays thin.

(defn target [sel]
  (case (:kind sel)
    "edge" {:section "edges" :id [(:source sel) (:target sel)]}
    "node" {:section "nodes" :id (.slice (:elk-id sel) 2)}
    "box" {:section "boxes" :id (.slice (:elk-id sel) 2)}))

(defn set-attr-op [tgt attr value-text scalar?]
  (assoc tgt :op "set-attr" :attr attr :value value-text :fallback scalar?))

(defn del-attr-op [tgt attr]
  (assoc tgt :op "del-attr" :attr attr))

(defn delete-op [tgt]
  (assoc tgt :op "delete"))

(defn direction-op [tgt dir]
  {:op "set-direction" :edge (:id tgt) :direction dir})

(defn- bare-id [item] (.slice (:id item) 2))

(defn add-connected-ops
  "Ops to create a new node and wire an edge from the selected node to it."
  [from new-id]
  [{:op "add-node" :id new-id}
   {:op "add-edge" :from from :to new-id :direction "->"}])

(defn wrap-in-box-ops
  "Ops to create a new box and put the selected node into it."
  [node-id box-id]
  [{:op "add-box" :id box-id}
   {:op "box-add" :box box-id :member node-id}])

(defn edit-body
  "The /api/edit POST body: routes ops to whichever file (\"old\"/\"new\")
  is the current edit target."
  [file ops]
  {:file file :ops ops})

(defn pick-ops
  "Ops for a pick-mode hit, or nil when item isn't a valid target for
  pick (keep picking). pick is one of:
  {:mode \"retarget\" :edge [a b] :end \"source\"|\"target\"} — any node
  or box is a valid new endpoint;
  {:mode \"into-box\" :member id} — only a box is valid;
  {:mode \"box-take\" :box id :want \"node\"|\"box\"} — only an item of
  the wanted kind is valid, and not the box itself."
  [pick item]
  (let [kind (:kind item)]
    (case (:mode pick)
      "retarget" (if (or (= kind "node") (= kind "box"))
                   [{:op "retarget-edge" :edge (:edge pick)
                     :end (:end pick) :to (bare-id item)}]
                   nil)
      "into-box" (if (= kind "box")
                   [{:op "box-add" :box (bare-id item) :member (:member pick)}]
                   nil)
      "box-take" (if (and (= kind (:want pick))
                          (not= (bare-id item) (:box pick)))
                   [{:op "box-add" :box (:box pick) :member (bare-id item)}]
                   nil)
      nil)))

(defn blur-op
  "The ops to post on a blur event for attr k, or nil when the blur is a
  side effect of :editing having already moved on — Escape cleared it,
  or a successful Enter save already cleared it — rather than the user
  actually clicking/tabbing away with the field still open. Removing
  the focused input/textarea from the DOM (which the re-render after
  either of those does) fires a native blur synchronously; without this
  guard that stale blur would re-post with nil/stale text and clobber
  the value that was just saved (or restored) a moment earlier."
  [editing k tgt scalar?]
  (when (= k (:attr editing))
    [(set-attr-op tgt k (:text editing) scalar?)]))

(defn scalar?
  "True when v is not a collection (vector or map) — squint sets arrive
  post-JSON as vectors, so those two predicates cover everything
  non-scalar the inspector can show."
  [v]
  (not (or (vector? v) (map? v))))

(defn- edn-text
  "Recursive EDN printer for squint data (plain JS values post-JSON):
  strings quoted/escaped via pr-str, keys of maps rendered with a
  leading `:` (attrs maps are conventionally keyword-keyed), numbers
  and booleans as-is, vectors/maps bracketed and recursed into."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (vector? v) (str "[" (.join (mapv edn-text v) " ") "]")
    (map? v) (str "{" (.join (mapv (fn [[k x]] (str ":" k " " (edn-text x)))
                                   (js/Object.entries v))
                             " ")
                  "}")
    :else (str v)))

(defn value->edn-text
  "Seed text for the inspector's edit field: a string value edits as its
  own raw text (no surrounding quotes to fight with); anything else
  seeds from its EDN printed form."
  [v]
  (if (string? v) v (edn-text v)))
