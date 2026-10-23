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
  ;; newer snapd: /usr/bin/snap is itself a link, to /usr/lib/snapd/snapd
  (is (= "chromium" (browser/snap-name "/snap/bin/chromium" "/usr/lib/snapd/snapd")))
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

(deftest a-compare-link-opens-the-comparison-beside-the-md-page
  ;; [x](g.edn?compare=next): the graph beside the text is compared with
  ;; g-next.edn, an edit there goes to the fork, × drops the comparison
  (with-real-browser
    (is (= {:compare "next" :legend true :open true :fork true :original false :closed true}
           (eval-on-served-page
            path "notes.md" "See [the change](g.edn?compare=next) and [the graph](g.edn).\n"
            (str "new Promise(ok => { let step = 0, r = {}; const t = setInterval(async () => {"
                 " if (step === 0) { const link = document.querySelector('#md-view a.md-link'); if (!link) return;"
                 "  link.click(); step = 1; return; }"
                 " const bar = document.getElementById('side-bar');"
                 " if (step === 1) { const legend = document.getElementById('diff-legend');"
                 "  if (!bar || bar.querySelector('.side-loading') || !legend) return;"
                 "  r.compare = new URLSearchParams(location.search).get('compare');"
                 "  r.legend = legend.innerText.includes('g-next.edn');"
                 "  r.open = [...document.querySelectorAll('#md-view a.md-link.open')].map(a => a.innerText).join() === 'the change';"
                 "  [...document.querySelectorAll('#selection-toolbar button')].find(b => b.innerText.startsWith('new')).click();"
                 "  step = 6; return; }"
                 " if (step === 6) { const b = [...document.querySelectorAll('#chord-popout button')].find(b => b.innerText.startsWith('new node'));"
                 "  if (!b) return; b.click(); step = 2; return; }"
                 " if (step === 2) { const input = document.querySelector('.id-entry-input'); if (!input) return;"
                 "  input.value = 'queue'; input.dispatchEvent(new Event('input', {bubbles: true}));"
                 "  input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})); step = 3; return; }"
                 " if (step === 3) { step = 4;"
                 "  const src = async w => (await fetch('/api/source?file=g.edn&compare=next&which=' + w)).text();"
                 "  const fork = await src('new'); if (!fork.includes(':queue')) { step = 3; return; }"
                 "  r.fork = true; r.original = (await src('old')).includes(':queue');"
                 "  bar.querySelector('[aria-label=Close]').click(); step = 5; return; }"
                 " if (step === 4) return;"
                 " clearInterval(t);"
                 " r.closed = !document.getElementById('side-bar') && !location.search.includes('compare');"
                 " ok(r); }, 50); })")
            :extra {"g.edn" "{:nodes {:a {}}}" "g-next.edn" "{:nodes {:a {} :b {}}}"})))))

