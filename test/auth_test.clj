(ns auth-test
  (:require [babashka.fs :as fs]
            [auth]
            [babashka.http-client :as http]
            [babashka.http-client.websocket :as ws]
            [browser]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [log]
            [org.httpkit.server :as srv]
            [serve]))

(deftest anonymous-requests-cannot-read-or-change-served-files
  (let [dir (fs/create-temp-dir {:prefix "serve-auth-"})
        file (str (fs/path dir "private.md"))
        before-files @serve/files
        before-root @serve/root-dir]
    (try
      (spit file "private text")
      (reset! serve/files {:root file})
      (reset! serve/root-dir (.getCanonicalFile (java.io.File. (str dir))))
      (doseq [uri ["/" "/api/root" "/api/text" "/api/file" "/api/source"
                   "/not-the-session/api/text"]]
        (let [response (serve/handler {:uri uri :request-method :get
                                       :query-string "path=private.md"
                                       :headers {"host" "localhost:7373"}})]
          (is (= 403 (:status response)) uri)
          (is (not (.contains (str (:body response)) "private text")) uri)))
      (let [response (serve/handler
                      {:uri "/api/text/save" :request-method :post :server-port 7373
                       :headers {"host" "localhost:7373" "content-type" "application/json"}
                       :body (java.io.ByteArrayInputStream.
                               (.getBytes "{\"path\":\"private.md\",\"text\":\"overwritten\"}" "UTF-8"))})]
        (is (= 403 (:status response)))
        (is (= "private text" (slurp file))))
      (finally
        (reset! serve/files before-files)
        (reset! serve/root-dir before-root)
        (fs/delete-tree dir)))))

