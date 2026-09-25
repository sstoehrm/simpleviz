(ns notices
  "The release jar's notices check: every library `bb jar` packs into
  the jar must be named (as group/artifact) in THIRD-PARTY-NOTICES.md.
  Two sources name them: the jar's META-INF/maven metadata, and the jar
  stage's resolved classpath, which also shows the libraries that ship
  without that metadata, such as malli (#96)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn metadata-libs
  "group/artifact of every META-INF/maven/<group>/<artifact>/pom.properties
  among a jar's entry names, distinct and sorted. The classpath names the
  dependencies; this still catches a library bundled inside one of them."
  [entry-names]
  (->> entry-names
       (keep #(second (re-matches #"META-INF/maven/([^/]+/[^/]+)/pom\.properties" %)))
       distinct sort vec))

(defn- maven-lib
  "group/artifact of a jar at <m2>/<group dirs>/<artifact>/<version>/
  <artifact>-<version>.jar, nil for any other path."
  [m2 entry]
  (when (fs/starts-with? entry m2)
    (let [parts (mapv str (fs/components (fs/relativize m2 entry)))
          [artifact version file] (take-last 3 parts)
          group (drop-last 3 parts)]
      (when (and (seq group) (= file (str artifact "-" version ".jar")))
        (str (str/join "." group) "/" artifact)))))

(defn classpath-libs
  "group/artifact of every library on classpath `cp` (entries joined by
  the path separator), in classpath order. Entries under `stage`, the
  jar stage's own folders, are skipped. Anything that isn't a jar in the
  Maven repository `m2` throws: a kind of dependency this check can't
  name must not reach the jar unnoticed."
  [cp {:keys [stage m2]}]
  (->> (str/split (str/trim cp) (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
       (remove str/blank?)
       (remove #(fs/starts-with? % stage))
       (mapv (fn [entry]
               (or (maven-lib m2 entry)
                   (throw (ex-info (str "can't tell which library " entry " is: the notices check"
                                        " only knows jars in " m2)
                                   {:entry entry})))))))

(defn missing
  "The libraries in `libs` that `notices` (the notices file's text) does
  not mention, distinct and sorted."
  [libs notices]
  (->> libs distinct (remove #(str/includes? notices %)) sort vec))
