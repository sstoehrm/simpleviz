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
