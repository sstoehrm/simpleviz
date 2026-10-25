(ns auth
  "Per-server capability URLs, with no cookies shared with other ports."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn new-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn with-launch-file
  "Open via a private redirect file, keeping the capability out of the
  desktop opener's process arguments. Home is visible to snap browsers;
  their /tmp and hidden home directories may not be. Keep it until f
  returns or the server shuts down, then delete it."
  [url f]
  (let [dir (fs/create-temp-dir {:dir (fs/home) :prefix "simpleviz-launch-"})
        file (io/file (str dir) "open.html")
        cleanup (Thread. ^Runnable (fn [] (fs/delete-tree dir)))]
    (try
      (fs/set-posix-file-permissions dir "rwx------")
      (fs/create-file file {:posix-file-permissions "rw-------"})
      (spit file (str "<!doctype html><meta name=referrer content=no-referrer>"
                      "<meta http-equiv=refresh content=\"0;url="
                      (str/escape url {\& "&amp;" \" "&quot;" \< "&lt;" \> "&gt;"})
                      "\">"))
      (.addShutdownHook (Runtime/getRuntime) cleanup)
      (f (str (.toURI file)))
      (finally
        (fs/delete-tree dir)
        (try (.removeShutdownHook (Runtime/getRuntime) cleanup)
             (catch IllegalStateException _ nil))))))

(defn wrap-session
  "Require this session's random path before routing any resource.
  Strip it before routing/logging, and never send it as a referrer."
  [handler token]
  (fn [{:keys [uri query-string] :as request}]
    (let [prefix (str "/" @token)
          response (cond
                     (= uri prefix)
                     {:status 302 :headers {"Location" (str prefix "/"
                                                          (when query-string (str "?" query-string)))}}

                     (str/starts-with? (str uri) (str prefix "/"))
                     (handler (assoc request :uri (subs uri (count prefix))))

                     :else
                     {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"}
                      :body "forbidden: open the URL printed by simpleviz"})]
      (update response :headers merge {"Referrer-Policy" "no-referrer"
                                       "Cache-Control" "no-store"}))))
