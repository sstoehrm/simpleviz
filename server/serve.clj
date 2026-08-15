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

(defn- edit-response [{:keys [old new]} body-stream]
  (try
    (let [{:keys [file ops]} (json/parse-string (slurp body-stream) true)
          path (if (= file "old") old new)]
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
  (str "usage: bb serve <graph.edn|export.png> [<new.edn|new.png>] [--port N]\n"
       "  pass two files to compare them: first = old, second = new\n"
       "  a PNG exported from simpleviz serves its embedded EDN;\n"
       "  a compare-mode export re-opens as the comparison"
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

(defn handler [{:keys [uri query-string request-method body]}]
  (case uri
    "/api/graph"   (json-response
                    (try
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
                        (json/generate-string {:error (ex-message e)}))))
    "/api/edit"    (if (= :post request-method)
                     (json-response (json/generate-string (edit-response @files body)))
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

(defn -main [& args]
  (let [{:keys [file old-file port error]} (parse-args args)]
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
    (srv/run-server handler {:port port})
    (println (str "simpleviz: serving "
                  (cond
                    old-file (str old-file " → " file " (compare)")
                    (some? (embedded-old file)) (str file " (embedded compare)")
                    :else file)
                  " at http://localhost:" port))
    @(promise)))
