# Fork, promote, and compare by suffix — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `simpleviz fork graph.edn next` copies a graph and its ref closure to `-next` siblings, `simpleviz graph.edn next` compares each file against its fork with refs working, and `simpleviz promote graph.edn next` moves the forks back over their bases. Two-file compare is removed.

**Architecture:** A new babashka namespace `server/fork.clj` computes the ref closure (server-side port of the page's `resolve-ref`, refs collected from `graph/normalize` output) and does the copies/moves. `server/serve.clj` replaces its global old/new pair with `{:root :suffix}` state and a per-request `sides` function that pairs any root-relative path with its fork, so every route that takes `?file=` works in compare mode. The page enables "follow ref" and the trail whenever the payload carries `:path`.

**Tech Stack:** babashka (Clojure) server, Squint ClojureScript page, bash launcher in `install.sh`, `clojure.test` + `node --test`.

**Spec:** `docs/superpowers/specs/2026-09-19-fork-promote-design.md`

## Global Constraints

- Fork name rule: `<dir>/<stem>-<suffix>.<ext>` (suffix before the extension; a file without an extension gets `-<suffix>` appended).
- Suffix charset: `^[A-Za-z0-9_.-]+$`; a suffix ending in `.edn`/`.png` (case-insensitive) is the removed two-file form and gets the replacement message.
- Forks are byte-for-byte copies; refs are never rewritten.
- Error messages verbatim: `no <fork> — create it with: simpleviz fork <rel> <suffix>`, `no <rel> (only <fork>)`, `refs are not available in an embedded compare`, `<path> already exists`, `nothing to promote`, `invalid suffix: <s>`.
- Run server tests with `bb -e "(require '[clojure.test :as t] 'server-test 'fork-test) (t/run-tests 'server-test 'fork-test)"`; the full suite with `bb test` (needs `bb build` once).
- Every commit message ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File structure

| File | Responsibility |
|---|---|
| `server/serve.clj` (modify) | CLI parsing with suffix, `fork-name`, `{:root :suffix}` state, `sides`, routes pairing per request |
| `server/fork.clj` (create) | ref closure, `fork!`, `promote!`, `-main` for the bb tasks |
| `test/fork_test.clj` (create) | closure/fork/promote tests on temp dirs |
| `test/server_test.clj` (modify) | new state shape, suffix-mode route tests |
| `bb.edn` (modify) | `fork`/`promote` tasks, `serve` doc, `dev` default, release `bb.edn`, `test:clj` requires |
| `install.sh` (modify) | launcher: suffix serve, `fork`, `promote`, usage, two-file message |
| `src/simpleviz/app.cljs` (modify) | gating by `:path`, top-center column, help text |
| `public/style.css` (modify) | `#top-center` column |
| `examples/api/internals-next.edn` (create) | fork of the demo's referenced graph |
| `README.md`, `plugins/simpleviz/skills/simpleviz/SKILL.md`, `docs/development.md` (modify) | docs |

---

### Task 1: `fork-name` and the suffix in `serve/parse-args`

**Files:**
- Modify: `server/serve.clj` (top of file after `ref-extensions`; `usage`; `parse-args`)
- Test: `test/server_test.clj`

**Interfaces:**
- Produces: `(serve/fork-name rel suffix)` → string; `serve/suffix-re`; `(serve/parse-args args)` → `{:file f :suffix s-or-nil :port n :debug b}` or `{:error msg}`.

- [ ] **Step 1: Write the failing tests** — in `test/server_test.clj`, replace `parse-args-two-files-enables-compare` and the third assertion of `parse-args-accepts-debug-flag` with:

```clojure
(deftest parse-args-suffix-enables-compare
  (is (= {:file "a.edn" :suffix "next" :port 7373 :debug false}
         (serve/parse-args ["a.edn" "next"])))
  (is (= {:file "a.edn" :suffix "v2" :port 9000 :debug false}
         (serve/parse-args ["a.edn" "v2" "--port" "9000"])))
  (is (= {:file "a.edn" :suffix nil :port 7373 :debug false}
         (serve/parse-args ["a.edn"]))))

(deftest parse-args-rejects-bad-suffixes
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "ne/xt"])) "invalid suffix: ne/xt"))
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "b.edn"])) "two-file compare was replaced"))
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "b.PNG"])) "two-file compare was replaced")))

(deftest fork-name-puts-the-suffix-before-the-extension
  (is (= "demo-next.edn" (serve/fork-name "demo.edn" "next")))
  (is (= "api/internals-next.edn" (serve/fork-name "api/internals.edn" "next")))
  (is (= "examples/x-v2.png" (serve/fork-name "examples/x.png" "v2")))
  (is (= "a.b/noext-next" (serve/fork-name "a.b/noext" "next"))))
```

and in `parse-args-accepts-debug-flag` change the third `is` to
`(is (= {:file "a.edn" :suffix "next" :port 7373 :debug true} (serve/parse-args ["a.edn" "next" "--debug"])))`.
Also update the two single-file assertions in that file (`parse-args-uses-default-port`, first two of `parse-args-accepts-debug-flag`, `parse-args-accepts-port-flag-and-alias`) to include `:suffix nil`.

- [ ] **Step 2: Run to verify they fail**

Run: `bb -e "(require '[clojure.test :as t] 'server-test) (t/run-tests 'server-test)"`
Expected: failures in the four tests above (`fork-name` unresolved, maps differ).

- [ ] **Step 3: Implement** — in `server/serve.clj` after `ref-extensions`:

```clojure
(def suffix-re #"^[A-Za-z0-9_.-]+$")

(defn fork-name
  "The fork of path `rel` for `suffix`: the suffix goes before the
  extension (\"a/b.edn\" \"next\" -> \"a/b-next.edn\"); a name without
  an extension gets \"-suffix\" appended."
  [rel suffix]
  (let [dot (str/last-index-of rel ".")
        slash (or (str/last-index-of rel "/") -1)]
    (if (and (some? dot) (> dot slash))
      (str (subs rel 0 dot) "-" suffix (subs rel dot))
      (str rel "-" suffix))))
```

Replace `usage`:

```clojure
(def ^:private usage
  (str "usage: bb serve <graph.edn|export.png> [<suffix>] [--port N] [--debug]\n"
       "  with a suffix, graph.edn is compared against its fork graph-<suffix>.edn\n"
       "  (create the fork with: bb fork graph.edn <suffix>); refs follow into\n"
       "  the same comparison of the referenced file and its fork\n"
       "  a PNG exported from simpleviz serves its embedded EDN;\n"
       "  a compare-mode export re-opens as the comparison\n"
       "  --debug writes a per-run log of edits and errors to " log/dir-hint
       "\n  (default port " default-port ")"))
```

Replace the body of `parse-args`'s `let`:

```clojure
    (let [debug (boolean (some #{"--debug"} args))
          {:keys [args opts]} (cli/parse-args (remove #{"--debug"} args) cli-spec)
          [f1 suffix & extra] args
          suffix (some-> suffix str)
          port (get opts :port default-port)]
      (cond
        (nil? f1) {:error usage}
        (seq extra) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        (and (some? suffix) (re-find #"(?i)\.(edn|png)$" suffix))
        {:error (str "two-file compare was replaced: bb fork " f1 " <suffix>, then bb serve " f1 " <suffix>\n" usage)}
        (and (some? suffix) (nil? (re-matches suffix-re suffix)))
        {:error (str "invalid suffix: " suffix "\n" usage)}
        :else {:file f1 :suffix suffix :port port :debug debug}))
```

Update the docstring to `{:file f :suffix s-or-nil :port n :debug b}`.

- [ ] **Step 4: Run tests** — same command. Expected: the four tests pass. (Other compare tests still pass because `-main`/routes are untouched so far.)

- [ ] **Step 5: Commit**

```bash
git add server/serve.clj test/server_test.clj
git commit -m "feat(serve): suffix argument and fork-name

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `server/fork.clj` — `resolve-ref`, `ref-targets`, `closure`

**Files:**
- Create: `server/fork.clj`
- Create: `test/fork_test.clj`
- Modify: `bb.edn` (`test:clj` requires and run list)

**Interfaces:**
- Consumes: `serve/resolve-path`, `serve/read-source` (make it public: change `(defn- read-source` to `(defn read-source` in `server/serve.clj`), `graph/normalize`.
- Produces: `(fork/resolve-ref current ref)` → root-relative string or nil; `(fork/ref-targets text)` → vector of ref strings; `(fork/closure start read-rel warn!)` → vector of root-relative paths, `start` first.

- [ ] **Step 1: Write the failing tests** — create `test/fork_test.clj`:

```clojure
(ns fork-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [fork]
            [serve]))

(deftest resolve-ref-collapses-segments-and-refuses-escapes
  (is (= "sub/api.edn" (fork/resolve-ref "root.edn" "sub/api.edn")))
  (is (= "sub/deep/db.edn" (fork/resolve-ref "sub/api.edn" "deep/db.edn")))
  (is (= "root.edn" (fork/resolve-ref "sub/api.edn" "../root.edn")))
  (is (= "sub/x.edn" (fork/resolve-ref "sub/api.edn" "./x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "../x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "/etc/x.edn")))
  (is (nil? (fork/resolve-ref "root.edn" "  ")))
  (is (nil? (fork/resolve-ref "root.edn" nil))))

(deftest ref-targets-collects-string-refs-on-every-element-kind
  (is (= #{"sub/api.edn" "x.edn" "y.edn"}
         (set (fork/ref-targets
               (str "{:nodes {:a {:ref \"sub/api.edn\"} :b {:ref 7} :c {:ref \"\"}}"
                    " :edges {[:a :b] {:ref \"x.edn\"}}"
                    " :boxes {:z {:components #{:a} :ref \"y.edn\"}}}")))))
  ;; vector forms work too
  (is (= ["v.edn"] (fork/ref-targets "{:nodes {:a nil} :boxes [{:name \"g\" :components #{:a} :ref \"v.edn\"}]}")))
  (is (thrown? Exception (fork/ref-targets "{:unclosed"))))

(defn- tree!
  "Write {rel text} under a fresh temp dir; returns its canonical File."
  [files]
  (let [dir (.getCanonicalFile (.toFile (fs/create-temp-dir {:prefix "fork-test"})))]
    (doseq [[rel text] files]
      (let [f (io/file dir rel)]
        (.mkdirs (.getParentFile f))
        (spit f text)))
    dir))

(defn- reader [root] (fn [rel] (serve/read-source (.getPath (serve/resolve-path root rel)))))

(deftest closure-walks-refs-depth-first-once-and-skips-bad-ones
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"} :b {:ref \"sub/api.edn\"}}}"
                     "sub/api.edn" "{:nodes {:h {:ref \"deep/db.edn\"} :up {:ref \"../root.edn\"} :out {:ref \"../../x.edn\"} :gone {:ref \"nope.edn\"} :txt {:ref \"../notes.txt\"}}}"
                     "sub/deep/db.edn" "{:nodes {:t {:ref \"../../root.edn\"}}}"
                     "notes.txt" "not a graph"})
        warnings (atom [])
        out (fork/closure "root.edn" (reader root) #(swap! warnings conj %))]
    (is (= "root.edn" (first out)))
    (is (= #{"root.edn" "sub/api.edn" "sub/deep/db.edn"} (set out)))
    (is (= 3 (count out)))
    (is (= 3 (count @warnings)))
    (is (some #(clojure.string/includes? % "\"../../x.edn\" leaves the root folder") @warnings))
    (is (some #(clojure.string/includes? % "\"nope.edn\"") @warnings))
    (is (some #(clojure.string/includes? % "\"../notes.txt\"") @warnings))
    (fs/delete-tree root)))

(deftest closure-names-the-file-on-a-parse-error
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"bad.edn\"}}}" "bad.edn" "{:unclosed"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^bad\.edn: "
                          (fork/closure "root.edn" (reader root) (fn [_]))))
    (fs/delete-tree root)))
```

- [ ] **Step 2: Run to verify they fail**

Run: `bb -e "(require '[clojure.test :as t] 'fork-test) (t/run-tests 'fork-test)"`
Expected: fails to load (`fork` namespace not found).

- [ ] **Step 3: Implement** — make `read-source` public in `server/serve.clj` (`defn` instead of `defn-`), then create `server/fork.clj`:

```clojure
(ns fork
  "simpleviz fork / promote: copy a graph file and every file it
  transitively refs to `<stem>-<suffix>.<ext>` siblings, and move such
  forks back over their bases. Forks are byte copies; refs are never
  rewritten, so both sides of a comparison name the same files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [graph]
            [serve]))

(defn resolve-ref
  "The root-relative path a `ref` on the file `current` (itself
  root-relative) points to, with `.`/`..`/empty segments collapsed; nil
  when the ref is blank, absolute, or climbs above the root. Mirrors
  editor/resolve-ref on the page."
  [current ref]
  (let [ref (str/trim (str ref))]
    (when (and (not= ref "")
               (not (str/starts-with? ref "/"))
               (nil? (re-find #"^[A-Za-z]:" ref)))
      (loop [acc (vec (butlast (str/split (str current) #"/" -1)))
             segs (str/split ref #"/" -1)]
        (if (empty? segs)
          (when (seq acc) (str/join "/" acc))
          (let [[seg & more] segs]
            (cond
              (or (= seg "") (= seg ".")) (recur acc more)
              (= seg "..") (when (seq acc) (recur (pop acc) more))
              :else (recur (conj acc seg) more))))))))

(defn ref-targets
  "The non-blank string :ref attrs on the nodes, edges and boxes in the
  EDN text of a graph file, distinct. Throws on a parse error."
  [text]
  (let [g (graph/normalize (edn/read-string text))]
    (->> (concat (map :attrs (vals (:nodes g)))
                 (map :attrs (:edges g))
                 (map :attrs (:boxes g)))
         (map :ref)
         (filter #(and (string? %) (not (str/blank? %))))
         distinct
         vec)))

(defn closure
  "Root-relative paths reachable by refs from `start`: `start` first,
  depth-first, each once. `read-rel` returns the EDN text of a
  root-relative path or throws; `warn!` takes one message. A ref that
  leaves the root or whose file cannot be read is reported and skipped;
  a parse error propagates as ex-info \"<rel>: <msg>\"."
  [start read-rel warn!]
  (let [seen (atom [])]
    (letfn [(targets [rel text]
              (try (ref-targets text)
                   (catch Exception e (throw (ex-info (str rel ": " (ex-message e)) {})))))
            (visit! [rel text]
              (swap! seen conj rel)
              (doseq [r (targets rel text)]
                (let [target (resolve-ref rel r)]
                  (cond
                    (nil? target)
                    (warn! (str rel ": ref " (pr-str r) " leaves the root folder, skipped"))

                    (some #{target} @seen) nil

                    :else
                    (let [text (try (read-rel target)
                                    (catch Exception e
                                      (warn! (str rel ": ref " (pr-str r) ": " (ex-message e) ", skipped"))
                                      nil))]
                      (when (some? text) (visit! target text)))))))]
      (visit! start (read-rel start))
      @seen)))
```

- [ ] **Step 4: Run tests** — same command. Expected: 4 tests pass.

- [ ] **Step 5: Register the namespace** in `bb.edn` `test:clj`: add `[fork-test]` to `:requires` and `'fork-test` to the `run-tests` call.

- [ ] **Step 6: Commit**

```bash
git add server/fork.clj server/serve.clj test/fork_test.clj bb.edn
git commit -m "feat(fork): ref closure over a graph file tree

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `fork!`, `promote!`, `-main`, bb tasks

**Files:**
- Modify: `server/fork.clj`
- Modify: `test/fork_test.clj`
- Modify: `bb.edn` (tasks `fork`, `promote`; release `bb.edn` inside `bundle`)

**Interfaces:**
- Consumes: `serve/fork-name`, `fork/closure`.
- Produces: `(fork/fork! file suffix warn!)` → vector of created paths (as shown to the user); `(fork/promote! file suffix warn!)` → vector of promoted paths; `(fork/-main cmd file suffix)`.

- [ ] **Step 1: Write the failing tests** — append to `test/fork_test.clj`:

```clojure
(deftest fork-copies-the-closure-verbatim-and-refuses-existing-targets
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"}}} ; keep me"
                     "sub/api.edn" "{:nodes {:h {:ref \"deep/db.edn\"}}}"
                     "sub/deep/db.edn" "{:nodes {:t nil}}"
                     "unrelated.edn" "{}"})
        file (.getPath (io/file root "root.edn"))
        created (fork/fork! file "next" (fn [_]))]
    (is (= (mapv #(.getPath (io/file root %)) ["root-next.edn" "sub/api-next.edn" "sub/deep/db-next.edn"])
           created))
    (is (= (slurp (io/file root "root.edn")) (slurp (io/file root "root-next.edn"))))
    (is (= (slurp (io/file root "sub/api.edn")) (slurp (io/file root "sub/api-next.edn"))))
    (is (not (.exists (io/file root "unrelated-next.edn"))))
    ;; a second fork refuses and writes nothing
    (spit (io/file root "sub/deep/db-next.edn") "{:nodes {:changed nil}}")
    (.delete (io/file root "root-next.edn"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub/deep/db-next\.edn already exists"
                          (fork/fork! file "next" (fn [_]))))
    (is (not (.exists (io/file root "root-next.edn"))))
    (fs/delete-tree root)))

(deftest promote-moves-every-fork-in-the-forks-closure
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"sub/api.edn\"}}}"
                     "root-next.edn" "{:nodes {:a {:ref \"sub/api.edn\"} :n {:ref \"new.edn\"}}}"
                     "sub/api.edn" "{:nodes {:h nil}}"
                     "sub/api-next.edn" "{:nodes {:h nil :extra nil}}"
                     "new-next.edn" "{:nodes {:only-in-fork nil}}"
                     "other.edn" "{}"
                     "other-next.edn" "{:nodes {:x nil}}"})
        file (.getPath (io/file root "root.edn"))
        moved (fork/promote! file "next" (fn [_]))]
    (is (= #{(.getPath (io/file root "root.edn"))
             (.getPath (io/file root "sub/api.edn"))
             (.getPath (io/file root "new.edn"))}
           (set moved)))
    (is (= "{:nodes {:a {:ref \"sub/api.edn\"} :n {:ref \"new.edn\"}}}" (slurp (io/file root "root.edn"))))
    (is (= "{:nodes {:h nil :extra nil}}" (slurp (io/file root "sub/api.edn"))))
    (is (= "{:nodes {:only-in-fork nil}}" (slurp (io/file root "new.edn"))))
    (is (not (.exists (io/file root "root-next.edn"))))
    (is (not (.exists (io/file root "sub/api-next.edn"))))
    (is (not (.exists (io/file root "new-next.edn"))))
    ;; outside the closure: untouched
    (is (.exists (io/file root "other-next.edn")))
    (is (= "{}" (slurp (io/file root "other.edn"))))
    ;; nothing left to promote
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to promote"
                          (fork/promote! file "next" (fn [_]))))
    (fs/delete-tree root)))

(deftest promote-follows-an-unforked-file-through-to-forked-ones
  (let [root (tree! {"root.edn" "{:nodes {:a {:ref \"mid.edn\"}}}"
                     "root-next.edn" "{:nodes {:a {:ref \"mid.edn\"}}}"
                     "mid.edn" "{:nodes {:m {:ref \"leaf.edn\"}}}"
                     "leaf.edn" "{}"
                     "leaf-next.edn" "{:nodes {:l nil}}"})
        moved (fork/promote! (.getPath (io/file root "root.edn")) "next" (fn [_]))]
    (is (= #{(.getPath (io/file root "root.edn")) (.getPath (io/file root "leaf.edn"))} (set moved)))
    (is (= "{:nodes {:l nil}}" (slurp (io/file root "leaf.edn"))))
    (fs/delete-tree root)))

(deftest suffix-validation
  (is (fork/valid-suffix? "next"))
  (is (fork/valid-suffix? "v2.1_rc-1"))
  (is (not (fork/valid-suffix? "a/b")))
  (is (not (fork/valid-suffix? "")))
  (is (not (fork/valid-suffix? nil))))
```

- [ ] **Step 2: Run to verify they fail** — `bb -e "(require '[clojure.test :as t] 'fork-test) (t/run-tests 'fork-test)"`. Expected: `fork!`, `promote!`, `valid-suffix?` unresolved.

- [ ] **Step 3: Implement** — append to `server/fork.clj`:

```clojure
(defn valid-suffix? [s]
  (boolean (and (string? s) (re-matches serve/suffix-re s))))

(defn- root-and-start
  "[canonical root dir, root-relative name] of a file path."
  [file]
  (let [f (.getCanonicalFile (io/file file))]
    [(.getParentFile f) (.getName f)]))

(defn- read-base [root]
  (fn [rel] (serve/read-source (.getPath (serve/resolve-path root rel)))))

(defn fork!
  "Copy `file` and every file in its ref closure to their `suffix`
  forks. Returns the created paths. Throws ex-info naming the first
  existing target before anything is written."
  [file suffix warn!]
  (let [[root start] (root-and-start file)
        rels (closure start (read-base root) warn!)
        pairs (mapv (fn [rel] [(io/file root rel) (io/file root (serve/fork-name rel suffix))]) rels)]
    (doseq [[_ to] pairs]
      (when (.exists to)
        (throw (ex-info (str (.getPath to) " already exists") {}))))
    (doseq [[from to] pairs]
      (io/copy from to))
    (mapv (fn [[_ to]] (.getPath to)) pairs)))

(defn promote!
  "Walk the closure of `file`, reading each file's `suffix` fork when
  it exists (else the base), and move every fork found over its base.
  Returns the promoted base paths; throws ex-info \"nothing to promote\"
  when no fork was found."
  [file suffix warn!]
  (let [[root start] (root-and-start file)
        fork-file (fn [rel] (io/file root (serve/fork-name rel suffix)))
        read-rel (fn [rel]
                   (let [fk (serve/fork-name rel suffix)]
                     (serve/read-source
                      (.getPath (serve/resolve-path root (if (.isFile (fork-file rel)) fk rel))))))
        rels (closure start read-rel warn!)
        moved (reduce (fn [acc rel]
                        (let [fk (fork-file rel)
                              base (io/file root rel)]
                          (if (.isFile fk)
                            (do (java.nio.file.Files/move
                                 (.toPath fk) (.toPath base)
                                 (into-array java.nio.file.CopyOption
                                             [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                                (conj acc (.getPath base)))
                            acc)))
                      [] rels)]
    (when (empty? moved)
      (throw (ex-info "nothing to promote" {})))
    moved))

(defn -main
  "bb fork|promote <graph.edn> <suffix> (the task passes the command)."
  [& [cmd file suffix & extra]]
  (when (or (not (contains? #{"fork" "promote"} cmd)) (nil? file) (nil? suffix) (seq extra))
    (println "usage: bb fork|promote <graph.edn> <suffix>")
    (System/exit 1))
  (when-not (valid-suffix? suffix)
    (println (str "invalid suffix: " suffix))
    (System/exit 1))
  (try
    (let [warn! (fn [m] (binding [*out* *err*] (println (str "warning: " m))))
          paths (if (= cmd "fork") (fork! file suffix warn!) (promote! file suffix warn!))
          verb (if (= cmd "fork") "created " "promoted ")]
      (doseq [p paths] (println (str verb p))))
    (catch Exception e
      (println (str cmd ": " (ex-message e)))
      (System/exit 1))))
```

Note on `promote!` with a start whose base is missing: `resolve-path` is applied to the fork name in that case, so `simpleviz promote new.edn next` works when only `new-next.edn` exists.

- [ ] **Step 4: Run tests** — same command. Expected: all fork tests pass.

- [ ] **Step 5: bb tasks** — in `bb.edn` add after `extract`:

```clojure
  fork     {:doc "Fork a graph and every file it refs: bb fork graph.edn <suffix> (writes graph-<suffix>.edn ...)"
            :requires ([fork])
            :task (apply fork/-main "fork" *command-line-args*)}
  promote  {:doc "Move each fork over its base file: bb promote graph.edn <suffix>"
            :requires ([fork])
            :task (apply fork/-main "promote" *command-line-args*)}
```

Change the `serve` task `:doc` to `"Serve an EDN graph file or exported PNG: bb serve graph.edn|.png [suffix] [--port N] (suffix = compare against graph-<suffix>.edn)"`. In the `bundle` task's `release-bb` `:tasks` quote, change the `serve` doc the same way and add the same `fork` and `promote` entries.

- [ ] **Step 6: Smoke test the tasks**

```bash
d=$(mktemp -d); cp -r examples/api "$d/"; cp examples/demo.edn "$d/"
bb fork "$d/demo.edn" try && ls "$d" "$d/api"
bb fork "$d/demo.edn" try; echo "exit $?"      # already exists, exit 1
bb promote "$d/demo.edn" try && ls "$d" "$d/api"
bb promote "$d/demo.edn" try; echo "exit $?"   # nothing to promote, exit 1
rm -rf "$d"
```
Expected: first fork prints `created .../demo-try.edn` and `created .../api/internals-try.edn`; second exits 1 with `fork: ... already exists`; promote prints two `promoted` lines; the last exits 1 with `promote: nothing to promote`.

- [ ] **Step 7: Commit**

```bash
git add server/fork.clj test/fork_test.clj bb.edn
git commit -m "feat: bb fork / bb promote

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: server state `{:root :suffix}`, `sides`, routes pairing per request

**Files:**
- Modify: `server/serve.clj` (`files`, `embedded-old` callers, `compare-mode?`/`requested-file` removed, `edit-response`, `graph-response-body`, `/api/version`, `/api/source`, `-main`)
- Modify: `test/server_test.clj` (state shape everywhere; compare tests)

**Interfaces:**
- Produces: `serve/files` holds `{:root <path> :suffix <s-or-nil>}`; `(serve/sides rel)` → `{:old File-or-nil :new File}` (rel nil = root); `(serve/edit-response parsed)` (one arg). Payloads: compare payloads in suffix mode carry `:path`.

- [ ] **Step 1: Rewrite the test state helper and the compare tests** — in `test/server_test.clj` add near the top (after the `ns` form):

```clojure
(defn- serve!
  "Point the server at `path` (single-file, or compare against its
  `suffix` fork) the way -main does, with a fresh undo stack."
  ([path] (serve! path nil))
  ([path suffix]
   (reset! serve/files {:root path :suffix suffix})
   (reset! serve/root-dir (.getParentFile (.getCanonicalFile (java.io.File. path))))
   (reset! serve/undo-stacks {})))
```

Then replace every `(reset! serve/files {:old nil :new X})` with `(serve! X)` and delete the `(reset! serve/undo-stacks {})` lines that follow them (the helper resets it). `refs-mode!` becomes `(serve! "test/fixtures/refs/root.edn")` (drop its own `root-dir`/`undo-stacks` resets; `refs-root` may stay if used elsewhere). In `api-edit-path-edits-that-file-with-its-own-undo` replace the three resets with `(serve! (.getPath root))`.

Rewrite these tests:

```clojure
(deftest api-source-compare-selects-files
  (serve! "examples/demo.edn" "next")
  (is (= (slurp "examples/demo.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=new"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source"})))))

(defn- png-bytes* [chunks]
  (byte-array (map unchecked-byte (concat [137 80 78 71 13 10 26 10] (apply concat chunks)))))

(defn- temp-png* [chunks]
  (let [f (java.io.File/createTempFile "serve-test" ".png")]
    (.deleteOnExit f)
    (with-open [os (clojure.java.io/output-stream f)] (.write os (png-bytes* chunks)))
    (.getPath f)))

(defn- temp-dir* []
  (.toFile (java.nio.file.Files/createTempDirectory "serve-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write! [dir rel content]
  (let [f (java.io.File. dir rel)]
    (.mkdirs (.getParentFile f))
    (if (bytes? content)
      (with-open [os (clojure.java.io/output-stream f)] (.write os content))
      (spit f content))
    (.getPath f)))

(deftest api-graph-suffix-compare-accepts-png-sides
  (let [dir (temp-dir*)
        p (write! dir "x.png" (png-bytes* [(itxt* "simpleviz-edn-new" "{:nodes {:a {}}}")]))]
    (write! dir "x-next.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {} :b {}}}")]))
    (serve! p "next")
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (contains? out "compare"))
      (is (= "added" (get-in out ["nodes" "b" "diff"])))
      (is (false? (get out "editable")))
      (is (false? (get out "editable-old"))))))

(deftest api-graph-compare-mode-carries-editable-flags
  (let [dir (temp-dir*)
        p (write! dir "g.edn" "{:nodes {:a nil}}")]
    (write! dir "g-next.edn" "{:nodes {:a nil :b nil}}")
    (serve! p "next")
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (true? (get out "editable")))
      (is (true? (get out "editable-old")))
      (is (= "g.edn" (get out "path")))
      (is (= {"old" "g.edn" "new" "g-next.edn"} (get out "compare"))))))

(deftest api-edit-refuses-png-in-old-slot
  (let [dir (temp-dir*)
        p (write! dir "x.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {}}}")]))]
    (write! dir "x-next.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {}}}")]))
    (serve! p "next")
    (is (= "PNG sources are read-only"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops []})))) "error")))))

(deftest api-graph-file-param-refused-in-embedded-compare
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (serve! f)
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=x.edn"})))]
      (is (= "refs are not available in an embedded compare" (get out "error"))))
    (is (= 0 (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=x.edn"}))) "mtime")))
    (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=x.edn"}))))
    (is (= "refs are not available in an embedded compare"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "x.edn" :ops []})))) "error")))))
```

(`api-graph-file-param-refused-in-compare-mode` and `api-graph-two-file-compare-accepts-png-sides` are replaced by the two above.)

Add the suffix-mode ref tests:

```clojure
;; --- suffix compare with refs ------------------------------------------

(defn- suffix-tree!
  "A root with a forked ref target, an unforked one, and a fork-only one."
  []
  (let [dir (temp-dir*)
        p (write! dir "root.edn" "{:nodes {:api {:ref \"sub/api.edn\"} :lone {:ref \"lone.edn\"} :fresh {:ref \"fresh.edn\"}}}")]
    (write! dir "root-next.edn" "{:nodes {:api {:ref \"sub/api.edn\"} :lone {:ref \"lone.edn\"} :fresh {:ref \"fresh.edn\"} :added nil}}")
    (write! dir "sub/api.edn" "{:nodes {:h nil}}")
    (write! dir "sub/api-next.edn" "{:nodes {:h nil :h2 nil}}")
    (write! dir "lone.edn" "{:nodes {:l nil}}")
    (write! dir "fresh-next.edn" "{:nodes {:f nil}}")
    (serve! p "next")
    dir))

(deftest sides-pairs-a-path-with-its-fork
  (let [dir (suffix-tree!)
        root (serve/sides nil)
        sub (serve/sides "sub/api.edn")]
    (is (= "root.edn" (.getName (:old root))))
    (is (= "root-next.edn" (.getName (:new root))))
    (is (= "api.edn" (.getName (:old sub))))
    (is (= "api-next.edn" (.getName (:new sub))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^no lone-next\.edn — create it with: simpleviz fork lone\.edn next$"
                          (serve/sides "lone.edn")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^no fresh\.edn \(only fresh-next\.edn\)$"
                          (serve/sides "fresh.edn")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves the served folder"
                          (serve/sides "../x.edn")))
    (serve! (.getPath (java.io.File. dir "root.edn")))
    (is (nil? (:old (serve/sides nil))))
    (is (= "api.edn" (.getName (:new (serve/sides "sub/api.edn")))))))

(deftest api-graph-file-param-compares-the-pair-in-suffix-mode
  (suffix-tree!)
  (let [sub (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fapi.edn"})))
        lone (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=lone.edn"})))
        fresh (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=fresh.edn"})))]
    (is (= {"old" "sub/api.edn" "new" "sub/api-next.edn"} (get sub "compare")))
    (is (= "sub/api.edn" (get sub "path")))
    (is (= "api-next.edn" (get sub "file")))
    (is (= "added" (get-in sub ["nodes" "h2" "diff"])))
    (is (true? (get sub "editable")))
    (is (true? (get sub "editable-old")))
    (is (= "no lone-next.edn — create it with: simpleviz fork lone.edn next" (get lone "error")))
    (is (= "no fresh.edn (only fresh-next.edn)" (get fresh "error")))))

(deftest api-version-and-source-per-side-in-suffix-mode
  (let [dir (suffix-tree!)
        old (java.io.File. dir "sub/api.edn")
        new (java.io.File. dir "sub/api-next.edn")]
    (is (= (str (.lastModified old) "-" (.lastModified new))
           (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=sub%2Fapi.edn"}))) "mtime")))
    (is (= 0 (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=lone.edn"}))) "mtime")))
    (is (= (slurp old) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn&which=old"}))))
    (is (= (slurp new) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn&which=new"}))))
    (is (= (slurp new) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn"}))))
    (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=lone.edn"}))))))

(deftest api-edit-path-and-file-pick-the-side-in-suffix-mode
  (let [dir (suffix-tree!)
        old (java.io.File. dir "sub/api.edn")
        new (java.io.File. dir "sub/api-next.edn")]
    (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :path "sub/api.edn" :ops [{:op "add-node" :id "o"}]})))) "ok")))
    (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "add-node" :id "n"}]})))) "ok")))
    (is (clojure.string/includes? (slurp old) ":o nil"))
    (is (not (clojure.string/includes? (slurp old) ":n nil")))
    (is (clojure.string/includes? (slurp new) ":n nil"))
    ;; independent undo stacks
    (serve/handler (edit-req {:file "old" :path "sub/api.edn" :ops [{:op "undo"}]}))
    (is (= "{:nodes {:h nil}}" (slurp old)))
    (is (clojure.string/includes? (slurp new) ":n nil"))
    ;; a missing side is an error before anything is written
    (is (= "no lone-next.edn — create it with: simpleviz fork lone.edn next"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "lone.edn" :ops [{:op "add-node" :id "z"}]})))) "error")))
    (is (= "{:nodes {:l nil}}" (slurp (java.io.File. dir "lone.edn"))))))
```

- [ ] **Step 2: Run to verify they fail** — `bb -e "(require '[clojure.test :as t] 'server-test) (t/run-tests 'server-test)"`. Expected: many failures (`sides` unresolved, state shape).

- [ ] **Step 3: Implement in `server/serve.clj`**

Give `resolve-path` an optional existence flag (the 2-arity keeps today's behaviour):

```clojure
(defn resolve-path
  "The canonical file for the root-relative path `rel` under `root`.
  Refuses (ex-info, message names the problem) an absolute path, a
  result outside `root` after canonicalization (so `..` and symlinks
  cannot escape), an extension other than .edn/.png, and — unless
  must-exist? is false — anything that is not an existing regular file."
  ([root rel] (resolve-path root rel true))
  ([root rel must-exist?]
   (let [rel (str rel)]
     (when (.isAbsolute (io/file rel))
       (throw (ex-info (str "absolute path refused: " rel) {})))
     (let [root-c (.getCanonicalFile (io/file root))
           f (.getCanonicalFile (io/file root-c rel))
           nm (.getName f)
           dot (str/last-index-of nm ".")
           ext (when (some? dot) (str/lower-case (subs nm (inc dot))))]
       (when-not (str/starts-with? (.getPath f) (str (.getPath root-c) java.io.File/separator))
         (throw (ex-info (str rel " leaves the served folder") {})))
       (when-not (contains? ref-extensions ext)
         (throw (ex-info (str rel " is not an .edn or .png file") {})))
       (when (and must-exist? (not (.isFile f)))
         (throw (ex-info (str "no such file: " rel) {})))
       f))))
```

Replace the `files` def:

```clojure
(def files (atom nil)) ; {:root <path> :suffix <s-or-nil>}
```

After `embedded-old` and `query-param`, replace `compare-mode?` and `requested-file` with:

```clojure
(defn- embedded-compare?
  "Is the root a compare-export PNG (both sides embedded, no suffix)?"
  []
  (let [{:keys [root suffix]} @files]
    (and (nil? suffix) (some? (embedded-old root)))))

(defn sides
  "The files behind the root-relative path `rel` (nil = the root file)
  as {:old <canonical File or nil> :new <canonical File>}: without a
  suffix only :new; with one, :old is the file and :new its fork.
  Throws (message for the error payload) when a side is missing or
  resolve-path refuses either."
  [rel]
  (let [{:keys [root suffix]} @files
        rel (or rel (.getName (io/file root)))
        root-c @root-dir]
    (if (nil? suffix)
      {:old nil :new (resolve-path root-c rel)}
      (let [fk (fork-name rel suffix)
            ;; escape/extension refusals first, existence checked here so
            ;; the message can name the missing side
            old (resolve-path root-c rel false)
            new (resolve-path root-c fk false)]
        (cond
          (not (.isFile new))
          (throw (ex-info (str "no " fk " — create it with: simpleviz fork " rel " " suffix) {}))
          (not (.isFile old))
          (throw (ex-info (str "no " rel " (only " fk ")") {}))
          :else {:old old :new new})))))

(defn- nav-rel
  "The `file` query parameter, or nil; refused (throws) on an embedded
  compare, which has no folder to navigate."
  [query-string]
  (when-let [rel (query-param query-string "file")]
    (when (embedded-compare?)
      (throw (ex-info "refs are not available in an embedded compare" {})))
    rel))
```

Replace `edit-response`:

```clojure
(defn- edit-response [{:keys [file ops path]}]
  (try
    (when (and (some? path) (embedded-compare?))
      (throw (ex-info "refs are not available in an embedded compare" {})))
    (let [{:keys [old new]} (sides path)
          target (if (= file "old") old new)
          path (some-> target .getPath)]
      (cond
        (nil? path) {:error "no old file in single-file mode"}
        (png/png? path) {:error "PNG sources are read-only"}
        (= "undo" (:op (first ops)))
        (if-let [prev (pop-undo! path)]
          (do (spit path prev) {:ok true})
          {:error "nothing to undo"})
        :else
        ;; snapshot the ORIGINAL text before applying — `before` feeds
        ;; both the patch and the undo stack
        (let [before (slurp path)
              {:keys [text error]} (edit/apply-ops before ops)]
          (if (some? error)
            {:error error}
            (do (push-undo! path before)
                (spit path text)
                {:ok true})))))
    (catch Exception e {:error (ex-message e)})))
```

and in `edit-response-body` call `(edit-response parsed)`.

Replace the `let` body of `graph-response-body`'s `try`:

```clojure
               (let [{:keys [root suffix]} @files
                     rel (nav-rel query-string)]
                 (if (embedded-compare?)
                   (let [nm (.getName (io/file root))]
                     (compare-json (embedded-old root) (read-source root)
                                   (str nm " (old)") (str nm " (new)") nm
                                   {:editable false :editable-old false}))
                   (let [{:keys [old new]} (sides rel)
                         path (or rel (.getName (io/file root)))
                         new-p (.getPath new)]
                     (if (some? old)
                       (compare-json (read-source (.getPath old)) (read-source new-p)
                                     path (fork-name path suffix) (.getName new)
                                     {:editable (not (png/png? new-p))
                                      :editable-old (not (png/png? (.getPath old)))
                                      :path path})
                       (graph-json (read-source new-p) (.getName new)
                                   {:editable (not (png/png? new-p)) :path path})))))
```

Replace the `/api/version` `:mtime` expression:

```clojure
                     {:mtime (try
                               (let [{:keys [old new]} (sides (nav-rel query-string))]
                                 (if (some? old)
                                   (str (.lastModified old) "-" (.lastModified new))
                                   (.lastModified new)))
                               ;; a refused file reports a constant: the page
                               ;; reloads once and shows the graph route's error
                               (catch Exception _ 0))}
```

Replace the `/api/source` `let` head:

```clojure
    (let [which (when (some? query-string)
                  (second (re-find #"(?:^|&)which=(old|new)(?:&|$)" query-string)))
          body (try
                 (if (embedded-compare?)
                   (do (nav-rel query-string)
                       (if (= which "old")
                         (embedded-old (:root @files))
                         (read-source (:root @files))))
                   (let [{:keys [old new]} (sides (nav-rel query-string))]
                     (if (= which "old")
                       (when (some? old) (read-source (.getPath old)))
                       (read-source (.getPath new)))))
                 (catch Exception _ nil))]
```

Replace the start of `-main` up to `log-path`:

```clojure
(defn -main [& args]
  (let [{:keys [file suffix port debug error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (when-not (.isFile (io/file file))
      (println (str "file not found: " file))
      (System/exit 1))
    (reset! files {:root file :suffix suffix})
    (reset! root-dir (.getParentFile (.getCanonicalFile (io/file file))))
    ;; resolve both sides once so a missing fork or a PNG without
    ;; embedded EDN fails at startup with a clear message instead of an
    ;; empty diagram in the browser
    (try
      (let [{:keys [old new]} (sides nil)]
        (doseq [f (remove nil? [old new])] (read-source (.getPath f))))
      (catch Exception e
        (println (ex-message e))
        (System/exit 1)))
    (let [serving (cond
                    suffix (str file " → " (fork-name file suffix) " (compare)")
                    (embedded-compare?) (str file " (embedded compare)")
                    :else file)
```

(the rest of `-main` is unchanged).

- [ ] **Step 4: Run tests** — same command. Expected: all server tests pass. If a test still references `:old`/`:new` state, convert it with `serve!`.

- [ ] **Step 5: Run the whole Clojure suite** — `bb test:clj`. Expected: 0 failures.

- [ ] **Step 6: Commit**

```bash
git add server/serve.clj test/server_test.clj
git commit -m "feat(serve): compare by suffix, paired per requested file

Two-file compare is gone: bb serve graph.edn <suffix> compares every
file against its <stem>-<suffix> fork, so refs work in compare mode.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: page — follow ref in a suffix compare, trail above the legend

**Files:**
- Modify: `src/simpleviz/app.cljs` (`action-spec` "follow-ref" branch ~line 326, `trail-view` ~line 558, `app-view` ~line 695, help "Edit" text ~line 662)
- Modify: `public/style.css` (`#diff-legend`, `#trail`)
- Test: `test/simpleviz/*` untouched (the gating is view code); verify with `bb build` + manual check in Task 7.

**Interfaces:**
- Consumes: payload `:path` (set by the server in single and suffix mode, absent in an embedded compare).

- [ ] **Step 1: Gate by `:path`** — in `action-spec`:

```clojure
        ;; only for a selection with a string :ref, when the server can
        ;; navigate (single-file or suffix compare: the payload has :path)
        "follow-ref" (when-let [r (editor/ref-of sel)]
                       (when (some? (:path (:graph @state)))
                         {:label "follow ref" :go r}))
```

In `trail-view` change the condition to `(when (and (seq trail) (some? (:path (:graph st))))`.

- [ ] **Step 2: Top-center column** — in `app-view` replace

```clojure
   (when (some? (:graph st)) (legend-view st))
   (trail-view st)
```

with

```clojure
   [:div {:id "top-center"}
    (trail-view st)
    (when (some? (:graph st)) (legend-view st))]
```

In `public/style.css` replace the positioning of both:

```css
#top-center { position: fixed; top: 12px; left: 50%; transform: translateX(-50%);
              z-index: 6; display: flex; flex-direction: column; align-items: center; gap: 8px;
              pointer-events: none; }
#top-center > * { pointer-events: auto; }
#diff-legend { min-width: 240px;
               background: var(--panel); border: 1px solid var(--panel-border);
               border-radius: 8px; padding: 8px 12px; font-size: 12px;
               box-shadow: 0 2px 8px var(--shadow); }
#trail { max-width: 80vw; display: flex; flex-wrap: wrap;
         align-items: center; gap: 4px;
         background: var(--panel); border: 1px solid var(--panel-border);
         border-radius: 10px; padding: 5px 10px; font-size: 12px;
         box-shadow: 0 2px 8px var(--shadow);
         font-family: ui-monospace, "Cascadia Mono", monospace; }
```

(remove `position/top/left/transform/z-index` from the two old rules; keep every other declaration.)

- [ ] **Step 3: Export fetches the shown file's sides** — `fetch-source` builds `?which=old` without the `file` parameter, so an export from a followed comparison would embed the root's files. Replace its URL expression:

```clojure
    (let [fq (file-query)
          resp (js-await (js/fetch (str "/api/source" fq
                                        (when (some? which)
                                          (str (if (= fq "") "?" "&") "which=" which)))))]
```

(keep the rest of the function as it is).

- [ ] **Step 4: Help text** — in the "Edit" help section replace `A :ref attribute naming another graph file (relative path) makes "follow ref" open it; the trail at the top leads back.` with `A :ref attribute naming another graph file (relative path) makes "follow ref" open it — in a suffix comparison (simpleviz graph.edn next) it opens that file's own comparison; the trail at the top leads back.`

- [ ] **Step 5: Build and run JS tests** — `bb build && bb test:js`. Expected: compiles, tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/simpleviz/app.cljs public/style.css
git commit -m "feat(page): follow refs inside a suffix compare; trail above legend

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: launcher, `bb dev`, example fork, docs

**Files:**
- Modify: `install.sh` (launcher heredoc: `usage`, `serve`, new `fork`/`promote` cases)
- Modify: `bb.edn` (`dev` task doc)
- Create: `examples/api/internals-next.edn`
- Modify: `README.md`, `plugins/simpleviz/skills/simpleviz/SKILL.md`, `docs/development.md`

- [ ] **Step 1: Launcher usage** — replace the `usage` heredoc body:

```
usage: simpleviz <graph.edn> [<suffix>] [--debug]   serve a graph; with a suffix, compare it
                                         against its fork graph-<suffix>.edn (refs follow
                                         into the same comparison of each referenced file)
                                         exported PNGs work in place of EDN files
                                         --debug logs edits and errors to $SIMPLEVIZ_HOME/logs/

       simpleviz fork <graph.edn> <suffix>     copy the graph and every file it refs to
                                               <name>-<suffix>.edn siblings
       simpleviz promote <graph.edn> <suffix>  move each fork over its original file
       simpleviz init <graph.edn>        write a starter graph file (won't overwrite)
       simpleviz extract <diagram.png> [out.edn] [--old]   print/extract the embedded EDN
       simpleviz update                  install the latest release if it is newer
       simpleviz clean-all               kill every running simpleviz server
       simpleviz --version               print the installed version
Serves on a random free port between 7370 and 7469.
Try the bundled example: simpleviz "$SIMPLEVIZ_HOME/examples/demo.edn" next
```

- [ ] **Step 2: Launcher `serve`** — replace the function:

```bash
fork_name() { # <path> <suffix> -> <dir>/<stem>-<suffix>.<ext>
  local f="$1" s="$2" dir base
  dir=$(dirname "$f"); base=$(basename "$f")
  case "$base" in
    *.*) echo "$dir/${base%.*}-$s.${base##*.}" ;;
    *)   echo "$dir/$base-$s" ;;
  esac
}

serve() {
  local file="" suffix="" flags=() f fork port pid i
  [ -d "$SIMPLEVIZ_HOME" ] || die "$SIMPLEVIZ_HOME not found — run install.sh first"
  for f in "$@"; do
    case "$f" in
      --debug) flags+=("$f") ;;
      *)
        if [ -z "$file" ]; then
          [ -f "$f" ] || die "file not found: $f"
          file=$(realpath "$f")
        elif [ -z "$suffix" ]; then
          if [ -e "$f" ]; then
            die "two-file compare was replaced: simpleviz fork $file <suffix>, then simpleviz $file <suffix>"
          fi
          suffix="$f"
        else
          usage >&2; exit 1
        fi
        ;;
    esac
  done
  [ -n "$file" ] || { usage >&2; exit 1; }
  if [ -n "$suffix" ]; then
    fork=$(fork_name "$file" "$suffix")
    [ -f "$fork" ] || die "$fork not found — create it with: simpleviz fork $file $suffix"
  fi
  check_bb
  port=$(free_port) || die "no free port between 7370 and 7469"
  (cd "$SIMPLEVIZ_HOME" && exec bb serve "$file" ${suffix:+"$suffix"} --port "$port" ${flags[@]+"${flags[@]}"}) &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true' INT TERM
  for i in $(seq 1 100); do
    port_busy "$port" && break
    kill -0 "$pid" 2>/dev/null || die "server exited before accepting connections"
    sleep 0.1
  done
  echo "simpleviz: http://localhost:$port"
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:$port" >/dev/null 2>&1 || true
  fi
  wait "$pid"
}
```

- [ ] **Step 3: Launcher `fork`/`promote` cases** — in the `case "${1:-}"` add before `extract)`:

```bash
  fork | promote)
    cmd="$1"; shift
    [ "$#" -eq 2 ] || { usage >&2; exit 1; }
    [ -f "$1" ] || die "file not found: $1"
    check_bb
    [ -d "$SIMPLEVIZ_HOME" ] || die "$SIMPLEVIZ_HOME not found — run install.sh first"
    file=$(realpath "$1")
    (cd "$SIMPLEVIZ_HOME" && exec bb "$cmd" "$file" "$2")
    ;;
```

- [ ] **Step 4: Check the launcher** — `bash -n install.sh`, then extract and exercise the launcher against this checkout (it needs a built `public/`):

```bash
bb build
SIMPLEVIZ_INSTALL_SOURCED=1 source install.sh
SIMPLEVIZ_BIN=$PWD/dist/tmpbin write_launcher
d=$(mktemp -d); cp -r examples/api "$d/"; cp examples/demo.edn "$d/"
SIMPLEVIZ_HOME=$PWD dist/tmpbin/simpleviz fork "$d/demo.edn" try
SIMPLEVIZ_HOME=$PWD dist/tmpbin/simpleviz "$d/demo.edn" "$d/demo-try.edn"; echo "exit $?"   # replaced message
SIMPLEVIZ_HOME=$PWD dist/tmpbin/simpleviz "$d/demo.edn" nope; echo "exit $?"                 # not found — create it
SIMPLEVIZ_HOME=$PWD timeout 5 dist/tmpbin/simpleviz "$d/demo.edn" try                        # prints the URL, serves 5s
SIMPLEVIZ_HOME=$PWD dist/tmpbin/simpleviz promote "$d/demo.edn" try
rm -rf "$d" dist/tmpbin
```
Expected: `created` lines; exit 1 with `two-file compare was replaced`; exit 1 with `demo-nope.edn not found — create it with: simpleviz fork ... nope`; a `simpleviz: http://localhost:NNNN` line; `promoted` lines.

- [ ] **Step 5: `bb dev`** — in `bb.edn` change the `dev` doc to `"Compile, watch and serve: bb dev [graph.edn [suffix]] [--port N]"`. Its body needs no change: `serve/cli-spec` positional parsing still yields the file as the first positional.

- [ ] **Step 6: Example fork** — create `examples/api/internals-next.edn`:

```clojure
{:nodes {:router   {:name "Router"        :type "component" :lib "reitit"}
         :handlers {:name "Handlers"      :type "component"}
         :authc    {:name "Auth client"   :type "component"}
         :repo     {:name "Repository"    :type "component"}
         :pool     {:name "Conn pool"     :type "component" :size 20}
         :cache    {:name "Query cache"   :type "component"}
         :overview {:name "Demo overview" :type "external"
                    :ref "../demo.edn"}}               ; a ref back up: ".." stays below the served folder
 :edges {[:router :handlers] {:direction :-> :name "dispatch"}
         [:handlers :authc]  {:direction :-> :name "verify"   :type "grpc"}
         [:handlers :repo]   {:direction :-> :name "load/save"}
         [:repo :cache]      {:direction :-> :name "lookup"}
         [:repo :pool]       {:direction :-> :name "borrow"   :type "jdbc"}
         [:overview :router] {:direction :-> :name "REST"     :type "http"}}
 :boxes {:api {:name "API internals" :type "zone"
               :components #{:router :handlers :authc :repo :pool :cache}}}}
```

Check: `bb serve examples/demo.edn next` starts and prints `serving examples/demo.edn → examples/demo-next.edn (compare)`; stop it.

- [ ] **Step 7: README** — apply these edits:

Getting started, the launcher block:
```
    simpleviz ~/.simpleviz/examples/demo.edn   # or any graph.edn; picks a free port 7370-7469
    simpleviz init my-arch.edn                 # write a starter file to edit
    simpleviz fork my-arch.edn next            # my-arch-next.edn (+ forks of every referenced file)
    simpleviz my-arch.edn next                 # compare my-arch.edn → my-arch-next.edn
    simpleviz promote my-arch.edn next         # make the forks the new originals
```
Tarball block: replace `bb serve examples/demo.edn examples/demo-next.edn   # compare two versions` with `bb serve examples/demo.edn next             # compare against demo-next.edn`.

Exporting: replace `simpleviz old.png new.edn                # any mix of PNG and EDN in compare mode` with `simpleviz diagram.png next               # compare against diagram-next.png`, and the explicit-extract block with:
```
    simpleviz extract diagram.png graph.edn --old
    simpleviz extract diagram.png graph-next.edn
    simpleviz graph.edn next
```

Replace the "Comparing two versions" section with:

```markdown
## Comparing two versions

A comparison is a graph against a *fork* of itself, named by a suffix:

    simpleviz fork graph.edn next      # graph-next.edn, plus a fork of every file graph.edn refs
    simpleviz graph.edn next           # compare graph.edn (old) → graph-next.edn (new)
    simpleviz promote graph.edn next   # each fork replaces its original

`fork` copies the file and, transitively, every file reachable through
`:ref` attributes to `<name>-<suffix>.<ext>` siblings (refs inside the
copies are left as they are). Edit the forks — in the page or by hand —
and the comparison shows the difference. Following a ref inside a
comparison opens the referenced file's own comparison against its fork;
a referenced file without a fork (or a fork without an original) shows an
error, and the trail leads back. `promote` walks the fork's refs and
moves every fork it finds over its original; forks outside that closure
are left alone.

Both files render as ONE merged diagram — added elements get a green `+`
ring, modified ones an amber `~` ring (click for an attribute-level
old → new list), and removed ones stay visible as red, dashed, ghosted
shapes. Nodes and boxes match by key, edges by their endpoints (flipping
the pair or changing `:direction` counts as modified). Layout follows the
new file; removed elements keep their old place. A collapsed box hiding
any change shows an amber dot. The legend at the top center names both
files and counts the changes per status — click a row to jump through
them (selects and centers each element, wraps around). Both files
live-reload.

Try it: `simpleviz ~/.simpleviz/examples/demo.edn next`, then select the
API node and press `f r`.
```

"Following refs" paragraph: replace `Following is not available in compare mode, and a PNG-served graph cannot follow refs either (the toolbar is read-only there).` with `In a suffix comparison, following opens the comparison of the target and its fork; an embedded-compare PNG cannot follow refs, and a PNG-served graph cannot follow refs either (the toolbar is read-only there).`

- [ ] **Step 8: SKILL.md** — in the Running block replace `bb serve old.edn new.edn         # compare mode: ONE merged diff view (old → new)` with:
```
    bb fork graph.edn next           # graph-next.edn + forks of every referenced file
    bb serve graph.edn next          # compare mode: graph.edn → graph-next.edn, ONE merged diff view
    bb promote graph.edn next        # each fork replaces its original
```
and `simpleviz old.edn new.edn        # compare mode` with:
```
    simpleviz fork graph.edn next    # fork the graph and its ref closure
    simpleviz graph.edn next         # compare graph.edn → graph-next.edn (refs follow into
                                     # the referenced file's own comparison)
    simpleviz promote graph.edn next # move each fork over its original
```
Replace `There is no `bb diff` or similar — comparing is just passing two files.` with `There is no `bb diff` or similar — comparing is serving a file with the suffix of its fork (`<name>-<suffix>.<ext>`; `fork` creates it, `promote` folds it back). Two-file compare (`simpleviz old.edn new.edn`) no longer exists.`
In the format rules, replace `Not available in compare mode.` (the `:ref` line) with `In a suffix comparison it opens the referenced file's own comparison.`

- [ ] **Step 9: development.md** — replace `bb serve graph.edn [new.edn]     # serve only (needs a prior bb build)` with:
```
    bb serve graph.edn [suffix]      # serve only (needs a prior bb build); suffix = compare against graph-<suffix>.edn
    bb fork graph.edn suffix         # fork graph.edn and its ref closure
    bb promote graph.edn suffix      # move the forks back over their originals
```
Replace `Passing two graph files serves them in compare mode (old → new, see the README's "Comparing two versions")` with `Passing a suffix serves the file in compare mode against its fork (old → new, see the README's "Comparing two versions"; `server/fork.clj` creates and promotes forks)`.
Replace `The API serves one root file (or a compare pair). In single-file mode the routes` with `The API serves one root file, alone or paired with its fork. The routes` and append after `see `editor/parse-nav`).`: ` In suffix mode `serve/sides` pairs the requested path with its fork per request, so every route works in compare mode; an embedded-compare PNG refuses the parameter.`

- [ ] **Step 10: Full suite and commit**

```bash
bb test
git add install.sh bb.edn examples/api/internals-next.edn README.md plugins/simpleviz/skills/simpleviz/SKILL.md docs/development.md
git commit -m "feat(cli): simpleviz fork / promote, compare by suffix; docs

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: headless end-to-end check

**Files:** none changed (verification only; fix anything found, commit as `fix:`).

- [ ] **Step 1: Serve and drive** (needs `chromium` and node >= 22):

```bash
bb build
(bb serve examples/demo.edn next --port 7465 &) ; sleep 2
(chromium --headless=new --remote-debugging-port=9222 --window-size=1400,900 --no-first-run --disable-gpu about:blank &) ; sleep 4
node dev/cdp.mjs '[{"navigate":"http://127.0.0.1:7465/","wait":3000},
  {"eval":"document.querySelector(\"#diff-legend .dl-files\").textContent"},
  {"screenshot":"/tmp/claude-1000/-home-soeren-repos-private-simpleviz/770031e2-fe0b-4d09-9f50-9653651008a3/scratchpad/fp-1.png"}]'
```
Expected eval: `demo.edn → demo-next.edn`. Look at the screenshot to find the API node's position, then:

```bash
node dev/cdp.mjs '[{"click":[X,Y]},{"wait":500},{"key":"f"},{"key":"r"},{"wait":2500},
  {"eval":"location.search"},
  {"eval":"document.querySelector(\"#diff-legend .dl-files\").textContent"},
  {"eval":"document.querySelector(\"#trail\").textContent"},
  {"screenshot":"/tmp/claude-1000/-home-soeren-repos-private-simpleviz/770031e2-fe0b-4d09-9f50-9653651008a3/scratchpad/fp-2.png"}]'
```
Expected: `?file=api%2Finternals.edn&trail=demo.edn`; legend `internals.edn → internals-next.edn`; trail `demo.edn›api/internals.edn`; the screenshot shows a green-ringed "Query cache" node and the trail bar above the legend.

```bash
node dev/cdp.mjs '[{"eval":"document.querySelector(\".trail-crumb\").click()"},{"wait":2500},
  {"eval":"location.search"},{"eval":"document.querySelector(\"#diff-legend .dl-files\").textContent"}]'
```
Expected: `""` (or no `file=`) and `demo.edn → demo-next.edn`.

- [ ] **Step 2: Stop the processes** — `pkill -f "bb serve examples/demo.edn"; pkill -f "remote-debugging-port=9222"`.

- [ ] **Step 3: Final `bb test`**, then commit any fix.
