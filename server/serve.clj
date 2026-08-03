(ns serve
  "Dumb static + EDN->JSON server. All graph logic lives in the browser."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as srv]))

(def edn-file (atom nil))

(defn edn->json
  "Parse an EDN string into a JSON string. Keywords become their names
  (:-> becomes \"->\"), sets become arrays. Parse failures return
  {\"error\": message} instead of throwing."
  [s]
  (try
    (json/generate-string (edn/read-string s))
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
                      (edn->json (slurp @edn-file))
                      (catch Exception e
                        (json/generate-string {:error (ex-message e)}))))
    "/api/version" (json-response (json/generate-string
                                   {:mtime (.lastModified (io/file @edn-file))}))
    (static-response uri)))

(defn -main [& args]
  (let [file (first args)]
    (when (or (nil? file) (not (.isFile (io/file file))))
      (println "usage: bb serve <graph.edn>")
      (System/exit 1))
    (reset! edn-file file)
    (srv/run-server handler {:port 8080})
    (println (str "simpleviz: serving " file " at http://localhost:8080"))
    @(promise)))
