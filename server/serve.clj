(ns serve
  "Static file server + EDN->JSON API. Parses and normalizes the graph
  (shape checks, semantics) server-side via graph/normalize; the browser
  just renders the resulting JSON."
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [diff]
            [edit]
            [graph]
            [log]
            [org.httpkit.server :as srv]
            [png]))

(def default-port 7373)

(def files (atom nil)) ; {:old <path-or-nil> :new <path>}

(def undo-stacks (atom {})) ; path -> [text ...] newest last, capped

(defn- push-undo! [path text]
  (swap! undo-stacks update path (fn [st] (vec (take-last 100 (conj (or st []) text))))))

(defn- pop-undo! [path]
  (when-let [top (peek (get @undo-stacks path))]
    (swap! undo-stacks update path pop)
    top))

(defn- edit-response [{:keys [old new]} {:keys [file ops]}]
  (try
    (let [path (if (= file "old") old new)]
      (cond
        (nil? path) {:error "no old file in single-file mode"}
        (png/png? path) {:error "PNG sources are read-only"}
        (= "undo" (:op (first ops)))
        (if-let [prev (pop-undo! path)]
          (do (spit path prev) {:ok true})
          {:error "nothing to undo"})
        :else
        ;; snapshot the ORIGINAL text before applying — `before` feeds
        ;; both the patch and the undo stack
        (let [before (slurp path)
              {:keys [text error]} (edit/apply-ops before ops)]
          (if (some? error)
            {:error error}
            (do (push-undo! path before)
                (spit path text)
                {:ok true})))))
    (catch Exception e {:error (ex-message e)})))

(def ^:private usage
  (str "usage: bb serve <graph.edn|export.png> [<new.edn|new.png>] [--port N] [--debug]\n"
       "  pass two files to compare them: first = old, second = new\n"
       "  a PNG exported from simpleviz serves its embedded EDN;\n"
       "  a compare-mode export re-opens as the comparison\n"
       "  --debug writes a per-run log of edits and errors to " log/dir-hint
       "\n  (default port " default-port ")"))

(defn- read-source
  "EDN text of a graph file: simpleviz PNG exports yield their embedded
  EDN (compare exports yield the new side), everything else its raw
  contents. Throws with a clear message when a PNG has nothing embedded."
  [f]
  (if (png/png? f)
    (or (png/extract f "simpleviz-edn-new")
        (png/extract f "simpleviz-edn")
        (throw (ex-info (str "no embedded simpleviz EDN found in " f) {})))
    (slurp f)))

(defn- embedded-old
  "The old-side EDN of a single-file compare export, nil otherwise."
  [f]
  (when (png/png? f) (png/extract f "simpleviz-edn-old")))

(def cli-spec {:alias {:p :port} :coerce {:port :long :debug :boolean}})

