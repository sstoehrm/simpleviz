(ns edit
  "Semantic edit operations on the raw text of a graph EDN file.
  rewrite-clj zipper patches: comments and formatting survive. Map-form
  files only. Every public op is text -> text; failures throw ex-info
  with {:edit-error true} and a user-facing message."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [graph :refer [ident->str]]
            [rewrite-clj.zip :as z]
            [themes]))

(defn- fail! [msg] (throw (ex-info msg {:edit-error true})))

(def ^:private ident-re #"[A-Za-z0-9*+!_'?<>=./-]+")

(defn- keyword-text?
  "Is :s a keyword the EDN reader gives back as s? The regex alone lets
  through such as api/ or a/1, which the file would then fail to load."
  [s]
  (and (some? (re-matches ident-re s))
       (= s (try (ident->str (edn/read-string (str ":" s))) (catch Exception _ nil)))))

(defn ident-node
  "New ids as keywords when legal, else strings."
  [id]
  (if (keyword-text? id) (keyword id) id))

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

(defn zroot [text]
  (let [zl (try (z/of-string text) (catch Exception _ (fail! "file does not parse as EDN")))]
    (when-not (map? (z/sexpr zl)) (fail! "root must be a map"))
    ;; the page shows such a file as an error (serve/parse-graph), so an
    ;; edit must not land in it; #_ discards after the root are fine
    (when (some? (loop [r (z/right zl)] (if (and (some? r) (= :uneval (z/tag r))) (recur (z/right r)) r)))
      (fail! "content after the end of the graph: check for an extra }"))
    zl))

(defn find-key
  "zloc of the KEY in the map at zmap whose sexpr satisfies pred, nil if none."
  [zmap pred]
  (loop [k (z/down zmap)]
    (when (some? k)
      (if (pred (z/sexpr k)) k (recur (z/right (z/right k)))))))

(defn find-val [zmap pred] (some-> (find-key zmap pred) z/right))

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

(defn remove-pair
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

(defn- element-exists? [data id]
  (or (exists? data :nodes id) (exists? data :boxes id)))

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
      (when-not (element-exists? data x)
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
    (when-not (element-exists? data to)
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

(defn- inside?
  "Is box `box` somewhere in box `outer`'s content, at any depth?"
  [data outer box]
  (loop [todo [outer] seen #{}]
    (if-let [b (first todo)]
      (let [kids (filterv #(exists? data :boxes %) (box-components data b))]
        (or (boolean (some #{box} kids))
            (recur (into (vec (rest todo)) (remove seen kids)) (conj seen b))))
      false)))

(declare remove-first-component until-done)

(defn box-add
  "Put `member` (a node or box) into box `box`, taking it out of the box
  it was in: an element sits in one box, and the loader would keep only
  its first membership (#112). A box can't go into its own content."
  [text {:keys [box member]}]
  (let [data (parsed text)]
    (when-not (element-exists? data member)
      (fail! (str "unknown node or box " (pr-str member))))
    (when (= box member) (fail! "a box cannot contain itself"))
    (when (contains? (box-components data box) member)
      (fail! (str "\"" member "\" is already in box \"" box "\"")))
    (when (and (exists? data :boxes member) (inside? data member box))
      (fail! "a box can't go inside its own content"))
    (let [text (until-done text (fn [t] (remove-first-component t member)))
          entry (entry-val (zroot text) :boxes box)
          entry (if (nil? (z/sexpr entry)) (z/replace entry {:components []}) entry)]
      (if-let [comps (find-val entry #(= % :components))]
        (z/root-string (z/append-child comps (ident-node member)))
        (z/root-string (-> entry (z/append-child :components)
                           (z/append-child [(ident-node member)])))))))

(defn- parent-box
  "Name of the box whose :components list `id`, nil when none does."
  [data id]
  (some (fn [[k _]] (let [b (ident->str k)] (when (contains? (box-components data b) id) b)))
        (ensure-map-section data :boxes)))

(defn- sexpr-of
  "zloc c's form, or ::skip for a `#_` discard, which has none."
  [c]
  (try (z/sexpr c) (catch Exception _ ::skip)))

(defn- names?
  "Does zloc `c` hold the identifier `id`? `#_` uneval forms, which have
  no sexpr, never do."
  [c id]
  (let [x (sexpr-of c)]
    (and (not= ::skip x) (= id (ident->str x)))))

(defn- find-component
  "The zloc of `member-id` among the :components zloc `comps`, or nil."
  [comps member-id]
  (loop [c (z/down comps)]
    (when (some? c)
      (if (names? c member-id) c (recur (z/right c))))))

(defn box-remove
  "Take `member` out of box `box`'s :components. When that box sits in a
  parent box, the member joins the parent instead of ending up nowhere."
  [text {:keys [box member]}]
  (let [data (parsed text)]
    (when-not (exists? data :boxes box) (unknown! :boxes box))
    (when-not (contains? (box-components data box) member)
      (fail! (str "\"" member "\" is not in box \"" box "\"")))
    (let [comps (find-val (entry-val (zroot text) :boxes box) #(= % :components))
          hit (find-component comps member)
          text' (z/root-string (z/remove hit))]
      (if-let [parent (parent-box data box)]
        (box-add text' {:box parent :member member})
        text'))))

(defn- first-component
  "The zloc of the first occurrence of member-id in any box's
  :components under root zloc `root`, or nil. Elements with no sexpr
  (`#_` uneval forms) are skipped; the :boxes form is not checked here
  (sect-val does)."
  [root member-id]
  (when-let [sect (find-val root #(= % :boxes))]
    (loop [k (z/down sect)]
      (when (some? k)
        (let [v (z/right k)
              comps (when (map? (z/sexpr v)) (find-val v #(= % :components)))
              hit (when (and (some? comps) (coll? (z/sexpr comps)))
                    (find-component comps member-id))]
          (or hit (recur (z/right v))))))))

(defn- edit-first-component
  "Apply f (a zloc -> zloc edit) to the first occurrence of member-id in
  any box's :components; nil when no box contains it."
  [text member-id f]
  (let [root (zroot text)]
    (when (some? (sect-val root :boxes))
      (some-> (first-component root member-id) f z/root-string))))

(defn- remove-first-component
  "Remove the first occurrence of member-id from any box's :components;
  nil when no box contains it."
  [text member-id]
  (edit-first-component text member-id z/remove))

(defn- until-done [text f]
  (if-let [t (f text)] (recur t f) text))

(defn- root-loc
  "The root map's zloc, from any zloc inside the file."
  [loc]
  (loop [c loc]
    (if (nil? (z/up c)) (z/down c) (recur (z/up c)))))

(defn- drop-key
  "Root zloc `root` without the first entry of `section` whose key
  satisfies pred, or nil when none does. The section's form is not
  checked here (sect-val does)."
  [root section pred]
  (when-let [k (some-> (find-val root #(= % section)) (find-key pred))]
    (root-loc (remove-pair k))))

(defn- check-sections!
  "Refuse a pre-v2 vector form of the sections deleting from `section`
  touches: the edges alone, or for a node or box all three."
  [root section]
  (doseq [s (if (= section :edges) [:edges] [section :edges :boxes])]
    (sect-val root s)))

(defn- delete-at
  "Root zloc `root` with target {:section :id} deleted: an edge, or a
  node or box with the edges touching it and its box memberships —
  removed one by one in the one zipper, never re-parsed."
  [root {:keys [section id]}]
  (case section
    :edges (or (drop-key root :edges (edge-key-pred id)) (unknown! :edges id))
    (:nodes :boxes)
    (-> (or (drop-key root section (entry-pred section id)) (unknown! section id))
        (until-done (fn [r] (drop-key r :edges (fn [s] (and (vector? s) (some #(= id (ident->str %)) s))))))
        (until-done (fn [r] (some-> (first-component r id) z/remove root-loc))))))

(defn delete [text {:keys [section] :as target}]
  (let [root (zroot text)]
    (check-sections! root section)
    (z/root-string (delete-at root target))))

(defn- rename-elems
  "zloc of a vector/set with every element naming `old` replaced by
  `new-node`; returns the zloc of the collection itself. Elements that
  have no sexpr (`#_` uneval forms) are left alone."
  [coll-loc old new-node]
  (loop [c (z/down coll-loc) last nil]
    (if (nil? c)
      (if (nil? last) coll-loc (z/up last))
      (let [c' (if (names? c old) (z/replace c new-node) c)]
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
        (when (element-exists? data to)
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

(defn wrap
  "Create box `box` around node or box `member`: the new box holds the
  member as its only component and, when the member sat in a parent
  box, takes the member's place there; any further membership of the
  member (the loader honours only the first) is dropped — so the member
  is in exactly one box afterwards. The new id must be unused anywhere,
  like a rename target."
  [text {:keys [box member]}]
  (let [data (parsed text)]
    (when-not (element-exists? data member)
      (fail! (str "unknown node or box " (pr-str member))))
    (when (and (exists? data :nodes member) (exists? data :boxes member))
      (fail! (str (pr-str member) " is both a node and a box; rename one in the file")))
    (when (element-exists? data box)
      (fail! (str (pr-str box) " already exists")))
    (when (contains? (referenced-ids data) box)
      (fail! (str (pr-str box) " is already referenced by an edge or box")))
    (let [new-node (ident-node box)]
      (-> (or (edit-first-component text member #(z/replace % new-node)) text)
          (until-done (fn [t] (remove-first-component t member)))
          (add-box {:id box})
          (box-add {:box box :member member})))))

(defn set-theme
  "Point the top-level :theme at built-in theme `theme` (a name), or
  remove it when theme is nil or blank. A custom (map) theme is refused
  either way: a name would drop its overrides."
  [text {:keys [theme]}]
  (when-not (or (nil? theme) (string? theme))
    (fail! (str "theme must be a theme name, got " (pr-str theme))))
  (let [root (zroot text)
        kloc (find-key root #(= % :theme))
        remove? (str/blank? theme)]
    (when (and (some? kloc) (map? (z/sexpr (z/right kloc))))
      (fail! "the file sets a custom theme (a :theme map); change it in the file"))
    (when-not (or remove? (contains? themes/THEMES (keyword theme)))
      (fail! (str "unknown theme " (pr-str theme))))
    (cond
      remove? (if (some? kloc) (z/root-string (remove-pair kloc)) text)
      (some? kloc) (z/root-string (z/replace (z/right kloc) (keyword theme)))
      ;; a new key goes on its own line after the others, where it's seen
      (some? (z/down root))
      (-> root
          (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces 1))
          (z/append-child* (n/keyword-node :theme)) (z/append-child* (n/spaces 1))
          (z/append-child* (n/keyword-node (keyword theme)))
          z/root-string)
      :else (-> root (z/append-child :theme) (z/append-child (keyword theme)) z/root-string))))

;; ---- copy and paste: a selection as a graph of its own ----

(defn- form-at
  "loc, or the first form right of it that is no `#_` discard; nil at
  the end."
  [loc]
  (loop [c loc] (if (and (some? c) (= :uneval (z/tag c))) (recur (z/right c)) c)))

(defn- section-entries
  "The [key value] zlocs of `section`'s entries in text, in file order,
  `#_` discards skipped; [] when the section is absent."
  [text section]
  (if-let [sect (sect-val (zroot text) section)]
    (loop [k (form-at (z/down sect)) acc []]
      (if (nil? k)
        acc
        (let [v (form-at (z/right k))]
          (recur (some-> v z/right form-at) (conj acc [k v])))))
    []))

(defn- key-column
  "The column (0-based) section's keys line up in, when its entries sit
  one per line; nil otherwise."
  [text section]
  (when-let [sect (sect-val (z/of-string text {:track-position? true}) section)]
    (let [k1 (z/down sect)
          k2 (some-> k1 z/right z/right)]
      (when (and (some? k2) (not= (first (z/position k1)) (first (z/position k2))))
        (dec (second (z/position k1)))))))

(defn- with-contents
  "ids and, for those that are boxes, their members at any depth."
  [data ids]
  (loop [todo (vec ids) seen #{}]
    (if-let [id (first todo)]
      (recur (into (subvec todo 1) (remove seen (box-components data id))) (conj seen id))
      seen)))

(defn- closure-ids
  "The ids of nodes and boxes `ids` ({:section :id}) and, of the boxes,
  their members at any depth; fails on an unknown one or none."
  [data ids]
  (when (empty? ids) (fail! "nothing selected"))
  (doseq [{:keys [section id]} ids]
    (when-not (exists? data (keyword section) id) (unknown! (keyword section) id)))
  (with-contents data (map :id ids)))

(defn- entries-text
  "The EDN text of a graph of `entries`, section -> [[key-text
  value-text] …]: one entry a line, keys lined up, empty sections left
  out."
  [entries]
  (let [sections (keep (fn [s]
                         (let [es (get entries s)
                               pad (apply str (repeat (+ 3 (count (str s))) " "))]
                           (when (seq es)
                             (str s " {" (str/join (str "\n" pad) (map (fn [[k v]] (str k " " v)) es)) "}"))))
                       [:nodes :edges :boxes])]
    (str "{" (str/join "\n " sections) "}\n")))

(defn- picked
  "The entries [k v] (zlocs) per section that nodes and boxes `ids`
  ({:section :id}) take — each box with its members at any depth, and
  the edges among them all — in file order."
  [text ids]
  (let [in (closure-ids (parsed text) ids)
        in? (fn [k] (if (vector? k) (every? #(contains? in (ident->str %)) k) (contains? in (ident->str k))))]
    (into {} (map (fn [s] [s (filterv (fn [[k _]] (in? (z/sexpr k))) (section-entries text s))])
                  [:nodes :edges :boxes]))))

(defn- picked-targets
  "The node and box targets ({:section :id}) of `picked`, in file order."
  [picked]
  (vec (for [s [:nodes :boxes] [k _] (get picked s)] {:section s :id (ident->str (z/sexpr k))})))

(defn copy
  "Nodes and boxes `ids` ({:section :id}), each box with its members at
  any depth, and the edges among them all: {:edn the EDN text of a graph
  of their own, entries in file order and as written; :ids the targets
  ({:section :id}) that took, in file order — what a cut deletes}."
  [text {:keys [ids]}]
  (let [p (picked text ids)]
    {:edn (entries-text (update-vals p (fn [es] (map (fn [[k v]] [(z/string k) (z/string v)]) es))))
     :ids (picked-targets p)}))

(defn delete-selection
  "Delete nodes and boxes `ids` ({:section :id}) with all copy takes for
  them — the boxes' members at any depth — and the edges touching any:
  what a cut deletes, the text parsed a few times in all, never once
  per element deleted."
  [text {:keys [ids]}]
  (let [root (zroot text)]
    (check-sections! root :nodes)
    (z/root-string (reduce delete-at root (picked-targets (picked text ids))))))

(defn- nothing-to-paste! []
  (fail! "nothing to paste: the clipboard holds no simpleviz nodes or boxes"))

(defn- clip-data
  "The clipboard text s as a graph map with nodes or boxes to paste."
  [s]
  (let [v (try (z/sexpr (z/of-string (str s))) (catch Exception _ nil))
        some-map? (fn [x] (and (map? x) (seq x)))]
    (if (and (map? v) (or (some-map? (:nodes v)) (some-map? (:boxes v))))
      v
      (nothing-to-paste!))))

(defn- rename-in
  "The zloc of collection `coll` with each element naming an id that
  `renames` (id -> new id) has renamed; `#_` discards stay."
  [coll renames]
  (loop [c (z/down coll) last nil]
    (if (nil? c)
      (if (nil? last) coll (z/up last))
      (let [x (sexpr-of c)
            to (when (not= ::skip x) (get renames (ident->str x)))
            c' (if (some? to) (z/replace c (ident-node to)) c)]
        (recur (z/right c') c')))))

(defn- outside-member
  "The zloc of the first member of box value zloc `v`'s :components that
  is no id of the clip (`frag?`), or nil."
  [v frag?]
  (when-let [comps (find-val v #(= % :components))]
    (loop [c (z/down comps)]
      (when (some? c)
        (let [x (sexpr-of c)]
          (if (and (not= ::skip x) (not (frag? (ident->str x)))) c (recur (z/right c))))))))

(defn- edit-text
  "Text s, one form, after (f its zloc) edits it."
  [s f]
  (z/root-string (f (z/of-string s))))

(defn- fresh-entry
  "Clip entry [k v] (zlocs) of `section` as it goes into the graph,
  [key-text value-text], edited on its own text: ids renamed by
  `renames` (a renamed :name suffixed alike), :pos dropped — the copy
  would sit on the original — and edges and memberships naming ids
  outside the clip (`frag?`) gone; nil for such an edge."
  [section [k v] frag? renames]
  (let [kx (z/sexpr k)
        vx (z/sexpr v)]
    (if (= section :edges)
      (when (or (not (vector? kx)) (every? #(frag? (ident->str %)) kx))
        [(if (vector? kx) (edit-text (z/string k) #(rename-in % renames)) (z/string k)) (z/string v)])
      (let [id (ident->str kx)
            to (get renames id)
            s (cond-> (z/string v)
                (and (map? vx) (contains? vx :pos))
                (edit-text #(remove-pair (find-key % (fn [a] (= a :pos)))))
                (and (some? to) (map? vx) (string? (:name vx)))
                (edit-text #(z/replace (find-val % (fn [a] (= a :name))) (str (:name vx) (subs to (count id)))))
                (and (= section :boxes) (map? vx) (coll? (:components vx)))
                (-> (until-done (fn [s'] (some-> (outside-member (z/of-string s') frag?) z/remove z/root-string)))
                    (edit-text #(rename-in (find-val % (fn [a] (= a :components))) renames))))]
        [(if (some? to) (pr-str (ident-node to)) (z/string k)) s]))))

(defn- append-components
  "Text with `members`, ids in no box yet, appended to box `box`'s
  :components in one edit."
  [text box members]
  (if (empty? members)
    text
    (let [entry (entry-val (zroot text) :boxes box)
          entry (if (nil? (z/sexpr entry)) (z/replace entry {:components []}) entry)]
      (z/root-string
       (if-let [comps (find-val entry #(= % :components))]
         (reduce (fn [c m] (z/append-child c (ident-node m))) comps members)
         (-> entry (z/append-child :components) (z/append-child (mapv ident-node members))))))))

(defn paste
  "Add the graph in EDN text `edn` (what copy gives) to text. An id that
  is taken — defined or referenced — gets the first free suffix -1, -2,
  …, its :name the same; :pos goes (the copy would sit on the original);
  edges and memberships naming ids the clip does not define go too.
  With `into`, the clip's top-level elements go into that box."
  [text {:keys [edn] box :into}]
  (let [clip (clip-data edn)
        data (parsed text)
        frag-ids (into [] (comp (mapcat (fn [s] (keys (ensure-map-section clip s))))
                                (filter #(or (keyword? %) (string? %)))
                                (map ident->str) (distinct))
                       [:nodes :boxes])
        frag? (set frag-ids)
        _ (doseq [k (keys (:nodes clip))]
            (when (some #(= (ident->str k) (ident->str %)) (keys (:boxes clip)))
              (fail! (str "the clipboard holds " (pr-str (ident->str k)) " as a node and as a box"))))
        ;; every id the graph defines or names
        taken (into (referenced-ids data)
                    (map ident->str)
                    (concat (keys (ensure-map-section data :nodes)) (keys (ensure-map-section data :boxes))))
        renames (first (reduce (fn [[m used] id]
                                 (if (contains? taken id)
                                   (let [to (some #(let [c (str id "-" %)] (when-not (contains? used c) c)) (rest (range)))]
                                     [(assoc m id to) (conj used to)])
                                   [m used]))
                               [{} (into taken frag-ids)] frag-ids))
        ;; the clip's entries as they go in, each edited on its own text:
        ;; one parse of the clip, not one per entry
        clip-text (entries-text
                   (into {} (map (fn [s]
                                   [s (keep (fn [e] (fresh-entry s e frag? renames)) (section-entries (str edn) s))])
                                 [:nodes :edges :boxes])))
        members (into #{} (comp (mapcat (fn [[_ v]] (map ident->str (:components v)))) (filter frag?)
                                (map #(get renames % %)))
                      (:boxes clip))
        add (fn [t s]
              (let [es (section-entries clip-text s)]
                (if (empty? es)
                  t
                  (let [col (key-column t s)]
                    (z/root-string
                     (reduce (fn [sect [k v]]
                               (if (some? col)
                                 (-> sect (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces col))
                                     (z/append-child* (z/node k)) (z/append-child* (n/spaces 1)) (z/append-child* (z/node v)))
                                 (-> sect (z/append-child (z/node k)) (z/append-child (z/node v)))))
                             (ensure-sect (zroot t) s) es))))))
        out (reduce add text [:nodes :edges :boxes])]
    (if (some? box)
      (append-components out box (remove members (map #(get renames % %) frag-ids)))
      out)))

(defn- norm-op
  "Browser payload -> internal op: keywordize section/attr, keep ids.
  Attr names are validated as ident-node validates ids — set-attr/del-attr
  both keywordize the raw browser string and write it straight into the
  file, so an unvalidated name (e.g. one containing a space, or api/)
  would corrupt the EDN on write."
  [op]
  (cond-> op
    (some? (:section op)) (update :section keyword)
    (some? (:attr op)) (update :attr
                          (fn [a]
                            (if (keyword-text? a)
                              (keyword a)
                              (fail! (str "invalid attribute name " (pr-str a))))))))

(defn apply-op [text op]
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
      "box-remove" (box-remove text o)
      "retarget-edge" (retarget-edge text o)
      "set-direction" (set-direction text o)
      "delete" (delete text o)
      "delete-selection" (delete-selection text o)
      "rename" (rename text o)
      "wrap" (wrap text o)
      "set-theme" (set-theme text o)
      "paste" (paste text o)
      (fail! (str "unknown op " (pr-str op-name))))))

(defn apply-ops
  "Apply a batch of ops to text, atomically: on any failure, return
  {:error msg} without partial mutation. On success, {:text new-text}."
  [text ops]
  (try
    {:text (reduce apply-op text ops)}
    (catch clojure.lang.ExceptionInfo e
      (if (:edit-error (ex-data e)) {:error (ex-message e)} (throw e)))))
