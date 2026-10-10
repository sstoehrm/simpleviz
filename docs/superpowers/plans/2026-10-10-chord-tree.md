# Chord Tree and Grouped Toolbar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Task graph:** `.blend/specs/2026-10-10-chord-tree-tasks.edn`. Set each task node's `:state` alongside its checkboxes: `:in-progress` when you start it, `:done` once its review is clean, `:blocked` plus a `:reason` string when stuck.

**Goal:** Chords of any length (`n n n`, `n n b`, `n n s`, `n n c`, `n n i`, `n n e`, `f p 1`–`9`), and a toolbar with a flat/grouped toggle whose groups and pending chords pop out a clickable menu.

**Architecture:** `src/simpleviz/editor.cljs` holds the chord table as `[keys {kind [action label]}]` entries and pure functions over it (`chord-action`, `chord-prefix?`, `chord-for`, `chord-menu`, `chord-leaves`), plus `creation-ops` for the new creation kinds — all unit-tested. `src/simpleviz/app.cljs` keeps the pending key path in `:chord`, renders the toolbar (flat or grouped) and the pop-out from those functions, and wires the new actions through `action-spec`. The server is unchanged: every new action is a batch of existing `/api/edit` ops.

**Tech Stack:** squint ClojureScript + reagami (page, `node:test`), babashka (`clojure.test`) for the server test.

**Spec:** `docs/superpowers/specs/2026-10-10-chord-tree-design.md`

## Global Constraints

- Build and test: `bb build` compiles `src/` and `test/simpleviz/` to `public/js/simpleviz/*.mjs`; `bb test:js` runs them; `bb test:clj` runs the server tests; `bb test` runs all three. A single JS test file: `bb build && node --test public/js/simpleviz/editor_test.mjs`.
- No server changes. New actions use only `add-node`, `add-box`, `add-edge`, `box-add`, `retarget-edge`, `set-attr`.
- `c n` is removed; `n n` is a prefix, never an action; `n b` is labelled "wrap in box".
- `f p` with ≥ 2 working pairs is a group `f p 1`…`f p 9` (action `["follow-pair" index]`, index 0-based, label `file#id`); pairs past 9 only from the inspector.
- Toolbar layout key in localStorage: `simpleviz-toolbar`, values `"flat"` (default) / `"grouped"`; storage failures fall back to flat.
- Edge direction stays an inline row in both layouts.
- `:chord` is nil when closed, else a vector of keys.
- Every commit message ends with the trailer `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` (a second `-m` on each `git commit` below).
- squint: keywords are strings at runtime, maps are JS objects (so map keys must be strings — join key paths with `" "` for lookups), vectors are arrays, `=` is deep equality. `undefined` ≠ `nil` under `=`; normalize with `(or x nil)` before comparing possibly-missing values. Stick to idioms already in `src/simpleviz/*.cljs`; `some->`, `true?` and `update-in` are not used there — don't introduce them.

## Review Focus

1. **A chord typed while the pop-out was opened by a click** — click `new n ▸` in the grouped toolbar, then type `n s`: the path must continue from `["n"]` (one state for both). Pinned in Task 4 step 6 (browser check).
2. **An edge between endpoints in different boxes, split with `n n e`** — X lands at top level, not in either box; an edge whose ends are in the same box puts X there. `split-parent` tests in Task 1.
3. **An undirected or unnamed edge, split** — no `:direction` on the original means the new `[X B]` has none either, and the batch still applies. `creation-ops` test (no direction) in Task 1, server batch test in Task 1.
4. **A node outside any box: `n n s` / `n n c` / `n n i`** — no `box-add` op is emitted (a `box-add` with a nil box would fail the whole batch). `creation-ops` tests with `:parent nil` in Task 1.
5. **More than 9 pairs** — `f p` keys stop at 9 and nothing breaks; the pop-out shows exactly 9. `chord-menu` test in Task 2.

---

## File Structure

| File | Change |
| --- | --- |
| `src/simpleviz/editor.cljs` | `creation-ops` new kinds, `parent-box`, `split-parent` (Task 1); chord table and functions (Task 2) |
| `test/simpleviz/editor_test.cljs` | tests for both |
| `test/edit_test.clj` | split batch against the real server ops (Task 1) |
| `src/simpleviz/app.cljs` | key handler (Task 2); actions, availability, numbered pairs (Task 3); toolbar, pop-out, toggle (Task 4); help text (Task 5) |
| `public/style.css` | pop-out, toggle, pair numbers (Task 3/4) |
| `docs/guide.md`, `plugins/simpleviz/skills/simpleviz/SKILL.md` | chord table and toolbar text (Task 5) |

---

### Task 1: Creation ops for the new kinds

**Files:**
- Modify: `src/simpleviz/editor.cljs` (`creation-ops` at ~line 129; new fns next to `top-box-of` ~line 310)
- Test: `test/simpleviz/editor_test.cljs`, `test/edit_test.clj`

**Interfaces:**
- Consumes: existing `add-node-ops`, `add-connected-ops`, `name-op`, `with-type`, `parse-entry`, `name->id` in `editor.cljs`.
- Produces:
  - `(parent-box parent-of id)` → box name directly containing node `id` (else box `id`), nil at top level. `parent-of` maps scene ids (`"n:api"`, `"b:grp"`) to box names.
  - `(split-parent parent-of [a b])` → `(parent-box parent-of a)` when both ends have the same parent, else nil.
  - `creation-ops` accepts entries `{:for kind :text t}` plus, by kind: `"box"` (nothing), `"box-inbox"` (nothing — the box is `(:id tgt)`), `"sibling"` / `"connect-here"` / `"incoming"` (`:parent` box name or nil), `"split"` (`:parent`, `:direction` string or nil; the edge is `(:id tgt)` = `[a b]` in file order). Returns `{:ops [...] :focus "n:<id>"|"b:<id>"}` or nil for an unusable name (as today).

- [ ] **Step 1: Write the failing tests** — add `parent-box split-parent` to the `:refer` list in `test/simpleviz/editor_test.cljs` and append:

