(ns tarball-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tarball]))

(defn- tree!
  "Write {rel text-or-bytes} under a fresh temp dir; returns its canonical File."
  [files]
  (let [dir (.getCanonicalFile (.toFile (fs/create-temp-dir {:prefix "tarball-test"})))]
    (doseq [[rel content] files]
      (let [f (io/file dir rel)]
        (.mkdirs (.getParentFile f))
        (if (string? content) (spit f content) (io/copy content f))))
    dir))

(defn- files-of
  "[paths warnings] of tarball/files for `start` under `root`."
  [root start]
  (let [warnings (atom [])
        out (tarball/files root start #(swap! warnings conj %))]
    [out @warnings]))

(deftest a-graph-takes-its-refs-pairs-and-the-md-files-they-name
  (let [root (tree! {"g.edn" (str "{:nodes {:a {:ref \"sub/api.edn\"} :b {:pair \"views/d.edn#x\"}"
                                  " :c {:ref \"notes.md\"}}}")
                     "sub/api.edn" "{:nodes {:h {:ref \"../g.edn\"}}}"
                     "views/d.edn" "{:nodes {:x {}}}"
                     "notes.md" "See [the db](sub/db.edn)."
                     "sub/db.edn" "{:nodes {:t {}}}"
                     "unused.edn" "{:nodes {:u {}}}"})
        [out warnings] (files-of root "g.edn")]
    (is (= "g.edn" (first out)))
    (is (= #{"g.edn" "sub/api.edn" "views/d.edn" "notes.md" "sub/db.edn"} (set out)))
    (is (= (count out) (count (set out))) "each file once")
    (is (= [] warnings))
    (fs/delete-tree root)))

(deftest an-md-file-takes-links-pictures-sides-and-data
  (let [root (tree! {"talk.md" (str "# Talk\n\n"
                                    "Open [the demo](demo.edn) or [notes](docs/notes.md#top).\n\n"
                                    "![a photo](img/photo.jpg)\n\n"
                                    "[ref link][d]\n\n[d]: refd.edn\n\n"
                                    "---\n\n>> side.edn\n\n# A side by path\n\n"
                                    "```simpleviz-table\n>> [sales](data/sales.csv)\n```\n\n"
                                    "| a |\n|---|\n| =(sum :a) |\n\n"
                                    "```simpleviz-chart\n>> [m](m.json)\n>> [:x]\n{:kind :bar}\n```\n\n"
                                    "```\n[in code](code.edn)\n```\n\n"
                                    "[web](https://example.com/x.edn) and [mail](mailto:a@b.c)\n")
                     "demo.edn" "{:nodes {:a {}}}"
                     "docs/notes.md" "Back to [the talk](../talk.md), and [a pic](pic.svg)."
                     "docs/pic.svg" "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                     "img/photo.jpg" (byte-array [1 2 3])
                     "refd.edn" "{:nodes {:r {}}}"
                     "side.edn" "{:nodes {:s {}}}"
                     "data/sales.csv" "a\n1\n"
                     "m.json" "[{\"x\": 1}]"
                     "code.edn" "{:nodes {:c {}}}"})
        [out warnings] (files-of root "talk.md")]
    (is (= "talk.md" (first out)))
    (is (= #{"talk.md" "demo.edn" "docs/notes.md" "docs/pic.svg" "img/photo.jpg" "refd.edn"
             "side.edn" "data/sales.csv" "m.json"}
           (set out)))
    (is (= (count out) (count (set out))))
    (is (= [] warnings))
    (fs/delete-tree root)))

(deftest edn-data-is-packed-without-reading-it-as-a-graph
  (let [root (tree! {"t.md" (str "```simpleviz-chart\n>> [d](data.edn)\n>> [:x]\n{:kind :bar}\n```\n\n"
                                 "Also [the graph](g.edn).\n")
                     ;; read as a graph, this would name gone.edn
                     "data.edn" "{:nodes {:a {:ref \"gone.edn\"}}}"
                     "g.edn" "{:nodes {:a {}}}"})
        [out warnings] (files-of root "t.md")]
    (is (= #{"t.md" "data.edn" "g.edn"} (set out)))
    (is (= [] warnings))
    (fs/delete-tree root)))

(deftest a-nested-quote-is-no-slide-side
  ;; >> opening a slide names its side only when it is a file a slide shows
  (let [root (tree! {"t.md" ">> deeply quoted words\n\n# Slide\n"})
        [out warnings] (files-of root "t.md")]
    (is (= ["t.md"] out))
    (is (= [] warnings))
    (fs/delete-tree root)))

(deftest a-compare-link-takes-the-forks-of-the-graph-and-its-refs
  (let [root (tree! {"talk.md" "[the change](g.edn?compare=next)"
                     "g.edn" "{:nodes {:a {:ref \"sub.edn\"}}}"
                     "g-next.edn" "{:nodes {:a {:ref \"sub.edn\"} :b {:ref \"new.edn\"}}}"
                     "sub.edn" "{:nodes {:s {}}}"
                     "sub-next.edn" "{:nodes {:s {} :t {}}}"
                     "new.edn" "{:nodes {:n {}}}"
                     "g-other.edn" "{:nodes {:o {}}}"})
        [out warnings] (files-of root "talk.md")]
    (is (= #{"talk.md" "g.edn" "g-next.edn" "sub.edn" "sub-next.edn" "new.edn"} (set out)))
    (is (= [] warnings))
    (fs/delete-tree root)))

(deftest missing-files-and-links-out-of-the-folder-are-skipped-with-a-warning
  (let [root (tree! {"sub/talk.md" "[up](../x.edn) [gone](gone.edn) ![gone too](gone.png)"})
        [out warnings] (files-of (io/file root "sub") "talk.md")]
    (is (= ["talk.md"] out))
    (is (= ["talk.md: link \"../x.edn\" leaves the folder, skipped"
            "talk.md: link \"gone.edn\": not found, skipped"
            "talk.md: link \"gone.png\": not found, skipped"]
           warnings))
    (fs/delete-tree root)))

(deftest a-graph-that-does-not-parse-is-packed-with-a-warning
  (let [root (tree! {"talk.md" "[bad](bad.edn)" "bad.edn" "{:nodes"})
        [out warnings] (files-of root "talk.md")]
    (is (= ["talk.md" "bad.edn"] out))
    (is (= 1 (count warnings)))
    (is (str/starts-with? (first warnings) "bad.edn: its links can't be read: "))
    (fs/delete-tree root)))

(deftest a-symlink-out-of-the-folder-is-not-followed
  (let [outside (tree! {"secret.edn" "{:nodes {:s {}}}"})
        root (tree! {"g.edn" "{:nodes {:a {:ref \"link.edn\"}}}"})]
    (fs/create-sym-link (fs/path root "link.edn") (fs/path outside "secret.edn"))
    (let [[out warnings] (files-of root "g.edn")]
      (is (= ["g.edn"] out))
      (is (= ["g.edn: link \"link.edn\" leaves the folder, skipped"] warnings)))
    (fs/delete-tree root)
    (fs/delete-tree outside)))

(defn- untar
  "Extract archive `f` with the system tar into a fresh dir; returns it."
  [f gz?]
  (let [dir (fs/create-temp-dir {:prefix "tarball-test-out"})]
    (p/shell {:out :string :err :string} "tar" (if gz? "-xzf" "-xf") (str f) "-C" (str dir))
    dir))

(deftest write-makes-an-archive-tar-extracts-below-one-folder
  (let [long-dir (str/join "/" (repeat 12 "abcdefghij"))
        bin (byte-array (map unchecked-byte (range 256)))
        root (tree! {"g.edn" "{:nodes {:a {}}}"
                     "dir/ünï.csv" "a\n1\n"
                     (str long-dir "/deep.edn") "{}"
                     "bin.png" bin})
        rels ["g.edn" "dir/ünï.csv" (str long-dir "/deep.edn") "bin.png"]]
    (doseq [[name gz?] [["out.tar.gz" true] ["out.tar" false]]]
      (let [out (io/file root name)
            _ (tarball/write! out root rels "talk")
            dir (untar out gz?)]
        (doseq [rel rels]
          (is (= (seq (fs/read-all-bytes (fs/path root rel)))
                 (seq (fs/read-all-bytes (fs/path dir "talk" rel))))
              (str name ": " rel)))
        (is (= ["talk"] (map (comp str fs/file-name) (fs/list-dir dir))) "one folder at the top")
        (fs/delete-tree dir)))
    (fs/delete-tree root)))

(deftest write-refuses-a-path-too-long-for-ustar
  (let [root (tree! {"g.edn" "{}"})
        rel (apply str (repeat 101 "x"))]
    (is (thrown-with-msg? Exception #"too long" (tarball/write! (io/file root "o.tar") root [rel] "t")))
    (fs/delete-tree root)))