(defn parse-args
  "CLI args -> {:file f :port n :debug b}, {:old-file f1 :file f2 :port n
  :debug b}, or {:error msg}. Graph files are positional (one = serve,
  two = compare old -> new); --port / -p overrides the default; --debug
  turns on the run log."
  [args]
  (try
    ;; babashka.cli reads options positionally (a positional after an
    ;; option ends option parsing), so the bare flag is picked off first
    ;; and may sit anywhere on the line
    (let [debug (boolean (some #{"--debug"} args))
          {:keys [args opts]} (cli/parse-args (remove #{"--debug"} args) cli-spec)
          [f1 f2 & extra] args
          port (get opts :port default-port)]
      (cond
        (nil? f1) {:error usage}
        (seq extra) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        (some? f2) {:old-file f1 :file f2 :port port :debug debug}
        :else {:file f1 :port port :debug debug}))
    (catch Exception e
      {:error (str "invalid arguments: " (ex-message e) "\n" usage)})))

(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  With fname, the payload carries it as :file (the export download
  name). extra-map, when given, is merged into the payload (e.g. the
  :editable flag). Parse failures return {\"error\": message} instead of
  throwing."
  ([s] (graph-json s nil))
  ([s fname] (graph-json s fname nil))
  ([s fname extra-map]
   (try
     (json/generate-string
      (cond-> (graph/normalize (edn/read-string s))
        (some? fname) (assoc :file fname)
        (some? extra-map) (merge extra-map)))
     (catch Exception e
       (json/generate-string {:error (ex-message e)})))))

(defn compare-json
  "Parse and normalize two EDN strings, diff them into one union-graph
  JSON string; file-name overrides the export download name (defaults to
  new-name's basename). A parse failure returns {\"error\": \"<file>: msg\"}."
  ([old-s new-s old-name new-name]
   (compare-json old-s new-s old-name new-name
                 (.getName (io/file new-name)) nil))
  ([old-s new-s old-name new-name file-name]
   (compare-json old-s new-s old-name new-name file-name nil))
  ([old-s new-s old-name new-name file-name extra-map]
   (try
     (let [parse (fn [s nm]
                   (try (edn/read-string s)
                        (catch Exception e
                          (throw (ex-info (str nm ": " (ex-message e)) {})))))
           old-g (graph/normalize (parse old-s old-name))
           new-g (graph/normalize (parse new-s new-name))]
       (json/generate-string
        (cond-> (assoc (diff/union old-g new-g old-name new-name)
                       :file file-name)
          (some? extra-map) (merge extra-map))))
     (catch Exception e
       (json/generate-string {:error (ex-message e)})))))

(def mime-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "json" "application/json"
   "svg"  "image/svg+xml"})

(defn- local-origin?
  "true when origin is absent (non-browser clients like curl send no
  Origin header) or is exactly this server's own http://localhost:<port>
  or http://127.0.0.1:<port> — same-origin browser fetches send Origin on
  POST even though they omit it on GET."
  [origin port]
  (or (nil? origin)
      (contains? #{(str "http://localhost:" port) (str "http://127.0.0.1:" port)} origin)))

(defn- json-content-type? [ct]
  (and (some? ct) (str/starts-with? (str/lower-case ct) "application/json")))

(defn- edit-guard
  "HTTP-level rejection response for a write to /api/edit, or nil when the
  request may proceed: 403 on a foreign Origin (cross-origin write
  attempt), 415 when Content-Type isn't application/json."
  [{:keys [headers server-port]}]
  (cond
    (not (local-origin? (get headers "origin") server-port))
    {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "forbidden"}
    (not (json-content-type? (get headers "content-type")))
    {:status 415 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "unsupported media type"}
    :else nil))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-store"}
   :body body})

(defn- static-response [uri]
  (let [path (if (= uri "/") "/index.html" uri)
        file (io/file "public" (subs path 1))]
    (if (and (.isFile file) (not (str/includes? path "..")))
      {:status 200
       :headers {"Content-Type" (get mime-types
                                     (last (str/split (.getName file) #"\."))
                                     "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body file}
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body "not found"})))

(defn- graph-response-body []
  (let [body (try
               (let [{:keys [old new]} @files]
                 (if (some? old)
                   (compare-json (read-source old) (read-source new) old new
                                 (.getName (io/file new))
                                 {:editable (not (png/png? new))
                                  :editable-old (not (png/png? old))})
                   (if-let [old-s (embedded-old new)]
                     (let [nm (.getName (io/file new))]
                       (compare-json old-s (read-source new)
                                     (str nm " (old)") (str nm " (new)") nm
                                     {:editable false :editable-old false}))
                     (graph-json (read-source new) (.getName (io/file new))
                                 {:editable (not (png/png? new))}))))
               (catch Exception e
                 (json/generate-string {:error (ex-message e)})))]
    ;; graph-json/compare-json fold parse failures into the payload; only
    ;; a debug run pays for parsing it back to find out
    (when (log/enabled?)
      (when-let [err (get (json/parse-string body) "error")]
        (log/event! "error" {:route "/api/graph" :error err})))
    body))

(defn- edit-response-body
  "Parse the edit request, apply it, log what was asked and what came of
  it, and return the JSON reply."
  [body-stream]
  (let [parsed (try (json/parse-string (slurp body-stream) true)
                    (catch Exception e {:parse-error (ex-message e)}))
        out (if-let [err (:parse-error parsed)]
              {:error err}
              (edit-response @files parsed))]
    (log/event! "edit" {:file (:file parsed) :ops (:ops parsed) :result out})
    (json/generate-string out)))

(defn- route [{:keys [uri query-string request-method body] :as req}]
  (case uri
    "/api/graph"   (json-response (graph-response-body))
    "/api/edit"    (if (= :post request-method)
                     (or (edit-guard req)
                         (json-response (edit-response-body body)))
                     {:status 405 :headers {"Content-Type" "text/plain"} :body "POST only"})
    "/api/version" (json-response
                    (json/generate-string
                     {:mtime (let [{:keys [old new]} @files
                                   m (.lastModified (io/file new))]
                               (if (some? old)
                                 (str (.lastModified (io/file old)) "-" m)
                                 m))}))
    "/api/source"
    (let [{:keys [old new]} @files
          which (when (some? query-string)
                  (second (re-find #"(?:^|&)which=(old|new)(?:&|$)" query-string)))
          body (try
                 (if (= which "old")
                   (if (some? old) (read-source old) (embedded-old new))
                   (when (some? new) (read-source new)))
                 (catch Exception _ nil))]
      (if (some? body)
        {:status 200
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body body}
        {:status 404
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body "not found"}))
    (static-response uri)))

(defn- request-line [req]
  (str (some-> (:request-method req) name str/upper-case) " " (:uri req)))

(defn guard
  "Wrap a ring handler so an uncaught exception becomes a crash report on
  disk plus a 500 JSON error (with a non-null message, which the browser
  reads as failure), and every rejected request (status >= 400) lands in
  the debug log."
  [h]
  (fn [req]
    (try
      (let [{:keys [status body] :as resp} (h req)]
        (when (and (some? status) (>= status 400))
          (log/event! "error" {:request (request-line req) :status status
                               :body (when (string? body) body)}))
        resp)
      (catch Throwable e
        (log/crash! {:request (request-line req)} e)
        {:status 500
         :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
         :body (json/generate-string {:error (or (ex-message e) (.getName (class e)))})}))))

(def handler (guard route))

(defn- version
  "The installed release: the launcher drops a VERSION file into the
  install dir, which is the server's cwd; a checkout has none."
  []
  (let [f (io/file "VERSION")]
    (if (.isFile f) (str/trim (slurp f)) "dev")))

(defn -main [& args]
  (let [{:keys [file old-file port debug error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (doseq [f (if old-file [old-file file] [file])]
      (when-not (.isFile (io/file f))
        (println (str "file not found: " f))
        (System/exit 1))
      ;; resolve once so a PNG without embedded EDN fails at startup with
      ;; a clear message instead of an empty diagram in the browser
      (try (read-source f)
           (catch Exception e
             (println (ex-message e))
             (System/exit 1))))
    (reset! files {:old old-file :new file})
    (let [serving (cond
                    old-file (str old-file " → " file " (compare)")
                    (some? (embedded-old file)) (str file " (embedded compare)")
                    :else file)
          log-path (log/init! {:dir (log/default-dir)
                               :debug debug
                               :header (str "simpleviz " (version)
                                            " (babashka " (System/getProperty "babashka.version")
                                            ") serving " serving " on port " port)})]
      (log/install-crash-handler!)
      (try
        ;; loopback only — /api/edit can write to disk, so the server must
        ;; never be reachable from other hosts on the network
        (srv/run-server handler {:port port :ip "127.0.0.1"})
        (println (str "simpleviz: serving " serving " at http://localhost:" port))
        (when log-path (println (str "simpleviz: debug log at " log-path)))
        @(promise)
        (catch java.net.BindException _
          ;; a busy port is a usage problem, not a crash
          (println (str "port " port " is already in use — pass --port N to pick another"))
          (System/exit 1))
        (catch Throwable e
          (log/crash! {:phase "startup"} e)
          (System/exit 1))))))
