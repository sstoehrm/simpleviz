(ns stage-jars
  "Stages the jars of a resolved classpath for Syft, for stacks with no
  SBOM tool of their own (bb.edn, deps.edn, project.clj):

    bb tools/stage_jars.clj [--m2 ~/.m2/repository] <out-dir> \"$(bb -e '(print (babashka.classpath/get-classpath))')\"

  Syft names a jar from the Maven metadata inside it; a jar built without
  any (malli, for one) would vanish from the SBOM. Such a jar's copy gets a
  pom.properties with the coordinates of its path in the Maven
  repository. Needs nothing beyond babashka's built-in libraries."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.nio.file FileSystems Files]
           [java.util.zip ZipFile]))

(defn split-classpath [cp]
  (vec (remove str/blank? (str/split (str/trim cp) (re-pattern java.io.File/pathSeparator)))))

(defn coordinates
  "{:group :artifact :version} of a jar at `<m2>/<group path>/<artifact>/<version>/<artifact>-<version>[-classifier].jar`."
  [m2 jar]
  (let [root (fs/normalize (fs/absolutize m2))
        path (fs/normalize (fs/absolutize jar))]
    (when (str/starts-with? (str path) (str root fs/file-separator))
      (let [parts (mapv str (fs/components (fs/relativize root path)))
            n (count parts)]
        (when (>= n 4)
          (let [[artifact version file] (subvec parts (- n 3))]
            (when (str/starts-with? file (str artifact "-" version))
              {:group (str/join "." (subvec parts 0 (- n 3)))
               :artifact artifact
               :version version})))))))

(defn- maven-metadata? [jar]
  (with-open [z (ZipFile. (str jar))]
    (boolean (some #(re-matches #"META-INF/maven/.+/pom\.properties" (.getName %))
                   (enumeration-seq (.entries z))))))

(defn- describe!
  "Writes a manifest (Syft reads only jars that have one) and a
  pom.properties into `jar`."
  [jar {:keys [group artifact version]}]
  (with-open [zfs (FileSystems/newFileSystem (fs/path jar))]
    (let [manifest (.getPath zfs "META-INF/MANIFEST.MF" (make-array String 0))
          props (.getPath zfs (str "META-INF/maven/" group "/" artifact "/pom.properties") (make-array String 0))]
      (Files/createDirectories (.getParent props) (make-array java.nio.file.attribute.FileAttribute 0))
      (when-not (Files/exists manifest (make-array java.nio.file.LinkOption 0))
        (Files/writeString manifest "Manifest-Version: 1.0\n" (make-array java.nio.file.OpenOption 0)))
      (Files/writeString props (str "groupId=" group "\nartifactId=" artifact "\nversion=" version "\n")
                         (make-array java.nio.file.OpenOption 0)))))

(defn stage!
  "Copies each jar of `entries` into `out`, adding Maven metadata where it
  lacks any. Returns the staged copies, the jars it :described, the
  :unknown ones it could not and the :dirs it left out."
  [entries out {:keys [m2]}]
  (reduce
   (fn [acc entry]
     (cond
       (fs/directory? entry) (update acc :dirs conj entry)
       (not (and (fs/regular-file? entry) (str/ends-with? entry ".jar"))) acc
       :else
       (let [coords (coordinates m2 entry)
             target (fs/path out (if coords
                                   (str (fs/relativize (fs/absolutize m2) (fs/absolutize entry)))
                                   (fs/file-name entry)))
             bare? (not (maven-metadata? entry))]
         (fs/create-dirs (fs/parent target))
         (fs/copy entry target {:replace-existing true})
         (when (and bare? coords) (describe! target coords))
         (cond-> (update acc :staged conj (str target))
           (and bare? coords) (update :described conj entry)
           (and bare? (not coords)) (update :unknown conj entry)))))
   {:staged [] :described [] :unknown [] :dirs []}
   entries))

(defn -main [& args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec {:m2 {:default (str (fs/path (fs/home) ".m2" "repository"))}}})
        [out cp] args]
    (when-not (and out cp)
      (binding [*out* *err*]
        (println "usage: bb tools/stage_jars.clj [--m2 dir] <out-dir> <classpath>"))
      (System/exit 2))
    (fs/create-dirs out)
    (let [{:keys [staged described unknown dirs]} (stage! (split-classpath cp) out opts)
          cwd (fs/canonicalize ".")]
      (println (str "staged " (count staged) " jars in " out))
      (doseq [j described] (println "  added Maven metadata to" (str (fs/file-name j))))
      (doseq [j unknown] (println "  WARNING: no Maven metadata, Syft may not identify" j))
      ;; source folders outside the project: git deps, local roots
      (doseq [d dirs :when (not (fs/starts-with? (fs/canonicalize d) cwd))]
        (println "  WARNING: not in the SBOM, a source folder:" d)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