(deftest session-paths-are-random-and-isolated
  (let [token (atom (auth/new-token))
        old @token
        seen (atom [])
        handler (auth/wrap-session
                  (fn [request] (swap! seen conj (:uri request)) {:status 200 :body "ok"}) token)]
    (is (re-matches #"[A-Za-z0-9_-]{43}" old))
    (is (= 302 (:status (handler {:uri (str "/" old) :query-string "file=a.edn"}))))
    (is (= (str "/" old "/?file=a.edn")
           (get-in (handler {:uri (str "/" old) :query-string "file=a.edn"}) [:headers "Location"])))
    (doseq [uri ["/" "/api/root" (str "/" old "x/api/root")]]
      (let [response (handler {:uri uri})]
        (is (= 403 (:status response)))
        (is (not (str/includes? (pr-str response) old)))))
    (let [response (handler {:uri (str "/" old "/api/root")})]
      (is (= 200 (:status response)))
      (is (= ["/api/root"] @seen))
      (is (= "no-referrer" (get-in response [:headers "Referrer-Policy"])))
      (is (= "no-store" (get-in response [:headers "Cache-Control"]))))
    (reset! token (auth/new-token))
    (is (not= old @token))
    (is (= 403 (:status (handler {:uri (str "/" old "/api/root")}))))
    (is (= 200 (:status (handler {:uri (str "/" @token "/api/root")}))))))

(deftest each-server-start-issues-a-new-session-url
  (let [file (fs/create-temp-file {:prefix "serve-session-" :suffix ".md"})
        before [@serve/files @serve/root-dir @serve/session-token @serve/prefs-on]
        bindings (atom [])]
    (try
      (spit (str file) "private")
      (with-redefs [srv/run-server (fn [_ options] (swap! bindings conj options))
                    log/init! (constantly nil)
                    log/install-crash-handler! (constantly nil)]
        (let [first-url (:url (serve/start! {:file (str file) :port 7373}))
              second-url (:url (serve/start! {:file (str file) :port 7373}))]
          (is (not= first-url second-url))
          (is (= (str "http://localhost:7373/" @serve/session-token) second-url))
          (is (= [{:port 7373 :ip "127.0.0.1"} {:port 7373 :ip "127.0.0.1"}] @bindings))))
      (finally
        (doseq [[state value] (map vector [serve/files serve/root-dir serve/session-token serve/prefs-on] before)]
          (reset! state value))
        (fs/delete file)))))

(deftest launch-files-are-removed-when-opening-fails
  (let [home (fs/create-temp-dir {:prefix "launch-failure-"})]
    (try
      (with-redefs [fs/home (constantly home)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opener failed"
                              (auth/with-launch-file "http://localhost:7373/test"
                                (fn [_] (throw (ex-info "opener failed" {})))))))
      (is (empty? (fs/list-dir home)))
      (finally (fs/delete-tree home)))))

(deftest the-private-launch-file-opens-the-authenticated-page
  (if-let [path (:path (browser/find-browser))]
    (let [token (atom (auth/new-token))
          stop (srv/run-server
                 (auth/wrap-session (fn [_] {:status 200 :headers {"Content-Type" "text/html"}
                                             :body "<!doctype html><title>authenticated</title>"}) token)
                 {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
          url (str "http://127.0.0.1:" (srv/server-port stop) "/" @token)]
      (try
        (auth/with-launch-file url
          (fn [uri]
            (browser/with-browser path
              (fn [connection _]
                (let [page (browser/open-page connection)]
                  (try
                    (browser/navigate! page uri)
                    (is (= "authenticated" (browser/evaluate page "document.title" 5000)))
                    (is (= (str url "/") (browser/evaluate page "location.href" 5000)))
                    (finally (browser/close-page page))))))))
        (finally (srv/server-stop! stop))))
    (println "auth-test: no browser found, launch-file browser test skipped")))

(deftest real-loopback-requests-require-the-session-for-reads-and-writes
  (let [dir (fs/create-temp-dir {:prefix "serve-auth-http-"})
        file (str (fs/path dir "private.md"))
        before-files @serve/files
        before-root @serve/root-dir
        stop (srv/run-server serve/handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        port (srv/server-port stop)
        origin (str "http://127.0.0.1:" port)
        url (str origin "/" @serve/session-token)
        logged (atom [])]
    (try
      (fs/set-posix-file-permissions dir "rwx------")
      (spit file "private text")
      (reset! serve/files {:root file})
      (reset! serve/root-dir (.getCanonicalFile (java.io.File. (str dir))))
      (with-redefs [log/event! (fn [kind data] (swap! logged conj [kind data]))]
        (doseq [path ["/" "/api/root" "/api/text?path=private.md" "/api/source" "/wrong/api/root"]]
          (is (= 403 (:status (http/get (str origin path) {:throw false})))))
        (is (= 403 (:status (http/post (str origin "/api/text/save")
                                      {:throw false :headers {"Content-Type" "application/json"}
                                       :body "{}"}))))
        (let [page (http/get url)
              doc (json/parse-string (:body (http/get (str url "/api/text?path=private.md"))) true)
              body (json/generate-string {:path "private.md" :base (:version doc) :text "saved"})]
          (is (str/includes? (:body page) "<html"))
          (is (= "no-referrer" (get-in page [:headers "referrer-policy"])))
          (is (= 200 (:status (http/get (str url "/style.css")))))
          (is (= "private text" (:text doc)))
          (is (= 403 (:status (http/post (str url "/api/text/save")
                                        {:throw false :headers {"Content-Type" "application/json"
                                                                "Origin" "http://evil.example"}
                                         :body body}))))
          (is (= "private text" (slurp file)))
          (is (= 200 (:status (http/post (str url "/api/text/save")
                                        {:headers {"Content-Type" "application/json"} :body body}))))
          (is (= "saved" (slurp file))))
        (is (seq @logged))
        (is (not (str/includes? (pr-str @logged) @serve/session-token))))
      (finally
        (srv/server-stop! stop)
        (reset! serve/files before-files)
        (reset! serve/root-dir before-root)
        (fs/delete-tree dir)))))

(deftest websocket-upgrades-require-the-session-and-a-local-origin
  (let [stop (srv/run-server serve/handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        port (srv/server-port stop)
        origin (str "http://127.0.0.1:" port)
        prefix (str "/" @serve/session-token)
        headers {"Host" (str "127.0.0.1:" port) "Connection" "Upgrade" "Upgrade" "websocket"
                 "Sec-WebSocket-Version" "13" "Sec-WebSocket-Key" "dGhlIHNhbXBsZSBub25jZQ=="}]
    (try
      (doseq [[path from] [["/api/watch" origin] ["/wrong/api/watch" origin]
                          [(str prefix "/api/watch") "http://evil.example"]]]
        (with-open [socket (java.net.Socket. "127.0.0.1" port)
                    writer (io/writer (.getOutputStream socket))
                    reader (io/reader (.getInputStream socket))]
          (.setSoTimeout socket 5000)
          (.write writer (str "GET " path " HTTP/1.1\r\n"
                              (str/join "\r\n" (map (fn [[k v]] (str k ": " v))
                                                       (assoc headers "Origin" from)))
                              "\r\n\r\n"))
          (.flush writer)
          (is (re-find #"^HTTP/1.1 403 " (.readLine reader)))))
      (let [before (set (keys @serve/watches))
            connection (ws/websocket {:uri (str "ws://127.0.0.1:" port prefix "/api/watch")
                                       :headers {"Origin" origin}})]
        (try
          (is (= 1 (count (remove before (keys @serve/watches)))))
          (finally (ws/close! connection))))
      (finally (srv/server-stop! stop)))))
