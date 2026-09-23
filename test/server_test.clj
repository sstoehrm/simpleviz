(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.cli]
            [babashka.fs]
            [log]
            [proc-util]
            [serve]))

(defn- serve!
  "Point the server at `path` (single-file, or compare against its
  `suffix` fork) the way -main does, with a fresh undo stack."
  ([path] (serve! path nil))
  ([path suffix]
   (reset! serve/files {:root path :suffix suffix})
   (reset! serve/root-dir (.getParentFile (.getCanonicalFile (java.io.File. path))))
   (reset! serve/undo-stacks {})
   (reset! serve/locks {})))

(deftest graph-json-serves-normalized-graph
  (let [out (json/parse-string
             (serve/graph-json
              (str "{:nodes {\"a\" {:name \"A\" :role [:active :passive]}}"
                   " :edges [{:nodes [\"a\" \"a\"] :direction :<-> :name \"self\"}]"
                   " :boxes [{:name \"g\" :components #{\"a\"}}]}")))]
    (is (= "a" (get-in out ["edges" 0 "source"])))
    (is (= true (get-in out ["edges" 0 "arrows" "source"])))
    (is (= ["active" "passive"] (get-in out ["nodes" "a" "attrs" "role"])))
    (is (= ["n:a"] (get-in out ["boxes" 0 "components"])))
    (is (= "g" (get-in out ["parent-of" "n:a"])))
    (is (= [] (get out "warnings")))))

(deftest parse-error-becomes-error-json
  (let [out (json/parse-string (serve/graph-json "{:unclosed"))]
    (is (contains? out "error"))
    (is (string? (get out "error")))))

(deftest parse-args-uses-default-port
  (is (= {:file "g.edn" :suffix nil :port 7373 :debug false} (serve/parse-args ["g.edn"]))))

(deftest parse-args-accepts-port-flag-and-alias
  (is (= {:file "g.edn" :suffix nil :port 9000 :debug false} (serve/parse-args ["g.edn" "--port" "9000"])))
  (is (= {:file "g.edn" :suffix nil :port 9000 :debug false} (serve/parse-args ["g.edn" "-p" "9000"])))
  (is (= {:file "g.edn" :suffix nil :port 9000 :debug false} (serve/parse-args ["--port" "9000" "g.edn"]))))

(deftest parse-args-rejects-bad-input
  (is (contains? (serve/parse-args []) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "abc"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "0"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "70000"]) :error)))

(deftest parse-args-suffix-enables-compare
  (is (= {:file "a.edn" :suffix "next" :port 7373 :debug false}
         (serve/parse-args ["a.edn" "next"])))
  (is (= {:file "a.edn" :suffix "v2" :port 9000 :debug false}
         (serve/parse-args ["a.edn" "v2" "--port" "9000"])))
  (is (= {:file "a.edn" :suffix nil :port 7373 :debug false}
         (serve/parse-args ["a.edn"]))))

(deftest parse-args-rejects-bad-suffixes
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "ne/xt"])) "invalid suffix: ne/xt"))
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "b.edn"])) "two-file compare was replaced"))
  (is (clojure.string/includes? (:error (serve/parse-args ["a.edn" "b.PNG"])) "two-file compare was replaced")))

(deftest parse-args-rejects-extra-positionals
  (is (contains? (serve/parse-args ["a.edn" "next" "extra"]) :error)))

(deftest fork-name-puts-the-suffix-before-the-extension
  (is (= "demo-next.edn" (serve/fork-name "demo.edn" "next")))
  (is (= "api/internals-next.edn" (serve/fork-name "api/internals.edn" "next")))
  (is (= "examples/x-v2.png" (serve/fork-name "examples/x.png" "v2")))
  (is (= "a.b/noext-next" (serve/fork-name "a.b/noext" "next"))))

(deftest compare-json-diffs-two-graphs
  (let [out (json/parse-string
             (serve/compare-json "{:nodes {:a {}}}"
                                 "{:nodes {:a {} :b {}}}"
                                 "old.edn" "new.edn"))]
    (is (= "added" (get-in out ["nodes" "b" "diff"])))
    (is (nil? (get-in out ["nodes" "a" "diff"])))
    (is (= {"old" "old.edn" "new" "new.edn"} (get out "compare")))))

(deftest compare-json-parse-error-names-the-file
  (let [out (json/parse-string
             (serve/compare-json "{:unclosed" "{}" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "old.edn: ")))
  (let [out (json/parse-string
             (serve/compare-json "{}" "{:unclosed" "old.edn" "new.edn"))]
    (is (clojure.string/starts-with? (get out "error") "new.edn: "))))

(deftest single-file-json-has-no-compare-keys
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "compare")))
    (is (not (contains? (get-in out ["nodes" "a"]) "diff")))))

(deftest graph-json-includes-file-basename
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}" "demo.edn"))]
    (is (= "demo.edn" (get out "file"))))
  ;; 1-arity unchanged: no :file key
  (let [out (json/parse-string (serve/graph-json "{:nodes {:a {}}}"))]
    (is (not (contains? out "file")))))

(deftest compare-json-includes-new-file-basename
  (let [out (json/parse-string
             (serve/compare-json "{}" "{}" "examples/old.edn" "examples/new.edn"))]
    (is (= "new.edn" (get out "file")))))

(deftest api-source-serves-raw-text
  (serve! "examples/demo.edn")
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))
    (is (clojure.string/starts-with?
         (get-in resp [:headers "Content-Type"]) "text/plain"))))

