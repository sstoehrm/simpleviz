(ns notices-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [notices]))

(def ^:private sep java.io.File/pathSeparator)

(def ^:private stage "/build/dist/jar-stage")
(def ^:private m2 "/home/u/.m2/repository")

(defn- cp [& entries] (str/join sep entries))

(deftest classpath-libs-names-every-maven-jar-and-skips-the-stage
  ;; malli ships without META-INF/maven metadata, so only the classpath
  ;; shows it is bundled (#96)
  (is (= ["metosin/malli" "org.clojure/tools.reader" "borkdude/edamame"]
         (notices/classpath-libs
          (cp (str stage "/server") (str stage "/res")
              (str m2 "/metosin/malli/0.19.1/malli-0.19.1.jar")
              (str m2 "/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar")
              (str m2 "/borkdude/edamame/1.4.30/edamame-1.4.30.jar"))
          {:stage stage :m2 m2}))))

(deftest classpath-libs-refuses-an-entry-it-cannot-name
  ;; a git dep or a local jar would otherwise be bundled without a notice
  (doseq [entry ["/home/u/.gitlibs/libs/io.github.x/y/abc123/src"
                 "/opt/vendor/thing.jar"
                 (str m2 "/metosin/malli/malli.jar")]]
    (is (thrown-with-msg? Exception
                          (re-pattern (str "can't tell which library " (java.util.regex.Pattern/quote entry)
                                           " is"))
                          (notices/classpath-libs (cp (str stage "/server") entry)
                                                  {:stage stage :m2 m2}))
        entry)))

(deftest metadata-libs-reads-the-jars-maven-metadata
  (is (= ["borkdude/dynaload" "fipp/fipp"]
         (notices/metadata-libs ["cli.clj"
                                 "META-INF/maven/fipp/fipp/pom.properties"
                                 "META-INF/maven/fipp/fipp/pom.xml"
                                 "META-INF/maven/borkdude/dynaload/pom.properties"]))))

(deftest missing-lists-the-libraries-the-notices-do-not-mention
  (is (= ["fipp/fipp" "metosin/malli"]
         (notices/missing ["metosin/malli" "borkdude/dynaload" "fipp/fipp" "metosin/malli"]
                          "| borkdude/dynaload | Eclipse Public License 1.0 |")))
  (is (= [] (notices/missing ["borkdude/dynaload"] "borkdude/dynaload"))))
