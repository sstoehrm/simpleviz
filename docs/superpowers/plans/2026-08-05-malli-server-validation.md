# Malli Server-Side Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all graph validation/normalization from the Squint frontend into the babashka server using malli for shape checks, making the frontend a pure view.

**Architecture:** New `server/graph.clj` normalizes parsed EDN (malli per-element shape checks with humanized warnings + plain-Clojure semantic normalization) into the exact graph shape the frontend already consumes; `/api/graph` serves it. `src/simpleviz/validate.cljs` is deleted; `app.cljs` derives `:boxes-by-name` client-side; transform/layout tests switch to hand-built fixtures.

**Tech Stack:** babashka + malli 0.19.1 (`:deps` in bb.edn), cheshire, existing Squint/reagami frontend.

**Spec:** `docs/superpowers/specs/2026-08-05-malli-server-validation-design.md` — read before starting.

## Global Constraints

- Lenient validation: malformed element → humanized warning + skip; never whole-file rejection; `normalize` never throws on any EDN value.
- Semantics preserved exactly: `:<-` swaps endpoints; arrows `{:source (= dir :<->) :target (not= dir :-)}`; name/type coerced via `str` with nil-fallback; first-box-wins membership; self-containment rejected; cycles broken with warning; duplicate box names skip the later one; empty/missing box name skipped; component ids prefixed `n:`/`b:` (node wins name collisions, with warning).
- New liberal inputs (server sees real EDN): `:direction` accepts keyword (`:->`) or string (`"->"`); box `:components` accepts sets or sequential collections.
- Output shape (after cheshire): `{:nodes {name {:id :name :type :attrs}} :edges [{:id :source :target :arrows :name :type :attrs}] :boxes [{:id :name :type :components :attrs}] :parent-of {} :warnings []}` — identical keys to what the frontend consumed before, minus `:boxes-by-name` (client derives it).
- Parse errors still `{"error": "..."}` status 200. Frontend behavior unchanged for the user.
- Squint gotchas still apply to any `.cljs` edits: keywords are NOT functions; maps are JS objects.
- After deleting `.cljs` files, stale compiled output can mask failures: verification steps MUST `rm -rf public/js` before `bb build`.
- Commit messages end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: Server-side normalize with malli

