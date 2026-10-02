# Headless Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> Task graph: `.blend/specs/2026-10-02-headless-export-tasks.edn`. Set each task's `:state` alongside its checkbox: `:in-progress` when you start it, `:done` once its review is clean, `:blocked` plus a `:reason` attribute when stuck.

**Goal:** `simpleviz export <in> [<suffix>] <out.png|out.svg> [--theme t] [--force]` writes the ⇩ export of the fully expanded graph, made by the page in a headless Chrome.

**Architecture:** The CLI starts the server in-process, launches the user's Chrome/Chromium headless (`server/browser.clj`), and over CDP (babashka's built-in WebSocket client) navigates to the page and awaits a new page hook, `window.simplevizExport`, which waits for layout, expands all boxes, applies the theme to the page only and returns the bytes from the same producers the ⇩ menu uses.

**Tech Stack:** babashka (babashka.process, babashka.http-client + .websocket, cheshire), squint-cljs page code, Chrome DevTools Protocol.

**Spec:** `docs/superpowers/specs/2026-10-02-headless-export-design.md`

## Global Constraints

- No new dependencies: bb built-ins only on the server, nothing new in package.json.
- Messages print as `simpleviz: <reason>` on stderr, exit 1; success prints `wrote <out>`, exit 0.
- The output is never overwritten without `--force`.
- `--theme` takes only `themes/NAMES`; a file's `:theme` still wins; the theme never reaches browser storage.
- Browser discovery order: `SIMPLEVIZ_BROWSER`, then PATH `google-chrome google-chrome-stable chromium chromium-browser microsoft-edge brave-browser`, then the macOS apps.
- Snap browsers get their profile in `~/snap/<name>/common/`; others in the system temp dir; the profile is deleted after the browser has exited, in a `finally`.
- Timeouts: 15 s for the DevTools line, 120 s for the hook.
- squint: `nil?` is loose, keywords are strings, a false `when` is `undefined` — never strict-assert nil in JS tests.

## Review Focus

1. A graph with a parse error: the export must fail with the page's `Graph error: …`, not time out after 120 s.
2. A big graph (`> 500` nodes opens collapsed): the export must contain every box expanded (Task 1 test on `export-readiness` with a collapsed set; Task 4 e2e asserts a collapsed-on-open graph exports expanded).
3. Chrome's stderr must keep draining after the DevTools line, or a chatty browser blocks on a full pipe (Task 3: the drain runs for the browser's lifetime).
4. An interrupted or failing export must not leave a browser process or profile folder behind (Task 3 test: after `with-browser` throws, the process is dead and the folder gone).
5. The output path in a folder that doesn't exist: fail with a clear message, not a stack trace (Task 4: `cannot write <out>: …`).

---

### Task 1: Page hook and shared producers

**Files:**
- Modify: `src/simpleviz/editor.cljs` (add `load-readiness`, `export-readiness`)
- Modify: `src/simpleviz/png.cljs` (add `bytes->base64`)
- Modify: `src/simpleviz/app.cljs` (split `export-png!`/`export-svg!`, add the hook)
- Test: `test/simpleviz/editor_test.cljs`, `test/simpleviz/png_test.cljs`

**Interfaces:**
- Produces: `window.simplevizExport({format: "png"|"svg", theme: string|null})` → Promise of `{data: string}` (base64 for PNG, SVG text), rejecting with an `Error` whose message is the page's error text.

- [ ] **Step 1: failing JS tests**

In `test/simpleviz/editor_test.cljs`:

```clojure
(test "load-readiness waits for a graph or an error"
  (fn []
    (assert/ok (nil? (editor/load-readiness {:graph nil :error nil})))
    (assert/equal (:ready (editor/load-readiness {:graph {:nodes {}} :error nil})) true)
    (assert/equal (:error (editor/load-readiness {:graph nil :error "Graph error: x"})) "Graph error: x")))

(test "export-readiness wants a settled, fully expanded scene"
  (fn []
    (let [base {:scene {:items []} :layouting false :collapsed-boxes (js/Set.) :error nil}]
      (assert/equal (:ready (editor/export-readiness base)) true)
      (assert/ok (nil? (editor/export-readiness (assoc base :layouting true))))
      (assert/ok (nil? (editor/export-readiness (assoc base :collapsed-boxes (js/Set. ["a"])))))
      (assert/ok (nil? (editor/export-readiness (assoc base :scene nil))))
      (assert/equal (:error (editor/export-readiness (assoc base :scene nil :error "Render error: x")))
                    "Render error: x")
      ;; with a scene, an error is a notice-level leftover, not a failure
      (assert/equal (:ready (editor/export-readiness (assoc base :error "old"))) true))))
```

In `test/simpleviz/png_test.cljs`:

```clojure
(test "bytes->base64 matches Buffer's encoding, also past one chunk"
  (fn []
    (let [u8 (js/Uint8Array.from (js/Array.from {:length 70000} (fn [_ i] (mod i 256))))]
      (assert/equal (png/bytes->base64 u8) (.toString (js/Buffer.from u8) "base64")))))
```

- [ ] **Step 2: run** `bb build && bb test:js` — expect the three tests to fail (functions missing).

- [ ] **Step 3: implement the pure parts**

`editor.cljs`:

```clojure
(defn load-readiness
  "Where the first load stands for a headless export: {:ready true} once a
  graph arrived, {:error msg} when only an error did, nil while waiting."
  [st]
  (cond (some? (:graph st)) {:ready true}
        (some? (:error st)) {:error (:error st)}
        :else nil))

(defn export-readiness
  "Whether page state st can be exported whole: {:ready true} for a laid
  out scene with no box collapsed, {:error msg} when the page shows an
  error and has nothing laid out, nil while layout is still going."
  [st]
  (cond (and (some? (:scene st)) (not (:layouting st))
             (zero? (.-size (:collapsed-boxes st)))) {:ready true}
        (and (nil? (:scene st)) (some? (:error st))) {:error (:error st)}
        :else nil))
```

`png.cljs`:

```clojure
(defn bytes->base64
  "Base64 of a Uint8Array, built in 32 KiB chunks so big exports don't
  overflow String.fromCharCode's argument limit."
  [u8]
  (let [parts #js []]
    (loop [i 0]
      (when (< i (.-length u8))
        (.push parts (.apply js/String.fromCharCode nil (.subarray u8 i (+ i 32768))))
        (recur (+ i 32768))))
    (js/btoa (.join parts ""))))
```

- [ ] **Step 4: split the producers in `app.cljs`** (replace `export-png!` and `export-svg!`):

```clojure
(defn- ^:async png-bytes
  "The PNG ⇩ downloads, as a Uint8Array, the sources embedded (plain if
  embedding fails). Throws when the canvas cannot be encoded."
  []
  (let [pairs (js-await (export-sources (:graph @state)))
        cnv (canvas/export-canvas (:scene @state))
        blob (js-await (js/Promise. (fn [res] (.toBlob cnv res "image/png"))))]
    (when (nil? blob)
      (throw (js/Error. "PNG export failed — the diagram may be too large")))
    (let [u8 (js/Uint8Array. (js-await (.arrayBuffer blob)))]
      (try (png/embed-many u8 pairs)
           (catch :default _ u8)))))

(defn- ^:async svg-text
  "The SVG ⇩ downloads, the sources embedded (see svg/svg-document)."
  []
  (canvas/export-svg (:scene @state) (js-await (export-sources (:graph @state)))))

(defn- ^:async export-png! []
  (when (some? (:scene @state))
    (let [nm (export-name (:graph @state))]
      (try (download-blob! (js/Blob. [(js-await (png-bytes))] {:type "image/png"}) nm "png")
           (catch :default e (swap! state assoc :notice (.-message e)))))))

(defn- ^:async export-svg!
  "Download the whole diagram as SVG. A failure shows in the error banner,
  as the PNG export's does, rather than as an unseen rejected promise."
  []
  (when (some? (:scene @state))
    (let [nm (export-name (:graph @state))]
      (try (download-blob! (js/Blob. [(js-await (svg-text))] {:type "image/svg+xml"}) nm "svg")
           (catch :default e
             (swap! state assoc :notice (str "SVG export failed — " (or (.-message e) (str e)))))))))
```

- [ ] **Step 5: the hook** (after the producers; `relayout!`, `apply-theme!`, `effective-theme` are defined above it):

```clojure
(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- ^:async wait-for!
  "Resolve once (readiness @state) is {:ready true}; reject with its
  :error. Polls every 50 ms — the CLI bounds the wait."
  [readiness]
  (let [r (readiness @state)]
    (cond (some? (:error r)) (throw (js/Error. (:error r)))
          (:ready r) true
          :else (do (js-await (sleep 50)) (js-await (wait-for! readiness))))))

(defn- ^:async headless-export
  "window.simplevizExport: wait for the graph, take `theme` as this page's
  theme preference (never stored), expand every box, wait for the layout,
  then hand back what the ⇩ menu would download — {:data base64} for
  \"png\", {:data svg-text} for \"svg\"."
  [opts]
  (let [fmt (.-format opts)
        theme (.-theme opts)]
    (js-await (wait-for! editor/load-readiness))
    (when (some? theme)
      (apply-theme! (effective-theme (:graph @state) theme (:theme @state)))
      (swap! state assoc :theme-pref theme))
    (when (pos? (.-size (:collapsed-boxes @state)))
      (swap! state assoc :collapsed-boxes #{} :selected nil)
      (js-await (relayout!)))
    (js-await (wait-for! editor/export-readiness))
    (if (= fmt "svg")
      {:data (js-await (svg-text))}
      {:data (png/bytes->base64 (js-await (png-bytes)))})))
```

