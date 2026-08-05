(ns serve
  "Static file server + EDN->JSON API. Parses and normalizes the graph
  (shape checks, semantics) server-side via graph/normalize; the browser
  just renders the resulting JSON."
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [graph]
            [org.httpkit.server :as srv]))

(def default-port 7373)

(def edn-file (atom nil))

(def ^:private usage
  (str "usage: bb serve <graph.edn> [--port N]  (default port " default-port ")"))

(def cli-spec {:alias {:p :port} :coerce {:port :long}})

(defn parse-args
  "CLI args -> {:file f :port n} or {:error msg}. The graph file is
  positional; --port / -p overrides the default."
  [args]
  (try
    (let [{:keys [args opts]} (cli/parse-args args cli-spec)
          file (first args)
          port (get opts :port default-port)]
      (cond
        (nil? file) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        :else {:file file :port port}))
    (catch Exception e
      {:error (str "invalid arguments: " (ex-message e) "\n" usage)})))

(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  Parse failures return {\"error\": message} instead of throwing."
  [s]
  (try
    (json/generate-string (graph/normalize (edn/read-string s)))
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
                    (try (graph-json (slurp @edn-file))
                         (catch Exception e
                           (json/generate-string {:error (ex-message e)}))))
    "/api/version" (json-response (json/generate-string
                                   {:mtime (.lastModified (io/file @edn-file))}))
    (static-response uri)))

(defn -main [& args]
  (let [{:keys [file port error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (when-not (.isFile (io/file file))
      (println (str "file not found: " file))
      (System/exit 1))
    (reset! edn-file file)
    (srv/run-server handler {:port port})
    (println (str "simpleviz: serving " file " at http://localhost:" port))
    @(promise)))
