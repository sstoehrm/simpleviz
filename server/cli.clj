(ns cli
  "The simpleviz command line, shared by every install. The install.sh
  launcher handles `update` and `clean-all` itself and execs this for
  everything else (`bb --config ~/.simpleviz/bb.edn -m cli ...`); the
  release jar runs it through simpleviz.main (bbin). Paths resolve from
  the working directory; the frontend, the examples and VERSION are
  classpath resources."
  (:require [babashka.fs :as fs]
            [browser]
            [check]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deck]
            [fork]
            [embedded]
            [log]
            [paths]
            [serve]
            [session]
            [themes]))

(def latest-jar-url
  "https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar")

(def shipped-templates
  "The files under templates/: the ones every install has. A test keeps
  this equal to the folder, since a jar cannot list a directory."
  ["default.edn" "presentation.md"])

(def usage
  (str/join
   "\n"
   ["usage: simpleviz <graph.edn|doc.md> [<suffix>] [--debug] [--no-open]"
    "                                         serve a graph; with a suffix, compare it"
    "                                         against its fork graph-<suffix>.edn (refs follow"
    "                                         into the same comparison of each referenced file)"
    "                                         exported PNGs and SVGs work in place of EDN files;"
    "                                         an .md file opens in the doc editor, its links to"
    "                                         graphs and md files followable"
    (str "                                         --debug logs edits and errors to " log/dir-hint)
    "                                         --no-open prints the URL without opening a browser"
    "       simpleviz demo [--debug] [--no-open]   copy the examples to a temp folder and serve"
    "                                         demo.edn compared with demo-next.edn"
    "       simpleviz servers                 list your running servers, a line each: the URL"
    "                                         and the file it serves"
    "       simpleviz fork <graph.edn> <suffix>     copy the graph and every file it refs to"
    "                                               <name>-<suffix>.edn siblings"
    "       simpleviz promote <graph.edn> <suffix>  move each fork over its original file"
    "       simpleviz init <graph.edn|doc.md> [-t|--template <name>]"
    "                                         write a starter file (won't overwrite) from"
    "                                         <name>.edn or <name>.md (default: default) in"
    "                                         ~/.simpleviz/templates (or $SIMPLEVIZ_HOME/templates);"
    "                                         -t presentation writes a deck and its graph"
    "       simpleviz extract <diagram.png|.svg> [out.edn] [--old]   print/extract the embedded EDN"
    "       simpleviz export <graph.edn> [<suffix>] <out.png|out.svg> [--theme <name>] [--force]"
    "                                         write the ⇩ export of the whole graph, made in a"
    "                                         headless Chrome/Chromium (SIMPLEVIZ_BROWSER overrides)"
    "       simpleviz export <talk.md> <out.pdf> [--theme <name>] [--force]"
    "                                         write its slides as a PDF, a 16:9 page each"
    "       simpleviz check <graph.edn|doc.md> print the parse error or validation warnings"
    "                                         the page would show, or an md file's broken"
    "                                         links and malformed questions; exit 1 if there"
    "                                         are any"
    "       simpleviz update [+ai]            install the latest release (install.sh launcher;"
    "                                         a bbin install prints the bbin command); +ai also"
    "                                         installs or updates the Claude Code, Codex and Grok"
    "                                         plugins"
    "       simpleviz clean-all               kill every running simpleviz server"
    "                                         (install.sh launcher on Linux only)"
    "       simpleviz --version               print the installed version"
    "Serves on a random free port between 7370 and 7469, below a secret token in the URL."]))

(defn- die
  "Print `simpleviz: <parts>` to stderr and exit 1."
  [& parts]
  (binding [*out* *err*] (println (apply str "simpleviz: " parts)))
  (System/exit 1))

(defn- usage-error []
  (binding [*out* *err*] (println usage))
  (System/exit 1))

(defn- reject-flag!
  "An argument starting with - that isn't a known flag is a mistyped one
  (--noopen); taken as a suffix, it would name a fork like
  graph---noopen.edn."
  [arg]
  (when (and (some? arg) (str/starts-with? arg "-"))
    (die "unknown option: " arg " (see simpleviz --help)")))