(deftest api-source-compare-selects-files
  (serve! "examples/demo.edn" "next")
  (is (= (slurp "examples/demo.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=new"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source"})))))

;; --- Serving PNGs with embedded EDN (issue #46) ----------------------
;;
;; A compare-export PNG fixture is built byte-by-byte here (signature +
;; IHDR-less iTXt chunks are enough for the extractor, which walks
;; chunks without decoding the image or checking CRCs).

(defn- be32* [n]
  [(bit-and (bit-shift-right n 24) 0xff)
   (bit-and (bit-shift-right n 16) 0xff)
   (bit-and (bit-shift-right n 8) 0xff)
   (bit-and n 0xff)])

(defn- itxt* [kw text]
  (let [kw-b (map int kw)
        txt-b (seq (.getBytes text "UTF-8"))
        data (concat kw-b [0 0 0 0 0] txt-b)]
    (concat (be32* (count data)) (map int "iTXt") data [0 0 0 0])))

(defn- edit-req
  "A POST /api/edit request map. Defaults to a same-origin-shaped request
  (JSON content-type, port matching serve/default-port) so existing tests
  that don't care about the Task-13 Origin/Content-Type guard keep
  passing; :headers here are merged over those defaults so a test can
  override just what it needs (e.g. a foreign :origin, or a nil
  \"content-type\" to simulate a missing header)."
  ([body] (edit-req body nil))
  ([body {:keys [headers server-port]}]
   {:uri "/api/edit" :request-method :post
    :headers (merge {"content-type" "application/json"} headers)
    :server-port (or server-port serve/default-port)
    :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8"))}))

(defn- temp-edn [text]
  (let [f (java.io.File/createTempFile "edit-test" ".edn")]
    (.deleteOnExit f) (spit f text) (.getPath f)))

(defn- png-bytes* [chunks]
  (byte-array (map unchecked-byte (concat [137 80 78 71 13 10 26 10] (apply concat chunks)))))

(defn- temp-png* [chunks]
  (let [f (java.io.File/createTempFile "serve-test" ".png")]
    (.deleteOnExit f)
    (with-open [os (clojure.java.io/output-stream f)] (.write os (png-bytes* chunks)))
    (.getPath f)))

(defn- temp-dir* []
  (.toFile (java.nio.file.Files/createTempDirectory "serve-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-temp-dir*
  "Call (f dir) with a fresh temp dir (java.io.File), deleting the tree
  afterwards regardless of outcome."
  [f]
  (let [dir (temp-dir*)]
    (try (f dir)
         (finally (babashka.fs/delete-tree (.toPath dir))))))

(defn- write! [dir rel content]
  (let [f (java.io.File. dir rel)]
    (.mkdirs (.getParentFile f))
    (if (bytes? content)
      (with-open [os (clojure.java.io/output-stream f)] (.write os content))
      (spit f content))
    (.getPath f)))

(deftest api-graph-suffix-compare-accepts-png-sides
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "x.png" (png-bytes* [(itxt* "simpleviz-edn-new" "{:nodes {:a {}}}")]))]
        (write! dir "x-next.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {} :b {}}}")]))
        (serve! p "next")
        (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
          (is (contains? out "compare"))
          (is (= "added" (get-in out ["nodes" "b" "diff"])))
          (is (false? (get out "editable")))
          (is (false? (get out "editable-old"))))))))

(deftest api-graph-compare-mode-carries-editable-flags
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "g.edn" "{:nodes {:a nil}}")]
        (write! dir "g-next.edn" "{:nodes {:a nil :b nil}}")
        (serve! p "next")
        (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
          (is (true? (get out "editable")))
          (is (true? (get out "editable-old")))
          (is (= "g.edn" (get out "path")))
          (is (= {"old" "g.edn" "new" "g-next.edn"} (get out "compare"))))))))

(deftest api-edit-refuses-png-in-old-slot
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "x.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {}}}")]))]
        (write! dir "x-next.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:a {}}}")]))
        (serve! p "next")
        (is (= "PNG sources are read-only"
               (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops []})))) "error")))))))

(deftest api-graph-file-param-refused-in-embedded-compare
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (serve! f)
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=x.edn"})))]
      (is (= "refs are not available in an embedded compare" (get out "error"))))
    (is (= 0 (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=x.edn"}))) "mtime")))
    (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=x.edn"}))))
    (is (= "refs are not available in an embedded compare"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "x.edn" :ops []})))) "error")))))

(deftest api-source-old-without-compare-404s
  (serve! "examples/demo.edn")
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "which=old"})))))

(deftest api-source-deleted-file-404s-instead-of-500
  ;; A file that vanished between serve-startup and the request (e.g.
  ;; deleted mid-serve) must not slurp-throw a raw exception message
  ;; (incl. the absolute path) back to the client as a 500.
  (serve! "test/fixtures/does-not-exist.edn")
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 404 (:status resp)))
    (is (= "not found" (:body resp)))))

(deftest api-source-which-regex-is-anchored
  ;; A substring match like "awhich=old" must not be treated as
  ;; which=old — it should fall through to the default (single-file
  ;; mode: :new, unaffected by :old since it's nil here).
  (serve! "examples/demo.edn")
  (let [resp (serve/handler {:uri "/api/source" :query-string "awhich=old"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))))

(deftest api-graph-serves-png-embedded-edn
  (serve! "test/fixtures/embedded.png")
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
    (is (contains? (get out "nodes") "a"))
    (is (= "embedded.png" (get out "file")))
    (is (not (contains? out "compare")))))

(deftest api-graph-auto-compares-a-compare-export-png
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (serve! f)
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (= "added" (get-in out ["nodes" "b" "diff"])))
      (is (contains? out "compare")))))

(deftest api-graph-png-without-edn-is-a-clear-error
  (serve! "test/fixtures/plain-1x1.png")
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
    (is (clojure.string/includes? (get out "error" "") "no embedded simpleviz EDN"))))

(deftest api-source-serves-png-embedded-edn
  (serve! "test/fixtures/embedded.png")
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= "{:nodes {:a {}}}" (:body resp)))))

(deftest api-source-compare-png-selects-sides
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:old {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:new {}}}")])]
    (serve! f)
    (is (= "{:nodes {:old {}}}"
           (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
    (is (= "{:nodes {:new {}}}"
           (:body (serve/handler {:uri "/api/source"}))))))

;; --- /api/edit: undo stack + editable flags (Task 6) ------------------

(deftest api-edit-applies-and-writes
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}))]
      (is (= 200 (:status resp)))
      (is (true? (get (json/parse-string (:body resp)) "ok")))
      (is (clojure.string/includes? (slurp p) ":b nil")))))

(deftest api-edit-error-leaves-file-untouched
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "a"}]}))]
      (is (clojure.string/includes? (get (json/parse-string (:body resp)) "error") "already exists"))
      (is (= "{:nodes {:a nil}}" (slurp p)))
      (is (empty? (get @serve/undo-stacks p))))))

