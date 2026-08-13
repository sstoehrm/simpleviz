(ns png-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [png]))

(deftest extracts-embedded-edn
  (is (= "{:nodes {:a {}}}"
         (png/extract "test/fixtures/embedded.png" "simpleviz-edn"))))

(deftest missing-keyword-returns-nil
  (is (nil? (png/extract "test/fixtures/embedded.png" "simpleviz-edn-old")))
  (is (nil? (png/extract "test/fixtures/plain-1x1.png" "simpleviz-edn"))))

(deftest non-png-throws
  (is (thrown? Exception (png/extract "README.md" "simpleviz-edn"))))

;; --- Adversarial / malformed iTXt cases ------------------------------
;;
;; These build a minimal PNG byte-for-byte in the test (signature + one
;; hand-crafted iTXt chunk) and write it to a throwaway temp file, rather
;; than committing a binary fixture, so the exact malformation each test
;; targets is visible right next to the assertion.

(def ^:private signature [137 80 78 71 13 10 26 10])

(defn- be32 [n]
  [(bit-and (bit-shift-right n 24) 0xff)
   (bit-and (bit-shift-right n 16) 0xff)
   (bit-and (bit-shift-right n 8) 0xff)
   (bit-and n 0xff)])

(defn- ascii [s] (mapv int s))

(defn- write-bytes! [file byte-vec]
  (with-open [os (io/output-stream file)]
    (.write os (byte-array (map unchecked-byte byte-vec)))))

(defn- temp-png [byte-vec]
  (let [f (java.io.File/createTempFile "malformed-itxt" ".png")]
    (.deleteOnExit f)
    (write-bytes! f byte-vec)
    (.getPath f)))

(deftest malformed-itxt-keyword-scan-runs-off-truncated-file
  ;; Declared chunk length (40) is far bigger than the 10 data bytes
  ;; actually present, and none of those 10 bytes is a NUL — so a
  ;; keyword scan bounded only by the (bogus) declared length walks
  ;; straight past the end of the real byte array.
  (let [path (temp-png (vec (concat signature (be32 40) (ascii "iTXt")
                                    (repeat 10 65))))] ; "AAAAAAAAAA", no NUL
    (is (nil? (png/extract path "simpleviz-edn")))))

(deftest malformed-itxt-terminator-scan-runs-off-truncated-file
  ;; The keyword itself is well-formed and matches, but the file is cut
  ;; off right after a couple of non-NUL bytes in the
  ;; flag/method/language/translated-keyword region — so the two-NUL
  ;; scan that looks for the end of that region walks past the end of
  ;; the real byte array before finding its second NUL.
  (let [kw (ascii "simpleviz-edn")
        data (vec (concat kw [0] [1 1])) ; kw NUL, then non-zero flag/method, no NULs
        path (temp-png (vec (concat signature (be32 100) (ascii "iTXt") data)))]
    (is (nil? (png/extract path "simpleviz-edn")))))

(deftest png?-sniffs-content-not-extension
  (is (true? (png/png? "test/fixtures/embedded.png")))
  (is (true? (png/png? "test/fixtures/plain-1x1.png")))
  (is (false? (png/png? "README.md")))
  (is (false? (png/png? "examples/demo.edn")))
  (is (false? (png/png? "test/fixtures/does-not-exist.png"))))