(defn- fork-cmd [cmd args]
  (when-not (= 2 (count args)) (usage-error))
  (let [[file suffix] args]
    (reject-flag! suffix)
    (when (paths/md-path? file) (die cmd " works on graph files (.edn, .png or .svg)"))
    (when-not (.isFile (io/file file)) (die "file not found: " file))
    (fork/-main cmd file suffix)))

(defn- init-args
  "{:file :template} from init's args, the -t/--template flag anywhere;
  nil when they don't fit."
  [args]
  (loop [args args, pos [], template "default"]
    (if-let [[a & more] (seq args)]
      (if (#{"-t" "--template"} a)
        (when (seq more) (recur (rest more) pos (first more)))
        (recur more (conj pos a) template))
      (when (= 1 (count pos)) {:file (first pos) :template template}))))

(defn- template-source
  "Template `name` of kind `ext` (\"edn\" or \"md\"):
  <home>/templates/<name>.<ext>, where users and tools put theirs, else
  the shipped one on the classpath; nil if neither."
  [name ext]
  (let [f (io/file (log/home) "templates" (str name "." ext))]
    (if (.isFile f) f (io/resource (str "templates/" name "." ext)))))

(defn- available-templates
  "The names of the templates of kind `ext`."
  [ext]
  (let [suffix (str "." ext)
        names (fn [files] (keep #(when (str/ends-with? % suffix)
                                   (subs % 0 (- (count %) (count suffix))))
                                files))]
    (->> (concat (names (map #(.getName %) (.listFiles (io/file (log/home) "templates"))))
                 (filter #(template-source % ext) (names shipped-templates)))
         distinct
         sort)))

(defn- write-file!
  "Write `text` to `f`, which must not be there — a link that is would
  take the write wherever it points."
  [f text]
  (try (io/make-parents f)
       (serve/write-new! f (.getBytes ^String text "UTF-8"))
       (catch java.nio.file.FileAlreadyExistsException _ (die f " already exists"))
       (catch java.io.IOException e (die "cannot write " f ": " (ex-message e)))))

(defn- shown-graphs
  "The .edn files the slides of md `text` show beside them, relative to
  its folder and staying below it: what a presentation template needs."
  [text]
  (->> (deck/slides text)
       (keep :side)
       (filter #(and (str/ends-with? (str/lower-case %) ".edn")
                     (not (.isAbsolute (io/file %)))
                     (not-any? #{".."} (str/split % #"[/\\]"))))
       distinct))

(defn- init-md!
  "Write md file `file` from template text `text`, {{name}} its own name,
  and each graph a slide shows that isn't there yet, from the default
  graph template; the files written."
  [file text]
  (let [f (io/file file)
        stem (str/replace (.getName f) #"(?i)\.md$" "")
        text (str/replace text "{{name}}" stem)
        graph-src (template-source "default" "edn")]
    (write-file! f text)
    (into [file]
          (keep (fn [rel]
                  (let [g (io/file (.getParentFile (.getAbsoluteFile f)) rel)]
                    (when (and (some? graph-src) (not (serve/there? g)))
                      (write-file! g (slurp graph-src))
                      (str (.normalize (.toPath (if-let [dir (.getParent f)] (io/file dir rel) (io/file rel)))))))))
          (shown-graphs text))))

(defn- init-cmd [args]
  (let [{:keys [file template]} (or (init-args args) (usage-error))
        ext (if (paths/md-path? file) "md" "edn")]
    (reject-flag! file)
    (when (re-find #"[/\\]" template)
      (die "template name must be a plain name, not a path: " template))
    (let [src (or (template-source template ext)
                  (die "unknown template: " template
                       " (available: " (str/join ", " (available-templates ext)) ")"))]
      (when (serve/there? file) (die file " already exists"))
      (let [written (if (= ext "md")
                      (init-md! file (slurp src))
                      (do (write-file! file (slurp src)) [file]))]
        (println (str "created " (str/join ", " written) " — view it with: simpleviz " file))))))

(def port-range (range 7370 7470))

(def example-files
  "The files under examples/, relative to it: what `demo` copies. A test
  keeps this equal to the folder, since a jar cannot list a directory."
  ["demo.edn" "demo-next.edn" "states.edn" "steps.edn" "big-5k.edn" "intro.md" "deck.md" "quiz.md" "tables.md" "charts.md" "sales.csv" "overview.png"
   "api/internals.edn" "api/internals-next.edn" "api/notes.md"])

(def ^:private flag? #{"--debug" "--no-open"})

(defn- split-flags
  "[positional args, set of flags]: --debug and --no-open may sit
  anywhere on the line."
  [args]
  [(vec (remove flag? args)) (set (filter flag? args))])

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

(defn- start!
  "Start on a free port; [port result]. A startup refusal (missing side,
  export without EDN) exits 1; any other startup failure gets a crash
  report, like serve/-main gives it, instead of a raw stack trace."
  [opts]
  (try (start-on-free-port! opts)
       (catch clojure.lang.ExceptionInfo e
         (if (:startup-check (ex-data e))
           (die (ex-message e))
           (do (log/crash! {:phase "startup"} e) (System/exit 1))))
       (catch Throwable e
         (log/crash! {:phase "startup"} e)
         (System/exit 1))))

(defn- open-browser!
  "Open `url` in the desktop browser through a launch file (its token
  stays out of the opener's arguments), deleted once the page loads —
  `loaded` is delivered — or the process exits."
  [url loaded]
  (try
    (let [{:keys [uri delete!]} (session/launch-file! url {})]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable delete!))
      (future @loaded (delete!))
      ;; like the old launcher's `xdg-open … || true`: no opener, no problem
      (try (browse/browse-url uri) (catch Exception _ nil)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "simpleviz: could not open a browser (" (or (ex-message e) (.getName (class e)))
                      ") — open the URL above"))))))

(defn- serve!
  "Start on a free port, list it for `simpleviz servers`, print the URL,
  open a browser unless `no-open`, and block."
  [opts no-open]
  (let [loaded (promise)
        [port {:keys [served url log-path]}] (start! (assoc opts :prefs true :on-page #(deliver loaded true)))]
    (serve/register! port url)
    (println (str "simpleviz: serving " served " at " url))
    (when log-path (println (str "simpleviz: debug log at " log-path)))
    (println (str "simpleviz: " url))
    (when-not no-open (open-browser! url loaded))
    @(promise)))

(defn- servers-cmd
  "Print each running server of yours, a line each: its URL and the file
  it serves."
  [args]
  (when (seq args) (usage-error))
  (doseq [{:keys [url file]} (session/servers {})]
    (println (str url " " file))))

(defn- check-input!
  "The checks `simpleviz <file> [<suffix>]` runs before serving; dies
  with their message."
  [file suffix]
  (when-not (.isFile (io/file file)) (die "file not found: " file))
  (when (some? suffix)
    (when (paths/md-path? file) (die "compare mode needs a graph file (.edn, .png or .svg)"))
    (when (re-find #"(?i)\.(edn|png|svg)$" suffix)
      (die "two-file compare was replaced: simpleviz fork " file
           " <suffix>, then simpleviz " file " <suffix>"))
    (when-not (re-matches serve/suffix-re suffix)
      (die "invalid suffix: " suffix))
    (when-not (.isFile (io/file (serve/fork-name file suffix)))
      (die (serve/missing-fork file suffix)))))

(defn- serve-cmd [args]
  (let [[[file suffix & extra] flags] (split-flags args)]
    (run! reject-flag! (cons suffix extra))
    (when (or (nil? file) (seq extra)) (usage-error))
    (check-input! file suffix)
    (serve! {:file file :suffix suffix :debug (contains? flags "--debug")}
            (contains? flags "--no-open"))))

(def export-usage
  (str "usage: simpleviz export <graph.edn|.png|.svg> [<suffix>] <out.png|out.svg> [--theme <name>] [--force]\n"
       "       simpleviz export <talk.md> <out.pdf> [--theme <name>] [--force]"))

(defn parse-export-args
  "export's arguments -> {:in :suffix :out :format :theme :force}, or
  {:error msg} for a usage problem. A graph exports as png or svg, an md
  file as the pdf of its slides."
  [args]
  (loop [[a & more] args, pos [], theme nil, force false]
    (cond
      (nil? a)
      (let [[in suffix out] (if (= 3 (count pos)) pos [(first pos) nil (second pos)])
            ext (some->> out (re-find #"(?i)\.(png|svg|pdf)$") second str/lower-case)
            names (map name themes/NAMES)]
        (cond (not (#{2 3} (count pos))) {:error export-usage}
              (nil? ext) {:error (str "the output must end in .png, .svg or .pdf: " out)}
              (and (paths/md-path? in) (nil? suffix) (not= ext "pdf"))
              {:error (str "an md file exports as the PDF of its slides: simpleviz export " in " "
                           (str/replace in #"(?i)\.md$" ".pdf"))}
              (and (not (paths/md-path? in)) (= ext "pdf"))
              {:error "a graph exports as .png or .svg — .pdf is for the slides of an md file"}
              (and (some? suffix) (not (re-matches serve/suffix-re suffix)))
              {:error (str "invalid suffix: " suffix)}
              (and (some? theme) (not (some #{theme} names)))
              {:error (str "unknown theme: " theme " (one of " (str/join ", " names) ")")}
              :else {:in in :suffix suffix :out out :format ext :theme theme :force force}))
      (= a "--force") (recur more pos theme true)
      (= a "--theme") (if (seq more) (recur (rest more) pos (first more) force) {:error export-usage})
      (str/starts-with? a "-") {:error (str "unknown option: " a " (see simpleviz --help)")}
      :else (recur more (conj pos a) theme force))))

(def export-timeout-ms 120000)

(defn- export-cmd [args]
  (let [{:keys [error in suffix out format theme force]} (parse-export-args args)]
    (when error (die error))
    (check-input! in suffix)
    (when (and (.exists (io/file out)) (not force))
      (die out " already exists (--force overwrites)"))
    (let [[_ {:keys [url]}] (start! {:file in :suffix suffix :debug false})
          {:keys [path] :as found} (browser/find-browser)
          _ (when-not path (die (:error found)))
          data (try (browser/with-browser path
                      (fn [conn _]
                        (browser/export-page! conn url
                                              {:format format :theme theme
                                               :timeout-ms export-timeout-ms})))
                    (catch clojure.lang.ExceptionInfo e (die (ex-message e)))
                    ;; anything else (a refused connection, a failed
                    ;; handshake) is still one line, not a stack trace
                    (catch Exception e (die (or (ex-message e) (.getName (class e))))))]
      (try (if (= format "svg")
             (spit out (:data data) :encoding "UTF-8")
             (io/copy (.decode (java.util.Base64/getDecoder) ^String (:data data)) (io/file out)))
           (catch java.io.IOException e (die "cannot write " out ": " (ex-message e))))
      (binding [*out* *err*]
        (doseq [[n why] (:problems data)] (println (str "simpleviz: slide " n ": " why))))
      (println (str "wrote " out (when (= format "pdf")
                                  (let [n (:slides data)] (str " (" n (if (= 1 n) " slide)" " slides)"))))))
      ;; http-kit's threads would keep the process alive
      (System/exit 0))))

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
      (try (copy-examples! dir)
           (catch clojure.lang.ExceptionInfo e
             (fs/delete-tree dir)
             (die (ex-message e) " — the install looks incomplete; reinstall simpleviz")))
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
      "extract" (apply embedded/-main more)
      "export" (export-cmd more)
      "check" (do (when-not (= 1 (count more)) (usage-error))
                  (check/-main (first more)))
      "demo" (demo-cmd more)
      "servers" (servers-cmd more)
      (if (and (str/starts-with? cmd "-") (not (flag? cmd)))
        (usage-error)
        (serve-cmd args)))))
