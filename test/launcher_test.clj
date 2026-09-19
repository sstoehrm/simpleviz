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
