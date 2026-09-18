# Follow refs between graphs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `:ref` attribute on a node, box or edge names another graph file by relative path; a "follow ref" toolbar action (`f r`) opens it in place, and a clickable trail of visited files leads back.

**Architecture:** One server per root file. The page asks the existing API routes for a file by root-relative path (`?file=`), and the server resolves every such path below the root folder or refuses it. The page keeps the shown file and the trail in the URL query string, so reload, bookmark and browser back all work. Pure path/URL helpers live in `editor.cljs` with JS unit tests; the server resolver and routes get Clojure tests.

**Tech Stack:** babashka (Clojure) server with http-kit-style ring maps, squint (ClojureScript → ES modules) page with reagami, `node --test` for JS tests, `bb test` for everything.

**Spec:** `docs/superpowers/specs/2026-09-17-follow-ref-design.md`

## Global Constraints

- Follow-ref is available in single-file mode only; compare mode (two files, or a compare-export PNG root) refuses the `file`/`path` parameters with the message `refs are not available in compare mode`.
- A ref never resolves outside the **root folder** = the directory of the file the server was started with; targets must be `.edn` or `.png` regular files.
- Squint gotchas: keywords are strings; `when`/`get` on a missing key yield `undefined`, test with `nil?`/`some?` (both treat `undefined` as nil); `(:key nil)` is safe.
- After any `.cljs` change run `bb build` (or `npx squint compile`) before `bb test:js`; `bb test` does both.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Work on branch `follow-ref` (already exists, holds the spec).

---

## File map

| File | Responsibility |
|---|---|
| `server/serve.clj` | `root-dir` atom, `resolve-path`, `file` query parameter on graph/version/source, `path` on edit |
| `test/server_test.clj` | resolver and route tests |
| `test/fixtures/refs/root.edn`, `test/fixtures/refs/sub/api.edn`, `test/fixtures/refs/sub/deep/db.edn` | a three-file ref tree used by server tests and the headless check |
| `src/simpleviz/editor.cljs` | pure nav helpers: `resolve-ref`, `parse-nav`, `nav-query`, `follow-url`, `crumb-url`, `ref-of`; chord `f r` |
| `test/simpleviz/editor_test.cljs` | tests for the above |
| `src/simpleviz/app.cljs` | `:nav` state from the URL, file parameter on fetches, `follow-ref!`/`navigate!`/`load-nav!`, trail bar, `nav-error` banner, help text |
| `public/style.css` | trail bar styling |
| `dev/cdp.mjs` | headless-Chromium DevTools driver used for the manual end-to-end check |
| `README.md`, `plugins/simpleviz/skills/simpleviz/SKILL.md`, `docs/development.md` | docs |

---

### Task 1: Server — root folder and `resolve-path`

**Files:**
- Modify: `server/serve.clj` (after the `undo-stacks` def, line ~21; and `-main`, line ~297)
- Create: `test/fixtures/refs/root.edn`, `test/fixtures/refs/sub/api.edn`, `test/fixtures/refs/sub/deep/db.edn`, `test/fixtures/refs/notes.txt`
- Test: `test/server_test.clj`

**Interfaces:**
- Produces: `serve/root-dir` — atom holding the canonical `java.io.File` of the served folder (nil until `-main` or a test sets it). `(serve/resolve-path root rel)` → canonical `java.io.File` below `root`, or throws `ex-info` whose message names the problem.

- [ ] **Step 1: Create the fixture tree**

`test/fixtures/refs/root.edn`:
```edn
{:nodes {:api {:name "API" :ref "sub/api.edn"}
         :web {:name "Web"}}
 :edges {[:web :api] {:direction :-> :ref "sub/api.edn"}}
 :boxes {:backend {:name "Backend" :components #{:api} :ref "sub/api.edn"}}}
```

`test/fixtures/refs/sub/api.edn`:
```edn
{:nodes {:handler {:name "Handler" :ref "deep/db.edn"}
         :escape {:name "Escape" :ref "../../secret.edn"}
         :up {:name "Up" :ref "../root.edn"}
         :txt {:name "Text" :ref "../notes.txt"}}}
```

`test/fixtures/refs/sub/deep/db.edn`:
```edn
{:nodes {:table {:name "Table" :ref "../../root.edn"}}}
```

`test/fixtures/refs/notes.txt`:
```
not a graph
```

- [ ] **Step 2: Write the failing resolver tests**

Append to `test/server_test.clj`:

```clojure
(def refs-root (.getCanonicalFile (java.io.File. "test/fixtures/refs")))

(deftest resolve-path-accepts-files-below-the-root
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/root.edn"))
         (serve/resolve-path refs-root "root.edn")))
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/sub/api.edn"))
         (serve/resolve-path refs-root "sub/api.edn")))
  ;; .. is fine while the result stays below the root
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/root.edn"))
         (serve/resolve-path refs-root "sub/../root.edn"))))

(deftest resolve-path-refuses-escapes-absolutes-types-and-missing
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves the served folder"
                        (serve/resolve-path refs-root "../embedded.png")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute"
                        (serve/resolve-path refs-root (.getPath (java.io.File. refs-root "root.edn")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an .edn or .png"
                        (serve/resolve-path refs-root "notes.txt")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                        (serve/resolve-path refs-root "sub/missing.edn")))
  ;; a directory is not a file
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                        (serve/resolve-path refs-root "sub"))))
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `bb test:clj 2>&1 | grep -E "resolve-path|Ran|failures|No such var"`
Expected: a compile error naming `serve/resolve-path` (no such var) — the namespace does not define it yet.

- [ ] **Step 4: Implement `root-dir` and `resolve-path`**

In `server/serve.clj`, after `(def undo-stacks (atom {}))`:

```clojure
(def root-dir (atom nil)) ; canonical File of the served folder (single-file mode)

