# Squint + Reagami Frontend Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain-JS frontend of simpleviz with idiomatic Squint ClojureScript rendering through reagami, preserving all behavior and test coverage.

**Architecture:** Squint compiles `src/` + `test/` `.cljs` files to git-ignored `public/js/` `.mjs` modules (`bb build`). The page loads them via an import map (`reagami`, `squint-cljs/` prefix → files copied from node_modules into `public/js/vendor/`). One reagami state atom drives hiccup rendering; pan/zoom stays imperative on a static wrapper element. The babashka server is untouched.

**Tech Stack:** squint-cljs ^0.14.206, reagami ^0.2.38 (both npm devDependencies), vendored ELK.js 0.9.3 (unchanged, classic script), babashka, `node --test` on compiled output.

**Spec:** `docs/superpowers/specs/2026-08-04-squint-reagami-rewrite-design.md` — read before starting.

## Global Constraints

- Frontend dev now requires node+npm; **runtime serving does not** (`bb serve` only needs the compiled `public/js/` present).
- Compiled output (`public/js/`) and `node_modules/` are git-ignored; only `.cljs` sources are committed.
- Behavior is identical to the current frontend: same validation rules/warnings, never-throw on bad input, same color tables (255 entries, golden angle 137.508, FNV-1a + linear probing, sorted deterministic assignment), same ELK options (`layered`, `RIGHT`, `INCLUDE_CHILDREN`, box padding `[top=40,left=14,bottom=14,right=14]`, node sizing `max(name,(type))+24`, heights 44/30), container-relative edge offsets, live reload (1000 ms), banners, details sidebar with ✕ and kind display.
- Data shapes are idiomatic squint (plain JS objects/arrays at runtime): `validate` returns `{:nodes {..} :edges [..] :boxes [..] :boxes-by-name {..} :parent-of {..} :warnings [..]}` — NO JS `Map`s.
- Squint gotchas that are bugs if ignored:
  - Keywords are strings: `(:type m)` works as a form, but keywords are NOT functions — `(mapv :type xs)` breaks; write `(mapv #(:type %) xs)`.
  - `#{...}` compiles to a JS `Set`: use `(.has s x)` / `(.add s x)` / `(.-size s)`, not `contains?`.
  - Maps are JS objects: use `(get m k)` / `(some? (get m k))` for lookups; `(assoc m ..)` copies, `(assoc! m ..)` mutates in place.
  - `import.meta.url` is reached via `(js* "import.meta.url")`.
  - Named recursion in an expression: `((fn walk [x] ...) start)` (named fn literal).
- Commit messages end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- The old JS frontend keeps working until Task 4 swaps it out; old JS tests are deleted only when their squint port lands.

---

### Task 1: Toolchain scaffold + colors port

**Files:**
- Create: `package.json`, `squint.edn`, `.gitignore`
- Create: `src/simpleviz/colors.cljs`
- Test: `test/simpleviz/colors_test.cljs`
- Modify: `bb.edn` (add `build` task)
- Delete: `test/colors.test.mjs` (superseded)

**Interfaces:**
- Consumes: nothing.
- Produces (namespace `simpleviz.colors`): `fnv1a`, `assign-indices` (types → `{type idx}` object), `color-map` (types, table → `{type entry}` object), `NODE-TABLE` (array of hsl strings), `BOX-TABLE` (array of `{:border :fill}`), `NEUTRAL-NODE`, `NEUTRAL-BOX`, `TABLE-SIZE`.
- Produces: `bb build` — installs npm deps if `node_modules` missing, runs `npx squint compile`, copies `node_modules/reagami` and `node_modules/squint-cljs` to `public/js/vendor/`.

- [ ] **Step 1: Scaffold the toolchain**

Create `package.json`:

```json
{
  "name": "simpleviz",
  "private": true,
  "type": "module",
  "devDependencies": {
    "reagami": "^0.2.38",
    "squint-cljs": "^0.14.206"
  }
}
```

Create `squint.edn`:

```clojure
{:paths ["src" "test"]
 :output-dir "public/js"}
```

Create `.gitignore`:

```
node_modules/
public/js/
```

Add to `bb.edn` `:tasks` (keep all existing tasks):

```clojure
build {:doc "Install npm deps and compile squint sources"
       :requires ([babashka.fs :as fs])
       :task (do (when-not (fs/exists? "node_modules")
                   (shell "npm install"))
                 (shell "npx squint compile")
                 (fs/create-dirs "public/js/vendor")
                 (fs/copy-tree "node_modules/reagami" "public/js/vendor/reagami"
                               {:replace-existing true})
                 (fs/copy-tree "node_modules/squint-cljs" "public/js/vendor/squint-cljs"
                               {:replace-existing true}))}
```

Run: `bb build` — expect npm install output, squint compiling nothing yet (no sources), vendor dirs created.

- [ ] **Step 2: Write the failing test**

Create `test/simpleviz/colors_test.cljs`:

```clojure
(ns simpleviz.colors-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.colors :as colors]))

(test "fnv1a is deterministic and unsigned"
  (fn []
    (assert/equal (colors/fnv1a "service") (colors/fnv1a "service"))
    (assert/notEqual (colors/fnv1a "service") (colors/fnv1a "database"))
    (assert/ok (>= (colors/fnv1a "service") 0))))

(test "tables have 255 entries"
  (fn []
    (assert/equal (.-length colors/NODE-TABLE) 255)
    (assert/equal (.-length colors/BOX-TABLE) 255)
    (assert/match (nth colors/NODE-TABLE 0) (js/RegExp. "^hsl\\("))
    (assert/match (:border (nth colors/BOX-TABLE 0)) (js/RegExp. "^hsl\\("))
    (assert/match (:fill (nth colors/BOX-TABLE 0)) (js/RegExp. "/ 0\\.1\\)$"))))

(test "assignment is independent of input order"
  (fn []
    (assert/deepEqual (colors/assign-indices ["db" "service" "cache"])
                      (colors/assign-indices ["cache" "db" "service"]))))

(test "empty types are ignored"
  (fn []
    (let [idx (colors/assign-indices ["" "svc" ""])]
      (assert/deepEqual (js/Object.keys idx) ["svc"]))))

(test "up to 255 types all get distinct indices"
  (fn []
    (let [types (mapv (fn [i] (str "type-" i)) (range 255))
          idx (colors/assign-indices types)]
      (assert/equal (.-size (js/Set. (js/Object.values idx))) 255))))

(test "more than 255 types does not hang; extras reuse slots"
  (fn []
    (let [types (mapv (fn [i] (str "type-" i)) (range 300))
          idx (colors/assign-indices types)]
      (assert/equal (.-length (js/Object.keys idx)) 300))))

(test "hash collision probes to the next free slot"
  (fn []
    (let [target (js-mod (colors/fnv1a "alpha") 255)
          other (loop [i 0]
                  (when (< i 1000000)
                    (let [cand (str "t" i)]
                      (if (and (not= cand "alpha")
                               (= (js-mod (colors/fnv1a cand) 255) target))
                        cand
                        (recur (inc i))))))]
      (assert/ok other "no colliding string found")
      (let [idx (colors/assign-indices ["alpha" other])]
        (assert/notEqual (get idx "alpha") (get idx other))))))

(test "color-map maps types to table entries"
  (fn []
    (let [m (colors/color-map ["svc"] colors/NODE-TABLE)]
      (assert/match (get m "svc") (js/RegExp. "^hsl\\(")))))
```