(deftest api-edit-undo-restores
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}))
    (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]}))
    (is (= "{:nodes {:a nil}}" (slurp p)))
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]}))]
      (is (= "nothing to undo" (get (json/parse-string (:body resp)) "error"))))))

(deftest api-edit-refuses-png-and-missing-old
  (serve! "test/fixtures/embedded.png")
  (is (= "PNG sources are read-only"
         (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops []})))) "error")))
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (is (clojure.string/includes?
         (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops []})))) "error")
         "no old file"))))

(deftest api-graph-carries-editable-flags
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (is (true? (get (json/parse-string (:body (serve/handler {:uri "/api/graph"}))) "editable"))))
  (serve! "test/fixtures/embedded.png")
  (is (false? (get (json/parse-string (:body (serve/handler {:uri "/api/graph"}))) "editable"))))

(deftest api-graph-embedded-compare-png-is-not-editable
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (serve! f)
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (false? (get out "editable")))
      (is (false? (get out "editable-old"))))))

;; --- /api/edit: Origin + Content-Type guard (Task 13) ------------------

(deftest api-edit-rejects-foreign-origin
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler
                (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}
                          {:headers {"origin" "http://evil.example"}}))]
      (is (= 403 (:status resp)))
      (is (= "forbidden" (:body resp)))
      (is (clojure.string/starts-with?
           (get-in resp [:headers "Content-Type"]) "text/plain"))
      (is (= "{:nodes {:a nil}}" (slurp p))))))

(deftest api-edit-accepts-local-origins
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (doseq [origin ["http://localhost:7373" "http://127.0.0.1:7373"]]
      (serve! p)
      (reset! serve/undo-stacks {})
      (spit p "{:nodes {:a nil}}")
      (let [resp (serve/handler
                  (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}
                            {:headers {"origin" origin}}))]
        (is (= 200 (:status resp)))
        (is (true? (get (json/parse-string (:body resp)) "ok")))))))

(deftest api-edit-rejects-missing-content-type
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler
                (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}
                          {:headers {"content-type" nil}}))]
      (is (= 415 (:status resp)))
      (is (= "unsupported media type" (:body resp)))
      (is (clojure.string/starts-with?
           (get-in resp [:headers "Content-Type"]) "text/plain"))
      (is (= "{:nodes {:a nil}}" (slurp p))))))

;; --- e2e: edit round-trip through the file to /api/graph (Task 12) ----

