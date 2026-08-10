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

;; --- Adversarial: iTXt with no terminator NULs ------------------------
;;
;; Hand-built (not the fixture) so the malformation is explicit: a
;; well-formed keyword + its NUL, then a non-zero compression flag and
;; method byte and no further NULs anywhere in the chunk's data. The
;; two-NUL scan that looks for the end of the flag/method/language/
;; translated-keyword region must stop at the end of that chunk's data
;; instead of counting forever. Before the fix this hangs the process
;; (confirmed via `node --test --test-timeout=2000`, which reports the
;; test as timed out rather than failed/passed); after the fix it
;; returns nil promptly.

(defn- str->codes [s] (vec (map #(.charCodeAt s %) (range (.-length s)))))

(defn- be32 [n]
  [(bit-and (unsigned-bit-shift-right n 24) 0xff)
   (bit-and (unsigned-bit-shift-right n 16) 0xff)
   (bit-and (unsigned-bit-shift-right n 8) 0xff)
   (bit-and n 0xff)])

(test "extract-text returns nil (does not hang) when terminator NULs never appear"
  (fn []
    (let [kw "simpleviz-edn"
          data (vec (concat (str->codes kw) [0]     ; keyword + its NUL
                            [1 1]                    ; non-zero flag/method
                            [65 66 67]))              ; padding, no NULs
          placeholder-sig (repeat 8 0)
          bytes (js/Uint8Array.from
                 (vec (concat placeholder-sig
                              (be32 (count data))
                              (str->codes "iTXt")
                              data)))]
      (assert/equal (png/extract-text bytes kw) nil))))
