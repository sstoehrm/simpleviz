(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [serve]))

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
  (is (= {:file "g.edn" :port 7373} (serve/parse-args ["g.edn"]))))

(deftest parse-args-accepts-port-flag-and-alias
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["g.edn" "--port" "9000"])))
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["g.edn" "-p" "9000"])))
  (is (= {:file "g.edn" :port 9000} (serve/parse-args ["--port" "9000" "g.edn"]))))

(deftest parse-args-rejects-bad-input
  (is (contains? (serve/parse-args []) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "abc"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "0"]) :error))
  (is (contains? (serve/parse-args ["g.edn" "--port" "70000"]) :error)))

(deftest parse-args-two-files-enables-compare
  (is (= {:old-file "a.edn" :file "b.edn" :port 7373}
         (serve/parse-args ["a.edn" "b.edn"])))
  (is (= {:old-file "a.edn" :file "b.edn" :port 9000}
         (serve/parse-args ["a.edn" "b.edn" "-p" "9000"]))))

(deftest parse-args-rejects-three-files
  (is (contains? (serve/parse-args ["a.edn" "b.edn" "c.edn"]) :error)))

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
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))
    (is (clojure.string/starts-with?
         (get-in resp [:headers "Content-Type"]) "text/plain"))))

(deftest api-source-compare-selects-files
  (reset! serve/files {:old "examples/demo.edn" :new "examples/demo-next.edn"})
  (is (= (slurp "examples/demo.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source" :query-string "which=new"}))))
  (is (= (slurp "examples/demo-next.edn")
         (:body (serve/handler {:uri "/api/source"})))))

(deftest api-source-old-without-compare-404s
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (is (= 404 (:status (serve/handler {:uri "/api/source" :query-string "which=old"})))))

(deftest api-source-deleted-file-404s-instead-of-500
  ;; A file that vanished between serve-startup and the request (e.g.
  ;; deleted mid-serve) must not slurp-throw a raw exception message
  ;; (incl. the absolute path) back to the client as a 500.
  (reset! serve/files {:old nil :new "test/fixtures/does-not-exist.edn"})
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 404 (:status resp)))
    (is (= "not found" (:body resp)))))

(deftest api-source-which-regex-is-anchored
  ;; A substring match like "awhich=old" must not be treated as
  ;; which=old — it should fall through to the default (single-file
  ;; mode: :new, unaffected by :old since it's nil here).
  (reset! serve/files {:old nil :new "examples/demo.edn"})
  (let [resp (serve/handler {:uri "/api/source" :query-string "awhich=old"})]
    (is (= 200 (:status resp)))
    (is (= (slurp "examples/demo.edn") (:body resp)))))

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

(defn- temp-png* [chunks]
  (let [f (java.io.File/createTempFile "serve-test" ".png")]
    (.deleteOnExit f)
    (with-open [os (clojure.java.io/output-stream f)]
      (.write os (byte-array (map unchecked-byte
                                  (concat [137 80 78 71 13 10 26 10]
                                          (apply concat chunks))))))
    (.getPath f)))

(deftest api-graph-serves-png-embedded-edn
  (reset! serve/files {:old nil :new "test/fixtures/embedded.png"})
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
    (is (contains? (get out "nodes") "a"))
    (is (= "embedded.png" (get out "file")))
    (is (not (contains? out "compare")))))

(deftest api-graph-auto-compares-a-compare-export-png
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (reset! serve/files {:old nil :new f})
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (= "added" (get-in out ["nodes" "b" "diff"])))
      (is (contains? out "compare")))))

(deftest api-graph-two-file-compare-accepts-png-sides
  (let [f (temp-png* [(itxt* "simpleviz-edn-new" "{:nodes {:a {}}}")])]
    (reset! serve/files {:old f :new "examples/demo.edn"})
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (contains? out "compare"))
      (is (= "added" (get-in out ["nodes" "web" "diff"]))))))

(deftest api-graph-png-without-edn-is-a-clear-error
  (reset! serve/files {:old nil :new "test/fixtures/plain-1x1.png"})
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
    (is (clojure.string/includes? (get out "error" "") "no embedded simpleviz EDN"))))

(deftest api-source-serves-png-embedded-edn
  (reset! serve/files {:old nil :new "test/fixtures/embedded.png"})
  (let [resp (serve/handler {:uri "/api/source"})]
    (is (= 200 (:status resp)))
    (is (= "{:nodes {:a {}}}" (:body resp)))))

