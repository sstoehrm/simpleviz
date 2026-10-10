(ns check
  "simpleviz check: what the page's banners would show for one graph
  file — its parse error or its validation warnings, pairs included —
  without a server, or one md file's broken links. The file's folder counts as the served folder, as
  `simpleviz <file>` would serve it; incoming pairs aren't looked for."
  (:require [clojure.java.io :as io]
            [pairs]
            [serve]))

(defn check
  "{:error <message or nil> :warnings [...]} for the graph file at
  `path` (EDN, or the EDN embedded in an exported PNG or SVG), or for
  the md file at `path`: its read error or broken links."
  [path]
  (try
    (let [f (.getCanonicalFile (io/file path))]
      (if (serve/md-path? path)
        (let [{:keys [text exists]} (serve/doc-state f)]
          (if exists
            {:error nil :warnings (serve/md-warnings (.getParentFile f) (.getName f) text)}
            {:error (str "no such file: " path) :warnings []}))
        (let [g (serve/parse-graph (serve/read-source path))]
          {:error nil
           :warnings (:warnings (pairs/attach g (serve/pair-context (.getParentFile f) (.getName f) nil {})))})))
    (catch Exception e
      {:error (ex-message e) :warnings []})))

(defn -main
  "bb check <graph.edn|.png|.svg|doc.md>: prints `error: ..` or one `warning: ..`
  line per warning and exits 1, or prints `ok`."
  [& [file & extra]]
  (when (or (nil? file) (seq extra))
    (binding [*out* *err*] (println "usage: bb check <graph.edn|.png|.svg|doc.md>"))
    (System/exit 1))
  (let [{:keys [error warnings]} (check file)]
    (if (or error (seq warnings))
      (do (when error (println (str "error: " error)))
          (doseq [w warnings] (println (str "warning: " w)))
          (System/exit 1))
      (println "ok"))))