(deftest e2e-edit-roundtrip
  (let [p (temp-edn "{:nodes {:web {:name \"Web\"}} ;; a comment\n :edges {}}")]
    (serve! p)
    (reset! serve/undo-stacks {})
    (serve/handler (edit-req {:file "new"
                              :ops [{:op "add-node" :id "api"}
                                    {:op "add-edge" :from "web" :to "api" :direction "->"}]}))
    (let [g (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (contains? (get g "nodes") "api"))
      (is (= 1 (count (get g "edges")))))
    (is (clojure.string/includes? (slurp p) ";; a comment"))))

;; --- --debug flag, crash guard, edit log ------------------------------

(deftest parse-args-accepts-debug-flag
  (is (= {:file "g.edn" :suffix nil :port 7373 :debug true} (serve/parse-args ["g.edn" "--debug"])))
  (is (= {:file "g.edn" :suffix nil :port 9000 :debug true} (serve/parse-args ["--debug" "g.edn" "-p" "9000"])))
  (is (= {:file "a.edn" :suffix "next" :port 7373 :debug true}
         (serve/parse-args ["a.edn" "next" "--debug"]))))

(defn- with-log-dir* [f]
  (let [d (str (babashka.fs/create-temp-dir {:prefix "serve-test-log"}))]
    (log/clear!)
    (try (f d) (finally (log/clear!) (babashka.fs/delete-tree d)))))

(deftest guard-turns-uncaught-exception-into-500-and-crash-report
  (with-log-dir*
    (fn [d]
      (log/init! {:dir d :debug false :header "hdr"})
      (let [h (serve/guard (fn [_] (throw (ex-info "kaboom" {}))))
            resp (h {:request-method :get :uri "/api/graph"})]
        (is (= 500 (:status resp)))
        (is (= "kaboom" (get (json/parse-string (:body resp)) "error")))
        (let [[report] (map str (babashka.fs/glob d "crash-*.log"))]
          (is (some? report))
          (is (re-find #"GET /api/graph" (slurp report)))
          (is (re-find #"kaboom" (slurp report))))))))

(deftest guard-passes-normal-responses-through
  (is (= {:status 200 :body "ok"} ((serve/guard (fn [_] {:status 200 :body "ok"})) {:uri "/"}))))

(deftest edit-request-is-logged-with-ops-and-result
  (with-log-dir*
    (fn [d]
      (let [run (log/init! {:dir d :debug true :header "hdr"})
            g (java.io.File/createTempFile "serve-test" ".edn")]
        (.deleteOnExit g)
        (spit g "{:nodes {:a {:name \"A\"} :b {:name \"B\"}} :edges {} :boxes {}}")
        (serve! (str g))
        (serve/handler {:request-method :post :uri "/api/edit"
                        :headers {"content-type" "application/json"}
                        :body (java.io.ByteArrayInputStream.
                               (.getBytes (json/generate-string
                                           {:file "new"
                                            :ops [{:op "delete" :section "nodes" :id "b"}]})))})
        (let [line (->> (slurp run) clojure.string/split-lines (filter #(re-find #" edit " %)) first)]
          (is (some? line))
          (is (re-find #"\"op\":\"delete\"" line))
          (is (re-find #"\"result\":\{\"ok\":true\}" line)))))))

(deftest guard-names-the-exception-class-when-it-has-no-message
  (with-log-dir*
    (fn [d]
      (log/init! {:dir d :debug false :header "hdr"})
      (let [resp ((serve/guard (fn [_] (throw (NullPointerException.)))) {:uri "/x"})]
        (is (= "java.lang.NullPointerException"
               (get (json/parse-string (:body resp)) "error")))))))

(deftest guard-logs-rejected-requests-as-errors
  (with-log-dir*
    (fn [d]
      (let [run (log/init! {:dir d :debug true :header "hdr"})]
        ((serve/guard (fn [_] {:status 415 :body "unsupported media type"}))
         {:request-method :post :uri "/api/edit"})
        (is (re-find #" error .*\"status\":415" (slurp run)))))))

(deftest cli-spec-treats-debug-as-a-bare-flag
  (is (= {:args ["g.edn"] :opts {:debug true}}
         (babashka.cli/parse-args ["--debug" "g.edn"] serve/cli-spec))))

(def refs-root (.getCanonicalFile (java.io.File. "test/fixtures/refs")))

(deftest resolve-path-accepts-files-below-the-root
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/root.edn"))
         (serve/resolve-path refs-root "root.edn")))
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/sub/api.edn"))
         (serve/resolve-path refs-root "sub/api.edn")))
  ;; .. is fine while the result stays below the root
  (is (= (.getCanonicalFile (java.io.File. "test/fixtures/refs/root.edn"))
         (serve/resolve-path refs-root "sub/../root.edn"))))

(deftest resolve-path-refuses-escapes-absolutes-types-and-missing
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves the served folder"
                        (serve/resolve-path refs-root "../embedded.png")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute"
                        (serve/resolve-path refs-root (.getPath (java.io.File. refs-root "root.edn")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an .edn or .png"
                        (serve/resolve-path refs-root "notes.txt")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                        (serve/resolve-path refs-root "sub/missing.edn")))
  ;; a directory is not a file: extension check runs first, so a
  ;; directory named "sub" (no .edn/.png extension) fails there
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an .edn or .png"
                        (serve/resolve-path refs-root "sub")))
  ;; a file with no dot at all in its name has no extension to match —
  ;; refused the same way as a wrong extension, not treated as a
  ;; directory-style special case
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an .edn or .png"
                        (serve/resolve-path refs-root "edn"))))

(deftest resolve-path-refuses-a-symlink-that-escapes-the-root
  (let [tmp (str (babashka.fs/create-temp-dir {:prefix "simpleviz-symlink-test"}))
        root (java.io.File. tmp "root")
        outside (java.io.File. tmp "outside")
        secret (java.io.File. outside "secret.edn")]
    (try
      (.mkdirs root)
      (.mkdirs outside)
      (spit secret "{}")
      (try
        (java.nio.file.Files/createSymbolicLink
         (.toPath (java.io.File. root "link.edn"))
         (java.nio.file.Paths/get "../outside/secret.edn" (make-array String 0))
         (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves the served folder"
                              (serve/resolve-path root "link.edn")))
        (catch Exception _
          ;; symlink creation unsupported on this filesystem — skip, don't fail
          (is true)))
      (finally (babashka.fs/delete-tree tmp)))))

(deftest sides-resolves-through-a-symlinked-root-file
  ;; bb serve ~/link.edn where link.edn symlinks into another dir: the
  ;; root-relative name must be the canonical (real) basename, not the
  ;; raw basename of the symlink path, or resolve-path looks for a file
  ;; that doesn't exist under the real dir.
  (let [tmp (str (babashka.fs/create-temp-dir {:prefix "simpleviz-root-symlink-test"}))
        real (java.io.File. tmp "real")
        elsewhere (java.io.File. tmp "elsewhere")
        g (java.io.File. real "g.edn")
        link (java.io.File. elsewhere "link.edn")]
    (try
      (.mkdirs real)
      (.mkdirs elsewhere)
      (spit g "{:nodes {:a nil}}")
      (let [created? (try
                       (java.nio.file.Files/createSymbolicLink
                        (.toPath link) (.toPath g)
                        (make-array java.nio.file.attribute.FileAttribute 0))
                       true
                       (catch Exception _ false))]
        (if created?
          (do
            (serve! (.getPath link))
            (is (= "g.edn" (.getName (:new (serve/sides nil)))))
            (is (= "g.edn" (get (json/parse-string (:body (serve/handler {:uri "/api/graph"}))) "path"))))
          ;; symlink creation unsupported on this filesystem — skip, don't fail
          (is true)))
      (finally (babashka.fs/delete-tree tmp)))))

;; --- ?file= parameter and edit :path (Task 2) -------------------------

(defn- refs-mode!
  "Serve the refs fixture tree in single-file mode."
  []
  (serve! "test/fixtures/refs/root.edn"))

(deftest api-graph-file-param-serves-a-file-below-the-root
  (refs-mode!)
  (let [root (json/parse-string (:body (serve/handler {:uri "/api/graph"})))
        sub (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fapi.edn"})))]
    (is (= "root.edn" (get root "path")))
    (is (= "API" (get-in root ["nodes" "api" "name"])))
    (is (= "sub/api.edn" (get sub "path")))
    (is (= "api.edn" (get sub "file")))
    (is (= "Handler" (get-in sub ["nodes" "handler" "attrs" "name"])))
    (is (true? (get sub "editable")))))

(deftest api-graph-file-param-refusals-are-error-payloads
  (refs-mode!)
  (let [escape (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=..%2Fembedded.png"})))
        missing (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fnope.edn"})))
        txt (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=notes.txt"})))]
    (is (clojure.string/includes? (get escape "error") "leaves the served folder"))
    (is (clojure.string/includes? (get missing "error") "no such file"))
    (is (clojure.string/includes? (get txt "error") "not an .edn or .png"))))

(deftest api-version-file-param
  (refs-mode!)
  (let [sub (java.io.File. "test/fixtures/refs/sub/api.edn")
        ok (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=sub%2Fapi.edn"})))
        bad (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=..%2Fembedded.png"})))]
    (is (= (.lastModified sub) (get ok "mtime")))
    (is (= 0 (get bad "mtime")))))

(deftest api-source-file-param
  (refs-mode!)
  (is (= (slurp "test/fixtures/refs/sub/deep/db.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fdeep%2Fdb.edn"}))))
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=..%2Fembedded.png"})))))

(deftest api-edit-path-edits-that-file-with-its-own-undo
  ;; copy the tree so the edit does not touch the fixture
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "refs" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (java.io.File. dir "root.edn")
        sub (java.io.File. dir "sub/api.edn")]
    (.mkdirs (.getParentFile sub))
    (spit root "{:nodes {:api {:ref \"sub/api.edn\"}}}")
    (spit sub "{:nodes {:handler nil}}")
    (serve! (.getPath root))
    (let [resp (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "add-node" :id "b"}]}))]
      (is (true? (get (json/parse-string (:body resp)) "ok")))
      (is (clojure.string/includes? (slurp sub) ":b nil"))
      (is (= "{:nodes {:api {:ref \"sub/api.edn\"}}}" (slurp root))))
    ;; undo on the sub file only
    (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "undo"}]}))
    (is (= "{:nodes {:handler nil}}" (slurp sub)))
    ;; the root has nothing to undo
    (is (= "nothing to undo"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]})))) "error")))
    ;; an escaping path is refused before anything is written
    (is (clojure.string/includes?
         (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "../x.edn" :ops [{:op "add-node" :id "c"}]})))) "error")
         "leaves the served folder"))))

(deftest api-edit-undo-shared-between-file-and-path
  ;; copy the tree so the edit does not touch the fixture
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "refs" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (java.io.File. dir "root.edn")
        sub (java.io.File. dir "sub/api.edn")
        original "{:nodes {:api {:ref \"sub/api.edn\"}}}"]
    (.mkdirs (.getParentFile sub))
    (spit root original)
    (spit sub "{:nodes {:handler nil}}")
    ;; the raw :new path carries a redundant "." segment so it differs
    ;; textually from resolve-path's canonical form — reproducing how a
    ;; non-canonical CLI arg differs from the canonical path a `path`-based
    ;; edit resolves to
    (serve! (str (.getPath dir) "/./root.edn"))
    ;; edit via `file`, undo via `path` — one shared undo stack
    (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}))
    (is (clojure.string/includes? (slurp root) ":b nil"))
    (serve/handler (edit-req {:file "new" :path "root.edn" :ops [{:op "undo"}]}))
    (is (= original (slurp root)))
    ;; the reverse direction: edit via `path`, undo via `file`
    (serve/handler (edit-req {:file "new" :path "root.edn" :ops [{:op "add-node" :id "c"}]}))
    (is (clojure.string/includes? (slurp root) ":c nil"))
    (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]}))
    (is (= original (slurp root)))))

