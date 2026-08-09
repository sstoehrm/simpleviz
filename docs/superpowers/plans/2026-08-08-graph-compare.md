# Graph Compare Mode (Diff Overlay) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bb serve old.edn new.edn` renders one merged diff view where added, removed, and modified nodes/edges/boxes are visually distinguished.

**Architecture:** The server normalizes both files (existing `graph/normalize`, untouched) and a new `diff/union` merges them into ONE union graph in the existing shape, with `:diff` status annotations per element. The frontend stays a single-graph renderer that styles by status. Spec: `docs/superpowers/specs/2026-08-08-graph-compare-design.md`.

**Tech Stack:** Babashka (Clojure) server, squint-compiled ClojureScript frontend (canvas painter, ELK layout). Tests: `bb test:clj` (clojure.test), `bb build && bb test:js` (node --test on compiled `.mjs`).

## Global Constraints

- Single-file mode (`bb serve graph.edn`) must behave byte-for-byte as today: no `:diff`/`:compare` keys in the JSON, zero diff UI. Elements with no changes carry NO `:diff` key (absence = unchanged).
- Matching is by user-written identifiers: nodes by key, boxes by name, edges by **unordered** endpoint pair. Never by positional ids.
- Layout structure follows the NEW file; removed elements keep their old parent (old parents always exist in the union since removed boxes are included).
- Statuses: `"added"` / `"removed"` / `"modified"`. Modified elements also carry `:changed {attr {:old x :new y}}`. Pseudo-attrs use STRING keys: `"box membership"`, `"components"`.
- Status colors (validated for contrast + CVD on this app's surfaces):
  light `added #0ca30c`, `modified #b45309`, `removed #d03b3b`;
  dark `added #22c55e`, `modified #fab219`, `removed #f87171`.
  Every status also carries a non-color channel: removed = dashed + ghosted; glyphs `+` / `~` / `−` on elements, edge labels, and the legend.
- squint output is plain JS: maps are objects, `(:k m)` on a missing key is `undefined`, `filterv`/`mapv` produce JS arrays. Follow existing idioms in each file (mutable JS accumulators in scene/prune hot paths).
- Clojure sources: `server/` on the bb classpath, namespaces are single-segment (`graph`, `serve`; new file → `diff`).
- Commit after every task. Frequent small commits, message style `feat:`/`test:`/`docs:` as in git history.

---

### Task 1: `server/diff.clj` — node diffing + union skeleton

**Files:**
- Create: `server/diff.clj`
- Create: `test/diff_test.clj`
- Modify: `bb.edn` (test:clj task requires)

**Interfaces:**
- Consumes: `graph/normalize` output: `{:nodes {id {:id :name :type :attrs}} :edges [..] :boxes [..] :parent-of {"n:a" "boxname" ..} :warnings [..]}`.
- Produces: `(diff/union old-g new-g old-name new-name)` → union graph map with keys `:nodes :edges :boxes :parent-of :warnings :compare`. After this task, `:nodes` is fully diffed; `:edges`/`:boxes`/`:parent-of` are passthrough from `new-g` (completed in Tasks 2–3). Also `diff/changed-attrs` (private helper): `(changed-attrs old-map new-map)` → `{k {:old v :new v}}` for differing keys.

- [ ] **Step 1: Register the new test namespace in `bb.edn`**

In the `test:clj` task, change the requires and run-tests lines:

```clojure
  test:clj {:doc "Run Clojure server tests"
            :requires ([clojure.test :as t] [server-test] [graph-test] [diff-test])
            :task (let [{:keys [fail error]} (t/run-tests 'graph-test 'server-test 'diff-test)]
                    (when (pos? (+ fail error)) (System/exit 1)))}
```

- [ ] **Step 2: Write the failing tests**

Create `test/diff_test.clj`:

```clojure
(ns diff-test
  (:require [clojure.test :refer [deftest is]]
            [diff]
            [graph]))

(defn norm [raw] (graph/normalize raw))

(defn u [old new] (diff/union (norm old) (norm new) "old.edn" "new.edn"))

(deftest identical-graphs-carry-no-diff
  (let [g (u {:nodes {:a {:name "A"}}} {:nodes {:a {:name "A"}}})]
    (is (nil? (get-in g [:nodes "a" :diff])))
    (is (= {:old "old.edn" :new "new.edn"} (:compare g)))))

(deftest added-node-marked
  (let [g (u {:nodes {:a {}}} {:nodes {:a {} :b {}}})]
    (is (nil? (get-in g [:nodes "a" :diff])))
    (is (= "added" (get-in g [:nodes "b" :diff])))))

(deftest removed-node-kept-in-union
  (let [g (u {:nodes {:a {} :b {:name "B" :type "svc"}}} {:nodes {:a {}}})]
    (is (= "removed" (get-in g [:nodes "b" :diff])))
    (is (= "B" (get-in g [:nodes "b" :name])))
    (is (= "svc" (get-in g [:nodes "b" :type])))))

(deftest modified-node-lists-changed-attrs
  (let [g (u {:nodes {:a {:lang "clojure" :replicas 3}}}
             {:nodes {:a {:lang "rust" :owner "x"}}})]
    (is (= "modified" (get-in g [:nodes "a" :diff])))
    (is (= {:old "clojure" :new "rust"} (get-in g [:nodes "a" :changed :lang])))
    (is (= {:old 3 :new nil} (get-in g [:nodes "a" :changed :replicas])))
    (is (= {:old nil :new "x"} (get-in g [:nodes "a" :changed :owner])))))

(deftest node-membership-change-is-modified
  (let [g (u {:nodes {:a {}} :boxes {:x {:components #{:a}} :y {}}}
             {:nodes {:a {}} :boxes {:x {} :y {:components #{:a}}}})]
    (is (= "modified" (get-in g [:nodes "a" :diff])))
    (is (= {:old "x" :new "y"} (get-in g [:nodes "a" :changed "box membership"])))))

(deftest warnings-prefixed-with-file-name
  (let [g (u {:nodes [1 2]} {:nodes {:a {}}})]
    (is (= [":nodes must be a map, ignoring it"] (:warnings (norm {:nodes [1 2]}))))
    (is (= ["old.edn: :nodes must be a map, ignoring it"] (:warnings g)))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `bb test:clj`
Expected: FAIL — `Could not resolve symbol` / namespace `diff` not found.

- [ ] **Step 4: Implement `server/diff.clj`**

```clojure
(ns diff
  "Two normalized graphs (graph/normalize output) -> one union graph with
  per-element :diff status (\"added\"/\"removed\"/\"modified\"; absent key =
  unchanged) and :changed {attr {:old v :new v}}. Layout structure follows
  the new graph; removed elements keep their old parent, which always
  exists in the union because removed boxes are included too.")

(defn- changed-attrs
  "attr -> {:old v :new v} for keys whose values differ (missing key = nil).
  Pseudo-attrs added by callers use string keys so they can never collide
  with EDN keyword attrs."
  [old-attrs new-attrs]
  (into {}
        (keep (fn [k]
                (let [o (get old-attrs k)
                      n (get new-attrs k)]
                  (when (not= o n)
                    [k {:old o :new n}]))))
        (distinct (concat (keys old-attrs) (keys new-attrs)))))

(defn- node-parent [g id] (get (:parent-of g) (str "n:" id)))

(defn- diff-nodes [old-g new-g]
  (let [old-nodes (:nodes old-g)
        new-nodes (:nodes new-g)
        matched+added
        (reduce-kv
         (fn [acc id n]
           (assoc acc id
                  (if-let [o (get old-nodes id)]
                    (let [ch (cond-> (changed-attrs (:attrs o) (:attrs n))
                               (not= (node-parent old-g id) (node-parent new-g id))
                               (assoc "box membership"
                                      {:old (node-parent old-g id)
                                       :new (node-parent new-g id)}))]
                      (if (seq ch) (assoc n :diff "modified" :changed ch) n))
                    (assoc n :diff "added"))))
         {} new-nodes)]
    (reduce-kv
     (fn [acc id o]
       (if (contains? new-nodes id)
         acc
         (assoc acc id (assoc o :diff "removed"))))
     matched+added old-nodes)))

(defn union
  "old-g/new-g are graph/normalize outputs; old-name/new-name label the
  files in warnings and the frontend legend."
  [old-g new-g old-name new-name]
  {:nodes (diff-nodes old-g new-g)
   :edges (:edges new-g)          ; diffed in Task 3
   :boxes (:boxes new-g)          ; diffed in Task 2
   :parent-of (:parent-of new-g)  ; union built in Task 2
   :compare {:old old-name :new new-name}
   :warnings (into (mapv (fn [w] (str old-name ": " w)) (:warnings old-g))
                   (mapv (fn [w] (str new-name ": " w)) (:warnings new-g)))})
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb test:clj`
Expected: PASS (all diff-test assertions; graph-test/server-test still green).

- [ ] **Step 6: Commit**

```bash
git add server/diff.clj test/diff_test.clj bb.edn
git commit -m "feat: diff/union node diffing with :diff status and :changed attrs"
```

---

### Task 2: `diff.clj` — box diffing, union parent-of, removed-element placement

**Files:**
- Modify: `server/diff.clj`
- Modify: `test/diff_test.clj`

**Interfaces:**
- Consumes: Task 1's `diff-nodes`, `changed-attrs`, `union`.
- Produces: `union` now returns diffed `:boxes` (each box `{:id "b:name" :name :type :components :attrs}` plus optional `:diff`/`:changed`) and a union `:parent-of`. Box `:components` lists are rebuilt so removed members appear inside their old parent. Private helpers `diff-boxes`, `union-parent-of`, `with-components`.

- [ ] **Step 1: Write the failing tests** (append to `test/diff_test.clj`)

```clojure
(deftest added-and-removed-boxes
  (let [g (u {:boxes {:x {}}} {:boxes {:y {}}})
        by-name (into {} (map (juxt :name identity)) (:boxes g))]
    (is (= "added" (:diff (get by-name "y"))))
    (is (= "removed" (:diff (get by-name "x"))))))

(deftest box-attr-and-component-changes-are-modified
  (let [g (u {:nodes {:a {} :b {}} :boxes {:x {:type "zone" :components #{:a}}}}
             {:nodes {:a {} :b {}} :boxes {:x {:type "area" :components #{:a :b}}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "modified" (:diff x)))
    (is (= {:old "zone" :new "area"} (get (:changed x) :type)))
    (is (= {:old ["n:a"] :new ["n:a" "n:b"]} (get (:changed x) "components")))))

(deftest removed-node-stays-in-old-parent-box
  (let [g (u {:nodes {:a {} :gone {}} :boxes {:x {:components #{:a :gone}}}}
             {:nodes {:a {}} :boxes {:x {:components #{:a}}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "x" (get (:parent-of g) "n:gone")))
    (is (some #{"n:gone"} (:components x)))
    ;; the box changed only because a member vanished -> still "modified"
    (is (= "modified" (:diff x)))))

(deftest removed-box-keeps-its-removed-members
  (let [g (u {:nodes {:a {} :b {}} :boxes {:x {:components #{:a :b}}}}
             {:nodes {:a {}}})
        x (first (filter (fn [b] (= "x" (:name b))) (:boxes g)))]
    (is (= "removed" (:diff x)))
    ;; a survives in new at top level -> no longer inside x; b is removed -> stays
    (is (= ["n:b"] (:components x)))
    (is (nil? (get (:parent-of g) "n:a")))
    (is (= "x" (get (:parent-of g) "n:b")))))

(deftest moved-node-follows-new-structure
  (let [g (u {:nodes {:a {}} :boxes {:x {:components #{:a}} :y {}}}
             {:nodes {:a {}} :boxes {:x {} :y {:components #{:a}}}})]
    (is (= "y" (get (:parent-of g) "n:a")))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test:clj`
Expected: FAIL — boxes are new-file passthrough, no `:diff` on removed box, `n:gone` missing from `:parent-of`.

- [ ] **Step 3: Implement box diffing in `server/diff.clj`**

Add below `diff-nodes` (before `union`):

```clojure
(defn- box-parent [g nm] (get (:parent-of g) (str "b:" nm)))

(defn- diff-boxes [old-g new-g]
  (let [old-by (into {} (map (juxt :name identity)) (:boxes old-g))
        new-names (into #{} (map :name) (:boxes new-g))
        matched+added
        (mapv (fn [b]
                (if-let [o (get old-by (:name b))]
                  (let [ch (cond-> (changed-attrs (dissoc (:attrs o) :components)
                                                  (dissoc (:attrs b) :components))
                             (not= (box-parent old-g (:name b))
                                   (box-parent new-g (:name b)))
                             (assoc "box membership"
                                    {:old (box-parent old-g (:name b))
                                     :new (box-parent new-g (:name b))})
                             (not= (set (:components o)) (set (:components b)))
                             (assoc "components"
                                    {:old (vec (sort (:components o)))
                                     :new (vec (sort (:components b)))}))]
                    (if (seq ch) (assoc b :diff "modified" :changed ch) b))
                  (assoc b :diff "added")))
              (:boxes new-g))
        removed (into []
                      (comp (remove (fn [b] (contains? new-names (:name b))))
                            (map (fn [b] (assoc b :diff "removed"))))
                      (:boxes old-g))]
    (into matched+added removed)))

(defn- union-parent-of
  "New file's structure, plus old-parent entries for removed elements."
  [old-g new-g union-nodes union-boxes]
  (let [removed-ids (concat (keep (fn [[id n]]
                                    (when (= "removed" (:diff n)) (str "n:" id)))
                                  union-nodes)
                            (keep (fn [b]
                                    (when (= "removed" (:diff b)) (str "b:" (:name b))))
                                  union-boxes))]
    (merge (:parent-of new-g)
           (select-keys (:parent-of old-g) removed-ids))))

(defn- with-components
  "Rebuild each box's :components from the union parent-of: keep the box's
  own order for members that still live here, append removed members that
  point here (sorted for determinism)."
  [boxes parent-of]
  (mapv (fn [b]
          (let [kept (filterv (fn [cid] (= (:name b) (get parent-of cid)))
                              (:components b))
                kept-set (set kept)
                extra (vec (sort (for [[cid p] parent-of
                                       :when (and (= p (:name b))
                                                  (not (contains? kept-set cid)))]
                                   cid)))]
            (assoc b :components (into kept extra))))
        boxes))
```

Update `union` to use them:

```clojure
(defn union
  "old-g/new-g are graph/normalize outputs; old-name/new-name label the
  files in warnings and the frontend legend."
  [old-g new-g old-name new-name]
  (let [nodes (diff-nodes old-g new-g)
        boxes0 (diff-boxes old-g new-g)
        parent-of (union-parent-of old-g new-g nodes boxes0)]
    {:nodes nodes
     :edges (:edges new-g)          ; diffed in Task 3
     :boxes (with-components boxes0 parent-of)
     :parent-of parent-of
     :compare {:old old-name :new new-name}
     :warnings (into (mapv (fn [w] (str old-name ": " w)) (:warnings old-g))
                     (mapv (fn [w] (str new-name ": " w)) (:warnings new-g)))}))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test:clj`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/diff.clj test/diff_test.clj
git commit -m "feat: diff/union box diffing, union parent-of, removed placement"
```

---

### Task 3: `diff.clj` — edge diffing (unordered matching, renumbering)

**Files:**
- Modify: `server/diff.clj`
- Modify: `test/diff_test.clj`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `union`'s `:edges` fully diffed. Matched edges take the NEW file's orientation/attrs; all union edges are renumbered `"e0"…"eN"` (new-file order first, then removed edges in deterministic old order) so ids never collide.

- [ ] **Step 1: Write the failing tests** (append to `test/diff_test.clj`)

```clojure
(defn edges-by-endpoints [g]
  (into {} (map (fn [e] [[(:source e) (:target e)] e])) (:edges g)))

(deftest reversed-endpoints-match-as-modified
  (let [g (u {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}
             {:nodes {:a {} :b {}} :edges {[:b :a] {:direction :->}}})
        e (get (edges-by-endpoints g) ["b" "a"])]
    (is (= 1 (count (:edges g))))
    (is (= "modified" (:diff e)))
    (is (= {:old ["a" "b"] :new ["b" "a"]} (get (:changed e) :nodes)))))

(deftest direction-change-is-modified
  (let [g (u {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}
             {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :<->}}})
        e (first (:edges g))]
    (is (= "modified" (:diff e)))
    (is (= {:old :-> :new :<->} (get (:changed e) :direction)))
    ;; orientation and arrows come from the NEW file
    (is (= {:source true :target true} (:arrows e)))))

(deftest added-and-removed-edges
  (let [g (u {:nodes {:a {} :b {} :c {}} :edges {[:a :b] {}}}
             {:nodes {:a {} :b {} :c {}} :edges {[:a :c] {}}})
        by (edges-by-endpoints g)]
    (is (= "added" (:diff (get by ["a" "c"]))))
    (is (= "removed" (:diff (get by ["a" "b"]))))
    (is (= 2 (count (:edges g))))))

(deftest edge-to-removed-node-survives
  (let [g (u {:nodes {:a {} :m {}} :edges {[:a :m] {:name "send"}}}
             {:nodes {:a {}}})
        e (first (:edges g))]
    (is (= "removed" (:diff e)))
    (is (= "removed" (get-in g [:nodes "m" :diff])))
    (is (= "send" (:name e)))))

(deftest union-edge-ids-are-unique-and-sequential
  (let [g (u {:nodes {:a {} :b {} :c {}} :edges {[:a :b] {} [:b :c] {}}}
             {:nodes {:a {} :b {} :c {}} :edges {[:a :c] {} [:a :b] {}}})]
    (is (= 3 (count (:edges g))))
    (is (= (set (map :id (:edges g))) #{"e0" "e1" "e2"}))))

(deftest both-orientations-in-old-one-in-new
  ;; old wrote the pair twice (both directions); new keeps one -> the
  ;; other is removed, deterministically
  (let [g (u {:nodes {:a {} :b {}} :edges [{:nodes [:a :b]} {:nodes [:b :a]}]}
             {:nodes {:a {} :b {}} :edges {[:a :b] {}}})
        statuses (frequencies (map :diff (:edges g)))]
    (is (= 2 (count (:edges g))))
    (is (= 1 (get statuses "removed")))))

(deftest equivalent-spellings-are-not-changes
  ;; pre-v2 vector form (keyword idents, string direction) vs map form:
  ;; canonicalization keeps the edge unchanged
  (let [g (u {:nodes {:a {} :b {}} :edges [{:nodes [:a :b] :direction "->"}]}
             {:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}})]
    (is (nil? (:diff (first (:edges g)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test:clj`
Expected: FAIL — edges are new-file passthrough (counts wrong, no `:diff`).

- [ ] **Step 3: Implement edge diffing in `server/diff.clj`**

Add below `with-components`:

```clojure
(defn- edge-key [e] (vec (sort [(:source e) (:target e)])))

(def ^:private canon-dir
  {:-> :-> :<- :<- :<-> :<-> :- :- "->" :-> "<-" :<- "<->" :<-> "-" :-})

(defn- ident->str [x] (if (keyword? x) (name x) (str x)))

(defn- edge-cmp-attrs
  "Edge attrs with :nodes and :direction canonicalized, so equivalent
  spellings (keyword vs string idents, \"->\" vs :->) never read as
  changes when the two files use different edge syntaxes."
  [e]
  (cond-> (:attrs e)
    (contains? (:attrs e) :nodes)
    (update :nodes (fn [ns] (mapv ident->str ns)))
    (contains? (:attrs e) :direction)
    (update :direction (fn [d] (get canon-dir d d)))))

(defn- diff-edges
  "Match by unordered endpoint pair. Several edges may share a pair (both
  orientations written); pair them up in file order per key, leftovers
  become added/removed. Matched edges keep the NEW file's orientation and
  attrs; all union edges are renumbered sequentially."
  [old-g new-g]
  (let [old-by (group-by edge-key (:edges old-g))
        [matched+added consumed]
        (reduce
         (fn [[acc consumed] e]
           (let [k (edge-key e)
                 i (get consumed k 0)
                 o (get (get old-by k) i)]
             [(conj acc
                    (if (some? o)
                      (let [ch (changed-attrs (edge-cmp-attrs o) (edge-cmp-attrs e))]
                        (if (seq ch) (assoc e :diff "modified" :changed ch) e))
                      (assoc e :diff "added")))
              (assoc consumed k (inc i))]))
         [[] {}] (:edges new-g))
        removed (into []
                      (mapcat (fn [[k es]]
                                (map (fn [e] (assoc e :diff "removed"))
                                     (drop (get consumed k 0) es))))
                      (sort-by (fn [[k _]] (pr-str k)) old-by))]
    (vec (map-indexed (fn [i e] (assoc e :id (str "e" i)))
                      (into matched+added removed)))))
```

In `union`, replace `:edges (:edges new-g)` (and drop the "Task 3" comment) with:

```clojure
     :edges (diff-edges old-g new-g)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test:clj`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/diff.clj test/diff_test.clj
git commit -m "feat: diff/union edge matching by unordered endpoints"
```

---

### Task 4: `serve.clj` — two-file CLI, compare endpoint, combined version stamp

**Files:**
- Modify: `server/serve.clj`
- Modify: `test/server_test.clj`
- Modify: `bb.edn` (serve task doc string)

**Interfaces:**
- Consumes: `diff/union` (Task 3).
- Produces: `serve/parse-args` → `{:file f :port n}` | `{:old-file f1 :file f2 :port n}` | `{:error msg}`. `serve/compare-json [old-str new-str old-name new-name]` → JSON string of the union graph, or `{"error": "<file>: msg"}`. `serve/files` atom `{:old <path-or-nil> :new <path>}` replaces `serve/edn-file`. `/api/version` returns `{:mtime <long>}` (single) or `{:mtime "m1-m2"}` (compare).

- [ ] **Step 1: Write the failing tests** (append to `test/server_test.clj`)

```clojure
(deftest parse-args-two-files-enables-compare
  (is (= {:old-file "a.edn" :file "b.edn" :port 7373}
         (serve/parse-args ["a.edn" "b.edn"])))
  (is (= {:old-file "a.edn" :file "b.edn" :port 9000}
         (serve/parse-args ["a.edn" "b.edn" "-p" "9000"]))))

(deftest parse-args-rejects-three-files
  (is (contains? (serve/parse-args ["a.edn" "b.edn" "c.edn"]) :error)))

(deftest compare-json-diffs-two-graphs
  (let [out (json/parse-string
             (serve/compare-json "{:nodes {:a {}}}"
                                 "{:nodes {:a {} :b {}}}"
                                 "old.edn" "new.edn"))]
    (is (= "added" (get-in out ["nodes" "b" "diff"])))
    (is (nil? (get-in out ["nodes" "a" "diff"])))
    (is (= {"old" "old.edn" "new" "new.edn"} (get out "compare")))))

(deftest compare-json-parse-error-names-the-file
  (let [out (json/parse-string
             (serve/compare-json "{:unclosed" "{}" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "old.edn: ")))
  (let [out (json/parse-string
             (serve/compare-json "{}" "{:unclosed" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "new.edn: "))))

(deftest single-file-json-has-no-compare-keys
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "compare")))
    (is (not (contains? (get-in out ["nodes" "a"]) "diff")))))
```

(`clojure.string/starts-with?` is used fully qualified — `clojure.string` is already loaded transitively, no `:require` change needed.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test:clj`
Expected: FAIL — `parse-args` returns `{:file "a.edn" ...}` ignoring the second file (or errors), `compare-json` unresolved.

- [ ] **Step 3: Implement in `server/serve.clj`**

Add `[diff]` to the ns `:require`. Replace the `edn-file` atom, usage string, `parse-args`, add `compare-json`, and update `handler`/`-main`:

```clojure
(def files (atom nil)) ; {:old <path-or-nil> :new <path>}

(def ^:private usage
  (str "usage: bb serve <graph.edn> [<new.edn>] [--port N]\n"
       "  two files compare them: first = old, second = new"
       "  (default port " default-port ")"))
```

```clojure
(defn parse-args
  "CLI args -> {:file f :port n}, {:old-file f1 :file f2 :port n}, or
  {:error msg}. Graph files are positional (one = serve, two = compare
  old -> new); --port / -p overrides the default."
  [args]
  (try
    (let [{:keys [args opts]} (cli/parse-args args cli-spec)
          [f1 f2 & extra] args
          port (get opts :port default-port)]
      (cond
        (nil? f1) {:error usage}
        (seq extra) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        (some? f2) {:old-file f1 :file f2 :port port}
        :else {:file f1 :port port}))
    (catch Exception e
      {:error (str "invalid arguments: " (ex-message e) "\n" usage)})))

(defn compare-json
  "Parse and normalize two EDN strings, diff them into one union-graph
  JSON string. A parse failure returns {\"error\": \"<file>: msg\"}."
  [old-s new-s old-name new-name]
  (try
    (let [parse (fn [s nm]
                  (try (edn/read-string s)
                       (catch Exception e
                         (throw (ex-info (str nm ": " (ex-message e)) {})))))
          old-g (graph/normalize (parse old-s old-name))
          new-g (graph/normalize (parse new-s new-name))]
      (json/generate-string (diff/union old-g new-g old-name new-name)))
    (catch Exception e
      (json/generate-string {:error (ex-message e)}))))
```

```clojure
(defn handler [{:keys [uri]}]
  (case uri
    "/api/graph"   (json-response
                    (try
                      (let [{:keys [old new]} @files]
                        (if (some? old)
                          (compare-json (slurp old) (slurp new) old new)
                          (graph-json (slurp new))))
                      (catch Exception e
                        (json/generate-string {:error (ex-message e)}))))
    "/api/version" (json-response
                    (json/generate-string
                     {:mtime (let [{:keys [old new]} @files
                                   m (.lastModified (io/file new))]
                               (if (some? old)
                                 (str (.lastModified (io/file old)) "-" m)
                                 m))}))
    (static-response uri)))

(defn -main [& args]
  (let [{:keys [file old-file port error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (doseq [f (if old-file [old-file file] [file])]
      (when-not (.isFile (io/file f))
        (println (str "file not found: " f))
        (System/exit 1)))
    (reset! files {:old old-file :new file})
    (srv/run-server handler {:port port})
    (println (str "simpleviz: serving "
                  (if old-file (str old-file " → " file " (compare)") file)
                  " at http://localhost:" port))
    @(promise)))
```

File labels in warnings/legend are the paths exactly as the user typed them (unambiguous, usually short).

- [ ] **Step 4: Update `bb.edn` serve doc**

Change the serve task's `:doc` to: `"Serve an EDN graph file: bb serve graph.edn [new.edn] [--port N] (two files = compare)"`. The `dev` task needs no change (it only checks for presence of positional args).

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb test:clj`
Expected: PASS, including all pre-existing server tests (their expected `parse-args` shapes are unchanged).

- [ ] **Step 6: Commit**

```bash
git add server/serve.clj test/server_test.clj bb.edn
git commit -m "feat: two-file compare mode in server CLI and endpoints"
```

---

### Task 5: `transform.cljs` + `scene.cljs` — diff pass-through and edge-label glyphs

**Files:**
- Modify: `src/simpleviz/transform.cljs`
- Modify: `src/simpleviz/scene.cljs`
- Modify: `test/simpleviz/transform_test.cljs`
- Modify: `test/simpleviz/scene_test.cljs`

**Interfaces:**
- Consumes: graph JSON now optionally carrying `:diff`/`:changed` on nodes/edges/boxes, `:diff-inside` on boxes (Task 6 sets it client-side).
- Produces: scene items carry `:diff`, `:changed` (nodes/boxes/edges), `:diff-inside` (boxes), and edge-label items carry the parent edge's `:diff`. ELK edge labels are prefixed with the status glyph (`+` / `~` / `−`); a diff edge with no name/type still gets a glyph-only label.

- [ ] **Step 1: Write the failing tests**

Append to `test/simpleviz/transform_test.cljs`:

```clojure
(test "diff edges get a glyph-prefixed label"
  (fn []
    (let [g (graph {:nodes {"a" (node "a") "b" (node "b")}
                    :edges [{:id "e0" :source "a" :target "b"
                             :arrows {:source false :target true}
                             :name "calls" :type "http" :diff "added" :attrs {}}
                            {:id "e1" :source "b" :target "a"
                             :arrows {:source false :target true}
                             :name "" :type "" :diff "removed" :attrs {}}]})
          elk (to-elk g measure)
          labels (mapv (fn [e] (:text (first (or (:labels e) [{}])))) (:edges elk))]
      (assert/equal (nth labels 0) "+ calls (http)")
      (assert/equal (nth labels 1) "−"))))
```

Append to `test/simpleviz/scene_test.cljs` (self-contained fixtures, same style as the existing ones):

```clojure
(def diff-graph
  {:nodes {"a" (assoc (gnode "a" "svc") :diff "added")
           "b" (assoc (gnode "b" "") :diff "removed")}
   :edges [{:id "e0" :source "a" :target "b"
            :arrows {:source false :target true} :name "" :type ""
            :diff "modified" :changed {:direction {:old "->" :new "<->"}}
            :attrs {}}]
   :boxes-by-name {"grp" {:id "b:grp" :name "grp" :type "" :components ["n:a"]
                          :diff "modified" :diff-inside true :attrs {}}}})

(def diff-layout
  {:width 500 :height 300
   :children [{:id "b:grp" :x 10 :y 20 :width 200 :height 150
               :children [{:id "n:a" :x 14 :y 40 :width 60 :height 30}]}
              {:id "n:b" :x 300 :y 50 :width 60 :height 30}]
   :edges [{:id "e0" :container "root"
            :sections [{:startPoint {:x 1 :y 2} :endPoint {:x 5 :y 6}}]
            :labels [{:x 2 :y 3 :width 20 :height 14 :text "~"}]}]})

(test "diff status flows through to scene items"
  (fn []
    (let [sc (build-scene {:layout diff-layout :graph diff-graph :colors colors})
          by-id (reduce (fn [acc it] (assoc acc (:id it) it)) {} (:items sc))]
      (assert/equal (:diff (get by-id "n:a")) "added")
      (assert/equal (:diff (get by-id "n:b")) "removed")
      (assert/equal (:diff (get by-id "b:grp")) "modified")
      (assert/ok (:diff-inside (get by-id "b:grp")))
      (assert/equal (:diff (get by-id "e0")) "modified")
      (assert/ok (some? (:changed (get by-id "e0"))))
      (assert/equal (:diff (get by-id "e0-label")) "modified"))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb build && node --test public/js/simpleviz/transform_test.mjs public/js/simpleviz/scene_test.mjs`
Expected: FAIL — labels lack glyphs, items lack `:diff`.

- [ ] **Step 3: Implement**

`src/simpleviz/transform.cljs` — add near the top:

```clojure
(def ^:private diff-glyphs {"added" "+" "removed" "−" "modified" "~"})
```

and in `to-elk`'s edge mapv, replace the `parts` binding with:

```clojure
(let [glyph (get diff-glyphs (:diff e))
      parts (filterv (fn [s] (pos? (.-length s)))
                     [(if (some? glyph) glyph "")
                      (:name e)
                      (if (pos? (.-length (:type e)))
                        (str "(" (:type e) ")")
                        "")])
      ...]  ; rest unchanged
```

`src/simpleviz/scene.cljs` — in `build-scene`:
- box item: add `:diff (:diff box) :changed (:changed box) :diff-inside (:diff-inside box)` after `:attrs (:attrs box)`.
- node item: add `:diff (:diff node) :changed (:changed node)` after `:attrs (:attrs node)`.
- edge item: add `:diff (:diff e) :changed (:changed e)` after `:attrs (:attrs e)`.
- edge-label item: add `:diff (when (some? e) (:diff e))` after `:text (:text lbl)` (`e` is in scope from the surrounding `let` but can be nil for labels of unknown edges).

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb build && bb test:js`
Expected: PASS (all JS suites — layout/hit/colors/prune untouched).

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/transform.cljs src/simpleviz/scene.cljs test/simpleviz/transform_test.cljs test/simpleviz/scene_test.cljs
git commit -m "feat: diff status through scene items and glyph edge labels"
```

---

### Task 6: `prune.cljs` — roll-up into collapsed boxes, aggregated-edge diff

**Files:**
- Modify: `src/simpleviz/prune.cljs`
- Modify: `test/simpleviz/prune_test.cljs`

**Interfaces:**
- Consumes: graph elements optionally carrying `:diff`.
- Produces: `collapse-boxes` marks each collapsed shell with `:diff-inside <boolean>` (any `:diff` on transitive member nodes/boxes or fully-interior edges); an aggregated edge whose constituents include any `:diff` gets `:diff "modified"` (mixed changes) with `:changed`/`:name`/`:attrs` replaced as before. `collapse-scene` sets `:diff-inside` on freshly collapsed shell items too (instant feedback path). New helper `contents-changed?` (private).

- [ ] **Step 1: Write the failing tests** (append to `test/simpleviz/prune_test.cljs`)

```clojure
(test "collapsed box rolls up hidden diffs as :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          g (collapse-boxes raw #{"inner"})]
      (assert/ok (:diff-inside (get (:boxes-by-name g) "inner"))))
    ;; nested: change inside inner, collapse outer
    (let [raw (assoc-in (graph) [:nodes "b" :diff] "removed")
          g (collapse-boxes raw #{"outer"})]
      (assert/ok (:diff-inside (get (:boxes-by-name g) "outer"))))
    ;; no changes -> falsy
    (let [g (collapse-boxes (graph) #{"inner"})]
      (assert/ok (not (:diff-inside (get (:boxes-by-name g) "inner")))))))

(test "fully-interior diff edge sets :diff-inside"
  (fn []
    (let [raw (update (graph) :edges
                      (fn [es] (mapv (fn [e] (if (= (:id e) "e2")
                                               (assoc e :diff "removed")
                                               e))
                                     es)))
          g (collapse-boxes raw #{"inner"})]
      (assert/ok (:diff-inside (get (:boxes-by-name g) "inner"))))))

(test "aggregated edge with a changed constituent becomes modified"
  (fn []
    (let [raw (update (graph) :edges
                      (fn [es] (conj es (assoc (edge "e4" "c" "d") :diff "added"))))
          g (collapse-boxes raw #{"inner"})
          agg (first (filterv (fn [e] (= (:id e) "e0")) (:edges g)))]
      (assert/equal (:name agg) "2 edges")
      (assert/equal (:diff agg) "modified"))
    ;; without changed constituents it stays unmarked
    (let [raw (update (graph) :edges
                      (fn [es] (conj es (edge "e4" "c" "d"))))
          g (collapse-boxes raw #{"inner"})
          agg (first (filterv (fn [e] (= (:id e) "e0")) (:edges g)))]
      (assert/ok (not (:diff agg))))))

(test "collapse-scene marks freshly collapsed shells with :diff-inside"
  (fn []
    (let [raw (assoc-in (graph) [:nodes "d" :diff] "added")
          sc {:items [{:kind "box" :id "b:inner"}
                      {:kind "box" :id "b:outer"}
                      {:kind "node" :id "n:b"}]}
          out (collapse-scene sc raw #{"inner"})
          shell (first (filterv (fn [it] (= (:id it) "b:inner")) (:items out)))]
      (assert/ok (:collapsed shell))
      (assert/ok (:diff-inside shell)))))
```

(Note the existing `graph` fixture in this file: `inner` contains `n:b`,`n:d`; `outer` contains `b:inner`,`n:a`; edge `e2` is `b→d`, wholly inside `inner`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb build && node --test public/js/simpleviz/prune_test.mjs`
Expected: FAIL — no `:diff-inside`, aggregated edge unmarked.

- [ ] **Step 3: Implement in `src/simpleviz/prune.cljs`**

Add after `mark-dead`:

```clojure
(defn- contents-changed?
  "Any :diff on box b's transitive contents: member nodes, nested boxes,
  or edges running wholly inside it?"
  [graph b]
  (let [{bs' :boxes ns' :nodes} (mark-dead graph [b])]
    (or (some? (.find (js/Array.from bs')
                      (fn [nm] (and (not= nm b)
                                    (some? (:diff (get (:boxes-by-name graph) nm)))))))
        (some? (.find (js/Array.from ns')
                      (fn [n] (some? (:diff (get (:nodes graph) n))))))
        (some? (.find (:edges graph)
                      (fn [e] (and (some? (:diff e))
                                   (.has ns' (:source e))
                                   (.has ns' (:target e)))))))))
```

In `collapse-boxes`, the collapsed-shell branch of the boxes `mapv` becomes:

```clojure
(assoc b :collapsed true :components []
       :diff-inside (contents-changed? graph (:name b)))
```

In the edge-merge loop, thread a has-diff flag: the initial `.set` gains `:agg-diff (some? (:diff e))`, the increment branch becomes

```clojure
(.set merged k (assoc m :aggregated (inc (:aggregated m))
                        :agg-diff (or (:agg-diff m) (some? (:diff e)))))
```

and the final edges `mapv` becomes:

```clojure
(mapv (fn [k]
        (let [m (.get merged k)]
          (dissoc
           (if (> (:aggregated m) 1)
             (let [m (assoc m :name (str (:aggregated m) " edges")
                            :type ""
                            :attrs {:aggregated (:aggregated m)}
                            :changed nil)]
               (if (:agg-diff m) (assoc m :diff "modified") (dissoc m :diff)))
             m)
           :agg-diff)))
      order)
```

In `collapse-scene`, the shell-marking `.map` branch becomes:

```clojure
(assoc it :collapsed true
       :diff-inside (contents-changed? graph (.slice (:id it) 2)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb build && bb test:js`
Expected: PASS, including the pre-existing prune tests (the "empty collapsed set returns the graph unchanged" identity test still passes because the empty set short-circuits before any of this).

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/prune.cljs test/simpleviz/prune_test.cljs
git commit -m "feat: roll diff status up into collapsed boxes and aggregated edges"
```

---

### Task 7: `canvas.cljs` — status painting (rings, ghosting, glyphs, roll-up dot)

**Files:**
- Modify: `src/simpleviz/canvas.cljs`

**Interfaces:**
- Consumes: scene items with `:diff` / `:diff-inside` (Tasks 5–6).
- Produces: visual encoding only; no new exports. DOM-only namespace — no unit tests (verified visually in Task 9).

- [ ] **Step 1: Add status colors to both palettes**

In the `palettes` def, append to the `"light"` map:

```clojure
:diff-added "#0ca30c" :diff-modified "#b45309" :diff-removed "#d03b3b"
```

and to the `"dark"` map:

```clojure
:diff-added "#22c55e" :diff-modified "#fab219" :diff-removed "#f87171"
```

- [ ] **Step 2: Add helpers** (after `rounded-rect`, which `draw-diff-ring` uses)

```clojure
(def ^:private diff-glyphs {"added" "+" "modified" "~" "removed" "−"})

(defn- diff-color [d]
  (case d
    "added" (:diff-added @palette)
    "modified" (:diff-modified @palette)
    "removed" (:diff-removed @palette)
    nil))

(defn- draw-diff-ring
  "Status ring + glyph just outside an element's top-left corner. Rings
  sit inside the scene bbox pad; the glyph can clip one frame early at
  the viewport edge, which is acceptable."
  [ctx item r text?]
  (let [d (:diff item)
        c (diff-color d)]
    (when (= d "removed") (.setLineDash ctx [5 4]))
    (rounded-rect ctx (- (:x item) 3) (- (:y item) 3)
                  (+ (:w item) 6) (+ (:h item) 6) r)
    (set! (.-strokeStyle ctx) c)
    (set! (.-lineWidth ctx) 2)
    (.stroke ctx)
    (.setLineDash ctx [])
    (when text?
      (set! (.-font ctx) "bold 11px system-ui, sans-serif")
      (set! (.-fillStyle ctx) c)
      (set! (.-textAlign ctx) "left")
      (.fillText ctx (get diff-glyphs d) (- (:x item) 2) (- (:y item) 6)))))
```

- [ ] **Step 3: Wire into `draw-node`**

Wrap the existing body and append the ring; removed nodes ghost:

```clojure
(defn- draw-node [ctx item sel? text?]
  (let [removed? (= (:diff item) "removed")]
    (when removed? (set! (.-globalAlpha ctx) 0.45))
    ;; ... existing draw-node body unchanged ...
    (when (some? (:diff item))
      (draw-diff-ring ctx item 8 text?))
    (when removed? (set! (.-globalAlpha ctx) 1))))
```

- [ ] **Step 4: Wire into `draw-box`**

Same pattern (`removed?` ghost around the whole body, ring radius 12 after the existing stroke/text/button code). Additionally, inside the existing hide-button `let` (which binds `bx`, `by`, `s`), after the button is drawn:

```clojure
(when (and (:collapsed item) (:diff-inside item))
  (.beginPath ctx)
  (.arc ctx (- bx 8) (+ by (/ s 2)) 3.5 0 (* 2 js/Math.PI))
  (set! (.-fillStyle ctx) (:diff-modified @palette))
  (.fill ctx))
```

- [ ] **Step 5: Wire into edges and labels**

`draw-arrowhead` gains a color parameter — change the signature to `[ctx from to color]` and use `(set! (.-fillStyle ctx) color)`; `draw-edge` becomes:

```clojure
(defn- draw-edge [ctx item sel? detail?]
  (let [d (:diff item)
        removed? (= d "removed")
        color (if sel? ACCENT (if (some? d) (diff-color d) (:edge @palette)))
        arrow-color (if (some? d) (diff-color d) (:arrow @palette))]
    (when removed?
      (set! (.-globalAlpha ctx) 0.45)
      (.setLineDash ctx [6 4]))
    (set! (.-strokeStyle ctx) color)
    (set! (.-lineWidth ctx) (if sel? 2.5 (if (some? d) 2 1.5)))
    ;; ... existing sections loop unchanged ...
    (.setLineDash ctx [])
    ;; ... existing arrowhead block, passing arrow-color as the new arg ...
    (when removed? (set! (.-globalAlpha ctx) 1))))
```

(The dash reset happens before arrowheads so they stay solid.)

`draw-edge-label`: wrap the body in the same removed-ghost pattern (`(= (:diff item) "removed")` → alpha 0.45, restore 1).

- [ ] **Step 6: Exclude the legend from pan/zoom**

In `setup-pan-zoom!`, both `closest` selector strings become
`"#details, #banner, #collapsed-panel, #theme-toggle, #diff-legend"`.

- [ ] **Step 7: Verify it compiles and nothing regressed**

Run: `bb build && bb test:js && bb test:clj`
Expected: PASS (canvas has no unit tests; visual check comes in Task 9).

- [ ] **Step 8: Commit**

```bash
git add src/simpleviz/canvas.cljs
git commit -m "feat: paint diff status rings, ghosting, glyphs, and roll-up dot"
```

---

### Task 8: `app.cljs` + `style.css` — legend and inspector changes section

**Files:**
- Modify: `src/simpleviz/app.cljs`
- Modify: `public/style.css`

**Interfaces:**
- Consumes: graph `:compare {:old :new}`, selection payloads with `:diff`/`:changed`.
- Produces: `#diff-legend` overlay (compare mode only, bottom-center); details panel status label + "changes (old → new)" section. CSS vars `--diff-added/--diff-modified/--diff-removed` in both themes.

- [ ] **Step 1: CSS**

Append to the `:root` block in `public/style.css`:

```css
  --diff-added: #0ca30c;
  --diff-modified: #b45309;
  --diff-removed: #d03b3b;
```

to the `[data-theme="dark"]` block:

```css
  --diff-added: #22c55e;
  --diff-modified: #fab219;
  --diff-removed: #f87171;
```

and at the end of the file:

```css
#diff-legend { position: fixed; bottom: 12px; left: 50%; transform: translateX(-50%);
               background: var(--panel); border: 1px solid var(--panel-border);
               border-radius: 8px; padding: 8px 12px; font-size: 12px;
               box-shadow: 0 2px 8px var(--shadow); }
#diff-legend .dl-files { font-weight: 600; color: var(--text-strong); margin-bottom: 4px; }
#diff-legend .dl-row { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
#diff-legend .dl-key { font-weight: 700; width: 14px; text-align: center; }
#diff-legend .dl-added { color: var(--diff-added); }
#diff-legend .dl-modified { color: var(--diff-modified); }
#diff-legend .dl-removed { color: var(--diff-removed); }
#details .details-changes { border-top: 1px solid var(--panel-divider);
                            margin-top: 14px; padding-top: 4px; }
#details .details-changes-header { font-size: 12px; font-weight: 600;
                                   color: var(--text-muted); margin-top: 8px; }
```

- [ ] **Step 2: Legend view in `src/simpleviz/app.cljs`**

Add after `collapsed-view`:

```clojure
(defn- legend-view [g]
  (when-let [cmp (:compare g)]
    [:div {:id "diff-legend"}
     [:div {:class "dl-files"} (str (:old cmp) " → " (:new cmp))]
     [:div {:class "dl-row"} [:span {:class "dl-key dl-added"} "+"] "added"]
     [:div {:class "dl-row"} [:span {:class "dl-key dl-modified"} "~"] "modified"]
     [:div {:class "dl-row"} [:span {:class "dl-key dl-removed"} "−"] "removed"]]))
```

and render it in `app-view` after `(collapsed-view st)`:

```clojure
   (when (some? (:graph st)) (legend-view (:graph st)))
```

- [ ] **Step 3: Inspector**

`item->payload` gains two entries:

```clojure
     :diff (:diff item)
     :changed (:changed item)
```

In `details-view`, the type line shows the status:

```clojure
   [:div {:class "details-type"}
    (str (if (pos? (.-length (:subtitle sel)))
           (str "(" (:subtitle sel) ") — ")
           "")
         (:kind sel)
         (if (some? (:diff sel)) (str " — " (:diff sel)) ""))]
```

and after the existing `[:dl ...]`, append a changes section:

```clojure
   (when (some? (:changed sel))
     [:div {:class "details-changes"}
      [:div {:class "details-changes-header"} "changes (old → new)"]
      (into [:dl]
            (mapcat (fn [[k v]]
                      [[:dt {:key (str "ct" k)} k]
                       [:dd {:key (str "cd" k)}
                        (str (fmt-val (:old v)) " → " (fmt-val (:new v)))]])
                    (js/Object.entries (:changed sel))))])
```

with the helper (near `visible-attrs`):

```clojure
(defn- fmt-val [v]
  (cond (nil? v) "—"
        (string? v) v
        :else (js/JSON.stringify v)))
```

- [ ] **Step 4: Verify build and full test suite**

Run: `bb build && bb test:js && bb test:clj`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/app.cljs public/style.css
git commit -m "feat: compare legend and inspector changes section"
```

---

### Task 9: Example, README, end-to-end verification

**Files:**
- Create: `examples/demo-next.edn`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: a runnable compare demo (`bb serve examples/demo.edn examples/demo-next.edn`), user docs.

- [ ] **Step 1: Create `examples/demo-next.edn`**

A copy of `demo.edn` with each diff category represented — added (`:metrics` node + edge), removed (`:mail` node + its edge), modified (node attr, edge attr, box components):

```clojure
{:nodes {:web     {:name "Web UI"   :type "frontend" :framework "htmx" :role [:active]}
         :api     {:name "API"      :type "service"  :lang "clojure" :replicas 3}
         :auth    {:name "Auth"     :type "service"}
         :db      {:name "Postgres" :type "database" :version "17"}
         :cache   {:name "Redis"    :type "cache"}
         :worker  {:name "Worker"   :type "service"}
         :queue   {:name "Queue"    :type "queue"}
         :metrics {:name "Metrics"  :type "service"}
         :logs    {:name "Log Sink"}}
 :edges {[:web :api]      {:direction :->  :name "REST"    :type "http" :auth "mtls"}
         [:api :db]       {:direction :->  :name "queries" :type "sql"}
         [:api :cache]    {:direction :<-> :name "session" :type "resp"}
         [:queue :api]    {:direction :<-  :name "publish" :type "amqp"}
         [:worker :queue] {:direction :->  :name "consume" :type "amqp"}
         [:api :metrics]  {:direction :->  :name "emit"    :type "http"}
         [:api :auth]     {:direction :-   :name "trust"}
         [:worker :logs]  {:direction :->  :name "write"}}
 :boxes {:backend {:type "zone" :components #{:api :auth :storage :worker :queue :metrics}
                   :owner "platform-team"}
         :storage {:type "zone" :components #{:db :cache}}}}
```

(vs. `demo.edn`: `:metrics`+`emit` added; `:mail`+`send` removed; `db :version` 16→17, `REST :auth` bearer→mtls, `:backend` components grew → all three modified.)

- [ ] **Step 2: README**

In "Getting started", after the `bb serve examples/big-5k.edn` line, add:

```
    bb serve examples/demo.edn examples/demo-next.edn   # compare two versions
```

After the "Data format" section, add:

```markdown
## Comparing two versions

Pass two files to compare architectures: `bb serve old.edn new.edn`. Both
render as ONE merged diagram — added elements get a green `+` ring, modified
ones an amber `~` ring (click for an attribute-level old → new list), and
removed ones stay visible as red, dashed, ghosted shapes. Nodes match by
key, boxes by name, edges by their endpoints (flipping the pair or changing
`:direction` counts as modified). Layout follows the new file; removed
elements keep their old place. A collapsed box hiding any change shows an
amber dot. Both files live-reload.
```

- [ ] **Step 3: Full test suite**

Run: `bb test`
Expected: PASS (clj + js).

- [ ] **Step 4: Manual verification (use the `run` skill if executing interactively)**

Run: `bb serve examples/demo.edn examples/demo-next.edn` and check at http://localhost:7373:
1. Legend bottom-center shows both file names and the three keys.
2. Metrics node: green ring + `+` glyph; `emit` edge green with `+ emit (http)` label.
3. Mailer node and `send` edge: red, dashed, ghosted, still laid out inside/near their old spot.
4. Postgres, REST edge, backend box: amber ring / stroke, `~` glyph; clicking Postgres shows `changes (old → new)` with `version: 16 → 17`.
5. Collapse `backend` (hide button): shell shows the amber dot; expand restores.
6. Toggle dark mode: statuses stay legible.
7. Edit `demo-next.edn` while serving: page live-updates.
8. `bb serve examples/demo.edn` (single file): no legend, no rings — identical to before.

- [ ] **Step 5: Commit**

```bash
git add examples/demo-next.edn README.md
git commit -m "docs: compare-mode example and README section"
```
