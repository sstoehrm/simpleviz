(ns simpleviz.png-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.png :as png]))

;; smallest valid 1x1 transparent PNG
(def fixture-b64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(defn fixture [] (js/Uint8Array.from (js/Buffer.from fixture-b64 "base64")))

(test "crc32 matches the known IEND vector"
  (fn []
    (let [iend (js/Uint8Array.from [73 69 78 68])]  ; "IEND"
      (assert/equal (png/crc32 iend) 0xAE426082))))

(test "chunk-seq walks the fixture"
  (fn []
    (assert/deepEqual (mapv (fn [c] (:type c)) (png/chunk-seq (fixture)))
                      ["IHDR" "IDAT" "IEND"])))

(test "embed-text inserts an iTXt chunk right after IHDR"
  (fn []
    (let [out (png/embed-text (fixture) "simpleviz-edn" "{:nodes {:a {}}}")]
      (assert/deepEqual (mapv (fn [c] (:type c)) (png/chunk-seq out))
                        ["IHDR" "iTXt" "IDAT" "IEND"]))))

(test "embed then extract round-trips UTF-8 text"
  (fn []
    (let [edn "{:nodes {:jp {:name \"日本\"}}}"
          out (png/embed-text (fixture) "simpleviz-edn" edn)]
      (assert/equal (png/extract-text out "simpleviz-edn") edn)
      (assert/equal (png/extract-text out "other-keyword") nil)
      (assert/equal (png/extract-text (fixture) "simpleviz-edn") nil))))

(test "embed-many embeds both compare keys; empty pairs is identity"
  (fn []
    (let [out (png/embed-many (fixture) [["simpleviz-edn-old" "{:a 1}"]
                                         ["simpleviz-edn-new" "{:a 2}"]])]
      (assert/equal (png/extract-text out "simpleviz-edn-old") "{:a 1}")
      (assert/equal (png/extract-text out "simpleviz-edn-new") "{:a 2}"))
    (assert/deepEqual (png/embed-many (fixture) []) (fixture))))
