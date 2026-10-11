(ns browser-test
  (:require [babashka.fs :as fs]
            [browser]
            [clojure.string :as str]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as srv]
            [proc-util]))

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

;; --- a real browser: skipped without one ------------------------------

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

(deftest export-page-loads-the-page-and-awaits-its-hook
  ;; the hook is defined by a module script, as app.mjs defines it
  (with-real-browser
    (let [html (str "<!doctype html><script type=module>"
                    "window.simplevizExport = (o) => Promise.resolve({data: o.format + ':' + o.theme});"
                    "</script>")
          stop (srv/run-server (fn [_] {:status 200 :headers {"content-type" "text/html"} :body html})
                               {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
      (try
        (browser/with-browser path
          (fn [ws-url _]
            (is (= "svg:nord" (browser/export-page! ws-url (str "http://127.0.0.1:" (srv/server-port stop))
                                                    {:format "svg" :theme "nord" :timeout-ms 10000})))))
        (finally (srv/server-stop! stop))))))

(defn- profiles-in [dir]
  (set (map str (fs/glob dir "simpleviz-export-*"))))

(deftest a-browser-that-cannot-start-is-a-clear-error-and-leaves-nothing
  ;; executable, but no program: exec itself fails
  (let [fake (str (fs/create-temp-file {:prefix "not-a-browser"}))
        tmp (System/getProperty "java.io.tmpdir")
        before (profiles-in tmp)]
    (spit fake "just text\n")
    (fs/set-posix-file-permissions fake "rwx------")
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^could not start not-a-browser"
                            (browser/with-browser fake (fn [_ _] :unreachable))))
      (is (= before (profiles-in tmp)) "no profile folder left")
      (finally (fs/delete fake)))))

(deftest a-browser-that-never-answers-times-out-with-its-last-stderr-lines
  ;; starts, says something, never prints the DevTools line
  (let [fake (str (fs/create-temp-file {:prefix "slow-browser"}))]
    (spit fake "#!/bin/sh\necho 'still warming up' >&2\nexec sleep 30\n")
    (fs/set-posix-file-permissions fake "rwx------")
    (try
      (with-redefs [browser/launch-timeout-ms 1000]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"^slow-browser\S* did not start within 1 s: still warming up$"
                              (browser/with-browser fake (fn [_ _] :unreachable)))))
      (finally (fs/delete fake)))))

(defn- browser-running? [profile]
  (zero? (:exit (p/shell {:out :string :err :string :continue true} "pgrep" "-f" profile))))

(deftest a-terminated-export-still-stops-the-browser-and-deletes-the-profile
  ;; a timeout kills bb with SIGTERM, not the whole process group
  (with-real-browser
    (let [proc (p/process ["bb" "--config" (str proc-util/repo-root "/bb.edn") "-e"
                           (str "(require 'browser) (browser/with-browser " (pr-str path)
                                " (fn [_ profile] (println profile) (flush) (Thread/sleep 60000)))")]
                          {:err :inherit})
          profile (.readLine (io/reader (:out proc)))]
      (is (fs/directory? profile))
      (p/destroy proc)
      (deref proc 20000 nil)
      (Thread/sleep 500)
      (is (not (browser-running? profile)) "browser stopped")
      (is (not (fs/exists? profile)) "profile deleted"))))