```clojure
(test "parent-box and split-parent place an element next to the selection"
  (fn []
    (let [pof {"n:a" "grp" "n:b" "grp" "n:c" "other" "b:grp" "outer"}]
      (assert/equal (parent-box pof "a") "grp")
      (assert/equal (parent-box pof "grp") "outer")
      (assert/ok (nil? (parent-box pof "top")))
      (assert/equal (split-parent pof ["a" "b"]) "grp")
      ;; ends in different boxes, or one at top level: X goes to top level
      (assert/ok (nil? (split-parent pof ["a" "c"])))
      (assert/ok (nil? (split-parent pof ["a" "top"])))
      (assert/ok (nil? (split-parent pof ["top" "top2"]))))))

(test "creation-ops: boxes, siblings, here-connected and incoming nodes"
  (fn []
    (let [node {:section "nodes" :id "api"}
          box {:section "boxes" :id "grp"}
          nm (fn [section id v] {:op "set-attr" :section section :id id :attr "name" :value (str "\"" v "\"") :fallback false})]
      (assert/deepEqual (creation-ops {:for "box" :text "Zone"} nil)
                        {:ops [{:op "add-box" :id "zone"} (nm "boxes" "zone" "Zone")] :focus "b:zone"})
      (assert/deepEqual (creation-ops {:for "box-inbox" :text "Zone"} box)
                        {:ops [{:op "add-box" :id "zone"} (nm "boxes" "zone" "Zone")
                               {:op "box-add" :box "grp" :member "zone"}]
                         :focus "b:zone"})
      (assert/deepEqual (creation-ops {:for "sibling" :text "DB" :parent "grp"} node)
                        {:ops [{:op "add-node" :id "db"} (nm "nodes" "db" "DB")
                               {:op "box-add" :box "grp" :member "db"}]
                         :focus "n:db"})
      ;; at top level: no box-add (a nil box would fail the whole batch)
      (assert/deepEqual (:ops (creation-ops {:for "sibling" :text "DB" :parent nil} node))
                        [{:op "add-node" :id "db"} (nm "nodes" "db" "DB")])
      (assert/deepEqual (:ops (creation-ops {:for "connect-here" :text "DB" :parent "grp"} node))
                        [{:op "add-node" :id "db"} (nm "nodes" "db" "DB")
                         {:op "add-edge" :from "api" :to "db" :direction "->"}
                         {:op "box-add" :box "grp" :member "db"}])
      (assert/deepEqual (:ops (creation-ops {:for "incoming" :text "DB" :parent nil} node))
                        [{:op "add-node" :id "db"} (nm "nodes" "db" "DB")
                         {:op "add-edge" :from "db" :to "api" :direction "->"}])
      ;; name::type still sets the type, last
      (assert/deepEqual (last (:ops (creation-ops {:for "box" :text "Zone::infra"} nil)))
                        {:op "set-attr" :section "boxes" :id "zone" :attr "type" :value "\"infra\"" :fallback false})
      (assert/ok (nil? (creation-ops {:for "sibling" :text "((("} node))))))

(test "creation-ops: split puts the new node between the edge's ends"
  (fn []
    (let [edge {:section "edges" :id ["a" "b"]}
          nm {:op "set-attr" :section "nodes" :id "x" :attr "name" :value "\"X\"" :fallback false}]
      (assert/deepEqual (creation-ops {:for "split" :text "X" :parent "grp" :direction "->"} edge)
                        {:ops [{:op "add-node" :id "x"} nm
                               {:op "box-add" :box "grp" :member "x"}
                               {:op "retarget-edge" :edge ["a" "b"] :end "target" :to "x"}
                               {:op "add-edge" :from "x" :to "b" :direction "->"}]
                         :focus "n:x"})
      ;; no direction on the original: none on the new half; top level: no box-add
      (assert/deepEqual (:ops (creation-ops {:for "split" :text "X" :parent nil :direction nil} edge))
                        [{:op "add-node" :id "x"} nm
                         {:op "retarget-edge" :edge ["a" "b"] :end "target" :to "x"}
                         {:op "add-edge" :from "x" :to "b"}]))))
```

And in `test/edit_test.clj`, after `apply-ops-batch-is-atomic`:

```clojure
(deftest split-edge-batch-keeps-attrs-on-the-first-half
  ;; the batch creation-ops builds for n n e on [:a :b] (tri-file: :-> named "x")
  (let [{:keys [text error]} (edit/apply-ops tri-file
                                             [{:op "add-node" :id "m"}
                                              {:op "retarget-edge" :edge ["a" "b"] :end "target" :to "m"}
                                              {:op "add-edge" :from "m" :to "b" :direction "->"}])
        edges (:edges (clojure.edn/read-string text))]
    (is (nil? error))
    (is (= {:direction :-> :name "x"} (get edges [:a :m])))
    (is (= {:direction :->} (get edges [:m :b])))
    (is (not (contains? edges [:a :b]))))
  (let [{:keys [text error]} (edit/apply-ops "{:nodes {:a nil :b nil}\n :edges {[:a :b] nil}}"
                                             [{:op "add-node" :id "m"}
                                              {:op "retarget-edge" :edge ["a" "b"] :end "target" :to "m"}
                                              {:op "add-edge" :from "m" :to "b"}])
        edges (:edges (clojure.edn/read-string text))]
    (is (nil? error))
    (is (contains? edges [:a :m]))
    (is (nil? (get edges [:m :b])))))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build && node --test public/js/simpleviz/editor_test.mjs 2>&1 | tail -20`
Expected: FAIL — `parent-box` is not exported (squint compile error or `undefined is not a function`).

Run: `bb test:clj 2>&1 | grep -A5 split-edge`
Expected: PASS already (the server ops exist) — this test guards the composition; if it fails, stop and report: the spec's "no server change" assumption is wrong.

- [ ] **Step 3: Implement** — in `src/simpleviz/editor.cljs`, before `creation-ops` (it must see them), add:

```clojure
(defn- into-box
  "ops with the new element `id` put into box `box` appended — nothing
  at top level (box nil), where a box-add would fail the batch."
  [ops box id]
  (if (some? box) (conj (vec ops) {:op "box-add" :box box :member id}) ops))

(defn- add-box-ops [id nm]
  [{:op "add-box" :id id} (name-op "boxes" id nm)])

(defn- split-ops
  "Ops putting new node `id` between the ends of edge [a b] (file
  order): the edge keeps its attrs and becomes [a id]; [id b] gets
  only the direction."
  [[a b] id nm parent direction]
  (-> (add-node-ops id nm)
      (into-box parent id)
      (conj {:op "retarget-edge" :edge [a b] :end "target" :to id})
      (conj (cond-> {:op "add-edge" :from id :to b}
              (some? direction) (assoc :direction direction)))))
```

`add-node-ops` is defined above `creation-ops` already; `name-op` too. Then extend the `case` in `creation-ops`:

```clojure
        (case (:for entry)
          "connect" {:ops (with-type (add-connected-ops from id nm) "nodes" id tp) :focus (str "n:" id)}
          "newbox" {:ops (with-type (wrap-in-box-ops from id nm) "boxes" id tp) :focus (str "b:" id)}
          "inbox" {:ops (with-type (add-node-in-box-ops from id nm) "nodes" id tp) :focus (str "n:" id)}
          "node" {:ops (with-type (add-node-ops id nm) "nodes" id tp) :focus (str "n:" id)}
          "box" {:ops (with-type (add-box-ops id nm) "boxes" id tp) :focus (str "b:" id)}
          "box-inbox" {:ops (with-type (into-box (add-box-ops id nm) from id) "boxes" id tp) :focus (str "b:" id)}
          "sibling" {:ops (with-type (into-box (add-node-ops id nm) (:parent entry) id) "nodes" id tp)
                     :focus (str "n:" id)}
          "connect-here" {:ops (with-type (into-box (add-connected-ops from id nm) (:parent entry) id) "nodes" id tp)
                          :focus (str "n:" id)}
          "incoming" {:ops (with-type (into-box (conj (vec (add-node-ops id nm))
                                                      {:op "add-edge" :from id :to from :direction "->"})
                                                (:parent entry) id)
                                      "nodes" id tp)
                      :focus (str "n:" id)}
          "split" {:ops (with-type (split-ops from id nm (:parent entry) (:direction entry)) "nodes" id tp)
                   :focus (str "n:" id)}
          nil)
```

