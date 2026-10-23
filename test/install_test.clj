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

(defn- sha256
  "The hex SHA-256 of the file at `path`."
  [path]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (fs/read-all-bytes path))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- install-files
  "Run install.sh's install_files for tag v0.0.1 and `tarball`, with
  every install location under `tmp`; {:out :err :exit}. The release's
  digest is the tarball's own unless `digest` says otherwise. curl is a
  stub that copies the tarball: install.sh's curl takes https only."
  [tmp tarball & {:keys [digest] :or {digest (str "sha256:" (sha256 tarball))}}]
  (let [stub-bin (fs/path tmp "stub-curl")]
    (fs/create-dirs stub-bin)
    (spit (str (fs/path stub-bin "curl"))
          (str "#!/usr/bin/env bash\n"
               "out=\"\"\n"
               "while [ \"$#\" -gt 0 ]; do case \"$1\" in -o) out=\"$2\"; shift ;; esac; shift; done\n"
               "cp '" tarball "' \"$out\"\n"))
    (.setExecutable (fs/file (fs/path stub-bin "curl")) true)
    (select-keys
     (p/shell {:out :string :err :string :continue true
               :extra-env {"HOME" (str (fs/path tmp "user-home"))
                           "PATH" (str stub-bin ":" (System/getenv "PATH"))
                           "SIMPLEVIZ_INSTALL_SOURCED" "1"
                           "SIMPLEVIZ_HOME" (str (fs/path tmp "home"))
                           "SIMPLEVIZ_BIN" (str (fs/path tmp "bin"))
                           "TAG" "v0.0.1"
                           "TARBALL_URL" "https://example.invalid/simpleviz-v0.0.1.tar.gz"
                           "TARBALL_DIGEST" digest}}
              "bash" "-c" (str "source '" proc-util/repo-root "/install.sh' && install_files"))
     [:out :err :exit])))

(deftest fetch-release-takes-the-tarballs-own-digest
  ;; the jar listed first, as GitHub may: its digest must not be taken
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        stub-bin (fs/path tmp "stub-curl")
        json (str "{\"tag_name\": \"v1.2.3\", \"assets\": ["
                  "{\"name\": \"simpleviz.jar\", \"digest\": \"sha256:" (apply str (repeat 64 "a")) "\","
                  " \"browser_download_url\": \"https://example.invalid/simpleviz.jar\"},"
                  "{\"name\": \"simpleviz-v1.2.3.tar.gz\", \"digest\": \"sha256:" (apply str (repeat 64 "b")) "\","
                  " \"browser_download_url\": \"https://example.invalid/simpleviz-v1.2.3.tar.gz\"}]}")]
    (try
      (fs/create-dirs stub-bin)
      (spit (str (fs/path tmp "latest.json")) json)
      (spit (str (fs/path stub-bin "curl"))
            (str "#!/usr/bin/env bash\ncat '" (fs/path tmp "latest.json") "'\n"))
      (.setExecutable (fs/file (fs/path stub-bin "curl")) true)
      (let [res (p/shell {:out :string :err :string :continue true
                          :extra-env {"PATH" (str stub-bin ":" (System/getenv "PATH"))
                                      "SIMPLEVIZ_INSTALL_SOURCED" "1"
                                      "SIMPLEVIZ_HOME" (str (fs/path tmp "home"))
                                      "SIMPLEVIZ_BIN" (str (fs/path tmp "bin"))}}
                         "bash" "-c" (str "source '" proc-util/repo-root "/install.sh' && fetch_release"
                                          " && printf '%s\\n' \"$TAG\" \"$TARBALL_URL\" \"$TARBALL_DIGEST\""))]
        (is (= 0 (:exit res)) (:err res))
        (is (= ["v1.2.3"
                "https://example.invalid/simpleviz-v1.2.3.tar.gz"
                (str "sha256:" (apply str (repeat 64 "b")))]
               (str/split-lines (:out res)))))
      (finally (fs/delete-tree tmp)))))

