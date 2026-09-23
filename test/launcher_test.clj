(ns launcher-test
  "Smoke tests for the launcher heredoc in install.sh, run in isolation
  from the user's real ~/.simpleviz: extract the heredoc text, run it as
  a standalone script with SIMPLEVIZ_HOME pointed at this checkout, and
  never source install.sh itself (that would touch the real install)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- launcher-script
  "The launcher heredoc body (between <<'LAUNCHER' and the closing
  LAUNCHER line) extracted from install.sh."
  []
  (let [lines (vec (str/split-lines (slurp "install.sh")))
        start (inc (first (keep-indexed (fn [i l] (when (str/includes? l "<<'LAUNCHER'") i)) lines)))
        rel-end (first (keep-indexed (fn [i l] (when (= (str/trim l) "LAUNCHER") i)) (subvec lines start)))]
    (str/join "\n" (subvec lines start (+ start rel-end)))))

(defn- write-launcher!
  "Write the extracted launcher to a fresh, executable temp file (under
  the system temp dir, never under $HOME) and return its path."
  []
  (let [f (fs/file (fs/create-temp-file {:prefix "simpleviz-launcher" :suffix ".sh"}))]
    (.deleteOnExit f)
    (spit f (launcher-script))
    (.setExecutable f true)
    (.getPath f)))

(def ^:private repo-root (System/getProperty "user.dir"))

(defn- run-cmd
  "Run an arbitrary command line (a vector of tokens, e.g. `timeout`
  wrapping the launcher), with SIMPLEVIZ_HOME set to this checkout
  (never the user's real ~/.simpleviz) and optionally in `dir`."
  [cmd & {:keys [dir]}]
  (select-keys
   (apply p/shell (cond-> {:out :string :err :string :continue true
                            :extra-env {"SIMPLEVIZ_HOME" repo-root}}
                    (some? dir) (assoc :dir (str dir)))
          cmd)
   [:out :err :exit]))

(defn- run-launcher
  "Run the launcher script with the given args; see run-cmd."
  [script args & {:keys [dir]}]
  (run-cmd (into ["bash" script] args) :dir dir))

