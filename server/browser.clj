(ns browser
  "Headless Chrome for `simpleviz export`: find the user's browser, start
  it with a throwaway profile, and drive the page over the DevTools
  protocol (CDP) — babashka built-ins only."
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
  "How long the browser may take to answer its debugging pipe: a first,
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
  "Drain stderr for the browser's whole life and retain its last lines."
  [proc tail]
  (future
    (with-open [r (io/reader (:err proc))]
      (doseq [line (line-seq r)]
        (swap! tail #(vec (take-last 5 (conj % line))))))))

(defn- pipe-connection
  "Read NUL-delimited CDP JSON from the child's private output pipe."
  [proc]
  (let [pending (atom {})
        events (atom {})
        closed (atom nil)
        connection {:in (:in proc) :pending pending :events events
                    :ids (atom 0) :closed closed}]
    (future
      (try
        (with-open [reader (io/reader (:out proc) :encoding "UTF-8")]
          (let [chars (char-array 8192)
                buf (StringBuilder.)]
            (loop []
              (let [n (.read reader chars)]
                (when (pos? n)
                  (.append buf chars 0 n)
                  (loop []
                    (let [end (.indexOf buf "\u0000")]
                      (when (not (neg? end))
                        (let [m (json/parse-string (.substring buf 0 end) true)]
                          (.delete buf 0 (inc end))
                          (if-let [id (:id m)]
                            (some-> (get @pending id) (deliver m))
                            (some-> (get @events [(:sessionId m) (:method m)]) (deliver m))))
                        (recur))))
                  (recur))))))
        (catch Exception e
          (reset! closed (str "browser debugging pipe failed: " (ex-message e))))
        (finally
          (let [message (or @closed "browser closed its debugging pipe")]
            (reset! closed message)
            (doseq [reply (vals @pending)]
              (deliver reply {:error {:message message}}))))))
    connection))

(declare call)

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
  "Start the browser headless with a throwaway profile and private CDP
  pipes, call (f connection profile), and always stop it and delete its
  profile. A shutdown hook covers Ctrl-C and SIGTERM; only SIGKILL escapes."
  [path f]
  (let [profile (profile-dir! path)
        proc (volatile! nil)
        cleanup (Thread. ^Runnable (fn [] (stop! @proc profile)))
        tail (atom [])
        nm (str (fs/file-name path))]
    (.addShutdownHook (Runtime/getRuntime) cleanup)
    (try
      ;; Chrome reads fd 3 and writes fd 4. Duplicate the private process
      ;; pipes onto those descriptors, keeping stdout free of protocol
      ;; traffic and never opening an unauthenticated debugging port.
      (vreset! proc (try (p/process ["sh" "-c" "exec 3<&0 4>&1; exec \"$@\" </dev/null >/dev/null"
                                     "simpleviz-browser" path "--headless" "--remote-debugging-pipe"
                                     (str "--user-data-dir=" profile)
                                     "--no-first-run" "--no-default-browser-check" "about:blank"]
                                    {:in :pipe :out :pipe :err :pipe})
                         (catch java.io.IOException e
                           (throw (ex-info (str "could not start " nm ": " (ex-message e)) {})))))
      (let [stderr (drain! @proc tail)
            connection (pipe-connection @proc)]
        (try
          (call connection "Browser.getVersion" {} launch-timeout-ms "the browser")
          (catch Exception e
            (when-not (:timeout (ex-data e)) (deref stderr 1000 nil))
            (throw (ex-info (str (if (:timeout (ex-data e))
                                  (str nm " did not start within " (quot launch-timeout-ms 1000) " s: ")
                                  (str "could not start " nm ": "))
                                (if (seq @tail) (str/join " | " @tail) (ex-message e))) {}))))
        (f connection profile))
      (finally
        (stop! @proc profile)
        (try (.removeShutdownHook (Runtime/getRuntime) cleanup)
             (catch IllegalStateException _ nil))))))

;; --- CDP ----------------------------------------------------------------

(defn- call
  "Send a CDP command and wait for its result; page commands carry the
  flattened target sessionId on the same private browser pipe."
  [{:keys [in pending ids closed session]} method params timeout-ms what]
  (let [id (swap! ids inc)
        reply (promise)
        command (cond-> {:id id :method method :params params}
                  (some? session) (assoc :sessionId session))]
    (swap! pending assoc id reply)
    (try
      (locking in
        (when-let [message @closed] (throw (ex-info message {})))
        (.write in (.getBytes (str (json/generate-string command) "\u0000") "UTF-8"))
        (.flush in))
      (let [m (deref reply timeout-ms nil)]
        (cond (nil? m) (throw (ex-info (str what " did not finish within " (quot timeout-ms 1000) " s") {:timeout true}))
              (:error m) (throw (ex-info (str method ": " (get-in m [:error :message])) {}))
              :else (:result m)))
      (finally (swap! pending dissoc id)))))

(defn open-page
  "Create a page and attach a flattened CDP session over the browser pipe."
  [connection]
  (let [target (:targetId (call connection "Target.createTarget" {:url "about:blank"} page-timeout-ms "the page"))
        session (:sessionId (call connection "Target.attachToTarget" {:targetId target :flatten true} page-timeout-ms "the page"))]
    (assoc connection :session session :target target)))

(defn close-page [{:keys [session target events] :as page}]
  (try
    (call (dissoc page :session) "Target.closeTarget" {:targetId target} page-timeout-ms "closing the page")
    (finally
      (swap! events (fn [m] (into {} (remove (fn [[[s _] _]] (= s session))) m))))))

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
  [{:keys [events session] :as page} url]
  (let [loaded (promise)]
    (swap! events assoc [session "Page.loadEventFired"] loaded)
    (call page "Page.enable" {} page-timeout-ms "the page")
    (call page "Page.navigate" {:url url} page-timeout-ms "the page")
    (when (nil? (deref loaded page-timeout-ms nil))
      (throw (ex-info (str "the page did not load within " (quot page-timeout-ms 1000) " s") {})))))

(def slide-size
  "A PDF page, in CSS px: the page lays a slide out at this size, 16:9."
  {:width 1280 :height 720})

(defn set-viewport!
  "Lay the page out at `size` ({:width :height}, CSS px)."
  [page size]
  (call page "Emulation.setDeviceMetricsOverride"
        (assoc size :deviceScaleFactor 1 :mobile false) page-timeout-ms "the page"))

(defn export-page!
  "Navigate to page-url and return window.simplevizExport's reply: its
  :data base64 for \"png\", SVG text for \"svg\"; for \"pdf\" the page
  lays out its slides, a page each (:slides how many), and :data is the
  PDF Chrome prints of it, base64."
  [connection page-url {:keys [format theme timeout-ms]}]
  (let [page (open-page connection)]
    (try
      (when (= format "pdf")
        (set-viewport! page slide-size))
      (navigate! page page-url)
      (let [r (evaluate page (str "window.simplevizExport("
                                  (json/generate-string {:format format :theme theme}) ")")
                        timeout-ms)]
        (if (= format "pdf")
          (assoc r :data (:data (call page "Page.printToPDF"
                                      {:printBackground true :preferCSSPageSize true}
                                      timeout-ms "printing the PDF")))
          r))
      (finally (close-page page)))))
