(ns edit
  "Semantic edit operations on the raw text of a graph EDN file.
  rewrite-clj zipper patches: comments and formatting survive. Map-form
  files only. Every public op is text -> text; failures throw ex-info
  with {:edit-error true} and a user-facing message."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(defn- fail! [msg] (throw (ex-info msg {:edit-error true})))

(defn- ident->str [x] (if (keyword? x) (subs (str x) 1) (str x)))

(def ^:private ident-re #"[A-Za-z0-9*+!_'?<>=./-]+")

(defn ident-node
  "New ids as keywords when legal, else strings."
  [id]
  (if (re-matches ident-re id) (keyword id) id))

(defn- edn-value
  "Parse an op's EDN-text value. :fallback true: unparseable or bare
  symbol becomes the raw string. :fallback false: unparseable fails."
  [s fallback]
  (let [v (try (edn/read-string s) (catch Exception _ ::bad))]
    (cond
      (and (= ::bad v) fallback) s
      (= ::bad v) (fail! (str "value " (pr-str s) " does not parse as EDN"))
      (and (symbol? v) fallback) s
      :else v)))

(defn- zroot [text]
  (let [zl (try (z/of-string text) (catch Exception _ (fail! "file does not parse as EDN")))]
    (when-not (map? (z/sexpr zl)) (fail! "root must be a map"))
    zl))

(defn- find-key
  "zloc of the KEY in the map at zmap whose sexpr satisfies pred, nil if none."
  [zmap pred]
  (loop [k (z/down zmap)]
    (when (some? k)
      (if (pred (z/sexpr k)) k (recur (z/right (z/right k)))))))

(defn- find-val [zmap pred] (some-> (find-key zmap pred) z/right))

(defn- sect-val
  "zloc of the top-level section map (:nodes/:edges/:boxes). Fails on
  vector form; nil when the key is absent."
  [root section]
  (when-let [v (find-val root #(= % section))]
    (when-not (map? (z/sexpr v))
      (fail! (str "pre-v2 vector form: convert " (name section) " to map form to edit")))
    v))

(defn- edge-key-pred [[from to]]
  (fn [s] (and (vector? s) (= 2 (count s)) (= [from to] (mapv ident->str s)))))

(defn- entry-pred [section id]
  (if (= section :edges)
    (edge-key-pred id)
    (fn [s] (and (or (keyword? s) (string? s)) (= (ident->str s) id)))))

(defn- unknown! [section id]
  (fail! (str "unknown " (case section :nodes "node" :edges "edge" :boxes "box")
              " " (if (vector? id) (str "[" (str/join " " id) "]") (pr-str id)))))

(defn- entry-val
  "zloc of the entry VALUE for id in section. Fails when missing."
  [root section id]
  (let [sect (or (sect-val root section) (unknown! section id))]
    (or (find-val sect (entry-pred section id)) (unknown! section id))))

(defn- remove-pair
  "Remove a key zloc and its value; returns the root zipper.
  Removing the value first can land the zipper's position deep inside the
  key's own subtree when the key is compound (e.g. an edge's [from to]
  vector) — rewrite-clj's `remove` moves to the depth-first predecessor,
  which dives into a compound left sibling. Walk back up by comparing
  source text (not sexpr, which would choke on a map now missing one
  value) until we're back at the key itself, then remove that."
  [kloc]
  (let [key-text (z/string kloc)
        after-val (z/remove (z/right kloc))
        at-key (loop [c after-val]
                 (if (= key-text (z/string c)) c (recur (z/up c))))]
    (z/remove at-key)))

(defn set-attr [text {:keys [section id attr value fallback]}]
  (let [v (edn-value value fallback)
        entry (entry-val (zroot text) section id)]
    (z/root-string
     (if (nil? (z/sexpr entry))
       (z/replace entry {attr v})
       (if-let [av (find-val entry #(= % attr))]
         (z/replace av v)
         (-> entry (z/append-child attr) (z/append-child v)))))))

(defn del-attr [text {:keys [section id attr]}]
  (let [entry (entry-val (zroot text) section id)]
    (when (nil? (z/sexpr entry)) (fail! (str "no attribute " attr " to delete")))
    (let [k (or (find-key entry #(= % attr))
                (fail! (str "no attribute " attr " to delete")))]
      (z/root-string (remove-pair k)))))

(defn- parsed [text]
  (try (edn/read-string text) (catch Exception _ (fail! "file does not parse as EDN"))))

(defn- ensure-sect
  "Root zipper positioned at the section map, creating `section {}` at
  the end of the root map when absent."
  [root section]
  (or (sect-val root section)
      (-> root (z/append-child section) (z/append-child {})
          z/root-string zroot (sect-val section))))

(defn- ensure-map-section
  "The section's raw value, having refused a pre-v2 vector form before
  any (keys ..) walk would blow up on it with an opaque cast exception."
  [data section]
  (let [v (get data section)]
    (when (and (some? v) (not (map? v)))
      (fail! (str "pre-v2 vector form: convert " (name section) " to map form to edit")))
    v))

(defn- exists? [data section id]
  (case section
    :nodes (contains? (into #{} (map ident->str) (keys (:nodes data))) id)
    :boxes (contains? (into #{} (map ident->str) (keys (ensure-map-section data :boxes))) id)))

(defn add-node [text {:keys [id attrs-text]}]
  (let [data (parsed text)]
    (when (exists? data :nodes id) (fail! (str "node " (pr-str id) " already exists")))
    (let [attrs (if (some? attrs-text) (edn-value attrs-text false) nil)
          sect (ensure-sect (zroot text) :nodes)]
      (z/root-string (-> sect (z/append-child (ident-node id)) (z/append-child attrs))))))

(defn add-box [text {:keys [id]}]
  (let [data (parsed text)]
    (when (exists? data :boxes id) (fail! (str "box " (pr-str id) " already exists")))
    (let [sect (ensure-sect (zroot text) :boxes)]
      (z/root-string (-> sect (z/append-child (ident-node id)) (z/append-child {:components []}))))))

(defn- edge-pairs [data]
  (into #{} (comp (filter vector?) (map (fn [k] (set (map ident->str k)))))
        (keys (ensure-map-section data :edges))))

(defn add-edge [text {:keys [from to direction]}]
  (let [data (parsed text)]
    (doseq [x [from to]]
      (when-not (or (exists? data :nodes x) (exists? data :boxes x))
        (fail! (str "unknown node or box " (pr-str x)))))
    (when (contains? (edge-pairs data) (set [from to]))
      (let [[x y] (sort [from to])]
        (fail! (str "edge [" x " " y "] already exists"))))
    (let [sect (ensure-sect (zroot text) :edges)
          attrs (if (some? direction) {:direction (keyword direction)} nil)]
      (z/root-string (-> sect
                         (z/append-child [(ident-node from) (ident-node to)])
                         (z/append-child attrs))))))

(defn retarget-edge [text {:keys [edge end to]}]
  (let [data (parsed text)
        [from t] edge
        new-pair (if (= end "source") [to t] [from to])]
    (when-not (or (exists? data :nodes to) (exists? data :boxes to))
      (fail! (str "unknown node or box " (pr-str to))))
    (when (= (first new-pair) (second new-pair))
      (fail! "cannot connect an element to itself"))
    (when (contains? (edge-pairs data) (set new-pair))
      (fail! (str "edge [" (first new-pair) " " (second new-pair) "] already exists")))
    (let [sect (or (sect-val (zroot text) :edges) (unknown! :edges edge))
          k (or (find-key sect (edge-key-pred edge)) (unknown! :edges edge))]
      (z/root-string (z/replace k (mapv ident-node new-pair))))))

(defn set-direction [text {:keys [edge direction]}]
  (set-attr text {:section :edges :id edge :attr :direction
                  :value (str ":" direction) :fallback false}))

(defn- box-components
  "Member ids (as strings) currently in box's :components — set or
  vector form, and nil-safe for an unknown box or a nil-valued entry
  (both have no members)."
  [data box]
  (let [box-val (some (fn [[k v]] (when (= (ident->str k) box) v)) (:boxes data))]
    (into #{} (map ident->str) (:components box-val))))

(defn box-add [text {:keys [box member]}]
  (let [data (parsed text)]
    (when-not (or (exists? data :nodes member) (exists? data :boxes member))
      (fail! (str "unknown node or box " (pr-str member))))
    (when (= box member) (fail! "a box cannot contain itself"))
    (when (contains? (box-components data box) member)
      (fail! (str "\"" member "\" is already in box \"" box "\"")))
    (let [entry (entry-val (zroot text) :boxes box)
          entry (if (nil? (z/sexpr entry)) (z/replace entry {:components []}) entry)]
      (if-let [comps (find-val entry #(= % :components))]
        (z/root-string (z/append-child comps (ident-node member)))
        (z/root-string (-> entry (z/append-child :components)
                           (z/append-child [(ident-node member)])))))))

(defn- remove-entry
  "Remove one map entry (key+value) from a section; nil when absent."
  [text section pred]
  (when-let [sect (sect-val (zroot text) section)]
    (when-let [k (find-key sect pred)]
      (z/root-string (remove-pair k)))))

(defn- remove-first-component
  "Remove the first occurrence of member-id from any box's :components;
  nil when no box contains it."
  [text member-id]
  (when-let [sect (sect-val (zroot text) :boxes)]
    (loop [k (z/down sect)]
      (when (some? k)
        (let [v (z/right k)
              comps (when (map? (z/sexpr v)) (find-val v #(= % :components)))
              hit (when (and (some? comps) (coll? (z/sexpr comps)))
                    (loop [c (z/down comps)]
                      (when (some? c)
                        (if (= member-id (ident->str (z/sexpr c))) c (recur (z/right c))))))]
          (if (some? hit)
            (z/root-string (z/remove hit))
            (recur (z/right v))))))))

(defn- until-done [text f]
  (if-let [t (f text)] (recur t f) text))

(defn- remove-edges-touching [text id]
  (until-done text
    (fn [t] (remove-entry t :edges
              (fn [s] (and (vector? s) (some #(= id (ident->str %)) s)))))))

(defn delete [text {:keys [section id]}]
  (case section
    :edges (or (remove-entry text :edges (edge-key-pred id)) (unknown! :edges id))
    :nodes (let [t (or (remove-entry text :nodes (entry-pred :nodes id))
                       (unknown! :nodes id))]
             (-> t (remove-edges-touching id)
                 (until-done (fn [t'] (remove-first-component t' id)))))
    :boxes (let [t (or (remove-entry text :boxes (entry-pred :boxes id))
                       (unknown! :boxes id))]
             (-> t (remove-edges-touching id)
                 (until-done (fn [t'] (remove-first-component t' id)))))))

(defn- root-loc
  "The root map's zloc, from any zloc inside the file."
  [loc]
  (loop [c loc]
    (if (nil? (z/up c)) (z/down c) (recur (z/up c)))))

(defn- rename-elems
  "zloc of a vector/set with every element naming `old` replaced by
  `new-node`; returns the zloc of the collection itself. Elements that
  have no sexpr (`#_` uneval forms) are left alone."
  [coll-loc old new-node]
  (loop [c (z/down coll-loc) last nil]
    (if (nil? c)
      (if (nil? last) coll-loc (z/up last))
      (let [s (try (z/sexpr c) (catch Exception _ ::skip))
            c' (if (and (not= ::skip s) (= old (ident->str s))) (z/replace c new-node) c)]
        (recur (z/right c') c')))))

(defn- rename-in-edge-keys
  "Root zloc with every [from to] edge key mentioning `old` now naming
  `new-node` there."
  [root old new-node]
  (if-let [sect (sect-val root :edges)]
    (loop [k (z/down sect) last sect]
      (if (nil? k)
        (root-loc last)
        (let [k' (if (vector? (z/sexpr k)) (rename-elems k old new-node) k)
              v (z/right k')]
          (recur (z/right v) v))))
    root))

(defn- rename-in-box-components
  "Root zloc with every box :components entry naming `old` now naming
  `new-node`."
  [root old new-node]
  (if-let [sect (sect-val root :boxes)]
    (loop [k (z/down sect) last sect]
      (if (nil? k)
        (root-loc last)
        (let [v (z/right k)
              comps (when (map? (z/sexpr v)) (find-val v #(= % :components)))
              v' (if (and (some? comps) (coll? (z/sexpr comps)))
                   (z/up (rename-elems comps old new-node))
                   v)]
          (recur (z/right v') v'))))
    root))

(defn- referenced-ids
  "Every id an edge endpoint or a box membership names — including ones
  that no node or box defines (the loader tolerates dangling refs)."
  [data]
  (into #{}
        (concat (mapcat (fn [k] (when (vector? k) (map ident->str k)))
                        (keys (ensure-map-section data :edges)))
                (mapcat (fn [[_ v]] (map ident->str (:components v)))
                        (ensure-map-section data :boxes)))))

(defn rename
  "Give node or box `id` the id `to`: its map key plus every reference —
  edge endpoints and box memberships — keeping the file's formatting.
  Nodes, boxes and the references to them share one namespace, so the
  new id must be unused everywhere; a name that is both a node and a
  box is ambiguous and refused."
  [text {:keys [section id to]}]
  (let [to (str/trim (str to))
        data (parsed text)]
    (when (= section :edges) (fail! "edges have no id to rename"))
    (when (str/blank? to) (fail! "new id is empty"))
    (when (re-find #"[\r\n]" to) (fail! "an id must be a single line"))
    (when-not (exists? data section id) (unknown! section id))
    (when (and (exists? data :nodes id) (exists? data :boxes id))
      (fail! (str (pr-str id) " is both a node and a box; rename it in the file")))
    (if (= to id)
      text
      (do
        (when (or (exists? data :nodes to) (exists? data :boxes to))
          (fail! (str (pr-str to) " already exists")))
        (when (contains? (referenced-ids data) to)
          (fail! (str (pr-str to) " is already referenced by an edge or box")))
        (let [new-node (ident-node to)
              sect (sect-val (zroot text) section)
              k (or (find-key sect (entry-pred section id)) (unknown! section id))]
          (-> (root-loc (z/replace k new-node))
              (rename-in-edge-keys id new-node)
              (rename-in-box-components id new-node)
              z/root-string))))))

(defn- norm-op
  "Browser payload -> internal op: keywordize section/attr, keep ids.
  Attr names are validated against the same ident regex ident-node uses —
  set-attr/del-attr both keywordize the raw browser string and write it
  straight into the file, so an unvalidated name (e.g. one containing a
  space) would corrupt the EDN on write."
  [op]
  (cond-> op
    (some? (:section op)) (update :section keyword)
    (some? (:attr op)) (update :attr
                          (fn [a]
                            (if (re-matches ident-re a)
                              (keyword a)
                              (fail! (str "invalid attribute name " (pr-str a))))))))

(defn- apply-op [text op]
  (let [o (norm-op op)
        op-name (:op o)]
    (when (and (some? (:section o)) (not (contains? #{:nodes :edges :boxes} (:section o))))
      (fail! (str "unknown section " (pr-str (:section op)))))
    (case op-name
      "set-attr" (set-attr text o)
      "del-attr" (del-attr text o)
      "add-node" (add-node text o)
      "add-edge" (add-edge text o)
      "add-box" (add-box text o)
      "box-add" (box-add text o)
      "retarget-edge" (retarget-edge text o)
      "set-direction" (set-direction text o)
      "delete" (delete text o)
      "rename" (rename text o)
      (fail! (str "unknown op " (pr-str op-name))))))

(defn apply-ops
  "Apply a batch of ops to text, atomically: on any failure, return
  {:error msg} without partial mutation. On success, {:text new-text}."
  [text ops]
  (try
    {:text (reduce apply-op text ops)}
    (catch clojure.lang.ExceptionInfo e
      (if (:edit-error (ex-data e)) {:error (ex-message e)} (throw e)))))