(deftest a-presentation-steps-through-its-slides
  ;; Present: the title slide alone and centered, every tool hidden but
  ;; Leave; → shows the next slide and what its >> names beside it — a
  ;; graph (read-only), an md file, a plain image; Esc leaves
  (with-real-browser
    (is (= {:title {:title true :h1 "The Deck" :slide "1" :tools false :leave true}
            :graph {:h1 "Graph slide" :side "g.edn" :slide "2" :shifted true :editing false}
            :md {:side-md "hello" :side nil}
            :image {:loaded true :counter "4 / 4"}
            :left {:slide nil :view true}}
           (eval-on-served-page
            path "deck.md"
            "---\n# The Deck\n## the sub\nme\n\n---\n>> [the graph](g.edn)\n# Graph slide\n\ntext\n---\n>> [notes](./notes.md)\n# Md slide\n---\n>> ![a picture](pic.svg)\n# Image slide\n"
            (str "new Promise(ok => { let step = 0; const r = {};"
                 " const q = k => new URLSearchParams(location.search).get(k);"
                 " const key = k => document.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));"
                 " const t = setInterval(() => {"
                 " const slide = document.getElementById('present-slide');"
                 " if (step === 0) { const b = document.querySelector('.md-present'); if (!b) return; b.click(); step = 1; return; }"
                 " if (step === 1) { if (!slide) return;"
                 "  r.title = {title: slide.classList.contains('title'), h1: slide.querySelector('h1').textContent, slide: q('slide'),"
                 "   tools: !!document.querySelector('#theme-select, #help-btn, #trail, #side-bar, .md-head'),"
                 "   leave: !!document.getElementById('present-leave')};"
                 "  key('ArrowRight'); step = 2; return; }"
                 " if (step === 2) { if (q('side') !== 'g.edn' || slide.querySelector('h1').textContent !== 'Graph slide') return;"
                 "  const panel = document.getElementById('md-panel'), wrap = document.getElementById('canvas-wrap');"
                 "  r.graph = {h1: slide.querySelector('h1').textContent, side: q('side'), slide: q('slide'),"
                 "   shifted: panel.offsetWidth > 0 && wrap.style.left === panel.offsetWidth + 'px',"
                 "   editing: !!document.querySelector('#selection-toolbar, #undo-btn')};"
                 "  key(' '); step = 3; return; }"
                 " if (step === 3) { const md = document.querySelector('#present-side .md-doc');"
                 "  if (!md || !md.textContent.includes('hello')) return;"
                 "  r.md = {'side-md': md.querySelector('p').textContent, side: q('side')};"
                 "  key('End'); step = 4; return; }"
                 " if (step === 4) { const img = document.querySelector('#present-side img');"
                 "  if (!img || !img.complete) return;"
                 "  r.image = {loaded: img.naturalWidth > 0, counter: document.getElementById('present-counter').textContent};"
                 "  key('Escape'); step = 5; return; }"
                 " if (slide) return; clearInterval(t);"
                 " r.left = {slide: q('slide'), view: !!document.getElementById('md-view')}; ok(r); }, 50); })")
            :extra {"g.edn" "{:nodes {:a {}}}"
                    "notes.md" "# Notes\n\nhello\n"
                    "pic.svg" "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>"})))))