;; --- suffix compare with refs ------------------------------------------

(defn- suffix-tree!
  "A root with a forked ref target, an unforked one, and a fork-only one,
  written under `dir`."
  [dir]
  (let [p (write! dir "root.edn" "{:nodes {:api {:ref \"sub/api.edn\"} :lone {:ref \"lone.edn\"} :fresh {:ref \"fresh.edn\"}}}")]
    (write! dir "root-next.edn" "{:nodes {:api {:ref \"sub/api.edn\"} :lone {:ref \"lone.edn\"} :fresh {:ref \"fresh.edn\"} :added nil}}")
    (write! dir "sub/api.edn" "{:nodes {:h nil}}")
    (write! dir "sub/api-next.edn" "{:nodes {:h nil :h2 nil}}")
    (write! dir "lone.edn" "{:nodes {:l nil}}")
    (write! dir "fresh-next.edn" "{:nodes {:f nil}}")
    (serve! p "next")
    dir))

(deftest sides-pairs-a-path-with-its-fork
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [root (serve/sides nil)
            sub (serve/sides "sub/api.edn")]
        (is (= "root.edn" (.getName (:old root))))
        (is (= "root-next.edn" (.getName (:new root))))
        (is (= "api.edn" (.getName (:old sub))))
        (is (= "api-next.edn" (.getName (:new sub))))
        ;; a referenced file may lack one side: both Files come back
        (is (= ["lone.edn" "lone-next.edn"] (map #(.getName %) ((juxt :old :new) (serve/sides "lone.edn")))))
        (is (not (.isFile (:new (serve/sides "lone.edn")))))
        (is (not (.isFile (:old (serve/sides "fresh.edn")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^no such file: nowhere\.edn$"
                              (serve/sides "nowhere.edn")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves the served folder"
                              (serve/sides "../x.edn")))
        (serve! (.getPath (java.io.File. dir "root.edn")))
        (is (nil? (:old (serve/sides nil))))
        (is (= "api.edn" (.getName (:new (serve/sides "sub/api.edn")))))))))

(deftest api-graph-file-param-compares-the-pair-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [sub (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fapi.edn"})))
            lone (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=lone.edn"})))
            fresh (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=fresh.edn"})))]
        (is (= {"old" "sub/api.edn" "new" "sub/api-next.edn"} (get sub "compare")))
        (is (= "sub/api.edn" (get sub "path")))
        (is (= "api-next.edn" (get sub "file")))
        (is (= "added" (get-in sub ["nodes" "h2" "diff"])))
        (is (true? (get sub "editable")))
        (is (true? (get sub "editable-old")))
        ;; no fork: the original compared with itself, nothing changed
        (is (nil? (get lone "error")))
        (is (= {"old" "lone.edn" "new" "lone-next.edn"} (get lone "compare")))
        (is (nil? (get-in lone ["nodes" "l" "diff"])))
        ;; no original: everything in the fork is added
        (is (= "added" (get-in fresh ["nodes" "f" "diff"])))))))

(deftest api-version-and-source-per-side-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [old (java.io.File. dir "sub/api.edn")
            new (java.io.File. dir "sub/api-next.edn")]
        (is (= (str (.lastModified old) "-" (.lastModified new))
               (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=sub%2Fapi.edn"}))) "mtime")))
        (is (= (str (.lastModified (java.io.File. dir "lone.edn")) "-0")
               (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=lone.edn"}))) "mtime")))
        (is (= 0 (get (json/parse-string (:body (serve/handler {:uri "/api/version" :query-string "file=nowhere.edn"}))) "mtime")))
        (is (= (slurp old) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn&which=old"}))))
        (is (= (slurp new) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn&which=new"}))))
        (is (= (slurp new) (:body (serve/handler {:uri "/api/source" :query-string "file=sub%2Fapi.edn"}))))
        ;; a missing fork reads as its original, a missing original as empty
        (is (= "{:nodes {:l nil}}" (:body (serve/handler {:uri "/api/source" :query-string "file=lone.edn"}))))
        (is (= serve/empty-graph (:body (serve/handler {:uri "/api/source" :query-string "file=fresh.edn&which=old"}))))
        (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "file=nowhere.edn"}))))))))

(deftest api-edit-path-and-file-pick-the-side-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [old (java.io.File. dir "sub/api.edn")
            new (java.io.File. dir "sub/api-next.edn")]
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :path "sub/api.edn" :ops [{:op "add-node" :id "o"}]})))) "ok")))
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "sub/api.edn" :ops [{:op "add-node" :id "n"}]})))) "ok")))
        (is (clojure.string/includes? (slurp old) ":o nil"))
        (is (not (clojure.string/includes? (slurp old) ":n nil")))
        (is (clojure.string/includes? (slurp new) ":n nil"))
        ;; independent undo stacks
        (serve/handler (edit-req {:file "old" :path "sub/api.edn" :ops [{:op "undo"}]}))
        (is (= "{:nodes {:h nil}}" (slurp old)))
        (is (clojure.string/includes? (slurp new) ":n nil"))
        ;; the root keeps the strict check
        (.delete (java.io.File. dir "root-next.edn"))
        (is (= "no root-next.edn — create it with: simpleviz fork root.edn next"
               (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "z"}]})))) "error")))))))