(deftest api-source-compare-png-selects-sides
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:old {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:new {}}}")])]
    (reset! serve/files {:old nil :new f})
    (is (= "{:nodes {:old {}}}"
           (:body (serve/handler {:uri "/api/source" :query-string "which=old"}))))
    (is (= "{:nodes {:new {}}}"
           (:body (serve/handler {:uri "/api/source"}))))))

;; --- /api/edit: undo stack + editable flags (Task 6) ------------------

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

(deftest api-edit-applies-and-writes
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}))]
      (is (= 200 (:status resp)))
      (is (true? (get (json/parse-string (:body resp)) "ok")))
      (is (clojure.string/includes? (slurp p) ":b nil")))))

(deftest api-edit-error-leaves-file-untouched
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
    (reset! serve/undo-stacks {})
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "a"}]}))]
      (is (clojure.string/includes? (get (json/parse-string (:body resp)) "error") "already exists"))
      (is (= "{:nodes {:a nil}}" (slurp p)))
      (is (empty? (get @serve/undo-stacks p))))))

(deftest api-edit-undo-restores
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
    (reset! serve/undo-stacks {})
    (serve/handler (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}))
    (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]}))
    (is (= "{:nodes {:a nil}}" (slurp p)))
    (let [resp (serve/handler (edit-req {:file "new" :ops [{:op "undo"}]}))]
      (is (= "nothing to undo" (get (json/parse-string (:body resp)) "error"))))))

(deftest api-edit-refuses-png-and-missing-old
  (reset! serve/files {:old nil :new "test/fixtures/embedded.png"})
  (is (= "PNG sources are read-only"
         (get (json/parse-string (:body (serve/handler (edit-req {:file "new" :ops []})))) "error")))
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
    (is (clojure.string/includes?
         (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops []})))) "error")
         "no old file"))))

(deftest api-graph-carries-editable-flags
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
    (is (true? (get (json/parse-string (:body (serve/handler {:uri "/api/graph"}))) "editable"))))
  (reset! serve/files {:old nil :new "test/fixtures/embedded.png"})
  (is (false? (get (json/parse-string (:body (serve/handler {:uri "/api/graph"}))) "editable"))))

(deftest api-graph-compare-mode-carries-editable-flags
  (let [old-p (temp-edn "{:nodes {:a nil}}")
        new-p (temp-edn "{:nodes {:a nil :b nil}}")]
    (reset! serve/files {:old old-p :new new-p})
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (true? (get out "editable")))
      (is (true? (get out "editable-old")))))
  (reset! serve/files {:old "test/fixtures/embedded.png" :new (temp-edn "{:nodes {:a nil}}")})
  (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
    (is (true? (get out "editable")))
    (is (false? (get out "editable-old")))))

(deftest api-graph-embedded-compare-png-is-not-editable
  (let [f (temp-png* [(itxt* "simpleviz-edn-old" "{:nodes {:a {}}}")
                      (itxt* "simpleviz-edn-new" "{:nodes {:a {} :b {}}}")])]
    (reset! serve/files {:old nil :new f})
    (let [out (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (false? (get out "editable")))
      (is (false? (get out "editable-old"))))))

(deftest api-edit-refuses-png-in-old-slot
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old "test/fixtures/embedded.png" :new p})
    (reset! serve/undo-stacks {})
    (is (= "PNG sources are read-only"
           (get (json/parse-string (:body (serve/handler (edit-req {:file "old" :ops []})))) "error")))))

;; --- /api/edit: Origin + Content-Type guard (Task 13) ------------------

(deftest api-edit-rejects-foreign-origin
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
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
      (reset! serve/files {:old nil :new p})
      (reset! serve/undo-stacks {})
      (spit p "{:nodes {:a nil}}")
      (let [resp (serve/handler
                  (edit-req {:file "new" :ops [{:op "add-node" :id "b"}]}
                            {:headers {"origin" origin}}))]
        (is (= 200 (:status resp)))
        (is (true? (get (json/parse-string (:body resp)) "ok")))))))

(deftest api-edit-rejects-missing-content-type
  (let [p (temp-edn "{:nodes {:a nil}}")]
    (reset! serve/files {:old nil :new p})
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
    (reset! serve/files {:old nil :new p})
    (reset! serve/undo-stacks {})
    (serve/handler (edit-req {:file "new"
                              :ops [{:op "add-node" :id "api"}
                                    {:op "add-edge" :from "web" :to "api" :direction "->"}]}))
    (let [g (json/parse-string (:body (serve/handler {:uri "/api/graph"})))]
      (is (contains? (get g "nodes") "api"))
      (is (= 1 (count (get g "edges")))))
    (is (clojure.string/includes? (slurp p) ";; a comment"))))
