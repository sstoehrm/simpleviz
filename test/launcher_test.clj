(ns launcher-test
  "Smoke tests for the launcher heredoc in install.sh, run in isolation
  from the user's real ~/.simpleviz: extract the heredoc text, run it as
  a standalone script with SIMPLEVIZ_HOME pointed at this checkout, and
  never source install.sh itself (that would touch the real install)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [proc-util]))

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

(deftest launcher-runs-the-cli-from-the-callers-folder
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-wrap"})]
    (try
      (spit (str (fs/path tmp "g.edn")) "{:nodes {:a {}}}")
      (let [res (run-launcher script ["check" "g.edn"] :dir tmp)]
        (is (= 0 (:exit res)) (:err res))
        (is (= "ok" (str/trim (:out res)))))
      (finally (fs/delete-tree tmp)))))

(deftest launcher-version-comes-from-the-install
  ;; an install root laid out like the tarball: server/ + the root on the
  ;; classpath, VERSION written by install.sh
  (let [script (write-launcher!)
        home (fs/create-temp-dir {:prefix "simpleviz-home"})]
    (try
      ;; bb refuses absolute :paths, so the install gets its own server/
      (fs/copy-tree (str repo-root "/server") (fs/path home "server"))
      (spit (str (fs/path home "VERSION")) "v1.2.3\n")
      (spit (str (fs/path home "bb.edn"))
            (pr-str {:paths ["server" "."]
                     :deps (:deps (edn/read-string (slurp "bb.edn")))}))
      (let [res (p/shell {:out :string :err :string :continue true
                          :extra-env {"SIMPLEVIZ_HOME" (str home)}}
                         "bash" script "--version")]
        (is (= "simpleviz v1.2.3" (str/trim (:out res))) (:err res)))
      (finally (fs/delete-tree home)))))

(deftest launcher-demo-runs-and-clean-all-stops-it
  (if-not (fs/which "pgrep")
    (is true "pgrep unavailable — case skipped")
    (let [script (write-launcher!)
          proc (proc-util/start ["bash" script "demo" "--no-open"]
                                :env {"SIMPLEVIZ_HOME" repo-root})
          demo-dir (atom nil)]
      (try
        (reset! demo-dir (second (proc-util/await-line proc #"^simpleviz: demo files in (.+)$" 30000)))
        (is (some? @demo-dir))
        (is (some? (proc-util/await-line proc #"^simpleviz: http://localhost:\d+$" 30000)))
        (let [res (run-launcher script ["clean-all"])]
          (is (= 0 (:exit res)) (:err res))
          (is (str/includes? (:out res) "killing")))
        (is (.waitFor ^Process (:proc proc) 5 java.util.concurrent.TimeUnit/SECONDS)
            "clean-all stopped the server")
        (finally
          (p/destroy-tree proc)
          (some-> @demo-dir fs/delete-tree))))))

(defn- stub-bb!
  "A fake `bb` in a fresh folder `name` of `tmp` that reports `user-home`
  as Java's user.home (which the environment can't fake). The CLI run
  (`--config ...`) prints `cli ran` when `fake-cli`, else goes to the
  real bb, like everything else. Returns the folder, for PATH."
  [tmp name user-home & {:keys [fake-cli]}]
  (let [bin (fs/path tmp name)
        real (str (fs/which "bb"))]
    (fs/create-dirs bin)
    (spit (str (fs/path bin "bb"))
          (str "#!/usr/bin/env bash\n"
               "if [ \"${1:-}\" = -e ] && [[ \"${2:-}\" == *user.home* ]]; then printf '%s' '" user-home "'; exit 0; fi\n"
               (when fake-cli "if [ \"${1:-}\" = --config ]; then echo 'cli ran'; exit 0; fi\n")
               "exec '" real "' \"$@\"\n"))
    (.setExecutable (fs/file (fs/path bin "bb")) true)
    (str bin)))

(deftest launcher-refuses-paths-babashka-cannot-load-from
  ;; bb loads nothing from a classpath entry whose path holds a % (#99):
  ;; say so instead of failing with "Could not locate cli.clj"
  (let [script (write-launcher!)
        tmp (fs/create-temp-dir {:prefix "simpleviz-pct"})
        home (fs/path tmp "sim%20viz")
        user-home (str (fs/path tmp "us%er"))]
    (try
      (fs/create-dirs home)
      (let [res (p/shell {:out :string :err :string :continue true
                          :extra-env {"SIMPLEVIZ_HOME" (str home)}}
                         "bash" script "--version")]
        (is (= 1 (:exit res)))
        (is (= (str "simpleviz: " home " contains a %, and babashka can't load code from such a path"
                    " — reinstall with SIMPLEVIZ_HOME set to a path without one")
               (str/trim (:err res)))))
      (let [res (p/shell {:out :string :err :string :continue true
                          :extra-env {"SIMPLEVIZ_HOME" repo-root
                                      "PATH" (str (stub-bb! tmp "pct-bin" user-home) ":" (System/getenv "PATH"))}}
                         "bash" script "--version")]
        (is (= 1 (:exit res)))
        (is (= (str "simpleviz: " user-home "/.m2 contains a %, and babashka can't load code from such a path"
                    " — it keeps simpleviz's libraries there")
               (str/trim (:err res)))))
      ;; the libraries sit under Java's user.home, not $HOME: a % in $HOME
      ;; is fine, and an unset HOME (env -i, some services) must not trip
      ;; set -u. The CLI run is faked; bb's deps tooling would fetch its
      ;; tools into a fresh home first.
      (let [bin (stub-bb! tmp "ok-bin" "/home/fine" :fake-cli true)
            path (str bin ":" (System/getenv "PATH"))]
        (doseq [env [{"SIMPLEVIZ_HOME" repo-root "PATH" path "HOME" (str tmp "/h%me")}
                     {"SIMPLEVIZ_HOME" repo-root "PATH" path}]]
          (let [res (p/shell {:out :string :err :string :continue true :dir (str tmp) :env env}
                             "bash" script "--version")]
            (is (= 0 (:exit res)) (str (keys env) (:err res)))
            (is (= "cli ran" (str/trim (:out res)))))))
      (finally (fs/delete-tree tmp)))))
