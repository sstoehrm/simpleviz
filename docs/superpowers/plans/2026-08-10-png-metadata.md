# PNG EDN Embedding + `simpleviz extract` (PR B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exported PNGs carry the source EDN as iTXt metadata; `simpleviz extract` gets it back out — the image becomes a reusable artifact.

**Architecture:** Stacked on the `png-export` branch (PR A). New: `/api/source` (raw file text), `src/simpleviz/png.cljs` (pure iTXt splicer + CRC32, node-tested), export wiring, `server/png.clj` (pure-bb chunk reader) + `bb extract` task + launcher subcommand, docs. Spec: `docs/superpowers/specs/2026-08-10-png-export-design.md` (PR B section).

**Tech Stack:** babashka server, squint frontend, bash launcher. Tests: `bb test:clj`, `bb build && bb test:js`, checked-in PNG fixtures under `test/fixtures/`.

## Global Constraints

- Embedded payload is the RAW file text (never normalized JSON). Keywords: `simpleviz-edn` (single), `simpleviz-edn-old`/`simpleviz-edn-new` (compare). iTXt (UTF-8), uncompressed, inserted directly after IHDR.
- If fetching `/api/source` fails, the export still downloads — without metadata (graceful degradation), no error banner.
- `extract` default reads `simpleviz-edn-new` then falls back to `simpleviz-edn`; `--old` reads `simpleviz-edn-old`. Output to stdout, or to a file that must not already exist (like `init`). Clear one-line errors + exit 1 for: not a PNG, no embedded EDN.
- The `bb extract` task must ALSO be added to the release `bb.edn` generated inside the `bundle` task (its quoted `:tasks` map currently holds only `serve`) — otherwise installed users can't extract.
- Round-trip is tested in both directions: JS embed→extract-text in the node suite; bb reads a checked-in fixture WRITTEN BY the JS path (provenance documented in the fixture-generating step); the e2e step runs a live node-embed → bb-extract byte-equality check.
- Commit style `feat:`/`docs:`/`test:` as in git history.

---

### Task 1: `/api/source` endpoint

**Files:**
- Modify: `server/serve.clj`
- Modify: `test/server_test.clj`

**Interfaces:**
- Produces: `GET /api/source` → raw served-file text (`text/plain; charset=utf-8`, no-store). `?which=old` / `?which=new` select the compare files; no param = single/new file; `which=old` in single-file mode → 404. Handler now destructures `:query-string` too.

- [ ] **Step 1: Write the failing tests** (append to `test/server_test.clj`; the handler reads the `serve/files` atom — set it per test with real temp files via `File/createTempFile`, or simpler: point at `examples/demo.edn` / `examples/demo-next.edn` which exist in the repo)

```clojure
(deftest api-source-serves-raw-text
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))
    (is (clojure.string/starts-with?
         (get-in resp [:headers "Content-Type"]) "text/plain"))))

(deftest api-source-compare-selects-files
  (reset! serve/files {:old "examples/demo.edn" :new "examples/demo-next.edn"})
  (is (= (slurp "examples/demo.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=new"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source"})))))

(deftest api-source-old-without-compare-404s
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "which=old"})))))
```

- [ ] **Step 2: Run to verify failures**

Run: `bb test:clj`
Expected: FAIL — `/api/source` falls through to static 404 for all three.

- [ ] **Step 3: Implement in `server/serve.clj`**

Change the handler signature to `(defn handler [{:keys [uri query-string]}] ...)` and add a case branch before the static fallthrough:

```clojure
    "/api/source"
    (let [{:keys [old new]} @files
          which (when (some? query-string)
                  (second (re-find #"which=(old|new)" query-string)))
          f (case which "old" old "new" new new)]
      (if (some? f)
        {:status 200
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body (slurp f)}
        {:status 404
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body "not found"}))
```

- [ ] **Step 4: Green + commit**

Run: `bb test:clj` → PASS.

```bash
git add server/serve.clj test/server_test.clj
git commit -m "feat: /api/source serves the raw EDN file text"
```

---

### Task 2: `simpleviz.png` — iTXt splicer (pure cljs)

