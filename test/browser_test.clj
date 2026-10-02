(ns browser-test
  (:require [babashka.fs :as fs]
            [browser]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as srv]))

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