(deftest install-refuses-a-tarball-that-does-not-match-the-release-digest
  ;; the digest GitHub lists for the release asset is checked before
  ;; anything is extracted or the existing install is touched
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")
        other (apply str (repeat 64 "0"))]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "VERSION")) "v0.0.0\n")
      (let [tarball (tarball! tmp {"server/cli.clj" "(ns cli)\n"})
            res (install-files tmp tarball :digest (str "sha256:" other))]
        (is (= 1 (:exit res)) (:out res))
        (is (= (str "install: checksum mismatch for https://example.invalid/simpleviz-v0.0.1.tar.gz:"
                    " the release lists " other ", the download is " (sha256 tarball)
                    " — refusing to install")
               (str/trim (:err res)))))
      (doseq [digest ["" "sha1:abc" (str "sha256:" (subs other 1))]]
        (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"}) :digest digest)]
          (is (= 1 (:exit res)) digest)
          (is (= (str "install: release v0.0.1 lists no SHA-256 digest for its tarball (got '" digest "')"
                      " — refusing to install an unverified download")
                 (str/trim (:err res))))))
      (is (= ["VERSION"] (map (comp str fs/file-name) (fs/list-dir home)))
          "the existing install is untouched")
      (is (= "v0.0.0\n" (slurp (str (fs/path home "VERSION")))))
      (finally (fs/delete-tree tmp)))))

(deftest install-refuses-paths-babashka-cannot-load-from
  ;; the launcher could not run from there (#99), so main stops before it
  ;; downloads anything
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        bin (fs/path tmp "stub-bin")
        fetched (fs/path tmp "fetched")
        real-bb (str (fs/which "bb"))
        run (fn [env user-home]
              (spit (str (fs/path bin "bb"))
                    (str "#!/usr/bin/env bash\n"
                         (when user-home
                           (str "if [ \"${1:-}\" = -e ]; then printf '%s' '" user-home "'; exit 0; fi\n"))
                         "exec '" real-bb "' \"$@\"\n"))
              (.setExecutable (fs/file (fs/path bin "bb")) true)
              (select-keys
               (p/shell {:out :string :err :string :continue true
                         :extra-env (merge {"HOME" (str (fs/path tmp "user-home"))
                                            "SIMPLEVIZ_HOME" (str (fs/path tmp "home"))
                                            "SIMPLEVIZ_BIN" (str (fs/path tmp "bin"))
                                            "PATH" (str bin ":" (System/getenv "PATH"))}
                                           env)}
                        "bash" (str proc-util/repo-root "/install.sh"))
               [:out :err :exit]))]
    (try
      (fs/create-dirs bin)
      (spit (str (fs/path bin "curl")) (str "#!/usr/bin/env bash\ntouch '" fetched "'\nexit 22\n"))
      (.setExecutable (fs/file (fs/path bin "curl")) true)
      (let [home (str (fs/path tmp "sim%viz"))
            res (run {"SIMPLEVIZ_HOME" home} nil)]
        (is (= 1 (:exit res)))
        (is (= (str "install: " home " contains a %, and babashka can't load code from such a path"
                    " — set SIMPLEVIZ_HOME to a path without one")
               (str/trim (:err res)))))
      (let [user-home (str (fs/path tmp "us%er"))
            res (run {} user-home)]
        (is (= 1 (:exit res)))
        (is (= (str "install: " user-home "/.m2 contains a %, and babashka can't load code from such a path"
                    " — it keeps simpleviz's libraries there")
               (str/trim (:err res)))))
      (is (not (fs/exists? fetched)) "nothing was downloaded")
      (is (not (fs/exists? (fs/path tmp "home"))) "nothing was installed")
      (is (not (fs/exists? (fs/path tmp "bin" "simpleviz"))) "no launcher was written")
      (finally (fs/delete-tree tmp)))))

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

(deftest install-keeps-the-users-templates
  ;; reinstalls replace the install dir; templates are the user's (and
  ;; their tools') — added ones and edits to a shipped one survive
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")
        tpl #(str (fs/path home "templates" %))]
    (try
      (fs/create-dirs (fs/path home "templates"))
      (spit (tpl "default.edn") ";; edited\n")
      (spit (tpl "mine.edn") ";; mine\n")
      (spit (str (fs/path home "VERSION")) "v0.0.0\n")
      (spit (str (fs/path home "stale.txt")) "old")
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"
                                                  "templates/default.edn" ";; shipped\n"
                                                  "templates/new.edn" ";; new\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (= ";; edited\n" (slurp (tpl "default.edn"))))
      (is (= ";; mine\n" (slurp (tpl "mine.edn"))))
      (is (= ";; new\n" (slurp (tpl "new.edn"))))
      (is (not (fs/exists? (fs/path home "stale.txt"))) "the rest is still replaced")
      (finally (fs/delete-tree tmp)))))

