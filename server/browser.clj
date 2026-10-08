(ns browser
  "Headless Chrome for `simpleviz export`: find the user's browser, start
  it with a throwaway profile, and drive the page over the DevTools
  protocol (CDP) — babashka built-ins only."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.http-client.websocket :as ws]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
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

;; --- running the browser ------------------------------------------------

(def launch-timeout-ms
  "How long the browser may take to print its DevTools line: a first,
  cold start of Chrome 154 on a CI runner has taken over 15 s."
  60000)

(def page-timeout-ms 15000)

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

(defn- stop!
  "Kill the browser (if it started), wait for it, then delete its profile
  — Chrome may still be flushing it as it exits, so a failed delete is
  retried."
  [proc profile]
  (when proc
    (p/destroy-tree proc)
    (deref proc 10000 nil))
  (loop [n 0]
    (when (and (fs/exists? profile)
               (not (try (fs/delete-tree profile) true (catch Exception _ false)))
               (< n 20))
      (Thread/sleep 100)
      (recur (inc n)))))

(defn with-browser
  "Start the browser at `path` headless with a throwaway profile, call
  (f ws-url profile), and always stop it and delete the profile — also
  when the process is interrupted or terminated (a shutdown hook covers
  Ctrl-C and SIGTERM; only SIGKILL escapes)."
  [path f]
  (let [profile (profile-dir! path)
        proc (volatile! nil)
        cleanup (Thread. ^Runnable (fn [] (stop! @proc profile)))
        url (promise)
        tail (atom [])
        nm (str (fs/file-name path))]
    (.addShutdownHook (Runtime/getRuntime) cleanup)
    (try
      (vreset! proc (try (p/process [path "--headless" "--remote-debugging-port=0"
                                     (str "--user-data-dir=" profile)
                                     "--no-first-run" "--no-default-browser-check" "about:blank"]
                                    {:out :discard :err :pipe})
                         (catch java.io.IOException e
                           (throw (ex-info (str "could not start " nm ": " (ex-message e)) {})))))
      (drain! @proc url tail)
      (let [u (deref url launch-timeout-ms nil)]
        (cond (nil? u) (throw (ex-info (str nm " did not start within " (quot launch-timeout-ms 1000) " s: "
                                            (str/join " | " @tail)) {}))
              (= :exited u) (throw (ex-info (str "could not start " nm ": " (str/join " | " @tail)) {}))
              :else (f u profile)))
      (finally
        (stop! @proc profile)
        ;; throws once shutdown is under way — then the hook runs anyway
        (try (.removeShutdownHook (Runtime/getRuntime) cleanup)
             (catch IllegalStateException _ nil))))))

;; --- CDP ----------------------------------------------------------------

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
               ;; a big reply arrives in several frames; `last?` ends it
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
  error, or after timeout-ms naming `what`."
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
  "The message of a rejected evaluate: the Error's message, else CDP's text."
  [details]
  (or (some-> (get-in details [:exception :description]) str/split-lines first
              (str/replace #"^\w*Error: " ""))
      (:text details)))

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
    (call page "Page.enable" {} page-timeout-ms "the page")
    (call page "Page.navigate" {:url url} page-timeout-ms "the page")
    (when (nil? (deref loaded page-timeout-ms nil))
      (throw (ex-info (str "the page did not load within " (quot page-timeout-ms 1000) " s") {})))))

(defn export-page!
  "Navigate to page-url and return window.simplevizExport's data: base64
  for \"png\", SVG text for \"svg\"."
  [ws-url page-url {:keys [format theme timeout-ms]}]
  (let [page (open-page ws-url)]
    (try
      (navigate! page page-url)
      (:data (evaluate page (str "window.simplevizExport("
                                 (json/generate-string {:format format :theme theme}) ")")
                       timeout-ms))
      (finally (close-page page)))))