**Files:**
- Create: `src/simpleviz/png.cljs`
- Create: `test/simpleviz/png_test.cljs`

**Interfaces:**
- Produces: `png/crc32 [u8]` → unsigned int; `png/chunk-seq [u8]` → vector of `{:type "IHDR" :start i :length n}` (`:start` = offset of the length field); `png/embed-text [u8 kw text]` → new `Uint8Array` with the iTXt chunk inserted after IHDR; `png/embed-many [u8 pairs]` (pairs = `[[kw text] ...]`, empty pairs → input unchanged); `png/extract-text [u8 kw]` → string or nil.

- [ ] **Step 1: Write the failing tests** (create `test/simpleviz/png_test.cljs`)

```clojure
(ns simpleviz.png-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.png :as png]))

;; smallest valid 1x1 transparent PNG
(def fixture-b64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(defn fixture [] (js/Uint8Array.from (js/Buffer.from fixture-b64 "base64")))

(test "crc32 matches the known IEND vector"
  (fn []
    (let [iend (js/Uint8Array.from [73 69 78 68])]  ; "IEND"
      (assert/equal (png/crc32 iend) 0xAE426082))))

(test "chunk-seq walks the fixture"
  (fn []
    (assert/deepEqual (mapv (fn [c] (:type c)) (png/chunk-seq (fixture)))
                      ["IHDR" "IDAT" "IEND"])))

(test "embed-text inserts an iTXt chunk right after IHDR"
  (fn []
    (let [out (png/embed-text (fixture) "simpleviz-edn" "{:nodes {:a {}}}")]
      (assert/deepEqual (mapv (fn [c] (:type c)) (png/chunk-seq out))
                        ["IHDR" "iTXt" "IDAT" "IEND"]))))

(test "embed then extract round-trips UTF-8 text"
  (fn []
    (let [edn "{:nodes {:jp {:name \"日本\"}}}"
          out (png/embed-text (fixture) "simpleviz-edn" edn)]
      (assert/equal (png/extract-text out "simpleviz-edn") edn)
      (assert/equal (png/extract-text out "other-keyword") nil)
      (assert/equal (png/extract-text (fixture) "simpleviz-edn") nil))))

(test "embed-many embeds both compare keys; empty pairs is identity"
  (fn []
    (let [out (png/embed-many (fixture) [["simpleviz-edn-old" "{:a 1}"]
                                         ["simpleviz-edn-new" "{:a 2}"]])]
      (assert/equal (png/extract-text out "simpleviz-edn-old") "{:a 1}")
      (assert/equal (png/extract-text out "simpleviz-edn-new") "{:a 2}"))
    (assert/deepEqual (png/embed-many (fixture) []) (fixture))))
```

- [ ] **Step 2: Run to verify failures**

Run: `bb build && node --test public/js/simpleviz/png_test.mjs`
Expected: FAIL — ns missing.

- [ ] **Step 3: Implement `src/simpleviz/png.cljs`**

