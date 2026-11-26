(ns tarball
  "simpleviz tar: pack a graph or md file and every file it uses into one
  archive, below a folder named after it, so it opens anywhere as it
  opens here. A graph uses the files its refs and pairs name; an md
  file the files its links, pictures and slide sides name and the data
  of its table and chart blocks; a link that compares uses the forks it
  compares with, its refs' forks too."
  (:require [calc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deck]
            [fork]
            [mdlinks]
            [paths]
            [serve])
  (:import [java.util.zip GZIPOutputStream]))

;; ---------------------------------------------------------------- the files

(defn- md-uses
  "What md `text` names, as written: [{:dest :compare :data}] — its links
  and pictures (a reference use counts at its definition), its slides'
  side targets, when a slide can show them, and the data its
  simpleviz-table and simpleviz-chart blocks link (:data true).
  Destinations with a scheme or an absolute path are left out."
  [text]
  (->> (concat
        (for [{:keys [kind] :as l} (mdlinks/links text) :when (not= kind "ref")]
          {:dest (:dest l) :compare (:compare l)})
        (for [s (deck/slides text) :when (some? (some-> (:side s) mdlinks/side-kind))]
          {:dest (:side s) :compare (:side-compare s)})
        (for [{:keys [info body]} (mdlinks/fences text)
              :when (#{"simpleviz-table" "simpleviz-chart"} info)
              :let [src (try (:source (calc/split-block (str/join "\n" body)))
                             (catch Exception _ nil))]
              :when (some? src)]
          {:dest src :data true}))
       (remove (fn [{:keys [dest]}]
                 (or (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" dest) (str/starts-with? dest "/"))))
       distinct))

(defn- graph-path? [rel]
  (contains? #{"edn" "png" "svg"} (some-> (re-find #"\.([^./]+)$" (str/lower-case rel)) second)))

(defn- file-in
  "The regular file root-relative `rel` names below `root`, :outside when
  it leads out of it (symlinks resolved), nil when there is none."
  [root rel]
  (let [root-c (.getCanonicalFile (io/file root))
        f (.getCanonicalFile (io/file root-c rel))]
    (cond (not (str/starts-with? (.getPath f) (str (.getPath root-c) java.io.File/separator))) :outside
          (.isFile f) f)))

(defn files
  "Root-relative paths of `start` (a file directly in folder `root`) and of
  every file it uses, transitively: `start` first, then depth-first, each
  once. A link out of `root` or to no file is reported to `warn!` and
  skipped; a graph that doesn't parse is packed, its links unread."
  [root start warn!]
  (let [seen (atom #{})
        out (atom [])]
    (letfn [(follow! [from dest suffix data?]
              (let [target (paths/resolve-ref from dest)
                    f (some->> target (file-in root))]
                (cond
                  (or (nil? target) (= f :outside))
                  (warn! (str from ": link " (pr-str dest) " leaves the folder, skipped"))
                  (nil? f) (warn! (str from ": link " (pr-str dest) ": not found, skipped"))
                  :else (visit! target suffix data?))))
            (visit! [rel suffix data?]
              (when-not (contains? @seen [rel suffix data?])
                (swap! seen conj [rel suffix data?])
                (when-not (some #{rel} @out) (swap! out conj rel))
                (cond
                  ;; data names no files, even an .edn one
                  data? nil

                  (paths/md-path? rel)
                  (doseq [{:keys [dest compare data]} (md-uses (slurp (io/file root rel)))]
                    (follow! rel dest compare (boolean data)))

                  (graph-path? rel)
                  (let [text (try (serve/read-source (.getPath (io/file root rel)))
                                  ;; a png or svg without simpleviz EDN is a picture
                                  (catch Exception _ nil))
                        targets (when (some? text)
                                  (try (fork/ref-targets text)
                                       (catch Exception e
                                         (warn! (str rel ": its links can't be read: " (ex-message e)))
                                         nil)))]
                    (doseq [t targets] (follow! rel t suffix false))
                    ;; a comparison shows each graph beside its fork
                    (when (some? suffix)
                      (let [fk (serve/fork-name rel suffix)]
                        (when (instance? java.io.File (file-in root fk))
                          (visit! fk suffix false))))))))]
      (visit! start nil false)
      @out)))

;; ---------------------------------------------------------------- the archive

(defn- octal
  "n in octal, zero-padded to `width` digits."
  [n width]
  (let [s (Long/toOctalString n)]
    (when (> (count s) width) (throw (ex-info (str n " doesn't fit a tar header") {})))
    (str (apply str (repeat (- width (count s)) "0")) s)))

(defn- utf8-count [^String s] (alength (.getBytes s "UTF-8")))

(defn- split-name
  "[prefix name] of archive path `path` as a ustar header holds it: the
  name up to 100 bytes, a longer path split at a / into a prefix of up
  to 155 and a name of up to 100."
  [path]
  (if (<= (utf8-count path) 100)
    [nil path]
    (or (some (fn [i]
                (let [prefix (subs path 0 i) nm (subs path (inc i))]
                  (when (and (<= (utf8-count prefix) 155) (<= (utf8-count nm) 100) (seq nm))
                    [prefix nm])))
              (reverse (keep-indexed (fn [i c] (when (= c \/) i)) path)))
        (throw (ex-info (str path " is too long for a tar archive") {})))))

(defn- header
  "The 512-byte ustar header of a regular file at `path`."
  [path size mtime]
  (let [h (byte-array 512)
        put! (fn [off ^String s]
               (let [b (.getBytes s "UTF-8")]
                 (System/arraycopy b 0 h off (alength b))))
        [prefix nm] (split-name path)]
    (put! 0 nm)
    (put! 100 "0000644\u0000")
    (put! 108 "0000000\u0000")
    (put! 116 "0000000\u0000")
    (put! 124 (str (octal size 11) "\u0000"))
    (put! 136 (str (octal mtime 11) "\u0000"))
    (put! 148 "        ")
    (put! 156 "0")
    (put! 257 "ustar\u0000")
    (put! 263 "00")
    (when prefix (put! 345 prefix))
    (put! 148 (str (octal (reduce + (map #(bit-and % 0xff) h)) 6) "\u0000 "))
    h))

(defn gzip? [out] (boolean (re-find #"(?i)\.(tar\.gz|tgz)$" (str out))))

(defn write!
  "Write the files at root-relative `rels` under `root` to the archive
  `out` (gzipped unless it ends in .tar), each below folder `top`. A
  failed write leaves no archive."
  [out root rels top]
  (let [entries (mapv (fn [rel]
                        (let [f (io/file root rel)]
                          {:path (str top "/" rel) :file f}))
                      rels)]
    ;; every header fits before anything is written
    (doseq [{:keys [path]} entries] (split-name path))
    (try
      (with-open [os (let [raw (io/output-stream (io/file out))]
                       (if (gzip? out) (GZIPOutputStream. raw) raw))]
        (doseq [{:keys [path ^java.io.File file]} entries]
          ;; read whole, so the size in the header is what follows it
          (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))
                size (alength bytes)]
            (.write os ^bytes (header path size (quot (.lastModified file) 1000)))
            (.write os bytes)
            (.write os (byte-array (mod (- 512 (mod size 512)) 512)))))
        (.write os (byte-array 1024)))
      (catch Exception e
        (io/delete-file (io/file out) true)
        (throw e)))))