- [ ] **Step 3: Compile and run to verify it fails**

Run: `bb build && node --test public/js/simpleviz/colors_test.mjs`
Expected: compile error or FAIL (namespace `simpleviz.colors` missing).

- [ ] **Step 4: Write the implementation**

Create `src/simpleviz/colors.cljs`:

```clojure
(ns simpleviz.colors)

;; Fixed 255-entry color tables. Entry i uses hue i * golden angle, so
;; ADJACENT indices are visually distinct — that makes linear probing on
;; hash collision a safe "next best" choice.

(def TABLE-SIZE 255)
(def GOLDEN-ANGLE 137.508)

(defn fnv1a [s]
  (loop [i 0
         h 0x811c9dc5]
    (if (< i (.-length s))
      (recur (inc i)
             (js/Math.imul (bit-xor h (.charCodeAt s i)) 0x01000193))
      (unsigned-bit-shift-right h 0))))

(defn- hue [i]
  (.toFixed (js-mod (* i GOLDEN-ANGLE) 360) 1))

(def NODE-TABLE
  (mapv (fn [i] (str "hsl(" (hue i) " 65% 38%)")) (range TABLE-SIZE)))

(def BOX-TABLE
  (mapv (fn [i] {:border (str "hsl(" (hue i) " 45% 55%)")
                 :fill (str "hsl(" (hue i) " 45% 55% / 0.1)")})
        (range TABLE-SIZE)))

(def NEUTRAL-NODE "hsl(0 0% 40%)")
(def NEUTRAL-BOX {:border "hsl(0 0% 65%)" :fill "hsl(0 0% 65% / 0.1)"})

(defn assign-indices [types]
  (let [sorted (sort (js/Array.from
                      (js/Set. (filterv (fn [t] (and t (pos? (.-length t)))) types))))
        taken (js/Set.)]
    (reduce (fn [acc t]
              (let [start (js-mod (fnv1a t) TABLE-SIZE)]
                (if (>= (.-size taken) TABLE-SIZE)
                  (assoc acc t start)
                  (loop [idx start]
                    (if (.has taken idx)
                      (recur (js-mod (inc idx) TABLE-SIZE))
                      (do (.add taken idx)
                          (assoc acc t idx)))))))
            {}
            sorted)))

(defn color-map [types table]
  (reduce (fn [acc [t i]] (assoc acc t (nth table i)))
          {}
          (js/Object.entries (assign-indices types))))
```

- [ ] **Step 5: Compile and run to verify it passes**

Run: `bb build && node --test public/js/simpleviz/colors_test.mjs`
Expected: 8/8 PASS. Also run the OLD suite to confirm nothing broke: `bb test` → still green.

- [ ] **Step 6: Delete superseded JS test and commit**

```bash
git rm test/colors.test.mjs
git add package.json package-lock.json squint.edn .gitignore bb.edn src/simpleviz/colors.cljs test/simpleviz/colors_test.cljs
git commit -m "feat: squint toolchain and colors module port"
```

(Note: deleting `test/colors.test.mjs` is safe — `public/lib/colors.mjs` stays until Task 4 because `app.js` still imports it.)

---

### Task 2: validate port

**Files:**
- Create: `src/simpleviz/validate.cljs`
- Test: `test/simpleviz/validate_test.cljs`
- Delete: `test/validate.test.mjs`

**Interfaces:**
- Consumes: nothing from other squint namespaces.
- Produces (`simpleviz.validate`): `validate(raw)` → `{:nodes {name {:id :name :type :attrs}} :edges [{:id :source :target :arrows {:source bool :target bool} :name :type :attrs}] :boxes [{:id :name :type :components [prefixed-ids] :attrs}] :boxes-by-name {name box} :parent-of {prefixed-id box-name} :warnings [str]}`. Boxes in `:boxes` and `:boxes-by-name` are the same (mutated-in-place) objects. Never throws; name/type coerced with `js/String`.

- [ ] **Step 1: Write the failing test**

Create `test/simpleviz/validate_test.cljs` (port of all 21 JS tests; `base` helper mirrors the old suite):