(`from` is `(:id tgt)`: the selected node/box id, or `[a b]` for an edge.) Extend the docstring of `creation-ops` with one line: "`:parent` (a box name or nil) places sibling/connect-here/incoming/split; a split also carries the edge's `:direction`."

Next to `top-box-of` add:

```clojure
(defn parent-box
  "The box directly containing element `id` — the node of that name,
  else the box — nil at top level. `parent-of` maps scene ids to box
  names."
  [parent-of id]
  (or (get parent-of (str "n:" id)) (get parent-of (str "b:" id)) nil))

(defn split-parent
  "Where a node splitting edge [a b] goes: the ends' box when both sit
  directly in the same one, else top level (nil)."
  [parent-of [a b]]
  (let [pa (parent-box parent-of a)]
    (when (= pa (parent-box parent-of b)) pa)))
```

- [ ] **Step 4: Run the tests**

Run: `bb build && node --test public/js/simpleviz/editor_test.mjs 2>&1 | grep -E "^# (pass|fail)"` and `bb test:clj 2>&1 | tail -3`
Expected: `# fail 0`, and `0 failures, 0 errors`.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/editor.cljs test/simpleviz/editor_test.cljs test/edit_test.clj
git commit -m "feat(editor): creation ops for new boxes, siblings, incoming nodes and edge splits" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The chord tree and the key handler