(deftest api-edit-creates-the-missing-side-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      ;; new side missing: the fork starts as a copy of the original
      (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "lone.edn" :ops [{:op "add-node" :id "z"}]})))) "ok")))
      (is (= "{:nodes {:l nil}}" (slurp (java.io.File. dir "lone.edn"))))
      (let [fork (slurp (java.io.File. dir "lone-next.edn"))]
        (is (clojure.string/includes? fork ":l nil"))
        (is (clojure.string/includes? fork ":z nil")))
      ;; old side missing: the original starts empty
      (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :path "fresh.edn" :ops [{:op "add-node" :id "o"}]})))) "ok")))
      (is (clojure.string/includes? (slurp (java.io.File. dir "fresh.edn")) ":o nil"))
      (is (= "{:nodes {:f nil}}" (slurp (java.io.File. dir "fresh-next.edn"))))
      ;; a failed edit creates nothing
      (write! dir "solo.edn" "{:nodes {:s nil}}")
      (is (some? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "solo.edn" :ops [{:op "bogus"}]})))) "error")))
      (is (not (.exists (java.io.File. dir "solo-next.edn")))))))

(defn- create-req [body & [opts]]
  (assoc (edit-req body opts) :uri "/api/create"))

(defn- create! [body]
  (json/parse-string (:body (serve/handler (create-req body)))))

(deftest api-create-writes-an-empty-graph-with-its-folders
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "root.edn" "{:nodes {:a {:ref \"deep/er/new.edn\"}}}"))
      (is (= {"ok" true "created" "deep/er/new.edn"} (create! {:path "deep/er/new.edn" :file "new"})))
      (is (= serve/empty-graph (slurp (java.io.File. dir "deep/er/new.edn"))))
      ;; the new file is editable right away
      (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "deep/er/new.edn" :ops [{:op "add-node" :id "n"}]})))) "ok")))
      (is (contains? (get (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=deep%2Fer%2Fnew.edn"}))) "nodes") "n")))))

(deftest api-create-never-overwrites
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "root.edn" "{:nodes {:a nil}}"))
      (write! dir "there.edn" "{:nodes {:keep nil}}")
      (is (= {"ok" true "created" nil} (create! {:path "there.edn" :file "new"})))
      (is (= "{:nodes {:keep nil}}" (slurp (java.io.File. dir "there.edn")))))))

(deftest api-create-refusals
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "root.edn" "{:nodes {:a nil}}"))
      (is (re-find #"leaves the served folder" (get (create! {:path "../x.edn" :file "new"}) "error")))
      (is (re-find #"not an \.edn or \.png" (get (create! {:path "x.txt" :file "new"}) "error")))
      (is (= "PNG files cannot be created" (get (create! {:path "x.png" :file "new"}) "error")))
      (is (= "path required" (get (create! {:file "new"}) "error")))
      (is (not (.exists (java.io.File. dir "x.png"))))
      (let [resp (serve/handler (create-req {:path "y.edn" :file "new"} {:headers {"origin" "http://evil.example"}}))]
        (is (= 403 (:status resp)))
        (is (not (.exists (java.io.File. dir "y.edn")))))
      (is (= 405 (:status (serve/handler {:uri "/api/create" :request-method :get})))))))

(deftest api-create-in-suffix-mode-follows-the-edit-side
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (is (= {"ok" true "created" "a/n-next.edn"} (create! {:path "a/n.edn" :file "new"})))
      (is (.isFile (java.io.File. dir "a/n-next.edn")))
      (is (not (.exists (java.io.File. dir "a/n.edn"))))
      (is (= {"ok" true "created" "a/o.edn"} (create! {:path "a/o.edn" :file "old"})))
      (is (.isFile (java.io.File. dir "a/o.edn")))
      (is (not (.exists (java.io.File. dir "a/o-next.edn"))))
      ;; either side existing means there is nothing to create: an empty
      ;; fork next to an original would show everything as removed
      (is (= {"ok" true "created" nil} (create! {:path "lone.edn" :file "new"})))
      (is (not (.exists (java.io.File. dir "lone-next.edn"))))
      (is (= {"ok" true "created" nil} (create! {:path "fresh.edn" :file "old"})))
      (is (not (.exists (java.io.File. dir "fresh.edn"))))
      ;; both created files open as an unchanged comparison
      (is (nil? (get (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=a%2Fn.edn"}))) "error"))))))

(deftest api-edit-without-path-picks-the-root-sides-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [root (java.io.File. dir "root.edn")
            root-next (java.io.File. dir "root-next.edn")]
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops [{:op "add-node" :id "r"}]})))) "ok")))
        (is (clojure.string/includes? (slurp root) ":r nil"))
        (is (not (clojure.string/includes? (slurp root-next) ":r nil")))
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "s"}]})))) "ok")))
        (is (clojure.string/includes? (slurp root-next) ":s nil"))
        (is (not (clojure.string/includes? (slurp root) ":s nil")))))))

