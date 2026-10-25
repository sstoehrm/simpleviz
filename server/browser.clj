(ns browser
  "Headless Chrome for `simpleviz export`: find the user's browser, start
  it with a throwaway profile, and drive the page over the DevTools
  protocol (CDP) on private pipes — babashka built-ins only."
  (:require [babashka.fs :as fs]
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
  "The snap a browser runs in, or nil: a command in /snap/bin/, or
  resolving to /usr/bin/snap, is that snap's launcher, named like the
  snap; a path under /snap/ names it after /snap/."
  [path resolved]
  (cond (or (str/starts-with? (str path) "/snap/bin/") (= resolved "/usr/bin/snap"))
        (str (fs/file-name path))
        (str/starts-with? (str path) "/snap/") (nth (str/split (str path) #"/" 4) 2)
        :else nil))

(defn profile-parent
  "Where the throwaway profile goes: a snap can't see the host's /tmp or
  hidden folders in home, but can see ~/snap/<name>/common."
  [snap home tmp]
  (if snap (str home "/snap/" snap "/common") (str tmp)))

;; --- running the browser ------------------------------------------------

(def launch-timeout-ms
  "How long the browser may take to answer its first command: a first,
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
  it — keeping the last lines in `tail` for error messages."
  [proc tail]
  (future
    (with-open [r (io/reader (:err proc))]
      (loop []
        (when-let [line (.readLine r)]
          (swap! tail #(vec (take-last 5 (conj % line))))
          (recur))))))

;; --- CDP over the browser's pipes ----------------------------------------
;; --remote-debugging-pipe: Chrome reads commands on fd 3 and writes
;; replies and events on fd 4, each JSON message ended by a NUL. Unlike
;; --remote-debugging-port, no TCP port opens: another user of this
;; machine could connect to one and drive the browser (and so read the
;; export's page, token and all).

(defn- dispatch!
  "Hand CDP message `m` to whoever waits for it: a reply to its command's
  promise, an event to the promise for its [sessionId method]."
  [{:keys [pending events]} m]
  (if-let [id (:id m)]
    (some-> (get @pending id) (deliver m))
    (some-> (get @events [(:sessionId m) (:method m)]) (deliver m))))

(defn- read-messages!
  "Read the browser's NUL-ended messages from `in` until it closes, then
  deliver :exited to `closed`, and closed to every command still waiting.
  A message that isn't JSON is skipped."
  [conn in closed]
  (future
    (let [buf (java.io.ByteArrayOutputStream.)]
      (try
        (loop []
          (let [b (.read ^java.io.InputStream in)]
            (cond
              (neg? b) nil
              (zero? b) (do (some->> (try (json/parse-string (.toString buf "UTF-8") true)
                                          (catch Exception _ nil))
                                     (dispatch! conn))
                            (.reset buf)
                            (recur))
              :else (do (.write buf b) (recur)))))
        (catch java.io.IOException _ nil))
      (deliver closed :exited)
      (doseq [[_ p] @(:pending conn)] (deliver p {:closed true})))))

(defn- pipe-connection
  "A CDP connection over the started process's stdin/stdout, which the
  sh wrapper made the browser's fd 3 and 4:
  {:send! fn :pending (atom {id promise}) :events (atom {[session method] promise})
   :ids (atom n) :closed promise}."
  [proc]
  (let [out (java.io.BufferedOutputStream. (:in proc))
        conn {:pending (atom {}) :events (atom {}) :ids (atom 0) :closed (promise)
              :send! (fn [m]
                       (locking out
                         (.write out (.getBytes ^String (json/generate-string m) "UTF-8"))
                         (.write out 0)
                         (.flush out)))}]
    (read-messages! conn (java.io.BufferedInputStream. (:out proc)) (:closed conn))
    conn))

(defn- call
  "Send a CDP command — to the browser, or with `session` to that page —
  and wait for its reply's :result; ex-info on a CDP error, a closed
  browser, or after timeout-ms naming `what`."
  [{:keys [send! pending ids closed]} session method params timeout-ms what]
  (let [id (swap! ids inc)
        reply (promise)]
    (swap! pending assoc id reply)
    ;; the reader, done already, won't answer it
    (when (realized? closed) (deliver reply {:closed true}))
    (try (send! (cond-> {:id id :method method :params params} session (assoc :sessionId session)))
         (catch java.io.IOException _ (deliver reply {:closed true})))
    (let [m (deref reply timeout-ms nil)]
      (swap! pending dissoc id)
      (cond (nil? m) (throw (ex-info (str what " did not finish within " (quot timeout-ms 1000) " s") {}))
            (:closed m) (throw (ex-info "the browser closed its debugging pipe" {:closed true}))
            (:error m) (throw (ex-info (str method ": " (get-in m [:error :message])) {}))
            :else (:result m)))))

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

(def ^:private pipe-wrapper
  "sh moves its stdin and stdout to fd 3 and 4, where Chrome talks CDP,
  and gives the browser /dev/null for both."
  "exec 3<&0 4>&1 </dev/null >/dev/null; exec \"$@\"")

(defn with-browser
  "Start the browser at `path` headless with a throwaway profile, its
  DevTools protocol on private pipes, call (f conn profile), and always
  stop it and delete the profile — also when the process is interrupted
  or terminated (a shutdown hook covers Ctrl-C and SIGTERM; only SIGKILL
  escapes)."
  [path f]
  (let [profile (profile-dir! path)
        proc (volatile! nil)
        cleanup (Thread. ^Runnable (fn [] (stop! @proc profile)))
        tail (atom [])
        nm (str (fs/file-name path))]
    (.addShutdownHook (Runtime/getRuntime) cleanup)
    (try
      (vreset! proc (try (p/process ["sh" "-c" pipe-wrapper "simpleviz-browser"
                                     path "--headless" "--remote-debugging-pipe"
                                     (str "--user-data-dir=" profile)
                                     "--no-first-run" "--no-default-browser-check" "about:blank"]
                                    {:in :pipe :out :pipe :err :pipe})
                         (catch java.io.IOException e
                           (throw (ex-info (str "could not start " nm ": " (ex-message e)) {})))))
      (drain! @proc tail)
      (let [conn (pipe-connection @proc)
            started (try (call conn nil "Browser.getVersion" {} launch-timeout-ms "start")
                         true
                         (catch clojure.lang.ExceptionInfo e
                           (if (:closed (ex-data e)) :exited nil)))
            ;; stderr's last lines may still be on their way
            why (fn [] (deref @proc 200 nil) (Thread/sleep 100) (str/join " | " @tail))]
        (cond (nil? started) (throw (ex-info (str nm " did not start within " (quot launch-timeout-ms 1000) " s: " (why)) {}))
              (= :exited started) (throw (ex-info (str "could not start " nm ": " (why)) {}))
              :else (f conn profile)))
      (finally
        (stop! @proc profile)
        ;; throws once shutdown is under way — then the hook runs anyway
        (try (.removeShutdownHook (Runtime/getRuntime) cleanup)
             (catch IllegalStateException _ nil))))))

;; --- CDP ----------------------------------------------------------------

(defn open-page
  "A fresh about:blank page of the browser on `conn`, attached to:
  {:conn conn :session <its CDP session id> :target <its target id>}."
  [conn]
  (let [{:keys [targetId]} (call conn nil "Target.createTarget" {:url "about:blank"} page-timeout-ms "the page")
        {:keys [sessionId]} (call conn nil "Target.attachToTarget" {:targetId targetId :flatten true}
                                  page-timeout-ms "the page")]
    {:conn conn :session sessionId :target targetId}))

(defn close-page [{:keys [conn target]}]
  (call conn nil "Target.closeTarget" {:targetId target} page-timeout-ms "closing the page"))

(defn- page-call [{:keys [conn session]} method params timeout-ms what]
  (call conn session method params timeout-ms what))

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
  (let [r (page-call page "Runtime.evaluate" {:expression expr :awaitPromise true :returnByValue true}
                     timeout-ms "export")]
    (if-let [d (:exceptionDetails r)]
      (throw (ex-info (exception-message d) {}))
      (get-in r [:result :value]))))

(defn navigate!
  "Load url in the page and wait for its load event."
  [{:keys [conn session] :as page} url]
  (let [loaded (promise)]
    (swap! (:events conn) assoc [session "Page.loadEventFired"] loaded)
    (page-call page "Page.enable" {} page-timeout-ms "the page")
    (page-call page "Page.navigate" {:url url} page-timeout-ms "the page")
    (when (nil? (deref loaded page-timeout-ms nil))
      (throw (ex-info (str "the page did not load within " (quot page-timeout-ms 1000) " s") {})))))

(def slide-size
  "A PDF page, in CSS px: the page lays a slide out at this size, 16:9."
  {:width 1280 :height 720})

(defn set-viewport!
  "Lay the page out at `size` ({:width :height}, CSS px)."
  [page size]
  (page-call page "Emulation.setDeviceMetricsOverride"
             (assoc size :deviceScaleFactor 1 :mobile false) page-timeout-ms "the page"))

(defn export-page!
  "Navigate to page-url and return window.simplevizExport's reply: its
  :data base64 for \"png\", SVG text for \"svg\"; for \"pdf\" the page
  lays out its slides, a page each (:slides how many), and :data is the
  PDF Chrome prints of it, base64."
  [conn page-url {:keys [format theme timeout-ms]}]
  (let [page (open-page conn)]
    (try
      (when (= format "pdf")
        (set-viewport! page slide-size))
      (navigate! page page-url)
      (let [r (evaluate page (str "window.simplevizExport("
                                  (json/generate-string {:format format :theme theme}) ")")
                        timeout-ms)]
        (if (= format "pdf")
          (assoc r :data (:data (page-call page "Page.printToPDF"
                                           {:printBackground true :preferCSSPageSize true}
                                           timeout-ms "printing the PDF")))
          r))
      ;; a failure to close must not hide the export's own
      (finally (try (close-page page) (catch Exception _ nil))))))
