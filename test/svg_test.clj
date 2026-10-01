(ns svg-test
  (:require [clojure.test :refer [deftest is]]
            [svg]))

;; The fixtures are real exports: svg/svg-document (src/simpleviz/svg.cljs)
;; wrote them, so a change to the export format shows up here.
;; embedded.svg holds EDN with ]]>, <, &, quotes and a CR LF.

(deftest extracts-embedded-edn
  (is (= "{:nodes {:a {:name \"x ]]> < & ' \\\" \r\n y\"}}}"
         (svg/extract "test/fixtures/embedded.svg" "simpleviz-edn"))))

(deftest extracts-both-sides-of-a-compare-export
  (is (= "{:nodes {:a {}}}" (svg/extract "test/fixtures/compare.svg" "simpleviz-edn-old")))
  (is (= "{:nodes {:a {} :b {}}}" (svg/extract "test/fixtures/compare.svg" "simpleviz-edn-new"))))

(deftest missing-key-returns-nil
  (is (nil? (svg/extract "test/fixtures/embedded.svg" "simpleviz-edn-old")))
  (is (nil? (svg/extract "test/fixtures/plain.svg" "simpleviz-edn"))))

(deftest non-svg-throws
  (is (thrown-with-msg? Exception #"is not an SVG file"
                        (svg/extract "README.md" "simpleviz-edn")))
  (is (thrown-with-msg? Exception #"is not an SVG file"
                        (svg/extract "test/fixtures/embedded.png" "simpleviz-edn"))))

(deftest svg?-sniffs-content
  (is (svg/svg? "test/fixtures/embedded.svg"))
  (is (not (svg/svg? "test/fixtures/embedded.png")))
  (is (not (svg/svg? "examples/demo.edn")))
  (is (not (svg/svg? "test/fixtures/no-such-file.svg"))))

(defn- temp-svg [text]
  (let [f (java.io.File/createTempFile "svg-test" ".svg")]
    (.deleteOnExit f)
    (spit f text)
    (str f)))

(deftest a-dtd-is-refused-so-no-entity-is-ever-read
  (let [secret (java.io.File/createTempFile "svg-test-secret" ".txt")
        _ (.deleteOnExit secret)
        _ (spit secret "SECRET")
        f (temp-svg (str "<?xml version=\"1.0\"?>"
                         "<!DOCTYPE svg [<!ENTITY e SYSTEM \"" (.toURI secret) "\">]>"
                         "<svg xmlns=\"http://www.w3.org/2000/svg\"><metadata"
                         " xmlns:simpleviz=\"https://github.com/sstoehrm/simpleviz\">"
                         "<simpleviz:source key=\"simpleviz-edn\">&e;</simpleviz:source>"
                         "</metadata></svg>"))]
    (is (thrown? Exception (svg/extract f "simpleviz-edn")))))

(deftest only-the-simpleviz-namespace-counts
  (let [f (temp-svg (str "<svg xmlns=\"http://www.w3.org/2000/svg\"><metadata"
                         " xmlns:other=\"https://example.com/other\">"
                         "<other:source key=\"simpleviz-edn\">{}</other:source>"
                         "</metadata></svg>"))]
    (is (nil? (svg/extract f "simpleviz-edn")))))

(deftest malformed-xml-throws-naming-the-file
  (let [f (temp-svg "<svg xmlns=\"http://www.w3.org/2000/svg\"><metadata>")]
    (is (thrown-with-msg? Exception #"svg-test" (svg/extract f "simpleviz-edn")))))