(deftest launcher-two-file-form-is-rejected
  (let [script (write-launcher!)
        res (run-launcher script ["examples/demo.edn" "examples/demo-next.edn"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "two-file compare was replaced"))))

(deftest launcher-missing-fork-names-the-fork
  (let [script (write-launcher!)
        res (run-launcher script ["examples/demo.edn" "nope"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "demo-nope.edn not found — create it with: simpleviz fork"))))

(deftest launcher-rejects-invalid-suffix-charset
  (let [script (write-launcher!)
        res (run-launcher script ["examples/demo.edn" "a/b"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "invalid suffix: a/b"))))

(deftest launcher-directory-named-like-suffix-does-not-hijack-the-argument
  ;; a `next/` dir sitting next to the graph file used to make the
  ;; filesystem-probe-based parsing in serve() mistake "next" for the
  ;; (removed) two-file form; it must fall through to suffix handling.
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-launcher-test"})]
    (try
      (fs/copy "examples/demo.edn" (fs/path tmp "demo.edn"))
      (fs/copy "examples/demo-next.edn" (fs/path tmp "demo-next.edn"))
      (fs/copy-tree "examples/api" (fs/path tmp "api"))
      (fs/create-dir (fs/path tmp "next"))
      (if (fs/which "timeout")
        (let [res (run-cmd ["timeout" "3" "bash" script "demo.edn" "next"] :dir tmp)]
          ;; started (and got killed by timeout: exit 124) or exited on
          ;; its own for some other reason — either way, never the
          ;; removed two-file message
          (is (not (str/includes? (:err res) "two-file compare"))))
        (is true "timeout(1) unavailable on this system — case skipped"))
      (finally (fs/delete-tree tmp)))))

(deftest launcher-fork-rejects-wrong-arg-count
  (let [script (write-launcher!)
        res (run-launcher script ["fork" "examples/demo.edn"])]
    (is (= 1 (:exit res)))
    (is (str/includes? (:err res) "usage:"))))

(deftest launcher-update-runs-the-new-release-installer-not-the-stored-one
  ;; a stored installer writes the launcher embedded in *itself*, so
  ;; re-running it on update would pin the launcher to the old release
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-update"})
        home (fs/path tmp "home")
        bin (fs/path tmp "bin")
        marker (fs/path tmp "ran")]
    (try
      (fs/create-dirs home)
      (fs/create-dirs bin)
      (spit (str (fs/path home "VERSION")) "v0.0.1\n")
      (spit (str (fs/path home "install.sh")) (str "echo stored >'" marker "'\n"))
      ;; fake curl: answers the release query and serves a tagged installer
      (spit (str (fs/path bin "curl"))
            (str "#!/usr/bin/env bash\n"
                 "out=\"\"; url=\"\"\n"
                 "while [ \"$#\" -gt 0 ]; do\n"
                 "  case \"$1\" in -o) out=\"$2\"; shift ;; -*) ;; *) url=\"$1\" ;; esac; shift\n"
                 "done\n"
                 "case \"$url\" in\n"
                 "  */releases/latest) body='\"tag_name\": \"v9.9.9\"' ;;\n"
                 "  */v9.9.9/install.sh) body=\"echo tagged >'" marker "'\" ;;\n"
                 "  *) exit 22 ;;\n"
                 "esac\n"
                 "if [ -n \"$out\" ]; then echo \"$body\" >\"$out\"; else echo \"$body\"; fi\n"))
      (.setExecutable (fs/file (fs/path bin "curl")) true)
      (let [res (p/shell {:out :string :err :string :continue true
                          :extra-env {"SIMPLEVIZ_HOME" (str home)
                                      "PATH" (str bin ":" (System/getenv "PATH"))}}
                         "bash" script "update")]
        (is (= 0 (:exit res)) (:err res))
        (is (= "tagged" (str/trim (slurp (str marker))))))
      (finally (fs/delete-tree tmp)))))

(deftest launcher-check-exits-0-on-a-clean-graph
  (let [script (write-launcher!)
        res (run-launcher script ["check" "examples/demo.edn"])]
    (is (= 0 (:exit res)) (:out res))
    (is (= "ok" (str/trim (:out res))))))

(deftest launcher-check-exits-1-and-prints-each-problem
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-check"})]
    (try
      (spit (str (fs/path tmp "warn.edn")) "{:nodes {:a {}} :edges {[:a :zz] {}} :boxes {:b {:components #{:nope}}}}")
      (spit (str (fs/path tmp "broken.edn")) "{:nodes {:a {}")
      (let [res (run-launcher script ["check" "warn.edn"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (= 2 (count (filter #(str/starts-with? % "warning: ") (str/split-lines (:out res))))))
        (is (str/includes? (:out res) "zz")))
      (let [res (run-launcher script ["check" "broken.edn"] :dir tmp)]
        (is (= 1 (:exit res)))
        (is (str/starts-with? (:out res) "error: ")))
      (finally (fs/delete-tree tmp)))))

(deftest launcher-check-rejects-wrong-arg-count
  (let [script (write-launcher!)]
    (doseq [args [["check"] ["check" "a.edn" "b.edn"]]]
      (let [res (run-launcher script args)]
        (is (= 1 (:exit res)))
        (is (str/includes? (:err res) "usage:"))))))

(deftest launcher-check-takes-a-file-name-starting-with-a-dash
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-check"})]
    (try
      (spit (str (fs/path tmp "-g.edn")) "{:nodes {:a {}}}")
      (let [res (run-launcher script ["check" "-g.edn"] :dir tmp)]
        (is (= 0 (:exit res)) (str (:out res) (:err res)))
        (is (= "ok" (str/trim (:out res)))))
      (finally (fs/delete-tree tmp)))))
