(ns serve
  "Static file server + EDN->JSON API. Parses and normalizes the graph
  (shape checks, semantics) server-side via graph/normalize; the browser
  just renders the resulting JSON."
  (:require [analyze]
            [babashka.cli :as cli]
            [calc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deck]
            [diff]
            [edit]
            [graph]
            [log]
            [org.httpkit.server :as srv]
            [embedded]
            [mdlinks]
            [pairs]
            [paths]
            [session]
            [steps]
            [themes])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(def default-port 7373)

(def files (atom nil)) ; {:root <path> :suffix <s-or-nil>}

(def undo-stacks (atom {})) ; path -> [text ...] newest last, capped

(def root-dir (atom nil)) ; canonical File of the served folder (single-file mode)

(def locks (atom {})) ; canonical path -> {:owner s :expires ms}

(def prefs-on (atom false)) ; /api/prefs reads and writes prefs-file (start! :prefs)

(def lock-ttl-ms 60000)

(def ^:private ref-extensions #{"edn" "png" "svg"})

(def ^:private doc-extensions #{"md"})

;; what a presentation slide may show as a picture (/api/file)
(def ^:private image-types
  {"png" "image/png" "svg" "image/svg+xml" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp"})

;; an agent may lock a doc before writing it, as it locks a graph
(def ^:private lockable-extensions (into ref-extensions doc-extensions))

;; what a markdown link may lead to
(def ^:private link-extensions (into ref-extensions doc-extensions))

(defn- extensions-phrase
  "\"an .edn, .png or .svg\" for #{\"edn\" \"png\" \"svg\"}."
  [exts]
  (let [ds (mapv (fn [e] (str "." e)) (sort exts))]
    (str "an " (if (= 1 (count ds))
                 (first ds)
                 (str (str/join ", " (pop ds)) " or " (peek ds))))))

(def suffix-re
  "A fork suffix: graph-<suffix>.edn. It can't start with -, which would
  read as a mistyped flag."
  #"^[A-Za-z0-9_.][A-Za-z0-9_.-]*$")

(def empty-graph
  "What a file created by the server holds: map form, so it is editable."
  "{:nodes {} :edges {} :boxes {}}\n")

(defn- split-ext
  "[stem ext] of path `rel`, ext with its dot (\"a/b.edn\" -> [\"a/b\" \".edn\"]);
  ext is \"\" when the last segment has no dot."
  [rel]
  (let [dot (str/last-index-of rel ".")
        slash (or (str/last-index-of rel "/") -1)]
    (if (and (some? dot) (> dot slash))
      [(subs rel 0 dot) (subs rel dot)]
      [rel ""])))

(defn fork-name
  "The fork of path `rel` for `suffix`: the suffix goes before the
  extension (\"a/b.edn\" \"next\" -> \"a/b-next.edn\"); a name without
  an extension gets \"-suffix\" appended."
  [rel suffix]
  (let [[stem ext] (split-ext rel)]
    (str stem "-" suffix ext)))

(defn fork-base
  "The path `rel` is the fork of under `suffix` (\"a/b-next.edn\"
  \"next\" -> \"a/b.edn\"), or nil when it carries no such suffix."
  [rel suffix]
  (let [[stem ext] (split-ext rel)
        tail (str "-" suffix)]
    (when (and (str/ends-with? stem tail) (> (count stem) (count tail)))
      (str (subs stem 0 (- (count stem) (count tail))) ext))))

(defn- ext-of
  "File name nm's extension, lower case; nil without a dot."
  [nm]
  (when-let [dot (str/last-index-of nm ".")]
    (str/lower-case (subs nm (inc dot)))))

(defn missing-fork
  "Why `rel` can't be compared under `suffix`: its fork is not there,
  and how to make it."
  [rel suffix]
  (str "no " (fork-name rel suffix) " — create it with: simpleviz fork " rel " " suffix))

(defn there?
  "Is anything at File `f` — a symlink too, even one whose target is
  missing (which .exists calls nothing)?"
  [f]
  (java.nio.file.Files/exists (.toPath (io/file f))
                              (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))

(defn write-new!
  "Write `content` (bytes) to File `f`, which must not be there yet:
  CREATE_NEW fails on anything that is, a symlink included, instead of
  writing through it. Throws FileAlreadyExistsException."
  [f ^bytes content]
  (java.nio.file.Files/write (.toPath (io/file f)) content
                             (into-array java.nio.file.OpenOption
                                         [java.nio.file.StandardOpenOption/CREATE_NEW])))

(defn resolve-path
  "The canonical file for the root-relative path `rel` under `root`.
  Refuses (ex-info, message names the problem) an absolute path, a
  result outside `root` after canonicalization (so `..` and symlinks
  cannot escape), an extension outside `exts` (default .edn/.png/.svg),
  and — unless must-exist? is false — anything that is not an existing
  regular file."
  ([root rel] (resolve-path root rel true))
  ([root rel must-exist?] (resolve-path root rel must-exist? ref-extensions))
  ([root rel must-exist? exts]
   (let [rel (str rel)]
     (when (.isAbsolute (io/file rel))
       (throw (ex-info (str "absolute path refused: " rel) {})))
     (let [root-c (.getCanonicalFile (io/file root))
           f (.getCanonicalFile (io/file root-c rel))
           ext (ext-of (.getName f))]
       (when-not (str/starts-with? (.getPath f) (str (.getPath root-c) java.io.File/separator))
         (throw (ex-info (str rel " leaves the served folder") {})))
       (when-not (contains? exts ext)
         (throw (ex-info (str rel " is not " (extensions-phrase exts) " file") {})))
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

(def ^:private file-monitors (atom {})) ; canonical path -> Object

(defn- file-monitor
  "One monitor per canonical file, shared by edits and advisory locks."
  [path]
  (or (get @file-monitors path)
      (get (swap! file-monitors
                  (fn [m] (if (contains? m path) m (assoc m path (Object.)))))
           path)))

(defn- lock-holder
  "The owner of the live lock on `path` at `now`, or nil."
  [path now]
  (let [{:keys [owner expires]} (get @locks path)]
    (when (and (some? owner) (< now expires)) owner)))

(defn acquire-lock!
  "Take or renew the advisory write lock on `path` for `owner` at `now`
  (ms): granted when the file is free, the lock expired, or `owner`
  already holds it. One swap! decides, so two racing requests cannot
  both win; a browser edit in flight finishes before a lock is granted."
  [path owner now]
  (locking (file-monitor path)
    (let [after (swap! locks
                       (fn [m]
                         (let [{held :owner :keys [expires]} (get m path)]
                           (if (and (some? held) (< now expires) (not= held owner))
                             m
                             (assoc m path {:owner owner :expires (+ now lock-ttl-ms)})))))
          holder (:owner (get after path))]
      (if (= holder owner)
        {:ok true :ttl (quot lock-ttl-ms 1000)}
        {:error (str "locked by " holder) :owner holder}))))

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
  "EDN text of a graph file: simpleviz PNG and SVG exports yield their
  embedded EDN (compare exports yield the new side), everything else its
  raw contents. Throws with a clear message when an export has nothing
  embedded."
  [f]
  (if (embedded/export? f)
    (or (embedded/extract f "simpleviz-edn-new")
        (embedded/extract f "simpleviz-edn")
        (throw (ex-info (str "no embedded simpleviz EDN found in " f) {:image true})))
    (slurp f)))

(defn- embedded-old
  "The old-side EDN of a single-file compare export, nil otherwise."
  [f]
  (when (embedded/export? f) (embedded/extract f "simpleviz-edn-old")))

(defn- query-param
  "The URL-decoded value of parameter k in a query string, or nil — also
  when its encoding is broken (%zz), as any web page can send."
  [query-string k]
  (when (some? query-string)
    (some (fn [kv]
            (let [[name v] (str/split kv #"=" 2)]
              (when (= name k)
                (try (java.net.URLDecoder/decode (or v "") "UTF-8")
                     (catch IllegalArgumentException _ nil)))))
          (str/split query-string #"&"))))

(defn- embedded-compare?
  "Is the root a compare export (both sides embedded, no suffix)?"
  []
  (let [{:keys [root suffix]} @files]
    (and (nil? suffix) (some? (embedded-old root)))))

(defn- root-rel
  "The root file's name, root-relative: its canonical basename, so a
  symlinked root (bb serve ~/link.edn -> elsewhere/g.edn) resolves under
  the real name rather than the raw basename of the served path."
  []
  (.getName (.getCanonicalFile (io/file (:root @files)))))

(defn- refuse-fork!
  "Throw when `rel` is the fork of another file under `suffix`: refs name
  the original."
  [rel suffix]
  (when-let [base (and (some? suffix) (fork-base rel suffix))]
    (throw (ex-info (str rel " is the fork of " base " — ref the original") {}))))

(defn- refuse-embedded-compare!
  "Throw on an embedded compare, which has no folder to navigate."
  []
  (when (embedded-compare?)
    (throw (ex-info "refs are not available in an embedded compare" {}))))

(defn- suffix?
  "Is `s` a fork suffix (suffix-re)?"
  [s]
  (and (string? s) (some? (re-matches suffix-re s))))

(defn request-suffix
  "The fork suffix a request compares under: its own `compare` (an md
  link's ?compare=<suffix>), else the one the server started with.
  Throws on a compare that is no suffix, or on an embedded compare,
  which holds both sides already."
  [compare]
  (cond
    (nil? compare) (:suffix @files)
    (embedded-compare?) (throw (ex-info "compare is not available in an embedded compare" {}))
    (suffix? compare) compare
    :else (throw (ex-info (str "invalid compare suffix: " (pr-str compare)) {}))))

(defn sides
  "The files behind the root-relative path `rel` (nil = the root file)
  as {:old <canonical File or nil> :new <canonical File>}: without a
  suffix only :new; with one, :old is the file and :new its fork. The
  suffix is the request's `compare` (request-suffix), else the
  startup one. One side of a pair may be missing (see side-file),
  except the root's under the startup suffix, which `simpleviz <root>
  <suffix>` promised. Throws (message for the error payload) when
  resolve-path refuses either side or nothing is there."
  ([rel] (sides rel nil))
  ([rel compare]
   (let [suffix (request-suffix compare)
         rel (or rel (root-rel))
         root-c @root-dir]
     (if (nil? suffix)
       {:old nil :new (resolve-path root-c rel)}
       (let [fk (fork-name rel suffix)
             _ (refuse-fork! rel suffix)
             ;; escape/extension refusals first, existence checked here so
             ;; the message can name the missing side
             old (resolve-path root-c rel false)
             new (resolve-path root-c fk false)]
         (cond
           (and (.isFile old) (.isFile new)) {:old old :new new}
           (or (some? compare) (not= old (resolve-path root-c (root-rel) false)))
           (if (or (.isFile old) (.isFile new))
             {:old old :new new}
             (throw (ex-info (str "no such file: " rel) {})))
           (not (.isFile new))
           (throw (ex-info (missing-fork rel suffix) {}))
           :else
           (throw (ex-info (str "no " rel " (only " fk ")") {}))))))))

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

(defn- export-name?
  "Does the file's name say PNG or SVG?"
  [f]
  (boolean (re-find #"(?i)\.(png|svg)$" (.getName f))))

(defn- read-only-side?
  "Is side `which` a PNG or SVG — by content, or by name when nothing is
  there yet, so an edit cannot bring a .png or .svg holding EDN text
  into being."
  [{:keys [old new] :as sides} which]
  (let [target (if (= which "old") old new)]
    (boolean
     (or (some-> (side-file sides which) .getPath embedded/export?)
         (and (not (.isFile target)) (export-name? target))))))

(defn- side-source [sides which]
  (if-let [f (side-file sides which)] (read-source (.getPath f)) empty-graph))

(defn- nav-rel
  "The `file` query parameter, or nil; refused (throws) on an embedded
  compare, which has no folder to navigate."
  [query-string]
  (when-let [rel (query-param query-string "file")]
    (refuse-embedded-compare!)
    rel))

(defn- analyzer-of
  "For `rel` naming an md file's analyzer block, <doc.md>#analyzer-<n>,
  [md n] — the md file checked as resolve-path checks it; else nil."
  [rel]
  (when-let [[_ md n] (re-matches #"(?i)(.+\.md)#analyzer-([1-9][0-9]{0,3})" (str rel))]
    (resolve-path @root-dir md true doc-extensions)
    [md (parse-long n)]))

(declare write-atomically!)

(defn- edit-response [{:keys [file ops path compare step]}]
  (try
    (when (some? path) (refuse-embedded-compare!))
    (let [{:keys [old new] :as pair} (sides path compare)
          target (if (= file "old") old new)
          path (some-> target .getPath)]
      (if (nil? path)
        {:error "no old file in single-file mode"}
        ;; read, apply, write and undo as one step per file: two edits
        ;; in flight would both read the same text and the later write
        ;; would drop the earlier change
        (locking (file-monitor path)
          ;; a side that is not there yet comes into being with its
          ;; first successful edit — maybe the one this waited for
          (let [source (side-file pair file)
                ;; the browser owns no lock, so any live one blocks it
                holder (lock-holder path (System/currentTimeMillis))]
            (cond
              (read-only-side? pair file) {:error "PNG and SVG sources are read-only"}
              (some? holder) {:error (str "locked by " holder)}
              (= "undo" (:op (first ops)))
              ;; a file deleted since (promote, by hand) stays deleted;
              ;; the snapshot is used up only once it is written back
              (if-let [prev (when (.isFile target) (peek (get @undo-stacks path)))]
                (do (write-atomically! target (.getBytes ^String prev "UTF-8"))
                    (pop-undo! path)
                    {:ok true})
                {:error "nothing to undo"})
              :else
              ;; snapshot the ORIGINAL text before applying — `before`
              ;; feeds both the patch and the undo stack
              (let [before (if (some? source) (slurp source) empty-graph)
                    {:keys [text error]} (steps/apply-ops before ops step)]
                (if (some? error)
                  {:error error}
                  (do (write-atomically! target (.getBytes ^String text "UTF-8"))
                      (push-undo! path before)
                      {:ok true}))))))))
    (catch Exception e {:error (or (ex-message e) (str e))})))

(defn- copy-response
  "The EDN text of nodes and boxes `ids` with what they hold, and the
  targets that took (edit/copy), from the side `file` names — what an
  edit there would change."
  [{:keys [file ids path compare step]}]
  (try
    (when (some? path) (refuse-embedded-compare!))
    (let [text (side-source (sides path compare) file)]
      (edit/copy (if (and (some? step) (pos? step)) (steps/state-text text step) text) {:ids ids}))
    (catch Exception e {:error (or (ex-message e) (str e))})))

(defn- new-graph-target
  "Where a new graph at root-relative `path` goes under the request's
  `compare` (request-suffix): {:suffix :base :fk :fork :exists}, :fk and
  :fork the fork's name and file in a comparison, :exists whether either
  side is there. `exts` are the extensions path may have. Refuses an
  embedded comparison, a missing path and a fork named directly."
  [path compare exts]
  (refuse-embedded-compare!)
  (when-not (string? path)
    (throw (ex-info "path required" {})))
  (let [suffix (request-suffix compare)
        _ (refuse-fork! path suffix)
        base (resolve-path @root-dir path false exts)
        fk (when (some? suffix) (fork-name path suffix))
        fork (when (some? fk) (resolve-path @root-dir fk false exts))]
    {:suffix suffix :base base :fk fk :fork fork
     :exists (boolean (or (.exists base) (and (some? fork) (.exists fork))))}))

(defn- create-response
  "Create the graph file a followed ref names, with its folders, when
  nothing is there yet: in a comparison the side being edited (`file`),
  and only while neither side exists — an empty fork next to an
  original would show everything as removed. With `from` (a
  root-relative .edn, outside a comparison) it starts as a byte copy of
  that file rather than empty. Never overwrites. The path's refusals
  (new-graph-target, then a PNG or SVG name) come before the copy's."
  [{:keys [file path compare from]}]
  (try
    (let [{:keys [suffix base fk fork exists]} (new-graph-target path compare ref-extensions)
          _ (when (export-name? base)
              (throw (ex-info "PNG and SVG files cannot be created" {})))
          _ (when (and (some? from) (some? suffix))
              (throw (ex-info "copying is not available in a comparison" {})))
          content (if (some? from)
                    (java.nio.file.Files/readAllBytes (.toPath (resolve-path @root-dir from true #{"edn"})))
                    (.getBytes empty-graph "UTF-8"))
          [rel target] (if (and (some? fork) (not= file "old")) [fk fork] [path base])]
      (if exists
        {:ok true :created nil}
        (do (.mkdirs (.getParentFile target))
            (try
              ;; a file that appeared since the check is kept
              (write-new! target content)
              {:ok true :created rel}
              (catch java.nio.file.FileAlreadyExistsException _
                {:ok true :created nil})))))
    (catch Exception e {:error (ex-message e)})))

(def ^:private usage
  (str "usage: bb serve <graph.edn|export.png|export.svg> [<suffix>] [--port N] [--debug]\n"
       "  with a suffix, graph.edn is compared against its fork graph-<suffix>.edn\n"
       "  (create the fork with: bb fork graph.edn <suffix>); refs follow into\n"
       "  the same comparison of the referenced file and its fork\n"
       "  a PNG or SVG exported from simpleviz serves its embedded EDN;\n"
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
        (and (some? suffix) (re-find #"(?i)\.(edn|png|svg)$" suffix))
        {:error (str "two-file compare was replaced: bb fork " f1 " <suffix>, then bb serve " f1 " <suffix>\n" usage)}
        (and (some? suffix) (nil? (re-matches suffix-re suffix)))
        {:error (str "invalid suffix: " suffix "\n" usage)}
        (and (some? suffix) (paths/md-path? f1))
        {:error (str "compare mode needs a graph file (.edn, .png or .svg)\n" usage)}
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
  ;; so rethrow the plain message. A form nested past the stack is an
  ;; Error, not an Exception: made one here, it is just a file that
  ;; doesn't parse, for every caller.
  (let [r (LineNumberingPushbackReader. (StringReader. s))
        root (try (edn/read {:eof nil} r)
                  (catch StackOverflowError _ (throw (ex-info "the file nests too deeply to read" {})))
                  (catch Exception e (throw (ex-info (ex-message (or (ex-cause e) e)) {} e))))]
    ;; a root that isn't a map gets normalize's own warning instead
    (when (map? root)
      (let [line (.getLineNumber r)]
        (when-not (= ::eof (try (edn/read {:eof ::eof} r)
                                (catch StackOverflowError _ ::stray)
                                (catch Exception _ ::stray)))
          (throw (ex-info (str "content after the end of the graph: its map closes at line "
                               line " — check there for an extra }")
                          {})))))
    (steps/attach (graph/normalize root) root graph/normalize)))

(def ^:private graph-cache
  "canonical path -> {:stamp [mtime length] :graph g} or {:stamp ..
  :error msg}: the files pairs read, parsed once per modification."
  (atom {}))

(defn- cached-graph
  "The normalized graph in canonical file f (`rel` names it in
  messages), parsed once per mtime and length — the length catches a
  rewrite within the mtime's resolution; throws \"<rel> does not
  parse\"."
  [f rel]
  (let [k (.getPath f)
        stamp [(.lastModified f) (.length f)]
        hit (get @graph-cache k)
        entry (if (= stamp (:stamp hit))
                hit
                (let [e (try {:stamp stamp :graph (parse-graph (read-source k))}
                             (catch Exception _ {:stamp stamp :error (str rel " does not parse")}))]
                  (swap! graph-cache assoc k e)
                  e))]
    (if-let [err (:error entry)]
      (throw (ex-info err {}))
      (:graph entry))))

(defn- read-rel-graph
  "The normalized graph of root-relative `rel` under folder `root`, for
  pairs: throws \"<rel> not found\", resolve-path's refusal, or
  \"<rel> does not parse\". With `suffix`, rel's fork is read when it
  exists — side-file's rule for the new side — still named rel."
  ([root rel]
   (let [f (try (resolve-path root rel)
                (catch Exception e
                  (throw (ex-info (if (str/starts-with? (str (ex-message e)) "no such file")
                                    (str rel " not found")
                                    (ex-message e))
                                  {}))))]
     (cached-graph f rel)))
  ([root rel suffix]
   (if-let [fk (try (resolve-path root (fork-name rel suffix)) (catch Exception _ nil))]
     (cached-graph fk rel)
     (read-rel-graph root rel))))

(defn- scan-edn
  "Root-relative paths of the .edn files under folder `root`, sorted:
  not a dotfile, not a fork of `suffix`. The walk never enters a
  dot-folder, node_modules, or a symlinked folder (so a link loop ends)."
  [root suffix]
  (let [root-f (io/file root)
        root-p (.toPath root-f)
        descend? (fn [f]
                   (or (identical? f root-f)
                       (let [nm (.getName f)]
                         (and (.isDirectory f)
                              (not (str/starts-with? nm "."))
                              (not= nm "node_modules")
                              (not (java.nio.file.Files/isSymbolicLink (.toPath f)))))))]
    (->> (tree-seq descend? (fn [d] (seq (.listFiles d))) root-f)
         (filter (fn [f] (let [nm (.getName f)]
                           (and (not (str/starts-with? nm "."))
                                (str/ends-with? (str/lower-case nm) ".edn")
                                (.isFile f)))))
         (map (fn [f] (str/replace (str (.relativize root-p (.toPath f))) java.io.File/separator "/")))
         (remove (fn [rel] (and (some? suffix) (some? (fork-base rel suffix)))))
         sort)))

(defn pair-index
  "The reverse index (pairs/index) of every .edn under canonical folder
  `root` but `suffix`'s forks, read through the mtime cache; a file that
  doesn't parse contributes nothing."
  [root suffix]
  (pairs/index (keep (fn [r] (try [r (read-rel-graph root r)] (catch Exception _ nil)))
                     (scan-edn root suffix))))

(defn pair-context
  "The pairs/attach ctx for root-relative `rel` under canonical folder
  `root`: reads through the mtime cache, knows `suffix`'s forks, and
  looks incoming pairs up in `index` (pair-index, or {} as check has
  it). Side \"new\" of a comparison reads each target's fork when it
  exists; \"old\" (the default) reads the originals."
  ([root rel suffix index] (pair-context root rel suffix index "old"))
  ([root rel suffix index side]
   {:path rel
    :read (if (and (some? suffix) (= side "new"))
            (fn [r] (read-rel-graph root r suffix))
            (fn [r] (read-rel-graph root r)))
    :fork-of (fn [r] (when (some? suffix) (fork-base r suffix)))
    :index index}))

(defn- graph-payload
  "graph-json's payload as a map, {:error message} when s does not parse."
  [s fname extra-map prepare]
  (try
    (cond-> (let [g (parse-graph s)]
              (cond-> (prepare g)
                (some? (:steps g)) (assoc :steps (mapv (fn [st] (update st :graph prepare)) (:steps g)))))
      (some? fname) (assoc :file fname)
      (some? extra-map) (merge extra-map))
    (catch Exception e
      {:error (ex-message e)})))

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
   (json/generate-string (graph-payload s fname extra-map prepare))))

(defn- compare-payload
  "compare-json's payload as a map, {:error \"<file>: msg\"} when a side
  does not parse."
  [old-s new-s old-name new-name file-name extra-map prepare]
  (try
    (let [parse (fn [s nm]
                  (try (parse-graph s)
                       (catch Exception e
                         (throw (ex-info (str nm ": " (ex-message e)) {})))))
          ;; a comparison shows two files, not the states of one
          old-g (prepare (dissoc (parse old-s old-name) :steps) "old")
          new-g (prepare (dissoc (parse new-s new-name) :steps) "new")]
      (cond-> (assoc (diff/union old-g new-g old-name new-name)
                     :file file-name)
        (some? extra-map) (merge extra-map)))
    (catch Exception e
      {:error (ex-message e)})))

(defn compare-json
  "Parse and normalize two EDN strings, diff them into one union-graph
  JSON string; file-name overrides the export download name (defaults to
  new-name's basename). A parse failure returns {\"error\": \"<file>: msg\"}.
  prepare, when given, transforms each normalized graph before the diff
  (pairs/attach): (prepare g \"old\") and (prepare g \"new\")."
  ([old-s new-s old-name new-name]
   (compare-json old-s new-s old-name new-name
                 (.getName (io/file new-name)) nil))
  ([old-s new-s old-name new-name file-name]
   (compare-json old-s new-s old-name new-name file-name nil))
  ([old-s new-s old-name new-name file-name extra-map]
   (compare-json old-s new-s old-name new-name file-name extra-map (fn [g _] g)))
  ([old-s new-s old-name new-name file-name extra-map prepare]
   (json/generate-string (compare-payload old-s new-s old-name new-name file-name extra-map prepare))))

(defn- step-pair
  "The `steps` query parameter \"a,b\" as [a b], two step numbers."
  [v]
  (let [[a b :as ks] (map #(parse-long (str/trim %)) (str/split v #","))]
    (when-not (and (= 2 (count ks)) (some? a) (some? b))
      (throw (ex-info (str "steps must be two step numbers, like 0,2 — got " (pr-str v)) {})))
    [a b]))

(defn- steps-compare-payload
  "States a (old) and b (new) of the graph in EDN text s — 0 the file as
  written — diffed into one union graph, as compare-json does for a file
  and its fork; read-only. prepare transforms each state first
  (pairs/attach); extra-map is merged into the payload. {:error msg}
  on a failure."
  [s [a b] extra-map prepare]
  (try
    (let [g (parse-graph s)
          steps (or (:steps g) [])
          state (fn [k]
                  (when-not (<= 0 k (count steps)) (throw (ex-info (str "no step " k) {})))
                  (if (zero? k) (dissoc g :steps) (:graph (nth steps (dec k)))))
          label (fn [k] (if (zero? k) "start" (str k " " (:name (nth steps (dec k))))))
          old-g (prepare (state a))
          new-g (prepare (state b))]
      (merge (diff/union old-g new-g (label a) (label b))
             extra-map
             {:editable false :editable-old false}))
    (catch Exception e
      {:error (ex-message e)})))

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
  /api/lock, /api/unlock, /api/text/save, POST /api/prefs, POST
  /api/answers), or nil when the
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

(defn- json-status-response
  "A JSON response of `out` with HTTP `status`."
  [status out]
  (assoc (json-response (json/generate-string out)) :status status))

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

(defn- text-response [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body body})

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
      (text-response 404 "not found"))))

(defn- file-response
  "The image file `path` (root-relative) of the served folder names, for a
  presentation slide; 404 with the reason for anything else. Sandboxed, so
  an SVG opened on its own runs no script on this origin."
  [query-string]
  (try
    (let [rel (query-param query-string "path")
          _ (when-not (seq rel) (throw (ex-info "path required" {})))
          f (resolve-path @root-dir rel true (set (keys image-types)))
          ext (ext-of (.getName f))]
      {:status 200
       :headers {"Content-Type" (get image-types ext)
                 "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; sandbox"
                 "X-Content-Type-Options" "nosniff"
                 "Cache-Control" "no-store"}
       :body f})
    (catch Exception e (text-response 404 (ex-message e)))))

(defn- graph-response
  "The /api/graph payload for the query, a map; a debug run logs its
  error under `route`, the route that asked for it."
  [query-string route]
  (let [out (try
               (let [{:keys [root]} @files
                     rel (nav-rel query-string)
                     compare (query-param query-string "compare")
                     suffix (request-suffix compare)]
                 (cond
                   (embedded-compare?)
                   (let [nm (.getName (io/file root))]
                     (compare-payload (embedded-old root) (read-source root)
                                      (str nm " (old)") (str nm " (new)") nm
                                      {:editable false :editable-old false} (fn [g _] g)))

                   ;; an md file's analyzer block: its graph, read-only
                   (some? (analyzer-of rel))
                   (let [[md n] (analyzer-of rel)]
                     (graph-payload (analyze/graph-text (.getCanonicalFile @root-dir) md n)
                                    (str (str/replace md #"(?i)\.md$" "") "-analyzer-" n ".edn")
                                    {:editable false :path rel}
                                    identity))

                   :else
                   (let [{:keys [old new] :as pair} (sides rel compare)
                         path (or rel (root-rel))
                         new-p (.getPath new)
                         ;; one scan per request, shared by both sides
                         index (pair-index @root-dir suffix)
                         prepare (fn [g which] (pairs/attach g (pair-context @root-dir path suffix index which)))]
                     (cond
                       (some? old)
                       (compare-payload (side-source pair "old") (side-source pair "new")
                                        path (fork-name path suffix) (.getName new)
                                        {:editable (not (read-only-side? pair "new"))
                                         :editable-old (not (read-only-side? pair "old"))
                                         :path path}
                                        prepare)

                       ;; two states of the file's :steps, compared
                       (some? (query-param query-string "steps"))
                       (steps-compare-payload (read-source new-p)
                                              (step-pair (query-param query-string "steps"))
                                              {:path path :file (.getName new)}
                                              #(prepare % "new"))

                       :else
                       (graph-payload (read-source new-p) (.getName new)
                                      {:editable (not (embedded/export? new-p)) :path path}
                                      #(prepare % "new"))))))
               (catch Exception e
                 ;; :image — a picture, not a graph: a slide shows it as one
                 (cond-> {:error (ex-message e)}
                   (:image (ex-data e)) (assoc :image true))))]
    (when-let [err (:error out)]
      (log/event! "error" {:route route :error err}))
    out))

(declare md-errors)

(defn- errors-response-body
  "What the page's banners would show for the same query: the graph
  route's parse error (or nil) and validation warnings, nothing else —
  or, for an md file, its read error, broken links, malformed
  questions and broken charts."
  [query-string]
  (let [rel (try (or (nav-rel query-string) (root-rel)) (catch Exception _ nil))]
    (if (and (some? rel) (paths/md-path? rel))
      (json/generate-string (md-errors rel))
      (let [out (graph-response query-string "/api/errors")]
        (json/generate-string {:error (:error out)
                               :warnings (:warnings out [])})))))

(defn- read-json-body
  "The request body's JSON, keys keywordized, or {:parse-error msg}."
  [body-stream]
  (try (json/parse-string (slurp body-stream) true)
       (catch Exception e {:parse-error (ex-message e)})))

(defn- edit-response-body
  "Parse the edit request, apply it, log what was asked and what came of
  it, and return the JSON reply."
  [body-stream]
  (let [parsed (read-json-body body-stream)
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
            (let [f (resolve-path @root-dir (or path (root-rel)) false lockable-extensions)
                  out (lock-fn (.getPath f) owner (System/currentTimeMillis))]
              [(if (:error out) 409 200) out]))
          (catch Exception e [400 {:error (ex-message e)}]))]
    (json-status-response status out)))

(defn- copy-response-body [body-stream]
  (let [parsed (read-json-body body-stream)]
    (json/generate-string (if-let [err (:parse-error parsed)] {:error err} (copy-response parsed)))))

(defn- create-response-body [body-stream]
  (let [parsed (read-json-body body-stream)
        out (if-let [err (:parse-error parsed)]
              {:error err}
              (create-response parsed))]
    (log/event! "create" {:file (:file parsed) :path (:path parsed) :result out})
    (json/generate-string out)))

;; ---- graphs a new one can start as a copy of ----

(def ^:private max-graph-choices 2000)

(defn- graphs-response
  "For a link to root-relative graph `path`: whether it is there yet (in
  a comparison either side) and, when not, the graphs it could start as
  a copy of — none in a comparison, where create refuses copying. The
  comparison is the link's `compare` (request-suffix), else the
  startup one."
  [path compare]
  (try
    (let [{:keys [suffix exists]} (new-graph-target path compare #{"edn"})
          fs (if (or exists (some? suffix)) [] (scan-edn @root-dir suffix))]
      {:exists exists
       :files (vec (take max-graph-choices fs))
       :truncated (> (count fs) max-graph-choices)})
    (catch Exception e {:error (ex-message e)})))

;; ---- docs: the plain-text editor for :md-ref files ----

(def ^:private max-doc-bytes (* 1024 1024))

;; one save at a time: the version check and the write must not interleave
(def ^:private save-monitor (Object.))

(defn- sha1-hex [^bytes b]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-1") b)]
    (apply str (map (fn [x] (format "%02x" (bit-and x 0xff))) d))))

(defn- doc-file
  "The canonical .md file the root-relative `path` names (may be missing)."
  [path]
  (when-not (string? path) (throw (ex-info "path required" {})))
  (resolve-path @root-dir path false doc-extensions))

(defn doc-state
  "What is on disk at f: {:text :version :exists}; a missing file is
  empty with version nil. Refuses a file over max-doc-bytes or not
  valid UTF-8."
  [f]
  (if (.isFile f)
    (do (when (> (.length f) max-doc-bytes)
          (throw (ex-info (str (.getName f) " is over 1 MiB") {})))
        (let [b (java.nio.file.Files/readAllBytes (.toPath f))
              ;; strict: a lossy decode would show U+FFFD, and the first
              ;; save would write it over the original bytes
              text (try (str (.decode (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                                      (java.nio.ByteBuffer/wrap b)))
                        (catch java.nio.charset.CharacterCodingException _
                          (throw (ex-info (str (.getName f) " is not UTF-8") {}))))]
          {:text text :version (sha1-hex b) :exists true}))
    {:text "" :version nil :exists false}))

(defn- write-atomically!
  "Replace f with `bytes` through a temp file in its folder (created with
  its parents), so a reader never sees half a file; an existing file
  keeps its permissions."
  [f ^bytes bytes]
  (let [dir (.getParentFile f)
        _ (.mkdirs dir)
        tmp (io/file dir (str "." (.getName f) ".simpleviz-tmp-" (System/nanoTime)))
        tp (.toPath tmp)]
    (try
      (java.nio.file.Files/write tp bytes (into-array java.nio.file.OpenOption
                                                      [java.nio.file.StandardOpenOption/CREATE_NEW
                                                       java.nio.file.StandardOpenOption/WRITE]))
      (when (.isFile f)
        (try (java.nio.file.Files/setPosixFilePermissions
              tp (java.nio.file.Files/getPosixFilePermissions (.toPath f) (make-array java.nio.file.LinkOption 0)))
             (catch UnsupportedOperationException _ nil)))
      (java.nio.file.Files/move tp (.toPath f)
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                             java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally (.delete tmp)))))

(defn- text-response-body [query-string]
  (json/generate-string
   (try (doc-state (doc-file (query-param query-string "path")))
        (catch Exception e {:error (ex-message e)}))))

(defn- compare-problem
  "What is wrong with link destination `dest` (its target, `target`
  root-relative, is a file) comparing under `suffix`, or nil."
  [root dest target suffix]
  (cond
    (not (suffix? suffix))
    (str dest ": invalid compare suffix " (pr-str suffix))
    (paths/md-path? dest)
    (str dest ": compare needs a graph file (.edn, .png or .svg)")
    (some? (fork-base dest suffix))
    (str dest " is the fork of " (fork-base dest suffix) " — link the original")
    (not (try (.isFile (resolve-path root (fork-name target suffix) false link-extensions))
              (catch Exception _ false)))
    (str dest ": " (missing-fork dest suffix))))

(def ^:private data-extensions #{"csv" "jsonl" "json" "edn"})

(def ^:private max-data-bytes (* 50 1024 1024))

(defn- data-reader
  "calc/with-data's reader for the tables of md file `md` (relative to
  folder `root`, by default the served one): a >> link resolves as the
  md file's links do, and must name a csv, jsonl, json or edn file of
  that folder."
  ([md] (data-reader @root-dir md))
  ([root md]
   (fn [dest]
     (when-not (string? md) (throw (ex-info "data needs the md file's path" {})))
     (let [rel (or (paths/resolve-ref md dest) (throw (ex-info (str dest " leaves the served folder") {})))
           f (resolve-path root rel true data-extensions)]
       (when (> (.length f) max-data-bytes)
         (throw (ex-info (str rel " is over 50 MiB") {})))
       [(ext-of (.getName f)) (slurp f)]))))

(defn- link-warnings
  "The broken links of md file `rel` (root-relative, under folder `root`)
  holding `text`: one [N \"<dest> not found\"], [N \"<dest> leaves the
  served folder\"] (or another problem the path check names it by) or
  [N \"<dest>: <message>\"], N its line, per followable link whose
  target is no file below `root`, or whose ?compare= cannot compare it
  (compare-problem); one link's failure never fails the rest. A
  reference use is checked once, at its definition."
  [root rel text]
  (let [dir (some-> (.getParent (io/file rel)) (str/replace "\\" "/"))]
    (keep (fn [{:keys [line dest kind] :as l}]
            (when (and (not= kind "ref") (mdlinks/followable? dest))
              (let [target (if (some? dir) (str dir "/" dest) dest)]
                (some->> (try (cond
                                (not (.isFile (resolve-path root target false link-extensions)))
                                (str dest " not found")
                                (contains? l :compare)
                                (compare-problem root dest target (:compare l)))
                              (catch Exception e
                                ;; resolve-path names the path it checked: say dest instead
                                (let [msg (str (ex-message e))]
                                  (if (str/starts-with? msg (str target " "))
                                    (str dest (subs msg (count target)))
                                    (str dest ": " msg)))))
                         (vector line)))))
          (mdlinks/links text))))

(defn md-warnings
  "What is wrong with md file `rel` (root-relative, under folder `root`)
  holding `text`, one \"line N: <warning>\" each, in line order: its
  broken links (link-warnings), the malformed questions of its slides
  (deck/warnings) and its broken charts (calc/chart-warnings)."
  [root rel text]
  ;; sort-by is stable: one line's warnings keep their order
  (mapv (fn [[line w]] (str "line " line ": " w))
        (sort-by first (concat (link-warnings root rel text) (deck/warnings text)
                               (calc/chart-warnings text (data-reader root rel))))))

(defn- md-errors
  "The /api/errors report for md file `rel`: its read error, or its
  md-warnings."
  [rel]
  (try (let [{:keys [text exists]} (doc-state (doc-file rel))]
         (if exists
           {:error nil :warnings (md-warnings @root-dir rel text)}
           {:error (str "no such file: " rel) :warnings []}))
       (catch Exception e {:error (ex-message e) :warnings []})))

(defn- text-save
  "Write {path text base}: only while the file's version is still `base`
  (nil = it must not exist), no live lock is held on it, and the session
  is not an export. Returns [status body]."
  [{:keys [path text base]}]
  (when (embedded/export? (:root @files))
    (throw (ex-info "PNG and SVG sources are read-only" {})))
  (when-not (string? text) (throw (ex-info "text must be a string" {})))
  (let [bytes (.getBytes ^String text "UTF-8")
        _ (when (> (alength bytes) max-doc-bytes) (throw (ex-info "text is over 1 MiB" {})))
        f (doc-file path)]
    (if-let [holder (lock-holder (.getPath f) (System/currentTimeMillis))]
      [409 {:error (str "locked by " holder)}]
      (locking save-monitor
        (let [cur (doc-state f)]
          (if (not= (:version cur) base)
            [409 (assoc cur :error "changed on disk")]
            (do (write-atomically! f bytes)
                [200 {:version (sha1-hex bytes)}])))))))

(defn- save-response
  "The JSON response of a save: (save parsed) on the request's JSON body
  gives [status reply]; a body that is no JSON is 400, a failure
  `fail-status` unless its ex-data names a :status. (logged parsed status
  reply) when given."
  [body-stream save fail-status logged]
  (let [parsed (read-json-body body-stream)
        [status out] (try
                       (if-let [err (:parse-error parsed)]
                         [400 {:error err}]
                         (save parsed))
                       (catch Exception e [(:status (ex-data e) fail-status) {:error (ex-message e)}]))]
    (when (some? logged) (logged parsed status out))
    (json-status-response status out)))

(defn- text-save-response [body-stream]
  (save-response body-stream text-save 400
                 (fn [parsed status out]
                   (log/event! "text-save" {:path (:path parsed) :status status
                                            :result (dissoc out :text)}))))

;; ---- the answers to a deck's questions (?? and ??? slides): beside
;; the deck, so an agent presenting it reads them as they come in

;; one write at a time: the read and the merge must not interleave
(def ^:private answers-monitor (Object.))

(defn- answers-file
  "The canonical answers file of the deck at root-relative `path` (an
  existing .md file): talk.md's is talk.answers.edn beside it."
  [path]
  (when-not (.isFile (doc-file path))
    (throw (ex-info (str "no such file: " path) {})))
  (resolve-path @root-dir (str/replace path #"(?i)\.md$" ".answers.edn") false #{"edn"}))

(defn- read-answers
  "The map in answers file f as written; {} when it is missing or empty.
  Throws (ex-data :status 409) when it holds anything else — no map, not
  UTF-8, more after the map — so a write never replaces what it could
  not read."
  [f]
  (if (.isFile f)
    (let [fail (fn [why] (throw (ex-info (str (.getName f) " " why) {:status 409})))
          text (try (:text (doc-state f))
                    (catch Exception e (fail (str/replace (ex-message e) (str (.getName f) " ") ""))))
          r (java.io.PushbackReader. (java.io.StringReader. text))
          m (try (edn/read {:eof nil} r) (catch Exception _ (fail "does not parse")))]
      (when-not (= ::eof (try (edn/read {:eof ::eof} r) (catch Exception _ ::more)))
        (fail "has content after the map"))
      (cond (nil? m) {}
            (map? m) m
            :else (fail "is not a map")))
    {}))

(defn answers-of
  "The answers in map `m` as the page takes them: name -> {:answer
  :num}, a keyword name as its string; an entry of any other shape is
  left out."
  [m]
  (into {} (keep (fn [[k v]]
                   (let [id (if (keyword? k) (name k) k)]
                     (when (and (string? id) (map? v) (string? (:answer v))
                                (integer? (:num v)) (>= (:num v) 0))
                       [id (select-keys v [:answer :num])])))
                 m)))

(defn- answers-text
  "EDN for the answers map, one entry a line."
  [m]
  (str "{" (str/join "\n " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) m)) "}\n"))

(defn- answers-response-body [query-string]
  (json/generate-string
   (try {:answers (answers-of (read-answers (answers-file (query-param query-string "path"))))}
        (catch Exception e {:error (ex-message e)}))))

(defn- answers-save
  "Merge the answer {path id answer num} into the deck's answers file,
  keeping every other entry as written. [status reply]; the reply is
  every answer now stored."
  [{:keys [path id answer num]}]
  (when (embedded/export? (:root @files))
    (throw (ex-info "PNG and SVG sources are read-only" {})))
  (when-not (and (string? id) (seq id)) (throw (ex-info "id must be a non-empty string" {})))
  (when-not (string? answer) (throw (ex-info "answer must be a string" {})))
  (when-not (and (integer? num) (>= num 0)) (throw (ex-info "num must be a whole number, 0 or more" {})))
  (let [f (answers-file path)]
    (locking answers-monitor
      ;; the name as a keyword is the same question: the new answer replaces it
      (let [m (assoc (dissoc (read-answers f) (keyword id)) id {:answer answer :num num})]
        (write-atomically! f (.getBytes ^String (answers-text m) "UTF-8"))
        [200 {:answers (answers-of m)}]))))

(defn- answers-save-response [body-stream]
  (save-response body-stream answers-save 400
                 (fn [parsed status _]
                   (log/event! "answer" {:path (:path parsed) :id (:id parsed) :status status}))))

;; ---- your choices in the page (theme, vi): one file for every
;; port, so they outlive the server — the page's localStorage did not,
;; being per origin and the port random

(defn prefs-file
  "Where your page choices are kept: prefs.edn in log/home."
  []
  (io/file (log/home) "prefs.edn"))

(def ^:private pref-valid?
  {:theme (set (map name themes/NAMES))
   :vi #{"on"}})

;; one write at a time: the read and the merge must not interleave
(def ^:private prefs-monitor (Object.))

(defn read-prefs
  "Your stored choices, {:theme s :vi s} with the valid ones only; {}
  when prefs are off, the file is missing or unreadable."
  []
  (if @prefs-on
    (let [m (try (edn/read-string (slurp (prefs-file))) (catch Exception _ nil))]
      (if (map? m)
        (into {} (filter (fn [[k v]] (some-> (get pref-valid? k) (contains? v))) m))
        {}))
    {}))

(defn- prefs-save
  "Merge the request's choices into the file: a key with \"\" or null
  forgets it. [status reply]; the reply is every choice now stored."
  [parsed]
  (let [bad (when (map? parsed)
              (first (remove (fn [[k v]] (or (nil? v) (= "" v) (some-> (get pref-valid? k) (contains? v))))
                             parsed)))]
    (cond
      (not (map? parsed)) [400 {:error "expected a JSON object"}]
      bad [400 {:error (str "bad pref: " (name (key bad)) " " (pr-str (val bad)))}]
      (not @prefs-on) [200 {}]
      :else (locking prefs-monitor
              (let [m (reduce (fn [m [k v]] (if (seq v) (assoc m k v) (dissoc m k)))
                              (read-prefs) parsed)]
                (write-atomically! (prefs-file) (.getBytes (pr-str m) "UTF-8"))
                [200 m])))))

(defn- prefs-save-response [body-stream]
  (save-response body-stream prefs-save 500 nil))

(defn- calc-table? [t]
  (and (map? t)
       (sequential? (:cells t))
       (every? (fn [row] (and (sequential? row) (every? string? row))) (:cells t))
       (or (nil? (:defs t)) (string? (:defs t)))
       (or (nil? (:chart t)) (string? (:chart t)))
       (or (nil? (:solo t)) (boolean? (:solo t)))))

(defn- calc-response
  "The formula results of the request's md tables (calc/evaluate-tables),
  in the order they came, the data their blocks link read for them."
  [body-stream]
  (let [parsed (read-json-body body-stream)
        [status out] (cond
                       (:parse-error parsed) [400 {:error (:parse-error parsed)}]
                       (not (and (sequential? (:tables parsed)) (every? calc-table? (:tables parsed))
                                 (or (nil? (:path parsed)) (string? (:path parsed)))))
                       [400 {:error "expected {path: string, tables: [{cells: [[string]], defs: string}]}"}]
                       :else [200 {:tables (calc/evaluate-tables
                                            (calc/with-data (:tables parsed) (data-reader (:path parsed))))}])]
    (json-status-response status out)))

(defn- post-only
  "The response of (f) for a guarded POST; 405 for any other method."
  [{:keys [request-method] :as req} f]
  (if (= :post request-method)
    (or (edit-guard req) (f))
    {:status 405 :headers {"Content-Type" "text/plain"} :body "POST only"}))

;; ---- /api/watch: changes on disk, pushed to the page ----

(defn- graph-version
  "What the graph the query string names is at on disk: its file's mtime
  (\"<old>-<new>\" for a compared pair). A refused file reports a
  constant: the page reloads once and shows the graph route's error."
  [query-string]
  (try
    (if-let [[md n] (analyzer-of (nav-rel query-string))]
      (analyze/stamp (.getCanonicalFile @root-dir) md n)
      (let [{:keys [old new]} (sides (nav-rel query-string)
                                     (query-param query-string "compare"))]
        (if (some? old)
          (str (.lastModified old) "-" (.lastModified new))
          (.lastModified new))))
    (catch Exception _ 0)))

(defn- doc-version
  "What md file `path` is at on disk: mtime and size, nil when missing,
  0 when refused."
  [path]
  (try
    (let [f (doc-file path)]
      (when (.isFile f) [(.lastModified f) (.length f)]))
    (catch Exception _ 0)))

(def ^:private watch-period-ms 200)

(def ^:private max-watch-chars 8192)

(def watches (atom {})) ; channel -> {:graph <query string> :md <path> :seen {kind version}}

(defn- parse-watch
  "What a page asks to watch — {:graph <its graph's query string> :md
  <its doc's root-relative path>}, each a string or nil — or nil when the
  message is not that."
  [text]
  (let [m (when (and (string? text) (<= (count text) max-watch-chars))
            (try (json/parse-string text true) (catch Exception _ nil)))
        ok? (fn [v] (or (nil? v) (string? v)))]
    (when (and (map? m) (ok? (:graph m)) (ok? (:md m)))
      (select-keys m [:graph :md]))))

(defn- watch-versions [{:keys [graph md]}]
  (cond-> {}
    (some? graph) (assoc "graph" (graph-version (str/replace-first graph #"^\?" "")))
    (some? md) (assoc "md" (doc-version md))))

(defn- push-changes!
  "Tell channel `ch` {kind: \"graph\"|\"md\"} for each watched file whose
  version moved since it last heard — everything on its first look, so
  a page that just (re)connected catches up. The page fetches the rest."
  [ch]
  (when-let [w (get @watches ch)]
    (let [now (watch-versions w)
          ;; one look wins: the watcher thread and a new watch set may
          ;; both get here with w, and only the first to record it sends;
          ;; a watch set that arrived meanwhile keeps its own (empty) :seen
          [before] (swap-vals! watches (fn [m] (cond-> m (= (get m ch) w) (assoc-in [ch :seen] now))))]
      (when (= (get before ch) w)
        (doseq [[kind v] now :when (not= v (get (:seen w) kind ::unseen))]
          (srv/send! ch (json/generate-string {:kind kind})))))))

(defonce ^:private watcher (atom nil))

(defn start-watcher!
  "Start the thread that looks at every watched file each
  watch-period-ms — once per process; a daemon, so it never keeps an
  export alive."
  []
  (when (compare-and-set! watcher nil true)
    (doto (Thread. ^Runnable
           (fn []
             (loop []
               (Thread/sleep watch-period-ms)
               (doseq [ch (keys @watches)]
                 (try (push-changes! ch)
                      (catch Throwable e
                        ;; dropped, so a broken watch can't flood the log,
                        ;; and closed, so the page reconnects
                        (swap! watches dissoc ch)
                        (srv/close ch)
                        (log/event! "error" {:watch (or (ex-message e) (.getName (class e)))}))))
               (recur))))
      (.setDaemon true)
      (.start))))

(defn- watch-response
  "Upgrade to the websocket that pushes what changed on disk. Any page
  may open a websocket to localhost — no same-origin rule stops it — so
  the Origin is checked here as for a write."
  [{:keys [headers server-port] :as req}]
  (cond
    (not (:websocket? req)) (text-response 400 "websocket only")
    (not (local-origin? (get headers "origin") server-port)) (text-response 403 "forbidden")
    ;; a watch set that comes in after the close (http-kit runs the two
    ;; on different threads) finds the channel gone and is dropped
    :else (srv/as-channel req {:on-open (fn [ch] (swap! watches assoc ch {}))
                               :on-receive (fn [ch text]
                                             (when-let [w (parse-watch text)]
                                               (when (contains? (swap! watches (fn [m] (cond-> m (contains? m ch) (assoc ch w))))
                                                                ch)
                                                 (push-changes! ch))))
                               :on-close (fn [ch _] (swap! watches dissoc ch))})))

(defn- route [{:keys [uri query-string body] :as req}]
  (case uri
    "/api/graph"   (json-response (json/generate-string (graph-response query-string "/api/graph")))
    "/api/errors"  (json-response (errors-response-body query-string))
    "/api/edit"    (post-only req #(json-response (edit-response-body body)))
    "/api/create"  (post-only req #(json-response (create-response-body body)))
    "/api/copy"    (post-only req #(json-response (copy-response-body body)))
    "/api/graphs"  (json-response (json/generate-string (graphs-response (query-param query-string "path")
                                                                        (query-param query-string "compare"))))
    "/api/lock"    (post-only req #(lock-response acquire-lock! body))
    "/api/unlock"  (post-only req #(lock-response release-lock! body))
    "/api/root"      (json-response (json/generate-string {:path (root-rel) :dir (.getPath @root-dir)}))
    "/api/text"      (json-response (text-response-body query-string))
    "/api/file"      (file-response query-string)
    "/api/text/save" (post-only req #(text-save-response body))
    "/api/calc"      (post-only req #(calc-response body))
    "/api/answers"   (if (= :post (:request-method req))
                       (post-only req #(answers-save-response body))
                       (json-response (answers-response-body query-string)))
    "/api/prefs"     (if (= :post (:request-method req))
                       (post-only req #(prefs-save-response body))
                       (json-response (json/generate-string (read-prefs))))
    "/api/version" (json-response (json/generate-string {:mtime (graph-version query-string)}))
    "/api/watch"   (watch-response req)
    "/api/source"
    (let [which (query-param query-string "which")
          body (try
                 (cond
                   (embedded-compare?)
                   (do (nav-rel query-string)
                       (if (= which "old")
                         (embedded-old (:root @files))
                         (read-source (:root @files))))

                   (some? (analyzer-of (nav-rel query-string)))
                   (when-not (= which "old")
                     (let [[md n] (analyzer-of (nav-rel query-string))]
                       (analyze/graph-text (.getCanonicalFile @root-dir) md n)))

                   :else
                   (let [{:keys [old] :as pair} (sides (nav-rel query-string)
                                                       (query-param query-string "compare"))]
                     (when (or (not= which "old") (some? old))
                       (side-source pair which))))
                 (catch Exception _ nil))]
      (if (some? body)
        (text-response 200 body)
        (text-response 404 "not found")))
    (static-response uri)))

(defn- request-line [req]
  (str (some-> (:request-method req) name str/upper-case) " " (:uri req)))

(defn- logged-body
  "What the debug log keeps of a rejected response's body: a JSON one's
  error alone — the rest may be a doc's whole text (a 409 has it)."
  [{:keys [headers body]}]
  (when (string? body)
    (if (= "application/json" (get headers "Content-Type"))
      (try (get (json/parse-string body) "error") (catch Exception _ nil))
      body)))

(defn guard
  "Wrap a ring handler so an uncaught exception becomes a crash report on
  disk plus a 500 JSON error (with a non-null message, which the browser
  reads as failure), and every rejected request (status >= 400) lands in
  the debug log."
  [h]
  (fn [req]
    (try
      (let [{:keys [status] :as resp} (h req)]
        (when (and (some? status) (>= status 400))
          (log/event! "error" {:request (request-line req) :status status
                               :body (logged-body resp)}))
        resp)
      (catch Throwable e
        (log/crash! {:request (request-line req)} e)
        (json-status-response 500 {:error (or (ex-message e) (.getName (class e)))})))))

(defn- local-host?
  "Does the Host header name this machine's loopback — localhost,
  127.0.0.1 or [::1], on any port (a tunnel may forward another)? A page
  that rebinds its own name to 127.0.0.1 sends that name, which is what
  this refuses. A request without Host passes: browsers always send it."
  [host]
  (or (nil? host)
      (contains? #{"localhost" "127.0.0.1" "[::1]"}
                 (str/lower-case (str/replace host #":\d+$" "")))))

(defn- host-guard
  "Answer only requests addressed to this machine (local-host?), the
  page and its files included: 403 for any other name. No response
  carries a CORS header either, so a page on another origin can't read
  the served files or /api/root's absolute folder."
  [h]
  (fn [req]
    (if (local-host? (get-in req [:headers "host"]))
      (h req)
      (text-response 403 "forbidden: simpleviz answers to localhost and 127.0.0.1 only"))))

(def handler (guard (host-guard route)))

(defn version
  "The release this is: the VERSION resource (the install root or the
  jar), or \"dev\" in a checkout."
  []
  (if-let [r (io/resource "VERSION")]
    (str/trim (slurp r))
    "dev"))

(defn startup-check!
  "Read what serving `file` needs, so a bad file fails at startup with a
  clear message (ex-info {:startup-check true}) instead of an empty page:
  both sides of a graph, or the md file itself."
  [file]
  (try
    (if (paths/md-path? file)
      (when-not (:exists (doc-state (.getCanonicalFile (io/file file))))
        (throw (ex-info (str "no such file: " file) {})))
      (let [{:keys [old new]} (sides nil)]
        (doseq [f (remove nil? [old new])] (read-source (.getPath f)))))
    (catch Exception e
      (throw (ex-info (or (ex-message e) (.getName (class e))) {:startup-check true})))))

(defn start!
  "Serve `file` — compared with its `suffix` fork when one is given — on
  127.0.0.1:`port`. Checks that every side resolves and reads first
  (ex-info with the user-facing message and {:startup-check true}
  otherwise), starts http-kit, then opens the run log:
  java.net.BindException passes through when the port is taken, before
  any log file is written (the CLI retries another port).
  `prefs` true serves your stored choices (/api/prefs); off, as for an
  export, the page starts from the defaults. Everything is served below
  a fresh secret token (session/wrap): `url`, with no trailing slash, is
  the only way in. `on-page` is called on each load of the page.
  Returns {:served <description> :url <url> :log-path <path or nil>}."
  [{:keys [file suffix port debug prefs on-page]}]
  (reset! files {:root file :suffix suffix})
  (reset! prefs-on (boolean prefs))
  (reset! root-dir (.getParentFile (.getCanonicalFile (io/file file))))
  ;; resolve both sides once so a missing fork or an export without
  ;; embedded EDN fails at startup with a clear message instead of an
  ;; empty diagram in the browser
  (startup-check! file)
  (let [served (cond
                 suffix (str file " → " (fork-name file suffix) " (compare)")
                 (embedded-compare?) (str file " (embedded compare)")
                 :else file)
        header (str "simpleviz " (version)
                    " (babashka " (System/getProperty "babashka.version")
                    ") serving " served " on port " port)
        ;; the header only, so a crash report from the bind carries it
        _ (log/init! {:dir (log/default-dir) :debug false :header header})
        token (session/new-token)
        ;; loopback only — /api/edit can write to disk, so the server must
        ;; never be reachable from other hosts on the network; and the
        ;; token, since loopback can't tell this machine's users apart
        _ (srv/run-server (session/wrap handler token {:on-page on-page}) {:port port :ip "127.0.0.1"})
        _ (start-watcher!)
        log-path (log/init! {:dir (log/default-dir) :debug debug :header header})]
    (log/install-crash-handler!)
    {:served served :url (str "http://localhost:" port "/" token) :log-path log-path}))

(defn register!
  "List this server in the registry (session/register!) until the process
  exits, so an agent of yours finds it with `simpleviz servers`. A
  registry that can't be written costs only that."
  [port url]
  (try (let [unregister! (session/register! port {:url url :file (.getPath (.getCanonicalFile (io/file (:root @files))))} {})]
         (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (unregister!)))))
       (catch Exception e
         (binding [*out* *err*]
           (println (str "simpleviz: not listed for simpleviz servers: " (or (ex-message e) (.getName (class e)))))))))

(defn -main [& args]
  (let [{:keys [file suffix port debug error]} (parse-args args)]
    (when error
      (println error)
      (System/exit 1))
    (when-not (.isFile (io/file file))
      (println (str "file not found: " file))
      (System/exit 1))
    (try
      (let [{:keys [served url log-path]} (start! {:file file :suffix suffix :port port :debug debug :prefs true})]
        (register! port url)
        (println (str "simpleviz: serving " served " at " url))
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
