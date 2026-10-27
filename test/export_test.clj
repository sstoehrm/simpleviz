(ns export-test
  "simpleviz export end to end: the CLI as a process, a real headless
  browser. Skipped when no browser is found — except on CI, which ships
  Chrome and must run it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [browser]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [deck]
            [embedded]
            [png]
            [proc-util]
            [steps]
            [svg]))

(def ^:private found (browser/find-browser))

(defn- export! [tmp & args]
  (select-keys (apply p/shell {:dir (str tmp) :out :string :err :string :continue true}
                      "bb" "--config" (str proc-util/repo-root "/bb.edn") "-m" "cli" "export" args)
               [:out :err :exit]))

(defmacro ^:private e2e [name & body]
  `(deftest ~name
     (cond (:path found)
           (let [~'tmp (fs/create-temp-dir {:prefix "export-test"})]
             (try (fs/copy-tree (str proc-util/repo-root "/examples") ~'tmp) ~@body
                  (finally (fs/delete-tree ~'tmp))))
           (System/getenv "CI") (is false (str "CI needs a browser: " (:error found)))
           :else (println "export-test: no browser found, skipped"))))

(defn- at [tmp f] (str (fs/path tmp f)))

(defn- pdf-pages
  "How many pages the PDF at path has: Chrome writes each page's
  dictionary uncompressed."
  [path]
  (count (re-seq #"/Type\s*/Page\b(?!s)" (slurp path :encoding "ISO-8859-1"))))

(defn- state-count [tmp f]
  (count (:states (steps/states (edn/read-string (slurp (at tmp f)))))))

(e2e png-and-svg-carry-the-source
  (let [r (export! tmp "demo.edn" "demo.png")]
    (is (= 0 (:exit r)) (:err r))
    (is (= "wrote demo.png" (str/trim (:out r)))))
  (is (png/png? (at tmp "demo.png")))
  (is (= (slurp (at tmp "demo.edn")) (embedded/extract (at tmp "demo.png") "simpleviz-edn")))
  (is (= 0 (:exit (export! tmp "demo.edn" "demo.svg"))))
  (is (= (slurp (at tmp "demo.edn")) (svg/extract (at tmp "demo.svg") "simpleviz-edn"))))

(e2e a-compare-export-embeds-both-sides
  (is (= 0 (:exit (export! tmp "demo.edn" "next" "diff.svg"))))
  (is (= (slurp (at tmp "demo.edn")) (svg/extract (at tmp "diff.svg") "simpleviz-edn-old")))
  (is (= (slurp (at tmp "demo-next.edn")) (svg/extract (at tmp "diff.svg") "simpleviz-edn-new"))))

(e2e the-theme-paints-the-export
  (is (= 0 (:exit (export! tmp "demo.edn" "nord.svg" "--theme" "nord"))))
  (is (str/includes? (slurp (at tmp "nord.svg")) "fill=\"#2e3440\"")))

(e2e a-big-graph-exports-expanded
  ;; > 500 nodes opens as a collapsed overview; the export must not
  (let [nodes (into {} (for [i (range 520)] [(keyword (str "n" i)) {}]))]
    (spit (at tmp "big.edn") (pr-str {:nodes nodes :boxes {:big {:components (set (keys nodes))}}}))
    (let [r (export! tmp "big.edn" "big.svg")]
      (is (= 0 (:exit r)) (:err r)))
    (is (str/includes? (slurp (at tmp "big.svg")) ">n519<"))))

(e2e a-graph-error-is-the-pages-message
  (spit (at tmp "broken.edn") "{:nodes {:a {}} :edges [[:a")
  (let [r (export! tmp "broken.edn" "x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: Graph error:") (:err r))
    (is (not (fs/exists? (at tmp "x.png"))))))

(e2e an-unwritable-output-is-a-clear-error
  (let [r (export! tmp "demo.edn" "no/such/dir/x.png")]
    (is (= 1 (:exit r)))
    (is (str/starts-with? (:err r) "simpleviz: cannot write no/such/dir/x.png") (:err r))))

(e2e a-deck-exports-as-a-pdf-page-per-slide
  ;; deck.md has one slide with steps.edn beside it: a page per state
  (let [n (count (deck/slides (slurp (at tmp "deck.md"))))
        pages (+ n (dec (state-count tmp "steps.edn")))
        r (export! tmp "deck.md" "deck.pdf")]
    (is (= 0 (:exit r)) (:err r))
    (is (= (str "wrote deck.pdf (" n " slides, " pages " pages)") (str/trim (:out r))))
    (is (= pages (pdf-pages (at tmp "deck.pdf")))))
  (is (= "%PDF-" (subs (slurp (at tmp "deck.pdf")) 0 5))))

(e2e a-graph-with-steps-prints-a-page-per-step
  ;; each state of the graph, the slide's text on every one of them
  (spit (at tmp "talk.md") (str "# Talk\n---\n>> [steps](steps.edn)\n# It grows\n---\n"
                                ">> [demo](demo.edn)\n# Plain\n---\n>> [again](steps.edn)\n# Again\n"))
  (let [k (state-count tmp "steps.edn")
        pages (+ 2 k k)
        r (export! tmp "talk.md" "talk.pdf")]
    (is (= 6 k))
    (is (= 0 (:exit r)) (:err r))
    (is (= "" (:err r)))
    (is (= (str "wrote talk.pdf (4 slides, " pages " pages)") (str/trim (:out r))))
    (is (= pages (pdf-pages (at tmp "talk.pdf"))))
    (is (= 4 (pdf-pages (do (spit (at tmp "flat.md") "# A\n---\n# B\n---\n>> [demo](demo.edn)\n# C\n---\n# D\n")
                            (export! tmp "flat.md" "flat.pdf")
                            (at tmp "flat.pdf")))))))

(e2e a-deck-with-broken-sides-still-exports-and-says-so
  ;; the slide says what's wrong, as it does presenting, and so does stderr
  (spit (at tmp "talk.md") (str "# Talk\n---\n>> [gone](gone.edn)\n# A\n---\n>> [demo](demo.edn)\n# B\n"
                                "---\n>> ![pic](nope.jpg)\n# C\n"))
  (let [r (export! tmp "talk.md" "talk.pdf")]
    (is (= 0 (:exit r)) (:err r))
    (is (= "wrote talk.pdf (4 slides)" (str/trim (:out r))))
    (is (= ["simpleviz: slide 2: Graph error: no such file: gone.edn"
            "simpleviz: slide 4: nope.jpg — no such picture, or not one the browser shows"]
           (str/split-lines (str/trim (:err r)))))))

(e2e a-slide-step-the-graph-lacks-exports-the-start-and-says-so
  (spit (at tmp "s.edn") "{:nodes {:a {}} :steps [{:name \"b\" :edits [[[:nodes :b] :+ {}]]}]}")
  (spit (at tmp "talk.md") "# Talk\n---\n>> [s](s.edn?step=1)\n# A\n---\n>> [s](s.edn?step=nope)\n# B\n")
  (let [r (export! tmp "talk.md" "talk.pdf")]
    (is (= 0 (:exit r)) (:err r))
    ;; a slide naming a step prints that step alone, not a page per step
    (is (= "wrote talk.pdf (3 slides)" (str/trim (:out r))))
    (is (= 3 (pdf-pages (at tmp "talk.pdf"))))
    (is (= ["simpleviz: slide 3: s.edn?step=nope — no step named \"nope\""]
           (str/split-lines (str/trim (:err r)))))))

(e2e a-long-deck-with-formulas-exports
  ;; more slides with formulas than the page keeps results for at once
  (let [table (fn [i] (str "| a | b |\n|---|--:|\n| x | " i " |\n| y | =(* 2 (sum :b 1)) |\n"))
        slides (for [i (range 60)] (str "# Slide " i "\n\n" (table i) "\n"))]
    (spit (at tmp "long.md") (str/join "---\n" slides))
    (let [r (export! tmp "long.md" "long.pdf")]
      (is (= 0 (:exit r)) (:err r))
      (is (= "wrote long.pdf (60 slides)" (str/trim (:out r)))))))

(e2e an-empty-deck-is-refused
  (spit (at tmp "empty.md") "---\n\n---\n")
  (let [r (export! tmp "empty.md" "empty.pdf")]
    (is (= 1 (:exit r)))
    (is (= "simpleviz: empty.md has no slides" (str/trim (:err r))))
    (is (not (fs/exists? (at tmp "empty.pdf"))))))
