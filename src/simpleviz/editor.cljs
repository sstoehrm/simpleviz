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

(defn name->id
  "The id a display name suggests: lowercased, every run of characters
  the server would not accept in a keyword id (its ident alphabet is
  letters, digits and *+!_'?<>=./-) collapsed to one dash — as is any
  run of dashes that results — and no dash at either end. Empty when
  nothing legal is left."
  [nm]
  (-> (.toLowerCase (str nm))
      (.replace (js/RegExp. "[^a-z0-9*+!_'?<>=./-]+" "g") "-")
      (.replace (js/RegExp. "-{2,}" "g") "-")
      (.replace (js/RegExp. "^-+|-+$" "g") "")))

(defn derived-id
  "The id a name edit on `tgt` should rename the element to, or nil
  when no rename should ride along: the target is an edge (no id of
  its own), the name yields no legal id, or it already is the id."
  [tgt name-text]
  (when (not= "edges" (:section tgt))
    (let [id (name->id name-text)]
      (when (and (not= id "") (not= id (:id tgt)))
        id))))

(defn direction-op [tgt dir]
  {:op "set-direction" :edge (:id tgt) :direction dir})

(defn- bare-id [item] (.slice (:id item) 2))

(defn- text-attr-op
  "The set-attr op giving the new element `id` of `section` the text
  attribute `attr`. The value goes as an EDN string literal (no scalar
  fallback), so \"2024\" or \"nil\" stay text."
  [section id attr value]
  (set-attr-op {:section section :id id} attr (pr-str value) false))

(defn- name-op [section id nm] (text-attr-op section id "name" nm))

(defn parse-entry
  "The creation prompt's text split at the first `::` into the name and
  the type, both trimmed: {:name .. :type ..}, :type nil when there is
  none (or it is empty)."
  [text]
  (let [text (str text)
        i (.indexOf text "::")
        [nm tp] (if (neg? i) [text ""] [(.slice text 0 i) (.slice text (+ i 2))])
        tp (.trim tp)]
    {:name (.trim nm) :type (if (= tp "") nil tp)}))

(defn add-node-ops
  "Ops to create a new free-standing node (no selection required) named
  `nm`; the caller derives `new-id` from the name (name->id)."
  [new-id nm]
  [{:op "add-node" :id new-id}
   (name-op "nodes" new-id nm)])

(defn add-connected-ops
  "Ops to create a new node named `nm` and wire an edge from the
  selected node to it."
  [from new-id nm]
  [{:op "add-node" :id new-id}
   (name-op "nodes" new-id nm)
   {:op "add-edge" :from from :to new-id :direction "->"}])

(defn add-node-in-box-ops
  "Ops to create a new node named `nm` as a member of box `box-id`."
  [box-id new-id nm]
  [{:op "add-node" :id new-id}
   (name-op "nodes" new-id nm)
   {:op "box-add" :box box-id :member new-id}])

(defn named-edge-ops
  "The add-edge ops of a connect pick with a set-attr naming the new
  edge `nm` appended; unchanged when the name is blank."
  [ops nm]
  (let [nm (.trim (str nm))
        {:keys [from to]} (first ops)]
    (if (= nm "")
      ops
      (conj (vec ops) (name-op "edges" [from to] nm)))))

(defn box-remove-op
  "Ops to take `member-id` out of box `box-id` (the server moves it to
  the enclosing box, if any)."
  [box-id member-id]
  [{:op "box-remove" :box box-id :member member-id}])

(defn- with-type
  "ops with a set-attr :type appended for element `id` of `section`
  when the prompt named a type."
  [ops section id tp]
  (if (nil? tp) ops (conj (vec ops) (text-attr-op section id "type" tp))))