and at init, next to `(canvas/set-repaint! paint-now!)`:

```clojure
;; the headless export's entry point (simpleviz export, server/browser.clj)
(set! (.-simplevizExport js/window) headless-export)
```

- [ ] **Step 6:** `bb build && bb test:js` — all pass. Then serve `examples/demo.edn`, open the page in a browser, ⇩ → PNG and ⇩ → SVG still download (`simpleviz extract` on both gives demo.edn), and in the console `await simplevizExport({format: "svg", theme: "nord"})` resolves with SVG text holding `fill="#2e3440"`.

- [ ] **Step 7: commit** `feat(page): window.simplevizExport — the ⇩ export, expanded and settled, for headless runs`.

---

### Task 2: Finding the browser

**Files:**
- Create: `server/browser.clj`
- Test: `test/browser_test.clj`; add `browser-test` to `test:clj` in `bb.edn` (both lists)

**Interfaces:**
- Produces: `(browser/find-browser)` / `(browser/find-browser {:env f :which f :exists? f})` → `{:path p}` or `{:error msg}`; `(browser/snap-name path resolved)` → string or nil; `(browser/profile-parent snap home tmp)` → string; `(browser/devtools-url line)` → string or nil.

- [ ] **Step 1: failing tests** (`test/browser_test.clj`):

```clojure
(ns browser-test
  (:require [browser]
            [clojure.test :refer [deftest is]]))

(defn- finder [{:keys [env on-path files]}]
  (browser/find-browser {:env #(get env %) :which #(get on-path %) :exists? #(contains? (set files) %)}))

(deftest the-override-wins
  (is (= {:path "/opt/c/chrome"}
         (finder {:env {"SIMPLEVIZ_BROWSER" "/opt/c/chrome"} :files ["/opt/c/chrome"]
                  :on-path {"chromium" "/usr/bin/chromium"}})))
  (is (= {:path "/usr/bin/brave"} (finder {:env {"SIMPLEVIZ_BROWSER" "brave"} :on-path {"brave" "/usr/bin/brave"}}))))

(deftest an-override-that-is-not-there-is-an-error
  (is (= {:error "SIMPLEVIZ_BROWSER=nope not found"}
         (finder {:env {"SIMPLEVIZ_BROWSER" "nope"} :on-path {"chromium" "/usr/bin/chromium"}}))))

(deftest path-order-then-mac-apps
  (is (= {:path "/usr/bin/google-chrome"}
         (finder {:on-path {"chromium" "/usr/bin/chromium" "google-chrome" "/usr/bin/google-chrome"}})))
  (is (= {:path "/Applications/Chromium.app/Contents/MacOS/Chromium"}
         (finder {:files ["/Applications/Chromium.app/Contents/MacOS/Chromium"]}))))

(deftest nothing-found
  (is (= {:error "export needs Chrome or Chromium — install one or set SIMPLEVIZ_BROWSER"} (finder {}))))

(deftest snaps-are-recognized
  (is (= "chromium" (browser/snap-name "/snap/bin/chromium" "/usr/bin/snap")))
  (is (= "chromium" (browser/snap-name "/snap/chromium/3235/usr/lib/chromium-browser/chrome"
                                       "/snap/chromium/3235/usr/lib/chromium-browser/chrome")))
  (is (nil? (browser/snap-name "/usr/bin/google-chrome" "/opt/google/chrome/chrome"))))

(deftest profile-parent-follows-the-snap
  (is (= "/home/u/snap/chromium/common" (browser/profile-parent "chromium" "/home/u" "/tmp")))
  (is (= "/tmp" (browser/profile-parent nil "/home/u" "/tmp"))))

(deftest the-devtools-line
  (is (= "ws://127.0.0.1:42001/devtools/browser/f0dc"
         (browser/devtools-url "DevTools listening on ws://127.0.0.1:42001/devtools/browser/f0dc")))
  (is (nil? (browser/devtools-url "[1002/080707.580414:ERROR:ssl_client_socket_impl.cc] handshake failed"))))
```

- [ ] **Step 2: run** `bb -e "(require 'clojure.test 'browser-test) (clojure.test/run-tests 'browser-test)"` — fails (no `browser` ns).

- [ ] **Step 3: implement** (`server/browser.clj`):