**Files:**
- Modify: `bb.edn` (add top-level `:deps`)
- Create: `server/graph.clj`
- Modify: `server/serve.clj` (replace `edn->json` with `graph-json`, rewire `/api/graph`)
- Test: `test/graph_test.clj`
- Modify: `test/server_test.clj` (retarget to `graph-json`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `graph/normalize` (EDN value → normalized graph map per Global Constraints), `serve/graph-json` (EDN string → JSON string of normalized graph, or `{"error": ...}` on parse failure). Task 2 relies on the `/api/graph` payload described in Global Constraints.

- [ ] **Step 1: Add malli to bb.edn**

Add a top-level `:deps` entry (sibling of `:paths`/`:tasks`) in `bb.edn`:

```clojure
:deps {metosin/malli {:mvn/version "0.19.1"}}
```

Run: `bb -e "(require '[malli.core :as m]) (println (m/validate :int 1))"` from the repo root → prints `true` (downloads malli on first run).

- [ ] **Step 2: Write the failing tests**

Create `test/graph_test.clj`:

```clojure
(ns graph-test
  (:require [clojure.test :refer [deftest is]]
            [graph]))

(defn base []
  {:nodes {"a" {:name "A" :type "svc"} "b" {:name "B"}}
   :edges []
   :boxes []})

(deftest empty-input
  (let [g (graph/normalize {})]
    (is (= {} (:nodes g)))
    (is (= [] (:edges g)))
    (is (= [] (:boxes g)))
    (is (= [] (:warnings g)))))

(deftest non-map-root-warns
  (let [g (graph/normalize [1 2 3])]
    (is (= {} (:nodes g)))
    (is (= 1 (count (:warnings g))))))

(deftest node-name-falls-back-to-key
  (let [g (graph/normalize {:nodes {"a" {}}})]
    (is (= "a" (get-in g [:nodes "a" :name])))
    (is (= "" (get-in g [:nodes "a" :type])))))

(deftest numeric-name-type-coerced
  (let [g (graph/normalize {:nodes {"a" {:name 7 :type 3}}
                            :edges [{:nodes ["a" "a"] :name 1 :type 2}]
                            :boxes [{:name "x" :type 9 :components ["a"]}]})]
    (is (= "7" (get-in g [:nodes "a" :name])))
    (is (= "3" (get-in g [:nodes "a" :type])))
    (is (= "2" (:type (first (:edges g)))))
    (is (= "9" (:type (first (:boxes g)))))))

(deftest direction-forward
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :->}]))]
    (is (= "a" (:source (first (:edges g)))))
    (is (= "b" (:target (first (:edges g)))))
    (is (= {:source false :target true} (:arrows (first (:edges g)))))))

(deftest direction-backward-swaps
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :<-}]))]
    (is (= "b" (:source (first (:edges g)))))
    (is (= "a" (:target (first (:edges g)))))
    (is (= {:source false :target true} (:arrows (first (:edges g)))))))

(deftest direction-both-and-none
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :<->}
                                                 {:nodes ["a" "b"]}]))]
    (is (= {:source true :target true} (:arrows (first (:edges g)))))
    (is (= {:source false :target false} (:arrows (second (:edges g)))))))

(deftest direction-as-string-accepted
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction "<->"}]))]
    (is (= {:source true :target true} (:arrows (first (:edges g)))))
    (is (= [] (:warnings g)))))

(deftest unknown-direction-warns-undirected
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "b"] :direction :=>}]))]
    (is (= 1 (count (:edges g))))
    (is (= {:source false :target false} (:arrows (first (:edges g)))))
    (is (= 1 (count (:warnings g))))))

(deftest edge-to-unknown-node-skipped
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a" "ghost"] :direction :->}]))]
    (is (= [] (:edges g)))
    (is (re-find #"ghost" (first (:warnings g))))))

(deftest edge-nodes-shape-enforced
  (let [g (graph/normalize (assoc (base) :edges [{:nodes ["a"]}
                                                 {:nodes "ab"}
                                                 {}
                                                 {:nodes #{"a" "b"}}]))]
    (is (= [] (:edges g)))
    (is (= 4 (count (:warnings g))))))

(deftest nil-edge-entries-skipped
  (let [g (graph/normalize (assoc (base) :edges [nil {:nodes ["a" "b"]}]))]
    (is (= 1 (count (:edges g))))
    (is (= 1 (count (:warnings g))))))

(deftest wrong-collection-types-at-top-level
  (let [g1 (graph/normalize {:nodes {"a" {}} :edges {:oops 1}})
        g2 (graph/normalize {:nodes {"a" {}} :boxes "nope"})
        g3 (graph/normalize {:nodes [1 2 3]})]
    (is (and (= [] (:edges g1)) (= 1 (count (:warnings g1)))))
    (is (and (= [] (:boxes g2)) (= 1 (count (:warnings g2)))))
    (is (and (= {} (:nodes g3)) (= 1 (count (:warnings g3)))))))

(deftest components-vector-and-set-prefixed
  (let [gv (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a" "b"]}]))
        gs (graph/normalize (assoc (base) :boxes [{:name "x" :components #{"a" "b"}}]))]
    (is (= ["n:a" "n:b"] (sort (:components (first (:boxes gv))))))
    (is (= ["n:a" "n:b"] (sort (:components (first (:boxes gs))))))
    (is (= "x" (get (:parent-of gv) "n:a")))))

(deftest non-collection-components-warn-empty
  (let [g1 (graph/normalize (assoc (base) :boxes [{:name "x" :components 42}]))
        g2 (graph/normalize (assoc (base) :boxes [{:name "x" :components "abc"}]))]
    (is (and (= [] (:components (first (:boxes g1)))) (= 1 (count (:warnings g1)))))
    (is (and (= [] (:components (first (:boxes g2)))) (= 1 (count (:warnings g2)))))))

(deftest boxes-nest
  (let [g (graph/normalize (assoc (base) :boxes [{:name "outer" :components ["inner"]}
                                                 {:name "inner" :components ["a"]}]))]
    (is (= ["b:inner"] (:components (first (:boxes g)))))
    (is (= "outer" (get (:parent-of g) "b:inner")))))

(deftest duplicate-membership-first-box-wins
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a"]}
                                                 {:name "y" :components ["a" "b"]}]))]
    (is (= "x" (get (:parent-of g) "n:a")))
    (is (= ["n:b"] (:components (second (:boxes g)))))
    (is (= 1 (count (:warnings g))))))

(deftest unknown-component-warns
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["ghost"]}]))]
    (is (= [] (:components (first (:boxes g)))))
    (is (re-find #"ghost" (first (:warnings g))))))

(deftest box-cannot-contain-itself
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["x" "a"]}]))]
    (is (= ["n:a"] (:components (first (:boxes g)))))
    (is (= 1 (count (:warnings g))))))

(deftest containment-cycle-broken
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["y"]}
                                                 {:name "y" :components ["x"]}]))
        links (keep #(get (:parent-of g) (str "b:" %)) ["x" "y"])]
    (is (= 1 (count links)))
    (is (>= (count (:warnings g)) 1))))

(deftest duplicate-box-name-later-skipped
  (let [g (graph/normalize (assoc (base) :boxes [{:name "x" :components ["a"]}
                                                 {:name "x" :components ["b"]}]))]
    (is (= 1 (count (:boxes g))))
    (is (= 1 (count (:warnings g))))))

(deftest empty-or-missing-box-name-skipped
  (let [g (graph/normalize (assoc (base) :boxes [{:name "" :components ["a"]}
                                                 {:components ["b"]}]))]
    (is (= [] (:boxes g)))
    (is (= 2 (count (:warnings g))))))
```

Update `test/server_test.clj` to:

```clojure
(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [serve]))

(deftest graph-json-serves-normalized-graph
  (let [out (json/parse-string
             (serve/graph-json
              (str "{:nodes {\"a\" {:name \"A\" :role [:active :passive]}}"
                   " :edges [{:nodes [\"a\" \"a\"] :direction :<-> :name \"self\"}]"
                   " :boxes [{:name \"g\" :components #{\"a\"}}]}")))]
    (is (= "a" (get-in out ["edges" 0 "source"])))
    (is (= true (get-in out ["edges" 0 "arrows" "source"])))
    (is (= ["active" "passive"] (get-in out ["nodes" "a" "attrs" "role"])))
    (is (= ["n:a"] (get-in out ["boxes" 0 "components"])))
    (is (= "g" (get-in out ["parent-of" "n:a"])))
    (is (= [] (get out "warnings")))))

(deftest parse-error-becomes-error-json
  (let [out (json/parse-string (serve/graph-json "{:unclosed"))]
    (is (contains? out "error"))
    (is (string? (get out "error")))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `bb test:clj`
Expected: FAIL — namespace `graph` not found / `serve/graph-json` unresolved.

- [ ] **Step 4: Write the implementation**

Create `server/graph.clj`:

```clojure
(ns graph
  "Parsed EDN -> normalized, render-ready graph. Shape checks via malli
  (lenient: humanized warning + skip), semantics in plain Clojure. Never
  throws on any input value."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private EdgeShape
  [:map [:nodes [:tuple :string :string]]])

(def ^:private directions
  {:-> :-> :<- :<- :<-> :<-> :- :-
   "->" :-> "<-" :<- "<->" :<-> "-" :-})

(defn- coerce-str [x fallback]
  (str (if (nil? x) fallback x)))

(defn- shape-warning
  "nil when value matches schema, else a humanized warning string."
  [schema label value]
  (when-let [expl (m/explain schema value)]
    (str label ": " (pr-str (me/humanize expl)))))

(defn- top-level [raw warn! k pred coerce-empty msg]
  (let [v (get raw k)]
    (cond (nil? v) coerce-empty
          (pred v) v
          :else (do (warn! msg) coerce-empty))))

(defn- build-nodes [nodes-in warn!]
  (reduce-kv
   (fn [acc k v]
     (let [k (str k)
           attrs (if (map? v) v {})]
       (when-not (or (nil? v) (map? v))
         (warn! (str "node \"" k "\": attributes must be a map, using {}")))
       (assoc acc k {:id k
                     :name (coerce-str (:name attrs) k)
                     :type (coerce-str (:type attrs) "")
                     :attrs attrs})))
   {} nodes-in))

(defn- build-edges [edges-in nodes warn!]
  (->> edges-in
       (map-indexed
        (fn [i e]
          (if-not (map? e)
            (do (warn! (str "edge " i ": not a map, skipped")) nil)
            (if-let [w (shape-warning EdgeShape (str "edge " i) e)]
              (do (warn! w) nil)
              (let [[a b] (:nodes e)
                    missing (remove #(contains? nodes %) [a b])]
                (if (seq missing)
                  (do (warn! (str "edge " i " [" a " " b "]: unknown node(s): "
                                  (str/join ", " missing)))
                      nil)
                  (let [dir0 (:direction e)
                        dir (cond
                              (nil? dir0) :-
                              (contains? directions dir0) (get directions dir0)
                              :else (do (warn! (str "edge " i ": unknown direction "
                                                    (pr-str dir0)
                                                    ", treating as undirected"))
                                        :-))
                        [source target] (if (= dir :<-) [b a] [a b])]
                    {:id (str "e" i)
                     :source source
                     :target target
                     :arrows {:source (= dir :<->) :target (not= dir :-)}
                     :name (coerce-str (:name e) "")
                     :type (coerce-str (:type e) "")
                     :attrs e})))))))
       (remove nil?)
       vec))

(defn- build-boxes [boxes-in warn!]
  (let [named (->> boxes-in
                   (map-indexed
                    (fn [i b]
                      (if-not (map? b)
                        (do (warn! (str "box " i ": not a map, skipped")) nil)
                        (let [nm (coerce-str (:name b) "")]
                          (if (str/blank? nm)
                            (do (warn! (str "box " i ": missing :name, skipped")) nil)
                            {:name nm :box b})))))
                   (remove nil?))]
    (first
     (reduce
      (fn [[acc seen] {:keys [name box]}]
        (if (contains? seen name)
          (do (warn! (str "box \"" name "\": duplicate name, later definition skipped"))
              [acc seen])
          (let [components
                (let [c (:components box)]
                  (cond (nil? c) []
                        (or (sequential? c) (set? c)) (vec c)
                        :else (do (warn! (str "box \"" name
                                              "\": :components must be a collection, skipped"))
                                  [])))]
            [(conj acc {:id (str "b:" name)
                        :name name
                        :type (coerce-str (:type box) "")
                        :components components
                        :attrs box})
             (conj seen name)])))
      [[] #{}] named))))

(defn- resolve-membership
  "First box in file order wins; components become prefixed ids."
  [boxes nodes warn!]
  (let [box-names (set (map :name boxes))]
    (reduce
     (fn [[bs parents] box]
       (let [[kept parents]
             (reduce
              (fn [[kept parents] c]
                (let [c (str c)
                      is-node (contains? nodes c)
                      is-box (contains? box-names c)]
                  (cond
                    (and (not is-node) (not is-box))
                    (do (warn! (str "box \"" (:name box) "\": unknown component \"" c "\""))
                        [kept parents])

                    (and is-box (not is-node) (= c (:name box)))
                    (do (warn! (str "box \"" (:name box) "\" cannot contain itself"))
                        [kept parents])

                    :else
                    (let [_ (when (and is-node is-box)
                              (warn! (str "\"" c "\" names both a node and a box; box \""
                                          (:name box) "\" gets the node")))
                          id (if is-node (str "n:" c) (str "b:" c))]
                      (if (contains? parents id)
                        (do (warn! (str "\"" c "\" is already in box \"" (get parents id)
                                        "\"; membership in \"" (:name box) "\" ignored"))
                            [kept parents])
                        [(conj kept id) (assoc parents id (:name box))])))))
              [[] parents] (:components box))]
         [(conj bs (assoc box :components kept)) parents]))
     [[] {}] boxes)))

(defn- break-cycles [boxes parent-of warn!]
  (reduce
   (fn [[bs parents] box]
     (loop [seen #{(:name box)}
            p (get parents (str "b:" (:name box)))]
       (cond
         (nil? p) [bs parents]

         (contains? seen p)
         (let [parent-name (get parents (str "b:" (:name box)))
               child-id (str "b:" (:name box))]
           (warn! (str "box containment cycle: detaching \"" (:name box)
                       "\" from \"" parent-name "\""))
           [(mapv (fn [b]
                    (if (= (:name b) parent-name)
                      (update b :components (fn [cs] (filterv #(not= % child-id) cs)))
                      b))
                  bs)
            (dissoc parents child-id)])

         :else (recur (conj seen p) (get parents (str "b:" p))))))
   [boxes parent-of] boxes))

(defn normalize [raw]
  (let [warnings (atom [])
        warn! (fn [msg] (swap! warnings conj msg))
        raw (if (map? raw)
              raw
              (do (when (some? raw) (warn! "root must be a map, ignoring content")) {}))
        nodes-in (top-level raw warn! :nodes map? {} ":nodes must be a map, ignoring it")
        edges-in (top-level raw warn! :edges sequential? [] ":edges must be a vector, ignoring it")
        boxes-in (top-level raw warn! :boxes sequential? [] ":boxes must be a vector, ignoring it")
        nodes (build-nodes nodes-in warn!)
        edges (build-edges edges-in nodes warn!)
        boxes0 (build-boxes boxes-in warn!)
        [boxes1 parents1] (resolve-membership boxes0 nodes warn!)
        [boxes parent-of] (break-cycles boxes1 parents1 warn!)]
    {:nodes nodes
     :edges edges
     :boxes boxes
     :parent-of parent-of
     :warnings @warnings}))
```

In `server/serve.clj`:
- Add `[graph]` to the `:require` vector.
- Replace the `edn->json` function with:

```clojure
(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  Parse failures return {\"error\": message} instead of throwing."
  [s]
  (try
    (json/generate-string (graph/normalize (edn/read-string s)))
    (catch Exception e
      (json/generate-string {:error (ex-message e)}))))
```

- In `handler`, change the `/api/graph` line to use it (the slurp stays inside the try — move it into `graph-json`'s caller position by changing the route to):

```clojure
"/api/graph" (json-response
              (try (graph-json (slurp @edn-file))
                   (catch Exception e
                     (json/generate-string {:error (ex-message e)}))))
```

(The outer try preserves the existing behavior where a deleted/unreadable file also yields `{"error": ...}`.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb test:clj`
Expected: all graph-test + server-test assertions PASS.

- [ ] **Step 6: Manual endpoint check**

```bash
bb serve examples/demo.edn &
sleep 2
curl -s localhost:8080/api/graph | head -c 300   # normalized JSON: "nodes", "edges" with "arrows", "parent-of", "warnings":[]
kill %1
```

- [ ] **Step 7: Commit**

```bash
git add bb.edn server/graph.clj server/serve.clj test/graph_test.clj test/server_test.clj
git commit -m "feat: server-side graph normalization with malli"
```

---

### Task 2: Slim the frontend to a pure view

**Files:**
- Delete: `src/simpleviz/validate.cljs`, `test/simpleviz/validate_test.cljs`
- Modify: `src/simpleviz/app.cljs` (consume normalized graph)
- Modify: `test/simpleviz/transform_test.cljs`, `test/simpleviz/layout_test.cljs` (fixture-based)
- Modify: `README.md` (one line)

**Interfaces:**
- Consumes: `/api/graph` now returns the normalized graph (keys `nodes`, `edges`, `boxes`, `parent-of`, `warnings`; or `error`). `to-elk`/`graph-view` signatures unchanged; they additionally need `:boxes-by-name`, which the app now derives.
- Produces: nothing downstream.

- [ ] **Step 1: Rewire app.cljs**

In `src/simpleviz/app.cljs`:
- Remove `[simpleviz.validate :refer [validate]]` from the `:require` vector.
- In `reload!`, replace the success branch (`(let [g (validate raw) ...`) so the fetched body IS the graph and `:boxes-by-name` is derived:

```clojure
(if (some? (:error raw))
  (swap! state assoc :error (str "EDN parse error: " (:error raw)))
  (let [g (assoc raw :boxes-by-name
                 (reduce (fn [acc b] (assoc acc (:name b) b)) {} (:boxes raw)))
        cmap {:node (colors/color-map (mapv (fn [n] (:type n))
                                            (js/Object.values (:nodes g)))
                                      colors/NODE-TABLE)
              :box (colors/color-map (mapv (fn [b] (:type b)) (:boxes g))
                                     colors/BOX-TABLE)
              :neutral-node colors/NEUTRAL-NODE
              :neutral-box colors/NEUTRAL-BOX}
        layout (js-await (.layout elk (to-elk g r/measure)))]
    (r/fit-view-once! layout)
    (swap! state assoc
           :error nil :graph g :warnings (:warnings g)
           :colors cmap :layout layout)))
```

- [ ] **Step 2: Delete the frontend validator**

```bash
git rm src/simpleviz/validate.cljs test/simpleviz/validate_test.cljs
```

- [ ] **Step 3: Convert transform/layout tests to fixtures**

Replace the top of `test/simpleviz/transform_test.cljs` (drop the validate require, add fixture helpers) and each test body:

```clojure
(ns simpleviz.transform-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.transform :refer [to-elk]]))

(defn node
  ([id] (node id ""))
  ([id type] {:id id :name id :type type :attrs {}}))

(defn graph [g]
  {:nodes (or (:nodes g) {})
   :edges (or (:edges g) [])
   :boxes (or (:boxes g) [])
   :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (or (:boxes g) []))
   :parent-of (or (:parent-of g) {})
   :warnings []})

(defn measure [text _font] (* (.-length text) 7))

(test "node sizing uses label widths; typed nodes are taller"
  (fn []
    (let [g (graph {:nodes {"a" (assoc (node "a" "svc") :name "Hello")
                            "b" (node "b")}})
          elk (to-elk g measure)
          a (first (filterv (fn [c] (= (:id c) "n:a")) (:children elk)))
          b (first (filterv (fn [c] (= (:id c) "n:b")) (:children elk)))]
      (assert/ok (>= (:width a) (measure "Hello" nil)))
      (assert/equal (:height a) 44)
      (assert/equal (:height b) 30))))

(test "boxes nest components; contained elements not repeated at root"
  (fn []
    (let [boxes [{:id "b:outer" :name "outer" :type "" :components ["b:inner" "n:a"] :attrs {}}
                 {:id "b:inner" :name "inner" :type "" :components ["n:b"] :attrs {}}]
          g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :boxes boxes
                    :parent-of {"b:inner" "outer" "n:a" "outer" "n:b" "inner"}})
          elk (to-elk g measure)]
      (assert/deepEqual (mapv (fn [c] (:id c)) (:children elk)) ["b:outer"])
      (let [outer (nth (:children elk) 0)
            inner (first (filterv (fn [c] (= (:id c) "b:inner")) (:children outer)))]
        (assert/deepEqual (sort (mapv (fn [c] (:id c)) (:children outer))) ["b:inner" "n:a"])
        (assert/deepEqual (mapv (fn [c] (:id c)) (:children inner)) ["n:b"])
        (assert/ok (.includes (get (:layoutOptions outer) "elk.padding") "top=40"))))))

(test "edges use prefixed ids and live at the root"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "" :type "" :attrs {}}]})
          elk (to-elk g measure)]
      (assert/deepEqual (:edges elk)
                        [{:id "e0" :sources ["n:a"] :targets ["n:b"]}]))))

(test "root layout options select hierarchical layered layout"
  (fn []
    (let [elk (to-elk (graph {}) measure)]
      (assert/equal (get (:layoutOptions elk) "elk.algorithm") "layered")
      (assert/equal (get (:layoutOptions elk) "elk.direction") "RIGHT")
      (assert/equal (get (:layoutOptions elk) "elk.hierarchyHandling") "INCLUDE_CHILDREN"))))
```

Replace `test/simpleviz/layout_test.cljs` with the same fixture approach:

```clojure
(ns simpleviz.layout-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.transform :refer [to-elk]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(defn node [id type] {:id id :name id :type type :attrs {}})

(defn graph [g]
  {:nodes (or (:nodes g) {})
   :edges (or (:edges g) [])
   :boxes (or (:boxes g) [])
   :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (or (:boxes g) []))
   :parent-of (or (:parent-of g) {})
   :warnings []})

(defn edge [i a b arrows]
  {:id (str "e" i) :source a :target b :arrows arrows :name "" :type "" :attrs {}})

(defn measure [text _font] (* (.-length text) 7))

(test "ELK lays out a nested boxed graph end to end"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "svc") "b" (node "b" "db") "c" (node "c" "")}
                    :edges [(edge 0 "a" "b" {:source false :target true})
                            (edge 1 "a" "c" {:source false :target true})
                            (edge 2 "b" "c" {:source true :target true})
                            (edge 3 "a" "c" {:source false :target false})]
                    :boxes [{:id "b:grp" :name "grp" :type ""
                             :components ["n:a" "n:b"] :attrs {}}]
                    :parent-of {"n:a" "grp" "n:b" "grp"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (assert/ok (and (pos? (:width layout)) (pos? (:height layout))))
                   (let [grp (first (filterv (fn [c] (= (:id c) "b:grp")) (:children layout)))]
                     (assert/ok grp "box present in layout")
                     (assert/equal (.-length (:children grp)) 2)
                     (doseq [child (:children grp)]
                       (assert/ok (and (some? (:x child)) (some? (:y child))))))
                   (assert/equal (.-length (:edges layout)) 4)
                   (doseq [e (:edges layout)]
                     (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                (str "edge " (:id e) " has sections")))))))))

(test "edges wholly inside a box get container-relative section coordinates"
  (fn []
    (let [g (graph {:nodes {"a" (node "a" "") "b" (node "b" "")}
                    :edges [(edge 0 "a" "b" {:source false :target true})]
                    :boxes [{:id "b:grp" :name "grp" :type ""
                             :components ["n:a" "n:b"] :attrs {}}]
                    :parent-of {"n:a" "grp" "n:b" "grp"}})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (assert/equal (:container (nth (:edges layout) 0)) "b:grp")))))))
```

- [ ] **Step 4: README touch**

In `README.md`, find the paragraph starting `Invalid references, duplicate box memberships, or containment cycles never break rendering` and change its first sentence to:

```markdown
Validation runs server-side (via [malli](https://github.com/metosin/malli)):
invalid references, duplicate box memberships, or containment cycles never
break rendering — the element is skipped and a warning banner explains it.
```

- [ ] **Step 5: Full verification (stale-output guard!)**

```bash
rm -rf public/js          # stale compiled validate*.mjs must not mask anything
bb test                   # build + clj tests + JS tests — all green
bb serve examples/demo.edn &
sleep 2
curl -s localhost:8080/api/graph | head -c 200          # normalized graph JSON
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/ # 200
kill %1
```

- [ ] **Step 6: Commit**

```bash
git add src/simpleviz/app.cljs test/simpleviz/transform_test.cljs test/simpleviz/layout_test.cljs README.md
git commit -m "refactor: frontend consumes server-normalized graph"
```