(def ^:private ref-extensions #{"edn" "png"})

(defn resolve-path
  "The canonical file for the root-relative path `rel` under `root`.
  Refuses (ex-info, message names the problem) an absolute path, a
  result outside `root` after canonicalization (so `..` and symlinks
  cannot escape), an extension other than .edn/.png, and anything that
  is not an existing regular file."
  [root rel]
  (let [rel (str rel)]
    (when (.isAbsolute (io/file rel))
      (throw (ex-info (str "absolute path refused: " rel) {})))
    (let [root-c (.getCanonicalFile (io/file root))
          f (.getCanonicalFile (io/file root-c rel))
          ext (last (str/split (.getName f) #"\."))]
      (when-not (str/starts-with? (.getPath f) (str (.getPath root-c) java.io.File/separator))
        (throw (ex-info (str rel " leaves the served folder") {})))
      (when-not (contains? ref-extensions ext)
        (throw (ex-info (str rel " is not an .edn or .png file") {})))
      (when-not (.isFile f)
        (throw (ex-info (str "no such file: " rel) {})))
      f)))
```

In `-main`, right after `(reset! files {:old old-file :new file})`:

```clojure
    (reset! root-dir (.getParentFile (.getCanonicalFile (io/file file))))
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `bb test:clj 2>&1 | tail -3`
Expected: `0 failures, 0 errors` (the test count grew by 2).

- [ ] **Step 6: Commit**

```bash
git add server/serve.clj test/server_test.clj test/fixtures/refs
git commit -m "feat(server): root folder and resolve-path for refs

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Server — `file` query parameter and `path` in edits

**Files:**
- Modify: `server/serve.clj` — `edit-response` (line ~31), `graph-response-body` (~196), `route` (~232)
- Test: `test/server_test.clj`

**Interfaces:**
- Consumes: `serve/root-dir`, `serve/resolve-path` (Task 1).
- Produces: `GET /api/graph?file=<rel>` → that file's graph JSON with `"path": <rel>`; the root graph carries `"path": <basename>`. `GET /api/version?file=<rel>` → `{"mtime": n}` (`0` when refused). `GET /api/source?file=<rel>` → text or 404. `POST /api/edit` body `{"file":"new","path":<rel>,"ops":[...]}` edits that file. Any `file`/`path` in compare mode → error `refs are not available in compare mode`.

- [ ] **Step 1: Write the failing route tests**

Append to `test/server_test.clj`:

```clojure
(defn- refs-mode!
  "Serve the refs fixture tree in single-file mode."
  []
  (reset! serve/files {:old nil :new "test/fixtures/refs/root.edn"})
  (reset! serve/root-dir refs-root)
  (reset! serve/undo-stacks {}))

(deftest api-graph-file-param-serves-a-file-below-the-root
  (refs-mode!)
  (let [root (json/parse-string (:body (serve/handler {:uri "/api/graph"})))
        sub (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fapi.edn"})))]
    (is (= "root.edn" (get root "path")))
    (is (= "api" (get-in root ["nodes" "api" "name"])))
    (is (= "sub/api.edn" (get sub "path")))
    (is (= "api.edn" (get sub "file")))
    (is (= "Handler" (get-in sub ["nodes" "handler" "attrs" "name"])))
    (is (true? (get sub "editable")))))

(deftest api-graph-file-param-refusals-are-error-payloads
  (refs-mode!)
  (let [escape (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=..%2Fembedded.png"})))
        missing (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fnope.edn"})))
        txt (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=notes.txt"})))]
    (is (clojure.string/includes? (get escape "error") "leaves the served folder"))
    (is (clojure.string/includes? (get missing "error") "no such file"))
    (is (clojure.string/includes? (get txt "error") "not an .edn or .png"))))

(deftest api-graph-file-param-refused-in-compare-mode
  (reset! serve/files {:old "examples/demo.edn" :new "examples/demo-next.edn"})
  (reset! serve/root-dir (.getCanonicalFile (java.io.File. "examples")))
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=demo.edn"})))]
    (is (= "refs are not available in compare mode" (get out "error")))))

(deftest api-version-file-param
  (refs-mode!)
  (let [sub (java.io.File. "test/fixtures/refs/sub/api.edn")
        ok (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=sub%2Fapi.edn"})))
        bad (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=..%2Fembedded.png"})))]
    (is (= (.lastModified sub) (get ok "mtime")))
    (is (= 0 (get bad "mtime")))))

(deftest api-source-file-param
  (refs-mode!)
  (is (= (slurp "test/fixtures/refs/sub/deep/db.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fdeep%2Fdb.edn"}))))
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=..%2Fembedded.png"})))))

(deftest api-edit-path-edits-that-file-with-its-own-undo
  ;; copy the tree so the edit does not touch the fixture
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "refs" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (java.io.File. dir "root.edn")
        sub (java.io.File. dir "sub/api.edn")]
    (.mkdirs (.getParentFile sub))
    (spit root "{:nodes {:api {:ref \"sub/api.edn\"}}}")
    (spit sub "{:nodes {:handler nil}}")
    (reset! serve/files {:old nil :new (.getPath root)})
    (reset! serve/root-dir (.getCanonicalFile dir))
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "add-node" :id "b"}]}))]
      (is (true? (get (json/parse-string (:body resp)) "ok")))
      (is (clojure.string/includes? (slurp sub) ":b nil"))
      (is (= "{:nodes {:api {:ref \"sub/api.edn\"}}}" (slurp root))))
    ;; undo on the sub file only
    (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "undo"}]}))
    (is (= "{:nodes {:handler nil}}" (slurp sub)))
    ;; the root has nothing to undo
    (is (= "nothing to undo"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]})))) "error")))
    ;; an escaping path is refused before anything is written
    (is (clojure.string/includes?
         (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "../x.edn" :ops [{:op "add-node" :id "c"}]})))) "error")
         "leaves the served folder"))))