```clojure
(ns simpleviz.validate-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.validate :refer [validate]]))

(defn base []
  {:nodes {"a" {:name "A" :type "svc"} "b" {:name "B"}}
   :edges []
   :boxes []})

(test "empty input yields empty graph, no warnings"
  (fn []
    (let [g (validate {})]
      (assert/deepEqual (:nodes g) {})
      (assert/deepEqual (:edges g) [])
      (assert/deepEqual (:boxes g) [])
      (assert/deepEqual (:warnings g) []))))

(test "node name falls back to its key; type to empty string"
  (fn []
    (let [g (validate {:nodes {"a" {}}})]
      (assert/equal (:name (get (:nodes g) "a")) "a")
      (assert/equal (:type (get (:nodes g) "a")) ""))))

(test "numeric name/type are coerced to strings, no throw"
  (fn []
    (let [g (validate {:nodes {"a" {:name 7 :type 3}}
                       :edges [{:nodes ["a" "a"] :name 1 :type 2}]
                       :boxes [{:name "x" :type 9 :components ["a"]}]})]
      (assert/equal (:name (get (:nodes g) "a")) "7")
      (assert/equal (:type (get (:nodes g) "a")) "3")
      (assert/equal (:type (nth (:edges g) 0)) "2")
      (assert/equal (:type (nth (:boxes g) 0)) "9"))))

(test "direction -> keeps order, arrow on target only"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "->" :name "x" :type "t"}]))]
      (assert/equal (:source (nth (:edges g) 0)) "a")
      (assert/equal (:target (nth (:edges g) 0)) "b")
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target true}))))

(test "direction <- swaps endpoints"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "<-"}]))]
      (assert/equal (:source (nth (:edges g) 0)) "b")
      (assert/equal (:target (nth (:edges g) 0)) "a")
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target true}))))

(test "<-> arrows both ends; missing direction means none"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "<->"}
                                            {:nodes ["a" "b"]}]))]
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source true :target true})
      (assert/deepEqual (:arrows (nth (:edges g) 1)) {:source false :target false}))))

(test "unknown direction warns and renders undirected"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "b"] :direction "=>"}]))]
      (assert/equal (.-length (:edges g)) 1)
      (assert/deepEqual (:arrows (nth (:edges g) 0)) {:source false :target false})
      (assert/equal (.-length (:warnings g)) 1))))

(test "edge to unknown node is skipped with warning"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a" "ghost"] :direction "->"}]))]
      (assert/equal (.-length (:edges g)) 0)
      (assert/match (nth (:warnings g) 0) (js/RegExp. "ghost")))))

(test "edge :nodes must be a 2-element vector"
  (fn []
    (let [g (validate (assoc (base) :edges [{:nodes ["a"]} {:nodes "ab"} {}]))]
      (assert/equal (.-length (:edges g)) 0)
      (assert/equal (.-length (:warnings g)) 3))))

(test "null or undefined edge entries are skipped with warning"
  (fn []
    (let [g (validate (assoc (base) :edges [nil {:nodes ["a" "b"]}]))]
      (assert/equal (.-length (:edges g)) 1)
      (assert/equal (.-length (:warnings g)) 1))))

(test "non-array edges/boxes and non-object nodes warn and act empty"
  (fn []
    (let [g1 (validate {:nodes {"a" {}} :edges {:oops 1}})
          g2 (validate {:nodes {"a" {}} :boxes "nope"})
          g3 (validate {:nodes [1 2 3]})]
      (assert/deepEqual (:edges g1) [])
      (assert/equal (.-length (:warnings g1)) 1)
      (assert/deepEqual (:boxes g2) [])
      (assert/equal (.-length (:warnings g2)) 1)
      (assert/deepEqual (:nodes g3) {})
      (assert/equal (.-length (:warnings g3)) 1))))

(test "box components become prefixed ids"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a" "b"]}]))]
      (assert/deepEqual (sort (:components (get (:boxes-by-name g) "x"))) ["n:a" "n:b"])
      (assert/equal (get (:parent-of g) "n:a") "x"))))

(test "non-array box components warn and act empty"
  (fn []
    (let [g1 (validate (assoc (base) :boxes [{:name "x" :components 42}]))
          g2 (validate (assoc (base) :boxes [{:name "x" :components "abc"}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g1) "x")) [])
      (assert/equal (.-length (:warnings g1)) 1)
      (assert/deepEqual (:components (get (:boxes-by-name g2) "x")) [])
      (assert/equal (.-length (:warnings g2)) 1))))

(test "boxes nest via box-name components"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "outer" :components ["inner"]}
                                            {:name "inner" :components ["a"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "outer")) ["b:inner"])
      (assert/equal (get (:parent-of g) "b:inner") "outer"))))

(test "duplicate membership: first box in file order wins"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a"]}
                                            {:name "y" :components ["a" "b"]}]))]
      (assert/equal (get (:parent-of g) "n:a") "x")
      (assert/deepEqual (:components (get (:boxes-by-name g) "y")) ["n:b"])
      (assert/equal (.-length (:warnings g)) 1))))

(test "unknown component ignored with warning"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["ghost"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "x")) [])
      (assert/match (nth (:warnings g) 0) (js/RegExp. "ghost")))))

(test "box cannot contain itself"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["x" "a"]}]))]
      (assert/deepEqual (:components (get (:boxes-by-name g) "x")) ["n:a"])
      (assert/equal (.-length (:warnings g)) 1))))

(test "containment cycle is broken with warning"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["y"]}
                                            {:name "y" :components ["x"]}]))
          links (filterv some? [(get (:parent-of g) "b:x") (get (:parent-of g) "b:y")])]
      (assert/equal (.-length links) 1)
      (assert/ok (>= (.-length (:warnings g)) 1)))))

(test "duplicate box name: later one ignored"
  (fn []
    (let [g (validate (assoc (base) :boxes [{:name "x" :components ["a"]}
                                            {:name "x" :components ["b"]}]))]
      (assert/equal (.-length (:boxes g)) 1)
      (assert/equal (.-length (:warnings g)) 1))))
```

- [ ] **Step 2: Compile and run to verify it fails**

Run: `bb build && node --test public/js/simpleviz/validate_test.mjs`
Expected: FAIL (namespace `simpleviz.validate` missing).

- [ ] **Step 3: Write the implementation**

Create `src/simpleviz/validate.cljs`:

```clojure
(ns simpleviz.validate)

;; Normalizes the raw server JSON into a validated graph. Never throws on
;; bad data — problems become entries in :warnings and the offending
;; element is skipped.

(def ^:private directions #{"->" "<-" "<->" "-"})

(defn- plain-map? [x]
  (and (some? x) (= "object" (js-typeof x)) (not (js/Array.isArray x))))

(defn- ->str [x fallback]
  (js/String (if (nil? x) fallback x)))

(defn validate [raw]
  (let [warnings (atom [])
        warn! (fn [msg] (swap! warnings conj msg))

        raw-nodes (let [n (:nodes raw)]
                    (cond (plain-map? n) n
                          (nil? n) {}
                          :else (do (warn! ":nodes must be a map, ignoring it") {})))
        raw-edges (let [e (:edges raw)]
                    (cond (js/Array.isArray e) e
                          (nil? e) []
                          :else (do (warn! ":edges must be a vector, ignoring it") [])))
        raw-boxes (let [b (:boxes raw)]
                    (cond (js/Array.isArray b) b
                          (nil? b) []
                          :else (do (warn! ":boxes must be a vector, ignoring it") [])))

        nodes (reduce (fn [acc [k v]]
                        (let [attrs (if (plain-map? v) v {})]
                          (assoc acc k {:id k
                                        :name (->str (:name attrs) k)
                                        :type (->str (:type attrs) "")
                                        :attrs attrs})))
                      {}
                      (js/Object.entries raw-nodes))

        edges (vec (filter some?
                (map-indexed
                 (fn [i e]
                   (if-not (plain-map? e)
                     (do (warn! (str "edge " i ": not a map, skipped")) nil)
                     (let [ends (:nodes e)]
                       (if (or (not (js/Array.isArray ends)) (not= 2 (.-length ends)))
                         (do (warn! (str "edge " i ": :nodes must be a vector of exactly 2 node names")) nil)
                         (let [missing (filterv (fn [n] (nil? (get nodes n))) ends)]
                           (if (pos? (.-length missing))
                             (do (warn! (str "edge " i " [" (.join ends " ") "]: unknown node(s): " (.join missing ", "))) nil)
                             (let [dir0 (if (nil? (:direction e)) "-" (:direction e))
                                   dir (if (.has directions dir0)
                                         dir0
                                         (do (warn! (str "edge " i ": unknown direction \"" dir0 "\", treating as undirected")) "-"))
                                   source (if (= dir "<-") (nth ends 1) (nth ends 0))
                                   target (if (= dir "<-") (nth ends 0) (nth ends 1))]
                               {:id (str "e" i)
                                :source source
                                :target target
                                :arrows {:source (= dir "<->") :target (not= dir "-")}
                                :name (->str (:name e) "")
                                :type (->str (:type e) "")
                                :attrs e})))))))
                 raw-edges)))

        boxes (atom [])
        boxes-by-name (atom {})
        _ (doseq [[i b] (map-indexed vector raw-boxes)]
            (cond
              (or (not (plain-map? b)) (nil? (:name b)))
              (warn! (str "box " i ": missing :name, skipped"))

              (some? (get @boxes-by-name (->str (:name b) "")))
              (warn! (str "box \"" (:name b) "\": duplicate name, later definition skipped"))

              :else
              (let [nm (->str (:name b) "")
                    comps (let [c (:components b)]
                            (cond (js/Array.isArray c) (vec c)
                                  (nil? c) []
                                  :else (do (warn! (str "box \"" nm "\": :components must be a collection, skipped")) [])))
                    box {:id (str "b:" nm)
                         :name nm
                         :type (->str (:type b) "")
                         :components comps
                         :attrs b}]
                (swap! boxes conj box)
                (swap! boxes-by-name assoc nm box))))

        ;; Membership: ELK needs a strict hierarchy — first box in file
        ;; order wins. Box objects are mutated in place (assoc!) so
        ;; :boxes and :boxes-by-name stay the same objects.
        parent-of (atom {})
        _ (doseq [box @boxes]
            (let [kept (atom [])]
              (doseq [c (:components box)]
                (let [is-node (some? (get nodes c))
                      is-box (some? (get @boxes-by-name c))]
                  (cond
                    (and (not is-node) (not is-box))
                    (warn! (str "box \"" (:name box) "\": unknown component \"" c "\""))

                    (and is-box (not is-node) (= c (:name box)))
                    (warn! (str "box \"" (:name box) "\" cannot contain itself"))

                    :else
                    (let [_ (when (and is-node is-box)
                              (warn! (str "\"" c "\" names both a node and a box; box \"" (:name box) "\" gets the node")))
                          id (if is-node (str "n:" c) (str "b:" c))]
                      (if (some? (get @parent-of id))
                        (warn! (str "\"" c "\" is already in box \"" (get @parent-of id) "\"; membership in \"" (:name box) "\" ignored"))
                        (do (swap! parent-of assoc id (:name box))
                            (swap! kept conj id)))))))
              (assoc! box :components @kept)))

        ;; Break containment cycles (a in b, b in a) by detaching one link.
        _ (doseq [box @boxes]
            (let [seen (js/Set. [(:name box)])]
              (loop [p (get @parent-of (str "b:" (:name box)))]
                (when (some? p)
                  (if (.has seen p)
                    (let [parent-name (get @parent-of (str "b:" (:name box)))
                          parent (get @boxes-by-name parent-name)]
                      (warn! (str "box containment cycle: detaching \"" (:name box) "\" from \"" parent-name "\""))
                      (assoc! parent :components
                              (filterv (fn [c] (not= c (str "b:" (:name box)))) (:components parent)))
                      (swap! parent-of dissoc (str "b:" (:name box))))
                    (do (.add seen p)
                        (recur (get @parent-of (str "b:" p)))))))))]

    {:nodes nodes
     :edges edges
     :boxes @boxes
     :boxes-by-name @boxes-by-name
     :parent-of @parent-of
     :warnings @warnings}))
```

- [ ] **Step 4: Compile and run to verify it passes**

Run: `bb build && node --test public/js/simpleviz/validate_test.mjs public/js/simpleviz/colors_test.mjs`
Expected: all PASS. `bb test` (old suite) still green.

- [ ] **Step 5: Delete superseded JS test and commit**

```bash
git rm test/validate.test.mjs
git add src/simpleviz/validate.cljs test/simpleviz/validate_test.cljs
git commit -m "feat: port validate to squint"
```

---

### Task 3: transform port + ELK integration test

**Files:**
- Create: `src/simpleviz/transform.cljs`
- Test: `test/simpleviz/transform_test.cljs`, `test/simpleviz/layout_test.cljs`
- Delete: `test/transform.test.mjs`, `test/layout.test.mjs`

**Interfaces:**
- Consumes: `simpleviz.validate/validate` (Task 2 shape).
- Produces (`simpleviz.transform`): `to-elk(graph, measure)` → ELK JSON graph (same options/ids/sizing as Global Constraints), `NODE-FONT` = `"bold 14px system-ui, sans-serif"`, `SUB-FONT` = `"11px system-ui, sans-serif"`.

- [ ] **Step 1: Write the failing tests**

Create `test/simpleviz/transform_test.cljs`:

```clojure
(ns simpleviz.transform-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.validate :refer [validate]]
            [simpleviz.transform :refer [to-elk]]))

(defn measure [text _font] (* (.-length text) 7))

(test "node sizing uses label widths; typed nodes are taller"
  (fn []
    (let [g (validate {:nodes {"a" {:name "Hello" :type "svc"} "b" {}}})
          elk (to-elk g measure)
          a (first (filterv (fn [c] (= (:id c) "n:a")) (:children elk)))
          b (first (filterv (fn [c] (= (:id c) "n:b")) (:children elk)))]
      (assert/ok (>= (:width a) (measure "Hello" nil)))
      (assert/equal (:height a) 44)
      (assert/equal (:height b) 30))))

(test "boxes nest components; contained elements not repeated at root"
  (fn []
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :boxes [{:name "outer" :components ["inner" "a"]}
                               {:name "inner" :components ["b"]}]})
          elk (to-elk g measure)]
      (assert/deepEqual (mapv (fn [c] (:id c)) (:children elk)) ["b:outer"])
      (let [outer (nth (:children elk) 0)
            inner (first (filterv (fn [c] (= (:id c) "b:inner")) (:children outer)))]
        (assert/deepEqual (sort (mapv (fn [c] (:id c)) (:children outer))) ["b:inner" "n:a"])
        (assert/deepEqual (mapv (fn [c] (:id c)) (:children inner)) ["n:b"])
        (assert/ok (.includes (get (:layoutOptions outer) "elk.padding") "top=40"))))))

(test "edges use prefixed ids and live at the root"
  (fn []
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}]})
          elk (to-elk g measure)]
      (assert/deepEqual (:edges elk)
                        [{:id "e0" :sources ["n:a"] :targets ["n:b"]}]))))

(test "root layout options select hierarchical layered layout"
  (fn []
    (let [elk (to-elk (validate {}) measure)]
      (assert/equal (get (:layoutOptions elk) "elk.algorithm") "layered")
      (assert/equal (get (:layoutOptions elk) "elk.direction") "RIGHT")
      (assert/equal (get (:layoutOptions elk) "elk.hierarchyHandling") "INCLUDE_CHILDREN"))))
```

Create `test/simpleviz/layout_test.cljs`:

```clojure
(ns simpleviz.layout-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.validate :refer [validate]]
            [simpleviz.transform :refer [to-elk]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(defn measure [text _font] (* (.-length text) 7))

(test "ELK lays out a nested boxed graph end to end"
  (fn []
    (let [g (validate {:nodes {"a" {:type "svc"} "b" {:type "db"} "c" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}
                               {:nodes ["c" "a"] :direction "<-"}
                               {:nodes ["b" "c"] :direction "<->"}
                               {:nodes ["a" "c"] :direction "-"}]
                       :boxes [{:name "grp" :components ["a" "b"]}]})]
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
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}]
                       :boxes [{:name "grp" :components ["a" "b"]}]})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   ;; the renderer must offset by the container's absolute
                   ;; origin — this documents the contract it relies on
                   (assert/equal (:container (nth (:edges layout) 0)) "b:grp")))))))
```

(Returning the promise from the test fn makes `node --test` await it.)

- [ ] **Step 2: Compile and run to verify it fails**

Run: `bb build && node --test public/js/simpleviz/transform_test.mjs`
Expected: FAIL (namespace `simpleviz.transform` missing).

- [ ] **Step 3: Write the implementation**

Create `src/simpleviz/transform.cljs`:

```clojure
(ns simpleviz.transform)

;; Builds the ELK JSON graph from a validated graph. Text measurement is
;; injected so this namespace stays DOM-free and testable.

(def NODE-FONT "bold 14px system-ui, sans-serif")
(def SUB-FONT "11px system-ui, sans-serif")

(defn to-elk [graph measure]
  (let [{:keys [nodes boxes boxes-by-name parent-of edges]} graph
        node-elk (fn [n]
                   (let [typed? (pos? (.-length (:type n)))
                         w (max (measure (:name n) NODE-FONT)
                                (if typed? (measure (str "(" (:type n) ")") SUB-FONT) 0))]
                     {:id (str "n:" (:id n))
                      :width (+ (js/Math.ceil w) 24)
                      :height (if typed? 44 30)}))
        box-elk (fn box-elk [b]
                  {:id (str "b:" (:name b))
                   :layoutOptions {"elk.padding" "[top=40,left=14,bottom=14,right=14]"}
                   :children (mapv (fn [c]
                                     (if (.startsWith c "n:")
                                       (node-elk (get nodes (.slice c 2)))
                                       (box-elk (get boxes-by-name (.slice c 2)))))
                                   (:components b))})
        root-nodes (vec (filter some?
                         (mapv (fn [n] (when (nil? (get parent-of (str "n:" (:id n))))
                                         (node-elk n)))
                               (js/Object.values nodes))))
        root-boxes (vec (filter some?
                         (mapv (fn [b] (when (nil? (get parent-of (str "b:" (:name b))))
                                         (box-elk b)))
                               boxes)))]
    {:id "root"
     :layoutOptions {"elk.algorithm" "layered"
                     "elk.direction" "RIGHT"
                     "elk.hierarchyHandling" "INCLUDE_CHILDREN"
                     "elk.layered.spacing.nodeNodeBetweenLayers" "50"
                     "elk.spacing.nodeNode" "30"
                     "elk.spacing.edgeNode" "20"
                     "elk.padding" "[top=20,left=20,bottom=20,right=20]"}
     :children (into root-nodes root-boxes)
     :edges (mapv (fn [e] {:id (:id e)
                           :sources [(str "n:" (:source e))]
                           :targets [(str "n:" (:target e))]})
                  edges)}))
```

- [ ] **Step 4: Compile and run to verify it passes**

Run: `bb build && node --test public/js/simpleviz/transform_test.mjs public/js/simpleviz/layout_test.mjs`
Expected: all PASS (transform 4, layout 2). `bb test` (old suite) still green.

- [ ] **Step 5: Delete superseded JS tests and commit**

```bash
git rm test/transform.test.mjs test/layout.test.mjs
git add src/simpleviz/transform.cljs test/simpleviz/transform_test.cljs test/simpleviz/layout_test.cljs
git commit -m "feat: port transform and layout tests to squint"
```

---

### Task 4: reagami UI, page swap, bb task rewiring

**Files:**
- Create: `src/simpleviz/render.cljs`, `src/simpleviz/app.cljs`
- Modify: `public/index.html`, `public/style.css`, `bb.edn`
- Delete: `public/app.js`, `public/lib/colors.mjs`, `public/lib/validate.mjs`, `public/lib/transform.mjs`, `public/lib/render.mjs`

