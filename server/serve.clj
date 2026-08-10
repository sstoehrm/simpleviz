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
            [graph]
            [org.httpkit.server :as srv]))

(def default-port 7373)

(def files (atom nil)) ; {:old <path-or-nil> :new <path>}

(def ^:private usage
  (str "usage: bb serve <graph.edn> [<new.edn>] [--port N]\n"
       "  pass two files to compare them: first = old, second = new"
       "\n  (default port " default-port ")"))

(def cli-spec {:alias {:p :port} :coerce {:port :long}})

(defn parse-args
  "CLI args -> {:file f :port n}, {:old-file f1 :file f2 :port n}, or
  {:error msg}. Graph files are positional (one = serve, two = compare
  old -> new); --port / -p overrides the default."
  [args]
  (try
    (let [{:keys [args opts]} (cli/parse-args args cli-spec)
          [f1 f2 & extra] args
          port (get opts :port default-port)]
      (cond
        (nil? f1) {:error usage}
        (seq extra) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        (some? f2) {:old-file f1 :file f2 :port port}
        :else {:file f1 :port port}))
    (catch Exception e
      {:error (str "invalid arguments: " (ex-message e) "\n" usage)})))

(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  With fname, the payload carries it as :file (the export download
  name). Parse failures return {\"error\": message} instead of throwing."
  ([s] (graph-json s nil))
  ([s fname]
   (try
     (json/generate-string
      (cond-> (graph/normalize (edn/read-string s))
        (some? fname) (assoc :file fname)))
     (catch Exception e
       (json/generate-string {:error (ex-message e)})))))

(defn compare-json
  "Parse and normalize two EDN strings, diff them into one union-graph
  JSON string. A parse failure returns {\"error\": \"<file>: msg\"}."
  [old-s new-s old-name new-name]
  (try
    (let [parse (fn [s nm]
                  (try (edn/read-string s)
                       (catch Exception e
                         (throw (ex-info (str nm ": " (ex-message e)) {})))))
          old-g (graph/normalize (parse old-s old-name))
          new-g (graph/normalize (parse new-s new-name))]
      (json/generate-string
       (assoc (diff/union old-g new-g old-name new-name)
              :file (.getName (io/file new-name)))))
    (catch Exception e
      (json/generate-string {:error (ex-message e)}))))

(def mime-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "json" "application/json"
   "svg"  "image/svg+xml"})

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

(defn handler [{:keys [uri]}]
  (case uri
    "/api/graph"   (json-response
                    (try
                      (let [{:keys [old new]} @files]
                        (if (some? old)
                          (compare-json (slurp old) (slurp new) old new)
                          (graph-json (slurp new) (.getName (io/file new)))))
                      (catch Exception e
                        (json/generate-string {:error (ex-message e)}))))
    "/api/version" (json-response
                    (json/generate-string
                     {:mtime (let [{:keys [old new]} @files
                                   m (.lastModified (io/file new))]
                               (if (some? old)
                                 (str (.lastModified (io/file old)) "-" m)
                                 m))}))
    (static-response uri)))

(defn -main [& args]
  (let [{:keys [file old-file port error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (doseq [f (if old-file [old-file file] [file])]
      (when-not (.isFile (io/file f))
        (println (str "file not found: " f))
        (System/exit 1)))
    (reset! files {:old old-file :new file})
    (srv/run-server handler {:port port})
    (println (str "simpleviz: serving "
                  (if old-file (str old-file " → " file " (compare)") file)
                  " at http://localhost:" port))
    @(promise)))