(deftest api-edit-path-refused-in-compare-mode
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old p :new p})
    (reset! serve/root-dir (.getParentFile (.getCanonicalFile (java.io.File. p))))
    (let [resp (serve/handler (edit-req {:file "new" :path (.getName (java.io.File. p)) :ops [{:op "add-node" :id "b"}]}))]
      (is (= "refs are not available in compare mode" (get (json/parse-string (:body resp)) "error"))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb test:clj 2>&1 | grep -E "^FAIL|^ERROR|Ran|failures" | head -20`
Expected: failures in the six new tests (`path` missing from payloads, `file` parameter ignored, edits landing on the root file).

- [ ] **Step 3: Implement query parsing and the compare-mode check**

In `server/serve.clj`, after `embedded-old` (line ~75) add:

```clojure
(defn- query-param
  "The URL-decoded value of parameter k in a query string, or nil."
  [query-string k]
  (when (some? query-string)
    (some (fn [kv]
            (let [[name v] (str/split kv #"=" 2)]
              (when (= name k)
                (java.net.URLDecoder/decode (or v "") "UTF-8"))))
          (str/split query-string #"&"))))

(defn- compare-mode?
  "True when the server shows two sides: two files, or a compare-export
  PNG as the single file."
  []
  (let [{:keys [old new]} @files]
    (or (some? old) (some? (embedded-old new)))))

(defn- requested-file
  "The file a request's `file` parameter (root-relative) asks for, as
  {:file <canonical File> :rel <the parameter>}; nil without the
  parameter. Throws (message for the error payload) in compare mode or
  when resolve-path refuses it."
  [query-string]
  (when-let [rel (query-param query-string "file")]
    (when (compare-mode?)
      (throw (ex-info "refs are not available in compare mode" {})))
    {:file (resolve-path @root-dir rel) :rel rel}))
```

- [ ] **Step 4: Thread the parameter through the graph route**

Replace `graph-response-body`'s signature and single-file branch:

```clojure
(defn- graph-response-body [query-string]
  (let [body (try
               (let [{:keys [old new]} @files
                     req (requested-file query-string)]
                 (cond
                   (some? req)
                   (let [f (:file req)]
                     (graph-json (read-source (.getPath f)) (.getName f)
                                 {:editable (not (png/png? (.getPath f))) :path (:rel req)}))

                   (some? old)
                   (compare-json (read-source old) (read-source new) old new
                                 (.getName (io/file new))
                                 {:editable (not (png/png? new))
                                  :editable-old (not (png/png? old))})

                   :else
                   (if-let [old-s (embedded-old new)]
                     (let [nm (.getName (io/file new))]
                       (compare-json old-s (read-source new)
                                     (str nm " (old)") (str nm " (new)") nm
                                     {:editable false :editable-old false}))
                     (graph-json (read-source new) (.getName (io/file new))
                                 {:editable (not (png/png? new))
                                  :path (.getName (io/file new))}))))
               (catch Exception e
                 (json/generate-string {:error (ex-message e)})))]
    (when (log/enabled?)
      (when-let [err (get (json/parse-string body) "error")]
        (log/event! "error" {:route "/api/graph" :error err})))
    body))
```

In `route`, change the graph line to `"/api/graph" (json-response (graph-response-body query-string))`.

- [ ] **Step 5: Version and source routes**

Replace the `"/api/version"` clause in `route`:

```clojure
    "/api/version" (json-response
                    (json/generate-string
                     {:mtime (try
                               (if-let [req (requested-file query-string)]
                                 (.lastModified (:file req))
                                 (let [{:keys [old new]} @files
                                       m (.lastModified (io/file new))]
                                   (if (some? old)
                                     (str (.lastModified (io/file old)) "-" m)
                                     m)))
                               ;; a refused file reports a constant: the page
                               ;; reloads once and shows the graph route's error
                               (catch Exception _ 0))}))
```

In the `"/api/source"` clause, change the `body` binding to:

```clojure
          body (try
                 (if-let [req (requested-file query-string)]
                   (read-source (.getPath (:file req)))
                   (if (= which "old")
                     (if (some? old) (read-source old) (embedded-old new))
                     (when (some? new) (read-source new))))
                 (catch Exception _ nil))
```

- [ ] **Step 6: `path` in the edit body**

Replace the start of `edit-response`:

```clojure
(defn- edit-response [{:keys [old new]} {:keys [file ops path]}]
  (try
    (let [path (cond
                 (some? path)
                 (do (when (compare-mode?)
                       (throw (ex-info "refs are not available in compare mode" {})))
                     (.getPath (resolve-path @root-dir path)))
                 (= file "old") old
                 :else new)]
      (cond
```

The rest of the function is unchanged (the `(catch Exception e {:error (ex-message e)})` already turns the throws into error replies). Note `edit-response` is defined before `compare-mode?`/`resolve-path` in the file: move the `(def root-dir ...)`, `resolve-path`, `query-param`, `compare-mode?` and `requested-file` definitions **above** `edit-response` (they only depend on `files`, `embedded-old` and `png`; move `read-source`/`embedded-old` up with them if needed — babashka needs definitions before use).

- [ ] **Step 7: Run the tests to verify they pass**

Run: `bb test:clj 2>&1 | tail -3`
Expected: `0 failures, 0 errors`.

- [ ] **Step 8: Commit**

```bash
git add server/serve.clj test/server_test.clj
git commit -m "feat(server): serve any graph below the root folder via ?file= and edit :path

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Editor — pure navigation helpers and the `f r` chord

**Files:**
- Modify: `src/simpleviz/editor.cljs` (before `;; ---- keyboard chords ----`, and the `chord-table`)
- Test: `test/simpleviz/editor_test.cljs`

**Interfaces:**
- Produces:
  - `(resolve-ref current-path ref)` → root-relative target string, or nil (absolute, empty, or climbs above the root).
  - `(parse-nav query-string)` → `{:file rel-or-nil :trail [rel ...]}`.
  - `(nav-query file trail)` → `""` or `"?file=..&trail=.."`.
  - `(follow-url current-path trail target)` → query string for following a ref.
  - `(crumb-url trail i)` → query string returning to crumb `i`.
  - `(ref-of sel)` → the non-blank string `:ref` of a selection's attrs, or nil.
  - chord `f r` → action `"follow-ref"` for node, edge and box.

- [ ] **Step 1: Write the failing tests**

Add to the `:refer` list in `test/simpleviz/editor_test.cljs`: `resolve-ref parse-nav nav-query follow-url crumb-url ref-of`. Append:

```clojure
(test "resolve-ref joins a ref onto the directory of the current file"
  (fn []
    (assert/equal (resolve-ref "root.edn" "sub/api.edn") "sub/api.edn")
    (assert/equal (resolve-ref "sub/api.edn" "deep/db.edn") "sub/deep/db.edn")
    (assert/equal (resolve-ref "sub/api.edn" "../root.edn") "root.edn")
    (assert/equal (resolve-ref "sub/deep/db.edn" "../../root.edn") "root.edn")
    (assert/equal (resolve-ref "root.edn" "./sub//api.edn") "sub/api.edn")
    ;; climbing above the root, absolute and empty refs resolve to nothing
    (assert/ok (nil? (resolve-ref "root.edn" "../x.edn")))
    (assert/ok (nil? (resolve-ref "sub/api.edn" "../../x.edn")))
    (assert/ok (nil? (resolve-ref "root.edn" "/etc/passwd")))
    (assert/ok (nil? (resolve-ref "root.edn" "C:/x.edn")))
    (assert/ok (nil? (resolve-ref "root.edn" "  ")))
    (assert/ok (nil? (resolve-ref "root.edn" nil)))))

(test "nav-query and parse-nav round-trip file and trail, commas included"
  (fn []
    (assert/equal (nav-query nil []) "")
    (assert/equal (nav-query "sub/api.edn" []) "?file=sub%2Fapi.edn")
    (assert/deepEqual (parse-nav "") {:file nil :trail []})
    (assert/deepEqual (parse-nav "?file=sub%2Fapi.edn") {:file "sub/api.edn" :trail []})
    (let [q (nav-query "sub/deep/db.edn" ["root.edn" "a,b.edn"])]
      (assert/deepEqual (parse-nav q) {:file "sub/deep/db.edn" :trail ["root.edn" "a,b.edn"]}))))

(test "follow-url appends the current file to the trail; crumb-url truncates it"
  (fn []
    (assert/deepEqual (parse-nav (follow-url "root.edn" [] "sub/api.edn"))
                      {:file "sub/api.edn" :trail ["root.edn"]})
    (assert/deepEqual (parse-nav (follow-url "sub/api.edn" ["root.edn"] "sub/deep/db.edn"))
                      {:file "sub/deep/db.edn" :trail ["root.edn" "sub/api.edn"]})
    (assert/deepEqual (parse-nav (crumb-url ["root.edn" "sub/api.edn"] 0))
                      {:file "root.edn" :trail []})
    (assert/deepEqual (parse-nav (crumb-url ["root.edn" "sub/api.edn"] 1))
                      {:file "sub/api.edn" :trail ["root.edn"]})))

(test "ref-of yields the selection's string :ref, else nil"
  (fn []
    (assert/equal (ref-of {:kind "node" :attrs {:ref "sub/api.edn"}}) "sub/api.edn")
    (assert/ok (nil? (ref-of {:kind "node" :attrs {:ref "  "}})))
    (assert/ok (nil? (ref-of {:kind "node" :attrs {:ref 3}})))
    (assert/ok (nil? (ref-of {:kind "node" :attrs {}})))
    (assert/ok (nil? (ref-of {:kind "edge"})))))

(test "chord f r follows a ref for every selection kind"
  (fn []
    (assert/ok (chord-group? "f"))
    (assert/equal (chord-action "node" "f" "r") "follow-ref")
    (assert/equal (chord-action "edge" "f" "r") "follow-ref")
    (assert/equal (chord-action "box" "f" "r") "follow-ref")
    (assert/ok (nil? (chord-action nil "f" "r")))
    (assert/equal (chord-for "node" "follow-ref") "f r")))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx squint compile >/dev/null 2>&1; node --test public/js/simpleviz/editor_test.mjs 2>&1 | grep -E "SyntaxError|^ℹ (pass|fail)"`
Expected: `SyntaxError: The requested module './editor.mjs' does not provide an export named 'resolve_ref'`.

- [ ] **Step 3: Implement the helpers and the chord**

In `src/simpleviz/editor.cljs`, before `;; ---- keyboard chords ----`:

```clojure
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

(defn parse-nav
  "The page's navigation state from its query string: {:file
  root-relative path or nil (the root file) :trail [paths visited
  before it]}. Each trail entry is URL-encoded on its own inside the
  parameter, so commas in file names survive."
  [query-string]
  (let [p (js/URLSearchParams. (str (if (nil? query-string) "" query-string)))
        file (.get p "file")
        trail (.get p "trail")]
    {:file (if (or (nil? file) (= file "")) nil file)
     :trail (if (or (nil? trail) (= trail ""))
              []
              (mapv js/decodeURIComponent (.split trail ",")))}))

(defn nav-query
  "The query string (\"\" or \"?file=..&trail=..\") for showing `file`
  (nil = root) with `trail` behind it — the inverse of parse-nav."
  [file trail]
  (let [p (js/URLSearchParams.)]
    (when (some? file) (.set p "file" file))
    (when (seq trail) (.set p "trail" (.join (mapv js/encodeURIComponent trail) ",")))
    (let [s (.toString p)]
      (if (= s "") "" (str "?" s)))))

(defn follow-url
  "Query string for following a ref from `current-path` to `target`:
  the current file joins the end of the trail."
  [current-path trail target]
  (nav-query target (conj (vec trail) current-path)))

(defn crumb-url
  "Query string for going back to trail entry i: it becomes the file
  shown, the entries before it stay the trail."
  [trail i]
  (nav-query (nth trail i) (vec (.slice trail 0 i))))

(defn ref-of
  "The selection's :ref when it is a non-blank string, else nil."
  [sel]
  (let [r (:ref (:attrs sel))]
    (when (and (string? r) (not= (.trim r) "")) r)))
```

Add to `chord-table`, after the `["r" "b" ...]` row:

```clojure
   ["f" "r" {"node" ["follow-ref" "follow ref"] "edge" ["follow-ref" "follow ref"] "box" ["follow-ref" "follow ref"]}]
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx squint compile >/dev/null 2>&1; node --test public/js/simpleviz/editor_test.mjs 2>&1 | grep -E "^ℹ (pass|fail)|not ok"`
Expected: `fail 0`, pass count up by 5.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/editor.cljs test/simpleviz/editor_test.cljs
git commit -m "feat(editor): ref resolution, nav query helpers and the f r chord

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Page — navigation state from the URL, file parameter on every fetch

**Files:**
- Modify: `src/simpleviz/app.cljs` — `state` def (line ~17), `reload!` (~812), `tick` (~845), `post-edit!` (~860), `fetch-source` (~963), boot lines at the end (~1078)

**Interfaces:**
- Consumes: `editor/parse-nav` (Task 3); server `file`/`path` parameters (Task 2).
- Produces: `(:nav @state)` = `{:file .. :trail ..}`; `(file-query)` → `""` or `"?file=.."`; `load-nav!` (async) re-reads the URL, drops per-file state and loads; a `popstate` listener.

No unit tests reach this DOM code; Task 7's headless run verifies it. Keep `bb build` green after each step.

- [ ] **Step 1: Navigation state and the query helper**

In the `state` atom add, after `:help false :disconnected false`:

```clojure
                  :nav (editor/parse-nav js/location.search) :nav-error nil
```

After `(def last-mtime (atom nil))` add:

```clojure
(defn- file-query
  "\"?file=<rel>\" for the graph the page is showing, \"\" for the root."
  []
  (let [f (:file (:nav @state))]
    (if (some? f) (str "?file=" (js/encodeURIComponent f)) "")))
```

- [ ] **Step 2: Fetch with the parameter**

- In `reload!`: `(js/fetch "/api/graph")` → `(js/fetch (str "/api/graph" (file-query)))`.
- In `tick`: `(js/fetch "/api/version")` → `(js/fetch (str "/api/version" (file-query)))`.
- In `fetch-source`, replace the URL expression:

```clojure
    (let [resp (js-await (js/fetch (str "/api/source"
                                        (if (some? which)
                                          (str "?which=" which)
                                          (file-query)))))]
```

- In `post-edit!`, the body becomes:

```clojure
                                  :body (js/JSON.stringify
                                         (let [body (editor/edit-body (:edit-target @state) ops)
                                               f (:file (:nav @state))]
                                           (if (some? f) (assoc body :path f) body)))
```

- [ ] **Step 3: `load-nav!` and the `popstate` listener**

After `tick` (it calls `tick`, so it must come after it) add:

```clojure
(defn- ^:async load-nav!
  "The URL changed (follow, crumb, browser back): re-read the
  navigation state, drop everything that belongs to the previous file —
  selection, edits in progress, collapsed boxes, cached layouts, the
  graph itself — and load the file the URL now names."
  []
  (swap! state assoc :nav (editor/parse-nav js/location.search)
         :nav-error nil :error nil :graph nil :scene nil :layout nil
         :selected nil :editing nil :edit-error nil :pick nil :pick-hint nil
         :chord nil :id-entry nil :pending-focus nil :collapsed-boxes #{})
  (.clear layout-cache)
  (reset! last-mtime nil)
  (js-await (tick)))

(js/window.addEventListener "popstate" (fn [_] (load-nav!)))
```

`layout-cache` and `demote-layout-cache!` are defined near line 52, before this point, so `.clear` on the `js/Map` is in scope.

- [ ] **Step 4: Build and smoke-check**

Run: `bb build 2>&1 | tail -1 && bb test 2>&1 | grep -E "^Ran|failures|^ℹ (pass|fail)"`
Expected: compile OK, all tests green (nothing new is tested here; this guards against squint compile errors).

Then a quick manual check that the root still loads: `bb serve test/fixtures/refs/root.edn --port 7465 &` and `curl -s 'localhost:7465/api/graph?file=sub%2Fapi.edn' | head -c 200` shows `"path":"sub/api.edn"`; stop the server afterwards (`kill %1`).

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/app.cljs
git commit -m "feat(page): navigation state from the URL; fetches carry the shown file

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Page — the follow-ref action

**Files:**
- Modify: `src/simpleviz/app.cljs` — `action-spec` (~282), `toolbar-actions` (~311), `start-action!` (~318), `banner-view` (~441), `on-select` (~32)

**Interfaces:**
- Consumes: `editor/ref-of`, `editor/resolve-ref`, `editor/follow-url` (Task 3); `load-nav!`, `(:nav @state)` (Task 4).
- Produces: `navigate!` (async, `[query]`): pushes the query onto the history and calls `load-nav!`; `follow-ref!` (`[ref]`); action `"follow-ref"` in `action-spec` with a `:go` key; `:nav-error` banner.

- [ ] **Step 1: `navigate!` and `follow-ref!`**

After `load-nav!` (Task 4) add:

```clojure
(defn- ^:async navigate!
  "Show another graph of the served folder: push its query string
  onto the browser history (so back returns here) and load it."
  [query]
  (js/history.pushState nil "" (str js/location.pathname query))
  (js-await (load-nav!)))

(defn- follow-ref!
  "Follow the selection's ref: resolve it against the file shown and
  navigate there, the current file joining the trail. A ref that
  climbs above the served folder is refused here with a banner; one
  the server refuses (missing, wrong type) shows as the graph error
  after navigating, with the trail intact to go back."
  [ref]
  (let [{:keys [trail]} (:nav @state)
        current (:path (:graph @state))
        target (editor/resolve-ref current ref)]
    (if (nil? target)
      (swap! state assoc :nav-error (str "ref " (pr-str ref) " leaves the served folder"))
      (navigate! (editor/follow-url current trail target)))))
```

`navigate!` and `follow-ref!` are referenced by `action-spec`/`start-action!` which sit earlier in the file: add `follow-ref!` to the existing `(declare relayout! post-edit! delete! current-edit-target-editable?)` line (line ~47) — `(declare relayout! post-edit! delete! current-edit-target-editable? follow-ref! navigate!)`.

- [ ] **Step 2: The action**

In `action-spec`'s `case`, before the `"remove-from-box"` clause:

```clojure
        ;; only for a selection with a string :ref, in single-file mode
        "follow-ref" (when-let [r (editor/ref-of sel)]
                       (when (nil? (:compare (:graph @state)))
                         {:label "follow ref" :go r}))
```

In `toolbar-actions`, append `"follow-ref"` to all three vectors:

```clojure
(def ^:private toolbar-actions
  {"edge" [["retarget" "source"] ["retarget" "target"] "follow-ref"]
   "node" ["add-edge" "add-to-box" "remove-from-box" "new-connected-node" "new-box" "follow-ref"]
   "box" ["add-edge" "add-node-member" "add-box-member" "remove-node-member"
          "new-node-in-box" "new-box" "follow-ref"]})
```

In `start-action!`, destructure `go` too and add a clause:

```clojure
  (let [{:keys [pick hint id-entry post go]} (action-spec sel tgt action)]
    (cond
      (some? pick) (start-pick! pick hint)
      (some? id-entry) (start-id-entry! id-entry)
      (some? post) (post-edit! post)
      (some? go) (follow-ref! go))))
```

The chord path needs no change: `run-chord-action!` falls through to `start-action!` for unknown actions.

- [ ] **Step 3: The banner and clearing it**

In `banner-view`, add `nav-error` to the destructured keys and a clause after the `edit-error` one:

```clojure
    (some? nav-error)
    [:div {:id "banner" :class "error"
           :on-click (fn [_] (swap! state assoc :nav-error nil))}
     nav-error]
```

In `on-select`, also clear it: `(swap! state assoc :selected payload :editing nil :id-entry nil :chord nil :nav-error nil)`.

- [ ] **Step 4: Build, test, manual check**

Run: `bb test 2>&1 | grep -E "^Ran|failures|^ℹ (pass|fail)"` — all green.

Manual: `bb serve test/fixtures/refs/root.edn --port 7465 &`, open http://localhost:7465, click the API node: the toolbar shows "follow ref f r". Press `f` then `r`: the URL becomes `/?file=sub%2Fapi.edn&trail=root.edn` and the sub graph shows. Browser back returns to the root. Click the Escape node in the sub graph and press `f r`: the banner reads `ref "../../secret.edn" leaves the served folder`. Stop the server.

- [ ] **Step 5: Commit**

```bash
git add src/simpleviz/app.cljs
git commit -m "feat(page): follow ref action (f r) navigates to the referenced graph

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Page — the trail bar

**Files:**
- Modify: `src/simpleviz/app.cljs` — new `trail-view` next to `legend-view` (~530), rendered in `app-view` (~656)
- Modify: `public/style.css` — after the `#diff-legend` rules (~214)

**Interfaces:**
- Consumes: `(:nav @state)`, `(:path (:graph @state))`, `navigate!`, `editor/crumb-url`.

- [ ] **Step 1: The view**

After `legend-view` add:

```clojure
(defn- trail-view
  "The files followed to reach the one shown, root first, each a
  button back to it; the current file last as plain text. Shown only
  once a ref has been followed (or the page loaded with a trail)."
  [st]
  (let [trail (:trail (:nav st))]
    (when (seq trail)
      (into [:div {:id "trail"}]
            (concat
             (apply concat
                    (map-indexed
                     (fn [i f]
                       [[:button {:class "trail-crumb" :type "button" :key (str "c" i)
                                  :title (str "back to " f)
                                  :on-click (fn [e]
                                              (.stopPropagation e)
                                              (navigate! (editor/crumb-url trail i)))}
                         f]
                        [:span {:class "trail-sep" :key (str "s" i)} "›"]])
                     trail))
             [[:span {:class "trail-current" :key "cur"}
               (or (:path (:graph st)) (:file (:nav st)))]])))))
```

In `app-view`, after `(when (some? (:graph st)) (legend-view st))` add `(trail-view st)`.

- [ ] **Step 2: The styling**

After the `#diff-legend` block in `public/style.css`:

```css
#trail { position: fixed; top: 12px; left: 50%; transform: translateX(-50%);
         z-index: 6; max-width: 80vw; display: flex; flex-wrap: wrap;
         align-items: center; gap: 4px;
         background: var(--panel); border: 1px solid var(--panel-border);
         border-radius: 10px; padding: 5px 10px; font-size: 12px;
         box-shadow: 0 2px 8px var(--shadow);
         font-family: ui-monospace, "Cascadia Mono", monospace; }
.trail-crumb { border: none; background: none; color: var(--accent);
               cursor: pointer; font: inherit; padding: 2px 5px; border-radius: 4px; }
.trail-crumb:hover { background: var(--hover); }
.trail-sep { color: var(--text-dim); }
.trail-current { color: var(--text-strong); font-weight: 600; padding: 2px 5px; }
```

- [ ] **Step 3: Build and manual check**

Run: `bb build 2>&1 | tail -1`. Serve the fixture as in Task 5, follow API → Handler (two refs deep): the bar reads `root.edn › sub/api.edn › sub/deep/db.edn`. Click `root.edn`: the root graph shows and the bar disappears (empty trail). Follow again, reload the page: the bar is still there. Stop the server.

- [ ] **Step 4: Commit**

```bash
git add src/simpleviz/app.cljs public/style.css
git commit -m "feat(page): trail bar of followed graphs, click to go back

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Docs, help text, and the scripted end-to-end check

**Files:**
- Modify: `README.md` (data format block ~line 50; Editing section ~146; chord table ~180), `plugins/simpleviz/skills/simpleviz/SKILL.md` (format rules ~31, editing paragraph ~75), `docs/development.md` (after "Working on the code"), `src/simpleviz/app.cljs` help panel (~624-634)
- Create: `dev/cdp.mjs`

- [ ] **Step 1: README**

In the data format block, add a line to the `:api` node after `:lang "clojure"`:

```
                   :ref "sub/api.edn"}   ; another graph file, relative to this one — "follow ref" opens it
```

(and move the closing brace accordingly).

In the Editing section, after the paragraph that ends "...a name prompt too)." bullets, add a paragraph before "Every toolbar action also has a two-key chord":

```markdown
**Following refs.** A `:ref` attribute on a node, box or edge names
another graph file by a path relative to the file it is in. With such an
element selected, "follow ref" (`f r`) opens that graph in place; a trail
at the top center lists the files followed, and clicking one goes back
there (the browser's back button works too). Refs may use `..` but never
leave the folder of the file the server was started with, and must point
at an `.edn` or exported `.png`; anything else shows an error. Following
is not available in compare mode.
```

In the chord table add a row before `| \`?\` |`:

```markdown
| `f r` | node, edge, box | follow the element's `:ref` (opens that graph) |
```

- [ ] **Step 2: Plugin skill**

In `SKILL.md`'s rules list (after the `:nodes` is a MAP bullet) add:

```markdown
- `:ref "sub/other.edn"` on a node, box or edge links another graph file, relative to the file it is in and never above the folder of the served root file; the viewer's "follow ref" (`f r`) opens it and shows a clickable trail back. Not available in compare mode.
```

In the editing paragraph (line ~75), after `"new box".` for boxes, add: ` Any element with a string \`:ref\` also offers "follow ref" (\`f r\`).`

- [ ] **Step 3: Developer docs**

In `docs/development.md`, at the end of "Working on the code":

```markdown
The API serves one root file (or a compare pair). In single-file mode the
routes `/api/graph`, `/api/version` and `/api/source` take `?file=<path>`
and `/api/edit` a `"path"` in its body, a path relative to the root file's
folder; `serve/resolve-path` refuses anything above that folder, non
`.edn`/`.png` targets and missing files. The page keeps the shown file and
the trail of followed refs in its query string (`?file=..&trail=..`, see
`editor/parse-nav`).
```

- [ ] **Step 4: In-app help**

In the help panel's "Edit" paragraph (app.cljs ~630), before `"In the inspector, click a value`, append to the previous string: ` A :ref attribute naming another graph file (relative path) makes "follow ref" open it; the trail at the top leads back.` In the "Keys" string, after `r b take the selected node out of its box` add ` · f r follow the selection's :ref`.

- [ ] **Step 5: The headless driver**

Create `dev/cdp.mjs`:

```js
// Drive a headless Chromium over the DevTools protocol (Node >= 22, global
// WebSocket). Start Chromium first:
//   chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 about:blank &
// then: node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7465/","wait":3000},{"screenshot":"/tmp/s.png"}]'
// Actions: navigate/wait, wait, click [x y], eval "js", type "text",
// key "Enter"|"f"|..., screenshot "path".
const actions = JSON.parse(process.argv[2]);
const targets = await (await fetch("http://127.0.0.1:9222/json")).json();
const page = targets.find(t => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const sleep = ms => new Promise(r => setTimeout(r, ms));
await send("Page.enable"); await send("Runtime.enable");
for (const a of actions) {
  if (a.navigate) { await send("Page.navigate", { url: a.navigate }); await sleep(a.wait ?? 1500); }
  else if (a.wait) await sleep(a.wait);
  else if (a.click) { const [x, y] = a.click;
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"])
      await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
    await sleep(300); }
  else if (a.eval) { const r = await send("Runtime.evaluate", { expression: a.eval, returnByValue: true }); console.log(JSON.stringify(r.result?.result?.value ?? r.result)); }
  else if (a.type) { await send("Input.insertText", { text: a.type }); await sleep(200); }
  else if (a.key) { for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", { type, key: a.key, code: a.key, windowsVirtualKeyCode: a.key === "Enter" ? 13 : 0, modifiers: a.shift ? 8 : 0 }); await sleep(300); }
  else if (a.screenshot) { const r = await send("Page.captureScreenshot", { format: "png" });
    const fs = await import("node:fs"); fs.writeFileSync(a.screenshot, Buffer.from(r.result.data, "base64")); console.log("saved", a.screenshot); }
}
ws.close();
```

- [ ] **Step 6: Run the end-to-end check**

```bash
bb build
(bb serve test/fixtures/refs/root.edn --port 7465 &) ; sleep 3
(chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 --no-first-run --disable-gpu about:blank &) ; sleep 4
node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7465/","wait":3000},{"screenshot":"/tmp/refs-1.png"}]'
```

Look at `/tmp/refs-1.png` to find the API node's pixel position (the screenshot is the full viewport, `[innerWidth, innerHeight]` from `{"eval":"[innerWidth, innerHeight]"}` gives the scale if the image was resized), then:

```bash
node dev/cdp.mjs '[{"click":[X,Y]},{"wait":500},
 {"eval":"document.querySelector(\"#details h2\")?.textContent"},
 {"eval":"[...document.querySelectorAll(\"#selection-toolbar button\")].map(b=>b.textContent)"},
 {"key":"f"},{"key":"r"},{"wait":2500},
 {"eval":"location.search"},
 {"eval":"document.querySelector(\"#trail\")?.textContent"},
 {"eval":"document.title"},
 {"screenshot":"/tmp/refs-2.png"},
 {"eval":"document.querySelector(\".trail-crumb\").click()"},{"wait":2500},
 {"eval":"location.search"},
 {"eval":"!!document.querySelector(\"#trail\")"},
 {"eval":"document.title"}]'
```

Expected output, in order: `"API"`; a button list containing `"follow reff r"`; `"?file=sub%2Fapi.edn&trail=root.edn"`; `"root.edn›sub/api.edn"`; a title containing `api.edn`; the screenshot; then `""`, `false`, a title containing `root.edn`.

Stop both: `pkill -f "remote-debugging-port=9222"; pkill -f "port 7465"`.

- [ ] **Step 7: Full suite and commit**

Run: `bb test 2>&1 | grep -E "^Ran|failures|^ℹ (pass|fail)"` — all green.

```bash
git add README.md plugins/simpleviz/skills/simpleviz/SKILL.md docs/development.md src/simpleviz/app.cljs dev/cdp.mjs
git commit -m "docs: refs between graphs — README, skill, dev docs, in-app help; headless driver

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review against the spec

- Format (`:ref` string, relative, `..` below root, `.edn`/`.png`, non-string ignored): Tasks 1 (server rules), 3 (`resolve-ref`, `ref-of`), 7 (docs). ✓
- Server: `root-dir`, `resolve-path` (Task 1); `file` on graph/version/source, `path` on edit, `:path` in the payload, compare-mode refusal, version returns `0` when refused, source 404 (Task 2). ✓
- Page URL state, load/popstate, fetches with the parameter (Task 4); pure helpers (Task 3); follow action, chord, escape banner, per-file reset (Task 5); trail bar (Task 6); help text (Task 7). ✓
- Errors table: page-side climb → `:nav-error` banner (Task 5); server refusals → `{"error"}` payload shown by the existing `:error` banner (Task 2, Task 4 clears `:error` on navigation so the new one shows); compare-mode refusals (Task 2); non-string ref → no button (`ref-of`). ✓
- Testing: JS tests (Task 3), server tests (Tasks 1–2), headless run (Task 7). ✓
- Docs: README, SKILL.md, development.md (Task 7). ✓
- Names used consistently: `resolve-path`, `root-dir`, `requested-file`, `compare-mode?`, `query-param` (server); `resolve-ref`, `parse-nav`, `nav-query`, `follow-url`, `crumb-url`, `ref-of` (editor); `file-query`, `load-nav!`, `navigate!`, `follow-ref!`, `trail-view`, `:nav`, `:nav-error`, `:go` (page). ✓
