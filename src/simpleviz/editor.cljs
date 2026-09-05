(ns simpleviz.editor)

;; Pure op-payload builders — the DOM-facing code in app.cljs stays thin.

(defn target
  "The op target for a selection. An edge is keyed by its pair as written
  in the file (carried in attrs :nodes): a :<- edge is displayed with
  source and target swapped, so the displayed pair would miss the key."
  [sel]
  (case (:kind sel)
    "edge" {:section "edges" :id (or (:nodes (:attrs sel)) [(:source sel) (:target sel)])}
    "node" {:section "nodes" :id (.slice (:elk-id sel) 2)}
    "box" {:section "boxes" :id (.slice (:elk-id sel) 2)}))

(defn retarget-end
  "Which end of the file key the displayed `end` (\"source\"/\"target\")
  of the selected edge is: the two are swapped for a :<- edge."
  [sel end]
  (if (= "<-" (:direction (:attrs sel)))
    (if (= end "source") "target" "source")
    end))

(defn set-attr-op [tgt attr value-text scalar?]
  (assoc tgt :op "set-attr" :attr attr :value value-text :fallback scalar?))

(defn del-attr-op [tgt attr]
  (assoc tgt :op "del-attr" :attr attr))

(defn delete-op [tgt]
  (assoc tgt :op "delete"))

(defn rename-op
  "Give the selected node or box the id `to` (whitespace-trimmed); the
  server rewrites every reference along with the key."
  [tgt to]
  (assoc tgt :op "rename" :to (.trim to)))

(defn direction-op [tgt dir]
  {:op "set-direction" :edge (:id tgt) :direction dir})

(defn- bare-id [item] (.slice (:id item) 2))

(defn add-node-ops
  "Ops to create a new free-standing node (no selection required)."
  [new-id]
  [{:op "add-node" :id new-id}])

(defn add-connected-ops
  "Ops to create a new node and wire an edge from the selected node to it."
  [from new-id]
  [{:op "add-node" :id new-id}
   {:op "add-edge" :from from :to new-id :direction "->"}])

(defn add-node-in-box-ops
  "Ops to create a new node as a member of box `box-id`."
  [box-id new-id]
  [{:op "add-node" :id new-id}
   {:op "box-add" :box box-id :member new-id}])

(defn box-remove-op
  "Ops to take `member-id` out of box `box-id` (the server moves it to
  the enclosing box, if any)."
  [box-id member-id]
  [{:op "box-remove" :box box-id :member member-id}])

(defn wrap-in-box-ops
  "Ops to create a new box around the selected node or box; the server
  also moves the member out of its old parent box into the new one."
  [member-id box-id]
  [{:op "wrap" :box box-id :member member-id}])

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
  the wanted kind is valid, and not the box itself;
  {:mode \"box-drop\" :box id} — only a node whose :parent is that box."
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
      "connect" (if (and (or (= kind "node") (= kind "box"))
                         (not= (bare-id item) (:from pick)))
                  [{:op "add-edge" :from (:from pick) :to (bare-id item)
                    :direction "->"}]
                  nil)
      "box-take" (if (and (= kind (:want pick))
                          (not= (bare-id item) (:box pick)))
                   [{:op "box-add" :box (:box pick) :member (bare-id item)}]
                   nil)
      "box-drop" (if (and (= kind "node") (= (:parent item) (:box pick)))
                   [{:op "box-remove" :box (:box pick) :member (bare-id item)}]
                   nil)
      nil)))

(defn blur-text
  "The text to commit on a blur event for field k, or nil when the blur
  is a side effect of :editing having already moved on — Escape cleared
  it, or a successful Enter save already cleared it — rather than the
  user actually clicking/tabbing away with the field still open.
  Removing the focused textarea from the DOM (which the re-render after
  either of those does) fires a native blur synchronously; without this
  guard that stale blur would re-post with nil/stale text and clobber
  the value that was just saved (or restored) a moment earlier."
  [editing k]
  (when (= k (:attr editing))
    (:text editing)))

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

;; ---- keyboard chords ----

;; Two-key chords, in the order the hints list them. Each entry maps a
;; selection kind ("node" "edge" "box", or "none" with nothing selected)
;; to [action label]; the action is what app.cljs dispatches on, the
;; label what the pending-chord hint shows.
(def ^:private chord-table
  [["d" "d" {"node" ["delete" "delete"] "edge" ["delete" "delete"] "box" ["delete" "delete"]}]
   ["e" "1" {"edge" [["direction" "->"] "→"]}]
   ["e" "2" {"edge" [["direction" "<-"] "←"]}]
   ["e" "3" {"edge" [["direction" "<->"] "↔"]}]
   ["e" "4" {"edge" [["direction" "-"] "—"]}]
   ["c" "s" {"edge" [["retarget" "source"] "change source"]}]
   ["c" "t" {"edge" [["retarget" "target"] "change target"]}]
   ["c" "n" {"box" ["new-node-in-box" "new node"]}]
   ["a" "e" {"node" ["add-edge" "add edge"] "box" ["add-edge" "add edge"]}]
   ["a" "b" {"node" ["add-to-box" "add to box"] "box" ["add-box-member" "add box"]}]
   ["a" "n" {"box" ["add-node-member" "add node"]}]
   ["n" "n" {"none" ["new-node" "new node"] "node" ["new-connected-node" "new node"]}]
   ["n" "b" {"node" ["new-box" "new box"] "box" ["new-box" "new box"]}]
   ["r" "r" {"node" ["rename" "rename"] "box" ["rename" "rename"]}]
   ["r" "n" {"box" ["remove-node-member" "remove node"]}]
   ["r" "b" {"node" ["remove-from-box" "remove from box"]}]])

(defn- kind-key [kind] (if (nil? kind) "none" kind))

(defn chord-group?
  "True when k opens a chord (is the first key of one)."
  [k]
  (some? (some (fn [[g _ _]] (when (= g k) true)) chord-table)))

(defn chord-action
  "The action for chord `k1 k2` with a selection of `kind` (nil
  for none), or nil when the chord does not exist or does not apply."
  [kind k1 k2]
  (some (fn [[g k kinds]]
          (when (and (= g k1) (= k k2))
            (first (get kinds (kind-key kind)))))
        chord-table))

(defn chord-for
  "The chord (\"d d\") that triggers `action` for `kind`, for the
  toolbar's key hints; nil when none does."
  [kind action]
  (some (fn [[g k kinds]]
          (when (= action (first (get kinds (kind-key kind)))) (str g " " k)))
        chord-table))

(defn chord-hint
  "What the pending group `g` can complete to for `kind`, e.g.
  \"c … s change source · t change target\"."
  [kind g]
  (let [opts (keep (fn [[g' k kinds]]
                     (when (= g' g)
                       (when-let [[_ label] (get kinds (kind-key kind))]
                         (str k " " label))))
                   chord-table)]
    (str g " … "
         (if (seq opts)
           (.join (vec opts) " · ")
           (if (nil? kind) "nothing without a selection" (str "nothing for a" (if (= kind "edge") "n " " ") kind))))))