```clojure
(ns browser
  "Headless Chrome for `simpleviz export`: find the user's browser, start
  it with a throwaway profile, and drive the page over the DevTools
  protocol (CDP) — babashka built-ins only."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def candidates
  "Browser commands looked for on PATH, in this order."
  ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "microsoft-edge" "brave-browser"])

(def mac-apps
  ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
   "/Applications/Chromium.app/Contents/MacOS/Chromium"
   "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
   "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"])

(defn find-browser
  "{:path p} of the browser to run, or {:error msg}: SIMPLEVIZ_BROWSER (a
  path or a command) wins, then the candidates on PATH, then the macOS
  apps. env, which and exists? are injectable for tests."
  ([] (find-browser {:env #(System/getenv %)
                     :which #(some-> (fs/which %) str)
                     :exists? #(and (fs/regular-file? %) (fs/executable? %))}))
  ([{:keys [env which exists?]}]
   (if-let [v (not-empty (env "SIMPLEVIZ_BROWSER"))]
     (if-let [p (if (exists? v) v (which v))]
       {:path p}
       {:error (str "SIMPLEVIZ_BROWSER=" v " not found")})
     (if-let [p (or (some which candidates) (first (filter exists? mac-apps)))]
       {:path p}
       {:error "export needs Chrome or Chromium — install one or set SIMPLEVIZ_BROWSER"}))))

(defn snap-name
  "The snap a browser runs in, or nil: a command resolving to
  /usr/bin/snap is that snap's launcher, named like the snap; a path under
  /snap/ names it after /snap/."
  [path resolved]
  (cond (= resolved "/usr/bin/snap") (str (fs/file-name path))
        (str/starts-with? (str path) "/snap/") (nth (str/split (str path) #"/" 4) 2)
        :else nil))

(defn profile-parent
  "Where the throwaway profile goes: a snap can't see the host's /tmp or
  hidden folders in home, but can see ~/snap/<name>/common."
  [snap home tmp]
  (if snap (str home "/snap/" snap "/common") (str tmp)))

(defn devtools-url
  "The browser WebSocket URL Chrome prints on stderr, or nil."
  [line]
  (second (re-find #"DevTools listening on (ws://\S+)" (str line))))
```

- [ ] **Step 4: run** the browser tests — pass. Add `browser-test` to both `test:clj` lists in `bb.edn`; `bb test:clj` green.

- [ ] **Step 5: commit** `feat(export): find the browser, snap-aware profile folder`.

---

### Task 3: Launching Chrome and talking CDP

**Files:**
- Modify: `server/browser.clj`
- Test: `test/browser_test.clj` (real-browser tests, skipped without one)

**Interfaces:**
- Consumes: Task 2's `find-browser`, `snap-name`, `profile-parent`, `devtools-url`.
- Produces: `(browser/with-browser path f)` → calls `(f ws-url profile)` with a running headless browser, always stopping it and deleting its profile; `(browser/export-page! ws-url page-url {:format f :theme t :timeout-ms n})` → string (base64 or SVG text), throws `ex-info` with the user-facing message.

- [ ] **Step 1: failing tests** (append to `test/browser_test.clj`; they need a real browser):

```clojure
(def ^:private found (browser/find-browser))

(defmacro ^:private with-real-browser [& body]
  `(if-let [~'path (:path found)]
     (do ~@body)
     (println "browser-test: no browser found, real-browser tests skipped")))

(deftest a-browser-starts-answers-and-is-cleaned-up
  (with-real-browser
    (let [seen (atom nil)]
      (browser/with-browser path
        (fn [ws-url profile]
          (reset! seen profile)
          (is (str/starts-with? ws-url "ws://127.0.0.1:"))
          (is (fs/directory? profile))))
      (is (not (fs/exists? @seen)) "profile folder deleted"))))

(deftest a-failure-inside-still-cleans-up
  (with-real-browser
    (let [seen (atom nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (browser/with-browser path
                              (fn [_ profile] (reset! seen profile) (throw (ex-info "boom" {}))))))
      (is (not (fs/exists? @seen))))))

(deftest evaluate-returns-values-and-page-errors
  (with-real-browser
    (browser/with-browser path
      (fn [ws-url _]
        (let [page (browser/open-page ws-url)]
          (try
            (is (= {:data "ok"} (browser/evaluate page "Promise.resolve({data: 'ok'})" 5000)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Graph error: x$"
                                  (browser/evaluate page "Promise.reject(new Error('Graph error: x'))" 5000)))
            (finally (browser/close-page page))))))))
```

(`with-browser`'s `f` takes `[ws-url profile]` — the profile is passed for the test; callers ignore it.) Require `[babashka.fs :as fs] [clojure.string :as str]` in the test ns.

- [ ] **Step 2: run** — fails (functions missing).

- [ ] **Step 3: implement** (append to `server/browser.clj`, add requires `[babashka.http-client :as http] [babashka.http-client.websocket :as ws] [babashka.process :as p] [cheshire.core :as json] [clojure.java.io :as io]`):

```clojure
(def start-timeout-ms 15000)

