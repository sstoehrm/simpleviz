(ns session
  "A server's secret: the random token in every URL it answers, so a
  program of another user on this machine — loopback TCP can't tell
  users apart — can't read or write the served files. How the browser
  gets the URL without it showing in `ps` (the launch file), and how an
  agent of the same user finds a server it didn't start (the registry)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [log]))

(defn new-token
  "256 random bits, base64url without padding: 43 URL-safe characters."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn- same-token?
  "Constant time, so the answer's timing tells nothing about how much of
  a guess was right."
  [^String a ^String b]
  (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(def ^:private forbidden
  {:status 403
   :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"}
   :body "forbidden: open the URL simpleviz printed"})

(defn wrap
  "Wrap handler `h` so it answers only below /<token>/, which it strips
  before `h` routes (and logs) the request; /<token> alone redirects
  there, so the page's relative URLs resolve below it. Anything else is
  403. No response sends a referrer: a link out of an md doc would carry
  the token. `on-page`, when given, is called each time a browser loads
  the page itself (Sec-Fetch-Dest: document — an agent's curl isn't one)."
  ([h token] (wrap h token {}))
  ([h token {:keys [on-page]}]
   (fn [{:keys [uri query-string] :as req}]
     (let [[_ head tail] (re-matches #"/([^/]*)(/.*)?" (str uri))
           resp (cond
                  (not (and (some? head) (same-token? head token))) forbidden
                  (nil? tail) {:status 302
                               :headers {"Location" (str "/" token "/" (when query-string (str "?" query-string)))}}
                  :else (do (when (and on-page (#{"/" "/index.html"} tail)
                                       (= "document" (get-in req [:headers "sec-fetch-dest"])))
                              (on-page))
                            (h (assoc req :uri tail))))]
       (assoc-in resp [:headers "Referrer-Policy"] "no-referrer")))))

;; ---- files only this user can read ---------------------------------------

(defn- uid [path]
  (java.nio.file.Files/getAttribute (fs/path path) "unix:uid"
                                    (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))

(defn- my-uid
  "This process's uid, as the owner of a file it just made: user.name
  can be \"?\" in a container whose uid has no passwd entry."
  []
  (let [f (fs/create-temp-file {:prefix "simpleviz-uid"})]
    (try (uid f) (finally (fs/delete f)))))

(defn- private-dir!
  "Folder `dir`, created when missing, readable by its owner only; throws
  when it is a link or someone else owns it."
  [dir]
  (fs/create-dirs dir)
  (when (or (fs/sym-link? dir) (not= (uid dir) (my-uid)))
    (throw (ex-info (str dir " is not a private folder of yours") {})))
  (fs/set-posix-file-permissions dir "rwx------")
  dir)

(defn- write-private!
  "Write `text` to `f` as a new file only its owner can read: one there
  already, a link included, is replaced, never written through."
  [f text]
  (fs/delete-if-exists f)
  (fs/create-file f {:posix-file-permissions "rw-------"})
  (spit (str f) text))

;; ---- the launch file ------------------------------------------------------

(defn- html-escape [s]
  (str/escape (str s) {\& "&amp;" \" "&quot;" \< "&lt;" \> "&gt;" \' "&#39;"}))

(defn launch-file!
  "A file that sends the browser on to `url`, for the desktop opener:
  its arguments show in `ps` to every user, the file to its owner alone.
  It goes in a fresh folder in home, not /tmp or a hidden folder, which
  a snap browser can't read. {:uri <file: URI> :delete! <fn>}."
  [url {:keys [home]}]
  (let [dir (fs/create-temp-dir {:dir (or home (str (fs/home))) :prefix "simpleviz-open-"
                                 :posix-file-permissions "rwx------"})
        f (fs/path dir "open.html")
        target (html-escape (str url "/"))]
    (write-private! f (str "<!doctype html><meta charset=utf-8><meta name=referrer content=no-referrer>"
                           "<meta http-equiv=refresh content=\"0;url=" target "\">"
                           "<title>simpleviz</title><a href=\"" target "\">open simpleviz</a>\n"))
    {:uri (str (.toUri f))
     :delete! (fn [] (fs/delete-tree dir))}))

;; ---- the registry: running servers, for agents of the same user ----------

(defn- registry-dir
  "servers/ in `home`, by default log/home."
  [home]
  (fs/path (or home (str (log/home))) "servers"))

(defn- open-channel
  "A channel on registry entry `f`, for its lock; never through a link."
  [f]
  (java.nio.channels.FileChannel/open (fs/path f)
                                      (into-array java.nio.file.OpenOption
                                                  [java.nio.file.StandardOpenOption/WRITE
                                                   java.nio.file.LinkOption/NOFOLLOW_LINKS])))

;; this process's entries: path -> the channel whose lock says it runs.
;; Held here so the channel is never collected (which would drop the lock)
(defonce ^:private held (atom {}))

(defn register!
  "Record the server on `port` — `entry` {:url :file}, the served file's
  absolute path — in the registry under `home`, readable by this user
  only, and hold a lock on it while the process lives: the OS drops the
  lock when the process ends, however it ends. Returns the fn that
  removes the entry again."
  [port entry {:keys [home]}]
  (let [f (fs/path (private-dir! (registry-dir home)) (str port ".edn"))
        k (str (fs/absolutize f))]
    (some-> (get @held k) .close)
    (write-private! f (pr-str entry))
    (let [ch (open-channel f)]
      (when-not (.tryLock ch)
        (.close ch)
        (throw (ex-info (str f " is locked by another process") {})))
      (swap! held assoc k ch)
      (fn []
        (when (= ch (get @held k))
          (swap! held dissoc k)
          (fs/delete-if-exists f)
          (.close ch))))))

(defn- running?
  "Does a live process hold registry entry `f`'s lock? A server's entry
  outlives a SIGKILL, and what answers on its port may then be anyone's:
  only the lock tells."
  [f]
  (or (contains? @held (str (fs/absolutize f)))
      ;; closing the channel lets go of a lock it got
      (try (with-open [ch (open-channel f)]
             (nil? (.tryLock ch)))
           ;; can't tell: keep it
           (catch Exception _ true))))

(defn servers
  "The registered servers still running, ordered by port: [{:url :file}].
  The entry of a server that is gone is deleted; an unreadable one is
  skipped."
  [{:keys [home]}]
  (let [dir (registry-dir home)]
    (if-not (fs/directory? dir)
      []
      (->> (fs/glob dir "*.edn")
           (sort-by (fn [f] (or (parse-long (str/replace (fs/file-name f) #"\.edn$" "")) 0)))
           (keep (fn [f]
                   (if (running? f)
                     (let [e (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
                       (when (and (map? e) (string? (:url e)))
                         (select-keys e [:url :file])))
                     (do (fs/delete-if-exists f) nil))))
           vec))))