(defn- present-then
  "JS for a served deck: press Present, wait 300 ms, press each key of
  `keys` with no pause between them, run `between` (JS statements) right
  after, and 1.5 s later resolve what the page shows."
  [keys between]
  (str "new Promise(ok => { const errs = [];"
       " window.addEventListener('error', e => errs.push(String(e.message)));"
       " const go = () => { const b = document.querySelector('.md-present'); if (!b) return setTimeout(go, 50);"
       " b.click();"
       " const key = k => document.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));"
       " setTimeout(() => { " (apply str (map #(str "key('" % "'); ") keys)) between
       "  setTimeout(() => { const el = id => document.getElementById(id);"
       "   ok({errs, view: !!el('md-view'), banner: el('banner') && el('banner').textContent,"
       "       h1: el('present-slide') && el('present-slide').querySelector('h1').textContent,"
       "       side: el('present-side') && el('present-side').textContent,"
       "       query: location.search}); }, 1500); }, 300); };"
       " go(); })"))

(deftest a-slide-change-overtaken-by-another-still-lands
  ;; the same >> on two slides stepped through at once; a graph slide
  ;; left before its graph came; back while a slide's md file loads
  (with-real-browser
    (is (= {:h1 "B" :side "Noteshello"}
           (select-keys (eval-on-served-page
                         path "deck.md" "# Title\n---\n>> notes.md\n# A\n---\n>> notes.md\n# B\n"
                         (present-then ["ArrowRight" "ArrowRight"] "")
                         :extra {"notes.md" "# Notes\n\nhello\n"})
                        [:h1 :side])))
    (is (= {:h1 "B" :banner nil :query "?slide=3"}
           (select-keys (eval-on-served-page
                         path "deck.md" "# Title\n---\n>> g.edn\n# A\n---\n# B\n"
                         (present-then ["ArrowRight" "ArrowRight"] "")
                         :extra {"g.edn" "{:nodes {:a {}}}"})
                        [:h1 :banner :query])))
    (is (= {:errs [] :view true :h1 nil :side nil}
           (select-keys (eval-on-served-page
                         path "deck.md" "# Title\n---\n>> notes.md\n# A\n"
                         (present-then ["ArrowRight"]
                                       (str "history.pushState(null, '', location.pathname + '?file=other.md');"
                                            " window.dispatchEvent(new PopStateEvent('popstate'));"))
                         :extra {"notes.md" "# Notes\n\nhello\n" "other.md" "# Other\n"})
                        [:errs :view :h1 :side])))))

(deftest a-missing-graph-link-offers-the-graphs-to-copy-a-page-at-a-time
  ;; a link to a graph not there yet lists the served folder's graphs to
  ;; start it as a copy of: the first 200, the filter narrows them
  (with-real-browser
    (is (= {:shown 200 :note "The first 200 of 205 — type to narrow." :narrowed ["g-150.edn"] :none "No graph matches."}
           (eval-on-served-page
            path "notes.md" "See [the new one](new.edn).\n"
            (str "new Promise(ok => { let step = 0; const r = {};"
                 " const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];"
                 " const type = v => { const f = $('.choose-filter'); f.value = v; f.dispatchEvent(new Event('input', {bubbles: true})); };"
                 " const t = setInterval(() => {"
                 " if (step === 0) { const a = $('#md-view a.md-link'); if (!a) return; a.click(); step = 1; return; }"
                 " if (step === 1) { if (!$('.choose-file')) return;"
                 "  r.shown = $$('.choose-file').length;"
                 "  r.note = $$('.choose-note').map(p => p.textContent).find(t => t.includes('narrow'));"
                 "  type('g-150'); step = 2; return; }"
                 " if (step === 2) { if ($$('.choose-file').length !== 1) return;"
                 "  r.narrowed = $$('.choose-file').map(b => b.textContent); type('zzz'); step = 3; return; }"
                 " if ($$('.choose-file').length) return; clearInterval(t);"
                 " r.none = $$('.choose-note').map(p => p.textContent).find(t => t.includes('matches')); ok(r); }, 50); })")
            :extra (into {} (map (fn [i] [(str "g-" i ".edn") "{:nodes {:a {}}}"]) (range 205))))))))

(deftest a-quiz-asks-and-locks
  ;; ??? holds → until Submit, then shows the answer locked, the right
  ;; choice and the explanation of the one picked; ?? takes a click, the
  ;; last of two quick ones wins; the answers are in deck.answers.edn and
  ;; come back locked when the deck is presented again
  (with-real-browser
    (is (= {:asked {:choices 2 :submit-disabled true :slide "2"}
            :blocked {:slide "2" :hint "Submit an answer to go on"}
            :locked {:wrong "Browser" :correct "Redis" :note "Per tab." :disabled true :slide "2"}
            :stored {:storage {:answer "Browser" :num 0} :poll {:answer "No" :num 1}}
            :again {:locked true :next "3"}}
           (eval-on-served-page
                path "deck.md"
                "# Quiz\n---\n??? storage\n# Where?\n- [ ] Browser\n  > Per tab.\n- [x] Redis\n  > Right: shared.\n---\n?? poll\n# Sure?\n- [ ] Yes\n- [ ] No\n"
                (str "new Promise(ok => { let step = 0; const r = {};"
                     " const q = k => new URLSearchParams(location.search).get(k);"
                     " const key = k => document.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));"
                     " const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];"
                     " const t = setInterval(async () => {"
                     " if (step === 0) { const b = $('.md-present'); if (!b) return; b.click(); step = 1; return; }"
                     " if (step === 1) { if (!$('#present-slide')) return; key('ArrowRight'); step = 2; return; }"
                     " if (step === 2) { if (!$('.ask-choice')) return;"
                     "  r.asked = {choices: $$('.ask-choice').length, 'submit-disabled': $('.ask-submit').disabled, slide: q('slide')};"
                     "  key('ArrowRight'); step = 3; return; }"
                     " if (step === 3) { if (!$('.ask-hint')) return;"
                     "  r.blocked = {slide: q('slide'), hint: $('.ask-hint').textContent};"
                     "  $$('.ask-choice')[0].click(); step = 4; return; }"
                     " if (step === 4) { if ($('.ask-submit').disabled) return; $('.ask-submit').click(); step = 5; return; }"
                     " if (step === 5) { if (!$('.ask.locked')) return;"
                     "  r.locked = {wrong: $('.ask-choice.wrong').textContent, correct: $('.ask-choice.correct').textContent,"
                     "   note: $('.ask-note').textContent, disabled: $$('.ask-choice').every(b => b.disabled), slide: q('slide')};"
                     "  key('ArrowRight'); step = 6; return; }"
                     " if (step === 6) { if (q('slide') !== '3') return; $$('.ask-choice')[0].click(); $$('.ask-choice')[1].click(); step = 7; return; }"
                     " if (step === 7) { if (!$('.ask-choice.chosen')) return;"
                     "  step = 9; clearInterval(t); await new Promise(go => setTimeout(go, 500));"
                     "  r.stored = (await (await fetch('/api/answers?path=deck.md')).json()).answers;"
                     "  key('Escape');"
                     "  const t2 = setInterval(() => {"
                     "   if (step === 9) { const b = $('.md-present'); if (!b) return; b.click(); step = 10; return; }"
                     "   if (step === 10) { if (!$('#present-slide')) return; key('ArrowRight'); step = 11; return; }"
                     "   if (step === 11) { if (!$('.ask.locked')) return; r.again = {locked: true}; key('ArrowRight'); step = 12; return; }"
                     "   if (q('slide') !== '3') return; r.again.next = q('slide'); clearInterval(t2); ok(r); }, 50);"
                     "  return; }"
                     " }, 50); })"))))))

(deftest a-quiz-goes-on-when-an-answer-is-not-saved
  ;; the answers file can't be read, so no answer is written: the ???
  ;; still locks and the deck goes on, saying so on every slide after
  (with-real-browser
    (let [r (eval-on-served-page
             path "deck.md"
             "# T\n---\n??? q\n# Q\n- [ ] a\n- [x] b\n---\n# After\n"
             (str "new Promise(ok => { let step = 0; const r = {};"
                  " const q = k => new URLSearchParams(location.search).get(k);"
                  " const key = k => document.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));"
                  " const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];"
                  " const t = setInterval(() => {"
                  " if (step === 0) { const b = $('.md-present'); if (!b) return; b.click(); step = 1; return; }"
                  " if (step === 1) { if (!$('#present-slide')) return; key('ArrowRight'); step = 2; return; }"
                  " if (step === 2) { if (!$('.ask-choice')) return; $$('.ask-choice')[0].click(); step = 3; return; }"
                  " if (step === 3) { if ($('.ask-submit').disabled) return; $('.ask-submit').click(); step = 4; return; }"
                  " if (step === 4) { if (!$('.ask.locked') || !$$('.ask-error').some(e => e.textContent.includes('not saved'))) return;"
                  "  key('ArrowRight'); step = 5; return; }"
                  " if (q('slide') !== '3') return; clearInterval(t);"
                  " r.slide = q('slide'); r.h1 = $('#present-slide h1').textContent;"
                  " r.error = $$('.ask-error').map(e => e.textContent); ok(r); }, 50); })")
             :extra {"deck.answers.edn" "{:oops"})]
      (is (= {:slide "3" :h1 "After"} (select-keys r [:slide :h1])))
      (is (some #(re-find #"not saved \(q\): deck.answers.edn does not parse" %) (:error r)) (pr-str r)))))

(deftest a-slide-says-why-its-picture-does-not-show
  (with-real-browser
    (is (= {:h1 "A" :side "missing.jpg — no such picture, or not one the browser shows"}
           (select-keys (eval-on-served-page
                         path "deck.md" "# Title\n---\n>> missing.jpg\n# A\n"
                         (present-then ["ArrowRight"] ""))
                        [:h1 :side])))))

(deftest an-md-page-shows-its-pictures-and-footnotes
  ;; an image of the folder shows as the picture; a footnote's number
  ;; scrolls to it without touching the URL, which navigation reads
  (with-real-browser
    (is (= {:img true :checkbox true :strong "bold" :query "" :view true}
           (eval-on-served-page
            path "notes.md" "**bold** ![a cup](pic.svg)\n\n- [x] done\n\nA claim.[^1]\n\n[^1]: The source.\n"
            (str "new Promise(ok => { let step = 0, r = {}; const t = setInterval(() => {"
                 " const img = document.querySelector('#md-view img.md-img');"
                 " if (step === 0) { if (!img || !img.complete) return;"
                 "  r.img = img.naturalWidth > 0;"
                 "  r.checkbox = document.querySelector('#md-view li.task input').checked;"
                 "  r.strong = document.querySelector('#md-view strong').textContent;"
                 "  document.querySelector('#md-view .footnote-ref').click(); step = 1; return; }"
                 " clearInterval(t);"
                 " r.query = location.search + location.hash; r.view = !!document.getElementById('md-view');"
                 " ok(r); }, 100); })")
            :extra {"pic.svg" "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>"})))))

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

(deftest the-cursor-points-at-what-can-be-clicked
  ;; the one node sits at the canvas's centre once laid out; a corner is empty
  (with-real-browser
    (is (= {:node "pointer" :empty "grab" :panning "grabbing" :released "grab"}
           (eval-on-served-page
            path "g.edn" "{:nodes {:a {}}}"
            (str "new Promise(ok => { const t = setInterval(() => {"
                 " const c = document.getElementById('canvas'); if (!c) return;"
                 " const r = c.getBoundingClientRect();"
                 " const send = (type, x, y) => { c.dispatchEvent(new PointerEvent(type, {clientX: x, clientY: y, bubbles: true}));"
                 "  return getComputedStyle(c).cursor; };"
                 " const node = send('pointermove', r.left + r.width / 2, r.top + r.height / 2);"
                 " if (node !== 'pointer') return; clearInterval(t);"
                 " const empty = send('pointermove', r.left + 2, r.top + 2);"
                 " const panning = send('pointerdown', r.left + 2, r.top + 2);"
                 " ok({node, empty, panning, released: send('pointerup', r.left + 2, r.top + 2)}); }, 50); })"))))))

(deftest doc-text-has-the-text-cursor
  ;; the doc sits inside #canvas-wrap; the canvas's grab hand stays the
  ;; canvas's, even while the text is pressed to select it
  (with-real-browser
    (is (= {:text "auto" :pressed "auto"}
           (eval-on-served-page
            path "notes.md" "# Notes\n\nsome text\n"
            (str "new Promise(ok => { const t = setInterval(() => {"
                 " const p = document.querySelector('#md-view p'); if (!p) return; clearInterval(t);"
                 " const text = getComputedStyle(p).cursor;"
                 " p.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}));"
                 " ok({text, pressed: getComputedStyle(p).cursor}); }, 50); })"))))))

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

(deftest vi-mode-edits-and-saves-a-doc
  ;; the vi button turns vi keys on (a choice kept on the server): normal
  ;; mode shows a block caret, dw deletes, the status line names pending
  ;; keys and the mode, only insert mode lets a paste in, :w saves
  (with-real-browser
    (is (= {:block [0 1] :pending "d" :text "bar baz\n" :insert "-- INSERT --" :normal "\u00a0"
            :paste-in-insert true :paste-in-normal false :saved true :pref "on"}
           (eval-on-served-page
            path "notes.md" "foo bar baz\n"
            (str "new Promise(ok => { let step = 0; const r = {}; const t = setInterval(async () => {"
                 " const key = (el, k) => el.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true, cancelable: true}));"
                 " const status = () => document.querySelector('.md-vim').textContent;"
                 " if (step === 0) { const b = document.querySelector('.md-mode'); if (!b) return; b.click(); step = 1; return; }"
                 " if (step === 1) { const v = document.querySelector('.md-vi'); if (!v) return; v.click(); step = 2; return; }"
                 " clearInterval(t);"
                 " const ta = document.getElementById('md-text');"
                 " r.block = [ta.selectionStart, ta.selectionEnd];"
                 " key(ta, 'd'); r.pending = status();"
                 " key(ta, 'w'); r.text = ta.value;"
                 " const paste = () => ta.dispatchEvent(new InputEvent('beforeinput', {inputType: 'insertFromPaste', data: 'x', bubbles: true, cancelable: true}));"
                 " key(ta, 'i'); r.insert = status(); r['paste-in-insert'] = paste();"
                 " key(ta, 'Escape'); r.normal = status(); r['paste-in-normal'] = paste();"
                 " for (const k of [':', 'w', 'Enter']) key(ta, k);"
                 " r.saved = false;"
                 " for (let i = 0; i < 50 && !r.saved; i++) {"
                 "  const d = await (await fetch('/api/text?path=notes.md')).json();"
                 "  r.saved = d.text === 'bar baz\\n';"
                 "  if (!r.saved) await new Promise(res => setTimeout(res, 100)); }"
                 " r.pref = (await (await fetch('/api/prefs')).json()).vi;"
                 " ok(r); }, 50); })"))))))

(deftest format-tidies-the-text-and-undoes
  ;; Format, in edit mode only, tidies the text; the browser's undo takes
  ;; it back, and in vi mode u does
  (with-real-browser
    (let [raw "#  Title\ntext   \n* a\n* b"
          tidy "# Title\n\ntext  \n- a\n- b\n"]
      (is (= {:read-has false :formatted tidy :dirty true :undone raw :vi tidy :vi-undone raw}
             (eval-on-served-page
              path "notes.md" raw
              (str "new Promise(ok => { let step = 0; const r = {}; const t = setInterval(() => {"
                   " const key = (el, k) => el.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true, cancelable: true}));"
                   " const fmt = () => document.querySelector('.md-format');"
                   " if (step === 0) { const b = document.querySelector('.md-mode'); if (!b) return;"
                   "  r['read-has'] = !!fmt(); b.click(); step = 1; return; }"
                   " clearInterval(t); const ta = document.getElementById('md-text');"
                   " fmt().click(); r.formatted = ta.value; r.dirty = !!document.querySelector('.md-dirty');"
                   " ta.focus(); document.execCommand('undo'); r.undone = ta.value;"
                   " document.querySelector('.md-vi').click();"
                   " fmt().click(); r.vi = ta.value;"
                   " key(ta, 'u'); r['vi-undone'] = ta.value; ok(r); }, 50); })")))))))

(deftest vi-moves-scroll-the-text
  ;; setting the textarea's selection doesn't scroll it: G must, gg back
  (with-real-browser
    (is (= {:end true :top 0}
           (eval-on-served-page
            path "notes.md" (str/join "\n" (map #(str "line " %) (range 300)))
            (str "new Promise(ok => { let step = 0; const t = setInterval(() => {"
                 " const key = (el, k) => el.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true, cancelable: true}));"
                 " if (step === 0) { const b = document.querySelector('.md-mode'); if (!b) return; b.click(); step = 1; return; }"
                 " if (step === 1) { const v = document.querySelector('.md-vi'); if (!v) return; v.click(); step = 2; return; }"
                 " clearInterval(t); const ta = document.getElementById('md-text');"
                 " key(ta, 'G'); const end = ta.scrollTop >= ta.scrollHeight - ta.clientHeight - 2;"
                 " key(ta, 'g'); key(ta, 'g'); ok({end, top: ta.scrollTop}); }, 50); })"))))))

(deftest the-layouting-indicator-shows-while-elk-runs
  ;; the page lays out in its ELK worker — it holds no engine of its
  ;; own; a re-layout shows the indicator until the new layout is there
  (with-real-browser
    (is (= {:worker true :indicator ["+" "-"] :layouting false}
           (eval-on-served-page
            path "g.edn" "{:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}"
            (str "(async () => {"
                 " const sleep = ms => new Promise(res => setTimeout(res, ms));"
                 " const app = await import('/js/simpleviz/app.mjs');"
                 " for (let i = 0; i < 100 && !app.state.val.scene; i++) await sleep(100);"
                 " const seen = [];"
                 " new MutationObserver(() => { const has = !!document.getElementById('layouting');"
                 "   if (seen[seen.length - 1] !== (has ? '+' : '-')) seen.push(has ? '+' : '-'); })"
                 "   .observe(document.body, {childList: true, subtree: true});"
                 " await app.relayout_BANG_(true); await sleep(100);"
                 " const worker = performance.getEntriesByType('resource').some(e => e.name.endsWith('/vendor/elk-worker.min.js'));"
                 " return {worker, indicator: seen, layouting: app.state.val.layouting};"
                 "})()"))))))

(deftest vi-line-moves-keep-the-caret-line-at-the-edge-of-the-view
  ;; once the gutter has measured the text, k and j lay out only the
  ;; caret's line: scrolling up puts that line at the view's top edge,
  ;; scrolling down at its bottom edge; each move goes a view and more
  (with-real-browser
    (is (= {:fast true :up true :down true}
           (eval-on-served-page
            path "notes.md" (str/join "\n" (map #(str "line " %) (range 300)))
            (str "new Promise(ok => { let step = 0; const t = setInterval(async () => {"
                 " const key = (el, k) => el.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true, cancelable: true}));"
                 " const frame = () => new Promise(r => requestAnimationFrame(() => setTimeout(r, 0)));"
                 " if (step === 0) { const b = document.querySelector('.md-mode'); if (!b) return; b.click(); step = 1; return; }"
                 " if (step === 1) { const v = document.querySelector('.md-vi'); if (!v) return; v.click(); step = 2; return; }"
                 " if (step !== 2) return; step = 3; clearInterval(t);"
                 " const ta = document.getElementById('md-text'); const cs = getComputedStyle(ta);"
                 " const lh = parseFloat(cs.lineHeight), pad = parseFloat(cs.paddingTop);"
                 " const n = Math.ceil(ta.clientHeight / lh) + 5;"
                 " await frame(); await frame(); key(ta, 'G');"
                 " const line = () => ta.value.slice(0, ta.selectionStart).split('\\n').length - 1;"
                 " for (let i = 0; i < 2 * n; i++) key(ta, 'k');"
                 " const fast = document.getElementById('md-mirror').textContent.length < 20;"
                 " const top = pad + line() * lh - ta.scrollTop;"
                 " const up = line() === 299 - 2 * n && top >= 0 && top < lh;"
                 " for (let i = 0; i < n; i++) key(ta, 'j');"
                 " const below = ta.scrollTop + ta.clientHeight - (pad + (line() + 1) * lh);"
                 " ok({fast, up, down: line() === 299 - n && below >= 0 && below < lh}); }, 50); })"))))))

(deftest manual-layout-places-and-drags
  ;; ✥ gives every top-level element a :pos; dragging one moves it by
  ;; whole grid cells and writes its new cell
  (with-real-browser
    (let [r (eval-on-served-page
             path "g.edn" "{:nodes {:a {} :b {}} :edges {[:a :b] {:direction :->}}}"
             (str "(async () => {"
                  " const sleep = ms => new Promise(res => setTimeout(res, ms));"
                  " const until = async f => { for (let i = 0; i < 100; i++) { const v = await f(); if (v) return v; await sleep(100); } return null; };"
                  " const app = await import('/js/simpleviz/app.mjs'), cv = await import('/js/simpleviz/canvas.mjs');"
                  " const st = () => app.state.val;"
                  " const pos = (src, id) => { const m = src.match(new RegExp(':' + id + ' \\\\{:pos \\\\[(\\\\d+) (\\\\d+)\\\\]')); return m && [+m[1], +m[2]]; };"
                  " const source = async () => (await fetch('/api/source')).text();"
                  " await until(() => st().scene && document.getElementById('arrange-btn'));"
                  " document.getElementById('arrange-btn').click();"
                  " const s1 = await until(async () => { const s = await source(); return pos(s, 'a') && pos(s, 'b') && s; });"
                  " await until(() => st().placed && Object.values(st().graph.nodes).every(n => n.pos));"
                  " const a = st().scene.items.find(i => i.id === 'n:a');"
                  " const wrap = document.getElementById('canvas-wrap'), rect = wrap.getBoundingClientRect();"
                  " const p = cv.to_screen(a.x + a.w / 2, a.y + a.h / 2), k = cv.view.k;"
                  " const ev = (type, x, y) => wrap.dispatchEvent(new PointerEvent(type, {clientX: rect.left + x, clientY: rect.top + y, bubbles: true, pointerId: 7}));"
                  " ev('pointerdown', p.x, p.y); ev('pointermove', p.x + 100 * k, p.y + 60 * k); ev('pointerup', p.x + 100 * k, p.y + 60 * k);"
                  " const before = pos(s1, 'a');"
                  " const s2 = await until(async () => { const s = await source(); const q = pos(s, 'a'); return q && (q[0] !== before[0] || q[1] !== before[1]) && s; });"
                  " return {placed: !!(pos(s1, 'a') && pos(s1, 'b')), moved: s2 ? [pos(s2, 'a')[0] - before[0], pos(s2, 'a')[1] - before[1]] : null,"
                  "         b: s2 && pos(s2, 'b').join(' ') === pos(s1, 'b').join(' ')};"
                  "})()"))]
      (is (= {:placed true :moved [5 3] :b true} r)))))

(deftest select-mode-copies-cuts-and-pastes
  ;; s s and a click select, y y copies, p p pastes with a -1; s r spans a
  ;; rectangle; s a then x x cuts everything, and p p brings it back.
  ;; The page's clipboard is a stand-in: headless Chrome asks permission.
  (with-real-browser
    (let [r (eval-on-served-page
             path "g.edn" "{:nodes {:a {:name \"A\"} :b nil :c nil}\n :edges {[:a :b] {:direction :->}}\n :boxes {:grp {:components [:b]}}}"
             (str "(async () => {"
                  " const sleep = ms => new Promise(res => setTimeout(res, ms));"
                  " const until = async f => { for (let i = 0; i < 100; i++) { const v = await f(); if (v) return v; await sleep(100); } return null; };"
                  " let clip = '';"
                  " delete window.ClipboardItem;"
                  " Object.defineProperty(navigator, 'clipboard', {value: {writeText: async t => { clip = t; }, readText: async () => clip}});"
                  " const app = await import('/js/simpleviz/app.mjs'), cv = await import('/js/simpleviz/canvas.mjs');"
                  " const st = () => app.state.val;"
                  " const key = k => document.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));"
                  " const keys = ks => ks.split(' ').forEach(key);"
                  " const source = async () => (await fetch('/api/source')).text();"
                  " const ids = () => [...st().multi.ids].sort().join(' ');"
                  " await until(() => st().scene);"
                  " keys('s s');"
                  " const a = st().scene.items.find(i => i.id === 'n:a'), p = cv.to_screen(a.x + a.w / 2, a.y + a.h / 2);"
                  " const canvas = document.getElementById('canvas'), rect = canvas.getBoundingClientRect();"
                  " canvas.dispatchEvent(new MouseEvent('click', {clientX: rect.left + p.x, clientY: rect.top + p.y, bubbles: true}));"
                  " const picked = ids();"
                  " keys('y y');"
                  " const copied = await until(() => clip);"
                  " const ended = st().multi === null;"
                  " keys('p p');"
                  " const pasted = await until(async () => { const s = await source(); return s.includes(':a-1') && s; });"
                  " await until(() => st().scene.items.some(i => i.id === 'n:a-1'));"
                  " keys('s r');"
                  " const wrap = document.getElementById('canvas-wrap'), wr = wrap.getBoundingClientRect();"
                  " const sc = st().scene, tl = cv.to_screen(-10, -10), br = cv.to_screen(sc.width + 10, sc.height + 10);"
                  " const ev = (type, x, y) => wrap.dispatchEvent(new PointerEvent(type, {clientX: wr.left + x, clientY: wr.top + y, bubbles: true, pointerId: 7}));"
                  " ev('pointerdown', tl.x, tl.y); ev('pointermove', br.x, br.y); ev('pointerup', br.x, br.y);"
                  " const spanned = ids();"
                  " key('Escape');"
                  " const escaped = st().multi === null;"
                  " keys('s a'); keys('x x');"
                  " const emptied = await until(async () => { const s = await source(); return !s.includes(':a') && s; });"
                  " keys('p p');"
                  " const back = await until(async () => { const s = await source(); return s.includes(':a-1 {:name \"A-1\"}') && s.includes(':grp') && s; });"
                  " return {picked, copied, ended, pasted: pasted && pasted.includes(':a-1 {:name \"A-1\"}'), spanned, escaped,"
                  "         emptied: !!emptied, back: !!back && !back.includes(':a-2')};"
                  "})()"))]
      (is (= {:picked "n:a" :copied "{:nodes {:a {:name \"A\"}}}\n" :ended true :pasted true
              :spanned "b:grp n:a n:a-1 n:b n:c" :escaped true :emptied true :back true}
             r)))))