(defn- profile-dir!
  "A fresh profile folder the browser at `path` can use."
  [path]
  (let [resolved (str (fs/real-path path))
        parent (profile-parent (snap-name path resolved) (str (fs/home))
                               (System/getProperty "java.io.tmpdir"))]
    (fs/create-dirs parent)
    (str (fs/create-temp-dir {:dir parent :prefix "simpleviz-export-"}))))

(defn- drain!
  "Read the browser's stderr for its whole life — a full pipe would block
  it. Delivers the DevTools URL to `url` (or :exited when stderr ends
  first) and keeps the last lines in `tail` for error messages."
  [proc url tail]
  (future
    (with-open [r (io/reader (:err proc))]
      (loop []
        (if-let [line (.readLine r)]
          (do (swap! tail #(vec (take-last 5 (conj % line))))
              (when-let [u (devtools-url line)] (deliver url u))
              (recur))
          (deliver url :exited))))))

(defn- stop! [proc profile]
  (p/destroy-tree proc)
  (deref proc 10000 nil)
  ;; Chrome may still be flushing the profile as it exits
  (loop [n 0]
    (when (and (fs/exists? profile)
               (not (try (fs/delete-tree profile) true (catch Exception _ false)))
               (< n 20))
      (Thread/sleep 100)
      (recur (inc n)))))

(defn with-browser
  "Start the browser at `path` headless with a throwaway profile, call
  (f ws-url profile), and always stop it and delete the profile."
  [path f]
  (let [profile (profile-dir! path)
        proc (p/process [path "--headless" "--remote-debugging-port=0"
                         (str "--user-data-dir=" profile)
                         "--no-first-run" "--no-default-browser-check" "about:blank"]
                        {:out :discard :err :pipe})
        url (promise)
        tail (atom [])
        nm (str (fs/file-name path))]
    (try
      (drain! proc url tail)
      (let [u (deref url start-timeout-ms nil)]
        (cond (nil? u) (throw (ex-info (str nm " did not start within 15 s") {}))
              (= :exited u) (throw (ex-info (str "could not start " nm ": "
                                                 (str/join " | " @tail)) {}))
              :else (f u profile)))
      (finally (stop! proc profile)))))

(defn open-page
  "A CDP connection to the browser's about:blank page:
  {:conn ws :pending (atom {id promise}) :events (atom {method promise}) :ids (atom n)}."
  [ws-url]
  (let [port (second (re-find #"ws://127\.0\.0\.1:(\d+)/" ws-url))
        targets (json/parse-string (:body (http/get (str "http://127.0.0.1:" port "/json/list"))) true)
        target (first (filter #(and (= "page" (:type %)) (= "about:blank" (:url %))) targets))
        pending (atom {})
        events (atom {})
        buf (StringBuilder.)
        conn (ws/websocket
              {:uri (:webSocketDebuggerUrl target)
               :on-message (fn [_ data last?]
                             (.append buf (str data))
                             (when last?
                               (let [m (json/parse-string (str buf) true)]
                                 (.setLength buf 0)
                                 (if-let [id (:id m)]
                                   (some-> (get @pending id) (deliver m))
                                   (some-> (get @events (:method m)) (deliver m))))))})]
    {:conn conn :pending pending :events events :ids (atom 0)}))

(defn close-page [{:keys [conn]}] (ws/close! conn))

(defn- call
  "Send a CDP command and wait for its reply's :result; ex-info on a CDP
  error or after timeout-ms (named `what` in the message)."
  [{:keys [conn pending ids]} method params timeout-ms what]
  (let [id (swap! ids inc)
        reply (promise)]
    (swap! pending assoc id reply)
    (ws/send! conn (json/generate-string {:id id :method method :params params}))
    (let [m (deref reply timeout-ms nil)]
      (swap! pending dissoc id)
      (cond (nil? m) (throw (ex-info (str what " did not finish within " (quot timeout-ms 1000) " s") {}))
            (:error m) (throw (ex-info (str method ": " (get-in m [:error :message])) {}))
            :else (:result m)))))

(defn- exception-message
  "The message of a rejected evaluate: the Error's message, else its description."
  [details]
  (let [ex (:exception details)]
    (or (some-> (:description ex) str/split-lines first (str/replace #"^Error: " ""))
        (:text details))))

(defn evaluate
  "Await the JS expression's promise in the page; its JSON value, keys
  keywordized. A rejection throws ex-info with the error's message."
  [page expr timeout-ms]
  (let [r (call page "Runtime.evaluate" {:expression expr :awaitPromise true :returnByValue true}
                timeout-ms "export")]
    (if-let [d (:exceptionDetails r)]
      (throw (ex-info (exception-message d) {}))
      (get-in r [:result :value]))))

(defn navigate!
  "Load url in the page and wait for its load event."
  [{:keys [events] :as page} url]
  (let [loaded (promise)]
    (swap! events assoc "Page.loadEventFired" loaded)
    (call page "Page.enable" {} start-timeout-ms "the page")
    (call page "Page.navigate" {:url url} start-timeout-ms "the page")
    (when (nil? (deref loaded start-timeout-ms nil))
      (throw (ex-info "the page did not load within 15 s" {})))))

(defn export-page!
  "Navigate to page-url and return window.simplevizExport's data: base64
  for \"png\", SVG text for \"svg\"."
  [ws-url page-url {:keys [format theme timeout-ms]}]
  (let [page (open-page ws-url)]
    (try
      (navigate! page page-url)
      (:data (evaluate page (str "window.simplevizExport(" (json/generate-string {:format format :theme theme}) ")")
                       timeout-ms))
      (finally (close-page page)))))
```

Note: the module script may not have run at the load event in every browser; if `window.simplevizExport` is undefined, the evaluate fails with "is not a function". If the e2e test shows that, wrap the expression: `(async () => { while (!window.simplevizExport) await new Promise(r => setTimeout(r, 50)); return window.simplevizExport(<opts>); })()`.

- [ ] **Step 4: run** the browser tests (needs Chrome/Chromium) — pass; `bb test:clj` green.

- [ ] **Step 5: commit** `feat(export): start headless Chrome and talk CDP over bb's WebSocket client`.

---

### Task 4: The `export` command

**Files:**
- Modify: `server/cli.clj` (arg parsing, shared input checks, `export-cmd`, usage, dispatch)
- Modify: `bb.edn` (`export` task in both task maps, `export-test` in both test lists)
- Test: `test/cli_test.clj` (pre-browser errors), `test/export_test.clj` (end to end)

**Interfaces:**
- Consumes: Task 3's `with-browser`, `export-page!`; Task 2's `find-browser`; Task 1's hook.
- Produces: `(cli/parse-export-args args)` → `{:in :suffix :out :format :theme :force}` or `{:error msg}`.

- [ ] **Step 1: failing tests.** `test/cli_test.clj`:

```clojure
(deftest export-args-parse
  (is (= {:in "g.edn" :suffix nil :out "g.png" :format "png" :theme nil :force false}
         (cli/parse-export-args ["g.edn" "g.png"])))
  (is (= {:in "g.edn" :suffix "next" :out "d.SVG" :format "svg" :theme "nord" :force true}
         (cli/parse-export-args ["g.edn" "next" "d.SVG" "--theme" "nord" "--force"])))
  (is (re-find #"usage" (:error (cli/parse-export-args ["g.edn"]))))
  (is (re-find #"\.png or \.svg" (:error (cli/parse-export-args ["g.edn" "g.jpg"]))))
  (is (re-find #"unknown theme: neon" (:error (cli/parse-export-args ["g.edn" "g.png" "--theme" "neon"]))))
  (is (re-find #"unknown option: --nope" (:error (cli/parse-export-args ["g.edn" "g.png" "--nope"]))))
  (is (re-find #"invalid suffix" (:error (cli/parse-export-args ["g.edn" "a/b" "g.png"])))))

(deftest export-refuses-before-any-browser
  (with-tmp
    (fn [tmp]
      (spit (str (fs/path tmp "g.edn")) "{:nodes {:a {}}}")
      (spit (str (fs/path tmp "g.png")) "x")
      (let [res (run-cli ["export" "g.edn" "g.png"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (= "simpleviz: g.png already exists (--force overwrites)" (str/trim (:err res)))))
      (let [res (run-cli ["export" "nope.edn" "x.png"] :dir tmp)]
        (is (= "simpleviz: file not found: nope.edn" (str/trim (:err res)))))
      (let [res (run-cli ["export" "g.edn" "next" "x.png"] :dir tmp)]
        (is (str/includes? (:err res) "g-next.edn not found"))))))

(deftest export-of-an-export-without-edn-is-refused
  (let [res (run-cli ["export" (str proc-util/repo-root "/test/fixtures/plain.svg") "out.png"])]
    (is (= 1 (:exit res)))
    (is (str/starts-with? (:err res) "simpleviz: no embedded simpleviz EDN found"))))
```

`test/export_test.clj` (end to end; add `export-test` to both `test:clj` lists):

```clojure
(ns export-test
  "simpleviz export end to end: the CLI as a process, a real headless
  browser. Skipped when no browser is found — except on CI, which ships
  Chrome and must run it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [browser]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [embedded]
            [png]
            [proc-util]
            [svg]))

(def ^:private found (browser/find-browser))

(defn- export! [tmp & args]
  (select-keys (apply p/shell {:dir (str tmp) :out :string :err :string :continue true}
                      "bb" "--config" (str proc-util/repo-root "/bb.edn") "-m" "cli" "export" args)
               [:out :err :exit]))

(defmacro ^:private e2e [name & body]
  `(deftest ~name
     (cond (:path found)
           (let [~'tmp (fs/create-temp-dir {:prefix "export-test"})]
             (try (fs/copy-tree (str proc-util/repo-root "/examples") ~'tmp) ~@body
                  (finally (fs/delete-tree ~'tmp))))
           (System/getenv "CI") (is false (str "CI needs a browser: " (:error found)))
           :else (println "export-test: no browser found, skipped"))))

(e2e png-and-svg-carry-the-source
  (let [r (export! tmp "demo.edn" "demo.png")]
    (is (= 0 (:exit r)) (:err r))
    (is (= "wrote demo.png" (str/trim (:out r)))))
  (is (png/png? (str (fs/path tmp "demo.png"))))
  (is (= (slurp (str (fs/path tmp "demo.edn"))) (embedded/extract (str (fs/path tmp "demo.png")) "simpleviz-edn")))
  (is (= 0 (:exit (export! tmp "demo.edn" "demo.svg"))))
  (is (= (slurp (str (fs/path tmp "demo.edn"))) (svg/extract (str (fs/path tmp "demo.svg")) "simpleviz-edn"))))

(e2e a-compare-export-embeds-both-sides
  (is (= 0 (:exit (export! tmp "demo.edn" "next" "diff.svg"))))
  (let [f (str (fs/path tmp "diff.svg"))]
    (is (= (slurp (str (fs/path tmp "demo.edn"))) (svg/extract f "simpleviz-edn-old")))
    (is (= (slurp (str (fs/path tmp "demo-next.edn"))) (svg/extract f "simpleviz-edn-new")))))

(e2e the-theme-paints-the-export
  (is (= 0 (:exit (export! tmp "demo.edn" "nord.svg" "--theme" "nord"))))
  (is (str/includes? (slurp (str (fs/path tmp "nord.svg"))) "fill=\"#2e3440\"")))

(e2e a-big-graph-exports-expanded
  ;; > 500 nodes opens as a collapsed overview; the export must not
  (let [nodes (into {} (for [i (range 520)] [(keyword (str "n" i)) {}]))
        g {:nodes nodes :boxes {:big {:components (set (keys nodes))}}}]
    (spit (str (fs/path tmp "big.edn")) (pr-str g))
    (is (= 0 (:exit (export! tmp "big.edn" "big.svg"))))
    (is (str/includes? (slurp (str (fs/path tmp "big.svg"))) ">n519<"))))

(e2e a-graph-error-is-the-pages-message
  (spit (str (fs/path tmp "broken.edn")) "{:nodes {:a {}} :edges [[:a")
  (let [r (export! tmp "broken.edn" "x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: Graph error:") (:err r))
    (is (not (fs/exists? (fs/path tmp "x.png"))))))

(e2e an-unwritable-output-is-a-clear-error
  (let [r (export! tmp "demo.edn" "no/such/dir/x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: cannot write no/such/dir/x.png") (:err r))))
```

Check a graph with a parse error really reaches the page: `serve/start!` reads both sides but only *parses* in the page route, so startup passes and `/api/graph` returns `{:error …}` — the page shows `Graph error: …`, the hook rejects with it. If startup refuses instead, assert that message.

- [ ] **Step 2: run** — the cli tests fail (`parse-export-args` missing, `export` unknown); the e2e tests fail.

- [ ] **Step 3: implement in `server/cli.clj`.** Add `[browser]` and `[themes]` to the requires. Then:

```clojure
(def export-usage
  "usage: simpleviz export <graph.edn|.png|.svg> [<suffix>] <out.png|out.svg> [--theme <name>] [--force]")

(defn parse-export-args
  "export's arguments -> {:in :suffix :out :format :theme :force}, or
  {:error msg} for a usage problem."
  [args]
  (loop [[a & more] args, pos [], theme nil, force false]
    (cond
      (nil? a)
      (let [[in suffix out] (if (= 3 (count pos)) pos [(first pos) nil (second pos)])
            ext (some->> out (re-find #"(?i)\.(png|svg)$") second str/lower-case)
            names (set (map name themes/NAMES))]
        (cond (not (#{2 3} (count pos))) {:error export-usage}
              (nil? ext) {:error (str "the output must end in .png or .svg: " out)}
              (and (some? suffix) (not (re-matches serve/suffix-re suffix))) {:error (str "invalid suffix: " suffix)}
              (and (some? theme) (not (names theme)))
              {:error (str "unknown theme: " theme " (one of " (str/join ", " (map name themes/NAMES)) ")")}
              :else {:in in :suffix suffix :out out :format ext :theme theme :force force}))
      (= a "--force") (recur more pos theme true)
      (= a "--theme") (if (seq more) (recur (rest more) pos (first more) force) {:error export-usage})
      (str/starts-with? a "-") {:error (str "unknown option: " a " (see simpleviz --help)")}
      :else (recur more (conj pos a) theme force))))
```

Pull the input checks out of `serve-cmd` so both commands give the same messages:

```clojure
(defn- check-input!
  "The checks `simpleviz <file> [<suffix>]` runs before serving; dies with
  their message."
  [file suffix]
  (when-not (.isFile (io/file file)) (die "file not found: " file))
  (when (some? suffix)
    (when (re-find #"(?i)\.(edn|png|svg)$" suffix)
      (die "two-file compare was replaced: simpleviz fork " file
           " <suffix>, then simpleviz " file " <suffix>"))
    (when-not (re-matches serve/suffix-re suffix)
      (die "invalid suffix: " suffix))
    (let [fk (serve/fork-name file suffix)]
      (when-not (.isFile (io/file fk))
        (die fk " not found — create it with: simpleviz fork " file " " suffix)))))
```

`serve-cmd` calls `(check-input! file suffix)` in place of its inline checks. Split the startup handling out of `serve!`:

```clojure
(defn- start!
  "Start on a free port; [port result]. A startup refusal (missing side,
  export without EDN) dies with its message; any other startup failure
  gets a crash report, like serve/-main gives it."
  [opts]
  (try (start-on-free-port! opts)
       (catch clojure.lang.ExceptionInfo e
         (if (:startup-check (ex-data e))
           (die (ex-message e))
           (do (log/crash! {:phase "startup"} e) (System/exit 1))))
       (catch Throwable e
         (log/crash! {:phase "startup"} e)
         (System/exit 1))))
```

`serve!` uses `(start! opts)`. Then:

```clojure
(def export-timeout-ms 120000)

(defn- export-cmd [args]
  (let [{:keys [error in suffix out format theme force]} (parse-export-args args)]
    (when error (die error))
    (check-input! in suffix)
    (when (and (.exists (io/file out)) (not force))
      (die out " already exists (--force overwrites)"))
    (let [[port] (start! {:file in :suffix suffix :debug false})
          {:keys [path] :as b} (browser/find-browser)
          _ (when-not path (die (:error b)))
          data (try (browser/with-browser path
                      (fn [ws-url _]
                        (browser/export-page! ws-url (str "http://localhost:" port)
                                              {:format format :theme theme :timeout-ms export-timeout-ms})))
                    (catch clojure.lang.ExceptionInfo e (die (ex-message e))))]
      (try (if (= format "png")
             (io/copy (.decode (java.util.Base64/getDecoder) ^String data) (io/file out))
             (spit out data :encoding "UTF-8"))
           (catch java.io.IOException e (die "cannot write " out ": " (ex-message e))))
      (println (str "wrote " out))
      ;; http-kit's threads would keep the process alive
      (System/exit 0))))
```

Dispatch in `-main`: `"export" (export-cmd more)`. Usage, after the extract line:

```
"       simpleviz export <graph.edn> [<suffix>] <out.png|out.svg> [--theme <name>] [--force]"
"                                         write the ⇩ export of the whole graph, made in a"
"                                         headless Chrome/Chromium (SIMPLEVIZ_BROWSER overrides)"
```

`bb.edn`, both task maps (the dev one and the release bundle's quoted one):

```clojure
export {:doc "Export a graph as PNG/SVG via headless Chrome: bb export graph.edn [suffix] out.png|out.svg [--theme t] [--force]"
        :requires ([cli])
        :task (apply cli/-main "export" *command-line-args*)}
```

The release bundle's `:paths` is `["server" "."]`, so `cli` resolves there; check the bundle task copies `server/` whole (it does).

- [ ] **Step 4: run** `bb test` — all green, the e2e tests included (they take a few seconds each).

- [ ] **Step 5: commit** `feat: simpleviz export — PNG/SVG from the command line via headless Chrome`.

---

### Task 5: Docs

**Files:**
- Modify: `README.md`, `docs/guide.md` (Exporting), `plugins/simpleviz/skills/simpleviz/SKILL.md`

- [ ] **Step 1:** README: one line under the command list — `simpleviz export graph.edn graph.png` writes the ⇩ export from the terminal (needs Chrome or Chromium).
- [ ] **Step 2:** guide.md Exporting: a block with the four spec examples, the theme rule (file's `:theme` wins; default light), "every box expanded", browser discovery and `SIMPLEVIZ_BROWSER`, the snap note (profile under `~/snap/<name>/common`).
- [ ] **Step 3:** SKILL.md: add to the command list `simpleviz export graph.edn out.png  # PNG/SVG of the whole graph via headless Chrome; never opens a window` and to Viewer: "To hand someone an image, run `simpleviz export` rather than asking them to click ⇩."
- [ ] **Step 4:** `bb test:clj` (docs-test) green.
- [ ] **Step 5: commit** `docs: simpleviz export in README, guide and skill`.
