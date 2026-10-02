(ns browser
  "Headless Chrome for `simpleviz export`: find the user's browser, start
  it with a throwaway profile, and drive the page over the DevTools
  protocol (CDP) — babashka built-ins only."
  (:require [babashka.fs :as fs]
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