**Interfaces:**
- Consumes: `simpleviz.colors` (`color-map`, `NODE-TABLE`, `BOX-TABLE`, `NEUTRAL-NODE`, `NEUTRAL-BOX`), `simpleviz.validate/validate`, `simpleviz.transform/to-elk`, npm `reagami` (`render`), global `ELK`.
- Produces (`simpleviz.render`): `measure`, `view` (mutable `{:x :y :k :initialized}`), `view-transform`, `fit-view-once!`, `setup-pan-zoom!`, `suppress-click` (atom), `graph-view`. (`simpleviz.app`): page entry, state atom, polling.

- [ ] **Step 1: Write the renderer namespace**

Create `src/simpleviz/render.cljs`:

```clojure
(ns simpleviz.render)

;; Hiccup views + imperative pan/zoom. DOM-only namespace: never imported
;; by node tests.

(def ^:private measure-ctx
  (.getContext (js/document.createElement "canvas") "2d"))

(defn measure [text font]
  (set! (.-font measure-ctx) font)
  (.-width (.measureText measure-ctx text)))

;; Pan/zoom state survives re-renders so live reload keeps the view.
;; Mutated in place (assoc!) — deliberately outside the reagami state atom
;; so pointermove does not trigger re-renders.
(def view {:x 0 :y 0 :k 1 :initialized false})
(def suppress-click (atom false))

(defn view-transform []
  (str "translate(" (:x view) "," (:y view) ") scale(" (:k view) ")"))

(defn- apply-view! []
  (when-let [vp (js/document.getElementById "viewport")]
    (.setAttribute vp "transform" (view-transform))))

(defn fit-view-once! [layout]
  (when-not (:initialized view)
    (assoc! view :initialized true)
    (let [rect (.getBoundingClientRect (js/document.getElementById "canvas-wrap"))
          w (or (:width layout) 1)
          h (or (:height layout) 1)
          k (js/Math.min 1.25 (* 0.9 (js/Math.min (/ (.-width rect) w)
                                                  (/ (.-height rect) h))))]
      (assoc! view
              :k k
              :x (/ (- (.-width rect) (* w k)) 2)
              :y (/ (- (.-height rect) (* h k)) 2)))))

(defn setup-pan-zoom!
  "Attach wheel/pointer listeners once to the static wrapper element
  (outside reagami's tree, so re-renders never stack handlers)."
  [wrap]
  (.addEventListener wrap "wheel"
    (fn [e]
      (.preventDefault e)
      (let [factor (if (< (.-deltaY e) 0) 1.1 (/ 1 1.1))
            rect (.getBoundingClientRect wrap)
            mx (- (.-clientX e) (.-left rect))
            my (- (.-clientY e) (.-top rect))]
        (assoc! view
                :x (- mx (* (- mx (:x view)) factor))
                :y (- my (* (- my (:y view)) factor))
                :k (* (:k view) factor))
        (apply-view!)))
    {:passive false})
  (let [drag (atom nil)]
    (.addEventListener wrap "pointerdown"
      (fn [e]
        (reset! drag {:x (.-clientX e) :y (.-clientY e)
                      :vx (:x view) :vy (:y view) :moved false})
        (.setPointerCapture wrap (.-pointerId e))))
    (.addEventListener wrap "pointermove"
      (fn [e]
        (when-let [d @drag]
          (let [dx (- (.-clientX e) (:x d))
                dy (- (.-clientY e) (:y d))]
            (when (> (+ (js/Math.abs dx) (js/Math.abs dy)) 3)
              (swap! drag assoc :moved true))
            (assoc! view :x (+ (:vx d) dx) :y (+ (:vy d) dy))
            (apply-view!)))))
    (.addEventListener wrap "pointerup"
      (fn [_]
        (when (and @drag (:moved @drag)) (reset! suppress-click true))
        (reset! drag nil)))
    (.addEventListener wrap "pointercancel" (fn [_] (reset! drag nil)))))

(defn- selectable-attrs [payload on-select]
  {:on-click (fn [e]
               (.stopPropagation e)
               (if @suppress-click
                 (reset! suppress-click false)
                 (on-select payload)))})

(defn- node-view [child x y node color on-select selected-id]
  (let [cx (/ (:width child) 2)
        sel? (= selected-id (:id child))]
    [:g (assoc (selectable-attrs {:kind "node" :elk-id (:id child)
                                  :title (:name node) :subtitle (:type node)
                                  :attrs (:attrs node)}
                                 on-select)
               :key (:id child)
               :class (str "node selectable" (when sel? " selected"))
               :transform (str "translate(" x "," y ")"))
     [:rect {:class "node-bg" :width (:width child) :height (:height child) :rx 6}]
     [:text {:class "node-name" :x cx :y 19 :text-anchor "middle" :fill color}
      (:name node)]
     (when (pos? (.-length (:type node)))
       [:text {:class "node-sub" :x cx :y 35 :text-anchor "middle"}
        (str "(" (:type node) ")")])]))

(defn- box-view [child x y box c on-select selected-id]
  (let [sel? (= selected-id (:id child))]
    [:g (assoc (selectable-attrs {:kind "box" :elk-id (:id child)
                                  :title (:name box) :subtitle (:type box)
                                  :attrs (:attrs box)}
                                 on-select)
               :key (:id child)
               :class (str "box selectable" (when sel? " selected"))
               :transform (str "translate(" x "," y ")"))
     [:rect {:class "box-bg" :width (:width child) :height (:height child) :rx 10
             :fill (:fill c) :stroke (:border c)}]
     [:text {:class "box-name" :x 12 :y 24 :fill (:border c)}
      (:name box)
      (when (pos? (.-length (:type box)))
        [:tspan {:class "box-sub"} (str " (" (:type box) ")")])]]))

(defn- section-points [sec]
  (into [(:startPoint sec)]
        (conj (vec (or (:bendPoints sec) [])) (:endPoint sec))))

(defn- midpoint [pts]
  (let [segs (mapv (fn [i]
                     (js/Math.hypot (- (:x (nth pts (inc i))) (:x (nth pts i)))
                                    (- (:y (nth pts (inc i))) (:y (nth pts i)))))
                   (range (dec (.-length pts))))
        total (reduce + 0 segs)]
    (loop [i 0 acc 0]
      (if (>= i (.-length segs))
        (nth pts 0)
        (if (>= (+ acc (nth segs i)) (/ total 2))
          (let [t (/ (- (/ total 2) acc) (max (nth segs i) 1e-9))
                p0 (nth pts i)
                p1 (nth pts (inc i))]
            {:x (+ (:x p0) (* t (- (:x p1) (:x p0))))
             :y (+ (:y p0) (* t (- (:y p1) (:y p0))))})
          (recur (inc i) (+ acc (nth segs i))))))))

(defn- edge-view [elk-edge e origin on-select selected-id]
  (let [pts (vec (mapcat (fn [sec]
                           (mapv (fn [p] {:x (+ (:x p) (:x origin))
                                          :y (+ (:y p) (:y origin))})
                                 (section-points sec)))
                         (or (:sections elk-edge) [])))
        d (.join (vec (map-indexed
                       (fn [i p] (str (if (zero? i) "M " "L ") (:x p) " " (:y p)))
                       pts))
                 " ")
        label (.join (filterv (fn [s] (pos? (.-length s)))
                              [(:name e) (if (pos? (.-length (:type e)))
                                           (str "(" (:type e) ")") "")])
                     " ")
        sel? (= selected-id (:id elk-edge))]
    (when (pos? (.-length pts))
      [:g (assoc (selectable-attrs {:kind "edge" :elk-id (:id elk-edge)
                                    :title (if (pos? (.-length (:name e)))
                                             (:name e)
                                             (str (:source e) " → " (:target e)))
                                    :subtitle (:type e) :attrs (:attrs e)}
                                   on-select)
                 :key (:id elk-edge)
                 :class (str "edge selectable" (when sel? " selected")))
       [:path (cond-> {:class "edge-line" :d d :fill "none"}
                (:target (:arrows e)) (assoc :marker-end "url(#arrow)")
                (:source (:arrows e)) (assoc :marker-start "url(#arrow)"))]
       [:path {:class "edge-hit" :d d :fill "none"}]
       (when (pos? (.-length label))
         (let [mid (midpoint pts)]
           [:text {:class "edge-label" :x (:x mid) :y (- (:y mid) 5)
                   :text-anchor "middle"}
            label]))])))

(defn- walk-layout
  "Flatten the ELK result: absolute positions for nodes/boxes plus each
  box's absolute origin (edge sections are relative to their :container)."
  [layout]
  (let [nodes (atom []) boxes (atom []) origins (atom {})]
    ((fn walk [parent ox oy]
       (doseq [child (or (:children parent) [])]
         (let [x (+ ox (:x child))
               y (+ oy (:y child))]
           (if (.startsWith (:id child) "b:")
             (do (swap! boxes conj {:child child :x x :y y})
                 (swap! origins assoc (:id child) {:x x :y y})
                 (walk child x y))
             (swap! nodes conj {:child child :x x :y y})))))
     layout 0 0)
    {:nodes @nodes :boxes @boxes :origins @origins}))

(defn graph-view [{:keys [layout graph colors selected-id on-select]}]
  (let [{:keys [nodes boxes origins]} (walk-layout layout)
        edges-by-id (reduce (fn [acc e] (assoc acc (:id e) e)) {} (:edges graph))]
    [:svg {:id "canvas"
           :on-click (fn [_]
                       (if @suppress-click
                         (reset! suppress-click false)
                         (on-select nil)))}
     [:defs
      [:marker {:id "arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                :markerWidth 7 :markerHeight 7 :orient "auto-start-reverse"}
       [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "#555"}]]]
     [:g {:id "viewport" :transform (view-transform)}
      (into [:g {:key "boxes"}]
            (mapv (fn [{:keys [child x y]}]
                    (let [box (get (:boxes-by-name graph) (.slice (:id child) 2))
                          c (if (pos? (.-length (:type box)))
                              (get (:box colors) (:type box))
                              (:neutral-box colors))]
                      (box-view child x y box c on-select selected-id)))
                  boxes))
      (into [:g {:key "edges"}]
            (vec (filter some?
                  (mapv (fn [elk-edge]
                          (let [e (get edges-by-id (:id elk-edge))
                                origin (or (get origins (:container elk-edge))
                                           {:x 0 :y 0})]
                            (when (some? e)
                              (edge-view elk-edge e origin on-select selected-id))))
                        (or (:edges layout) [])))))
      (into [:g {:key "nodes"}]
            (mapv (fn [{:keys [child x y]}]
                    (let [node (get (:nodes graph) (.slice (:id child) 2))
                          color (if (pos? (.-length (:type node)))
                                  (get (:node colors) (:type node))
                                  (:neutral-node colors))]
                      (node-view child x y node color on-select selected-id)))
                  nodes))]]))
```