```clojure
(ns simpleviz.png)

;; PNG iTXt chunk splicing for EDN-carrying exports (issue #31, PR B).
;; Pure byte manipulation on Uint8Array — no DOM — so node tests cover
;; it. The babashka twin (server/png.clj) reads what this writes.

(def ^:private crc-table
  (let [t (js/Uint32Array. 256)]
    (dotimes [n 256]
      (loop [c n k 0]
        (if (< k 8)
          (recur (if (pos? (bit-and c 1))
                   (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1))
                   (unsigned-bit-shift-right c 1))
                 (inc k))
          (aset t n (unsigned-bit-shift-right c 0)))))
    t))

(defn crc32 [u8]
  (loop [crc 0xFFFFFFFF i 0]
    (if (< i (.-length u8))
      (recur (bit-xor (unsigned-bit-shift-right crc 8)
                      (aget crc-table (bit-and (bit-xor crc (aget u8 i)) 0xFF)))
             (inc i))
      (unsigned-bit-shift-right (bit-xor crc 0xFFFFFFFF) 0))))

(defn chunk-seq
  "PNG chunks as {:type :start :length}; :start is the offset of the
  4-byte length field. Assumes a well-formed stream after the 8-byte
  signature."
  [u8]
  (loop [i 8 acc []]
    (if (>= (+ i 8) (.-length u8))
      acc
      (let [len (+ (* (aget u8 i) 16777216)
                   (* (aget u8 (+ i 1)) 65536)
                   (* (aget u8 (+ i 2)) 256)
                   (aget u8 (+ i 3)))
            type (js/String.fromCharCode
                  (aget u8 (+ i 4)) (aget u8 (+ i 5))
                  (aget u8 (+ i 6)) (aget u8 (+ i 7)))]
        (recur (+ i 12 len) (conj acc {:type type :start i :length len}))))))

(defn- be32-bytes [n]
  [(bit-and (unsigned-bit-shift-right n 24) 0xFF)
   (bit-and (unsigned-bit-shift-right n 16) 0xFF)
   (bit-and (unsigned-bit-shift-right n 8) 0xFF)
   (bit-and n 0xFF)])

(defn- itxt-chunk
  "Complete iTXt chunk bytes (length + type + data + crc) for an
  uncompressed UTF-8 text with empty language/translated fields."
  [kw text]
  (let [enc (js/TextEncoder.)
        kw-b (.encode enc kw)
        txt-b (.encode enc text)
        data-len (+ (.-length kw-b) 5 (.-length txt-b))
        type+data (js/Uint8Array. (+ 4 data-len))]
    ;; "iTXt"
    (.set type+data (js/Uint8Array.from [105 84 88 116]) 0)
    (.set type+data kw-b 4)
    ;; NUL, compression flag 0, compression method 0, empty language NUL,
    ;; empty translated keyword NUL
    (.set type+data (js/Uint8Array.from [0 0 0 0 0]) (+ 4 (.-length kw-b)))
    (.set type+data txt-b (+ 4 (.-length kw-b) 5))
    (let [out (js/Uint8Array. (+ 4 (.-length type+data) 4))]
      (.set out (js/Uint8Array.from (be32-bytes data-len)) 0)
      (.set out type+data 4)
      (.set out (js/Uint8Array.from (be32-bytes (crc32 type+data)))
            (+ 4 (.-length type+data)))
      out)))

(defn embed-text
  "New Uint8Array with the iTXt chunk inserted right after IHDR."
  [u8 kw text]
  (let [ihdr (first (chunk-seq u8))
        cut (+ (:start ihdr) 12 (:length ihdr))
        chunk (itxt-chunk kw text)
        out (js/Uint8Array. (+ (.-length u8) (.-length chunk)))]
    (.set out (.subarray u8 0 cut) 0)
    (.set out chunk cut)
    (.set out (.subarray u8 cut) (+ cut (.-length chunk)))
    out))

(defn embed-many [u8 pairs]
  (reduce (fn [acc [kw text]] (embed-text acc kw text)) u8 pairs))

(defn extract-text
  "Text of the iTXt chunk with the given keyword, or nil."
  [u8 kw]
  (let [dec (js/TextDecoder.)]
    (some (fn [{:keys [type start length]}]
            (when (= type "iTXt")
              (let [data (.subarray u8 (+ start 8) (+ start 8 length))
                    z (loop [i 0]
                        (if (or (>= i (.-length data)) (zero? (aget data i)))
                          i
                          (recur (inc i))))]
                (when (= (.decode dec (.subarray data 0 z)) kw)
                  ;; skip NUL, flag, method, then two NUL-terminated
                  ;; (empty) fields
                  (let [after (loop [i (+ z 3) nulls 0]
                                (if (= nulls 2)
                                  i
                                  (recur (inc i)
                                         (if (zero? (aget data i))
                                           (inc nulls)
                                           nulls))))]
                    (.decode dec (.subarray data after)))))))
          (chunk-seq u8))))
```

- [ ] **Step 4: Green + full JS suite + commit**

Run: `bb build && bb test:js` → PASS.

```bash
git add src/simpleviz/png.cljs test/simpleviz/png_test.cljs
git commit -m "feat: pure iTXt chunk splicing for EDN-carrying PNG exports"
```