**Files:**
- Modify: `src/simpleviz/editor.cljs:455-519` (the `;; ---- keyboard chords ----` section)
- Modify: `src/simpleviz/app.cljs` — `handle-chord-key!` (~line 1667), `hint-view` chord branch (~line 846), `working-pairs` must be visible from the key handler (it is defined ~line 1272, above it — fine)
- Test: `test/simpleviz/editor_test.cljs`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces (all in `simpleviz.editor`; `kind` is `"node"|"edge"|"box"` or nil for no selection; `path` a vector of one-char strings; `pairs` the selection's working pairs, a vector of `{:file :id ...}`, `[]` or nil when none):
  - `(chord-action kind path pairs)` → action (string or vector) when `path` is exactly a chord, else nil.
  - `(chord-prefix? kind path pairs)` → true when some chord is longer than `path` and starts with it.
  - `(chord-for kind action)` → `"n n b"` or nil.
  - `(chord-menu kind path available? pairs)` → vector of items `{:keys [k ...] :label s :action a}` or `{:keys [k] :label s :group true}`; `:keys` are the keys still to type after `path`.
  - `(chord-leaves kind available? pairs)` → vector of items `{:keys full-keys :label s :action a}` in table order.
  - Action names (app.cljs dispatches on them in Task 3): `"new-node"`, `"new-connected-node"`, `"new-node-in-box"`, `"new-free-box"`, `"new-box-in-box"`, `"new-sibling"`, `"new-connected-here"`, `"new-incoming"`, `"split-edge"`, `"new-box"` (wrap), `["follow-pair" i]`, plus all existing ones.
  - Removed: `chord-group?`, `chord-hint`.

- [ ] **Step 1: Replace the chord tests** — in `test/simpleviz/editor_test.cljs`: in the `:refer` list replace `chord-action chord-group? chord-for chord-hint` with `chord-action chord-prefix? chord-for chord-menu chord-leaves`. Delete these tests (they test the two-key API): "chord-action resolves a two-key chord for the selection kind", "chord-group? knows the first keys", "chord-for finds the chord behind an action, for the toolbar hints", "chord-hint lists the completions of a pending group for the selection", "chords for box membership: remove node, new node in box, remove from box", "n n on a box is new node inside it, as c n is (#105)", "chord f r follows a ref for every selection kind", "f p follows a node's or a box's pair". Use `grep -n "chord" test/simpleviz/editor_test.cljs` to find every remaining use and make sure none of the removed names is left. Add:

```clojure
(def ^:private all (fn [_] true))
(def ^:private two-pairs [{:file "a.edn" :id "x"} {:file "b.edn" :id "y"}])

(test "chord-action resolves complete chords of any length"
  (fn []
    (assert/equal (chord-action "node" ["d" "d"] []) "delete")
    (assert/deepEqual (chord-action "edge" ["e" "3"] []) ["direction" "<->"])
    (assert/deepEqual (chord-action "edge" ["c" "t"] []) ["retarget" "target"])
    (assert/equal (chord-action "node" ["a" "b"] []) "add-to-box")
    (assert/equal (chord-action "box" ["a" "b"] []) "add-box-member")
    (assert/equal (chord-action "box" ["a" "n"] []) "add-node-member")
    (assert/equal (chord-action nil ["n" "n" "n"] []) "new-node")
    (assert/equal (chord-action "node" ["n" "n" "n"] []) "new-connected-node")
    (assert/equal (chord-action "box" ["n" "n" "n"] []) "new-node-in-box")
    (assert/equal (chord-action nil ["n" "n" "b"] []) "new-free-box")
    (assert/equal (chord-action "box" ["n" "n" "b"] []) "new-box-in-box")
    (assert/equal (chord-action "node" ["n" "n" "s"] []) "new-sibling")
    (assert/equal (chord-action "box" ["n" "n" "c"] []) "new-connected-here")
    (assert/equal (chord-action "node" ["n" "n" "i"] []) "new-incoming")
    (assert/equal (chord-action "edge" ["n" "n" "e"] []) "split-edge")
    (assert/equal (chord-action "node" ["n" "b"] []) "new-box")
    (assert/equal (chord-action "box" ["r" "r"] []) "rename")
    (assert/equal (chord-action "box" ["r" "n"] []) "remove-node-member")
    (assert/equal (chord-action "node" ["r" "b"] []) "remove-from-box")
    (assert/equal (chord-action "edge" ["f" "r"] []) "follow-ref")
    (assert/equal (chord-action "node" ["f" "m"] []) "open-md")
    ;; n n is a group now, c n is gone, and kinds still matter
    (assert/ok (nil? (chord-action "box" ["n" "n"] [])))
    (assert/ok (nil? (chord-action "box" ["c" "n"] [])))
    (assert/ok (nil? (chord-action "node" ["n" "n" "b"] [])))
    (assert/ok (nil? (chord-action "edge" ["a" "e"] [])))
    (assert/ok (nil? (chord-action nil ["d" "d"] [])))
    (assert/ok (nil? (chord-action "node" ["z" "z"] [])))))

(test "f p follows the one pair, or numbers several"
  (fn []
    (assert/equal (chord-action "node" ["f" "p"] [{:file "a.edn" :id "x"}]) "follow-pair")
    (assert/ok (nil? (chord-action "edge" ["f" "p"] [{:file "a.edn" :id "x"}])))
    (assert/ok (nil? (chord-action "box" ["f" "p"] two-pairs)))
    (assert/ok (chord-prefix? "box" ["f" "p"] two-pairs))
    (assert/deepEqual (chord-action "box" ["f" "p" "2"] two-pairs) ["follow-pair" 1])
    (assert/ok (nil? (chord-action "box" ["f" "p" "3"] two-pairs)))))

(test "chord-prefix? knows the groups for the selection"
  (fn []
    (assert/ok (chord-prefix? nil ["n"] []))
    (assert/ok (chord-prefix? nil ["n" "n"] []))
    (assert/ok (chord-prefix? "edge" ["n"] []))
    (assert/ok (not (chord-prefix? nil ["d"] [])))
    (assert/ok (not (chord-prefix? "node" ["n" "n" "n"] [])))
    (assert/ok (not (chord-prefix? "node" ["f" "p"] [{:file "a.edn" :id "x"}])))
    (assert/ok (not (chord-prefix? "node" ["z"] [])))))

(test "chord-for finds the chord behind an action, for the button hints"
  (fn []
    (assert/equal (chord-for "node" "delete") "d d")
    (assert/equal (chord-for "edge" ["direction" "<-"]) "e 2")
    (assert/equal (chord-for nil "new-free-box") "n n b")
    (assert/equal (chord-for "box" "new-node-in-box") "n n n")
    (assert/equal (chord-for "node" "new-box") "n b")
    (assert/ok (nil? (chord-for "edge" "add-to-box")))))

(test "chord-menu lists what comes next, collapsing chains and single chords"
  (fn []
    ;; nothing selected: n → n n is a chain, so the root shows both chords
    (assert/deepEqual (chord-menu nil [] all [])
                      [{:keys ["n" "n" "n"] :label "new node" :action "new-node"}
                       {:keys ["n" "n" "b"] :label "new box" :action "new-free-box"}])
    (assert/deepEqual (chord-menu nil ["n"] all [])
                      [{:keys ["n" "n"] :label "new node" :action "new-node"}
                       {:keys ["n" "b"] :label "new box" :action "new-free-box"}])
    ;; a node: n holds a group and a chord
    (assert/deepEqual (chord-menu "node" ["n"] all [])
                      [{:keys ["n"] :label "new element" :group true}
                       {:keys ["b"] :label "wrap in box" :action "new-box"}])
    (assert/deepEqual (mapv :action (chord-menu "node" ["n" "n"] all []))
                      ["new-connected-node" "new-sibling" "new-connected-here" "new-incoming"])
    ;; root for a node, with only some actions available: a group with one
    ;; available chord shows the chord itself; empty groups vanish
    (let [avail (fn [a] (contains? #{"add-edge" "add-to-box" "rename" "delete"
                                     "new-connected-node" "new-sibling" "new-box"} a))]
      (assert/deepEqual (chord-menu "node" [] avail [])
                        [{:keys ["a"] :label "add" :group true}
                         {:keys ["n"] :label "new" :group true}
                         {:keys ["r" "r"] :label "rename" :action "rename"}
                         {:keys ["d" "d"] :label "delete" :action "delete"}]))))

(test "chord-menu numbers pairs, nine at most"
  (fn []
    (assert/deepEqual (chord-menu "box" ["f" "p"] all two-pairs)
                      [{:keys ["1"] :label "a.edn#x" :action ["follow-pair" 0]}
                       {:keys ["2"] :label "b.edn#y" :action ["follow-pair" 1]}])
    (let [many (mapv (fn [i] {:file "v.edn" :id (str "e" i)}) (range 12))]
      (assert/equal (count (chord-menu "box" ["f" "p"] all many)) 9)
      (assert/ok (nil? (chord-action "box" ["f" "p" "0"] many))))))

(test "chord-leaves lists every available chord with its full keys"
  (fn []
    (assert/deepEqual (chord-leaves nil all [])
                      [{:keys ["n" "n" "n"] :label "new node" :action "new-node"}
                       {:keys ["n" "n" "b"] :label "new box" :action "new-free-box"}])
    (assert/deepEqual (mapv :action (chord-leaves "edge" (fn [a] (not= a "follow-ref")) []))
                      [["direction" "->"] ["direction" "<-"] ["direction" "<->"] ["direction" "-"]
                       ["retarget" "source"] ["retarget" "target"] "split-edge" "delete"])))
```

- [ ] **Step 2: Run to see them fail**

Run: `bb build && node --test public/js/simpleviz/editor_test.mjs 2>&1 | tail -20`
Expected: FAIL — `chord-prefix?`/`chord-menu`/`chord-leaves` not exported, or wrong results from the old two-arg `chord-action`. (If `bb build` itself fails on `app.cljs` because it still calls `chord-group?`/`chord-hint`, that is expected until Step 3.)

- [ ] **Step 3: Implement the table and functions** — replace everything from `;; ---- keyboard chords ----` through the end of `chord-hint` in `src/simpleviz/editor.cljs` with:

```clojure
;; ---- keyboard chords ----

;; Chords of any length, in the order the toolbar and the pop-out list
;; them (d d last, so Delete ends the row). Each entry maps a selection
;; kind ("node" "edge" "box", or "none" with nothing selected) to
;; [action label]; the action is what app.cljs dispatches on, the label
;; what the buttons and the pop-out show.
(def ^:private chord-table
  [[["e" "1"] {"edge" [["direction" "->"] "→"]}]
   [["e" "2"] {"edge" [["direction" "<-"] "←"]}]
   [["e" "3"] {"edge" [["direction" "<->"] "↔"]}]
   [["e" "4"] {"edge" [["direction" "-"] "—"]}]
   [["c" "s"] {"edge" [["retarget" "source"] "change source"]}]
   [["c" "t"] {"edge" [["retarget" "target"] "change target"]}]
   [["a" "e"] {"node" ["add-edge" "add edge"] "box" ["add-edge" "add edge"]}]
   [["a" "b"] {"node" ["add-to-box" "add to box"] "box" ["add-box-member" "add box"]}]
   [["a" "n"] {"box" ["add-node-member" "add node"]}]
   [["n" "n" "n"] {"none" ["new-node" "new node"] "node" ["new-connected-node" "new connected node"]
                   "box" ["new-node-in-box" "new node inside"]}]
   [["n" "n" "b"] {"none" ["new-free-box" "new box"] "box" ["new-box-in-box" "new box inside"]}]
   [["n" "n" "s"] {"node" ["new-sibling" "new sibling node"] "box" ["new-sibling" "new sibling node"]}]
   [["n" "n" "c"] {"node" ["new-connected-here" "new connected node, same box"]
                   "box" ["new-connected-here" "new connected node, same box"]}]
   [["n" "n" "i"] {"node" ["new-incoming" "new incoming node"] "box" ["new-incoming" "new incoming node"]}]
   [["n" "n" "e"] {"edge" ["split-edge" "split edge"]}]
   [["n" "b"] {"node" ["new-box" "wrap in box"] "box" ["new-box" "wrap in box"]}]
   [["r" "r"] {"node" ["rename" "rename"] "box" ["rename" "rename"]}]
   [["r" "n"] {"box" ["remove-node-member" "remove node"]}]
   [["r" "b"] {"node" ["remove-from-box" "remove from box"]}]
   [["f" "r"] {"node" ["follow-ref" "follow ref"] "edge" ["follow-ref" "follow ref"] "box" ["follow-ref" "follow ref"]}]
   [["f" "p"] {"node" ["follow-pair" "follow pair"] "box" ["follow-pair" "follow pair"]}]
   [["f" "m"] {"node" ["open-md" "open md"] "box" ["open-md" "open md"]}]
   [["d" "d"] {"node" ["delete" "delete"] "edge" ["delete" "delete"] "box" ["delete" "delete"]}]])

;; what a group button and a group item in the pop-out are called, by
;; their keys joined with spaces
(def ^:private chord-groups
  {"n" "new" "n n" "new element" "a" "add" "r" "rename / remove"
   "c" "change" "e" "direction" "f" "follow" "f p" "follow pair" "d" "delete"})

(defn- kind-key [kind] (if (nil? kind) "none" kind))

(defn- entries
  "The chords open to a selection of `kind` whose working pairs are
  `pairs`, as [keys action label] in table order. With several pairs
  f p turns into a group: f p 1 … f p 9 follow pair n (action
  [\"follow-pair\" index], label file#id); later pairs are followed
  from the inspector."
  [kind pairs]
  (let [n-pairs (count (or pairs []))]
    (vec (mapcat (fn [[ks kinds]]
                   (when-let [[action label] (get kinds (kind-key kind))]
                     (if (and (= action "follow-pair") (> n-pairs 1))
                       (map-indexed (fn [i p] [(conj ks (str (inc i))) ["follow-pair" i] (str (:file p) "#" (:id p))])
                                    (take 9 pairs))
                       [[ks action label]])))
                 chord-table))))

(defn- under?
  "True when chord keys `ks` are longer than `path` and start with it."
  [ks path]
  (and (> (count ks) (count path)) (= path (vec (.slice ks 0 (count path))))))

(defn chord-action
  "The action for chord `path` (keys, [\"n\" \"n\" \"b\"]) with a
  selection of `kind` (nil for none) whose working pairs are `pairs`;
  nil when no chord is exactly `path`."
  [kind path pairs]
  (some (fn [[ks action _]] (when (= ks path) action)) (entries kind pairs)))

(defn chord-prefix?
  "True when `path` is the start of a longer chord for this selection —
  a group to keep typing (or clicking) in."
  [kind path pairs]
  (some? (some (fn [[ks _ _]] (when (under? ks path) true)) (entries kind pairs))))

(defn chord-for
  "The chord (\"n n b\") that triggers `action` for `kind`, for the
  button hints; nil when none does."
  [kind action]
  (some (fn [[ks kinds]]
          (when (= action (first (get kinds (kind-key kind)))) (.join ks " ")))
        chord-table))

(defn chord-menu
  "The items after the pending keys `path` for a selection of `kind`
  with working `pairs`, keeping only actions `available?` accepts:
  {:keys keys-still-to-type :label .. :action ..} for a chord,
  {:keys [k] :label .. :group true} for a group. A group with a single
  available chord under it shows as that chord; a menu whose only item
  is a group shows that group's items instead, their keys prefixed."
  [kind path available? pairs]
  (let [n (count path)
        leaves (filterv (fn [[ks action _]] (and (under? ks path) (available? action)))
                        (entries kind pairs))
        items (mapv (fn [k]
                      (let [here (filterv (fn [[ks _ _]] (= k (nth ks n))) leaves)]
                        (if (= 1 (count here))
                          (let [[ks action label] (first here)]
                            {:keys (vec (.slice ks n)) :label label :action action})
                          {:keys [k] :label (get chord-groups (.join (conj path k) " ")) :group true})))
                    (distinct (map (fn [[ks _ _]] (nth ks n)) leaves)))]
    (if (and (= 1 (count items)) (= true (:group (first items))))
      (let [k (first (:keys (first items)))]
        (mapv (fn [it] (assoc it :keys (into [k] (:keys it))))
              (chord-menu kind (conj path k) available? pairs)))
      items)))

(defn chord-leaves
  "Every available chord for a selection of `kind` with working
  `pairs`, as items with their full keys, in table order — the flat
  toolbar's buttons."
  [kind available? pairs]
  (vec (keep (fn [[ks action label]]
               (when (available? action) {:keys ks :label label :action action}))
             (entries kind pairs))))
```

- [ ] **Step 4: Point the key handler at the tree** — in `src/simpleviz/app.cljs` replace `handle-chord-key!` with:

```clojure
(defn- handle-chord-key!
  "Feed a plain key press into the chords: it extends the pending keys
  (:chord) while they are the start of a longer chord for the
  selection; a complete chord runs and closes; any other key closes.
  Bare modifier, arrow and other named keys are not chord keys."
  [e]
  (let [k (.-key e)
        st @state
        sel (:selected st)
        pairs (working-pairs sel)
        path (conj (or (:chord st) []) k)]
    (when (and (= 1 (.-length k)) (nil? (:pick st)) (some? (:scene st)))
      (cond
        (editor/chord-prefix? (:kind sel) path pairs)
        (do (.preventDefault e) (swap! state assoc :chord path))

        (some? (:chord st))
        (do (.preventDefault e)
            (swap! state assoc :chord nil)
            (when-let [action (editor/chord-action (:kind sel) path pairs)]
              (run-chord-action! sel action)))))))
```

`working-pairs` of a nil selection must be `[]`: it is `(filterv ... (or (:pairs sel) []))` — fine. In `hint-view`, replace the chord branch's body so the build stays green until Task 4 replaces it with the pop-out:

```clojure
    (some? (:chord st))
    [:div {:id "pick-hint"} (.join (:chord st) " ") " … — Esc cancels"]
```

- [ ] **Step 5: Run the tests**

Run: `bb build && bb test:js 2>&1 | grep -E "^# (pass|fail)"`
Expected: `# fail 0`.

- [ ] **Step 6: Commit**

```bash
git add src/simpleviz/editor.cljs src/simpleviz/app.cljs test/simpleviz/editor_test.cljs
git commit -m "feat(editor): chords of any length; n n becomes a group, c n goes" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Wire the new actions, availability and numbered pairs

**Files:**
- Modify: `src/simpleviz/app.cljs` — `start-id-entry!` (~line 93), `action-spec` (~line 366), `start-action!` (~line 419), `run-chord-action!` (~line 1650), the pair rows (~line 485), the `declare` (~line 99)
- Modify: `public/style.css` (pair number)

**Interfaces:**
- Consumes: Task 1 `editor/parent-box`, `editor/split-parent`, `creation-ops` kinds; Task 2 action names.
- Produces: `(available? sel tgt action)` → truthy when the action does something now (used by Task 4); `run-chord-action!` runs any action from the table, including `["follow-pair" i]`.

- [ ] **Step 1: Name prompts carry placement** — replace `start-id-entry!`:

```clojure
(defn- start-id-entry!
  "Open the name prompt for creating a `for-kind`; `extra` carries what
  creation-ops needs beyond the name (:parent, :direction)."
  [for-kind extra]
  (swap! state assoc :id-entry (merge {:for for-kind :text ""} extra)))
```

In `start-action!` destructure the whole spec and pass the extras:

```clojure
  (let [{:keys [pick hint id-entry post go go-pair md] :as spec} (action-spec sel tgt action)]
    (cond
      (some? pick) (start-pick! pick hint)
      (some? id-entry) (start-id-entry! id-entry (select-keys spec [:parent :direction]))
      ...
```

(Keep the remaining `cond` branches as they are. `grep -n "start-id-entry!" src/simpleviz/app.cljs` must show no other caller; if one exists, pass `nil` as `extra`.)

- [ ] **Step 2: `action-spec` learns the new actions** — the vector branch becomes a `case` on the first element, and the string `case` gains the new names. Replace the `(if (vector? action) ...` head of `action-spec` with:

```clojure
  (let [id (:id tgt)
        pof (:parent-of (:graph @state))
        here (when (some? sel) (get pof (:elk-id sel)))]
    (if (vector? action)
      (case (first action)
        "retarget" (let [end (second action)]
                     {:label (str "change " end)
                      :pick {:mode "retarget" :edge id :end (editor/retarget-end sel end)}
                      :hint (str "click the new " end " node or box")})
        "follow-pair" (when-let [p (get (working-pairs sel) (second action))]
                        {:label "follow pair" :go-pair p})
        nil)
      (case action
```

and add to the string `case`, next to `"new-box"`:

```clojure
        "new-free-box" {:label "new box" :id-entry "box"}
        "new-box-in-box" {:label "new box inside" :id-entry "box-inbox"}
        "new-sibling" {:label "new sibling node" :id-entry "sibling" :parent (or here nil)}
        "new-connected-here" {:label "new connected node, same box" :id-entry "connect-here" :parent (or here nil)}
        "new-incoming" {:label "new incoming node" :id-entry "incoming" :parent (or here nil)}
        "split-edge" {:label "split edge" :id-entry "split"
                      :parent (editor/split-parent pof id)
                      :direction (or (:direction (:attrs sel)) nil)}
```

(`here` uses the selection's scene id directly — `(:elk-id sel)` is `"n:api"`/`"b:grp"` — so a node and a box of the same name can't be confused. `editor/parent-box` is used by `split-parent` for edge ends, which are bare ids.)

Add `available?` right after `action-spec`:

```clojure
(defn- available?
  "Whether `action` does something for selection sel right now — what
  the toolbar and the pop-out offer, and what a chord may run."
  [sel tgt action]
  (or (= action "delete") (= action "rename")
      (and (vector? action) (= "direction" (first action)))
      (some? (action-spec sel tgt action))))
```

- [ ] **Step 3: `run-chord-action!`** — delete the `follow-pair` flash branch (the chord table now numbers several pairs), so it reads:

```clojure
(defn- run-chord-action!
  "Do what the toolbar button for `action` would do for selection sel."
  [sel action]
  (let [tgt (when (some? sel) (editor/target sel))]
    (cond
      (and (vector? action) (= "direction" (first action)))
      (post-edit! [(editor/direction-op tgt (second action))])

      (= action "delete") (delete! tgt)
      (= action "rename") (start-editing! ID-FIELD (:id tgt))
      :else (start-action! sel tgt action))))
```

Add `run-chord-action!` to the `(declare ...)` near the top of `app.cljs` (Task 4's toolbar, defined above it, calls it). Remove the now-unused `toolbar-actions` only in Task 4, together with its last user.

- [ ] **Step 4: Numbered pair rows** — in the pairs view (`(defn- ...` around line 485, the one rendering `details-pairs`), number the working pairs when there are at least two, matching `f p n`:

```clojure
  [sel]
  (let [ps (or (:pairs sel) [])
        numbered (> (count (working-pairs sel)) 1)
        num-of (fn [i] (inc (count (filter (fn [q] (nil? (:problem q))) (take i ps)))))]
    (when (pos? (.-length ps))
      (into [:div {:class "details-pairs"} [:div {:class "details-pairs-header"} "pairs"]]
            (map-indexed
             (fn [i p]
               (let [in? (= (:dir p) "in")
                     label (str (if in? "← " "→ ") (:file p) "#" (:id p)
                                (if in? " (points here)" ""))]
                 (if (some? (:problem p))
                   [:div {:class "pair-row broken" :title (:problem p)}
                    label [:div {:class "pair-problem"} (:problem p)]]
                   [:button {:class "pair-row" :type "button"
                             :on-click (fn [e] (.stopPropagation e) (follow-pair! p))}
                    (when (and numbered (<= (num-of i) 9))
                      [:kbd {:class "pair-num" :title (str "f p " (num-of i))} (str (num-of i))])
                    label])))
             ps)))))
```

(Keep the existing docstring and name. `map-indexed` returns a lazy seq; wrap it in `vec` if reagami rejects it — the old code used `mapv`.) In `public/style.css`, after `.key-hint`:

```css
/* f p n: which pair a number key follows */
.pair-num { margin-right: 6px; font-family: ui-monospace, monospace; font-size: 10px;
            padding: 0 4px; border: 1px solid var(--panel-border); border-radius: 4px;
            opacity: .75; }
```

- [ ] **Step 5: Build and run all tests**

Run: `bb build && bb test:js 2>&1 | grep -E "^# (pass|fail)"`
Expected: build without errors, `# fail 0`.

- [ ] **Step 6: Smoke-check the keys in a browser**

```bash
T=$(mktemp -d)
printf '{:nodes {:a nil :b nil :c nil}\n :edges {[:a :b] {:direction :-> :name "calls"}}\n :boxes {:grp {:components #{:a :b}}}}\n' > $T/g.edn
bb serve $T/g.edn --port 7467 &
chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 about:blank &
node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7467/","wait":3000},{"screenshot":"/tmp/ct-0.png"}]'
```

Read `/tmp/ct-0.png` to find the `grp` box header and the `a→b` edge. Click `grp`, type `n n b`, type `Inner` + Enter → `$T/g.edn` has a box `:inner` with `:components []` listed in `grp`. Click the edge, type `n n e`, `Mid` + Enter → `[:a :mid] {:direction :-> :name "calls"}`, `[:mid :b] {:direction :->}`, and `:mid` in `grp`. Check with `cat $T/g.edn` and `bb check $T/g.edn` (expect `ok`). Then `kill %1 %2; rm -rf $T`.

- [ ] **Step 7: Commit**

```bash
git add src/simpleviz/app.cljs public/style.css
git commit -m "feat(app): new-element chords, edge split and numbered pairs" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Pop-out and the flat/grouped toolbar

**Files:**
- Modify: `src/simpleviz/app.cljs` — prefs (~line 41), `state` (~line 57), `action-btn`/`action-buttons`/`action-bar`/`selection-toolbar` (~lines 432–553), `hint-view` (~line 846), the `pointerdown` listener (end of file)
- Modify: `public/style.css`

**Interfaces:**
- Consumes: Task 2 `editor/chord-menu`, `editor/chord-leaves`; Task 3 `available?`, `run-chord-action!`, `working-pairs`.
- Produces: `:toolbar-layout` in `state` (`"flat"`/`"grouped"`); `#chord-popout` element; `.toolbar-layout` toggle button.

- [ ] **Step 1: The stored layout** — after `stored-layout` add:

```clojure
(def ^:private toolbar-key
  "localStorage key of your toolbar layout."
  "simpleviz-toolbar")

(defn- stored-toolbar-layout
  "\"grouped\" when you picked it in this browser, else \"flat\" (also
  when storage is unavailable)."
  []
  (try (if (= "grouped" (js/localStorage.getItem toolbar-key)) "grouped" "flat")
       (catch :default _ "flat")))

(defn- store-toolbar-layout! [v]
  (try (js/localStorage.setItem toolbar-key v) (catch :default _ nil)))
```

and in the `state` atom, next to `:layout-pref`:

```clojure
                  ;; your toolbar: every tool in a row, or grouped by first key
                  :toolbar-layout (stored-toolbar-layout)
```

- [ ] **Step 2: Items, buttons, pop-out** — replace `action-btn`, `action-buttons` and `toolbar-actions` (delete it) with:

```clojure
(defn- avail-fn
  "available? for selection sel, as the one-argument predicate the
  chord menus take."
  [sel]
  (let [tgt (when (some? sel) (editor/target sel))]
    (fn [action] (available? sel tgt action))))

(defn- toolbar-items
  "The toolbar's buttons as chord items: every available chord (flat)
  or one per first key (grouped). Edge direction has its own row, and
  several pairs are followed by number, so flat leaves both out."
  [st]
  (let [sel (:selected st)
        kind (:kind sel)
        pairs (working-pairs sel)]
    (if (= "grouped" (:toolbar-layout st))
      (filterv (fn [it] (not (and (= kind "edge") (= "e" (first (:keys it))))))
               (editor/chord-menu kind [] (avail-fn sel) pairs))
      (filterv (fn [it] (let [a (:action it)]
                          (not (and (vector? a) (contains? #{"direction" "follow-pair"} (first a))))))
               (editor/chord-leaves kind (avail-fn sel) pairs)))))

(defn- choose-item!
  "Act on a toolbar or pop-out item reached from the pending keys
  `path` (nil from the toolbar): run its chord, or open its group —
  closing it when it is already the open one."
  [path item]
  (let [ks (into (or path []) (:keys item))]
    (cond
      (some? (:action item)) (do (swap! state assoc :chord nil)
                                 (run-chord-action! (:selected @state) (:action item)))
      (= ks (:chord @state)) (swap! state assoc :chord nil)
      :else (swap! state assoc :chord ks))))

(defn- item-btn
  "A toolbar or pop-out button for chord item `it` below `path`."
  [path it]
  [:button {:class (if (= "delete" (:action it)) "action-delete" "action-pick") :type "button"
            :on-click (fn [e] (.stopPropagation e) (choose-item! path it))}
   (:label it) (key-hint (.join (:keys it) " ")) (when (= true (:group it)) " ▸")])

(defn- chord-popout
  "What the pending keys can become, as buttons — the keyboard and the
  mouse share it."
  [st]
  (let [sel (:selected st)
        path (:chord st)]
    [:div {:id "chord-popout"}
     [:div {:class "chord-path"} (.join path " ") " … — Esc cancels"]
     (into [:div {:class "chord-items"}]
           (mapv (fn [it] (item-btn path it))
                 (editor/chord-menu (:kind sel) path (avail-fn sel) (working-pairs sel))))]))

(defn- layout-toggle
  "Switches the toolbar between flat and grouped; saved in this browser."
  [st]
  (let [grouped (= "grouped" (:toolbar-layout st))]
    [:button {:class "toolbar-layout" :type "button"
              :title (if grouped
                       "Grouped by first key — click for every tool in a row"
                       "Every tool in a row — click to group them by first key")
              :on-click (fn [e] (.stopPropagation e)
                          (let [v (if grouped "flat" "grouped")]
                            (store-toolbar-layout! v)
                            (swap! state assoc :toolbar-layout v :chord nil)))}
     (if grouped "grouped" "flat")]))
```

`key-hint`, `direction-btn` and `direction-choices` stay as they are.

- [ ] **Step 3: The bar** — replace `action-bar` and `selection-toolbar` with:

```clojure
(defn- action-bar [st sel tgt]
  (into [:div {:class "details-actions"}]
        (concat
         (when (= (:kind sel) "edge")
           (let [current (or (:direction (:attrs sel)) "-")]
             [(into [:div {:class "dir-group"}]
                    (mapv (fn [[label dir]] (direction-btn tgt current label dir))
                          direction-choices))]))
         (mapv (fn [it] (item-btn nil it)) (toolbar-items st))
         [(layout-toggle st)]
         (when-let [entry (:id-entry st)] [(id-entry-row tgt entry)]))))

(defn- selection-toolbar
  "Floating bottom-center toolbar: the selection's edit tools (or, with
  nothing selected, what can be created), the layout toggle, and above
  them the pop-out while keys are pending. The inspector panel itself
  stays read/data-only."
  [st]
  (let [sel (:selected st)]
    [:div {:id "selection-toolbar"}
     (when (some? (:chord st)) (chord-popout st))
     (action-bar st sel (when (some? sel) (editor/target sel)))]))
```

`grep -n "action-bar\|action-btn\|action-buttons\|toolbar-actions" src/simpleviz/app.cljs` must show only these definitions and the call in `selection-toolbar`. In `hint-view`, delete the `(some? (:chord st))` branch entirely (the pop-out replaces it).

- [ ] **Step 4: Close on a press outside** — extend the existing capture-phase `pointerdown` listener at the end of `app.cljs`:

```clojure
(js/document.addEventListener "pointerdown"
  (fn [e]
    (when (and (:export-menu @state)
               (not (.closest (.-target e) "#export-menu, #export-btn")))
      (swap! state assoc :export-menu false))
    (when (and (some? (:chord @state))
               (not (.closest (.-target e) "#selection-toolbar")))
      (swap! state assoc :chord nil)))
  true)
```

(Update the comment above it: "a press outside the export menu closes it, one outside the toolbar closes the pop-out".)

- [ ] **Step 5: Styles** — in `public/style.css`, after the `.action-delete:hover` rule:

```css
/* pending chord: what the keys typed (or clicked) so far can become */
#chord-popout { position: absolute; bottom: calc(100% + 8px); left: 50%;
                transform: translateX(-50%); min-width: 200px; max-width: 80vw;
                background: var(--panel); border: 1px solid var(--panel-border);
                border-radius: 10px; padding: 6px; box-shadow: 0 2px 8px var(--shadow); }
#chord-popout .chord-path { font-size: 11px; color: var(--text-muted); padding: 2px 6px 6px;
                            font-family: ui-monospace, monospace; }
#chord-popout .chord-items { display: flex; flex-direction: column; gap: 4px; }
#chord-popout .action-pick, #chord-popout .action-delete { margin: 0; text-align: left;
                                                           display: flex; justify-content: space-between; }
.toolbar-layout { margin-left: 8px; border: none; background: none; color: var(--text-muted);
                  font-size: 10.5px; cursor: pointer; padding: 3px 6px; border-radius: 6px; }
.toolbar-layout:hover { color: var(--accent); background: var(--hover-plain); }
```

- [ ] **Step 6: Build, test, and check both layouts in a browser**

Run: `bb build && bb test:js 2>&1 | grep -E "^# (pass|fail)"` → `# fail 0`.

Then with the Task 3 fixture (recreate it), server on 7467 and headless Chromium on 9222 as in Task 3 step 6:
1. Nothing selected, flat: the toolbar shows `new node n n n`, `new box n n b`, `flat`. Type `n` → `#chord-popout` lists `new node n n` and `new box n b`; Esc closes it.
2. Click `flat` → it reads `grouped`; reload the page → still `grouped` (localStorage).
3. Select node `c` (top level), grouped: buttons `add a ▸`, `new n ▸`, `rename r r`, `delete d d`, `grouped` (no `remove from box`, no follow). Click `new n ▸` → pop-out shows `new element n ▸` and `wrap in box b`. Now type `n` → pop-out lists the four `n n …` chords (the click and the key share the path — Review Focus 1). Type `s`, `Sib` + Enter → `:sib` exists at top level, no box-add.
4. Click `new n ▸` twice → the pop-out opens then closes. Open it, click the canvas background → closed.
5. Select edge `a→mid` (or any edge), grouped: the direction row is inline, no `direction e ▸` button.
6. Pairs: add `:pair ["o.edn#x" "o.edn#y"]` to node `c` and create `$T/o.edn` with `{:nodes {:x nil :y nil}}`; select `c` → inspector pair rows show `1`, `2`; `f p` opens a pop-out listing `o.edn#x 1`, `o.edn#y 2`; `2` navigates to `o.edn` with `y` selected.
7. Screenshot light and dark (theme menu), look at them: pop-out centred above the toolbar, readable, not covering the toolbar.

`kill %1 %2; rm -rf $T`.

- [ ] **Step 7: Commit**

```bash
git add src/simpleviz/app.cljs public/style.css
git commit -m "feat(app): grouped toolbar with pop-outs, flat/grouped toggle" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Docs and help text

**Files:**
- Modify: `docs/guide.md` (Toolbar paragraph + chord table, ~lines 143–166)
- Modify: `src/simpleviz/app.cljs` (help "Keys" and "Edit" sections, ~lines 820–827)
- Modify: `plugins/simpleviz/skills/simpleviz/SKILL.md` (Editing section)

**Interfaces:**
- Consumes: the final chord table (Task 2) and toolbar behavior (Task 4).

- [ ] **Step 1: Guide** — replace the Toolbar paragraph's last two sentences ("Every tool has a two-key chord … Esc cancels a pending chord or pick.") with:

```markdown
Every tool has a chord, shown on its button. Chords work while no text
field has focus; typing the first key opens a pop-out above the toolbar
listing what comes next, and its entries can be clicked too. Esc cancels
a pending chord or pick. The toggle at the toolbar's right end switches
between **flat** (every tool in a row) and **grouped** (one button per
first key — `new`, `add`, `rename / remove`, `follow` — whose options
pop out); the choice is saved in this browser.
```

Replace the chord table with:

```markdown
| Chord | Selection | Action |
| --- | --- | --- |
| `d d` | any | delete; a node or box also loses its edges and its box membership |
| `e 1` `e 2` `e 3` `e 4` | edge | direction → ← ↔ — |
| `c s` / `c t` | edge | change source / target (click the new endpoint) |
| `a e` | node, box | add edge (click the other endpoint, then name it or leave it empty) |
| `a b` | node / box | add to a box / add a box as member (click it); it leaves the box it was in |
| `a n` | box | add a node as member (click it); it leaves the box it was in |
| `n n n` | none / node / box | new node / new node connected to the selection / new node inside the box |
| `n n b` | none / box | new empty box / new empty box inside the box |
| `n n s` | node, box | new node next to the selection (same box), unconnected |
| `n n c` | node, box | new node connected from the selection, in the same box |
| `n n i` | node, box | new node connected to the selection (new → selected), in the same box |
| `n n e` | edge | split the edge: A→B becomes A→X→B; A→X keeps the edge's attributes, X→B gets its direction |
| `n b` | node, box | wrap the selection in a new box, in the selection's place |
| `r r` | node, box | rename the id |
| `r n` | box | remove node (click a member; it moves to the enclosing box or out) |
| `r b` | node | remove from box (it moves to the enclosing box or out) |
| `f r` | node, edge, box | follow the `:ref` |
| `f p` | node, box | follow the pair; with several, `f p 1`…`f p 9` (numbered in the inspector) |
| `f m` | node, box | open the `:md-ref` doc in the text panel |
| `?` | any | toggle the help panel |
```

Also check the guide for other mentions: `grep -n "n n\|c n\|two-key\|several pairs" docs/guide.md README.md` and fix each to match.

- [ ] **Step 2: Help panel** — in `app.cljs`, the "Keys" section string becomes:

```clojure
      "Chords act on the selection, when no text field has focus (the toolbar buttons show them); the first key opens a pop-out of what comes next, which can be clicked too: d d delete · e 1/2/3/4 edge direction → ← ↔ — · c s / c t change an edge's source / target · a e add edge · a b add to box (node) or add a box as member (box) · a n add a node as member (box) — either moves it out of the box it was in · n n n new node (connected to the selected node, or inside the selected box) · n n b new box (inside the selected box) · n n s new node in the selection's box · n n c / n n i new node in the selection's box, connected from / to it · n n e split the selected edge with a new node · n b wrap the selection in a new box · r r rename the id · r n take a node out of the selected box · r b take the selected node out of its box · f r follow the selection's :ref · f p follow the selection's pair (f p 1–9 with several) · f m open the selection's :md-ref doc (Ctrl+S saves it). Esc cancels a pending chord; ? toggles this help; Ctrl+Z undoes."
```

In the "Edit" section's first string, after "the floating toolbar at the bottom holds the tools for the current selection", insert: " — every tool in a row, or grouped by first key with options that pop out (the toggle at its right end switches; saved in this browser)". In the "Navigate" pair sentence, change `Click one, or use "follow pair" (f p), to open that graph` to `Click one, or use "follow pair" (f p, or f p 1–9 with several — the inspector numbers them), to open that graph`.

- [ ] **Step 3: Skill** — in `plugins/simpleviz/skills/simpleviz/SKILL.md`, Editing section: replace `nodes: "add edge" (pick the endpoint, then name the edge — empty leaves it unnamed), "add to box", "new node" (new connected node via name prompt), "new box" (name prompt); boxes: "add edge", "add node"/"add box" (pick a member), "new node" (inside the box, name prompt), "new box".` with:

```markdown
nodes: "add edge" (pick the endpoint, then name the edge — empty leaves it unnamed), "add to box", "new connected node" (`n n n`), "new sibling node" (`n n s`), "new connected node, same box" (`n n c`), "new incoming node" (`n n i`), "wrap in box" (`n b`); boxes: the same plus "add node"/"add box" (pick a member), "new node inside" (`n n n`), "new box inside" (`n n b`); edges: "split edge" (`n n e`, A→X→B). Chords are shown on the buttons; typing a first key pops out what comes next, clickable. A toggle at the toolbar's right end switches between every tool in a row and one button per first key with pop-outs (saved per browser).
```

and in the `:pair` rule, after `"follow pair" (`f p`)` add `— `f p 1`…`f p 9` with several, numbered in the inspector —`.

- [ ] **Step 4: Verify nothing stale remains**

Run: `grep -rn "c n\b\|\"n n\"\|two-key\|several pairs — pick" docs/guide.md README.md plugins/simpleviz/skills/simpleviz/SKILL.md src/simpleviz/app.cljs`
Expected: no hits that describe the old behaviour.

Run: `bb test 2>&1 | grep -E "^Ran|failures|# (pass|fail)"`
Expected: `0 failures, 0 errors` and `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add docs/guide.md src/simpleviz/app.cljs plugins/simpleviz/skills/simpleviz/SKILL.md
git commit -m "docs: chord tree, pop-outs and the toolbar toggle" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
