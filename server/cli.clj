(ns cli
  "The simpleviz command line, shared by every install. The install.sh
  launcher handles `update` and `clean-all` itself and execs this for
  everything else (`bb --config ~/.simpleviz/bb.edn -m cli ...`); the
  release jar runs it through simpleviz.main (bbin). Paths resolve from
  the working directory; the frontend, the examples and VERSION are
  classpath resources."
  (:require [check]
            [clojure.java.browse :as browse]
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

(def port-range (range 7370 7470))

(def example-files
  "The files under examples/, relative to it: what `demo` copies. A test
  keeps this equal to the folder, since a jar cannot list a directory."
  ["demo.edn" "demo-next.edn" "states.edn" "big-5k.edn"
   "api/internals.edn" "api/internals-next.edn"])

(defn- split-flags
  "[positional args, set of flags]: --debug and --no-open may sit
  anywhere on the line."
  [args]
  (let [flag? #{"--debug" "--no-open"}]
    [(vec (remove flag? args)) (set (filter flag? args))]))

(defn- bindable? [port]
  (try (with-open [_ (java.net.ServerSocket. port 0 (java.net.InetAddress/getByName "127.0.0.1"))]
         true)
       (catch java.io.IOException _ false)))

(defn- start-on-free-port!
  "serve/start! on a random free port of port-range; [port result]."
  [opts]
  (loop [ports (shuffle port-range)]
    (if-let [port (first ports)]
      (or (when (bindable? port)
            (try [port (serve/start! (assoc opts :port port))]
                 (catch java.net.BindException _ nil)))
          (recur (rest ports)))
      (die "no free port between 7370 and 7469"))))

(defn- serve!
  "Start on a free port, print the URL, open a browser unless `no-open`,
  and block. A startup refusal (missing side, PNG without EDN) exits 1;
  any other startup failure gets a crash report, like serve/-main gives
  it, instead of a raw stack trace."
  [opts no-open]
  (let [[port {:keys [served log-path]}]
        (try (start-on-free-port! opts)
             (catch clojure.lang.ExceptionInfo e
               (if (:startup-check (ex-data e))
                 (die (ex-message e))
                 (do (log/crash! {:phase "startup"} e) (System/exit 1))))
             (catch Throwable e
               (log/crash! {:phase "startup"} e)
               (System/exit 1)))
        url (str "http://localhost:" port)]
    (println (str "simpleviz: serving " served " at " url))
    (when log-path (println (str "simpleviz: debug log at " log-path)))
    (println (str "simpleviz: " url))
    (when-not no-open
      ;; like the old launcher's `xdg-open … || true`: no opener, no problem
      (try (browse/browse-url url) (catch Exception _ nil)))
    @(promise)))

(defn- serve-cmd [args]
  (let [[[file suffix & extra] flags] (split-flags args)]
    (when (or (nil? file) (seq extra)) (usage-error))
    (when-not (.isFile (io/file file)) (die "file not found: " file))
    (when (some? suffix)
      (when (re-find #"(?i)\.(edn|png)$" suffix)
        (die "two-file compare was replaced: simpleviz fork " file
             " <suffix>, then simpleviz " file " <suffix>"))
      (when-not (re-matches serve/suffix-re suffix)
        (die "invalid suffix: " suffix))
      (let [fk (serve/fork-name file suffix)]
        (when-not (.isFile (io/file fk))
          (die fk " not found — create it with: simpleviz fork " file " " suffix))))
    (serve! {:file file :suffix suffix :debug (contains? flags "--debug")}
            (contains? flags "--no-open"))))

(defn copy-examples!
  "Copy example-files from the classpath into `dir`, keeping subfolders."
  [dir]
  (doseq [rel example-files]
    (let [res (or (io/resource (str "examples/" rel))
                  (throw (ex-info (str "example missing from the classpath: " rel) {})))
          target (io/file dir rel)]
      (io/make-parents target)
      (with-open [in (io/input-stream res)] (io/copy in target)))))

(defn- demo-cmd [args]
  (let [[positional flags] (split-flags args)]
    (when (seq positional) (usage-error))
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "simpleviz-demo-" (make-array java.nio.file.attribute.FileAttribute 0)))]
      (copy-examples! dir)
      (println (str "simpleviz: demo files in " dir))
      (serve! {:file (str (io/file dir "demo.edn")) :suffix "next" :debug (contains? flags "--debug")}
              (contains? flags "--no-open")))))

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
      "demo" (demo-cmd more)
      (if (and (str/starts-with? cmd "-") (not (#{"--debug" "--no-open"} cmd)))
        (usage-error)
        (serve-cmd args)))))
