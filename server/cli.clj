(ns cli
  "The simpleviz command line, shared by every install. The install.sh
  launcher handles `update` and `clean-all` itself and execs this for
  everything else (`bb --config ~/.simpleviz/bb.edn -m cli ...`); the
  release jar runs it directly (bbin). Paths resolve from the working
  directory; the frontend, the examples and VERSION are classpath
  resources."
  (:require [check]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fork]
            [log]
            [png]
            [serve]))

(def latest-jar-url
  "https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar")

(def init-template
  "What `simpleviz init` writes."
  (str "{:nodes {:web {:name \"Web\" :type \"frontend\"}\n"
       "         :api {:name \"API\" :type \"service\"}}\n"
       " :edges {[:web :api] {:direction :-> :name \"calls\" :type \"http\"}}\n"
       " :boxes {:backend {:type \"zone\" :components #{:api}}}}\n"))

(def usage
  (str/join
   "\n"
   ["usage: simpleviz <graph.edn> [<suffix>] [--debug] [--no-open]"
    "                                         serve a graph; with a suffix, compare it"
    "                                         against its fork graph-<suffix>.edn (refs follow"
    "                                         into the same comparison of each referenced file)"
    "                                         exported PNGs work in place of EDN files"
    (str "                                         --debug logs edits and errors to " log/dir-hint)
    "                                         --no-open prints the URL without opening a browser"
    "       simpleviz demo [--debug] [--no-open]   copy the examples to a temp folder and serve"
    "                                         demo.edn compared with demo-next.edn"
    "       simpleviz fork <graph.edn> <suffix>     copy the graph and every file it refs to"
    "                                               <name>-<suffix>.edn siblings"
    "       simpleviz promote <graph.edn> <suffix>  move each fork over its original file"
    "       simpleviz init <graph.edn>        write a starter graph file (won't overwrite)"
    "       simpleviz extract <diagram.png> [out.edn] [--old]   print/extract the embedded EDN"
    "       simpleviz check <graph.edn>       print the parse error or validation warnings"
    "                                         the page would show; exit 1 if there are any"
    "       simpleviz update                  install the latest release (install.sh launcher;"
    "                                         a bbin install prints the bbin command)"
    "       simpleviz clean-all               kill every running simpleviz server"
    "                                         (install.sh launcher on Linux only)"
    "       simpleviz --version               print the installed version"
    "Serves on a random free port between 7370 and 7469."]))

(defn- die
  "Print `simpleviz: <parts>` to stderr and exit 1."
  [& parts]
  (binding [*out* *err*] (println (apply str "simpleviz: " parts)))
  (System/exit 1))

(defn- usage-error []
  (binding [*out* *err*] (println usage))
  (System/exit 1))

(defn- fork-cmd [cmd args]
  (when-not (= 2 (count args)) (usage-error))
  (let [[file suffix] args]
    (when-not (.isFile (io/file file)) (die "file not found: " file))
    (fork/-main cmd file suffix)))

(defn- init-cmd [args]
  (when-not (= 1 (count args)) (usage-error))
  (let [f (first args)]
    (when (.exists (io/file f)) (die f " already exists"))
    (try (spit f init-template)
         (catch java.io.IOException e (die "cannot write " f ": " (ex-message e))))
    (println (str "created " f " — view it with: simpleviz " f))))

(defn -main [& args]
  (let [[cmd & more] args]
    (case cmd
      (nil "-h" "--help") (println usage)
      ("--version" "version") (println (str "simpleviz " (serve/version)))
      "update" (println (str "simpleviz was installed with bbin; update it with: bbin install "
                             latest-jar-url))
      "clean-all" (die "clean-all needs the install.sh launcher (Linux)")
      ("fork" "promote") (fork-cmd cmd more)
      "init" (init-cmd more)
      "extract" (apply png/-main more)
      "check" (apply check/-main more)
      (usage-error))))
