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

(defn ident-node
  "New ids as keywords when legal, else strings."
  [id]
  (if (re-matches #"[A-Za-z0-9*+!_'?<>=./-]+" id) (keyword id) id))

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

(defn- exists? [data section id]
  (case section
    :nodes (contains? (into #{} (map ident->str) (keys (:nodes data))) id)
    :boxes (contains? (into #{} (map ident->str) (keys (:boxes data))) id)))

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
        (keys (:edges data))))

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

(defn box-add [text {:keys [box member]}]
  (let [data (parsed text)]
    (when-not (or (exists? data :nodes member) (exists? data :boxes member))
      (fail! (str "unknown node or box " (pr-str member))))
    (when (= box member) (fail! "a box cannot contain itself"))
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
