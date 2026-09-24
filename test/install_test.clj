(ns install-test
  "install.sh's install_files against local tarballs. Sources install.sh
  with SIMPLEVIZ_INSTALL_SOURCED=1 (so main does not run) and with HOME,
  SIMPLEVIZ_HOME and SIMPLEVIZ_BIN all pointing into a temp dir, so the
  user's real ~/.simpleviz and ~/.local/bin/simpleviz are never touched."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [proc-util]))

(defn- tarball!
  "Write <tmp>/bundle.tar.gz holding one top-level folder
  simpleviz-v0.0.1 with `files` (relative path -> content); its path."
  [tmp files]
  (let [src (fs/path tmp "src")
        top (fs/path src "simpleviz-v0.0.1")]
    (doseq [[rel content] files]
      (let [f (fs/path top rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) content)))
    (p/shell {:dir (str src)} "tar" "czf" "../bundle.tar.gz" "simpleviz-v0.0.1")
    (str (fs/path tmp "bundle.tar.gz"))))

(defn- install-files
  "Run install.sh's install_files for tag v0.0.1 and `tarball`, with
  every install location under `tmp`; {:out :err :exit}."
  [tmp tarball]
  (select-keys
   (p/shell {:out :string :err :string :continue true
             :extra-env {"HOME" (str (fs/path tmp "user-home"))
                         "SIMPLEVIZ_INSTALL_SOURCED" "1"
                         "SIMPLEVIZ_HOME" (str (fs/path tmp "home"))
                         "SIMPLEVIZ_BIN" (str (fs/path tmp "bin"))
                         "TAG" "v0.0.1"
                         "TARBALL_URL" (str "file://" tarball)}}
            "bash" "-c" (str "source '" proc-util/repo-root "/install.sh' && install_files"))
   [:out :err :exit]))

(deftest install-refuses-a-release-without-the-cli
  ;; install.sh on main installs the latest release: one from before the
  ;; CLI would leave a launcher that execs a namespace it doesn't have
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "VERSION")) "v0.0.0\n")
      (let [res (install-files tmp (tarball! tmp {"server/serve.clj" "(ns serve)\n"}))]
        (is (= 1 (:exit res)) (:out res))
        (is (= (str "install: release v0.0.1 predates this installer — install it with its own: "
                    "curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/v0.0.1/install.sh | bash")
               (str/trim (:err res)))))
      (is (= ["VERSION"] (map (comp str fs/file-name) (fs/list-dir home)))
          "the existing install is untouched")
      (is (= "v0.0.0\n" (slurp (str (fs/path home "VERSION")))))
      (finally (fs/delete-tree tmp)))))

(deftest install-copies-a-release-with-the-cli
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")]
    (try
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"
                                                  "bb.edn" "{:paths [\"server\" \".\"]}\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (= "(ns cli)\n" (slurp (str (fs/path home "server" "cli.clj")))))
      (is (fs/exists? (fs/path home "bb.edn")))
      (is (= "v0.0.1\n" (slurp (str (fs/path home "VERSION")))))
      (is (not (fs/exists? (fs/path tmp "bin"))) "install_files leaves the launcher alone")
      (finally (fs/delete-tree tmp)))))
