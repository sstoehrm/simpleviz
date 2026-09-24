(ns proc-util
  "Helpers for tests that run simpleviz as a separate process."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]))

(def repo-root (System/getProperty "user.dir"))

(defn free-port
  "A TCP port that was free a moment ago."
  []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn start
  "Start `cmd` (a vector of strings) in `dir` with extra environment
  `env`; stderr goes to the test output. Returns the babashka.process
  with :lines, the one reader over its stdout that every await-line
  call shares (a second reader would miss what the first read ahead)."
  [cmd & {:keys [dir env]}]
  (let [proc (p/process (cond-> {:err :inherit :cmd cmd}
                          (some? dir) (assoc :dir (str dir))
                          (some? env) (assoc :extra-env env)))]
    (assoc proc :lines (io/reader (:out proc)))))

(defn await-line
  "The re-find of `re` in the next stdout line of `proc` that matches,
  reading for at most `timeout-ms`; nil when the output ends or the time
  runs out. Reads from (:lines proc) when `start` made the process, so
  repeated calls continue where the last one stopped."
  [proc re timeout-ms]
  (let [rdr (or (:lines proc) (io/reader (:out proc)))
        f (future (loop []
                    (when-let [line (.readLine rdr)]
                      (or (re-find re line) (recur)))))]
    (deref f timeout-ms nil)))
