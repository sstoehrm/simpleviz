(ns log-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.fs :as fs]
            [log]))

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [t]
    (let [d (str (fs/create-temp-dir {:prefix "simpleviz-log-test"}))]
      (log/clear!)
      (binding [*dir* d]
        (try (t)
             (finally (log/clear!) (fs/delete-tree d)))))))

(defn- log-files [] (map str (fs/glob *dir* "simpleviz-*.log")))

(deftest event-appends-timestamped-line-with-kind-and-data
  (let [path (log/init! {:dir *dir* :debug true :header "hdr"})]
    (log/event! "edit" {:file "g.edn" :ops [{:op "delete"}] :result {:ok true}})
    (let [line (second (clojure.string/split-lines (slurp path)))]
      (is (re-find #"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d" line))
      (is (re-find #" edit " line))
      (is (re-find #"\"ops\":\[\{\"op\":\"delete\"\}\]" line))
      (is (re-find #"\"result\":\{\"ok\":true\}" line)))))

(deftest event-writes-nothing-when-debug-is-off
  (is (nil? (log/init! {:dir *dir* :debug false :header "hdr"})))
  (log/event! "edit" {:file "g.edn"})
  (is (empty? (log-files))))

(deftest crash-writes-report-even-when-debug-is-off
  (log/init! {:dir *dir* :debug false :header "simpleviz v9 serving g.edn"})
  (let [path (log/crash! {:request "POST /api/edit"}
                         (try (throw (ex-info "boom" {:detail 42})) (catch Exception e e)))
        text (slurp path)]
    (is (= [path] (map str (fs/glob *dir* "crash-*.log"))))
    (is (re-find #"simpleviz v9 serving g\.edn" text) "header names version and files")
    (is (re-find #"POST /api/edit" text) "context is included")
    (is (re-find #"boom" text) "exception message is included")
    (is (re-find #":detail 42" text) "exception data is included")
    (is (re-find #"\n\tat " text) "stack trace is included")))

(deftest crash-is-also-noted-in-the-run-log
  (let [run (log/init! {:dir *dir* :debug true :header "hdr"})]
    (log/crash! {:request "GET /x"} (try (throw (ex-info "boom" {})) (catch Exception e e)))
    (is (re-find #" crash .*boom" (slurp run)))))

(deftest init-with-debug-creates-log-file-with-header
  (let [path (log/init! {:dir *dir* :debug true :header "simpleviz v9 serving g.edn"})]
    (is (= [path] (log-files)))
    (is (re-find #"simpleviz v9 serving g\.edn" (slurp path)))))

(deftest two-crashes-in-quick-succession-keep-both-reports
  (log/init! {:dir *dir* :debug false :header "hdr"})
  (let [boom (fn [m] (try (throw (ex-info m {})) (catch Exception e e)))]
    (log/crash! {:n 1} (boom "first"))
    (log/crash! {:n 2} (boom "second"))
    (let [texts (map (comp slurp str) (fs/glob *dir* "crash-*.log"))]
      (is (some #(re-find #"first" %) texts))
      (is (some #(re-find #"second" %) texts)))))

(deftest unwritable-log-dir-does-not-throw
  (log/init! {:dir (str (fs/path *dir* "not-a-dir-but-a-file" "logs")) :debug false :header "hdr"})
  (spit (str (fs/path *dir* "not-a-dir-but-a-file")) "occupied")
  (is (nil? (log/crash! {:n 1} (try (throw (ex-info "boom" {})) (catch Exception e e)))))
  (is (nil? (log/event! "edit" {:x 1}))))
