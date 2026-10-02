(ns browser-test
  (:require [browser]
            [clojure.test :refer [deftest is]]))

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
