(ns session-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.process :as p]
            [org.httpkit.server :as srv]
            [proc-util]
            [session]))

(def tok "k3y_-Abc")

(defn- echo
  "A handler answering with the uri and query string it was given."
  [req]
  {:status 200 :headers {"Content-Type" "text/plain"} :body (str (:uri req) "|" (:query-string req))})

(def gated (session/wrap echo tok))

(deftest tokens-are-long-random-and-url-safe
  (let [a (session/new-token) b (session/new-token)]
    (is (re-matches #"[A-Za-z0-9_-]{43}" a) "256 bits, base64url without padding")
    (is (not= a b))))

(deftest the-token-prefix-is-stripped-before-routing
  (is (= "/api/root|" (:body (gated {:uri (str "/" tok "/api/root")}))))
  (is (= "/|slide=1" (:body (gated {:uri (str "/" tok "/") :query-string "slide=1"}))))
  (is (= "/js/app.mjs|" (:body (gated {:uri (str "/" tok "/js/app.mjs")})))))

(deftest the-bare-token-redirects-to-its-folder
  ;; relative URLs in the page resolve against /<token>/
  (let [r (gated {:uri (str "/" tok) :query-string "slide=2"})]
    (is (= 302 (:status r)))
    (is (= (str "/" tok "/?slide=2") (get-in r [:headers "Location"]))))
  (is (= (str "/" tok "/") (get-in (gated {:uri (str "/" tok)}) [:headers "Location"]))))

(deftest anything-without-the-token-is-forbidden
  (doseq [uri ["/" "/api/root" "/index.html" "/favicon.ico" "/api/watch"
               (str "/" tok "x/api/root") (str "/x" tok "/api/root")
               (str "/" (subs tok 1) "/api/root") (str "/" (str/upper-case tok) "/api/root")
               (str "/api/root/" tok "/") "//api/root" ""]]
    (testing uri
      (let [r (gated {:uri uri})]
        (is (= 403 (:status r)))
        (is (not (str/includes? (str (:body r)) tok)) "the answer never names the token")))))

(deftest responses-send-no-referrer
  ;; an md doc's link to another site must not carry the token along
  (is (= "no-referrer" (get-in (gated {:uri (str "/" tok "/")}) [:headers "Referrer-Policy"])))
  (is (= "no-referrer" (get-in (gated {:uri "/"}) [:headers "Referrer-Policy"])))
  (is (= "text/plain" (get-in (gated {:uri (str "/" tok "/")}) [:headers "Content-Type"]))
      "the handler's own headers stay"))

(deftest a-browser-page-load-is-reported
  (let [seen (atom 0)
        h (session/wrap echo tok {:on-page #(swap! seen inc)})
        doc {"sec-fetch-dest" "document"}]
    (h {:uri (str "/" tok "/api/root") :headers doc})
    (h {:uri "/" :headers doc})
    (h {:uri (str "/" tok "/")})
    (is (= 0 @seen) "an API call, a refused request, curl's GET of the page: none is a browser's load")
    (h {:uri (str "/" tok "/") :query-string "file=a.edn" :headers doc})
    (h {:uri (str "/" tok "/index.html") :headers doc})
    (is (= 2 @seen))))

;; ---- the launch file ----------------------------------------------------

(defn- mode [f] (fs/posix->str (fs/posix-file-permissions f)))

(deftest the-launch-file-redirects-privately-and-goes-away
  (let [home (fs/create-temp-dir {:prefix "session-home"})
        url (str "http://localhost:7400/" tok)
        {:keys [uri delete!]} (session/launch-file! url {:home (str home)})
        f (fs/file (java.net.URI. uri))]
    (try
      (is (str/starts-with? uri "file:"))
      (is (not (str/includes? uri tok)) "the opener's arguments never carry the token")
      (is (not (str/starts-with? (fs/file-name (fs/parent f)) ".")) "a snap browser can't read hidden folders")
      (is (= "rwx------" (mode (fs/parent f))))
      (is (= "rw-------" (mode f)))
      (let [html (slurp f)]
        (is (str/includes? html (str "url=" url "/")))
        (is (str/includes? html "no-referrer")))
      (delete!)
      (is (not (fs/exists? (fs/parent f))))
      (delete!)
      (is (not (fs/exists? (fs/parent f))) "deleting twice is harmless")
      (finally (fs/delete-tree home)))))

(deftest the-launch-file-escapes-the-url
  (let [home (fs/create-temp-dir {:prefix "session-home"})
        {:keys [uri delete!]} (session/launch-file! "http://localhost:1/a\"><script>" {:home (str home)})]
    (try
      (is (not (str/includes? (slurp (fs/file (java.net.URI. uri))) "\"><script>")))
      (finally (delete!) (fs/delete-tree home)))))

;; ---- the registry: how an agent finds a server it didn't start ----------

(defn- with-gated-server
  "Run a server behind session/wrap with token `t` on a free port and
  call (f url), its URL without the trailing slash."
  [t f]
  (let [stop (srv/run-server (session/wrap echo t) {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (str "http://localhost:" (srv/server-port stop) "/" t))
         (finally (srv/server-stop! stop)))))

(defn- dead-url
  "The URL of a port nothing listens on any more."
  []
  (str "http://localhost:" (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)) "/x"))

(defn- tmp-home [] (str (fs/create-temp-dir {:prefix "session-home"})))

(deftest a-registered-server-is-listed-privately-and-unlisted-on-exit
  (with-gated-server tok
    (fn [url]
      (let [home (tmp-home)
            entry {:url url :file "/w/arch.edn"}
            unregister! (session/register! 7401 entry {:home home})
            dir (fs/path home "servers")]
        (try
          (is (= "rwx------" (mode dir)))
          (is (= ["rw-------"] (map mode (fs/list-dir dir))))
          (is (= [entry] (session/servers {:home home})))
          (unregister!)
          (is (= [] (session/servers {:home home})))
          (finally (fs/delete-tree home)))))))

(deftest no-registry-is-no-servers
  (is (= [] (session/servers {:home (str (fs/path (tmp-home) "none"))}))))

(deftest an-entry-whose-server-died-is-dropped-whatever-answers-on-its-port
  ;; SIGKILL runs no shutdown hook; then another user's program may take
  ;; the port and answer 200 to everything
  (let [squatter (srv/run-server (fn [_] {:status 200 :body "{}"}) {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        url (str "http://localhost:" (srv/server-port squatter) "/" tok)
        home (tmp-home)]
    (try
      (p/shell {:dir proc-util/repo-root}
               "bb" "-e" (str "(require 'session) (session/register! 7410 " (pr-str {:url url :file "/w/a.edn"})
                              " {:home " (pr-str home) "}) (System/exit 0)"))
      (is (fs/exists? (fs/path home "servers" "7410.edn")) "the dead server's entry is still there")
      (is (= [] (session/servers {:home home})))
      (is (empty? (fs/list-dir (fs/path home "servers"))) "and now deleted")
      (finally (srv/server-stop! squatter) (fs/delete-tree home)))))

(deftest another-processs-live-server-is-listed
  (let [home (tmp-home)
        proc (p/process {:dir proc-util/repo-root :err :inherit}
                        "bb" "-e" (str "(require 'session) (session/register! 7411 {:url \"http://localhost:7411/x\" :file \"/w/a.edn\"}"
                                       " {:home " (pr-str home) "}) (println :ok) (flush) (Thread/sleep 60000)"))]
    (try
      (is (= ":ok" (.readLine (io/reader (:out proc)))))
      (is (= [{:url "http://localhost:7411/x" :file "/w/a.edn"}] (session/servers {:home home}))
          "listed without asking its port anything")
      (finally (p/destroy-tree proc) (deref proc 10000 nil) (fs/delete-tree home)))))

(defn- leave-entry!
  "An entry as a server killed with SIGKILL leaves it: no lock held."
  [home port entry]
  (fs/create-dirs (fs/path home "servers"))
  (spit (str (fs/path home "servers" (str port ".edn"))) (pr-str entry)))

(deftest a-gone-servers-entry-is-dropped
  (let [home (tmp-home)]
    (try
      (leave-entry! home 7402 {:url (dead-url) :file "/w/a.edn"})
      (is (= [] (session/servers {:home home})))
      (is (empty? (fs/list-dir (fs/path home "servers"))) "and its file deleted")
      (finally (fs/delete-tree home)))))

(deftest a-garbled-entry-is-skipped
  (with-gated-server tok
    (fn [url]
      (let [home (tmp-home)]
        (try
          (session/register! 7404 {:url url :file "/w/a.edn"} {:home home})
          (spit (str (fs/path home "servers" "7405.edn")) "{:url")
          (is (= [url] (map :url (session/servers {:home home}))))
          (finally (fs/delete-tree home)))))))

(deftest unregistering-leaves-a-later-servers-entry
  (let [home (tmp-home)]
    (try
      (let [unregister-old! (session/register! 7406 {:url "http://localhost:7406/old" :file "/w/a.edn"} {:home home})]
        (session/register! 7406 {:url "http://localhost:7406/new" :file "/w/a.edn"} {:home home})
        (unregister-old!)
        (is (= {:url "http://localhost:7406/new" :file "/w/a.edn"}
               (edn/read-string (slurp (str (fs/path home "servers" "7406.edn")))))))
      (finally (fs/delete-tree home)))))

(deftest a-registry-folder-others-can-enter-is-made-private
  (let [home (tmp-home)
        dir (fs/create-dirs (fs/path home "servers"))]
    (try
      (fs/set-posix-file-permissions dir "rwxrwxrwx")
      (session/register! 7407 {:url "http://localhost:7407/x" :file "/w/a.edn"} {:home home})
      (is (= "rwx------" (mode dir)))
      (finally (fs/delete-tree home)))))

(deftest a-symlinked-registry-folder-is-refused
  (let [home (tmp-home)
        elsewhere (fs/create-dirs (fs/path home "elsewhere"))]
    (try
      (fs/create-sym-link (fs/path home "servers") elsewhere)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a private folder"
                            (session/register! 7408 {:url "http://localhost:7408/x" :file "/w/a.edn"} {:home home})))
      (is (empty? (fs/list-dir elsewhere)))
      (finally (fs/delete-tree home)))))

(deftest a-symlinked-entry-is-not-written-through
  (let [home (tmp-home)
        dir (fs/create-dirs (fs/path home "servers"))
        target (fs/path home "target")]
    (try
      (spit (str target) "keep")
      (fs/create-sym-link (fs/path dir "7409.edn") target)
      (session/register! 7409 {:url "http://localhost:7409/x" :file "/w/a.edn"} {:home home})
      (is (= "keep" (slurp (str target))))
      (is (not (fs/sym-link? (fs/path dir "7409.edn"))))
      (finally (fs/delete-tree home)))))

(deftest the-page-asks-the-server-by-relative-urls
  ;; "/api/root" would miss the token the page lives below
  (doseq [f (fs/glob "src/simpleviz" "*.cljs")
          :let [hits (re-seq #"\"/(?:api|js|vendor)/[^\"]*\"" (slurp (str f)))]]
    (is (nil? hits) (str f))))