- [ ] **Step 2: Write the app namespace**

Create `src/simpleviz/app.cljs`:

```clojure
(ns simpleviz.app
  (:require ["reagami" :refer [render]]
            [simpleviz.colors :as colors]
            [simpleviz.validate :refer [validate]]
            [simpleviz.transform :refer [to-elk]]
            [simpleviz.render :as r]))

(def elk (js/ELK.))
(def app-el (js/document.getElementById "app"))

(def state (atom {:error nil :warnings [] :graph nil :layout nil
                  :colors nil :selected nil :collapsed false}))
(def last-mtime (atom nil))

(defn- on-select [payload]
  (swap! state assoc :selected payload))

(defn- details-view [sel]
  [:aside {:id "details"}
   [:button {:id "details-close"
             :on-click (fn [e] (.stopPropagation e) (on-select nil))}
    "×"]
   [:h2 (:title sel)]
   [:div {:class "details-type"}
    (if (pos? (.-length (:subtitle sel)))
      (str "(" (:subtitle sel) ") — " (:kind sel))
      (:kind sel))]
   (into [:dl]
         (mapcat (fn [[k v]]
                   [[:dt {:key (str "t" k)} k]
                    [:dd {:key (str "d" k)}
                     (if (string? v) v (js/JSON.stringify v nil 2))]])
                 (js/Object.entries (:attrs sel))))])

(defn- banner-view [{:keys [error warnings collapsed]}]
  (cond
    (some? error)
    [:div {:id "banner" :class "error"} error]

    (pos? (.-length warnings))
    [:div {:id "banner"
           :class (str "warning" (when collapsed " collapsed"))
           :on-click (fn [_] (swap! state update :collapsed not))}
     (.join warnings "\n")]

    :else nil))

(defn- app-view [st]
  [:div {:id "root"}
   (banner-view st)
   (when (some? (:layout st))
     (r/graph-view {:layout (:layout st)
                    :graph (:graph st)
                    :colors (:colors st)
                    :selected-id (:elk-id (:selected st))
                    :on-select on-select}))
   (when (some? (:selected st))
     (details-view (:selected st)))])

(defn- rerender! []
  (render app-el (app-view @state)))

(defn ^:async reload! []
  (try
    (let [resp (js-await (js/fetch "/api/graph"))
          raw (js-await (.json resp))]
      (if (some? (:error raw))
        (swap! state assoc :error (str "EDN parse error: " (:error raw)))
        (let [g (validate raw)
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
                 :colors cmap :layout layout))))
    (catch :default e
      (js/console.error "Reload failed:" e)
      (reset! last-mtime nil)
      (swap! state assoc :error (str "Render error: " (or (.-message e) (str e)))))))

(defn ^:async tick []
  (let [mtime (try
                (let [resp (js-await (js/fetch "/api/version"))
                      v (js-await (.json resp))]
                  (:mtime v))
                (catch :default _ nil))]
    (when (and (some? mtime) (not= mtime @last-mtime))
      (reset! last-mtime mtime)
      (js-await (reload!)))))

;; init
(add-watch state :render (fn [_ _ _ _] (rerender!)))
(r/setup-pan-zoom! (js/document.getElementById "canvas-wrap"))
(rerender!)
(tick)
(js/setInterval tick 1000)
```

