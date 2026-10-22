(ns stage-jars-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [stage-jars])
  (:import [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- make-jar [path entries]
  (fs/create-dirs (fs/parent path))
  (with-open [z (ZipOutputStream. (java.io.FileOutputStream. (str path)))]
    (doseq [[name content] entries]
      (.putNextEntry z (ZipEntry. ^String name))
      (.write z (.getBytes ^String content))
      (.closeEntry z)))
  (str path))

(defn- entries [jar]
  (with-open [z (ZipFile. (str jar))]
    (into {} (for [e (enumeration-seq (.entries z))]
               [(.getName e) (slurp (.getInputStream z e))]))))

(defn- m2-with-jars []
  (let [m2 (fs/create-temp-dir {:prefix "stage-jars-m2"})]
    {:m2 (str m2)
     :bare (make-jar (fs/path m2 "metosin/malli/0.20.2/malli-0.20.2.jar")
                     {"malli/core.clj" "(ns malli.core)"})
     :described (make-jar (fs/path m2 "juji/editscript/0.8.0/editscript-0.8.0.jar")
                          {"META-INF/MANIFEST.MF" "Manifest-Version: 1.0\n"
                           "META-INF/maven/juji/editscript/pom.properties"
                           "groupId=juji\nartifactId=editscript\nversion=0.8.0\n"
                           "editscript/core.cljc" "(ns editscript.core)"})}))

(deftest coordinates-come-from-the-maven-repository-layout
  (is (= {:group "org.clojure" :artifact "tools.reader" :version "1.5.2"}
         (stage-jars/coordinates "/r" "/r/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar")))
  (is (nil? (stage-jars/coordinates "/r" "/elsewhere/foo-1.0.jar")) "outside the repository")
  (is (nil? (stage-jars/coordinates "/r" "/r/foo/1.0/foo-1.0.jar")) "no group")
  (is (nil? (stage-jars/coordinates "/r" "/r/a/foo/1.0/bar-1.0.jar")) "the file is not artifact-version"))

(deftest a-jar-without-maven-metadata-gets-it-from-its-path
  (let [{:keys [m2 bare]} (m2-with-jars)
        out (fs/create-temp-dir)
        result (stage-jars/stage! [bare] (str out) {:m2 m2})
        staged (first (:staged result))
        es (entries staged)]
    (is (= [bare] (:described result)))
    (is (= "(ns malli.core)" (es "malli/core.clj")) "the original content stays")
    (is (str/includes? (es "META-INF/maven/metosin/malli/pom.properties") "groupId=metosin\nartifactId=malli\nversion=0.20.2"))
    (is (str/starts-with? (es "META-INF/MANIFEST.MF") "Manifest-Version: 1.0"))
    (is (= {"malli/core.clj" "(ns malli.core)"} (entries bare)) "the repository's jar is untouched")))

(deftest a-jar-with-maven-metadata-is-copied-as-it-is
  (let [{:keys [m2 described]} (m2-with-jars)
        out (fs/create-temp-dir)
        result (stage-jars/stage! [described] (str out) {:m2 m2})]
    (is (= [] (:described result)))
    (is (= (entries described) (entries (first (:staged result)))))))

(deftest directories-and-unknown-jars-are-reported-not-staged
  (let [{:keys [m2 bare]} (m2-with-jars)
        out (fs/create-temp-dir)
        stray (make-jar (fs/path (fs/create-temp-dir) "stray.jar") {"a.clj" "(ns a)"})
        dir (str (fs/create-temp-dir))
        result (stage-jars/stage! [bare stray dir "/no/such/path.jar"] (str out) {:m2 m2})]
    (is (= 2 (count (:staged result))))
    (is (= [stray] (:unknown result)))
    (is (= [dir] (:dirs result)))))

(deftest the-classpath-splits-on-the-path-separator
  (is (= ["a.jar" "b" "c.jar"]
         (stage-jars/split-classpath (str "a.jar" java.io.File/pathSeparator "b" java.io.File/pathSeparator "c.jar\n")))))
