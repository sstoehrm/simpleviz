(ns check
  "simpleviz check: what the page's banners would show for one graph
  file — its parse error or its validation warnings — without a server."
  (:require [serve]))

(defn check
  "{:error <message or nil> :warnings [...]} for the graph file at
  `path` (EDN, or the EDN embedded in an exported PNG)."
  [path]
  (try
    {:error nil
     :warnings (:warnings (serve/parse-graph (serve/read-source path)))}
    (catch Exception e
      {:error (ex-message e) :warnings []})))

(defn -main
  "bb check <graph.edn|.png>: prints `error: ..` or one `warning: ..`
  line per warning and exits 1, or prints `ok`."
  [& [file & extra]]
  (when (or (nil? file) (seq extra))
    (binding [*out* *err*] (println "usage: bb check <graph.edn|.png>"))
    (System/exit 1))
  (let [{:keys [error warnings]} (check file)]
    (if (or error (seq warnings))
      (do (when error (println (str "error: " error)))
          (doseq [w warnings] (println (str "warning: " w)))
          (System/exit 1))
      (println "ok"))))