- [ ] **Step 3: Swap the page**

Replace `public/index.html` with:

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>simpleviz</title>
<link rel="stylesheet" href="/style.css">
<script type="importmap">
{"imports": {"reagami": "/js/vendor/reagami/reagami.mjs",
             "squint-cljs/": "/js/vendor/squint-cljs/"}}
</script>
</head>
<body>
<div id="canvas-wrap"><div id="app"></div></div>
<script src="/vendor/elk.bundled.js"></script>
<script type="module" src="/js/simpleviz/app.mjs"></script>
</body>
</html>
```

In `public/style.css`, replace the `#canvas` block with (rest of the file unchanged, plus one new rule for the close button):

```css
#canvas-wrap { position: fixed; inset: 0; background: #fafafa; cursor: grab; }
#canvas-wrap:active { cursor: grabbing; }
#canvas { width: 100%; height: 100%; display: block; }

#details-close { position: absolute; top: 8px; right: 10px; border: none;
                 background: none; font-size: 18px; cursor: pointer; color: #666; }
#details-close:hover { color: #000; }
```

(`#details` already has `position: fixed`; add `position` context only if missing — the ✕ is positioned against the viewport edge either way. Verify visually in Step 6.)

- [ ] **Step 4: Delete the old frontend and rewire bb tasks**

```bash
git rm public/app.js public/lib/colors.mjs public/lib/validate.mjs public/lib/transform.mjs public/lib/render.mjs
```

In `bb.edn`:
- Change `test:js` to:

```clojure
test:js {:doc "Run JS unit tests (compiled squint output)"
         :requires ([babashka.fs :as fs])
         :task (apply shell "node" "--test"
                      (map str (fs/glob "public/js/simpleviz" "*_test.mjs")))}
```

- Change `test` to `{:doc "Run all tests" :depends [build test:clj test:js]}`.
- Add:

```clojure
dev {:doc "Compile, watch and serve (default examples/demo.edn)"
     :requires ([babashka.process :as p] [serve])
     :task (do (run 'build)
               (p/process ["npx" "squint" "watch"] {:inherit true})
               (serve/-main (or (first *command-line-args*) "examples/demo.edn")))}
```

- [ ] **Step 5: Full test + server verification**

```bash
bb test          # build + clj tests + squint-compiled JS tests — all green
bb serve examples/demo.edn &
sleep 2
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/                       # 200
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/js/simpleviz/app.mjs   # 200
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/js/vendor/reagami/reagami.mjs  # 200
curl -s localhost:8080/js/simpleviz/app.mjs | head -c 200                      # compiled JS, imports visible
kill %1
```

- [ ] **Step 6: Browser check**

Start `bb serve examples/demo.edn` (or `bb dev`), open http://localhost:8080 and verify the full checklist: graph renders identically to the JS version (nested boxes, colors by type, arrowheads per direction, in-box edges attached to their nodes), click → details sidebar with kind + all attrs + working ✕, background click closes, pan/zoom works, editing the file live-reloads preserving the view, breaking the file shows the error banner and keeps the last render, warning banner collapses on click. If running without a browser, use available browser tooling or hand the checklist to the controller — do not skip silently.

- [ ] **Step 7: Commit**

```bash
git add public/index.html public/style.css bb.edn src/simpleviz/render.cljs src/simpleviz/app.cljs
git commit -m "feat: reagami UI in squint, replace JS frontend"
```

---

### Task 5: README + final verification

**Files:**
- Modify: `README.md` (Development + Requirements sections)

**Interfaces:** consumes everything; produces docs.

- [ ] **Step 1: Update the README**

Replace the `## Requirements` section with:

```markdown
## Requirements

- [babashka](https://babashka.org/) (serving + tests)
- node + npm (frontend development — compiling the Squint sources)
- A browser
```

Replace the `## Development` section with:

```markdown
## Development

The frontend is written in [Squint](https://github.com/squint-cljs/squint)
ClojureScript rendered with [reagami](https://github.com/borkdude/reagami),
compiled to plain ES modules (no bundler).

    bb dev [graph.edn]   # compile, watch sources, serve (default: examples/demo.edn)
    bb build             # one-shot compile to public/js/ (git-ignored)
    bb test              # compile + Clojure server tests + JS unit tests
    bb serve graph.edn   # serve only (needs a prior bb build)

Layout: vendored [ELK.js](https://github.com/kieler/elkjs) (layered,
left-to-right, compound boxes). Sources in `src/simpleviz/`, tests in
`test/simpleviz/` (run by `node --test` against the compiled output).
```

- [ ] **Step 2: Final verification**

Run: `bb test` — all green. `git status` — no uncommitted source files; `public/js/` and `node_modules/` absent from tracking.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: squint/reagami development workflow"
```