(deftest install-keeps-the-users-prefs
  ;; your theme and layout choices outlive `simpleviz update`
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")
        prefs (str (fs/path home "prefs.edn"))]
    (try
      (fs/create-dirs home)
      (spit prefs "{:theme \"dark\"}")
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (= "{:theme \"dark\"}" (slurp prefs)))
      (finally (fs/delete-tree tmp)))))

(deftest install-writes-through-a-symlinked-templates-folder
  ;; e.g. templates kept in a dotfiles repo and linked in
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")
        dots (fs/path tmp "dots")]
    (try
      (fs/create-dirs home)
      (fs/create-dirs dots)
      (spit (str (fs/path dots "mine.edn")) ";; mine\n")
      (fs/create-sym-link (fs/path home "templates") dots)
      (fs/create-sym-link (fs/path dots "gone.edn") (fs/path tmp "nowhere.edn"))
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"
                                                  "templates/default.edn" ";; shipped\n"
                                                  "templates/gone.edn" ";; shipped\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (fs/sym-link? (fs/path home "templates")) "the link stays a link")
      (is (= ";; mine\n" (slurp (str (fs/path dots "mine.edn")))))
      (is (= ";; shipped\n" (slurp (str (fs/path dots "default.edn")))))
      (is (fs/sym-link? (fs/path dots "gone.edn")) "a dangling link is the user's too")
      (is (fs/exists? (fs/path home "server" "cli.clj")))
      (finally (fs/delete-tree tmp)))))

(deftest install-refuses-a-templates-file-before-deleting-anything
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "VERSION")) "v0.0.0\n")
      (spit (str (fs/path home "templates")) "not a folder")
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"
                                                  "templates/default.edn" ";; shipped\n"}))]
        (is (= 1 (:exit res)))
        (is (= (str "install: " home "/templates is not a directory — move it away and rerun")
               (str/trim (:err res)))))
      (is (= "v0.0.0\n" (slurp (str (fs/path home "VERSION")))) "the existing install is untouched")
      (finally (fs/delete-tree tmp)))))

(deftest install-cleans-a-symlinked-home
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        real (fs/path tmp "real")]
    (try
      (fs/create-dirs real)
      (spit (str (fs/path real "VERSION")) "v0.0.0\n")
      (spit (str (fs/path real "stale.txt")) "old")
      (fs/create-sym-link (fs/path tmp "home") real)
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (not (fs/exists? (fs/path real "stale.txt"))))
      (is (fs/exists? (fs/path real "server" "cli.clj")))
      (finally (fs/delete-tree tmp)))))

(deftest install-refuses-a-home-that-is-no-simpleviz-install
  ;; SIMPLEVIZ_HOME=$HOME, or a typo, must not empty that folder
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "thesis.tex")) "years of work")
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"}))]
        (is (= 1 (:exit res)))
        (is (= (str "install: " home " holds files that aren't simpleviz's — set SIMPLEVIZ_HOME to an empty or new folder")
               (str/trim (:err res)))))
      (is (= "years of work" (slurp (str (fs/path home "thesis.tex")))))
      (finally (fs/delete-tree tmp)))))

(deftest install-takes-a-home-with-only-what-simpleviz-keeps-there
  ;; a bbin install or a run leaves templates, prefs and logs, no VERSION
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-install"})
        home (fs/path tmp "home")]
    (try
      (fs/create-dirs (fs/path home "templates"))
      (fs/create-dirs (fs/path home "logs"))
      (spit (str (fs/path home "prefs.edn")) "{:theme \"nord\"}")
      (let [res (install-files tmp (tarball! tmp {"server/cli.clj" "(ns cli)\n"}))]
        (is (= 0 (:exit res)) (:err res)))
      (is (= "{:theme \"nord\"}" (slurp (str (fs/path home "prefs.edn")))))
      (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions home))) "yours alone")
      (finally (fs/delete-tree tmp)))))