;; --- Advisory write locks (/api/lock, /api/unlock) --------------------

(defn- lock-req
  "A POST to /api/lock or /api/unlock, shaped like edit-req."
  [uri body]
  (assoc (edit-req body) :uri uri))

(defn- lock-resp [uri body]
  (let [resp (serve/handler (lock-req uri body))]
    [(:status resp) (json/parse-string (:body resp))]))

(deftest api-lock-grants-renews-and-refuses-a-second-owner
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a nil}}"))
      (is (= [200 {"ok" true "ttl" 60}] (lock-resp "/api/lock" {:owner "a"})))
      (is (= [200 {"ok" true "ttl" 60}] (lock-resp "/api/lock" {:owner "a" :path "g.edn"})) "same owner renews")
      (is (= [409 {"error" "locked by a" "owner" "a"}] (lock-resp "/api/lock" {:owner "b"}))))))

(deftest api-lock-is-per-file
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a nil}}"))
      (write! dir "sub/h.edn" "{:nodes {:h nil}}")
      (is (= 200 (first (lock-resp "/api/lock" {:owner "a"}))))
      (is (= 200 (first (lock-resp "/api/lock" {:owner "b" :path "sub/h.edn"})))))))

(deftest api-unlock-releases-only-for-the-owner
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a nil}}"))
      (is (= [200 {"ok" true}] (lock-resp "/api/unlock" {:owner "a"})) "nothing held is fine")
      (lock-resp "/api/lock" {:owner "a"})
      (is (= [409 {"error" "locked by a" "owner" "a"}] (lock-resp "/api/unlock" {:owner "b"})))
      (is (= [200 {"ok" true}] (lock-resp "/api/unlock" {:owner "a"})))
      (is (= 200 (first (lock-resp "/api/lock" {:owner "b"})))))))

(deftest expired-lock-can-be-taken-over
  (is (= {:ok true :ttl 60} (do (reset! serve/locks {}) (serve/acquire-lock! "/x.edn" "a" 1000))))
  (is (= {:error "locked by a" :owner "a"} (serve/acquire-lock! "/x.edn" "b" (+ 1000 59999))))
  (is (= {:ok true :ttl 60} (serve/acquire-lock! "/x.edn" "b" (+ 1000 60000)))))

(deftest api-lock-refuses-bad-requests
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a nil}}"))
      (is (= [400 {"error" "owner must be a non-empty string"}] (lock-resp "/api/lock" {})))
      (is (= [400 {"error" "owner must be a non-empty string"}] (lock-resp "/api/lock" {:owner ""})))
      (is (= [400 {"error" "../x.edn leaves the served folder"}] (lock-resp "/api/lock" {:owner "a" :path "../x.edn"})))
      (is (= 405 (:status (serve/handler {:uri "/api/lock" :request-method :get}))))
      (is (= 405 (:status (serve/handler {:uri "/api/unlock" :request-method :get}))))
      (is (= 403 (:status (serve/handler (assoc-in (lock-req "/api/lock" {:owner "a"})
                                                   [:headers "origin"] "http://evil.example"))))))))

(deftest api-edit-is-refused-while-the-file-is-locked
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "g.edn" "{:nodes {:a nil}}")]
        (serve! p)
        (lock-resp "/api/lock" {:owner "agent"})
        (is (= "locked by agent"
               (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]})))) "error")))
        (is (= "locked by agent"
               (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]})))) "error")))
        (is (= "{:nodes {:a nil}}" (slurp p)))
        (lock-resp "/api/unlock" {:owner "agent"})
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]})))) "ok")))))))

(deftest api-edit-lock-follows-the-compare-side
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "g.edn" "{:nodes {:a nil}}")]
        (write! dir "g-next.edn" "{:nodes {:a nil}}")
        (serve! p "next")
        (lock-resp "/api/lock" {:owner "agent" :path "g-next.edn"})
        (is (= "locked by agent"
               (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]})))) "error")))
        (is (true? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops [{:op "add-node" :id "b"}]})))) "ok")))))))

(defn- symlink! [dir rel target]
  (java.nio.file.Files/createSymbolicLink
   (.toPath (java.io.File. dir rel)) (.toPath (java.io.File. target))
   (make-array java.nio.file.attribute.FileAttribute 0)))

(deftest a-dangling-symlink-cannot-carry-a-write-out-of-the-served-folder
  (with-temp-dir*
    (fn [outer]
      (let [dir (java.io.File. outer "served")
            outside (java.io.File. outer "outside")]
        (.mkdirs outside)
        (suffix-tree! dir)
        ;; canonicalization leaves a link with a missing target alone, so
        ;; containment alone would let these through
        (symlink! dir "dang.edn" "../outside/evil.edn")
        (symlink! dir "lone-next.edn" "../outside/evil2.edn")
        (is (re-find #"symlink" (get (create! {:path "dang.edn" :file "old"}) "error")))
        (is (re-find #"symlink"
                     (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "lone.edn" :ops [{:op "add-node" :id "z"}]})))) "error")))
        (is (empty? (.list outside)))))))

(deftest a-missing-png-side-is-read-only
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (write! dir "pic-next.png" (png-bytes* [(itxt* "simpleviz-edn" "{:nodes {:p nil}}")]))
      (let [g (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=pic.png"})))]
        (is (nil? (get g "error")))
        (is (false? (get g "editable-old"))))
      (is (= "PNG sources are read-only"
             (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :path "pic.png" :ops [{:op "add-node" :id "z"}]})))) "error")))
      (is (not (.exists (java.io.File. dir "pic.png")))))))

(deftest undo-does-not-bring-back-a-deleted-file
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (serve/handler (edit-req {:file "new" :path "lone.edn" :ops [{:op "add-node" :id "z"}]}))
      (.delete (java.io.File. dir "lone-next.edn"))
      (is (= "nothing to undo"
             (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :path "lone.edn" :ops [{:op "undo"}]})))) "error")))
      (is (not (.exists (java.io.File. dir "lone-next.edn")))))))

(deftest a-failed-edit-to-a-missing-original-creates-nothing
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (is (some? (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :path "fresh.edn" :ops [{:op "bogus"}]})))) "error")))
      (is (not (.exists (java.io.File. dir "fresh.edn")))))))