(defn creation-ops
  "What the toolbar's creation prompt `entry` ({:for kind :text text},
  an edge prompt also carrying the pick's :ops) submits for the
  selection target tgt: {:ops [...] :focus scene-id} — :focus the new
  element to select once it lands (nil for an edge). The text is
  `name` or `name::type` (parse-entry); the id is derived from the
  name (name->id); nil when the name yields none, so the prompt stays
  open. An edge is created unnamed on an empty name."
  [entry tgt]
  (let [{nm :name tp :type} (parse-entry (:text entry))
        id (name->id nm)
        from (:id tgt)]
    (if (= (:for entry) "edge")
      (let [ops (:ops entry)
            {:keys [from to]} (first ops)]
        {:ops (with-type (named-edge-ops ops nm) "edges" [from to] tp) :focus nil})
      (when (not= id "")
        (case (:for entry)
          "connect" {:ops (with-type (add-connected-ops from id nm) "nodes" id tp) :focus (str "n:" id)}
          "newbox" {:ops (with-type (wrap-in-box-ops from id nm) "boxes" id tp) :focus (str "b:" id)}
          "inbox" {:ops (with-type (add-node-in-box-ops from id nm) "nodes" id tp) :focus (str "n:" id)}
          "node" {:ops (with-type (add-node-ops id nm) "nodes" id tp) :focus (str "n:" id)}
          nil)))))

(defn wrap-in-box-ops
  "Ops to create a new box named `nm` around the selected node or box;
  the server also moves the member out of its old parent box into the
  new one."
  [member-id box-id nm]
  [{:op "wrap" :box box-id :member member-id}
   (name-op "boxes" box-id nm)])

(defn edit-body
  "The /api/edit POST body: routes ops to whichever file (\"old\"/\"new\")
  is the current edit target."
  [file ops]
  {:file file :ops ops})

(defn theme-menu
  "The theme menu's state for graph payload g. :value is the built-in the
  file names, \"custom\" for a :theme map, \"\" for none. The menu writes
  :theme into the file whose theme is shown (the new side of a
  comparison), so it's disabled for a custom theme (a name would drop its
  overrides) and when that file isn't editable."
  [g]
  (let [custom? (and (some? (:theme g)) (nil? (:theme-name g)))
        value (cond custom? "custom"
                    (some? (:theme-name g)) (:theme-name g)
                    :else "")]
    (cond
      custom? {:value value :disabled true
               :title "This graph sets a custom theme (a :theme map); change it in the file"}
      (not (:editable g)) {:value value :disabled true
                           :title "Read-only: the theme comes from the file"}
      :else {:value value :disabled false
             :title "Theme of this graph (sets :theme in the file)"})))

(defn set-theme-op
  "The edit op setting the file's :theme to built-in `theme`, or removing
  it for the menu's empty entry."
  [theme]
  {:op "set-theme" :theme (if (seq theme) theme nil)})