---

### Task 3: export embeds the EDN

**Files:**
- Modify: `src/simpleviz/app.cljs`

**Interfaces:**
- Consumes: `png/embed-many`, `/api/source`.
- Produces: `export-png!` fetches the source text(s) (compare: old+new; single: one) and splices them into the blob before download. Fetch failure → plain export (empty pairs), no banner.

- [ ] **Step 1: Implement** (add `[simpleviz.png :as png]` to the ns require)

Add before `export-png!`:

```clojure
(defn- ^:async fetch-source
  "Raw EDN text from /api/source (which = \"old\"|\"new\"|nil), or nil on
  any failure — a failed fetch degrades the export to metadata-less."
  [which]
  (try
    (let [resp (js-await (js/fetch (str "/api/source"
                                        (if (some? which)
                                          (str "?which=" which)
                                          ""))))]
      (if (.-ok resp) (js-await (.text resp)) nil))
    (catch :default _ nil)))
```

Replace `export-png!` with an async version; the toBlob callback splices before downloading:

```clojure
(defn- ^:async export-png! []
  (when-let [sc (:scene @state)]
    (let [g (:graph @state)
          nm (let [f (:file g)]
               (if (some? f) (.replace f (js/RegExp. "\\.edn$") "") "graph"))
          pairs (if (some? (:compare g))
                  (let [o (js-await (fetch-source "old"))
                        n (js-await (fetch-source "new"))]
                    (cond-> []
                      (some? o) (conj ["simpleviz-edn-old" o])
                      (some? n) (conj ["simpleviz-edn-new" n])))
                  (let [s (js-await (fetch-source nil))]
                    (if (some? s) [["simpleviz-edn" s]] [])))
          cnv (canvas/export-canvas sc)]
      (.toBlob cnv
               (fn [blob]
                 (if (some? blob)
                   (-> (.arrayBuffer blob)
                       (.then
                        (fn [buf]
                          (let [out (png/embed-many (js/Uint8Array. buf) pairs)
                                blob2 (js/Blob. [out] {:type "image/png"})
                                url (js/URL.createObjectURL blob2)
                                a (js/document.createElement "a")]
                            (set! (.-href a) url)
                            (set! (.-download a) (str nm ".png"))
                            (.click a)
                            (js/setTimeout
                             (fn [] (js/URL.revokeObjectURL url)) 1000)))))
                   (swap! state assoc :error
                          "PNG export failed — the diagram may be too large")))
               "image/png"))))
```

- [ ] **Step 2: Suites + commit**

Run: `bb build && bb test:js && bb test:clj` → PASS. Verify compiled `app.mjs` references `embed_many` and `/api/source`.

```bash
git add src/simpleviz/app.cljs
git commit -m "feat: exported PNGs embed the source EDN as iTXt metadata"
```

---

### Task 4: `server/png.clj`, `bb extract`, launcher subcommand

**Files:**
- Create: `server/png.clj`
- Create: `test/png_test.clj`
- Create: `test/fixtures/plain-1x1.png`, `test/fixtures/embedded.png`
- Modify: `bb.edn` (new `extract` task, test registration, AND the release `bb.edn` inside `bundle`)
- Modify: `install.sh` (launcher `extract` subcommand + usage)

**Interfaces:**
- Produces: `png/extract [path kw]` → string or nil (throws ex-info "<path> is not a PNG file" on bad signature); `png/-main` implementing the CLI contract (stdout / no-overwrite file / `--old` / fallback keyword order); `bb extract ...` task in dev AND release bb.edn; `simpleviz extract` launcher subcommand (absolutizes paths, runs `bb extract` from `~/.simpleviz`).

- [ ] **Step 1: Create the fixtures**

```bash
mkdir -p test/fixtures
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==' | base64 -d > test/fixtures/plain-1x1.png
```

Generate `embedded.png` VIA THE JS PATH (this is the cross-runtime provenance — record the command in the commit/report):