(deftest a-ref-naming-a-fork-is-refused-in-suffix-mode
  (with-temp-dir*
    (fn [dir]
      (suffix-tree! dir)
      (let [msg "sub/api-next.edn is the fork of sub/api.edn — ref the original"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is the fork of" (serve/sides "sub/api-next.edn")))
        (is (= msg (get (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=sub%2Fapi-next.edn"}))) "error")))
        (is (= msg (get (create! {:path "sub/api-next.edn" :file "new"}) "error")))
        (is (not (.exists (java.io.File. dir "sub/api-next-next.edn"))))
        ;; a name that merely ends in the suffix is fine
        (write! dir "connext.edn" "{:nodes {:c nil}}")
        (is (nil? (get (json/parse-string (:body (serve/handler {:uri "/api/graph" :query-string "file=connext.edn"}))) "error")))
        ;; without a suffix nothing is a fork
        (serve! (.getPath (java.io.File. dir "root.edn")))
        (is (= "api-next.edn" (.getName (:new (serve/sides "sub/api-next.edn")))))))))

(defn- api-errors
  "GET /api/errors (with an optional query string), parsed."
  ([] (api-errors nil))
  ([qs] (json/parse-string (:body (serve/handler {:uri "/api/errors" :query-string qs})))))

(deftest api-errors-clean-file-reports-nothing
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a {} :b {}} :edges {[:a :b] {}}}"))
      (is (= {"error" nil "warnings" []} (api-errors))))))

(deftest api-errors-reports-the-validation-warnings-only
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a {}} :edges {[:a :zz] {}}}"))
      (let [out (api-errors)]
        (is (= #{"error" "warnings"} (set (keys out))) "no graph payload")
        (is (nil? (get out "error")))
        (is (= 1 (count (get out "warnings"))))
        (is (clojure.string/includes? (first (get out "warnings")) "zz"))))))

(deftest api-errors-reports-a-parse-error
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a {}"))
      (let [out (api-errors)]
        (is (string? (get out "error")))
        (is (= [] (get out "warnings")))))))

(deftest api-errors-file-param-checks-a-referenced-file
  (with-temp-dir*
    (fn [dir]
      (serve! (write! dir "g.edn" "{:nodes {:a {:ref \"sub/x.edn\"}}}"))
      (write! dir "sub/x.edn" "{:boxes {:b {:components #{:missing}}}}")
      (let [out (api-errors "file=sub%2Fx.edn")]
        (is (nil? (get out "error")))
        (is (= 1 (count (get out "warnings"))))
        (is (clojure.string/includes? (first (get out "warnings")) "missing")))
      (is (clojure.string/includes? (get (api-errors "file=..%2Fx.edn") "error")
                                    "leaves the served folder")))))

(deftest api-errors-compare-mode-names-the-file-of-each-warning
  (with-temp-dir*
    (fn [dir]
      (let [p (write! dir "g.edn" "{:nodes {:a {}}}")]
        (write! dir "g-next.edn" "{:nodes {:a {}} :edges {[:a :zz] {}}}")
        (serve! p "next")
        (let [ws (get (api-errors) "warnings")]
          (is (= 1 (count ws)))
          (is (clojure.string/starts-with? (first ws) "g-next.edn: ")))))))

(deftest api-errors-parse-error-is-logged-under-its-own-route
  (with-log-dir*
    (fn [d]
      (let [run (log/init! {:dir d :debug true :header "hdr"})
            g (java.io.File/createTempFile "serve-test" ".edn")]
        (.deleteOnExit g)
        (spit g "{:nodes {:a {}")
        (serve! (str g))
        (api-errors)
        (let [line (->> (slurp run) clojure.string/split-lines (filter #(re-find #" error " %)) first)]
          (is (some? line))
          (is (re-find #"\"route\":\"/api/errors\"" line) line))))))

(deftest bb-serve-finds-its-frontend-from-any-working-directory
  (let [tmp (babashka.fs/create-temp-dir {:prefix "serve-cwd"})
        port (proc-util/free-port)
        proc (proc-util/start ["bb" "--config" (str proc-util/repo-root "/bb.edn") "-m" "serve"
                               (str proc-util/repo-root "/examples/demo.edn") "--port" (str port)]
                              :dir tmp)]
    (try
      (is (some? (proc-util/await-line proc #"^simpleviz: serving " 30000)))
      (is (str/includes? (slurp (str "http://127.0.0.1:" port "/")) "<html"))
      (finally
        (p/destroy-tree proc)
        (babashka.fs/delete-tree tmp)))))

(deftest version-comes-from-the-classpath
  ;; the tarball layout: server/ plus the install root on the classpath,
  ;; VERSION in that root; run from elsewhere so the cwd cannot help
  (let [home (babashka.fs/create-temp-dir {:prefix "serve-version"})]
    (try
      ;; bb refuses absolute :paths, so the install gets its own server/
      (babashka.fs/copy-tree (str proc-util/repo-root "/server") (babashka.fs/path home "server"))
      (spit (str (babashka.fs/path home "VERSION")) "v1.2.3\n")
      (spit (str (babashka.fs/path home "bb.edn"))
            (pr-str {:paths ["server" "."]
                     :deps (:deps (edn/read-string (slurp "bb.edn")))}))
      (let [res (p/shell {:out :string :err :string :continue true :dir proc-util/repo-root}
                         "bb" "--config" (str (babashka.fs/path home "bb.edn"))
                         "-e" "(require 'serve) (println (serve/version))")]
        (is (= "v1.2.3" (str/trim (:out res))) (:err res)))
      (finally (babashka.fs/delete-tree home)))))

(deftest static-files-refuse-dot-dot-and-directories
  (is (= 200 (:status (serve/handler {:uri "/style.css"}))))
  (is (= "text/css; charset=utf-8"
         (get-in (serve/handler {:uri "/style.css"}) [:headers "Content-Type"])))
  ;; "." is on the classpath, so public/../bb.edn would resolve without the guard
  (is (= 404 (:status (serve/handler {:uri "/../bb.edn"}))))
  (is (= 404 (:status (serve/handler {:uri "/vendor"}))))
  (is (= 404 (:status (serve/handler {:uri "/vendor/"})))))