(defn create-body
  "The /api/create POST body: the served-folder path a followed ref
  names and the file (\"old\"/\"new\") being edited, which is the side
  a comparison creates."
  [file path]
  {:file file :path path})

(defn pick-ops
  "Ops for a pick-mode hit, or nil when item isn't a valid target for
  pick (keep picking). pick is one of:
  {:mode \"retarget\" :edge [a b] :end \"source\"|\"target\"} — any node
  or box is a valid new endpoint;
  {:mode \"into-box\" :member id} — only a box is valid, not the member itself;
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
      "into-box" (if (and (= kind "box") (not= (bare-id item) (:member pick)))
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

;; ---- refs between graphs ----

(defn resolve-ref
  "The root-relative path a `ref` on the file `current-path` (itself
  root-relative) points to, with `.`/`..`/empty segments collapsed;
  nil when the ref is blank, absolute, or climbs above the root (more
  `..` than `current-path` has directories)."
  [current-path ref]
  (let [ref (.trim (str (if (nil? ref) "" ref)))]
    (when (and (not= ref "")
               (not (.startsWith ref "/"))
               (nil? (re-find (js/RegExp. "^[A-Za-z]:") ref)))
      (loop [acc (vec (.slice (.split (str current-path) "/") 0 -1))
             segs (vec (.split ref "/"))]
        (if (empty? segs)
          (when (seq acc) (.join acc "/"))
          (let [seg (first segs)
                more (vec (rest segs))]
            (cond
              (or (= seg "") (= seg ".")) (recur acc more)
              (= seg "..") (when (seq acc) (recur (pop acc) more))
              :else (recur (conj acc seg) more))))))))

(defn top-box-of
  "The outermost box around scene id `scene-id` (\"n:api\", \"b:grp\"):
  walks `parent-of` (scene id -> box name) up to the last box; nil when
  the id is in no box."
  [parent-of scene-id]
  (loop [box (get parent-of scene-id)]
    (when (some? box)
      (let [up (get parent-of (str "b:" box))]
        (if (some? up) (recur up) box)))))

(defn parse-nav
  "The page's navigation state from its query string: {:file
  root-relative path or nil (the root file) :trail [paths visited
  before it]}, plus :focus (a scene id, \"n:api\") when the URL names
  an element to select. Each trail entry is URL-encoded on its own
  inside the parameter, so commas in file names survive."
  [query-string]
  (let [p (js/URLSearchParams. (str (if (nil? query-string) "" query-string)))
        file (.get p "file")
        trail (.get p "trail")
        focus (.get p "focus")]
    (cond-> {:file (if (or (nil? file) (= file "")) nil file)
             :trail (if (or (nil? trail) (= trail ""))
                      []
                      (mapv js/decodeURIComponent (.split trail ",")))}
      (and (some? focus) (not= focus "")) (assoc :focus focus))))

(defn nav-query
  "The query string (\"\" or \"?file=..&trail=..&focus=..\") for showing
  `file` (nil = root) with `trail` behind it and, optionally, element
  `focus` selected — the inverse of parse-nav."
  [file trail & [focus]]
  (let [p (js/URLSearchParams.)]
    (when (some? file) (.set p "file" file))
    (when (seq trail) (.set p "trail" (.join (mapv js/encodeURIComponent trail) ",")))
    (when (some? focus) (.set p "focus" focus))
    (let [s (.toString p)]
      (if (= s "") "" (str "?" s)))))

(defn follow-url
  "Query string for following a ref or pair from `current-path` to
  `target`: the current file joins the end of the trail; `focus`, when
  given, is the element to select there."
  [current-path trail target & [focus]]
  (nav-query target (conj (vec trail) current-path) focus))

(defn crumb-url
  "Query string for going back to trail entry i: it becomes the file
  shown, the entries before it stay the trail."
  [trail i]
  (nav-query (nth trail i) (vec (.slice trail 0 i))))

(defn banner-visible?
  "Show a banner with `text` unless it's empty or the viewer dismissed
  exactly this text (`dismissed`, nil when nothing was): a dismissed
  warnings or error banner comes back once its content changes."
  [text dismissed]
  (boolean (and (some? text) (not= text "") (not= text dismissed))))

(defn ref-of
  "The selection's :ref when it is a non-blank string, else nil."
  [sel]
  (let [r (:ref (:attrs sel))]
    (when (and (string? r) (not= (.trim r) "")) r)))

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
   ["a" "e" {"node" ["add-edge" "add edge"] "box" ["add-edge" "add edge"]}]
   ["a" "b" {"node" ["add-to-box" "add to box"] "box" ["add-box-member" "add box"]}]
   ["a" "n" {"box" ["add-node-member" "add node"]}]
   ["n" "n" {"none" ["new-node" "new node"] "node" ["new-connected-node" "new node"]
             "box" ["new-node-in-box" "new node"]}]
   ;; after n n, so a box's "new node" button shows n n (chord-for takes the first)
   ["c" "n" {"box" ["new-node-in-box" "new node"]}]
   ["n" "b" {"node" ["new-box" "new box"] "box" ["new-box" "new box"]}]
   ["r" "r" {"node" ["rename" "rename"] "box" ["rename" "rename"]}]
   ["r" "n" {"box" ["remove-node-member" "remove node"]}]
   ["r" "b" {"node" ["remove-from-box" "remove from box"]}]
   ["f" "r" {"node" ["follow-ref" "follow ref"] "edge" ["follow-ref" "follow ref"] "box" ["follow-ref" "follow ref"]}]
   ["f" "p" {"node" ["follow-pair" "follow pair"] "box" ["follow-pair" "follow pair"]}]])

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
