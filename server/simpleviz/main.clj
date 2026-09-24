(ns simpleviz.main
  "The release jar's entry point (its Main-Class, the namespace bbin's
  shim requires). The shim adds the jar to the classpath at run time,
  after bb has loaded any bb.edn in the caller's folder, whose :paths
  come first: its VERSION, public/ and namespaces (cli, serve, ...)
  would win over the jar's. So when a bb.edn was loaded, this re-runs the
  CLI as `bb -cp <jar> -m cli ...`, which ignores a local bb.edn; else it
  runs the CLI in place. `cli` is required only on that path, so a
  shadowing `cli` never loads."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn jar-path
  "The path of the jar that a `jar:file:<path>!/<entry>` resource URL
  points into, nil for any other URL. bb leaves the path as is; a
  percent-encoded one (the JVM's form) is decoded."
  [url]
  (let [s (str url)
        bang (str/index-of s "!/")]
    (when (and (str/starts-with? s "jar:file:") bang)
      (let [file-url (subs s (count "jar:") bang)]
        (try (.getPath (java.io.File. (java.net.URI. file-url)))
             (catch java.net.URISyntaxException _
               (.getPath (java.net.URL. file-url))))))))

(defn- run-cli [args]
  (apply (requiring-resolve 'cli/-main) args))

(defn -main [& args]
  (if-not (System/getProperty "babashka.config")
    (run-cli args)
    (if-let [jar (jar-path (io/resource "simpleviz/main.clj"))]
      (let [cmd (into ["bb" "-cp" jar "-m" "cli"] args)]
        (try (p/exec {:cmd cmd})
             ;; exec replaces this process where the platform allows it
             ;; (not on Windows); elsewhere run the CLI as a child
             (catch Exception _
               (System/exit (:exit @(p/process {:cmd cmd :inherit true}))))))
      ;; a checkout: the CLI is right here
      (run-cli args))))