(defn- eval-on-served-page
  "Serve `file` (holding `text`) from a fresh folder, open it in the
  browser at `path` and evaluate `js` there; its value. The server keeps
  your choices in `home` (default: a fresh one), never in ~/.simpleviz.
  `extra` maps more file names in the folder to their text."
  [path file text js & {:keys [home extra]}]
  (let [dir (fs/create-temp-dir {:prefix "served-page"})
        port (proc-util/free-port)
        _ (spit (str (fs/path dir file)) text)
        _ (doseq [[f t] extra] (spit (str (fs/path dir f)) t))
        srv (proc-util/start ["bb" "--config" (str proc-util/repo-root "/bb.edn") "serve" file
                              "--port" (str port)]
                             :dir dir
                             :env {"SIMPLEVIZ_HOME" (str (or home (fs/path dir "home")))})]
    (try
      (is (proc-util/await-line srv #"serving" 20000))
      (browser/with-browser path
        (fn [ws-url _]
          (let [page (browser/open-page ws-url)]
            (try
              (browser/navigate! page (str "http://127.0.0.1:" port "/"))
              (browser/evaluate page js 10000)
              (finally (browser/close-page page))))))
      (finally (p/destroy-tree srv) (fs/delete-tree dir)))))

(deftest the-doc-panel-scrolls-and-the-canvas-does-not-zoom
  ;; the panel sits inside #canvas-wrap; its wheel must stay the doc's,
  ;; rendered and, after Edit, the textarea's
  (with-real-browser
    (is (= {:view {:prevented false :overflows true} :text {:prevented false :overflows true}}
           (eval-on-served-page
            path "notes.md" (str/join "\n\n" (map #(str "line " %) (range 300)))
            (str "new Promise(ok => { let r = {}; const t = setInterval(() => {"
                 " const wheel = el => { const w = new WheelEvent('wheel', {deltaY: 100, bubbles: true, cancelable: true});"
                 "  el.dispatchEvent(w); return {prevented: w.defaultPrevented, overflows: el.scrollHeight > el.clientHeight}; };"
                 " const view = document.getElementById('md-view');"
                 " if (!r.view) { if (!view || !view.textContent) return;"
                 "  r.view = wheel(view); document.querySelector('.md-mode').click(); return; }"
                 " const ta = document.getElementById('md-text');"
                 " if (!ta || !ta.value) return; clearInterval(t);"
                 " r.text = wheel(ta); ok(r); }, 50); })"))))))

(deftest a-graph-link-opens-beside-the-md-page
  ;; a link of the rendered md page shows the graph to the right of the
  ;; text, the link highlighted — and, after Edit, its line's » — and the
  ;; canvas shifted past the column; × closes it
  (with-real-browser
    (is (= {:side "g.edn" :open true :mark-open true :shifted true :closed true}
           (eval-on-served-page
            path "notes.md" "See [the graph](g.edn).\n"
            (str "new Promise(ok => { let step = 0, r = null; const t = setInterval(() => {"
                 " const link = document.querySelector('#md-view a.md-link');"
                 " if (step === 0) { if (!link) return; link.click(); step = 1; return; }"
                 " const bar = document.getElementById('side-bar');"
                 " const wrap = document.getElementById('canvas-wrap');"
                 " if (step === 1) { if (!bar || bar.querySelector('.side-loading')) return;"
                 "  const panel = document.getElementById('md-panel');"
                 "  r = {side: new URLSearchParams(location.search).get('side'),"
                 "       open: !!document.querySelector('#md-view a.md-link.open'),"
                 "       shifted: panel.offsetWidth > 0 && wrap.style.left === panel.offsetWidth + 'px'};"
                 "  document.querySelector('.md-mode').click(); step = 2; return; }"
                 " if (step === 2) { const mark = document.querySelector('.md-mark'); if (!mark) return;"
                 "  r['mark-open'] = mark.classList.contains('open');"
                 "  bar.querySelector('[aria-label=Close]').click(); step = 3; return; }"
                 " clearInterval(t);"
                 " r.closed = !document.getElementById('side-bar') && wrap.style.left === '0px'"
                 "   && !document.querySelector('.md-mark.open') && !location.search.includes('side');"
                 " ok(r); }, 50); })")
            :extra {"g.edn" "{:nodes {:a {}}}"})))))

(deftest the-help-panel-opens
  ;; every section's text renders; a stray quote once broke one into code
  (with-real-browser
    (is (= {:help true :errors []}
           (eval-on-served-page
            path "g.edn" "{:nodes {:a {}}}"
            (str "new Promise(ok => { const errors = [];"
                 " window.addEventListener('error', e => errors.push(String(e.message)));"
                 " const t = setInterval(() => {"
                 " if (!document.getElementById('help-btn')) return; clearInterval(t);"
                 " document.dispatchEvent(new KeyboardEvent('keydown', {key: '?', bubbles: true}));"
                 " setTimeout(() => ok({help: !!document.getElementById('help-panel'), errors}), 200); }, 50); })"))))))

(deftest your-theme-outlives-the-port
  ;; localStorage is per origin and every run picks a random port: the
  ;; choice has to live on the server
  (with-real-browser
    (let [home (fs/create-temp-dir {:prefix "prefs-home"})]
      (try
        (is (= "dark"
               (eval-on-served-page
                path "g.edn" "{:nodes {:a {}}}"
                (str "new Promise(ok => { const t = setInterval(async () => {"
                     " const s = document.getElementById('theme-select');"
                     " if (!s) return; clearInterval(t);"
                     " s.value = 'dark'; s.dispatchEvent(new Event('change', {bubbles: true}));"
                     " for (let i = 0; i < 50; i++) {"
                     "  const p = await (await fetch('/api/prefs')).json();"
                     "  if (p.theme) return ok(p.theme);"
                     "  await new Promise(r => setTimeout(r, 100)); }"
                     " ok(null); }, 50); })")
                :home home)))
        (is (= {:value "dark" :bg "#111827"}
               (eval-on-served-page
                path "g.edn" "{:nodes {:a {}}}"
                (str "new Promise(ok => { let n = 0; const t = setInterval(() => {"
                     " const s = document.getElementById('theme-select');"
                     " if (!(s && s.value === 'dark') && ++n < 100) return; clearInterval(t);"
                     " ok({value: s && s.value, bg: getComputedStyle(document.documentElement)"
                     ".getPropertyValue('--bg').trim()}); }, 50); })")
                :home home)))
        (finally (fs/delete-tree home))))))
