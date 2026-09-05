(ns log
  "Debug log and crash reports on disk. `init!` opens a per-run log file
  when debugging is on; `event!` appends one line to it (no-op otherwise);
  `crash!` always writes a standalone crash report. Neither writer throws:
  a log that cannot be written falls back to a note on stderr, so the
  failure being reported is never made worse by the reporting."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private state (atom {:dir nil :file nil :header nil}))

(def dir-hint "~/.simpleviz/logs (or $SIMPLEVIZ_HOME/logs)")

(defn default-dir
  "Where logs go: the launcher's install dir when set, else ~/.simpleviz."
  []
  (str (io/file (or (not-empty (System/getenv "SIMPLEVIZ_HOME"))
                    (io/file (System/getProperty "user.home") ".simpleviz"))
                "logs")))

(defn clear! []
  (clojure.core/reset! state {:dir nil :file nil :header nil}))

(defn enabled?
  "True while a run log is open (--debug)."
  []
  (some? (:file @state)))

(defn- stamp []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS")))

(defn- now-iso [] (.format (LocalDateTime/now) DateTimeFormatter/ISO_LOCAL_DATE_TIME))

(defn- warn! [& parts]
  (binding [*out* *err*] (println (apply str "simpleviz: " parts))))

(defn- write!
  "Write text to file f (creating its directory), returning f's path; on
  failure, say so on stderr and return nil."
  [f text & opts]
  (try
    (io/make-parents f)
    (apply spit f text opts)
    (str f)
    (catch Exception e
      (warn! "cannot write " f ": " (ex-message e))
      nil)))

(defn init!
  "Remember the log directory; with debug on, create it, open a fresh
  `simpleviz-<timestamp>.log` there with `header` as its first line, and
  return its path (nil when debug is off or the file cannot be created)."
  [{:keys [dir debug header]}]
  (swap! state assoc :dir dir :header header)
  (when debug
    (when-let [path (write! (io/file dir (str "simpleviz-" (stamp) ".log"))
                            (str header "\n"))]
      (swap! state assoc :file path)
      path)))

(defn event!
  "Append `<timestamp> <kind> <data as JSON>` to the run log; no-op when
  debugging is off. Returns nil."
  [kind data]
  (when-let [f (:file @state)]
    (write! f (str (now-iso) " " kind " " (json/generate-string data) "\n") :append true)
    nil))

(defn- stack-trace [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (str sw)))

(defn crash!
  "Write `crash-<timestamp>.log` in the log directory (the default one
  when init! has not run) — the run header, `context` (a map describing
  what was going on), and the throwable with its message, data and stack
  trace — whether or not debugging is on. The run log, when open, gets a
  one-line note, and stderr always gets the message and the report path.
  Returns the report's path, or nil when it could not be written."
  [context ^Throwable e]
  (let [{:keys [dir header]} @state
        f (io/file (or dir (default-dir)) (str "crash-" (stamp) ".log"))
        msg (or (ex-message e) (.getName (class e)))
        path (write! f (str header "\n"
                            (now-iso) " crash\n"
                            (json/generate-string context {:pretty true}) "\n\n"
                            msg "\n"
                            (when-let [d (ex-data e)] (str (pr-str d) "\n"))
                            (stack-trace e))
                     :append true)]
    (warn! "crash: " msg (if path (str " — report written to " path) ""))
    (event! "crash" (assoc context :error msg :report path))
    path))

(defn install-crash-handler!
  "Report exceptions that escape a thread without a handler of its own as
  crash files. (http-kit catches its workers' exceptions itself, so
  request handling relies on serve/guard instead.)"
  []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ t e]
       (crash! {:thread (.getName t)} e)))))