```bash
bb build   # png.mjs must exist first
node --input-type=module -e '
import * as png from "./public/js/simpleviz/png.mjs";
import fs from "node:fs";
const src = new Uint8Array(fs.readFileSync("test/fixtures/plain-1x1.png"));
const out = png.embed_text(src, "simpleviz-edn", "{:nodes {:a {}}}");
fs.writeFileSync("test/fixtures/embedded.png", Buffer.from(out));'
```

(squint `.mjs` output uses ESM named exports with munged names — `embed_text`. If the one-liner errors on the import, inspect `public/js/simpleviz/png.mjs`'s actual export names and report what differed.)

- [ ] **Step 2: Write the failing tests** (create `test/png_test.clj`; register `png-test` in `bb.edn`'s `test:clj` requires + run-tests list)

```clojure
(ns png-test
  (:require [clojure.test :refer [deftest is]]
            [png]))

(deftest extracts-embedded-edn
  (is (= "{:nodes {:a {}}}"
         (png/extract "test/fixtures/embedded.png" "simpleviz-edn"))))

(deftest missing-keyword-returns-nil
  (is (nil? (png/extract "test/fixtures/embedded.png" "simpleviz-edn-old")))
  (is (nil? (png/extract "test/fixtures/plain-1x1.png" "simpleviz-edn"))))

(deftest non-png-throws
  (is (thrown? Exception (png/extract "README.md" "simpleviz-edn"))))
```

Run: `bb test:clj` → FAIL (ns `png` missing).

- [ ] **Step 3: Implement `server/png.clj`**

```clojure
(ns png
  "Read the EDN embedded in an exported PNG (iTXt chunks written by
  src/simpleviz/png.cljs). Pure babashka — byte walking, no deps."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]))

(def ^:private signature [137 80 78 71 13 10 26 10])

(defn- ub [b] (bit-and b 0xff))

(defn- be32 [bs i]
  (+ (* (ub (aget bs i)) 16777216)
     (* (ub (aget bs (+ i 1))) 65536)
     (* (ub (aget bs (+ i 2))) 256)
     (ub (aget bs (+ i 3)))))

(defn extract
  "Text of the iTXt chunk with the given keyword, or nil. Throws when
  the file is not a PNG."
  [path kw]
  (let [bs (Files/readAllBytes (.toPath (io/file path)))]
    (when (or (< (alength bs) 8)
              (not= signature (mapv (fn [i] (ub (aget bs i))) (range 8))))
      (throw (ex-info (str path " is not a PNG file") {})))
    (loop [i 8]
      (when (< (+ i 8) (alength bs))
        (let [len (be32 bs i)
              type (String. bs (+ i 4) 4 "US-ASCII")
              next-i (+ i 12 len)]
          (if (= type "iTXt")
            (let [data-off (+ i 8)
                  z (loop [j 0]
                      (if (or (>= j len) (zero? (aget bs (+ data-off j))))
                        j
                        (recur (inc j))))
                  k (String. bs data-off z "UTF-8")]
              (if (= k kw)
                (let [after (loop [j (+ z 3) nulls 0]
                              (if (= nulls 2)
                                j
                                (recur (inc j)
                                       (if (zero? (aget bs (+ data-off j)))
                                         (inc nulls)
                                         nulls))))]
                  (String. bs (+ data-off after) (- len after) "UTF-8"))
                (recur next-i)))
            (recur next-i)))))))

(defn -main
  "bb extract <diagram.png> [out.edn] [--old]"
  [& args]
  (let [old? (boolean (some #{"--old"} args))
        [in out] (vec (remove #{"--old"} args))]
    (when (nil? in)
      (println "usage: bb extract <diagram.png> [out.edn] [--old]")
      (System/exit 1))
    (let [text (try
                 (if old?
                   (extract in "simpleviz-edn-old")
                   (or (extract in "simpleviz-edn-new")
                       (extract in "simpleviz-edn")))
                 (catch Exception e
                   (println (ex-message e))
                   (System/exit 1)))]
      (cond
        (nil? text)
        (do (println (str "no embedded simpleviz EDN"
                          (when old? " (old)") " found in " in))
            (System/exit 1))

        (nil? out) (print text)

        (.exists (io/file out))
        (do (println (str out " already exists")) (System/exit 1))

        :else (do (spit out text) (println (str "wrote " out)))))))
```

- [ ] **Step 4: `bb.edn`** — add the task (dev) and register the test ns:

```clojure
  extract  {:doc "Extract embedded EDN from an exported PNG: bb extract diagram.png [out.edn] [--old]"
            :requires ([png])
            :task (apply png/-main *command-line-args*)}
```

AND inside the `bundle` task's quoted `release-bb` `:tasks` map, add the same `extract` entry next to `serve`.

- [ ] **Step 5: launcher** (`install.sh`, inside the `LAUNCHER` heredoc): usage gains
`       simpleviz extract <diagram.png> [out.edn] [--old]   print/extract the embedded EDN`
and the case dispatch gains, before the default branch:

```bash
  extract)
    shift
    check_bb
    [ -d "$SIMPLEVIZ_HOME" ] || die "$SIMPLEVIZ_HOME not found — run install.sh first"
    args=()
    for a in "$@"; do
      case "$a" in
        --old) args+=("$a") ;;
        *) args+=("$(realpath -m "$a")") ;;
      esac
    done
    (cd "$SIMPLEVIZ_HOME" && exec bb extract "${args[@]}")
    ;;
```

- [ ] **Step 6: Green + gates + commit**

Run: `bb test:clj` → PASS; `bash -n install.sh`; source-guard launcher extraction + `bash -n` on it.

```bash
git add server/png.clj test/png_test.clj test/fixtures bb.edn install.sh
git commit -m "feat: simpleviz extract reads the EDN back out of exported PNGs"
```

---

### Task 5: docs + live round-trip e2e

**Files:**
- Modify: `README.md`
- Modify: `plugins/simpleviz/skills/simpleviz/SKILL.md`

**Interfaces:** docs match behavior; live cross-runtime round-trip verified.

- [ ] **Step 1: README** — add a short section after "Data format" (before "Comparing two versions"):

```markdown
## Exporting

The ⇩ button downloads the diagram as a PNG of the whole graph. The
image embeds the source EDN as metadata (`iTXt`, keyword
`simpleviz-edn`; compare mode embeds both files), so an exported
picture is never a dead end:

    simpleviz extract diagram.png            # print the embedded EDN
    simpleviz extract diagram.png graph.edn  # write it (won't overwrite)
    simpleviz extract diagram.png --old      # compare exports: the old side
```

- [ ] **Step 2: Skill** (RED probe first, per writing-skills): probe question "I have a PNG exported from simpleviz — can I get the graph EDN back out of it, and how?" against the current skill (expect: not covered). Then add to the Running section's launcher block:

```
    simpleviz extract diagram.png    # print the EDN embedded in an exported PNG
```

and to the Viewer section, after the collapse sentence: `The ⇩ button exports the whole diagram as a PNG with the source EDN embedded as metadata (recoverable via simpleviz extract).` GREEN probe: same question answered correctly. Record both probe outputs.

- [ ] **Step 3: Live cross-runtime round-trip e2e** (record actual output)

```bash
S=$(mktemp -d)
S="$S" node --input-type=module -e '
import * as png from "./public/js/simpleviz/png.mjs";
import fs from "node:fs";
const src = new Uint8Array(fs.readFileSync("test/fixtures/plain-1x1.png"));
const edn = fs.readFileSync("examples/demo.edn", "utf8");
const out = png.embed_text(src, "simpleviz-edn", edn);
fs.writeFileSync(process.env.S + "/rt.png", Buffer.from(out));'
bb extract "$S/rt.png" "$S/rt.edn"
diff examples/demo.edn "$S/rt.edn" && echo "ROUND-TRIP BYTE-EQUAL"
rm -rf "$S"
```

Expected: `ROUND-TRIP BYTE-EQUAL`. Also `bb test` fully green.

- [ ] **Step 4: Commit**

```bash
git add README.md plugins/simpleviz/skills/simpleviz/SKILL.md
git commit -m "docs: PNG export metadata and the extract command"
```
