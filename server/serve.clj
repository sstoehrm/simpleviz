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
            [log]
            [org.httpkit.server :as srv]
            [pairs]
            [png])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(def default-port 7373)

(def files (atom nil)) ; {:root <path> :suffix <s-or-nil>}

(def undo-stacks (atom {})) ; path -> [text ...] newest last, capped

(def root-dir (atom nil)) ; canonical File of the served folder (single-file mode)

(def locks (atom {})) ; canonical path -> {:owner s :expires ms}

(def lock-ttl-ms 60000)

(def ^:private ref-extensions #{"edn" "png"})

(def suffix-re
  "A fork suffix: graph-<suffix>.edn. It can't start with -, which would
  read as a mistyped flag."
  #"^[A-Za-z0-9_.][A-Za-z0-9_.-]*$")

(def empty-graph
  "What a file created by the server holds: map form, so it is editable."
  "{:nodes {} :edges {} :boxes {}}\n")

(defn fork-name
  "The fork of path `rel` for `suffix`: the suffix goes before the
  extension (\"a/b.edn\" \"next\" -> \"a/b-next.edn\"); a name without
  an extension gets \"-suffix\" appended."
  [rel suffix]
  (let [dot (str/last-index-of rel ".")
        slash (or (str/last-index-of rel "/") -1)]
    (if (and (some? dot) (> dot slash))
      (str (subs rel 0 dot) "-" suffix (subs rel dot))
      (str rel "-" suffix))))

(defn fork-base
  "The path `rel` is the fork of under `suffix` (\"a/b-next.edn\"
  \"next\" -> \"a/b.edn\"), or nil when it carries no such suffix."
  [rel suffix]
  (let [dot (str/last-index-of rel ".")
        slash (or (str/last-index-of rel "/") -1)
        [stem ext] (if (and (some? dot) (> dot slash))
                     [(subs rel 0 dot) (subs rel dot)]
                     [rel ""])
        tail (str "-" suffix)]
    (when (and (str/ends-with? stem tail) (> (count stem) (count tail)))
      (str (subs stem 0 (- (count stem) (count tail))) ext))))

(defn resolve-path
  "The canonical file for the root-relative path `rel` under `root`.
  Refuses (ex-info, message names the problem) an absolute path, a
  result outside `root` after canonicalization (so `..` and symlinks
  cannot escape), an extension other than .edn/.png, and — unless
  must-exist? is false — anything that is not an existing regular file."
  ([root rel] (resolve-path root rel true))
  ([root rel must-exist?]
   (let [rel (str rel)]
     (when (.isAbsolute (io/file rel))
       (throw (ex-info (str "absolute path refused: " rel) {})))
     (let [root-c (.getCanonicalFile (io/file root))
           f (.getCanonicalFile (io/file root-c rel))
           nm (.getName f)
           dot (str/last-index-of nm ".")
           ext (when (some? dot) (str/lower-case (subs nm (inc dot))))]
       (when-not (str/starts-with? (.getPath f) (str (.getPath root-c) java.io.File/separator))
         (throw (ex-info (str rel " leaves the served folder") {})))
       (when-not (contains? ref-extensions ext)
         (throw (ex-info (str rel " is not an .edn or .png file") {})))
       ;; canonicalization resolves every link but one whose target is
       ;; missing, and a write through that one lands wherever it points
       (when (java.nio.file.Files/isSymbolicLink (.toPath f))
         (throw (ex-info (str rel " is a dangling symlink") {})))
       (when (and must-exist? (not (.isFile f)))
         (throw (ex-info (str "no such file: " rel) {})))
       f))))

(defn- push-undo! [path text]
  (swap! undo-stacks update path (fn [st] (vec (take-last 100 (conj (or st []) text))))))

(defn- pop-undo! [path]
  (when-let [top (peek (get @undo-stacks path))]
    (swap! undo-stacks update path pop)
    top))

(defn- lock-holder
  "The owner of the live lock on `path` at `now`, or nil."
  [path now]
  (let [{:keys [owner expires]} (get @locks path)]
    (when (and (some? owner) (< now expires)) owner)))

(defn acquire-lock!
  "Take or renew the advisory write lock on `path` for `owner` at `now`
  (ms): granted when the file is free, the lock expired, or `owner`
  already holds it. One swap! decides, so two racing requests cannot
  both win."
  [path owner now]
  (let [after (swap! locks
                     (fn [m]
                       (let [{held :owner :keys [expires]} (get m path)]
                         (if (and (some? held) (< now expires) (not= held owner))
                           m
                           (assoc m path {:owner owner :expires (+ now lock-ttl-ms)})))))
        holder (:owner (get after path))]
    (if (= holder owner)
      {:ok true :ttl (quot lock-ttl-ms 1000)}
      {:error (str "locked by " holder) :owner holder})))

(defn release-lock!
  "Drop `owner`'s lock on `path`; releasing a free file is fine, someone
  else's live lock is refused."
  [path owner now]
  (let [after (swap! locks
                     (fn [m]
                       (let [{held :owner :keys [expires]} (get m path)]
                         (if (and (some? held) (< now expires) (not= held owner))
                           m
                           (dissoc m path)))))]
    (if-let [holder (:owner (get after path))]
      {:error (str "locked by " holder) :owner holder}
      {:ok true})))

(defn read-source
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

(defn- query-param
  "The URL-decoded value of parameter k in a query string, or nil."
  [query-string k]
  (when (some? query-string)
    (some (fn [kv]
            (let [[name v] (str/split kv #"=" 2)]
              (when (= name k)
                (java.net.URLDecoder/decode (or v "") "UTF-8"))))
          (str/split query-string #"&"))))

(defn- embedded-compare?
  "Is the root a compare-export PNG (both sides embedded, no suffix)?"
  []
  (let [{:keys [root suffix]} @files]
    (and (nil? suffix) (some? (embedded-old root)))))

(defn- root-rel
  "The root file's name, root-relative: its canonical basename, so a
  symlinked root (bb serve ~/link.edn -> elsewhere/g.edn) resolves under
  the real name rather than the raw basename of the served path."
  []
  (.getName (.getCanonicalFile (io/file (:root @files)))))

(defn sides
  "The files behind the root-relative path `rel` (nil = the root file)
  as {:old <canonical File or nil> :new <canonical File>}: without a
  suffix only :new; with one, :old is the file and :new its fork.
  Below the root one side of a pair may be missing (see side-file);
  the root itself needs both. Throws (message for the error payload)
  when resolve-path refuses either side or nothing is there."
  [rel]
  (let [{:keys [suffix]} @files
        rel (or rel (root-rel))
        root-c @root-dir]
    (if (nil? suffix)
      {:old nil :new (resolve-path root-c rel)}
      (let [fk (fork-name rel suffix)
            _ (when-let [base (fork-base rel suffix)]
                (throw (ex-info (str rel " is the fork of " base " — ref the original") {})))
            ;; escape/extension refusals first, existence checked here so
            ;; the message can name the missing side
            old (resolve-path root-c rel false)
            new (resolve-path root-c fk false)]
        (cond
          (and (.isFile old) (.isFile new)) {:old old :new new}
          (not= old (resolve-path root-c (root-rel) false))
          (if (or (.isFile old) (.isFile new))
            {:old old :new new}
            (throw (ex-info (str "no such file: " rel) {})))
          (not (.isFile new))
          (throw (ex-info (str "no " fk " — create it with: simpleviz fork " rel " " suffix) {}))
          :else
          (throw (ex-info (str "no " rel " (only " fk ")") {})))))))

(defn- side-file
  "The file holding what side `which` (\"old\", else new) of `sides`
  shows, or nil for an empty graph: a missing fork reads as its
  original — unforked means unchanged, which is also what promote
  makes of it — and a missing original as empty."
  [{:keys [old new]} which]
  (cond
    (= which "old") (when (and (some? old) (.isFile old)) old)
    (.isFile new) new
    (and (some? old) (.isFile old)) old))

(defn- read-only-side?
  "Is side `which` a PNG — by content, or by name when nothing is there
  yet, so an edit cannot bring a .png holding EDN text into being."
  [{:keys [old new] :as sides} which]
  (let [target (if (= which "old") old new)]
    (boolean
     (or (some-> (side-file sides which) .getPath png/png?)
         (and (not (.isFile target))
              (str/ends-with? (str/lower-case (.getName target)) ".png"))))))

(defn- side-source [sides which]
  (if-let [f (side-file sides which)] (read-source (.getPath f)) empty-graph))

(defn- nav-rel
  "The `file` query parameter, or nil; refused (throws) on an embedded
  compare, which has no folder to navigate."
  [query-string]
  (when-let [rel (query-param query-string "file")]
    (when (embedded-compare?)
      (throw (ex-info "refs are not available in an embedded compare" {})))
    rel))

(defn- edit-response [{:keys [file ops path]}]
  (try
    (when (and (some? path) (embedded-compare?))
      (throw (ex-info "refs are not available in an embedded compare" {})))
    (let [{:keys [old new] :as pair} (sides path)
          target (if (= file "old") old new)
          path (some-> target .getPath)
          ;; a side that is not there yet comes into being with its
          ;; first successful edit
          source (when (some? target) (side-file pair file))
          ;; the browser owns no lock, so any live one blocks it
          holder (some-> path (lock-holder (System/currentTimeMillis)))]
      (cond
        (nil? path) {:error "no old file in single-file mode"}
        (read-only-side? pair file) {:error "PNG sources are read-only"}
        (some? holder) {:error (str "locked by " holder)}
        (= "undo" (:op (first ops)))
        ;; a file deleted since (promote, by hand) stays deleted
        (if-let [prev (when (.isFile target) (pop-undo! path))]
          (do (spit path prev) {:ok true})
          {:error "nothing to undo"})
        :else
        ;; snapshot the ORIGINAL text before applying — `before` feeds
        ;; both the patch and the undo stack
        (let [before (if (some? source) (slurp source) empty-graph)
              {:keys [text error]} (edit/apply-ops before ops)]
          (if (some? error)
            {:error error}
            (do (push-undo! path before)
                (spit path text)
                {:ok true})))))
    (catch Exception e {:error (ex-message e)})))

(defn- create-response
  "Create the graph file a followed ref names, with its folders, when
  nothing is there yet: in a comparison the side being edited (`file`),
  and only while neither side exists — an empty fork next to an
  original would show everything as removed. Never overwrites."
  [{:keys [file path]}]
  (try
    (when (embedded-compare?)
      (throw (ex-info "refs are not available in an embedded compare" {})))
    (when-not (string? path)
      (throw (ex-info "path required" {})))
    (let [{:keys [suffix]} @files
          _ (when-let [b (and (some? suffix) (fork-base path suffix))]
              (throw (ex-info (str path " is the fork of " b " — ref the original") {})))
          base (resolve-path @root-dir path false)
          fk (when (some? suffix) (fork-name path suffix))
          fork (when (some? fk) (resolve-path @root-dir fk false))
          [rel target] (if (and (some? fork) (not= file "old")) [fk fork] [path base])]
      (cond
        (str/ends-with? (str/lower-case (.getName base)) ".png")
        {:error "PNG files cannot be created"}
        (or (.exists base) (and (some? fork) (.exists fork)))
        {:ok true :created nil}
        :else
        (do (.mkdirs (.getParentFile target))
            (try
              ;; CREATE_NEW: a file that appeared since the check is kept
              (java.nio.file.Files/write (.toPath target) (.getBytes empty-graph "UTF-8")
                                         (into-array java.nio.file.OpenOption
                                                     [java.nio.file.StandardOpenOption/CREATE_NEW]))
              {:ok true :created rel}
              (catch java.nio.file.FileAlreadyExistsException _
                {:ok true :created nil})))))
    (catch Exception e {:error (ex-message e)})))

(def ^:private usage
  (str "usage: bb serve <graph.edn|export.png> [<suffix>] [--port N] [--debug]\n"
       "  with a suffix, graph.edn is compared against its fork graph-<suffix>.edn\n"
       "  (create the fork with: bb fork graph.edn <suffix>); refs follow into\n"
       "  the same comparison of the referenced file and its fork\n"
       "  a PNG exported from simpleviz serves its embedded EDN;\n"
       "  a compare-mode export re-opens as the comparison\n"
       "  --debug writes a per-run log of edits and errors to " log/dir-hint
       "\n  (default port " default-port ")"))

(def cli-spec {:alias {:p :port} :coerce {:port :long :debug :boolean}})

(defn parse-args
  "CLI args -> {:file f :suffix s-or-nil :port n :debug b} or {:error msg}.
  Graph files are positional; --port / -p overrides the default; --debug
  turns on the run log."
  [args]
  (try
    ;; babashka.cli reads options positionally (a positional after an
    ;; option ends option parsing), so the bare flag is picked off first
    ;; and may sit anywhere on the line
    (let [debug (boolean (some #{"--debug"} args))
          {:keys [args opts]} (cli/parse-args (remove #{"--debug"} args) cli-spec)
          [f1 suffix & extra] args
          suffix (some-> suffix str)
          port (get opts :port default-port)]
      (cond
        (nil? f1) {:error usage}
        (seq extra) {:error usage}
        (not (and (int? port) (<= 1 port 65535))) {:error (str "invalid port: " port)}
        (and (some? suffix) (re-find #"(?i)\.(edn|png)$" suffix))
        {:error (str "two-file compare was replaced: bb fork " f1 " <suffix>, then bb serve " f1 " <suffix>\n" usage)}
        (and (some? suffix) (nil? (re-matches suffix-re suffix)))
        {:error (str "invalid suffix: " suffix "\n" usage)}
        :else {:file f1 :suffix suffix :port port :debug debug}))
    (catch Exception e
      {:error (str "invalid arguments: " (ex-message e) "\n" usage)})))

(defn parse-graph
  "Read the EDN text of a graph file and normalize it. Throws on a parse
  error, and when anything but whitespace, commas, comments and #_
  discards follows a root map: edn/read-string ignores it, so an extra }
  would hide the rest of the file without a word (#92). The error names
  the line the map closes on, where that } sits."
  [s]
  ;; LineNumberingPushbackReader counts lines (and folds \r\n to \n,
  ;; also inside strings); it wraps reader errors in a ReaderException,
  ;; so rethrow the plain message
  (let [r (LineNumberingPushbackReader. (StringReader. s))
        root (try (edn/read {:eof nil} r)
                  (catch Exception e (throw (ex-info (ex-message (or (ex-cause e) e)) {} e))))]
    ;; a root that isn't a map gets normalize's own warning instead
    (when (map? root)
      (let [line (.getLineNumber r)]
        (when-not (= ::eof (try (edn/read {:eof ::eof} r) (catch Exception _ ::stray)))
          (throw (ex-info (str "content after the end of the graph: its map closes at line "
                               line " — check there for an extra }")
                          {})))))
    (graph/normalize root)))

(def ^:private graph-cache
  "canonical path -> {:mtime ms :graph g} or {:mtime ms :error msg}: the
  files pairs read, parsed once per modification."
  (atom {}))

(defn- cached-graph
  "The normalized graph in canonical file f (`rel` names it in
  messages), parsed once per mtime; throws \"<rel> does not parse\"."
  [f rel]
  (let [k (.getPath f)
        mtime (.lastModified f)
        hit (get @graph-cache k)
        entry (if (= mtime (:mtime hit))
                hit
                (let [e (try {:mtime mtime :graph (parse-graph (read-source k))}
                             (catch Exception _ {:mtime mtime :error (str rel " does not parse")}))]
                  (swap! graph-cache assoc k e)
                  e))]
    (if-let [err (:error entry)]
      (throw (ex-info err {}))
      (:graph entry))))

(defn- read-rel-graph
  "The normalized graph of root-relative `rel` under folder `root`, for
  pairs: throws \"<rel> not found\", resolve-path's refusal, or
  \"<rel> does not parse\"."
  [root rel]
  (let [f (try (resolve-path root rel)
               (catch Exception e
                 (throw (ex-info (if (str/starts-with? (str (ex-message e)) "no such file")
                                   (str rel " not found")
                                   (ex-message e))
                                 {}))))]
    (cached-graph f rel)))

(defn- scan-edn
  "Root-relative paths of the .edn files under folder `root`: not in a
  dot-folder or node_modules, not a dotfile, and not a fork of `suffix`."
  [root suffix]
  (let [root-p (.toPath (io/file root))]
    (->> (file-seq (io/file root))
         (filter (fn [f] (and (.isFile f) (str/ends-with? (str/lower-case (.getName f)) ".edn"))))
         (map (fn [f] (str/replace (str (.relativize root-p (.toPath f))) java.io.File/separator "/")))
         (remove (fn [rel] (some (fn [seg] (or (str/starts-with? seg ".") (= seg "node_modules")))
                                 (str/split rel #"/"))))
         (remove (fn [rel] (and (some? suffix) (some? (fork-base rel suffix)))))
         sort)))

(defn pair-context
  "The pairs/attach ctx for root-relative `rel` under canonical folder
  `root`: reads through the mtime cache, knows `suffix`'s forks, and —
  with scan? — the reverse index of every .edn under root (else an
  empty one, as check has it)."
  [root rel suffix scan?]
  (let [read (fn [r] (read-rel-graph root r))]
    {:path rel
     :read read
     :fork-of (fn [r] (when (some? suffix) (fork-base r suffix)))
     :index (if scan?
              (pairs/index (keep (fn [r] (try [r (read r)] (catch Exception _ nil)))
                                 (scan-edn root suffix)))
              {})}))

(defn graph-json
  "Parse an EDN string, normalize it, return the graph as a JSON string.
  With fname, the payload carries it as :file (the export download
  name). extra-map, when given, is merged into the payload (e.g. the
  :editable flag); prepare, when given, transforms the normalized graph
  first (pairs/attach). Parse failures return {\"error\": message}
  instead of throwing."
  ([s] (graph-json s nil))
  ([s fname] (graph-json s fname nil))
  ([s fname extra-map] (graph-json s fname extra-map identity))
  ([s fname extra-map prepare]
   (try
     (json/generate-string
      (cond-> (prepare (parse-graph s))
        (some? fname) (assoc :file fname)
        (some? extra-map) (merge extra-map)))
     (catch Exception e
       (json/generate-string {:error (ex-message e)})))))

(defn compare-json
  "Parse and normalize two EDN strings, diff them into one union-graph
  JSON string; file-name overrides the export download name (defaults to
  new-name's basename). A parse failure returns {\"error\": \"<file>: msg\"}.
  prepare, when given, transforms each normalized graph before the diff
  (pairs/attach)."
  ([old-s new-s old-name new-name]
   (compare-json old-s new-s old-name new-name
                 (.getName (io/file new-name)) nil))
  ([old-s new-s old-name new-name file-name]
   (compare-json old-s new-s old-name new-name file-name nil))
  ([old-s new-s old-name new-name file-name extra-map]
   (compare-json old-s new-s old-name new-name file-name extra-map identity))
  ([old-s new-s old-name new-name file-name extra-map prepare]
   (try
     (let [parse (fn [s nm]
                   (try (parse-graph s)
                        (catch Exception e
                          (throw (ex-info (str nm ": " (ex-message e)) {})))))
           old-g (prepare (parse old-s old-name))
           new-g (prepare (parse new-s new-name))]
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

(defn- local-origin?
  "true when origin is absent (non-browser clients like curl send no
  Origin header) or is exactly this server's own http://localhost:<port>
  or http://127.0.0.1:<port> — same-origin browser fetches send Origin on
  POST even though they omit it on GET."
  [origin port]
  (or (nil? origin)
      (contains? #{(str "http://localhost:" port) (str "http://127.0.0.1:" port)} origin)))

(defn- json-content-type? [ct]
  (and (some? ct) (str/starts-with? (str/lower-case ct) "application/json")))

(defn- edit-guard
  "HTTP-level rejection response for a write (/api/edit, /api/create,
  /api/lock, /api/unlock), or nil when the
  request may proceed: 403 on a foreign Origin (cross-origin write
  attempt), 415 when Content-Type isn't application/json."
  [{:keys [headers server-port]}]
  (cond
    (not (local-origin? (get headers "origin") server-port))
    {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "forbidden"}
    (not (json-content-type? (get headers "content-type")))
    {:status 415 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "unsupported media type"}
    :else nil))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-store"}
   :body body})

(defn- static-resource
  "The classpath resource behind a request path under public/, or nil for
  a path containing `..`, a directory, or nothing there."
  [path]
  (when-not (str/includes? path "..")
    (when-let [url (io/resource (str "public" path))]
      (case (.getProtocol url)
        "file" (when (.isFile (io/file url)) url)
        ;; bb names a folder in a jar with or without the trailing slash,
        ;; as asked; only a folder answers the name with a slash
        "jar" (when-not (or (str/ends-with? (str url) "/")
                            (io/resource (str "public" path "/")))
                url)
        nil))))

(defn- static-response [uri]
  (let [path (if (= uri "/") "/index.html" uri)
        url (static-resource path)]
    (if url
      {:status 200
       :headers {"Content-Type" (get mime-types
                                     (last (str/split path #"\."))
                                     "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body (io/input-stream url)}
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body "not found"})))

(defn- graph-response-body
  "The /api/graph JSON for the query; a debug run logs its error under
  `route`, the route that asked for it."
  [query-string route]
  (let [body (try
               (let [{:keys [root suffix]} @files
                     rel (nav-rel query-string)]
                 (if (embedded-compare?)
                   (let [nm (.getName (io/file root))]
                     (compare-json (embedded-old root) (read-source root)
                                   (str nm " (old)") (str nm " (new)") nm
                                   {:editable false :editable-old false}))
                   (let [{:keys [old new] :as pair} (sides rel)
                         path (or rel (root-rel))
                         new-p (.getPath new)
                         prepare (fn [g] (pairs/attach g (pair-context @root-dir path suffix true)))]
                     (if (some? old)
                       (compare-json (side-source pair "old") (side-source pair "new")
                                     path (fork-name path suffix) (.getName new)
                                     {:editable (not (read-only-side? pair "new"))
                                      :editable-old (not (read-only-side? pair "old"))
                                      :path path}
                                     prepare)
                       (graph-json (read-source new-p) (.getName new)
                                   {:editable (not (png/png? new-p)) :path path}
                                   prepare)))))
               (catch Exception e
                 (json/generate-string {:error (ex-message e)})))]
    ;; graph-json/compare-json fold parse failures into the payload; only
    ;; a debug run pays for parsing it back to find out
    (when (log/enabled?)
      (when-let [err (get (json/parse-string body) "error")]
        (log/event! "error" {:route route :error err})))
    body))

(defn- errors-response-body
  "What the page's banners would show for the same query: the graph
  route's parse error (or nil) and validation warnings, nothing else."
  [query-string]
  (let [out (json/parse-string (graph-response-body query-string "/api/errors"))]
    (json/generate-string {:error (get out "error")
                           :warnings (get out "warnings" [])})))

(defn- edit-response-body
  "Parse the edit request, apply it, log what was asked and what came of
  it, and return the JSON reply."
  [body-stream]
  (let [parsed (try (json/parse-string (slurp body-stream) true)
                    (catch Exception e {:parse-error (ex-message e)}))
        out (if-let [err (:parse-error parsed)]
              {:error err}
              (edit-response parsed))]
    (log/event! "edit" {:file (:file parsed) :ops (:ops parsed) :result out})
    (json/generate-string out)))

(defn- lock-response
  "Apply lock-fn (acquire-lock! / release-lock!) to the request's
  {owner, path}: `path` is the real file name, root-relative (default:
  the root file) and may not exist yet. 400 on a bad request, 409 when
  someone else holds the lock."
  [lock-fn body-stream]
  (let [[status out]
        (try
          (let [{:keys [owner path]} (json/parse-string (slurp body-stream) true)]
            (when-not (and (string? owner) (seq owner))
              (throw (ex-info "owner must be a non-empty string" {})))
            (let [f (resolve-path @root-dir (or path (root-rel)) false)
                  out (lock-fn (.getPath f) owner (System/currentTimeMillis))]
              [(if (:error out) 409 200) out]))
          (catch Exception e [400 {:error (ex-message e)}]))]
    (assoc (json-response (json/generate-string out)) :status status)))

(defn- create-response-body [body-stream]
  (let [parsed (try (json/parse-string (slurp body-stream) true)
                    (catch Exception e {:parse-error (ex-message e)}))
        out (if-let [err (:parse-error parsed)]
              {:error err}
              (create-response parsed))]
    (log/event! "create" {:file (:file parsed) :path (:path parsed) :result out})
    (json/generate-string out)))

(defn- post-only
  "The response of (f) for a guarded POST; 405 for any other method."
  [{:keys [request-method] :as req} f]
  (if (= :post request-method)
    (or (edit-guard req) (f))
    {:status 405 :headers {"Content-Type" "text/plain"} :body "POST only"}))

(defn- route [{:keys [uri query-string body] :as req}]
  (case uri
    "/api/graph"   (json-response (graph-response-body query-string "/api/graph"))
    "/api/errors"  (json-response (errors-response-body query-string))
    "/api/edit"    (post-only req #(json-response (edit-response-body body)))
    "/api/create"  (post-only req #(json-response (create-response-body body)))
    "/api/lock"    (post-only req #(lock-response acquire-lock! body))
    "/api/unlock"  (post-only req #(lock-response release-lock! body))
    "/api/version" (json-response
                    (json/generate-string
                     {:mtime (try
                               (let [{:keys [old new]} (sides (nav-rel query-string))]
                                 (if (some? old)
                                   (str (.lastModified old) "-" (.lastModified new))
                                   (.lastModified new)))
                               ;; a refused file reports a constant: the page
                               ;; reloads once and shows the graph route's error
                               (catch Exception _ 0))}))
    "/api/source"
    (let [which (when (some? query-string)
                  (second (re-find #"(?:^|&)which=(old|new)(?:&|$)" query-string)))
          body (try
                 (if (embedded-compare?)
                   (do (nav-rel query-string)
                       (if (= which "old")
                         (embedded-old (:root @files))
                         (read-source (:root @files))))
                   (let [{:keys [old] :as pair} (sides (nav-rel query-string))]
                     (when (or (not= which "old") (some? old))
                       (side-source pair which))))
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

(defn- request-line [req]
  (str (some-> (:request-method req) name str/upper-case) " " (:uri req)))

(defn guard
  "Wrap a ring handler so an uncaught exception becomes a crash report on
  disk plus a 500 JSON error (with a non-null message, which the browser
  reads as failure), and every rejected request (status >= 400) lands in
  the debug log."
  [h]
  (fn [req]
    (try
      (let [{:keys [status body] :as resp} (h req)]
        (when (and (some? status) (>= status 400))
          (log/event! "error" {:request (request-line req) :status status
                               :body (when (string? body) body)}))
        resp)
      (catch Throwable e
        (log/crash! {:request (request-line req)} e)
        {:status 500
         :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
         :body (json/generate-string {:error (or (ex-message e) (.getName (class e)))})}))))

(def handler (guard route))

(defn version
  "The release this is: the VERSION resource (the install root or the
  jar), or \"dev\" in a checkout."
  []
  (if-let [r (io/resource "VERSION")]
    (str/trim (slurp r))
    "dev"))

(defn start!
  "Serve `file` — compared with its `suffix` fork when one is given — on
  127.0.0.1:`port`. Checks that every side resolves and reads first
  (ex-info with the user-facing message and {:startup-check true}
  otherwise), starts http-kit, then opens the run log:
  java.net.BindException passes through when the port is taken, before
  any log file is written (the CLI retries another port).
  Returns {:served <description> :log-path <path or nil>}."
  [{:keys [file suffix port debug]}]
  (reset! files {:root file :suffix suffix})
  (reset! root-dir (.getParentFile (.getCanonicalFile (io/file file))))
  ;; resolve both sides once so a missing fork or a PNG without
  ;; embedded EDN fails at startup with a clear message instead of an
  ;; empty diagram in the browser
  (try
    (let [{:keys [old new]} (sides nil)]
      (doseq [f (remove nil? [old new])] (read-source (.getPath f))))
    (catch Exception e
      (throw (ex-info (or (ex-message e) (.getName (class e))) {:startup-check true}))))
  (let [served (cond
                 suffix (str file " → " (fork-name file suffix) " (compare)")
                 (embedded-compare?) (str file " (embedded compare)")
                 :else file)
        header (str "simpleviz " (version)
                    " (babashka " (System/getProperty "babashka.version")
                    ") serving " served " on port " port)
        ;; the header only, so a crash report from the bind carries it
        _ (log/init! {:dir (log/default-dir) :debug false :header header})
        ;; loopback only — /api/edit can write to disk, so the server must
        ;; never be reachable from other hosts on the network
        _ (srv/run-server handler {:port port :ip "127.0.0.1"})
        log-path (log/init! {:dir (log/default-dir) :debug debug :header header})]
    (log/install-crash-handler!)
    {:served served :log-path log-path}))

(defn -main [& args]
  (let [{:keys [file suffix port debug error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (when-not (.isFile (io/file file))
      (println (str "file not found: " file))
      (System/exit 1))
    (try
      (let [{:keys [served log-path]} (start! {:file file :suffix suffix :port port :debug debug})]
        (println (str "simpleviz: serving " served " at http://localhost:" port))
        (when log-path (println (str "simpleviz: debug log at " log-path)))
        @(promise))
      (catch java.net.BindException _
        ;; a busy port is a usage problem, not a crash
        (println (str "port " port " is already in use — pass --port N to pick another"))
        (System/exit 1))
      (catch clojure.lang.ExceptionInfo e
        (if (:startup-check (ex-data e))
          (println (ex-message e))
          (log/crash! {:phase "startup"} e))
        (System/exit 1))
      (catch Throwable e
        (log/crash! {:phase "startup"} e)
        (System/exit 1)))))
