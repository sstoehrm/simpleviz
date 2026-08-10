(ns simpleviz.png)

;; PNG iTXt chunk splicing for EDN-carrying exports (issue #31, PR B).
;; Pure byte manipulation on Uint8Array — no DOM — so node tests cover
;; it. The babashka twin (server/png.clj) reads what this writes.

(def ^:private crc-table
  (let [t (js/Uint32Array. 256)]
    (dotimes [n 256]
      (loop [c n k 0]
        (if (< k 8)
          (recur (if (pos? (bit-and c 1))
                   (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1))
                   (unsigned-bit-shift-right c 1))
                 (inc k))
          (aset t n (unsigned-bit-shift-right c 0)))))
    t))

(defn crc32 [u8]
  (loop [crc 0xFFFFFFFF i 0]
    (if (< i (.-length u8))
      (recur (bit-xor (unsigned-bit-shift-right crc 8)
                      (aget crc-table (bit-and (bit-xor crc (aget u8 i)) 0xFF)))
             (inc i))
      (unsigned-bit-shift-right (bit-xor crc 0xFFFFFFFF) 0))))

(defn chunk-seq
  "PNG chunks as {:type :start :length}; :start is the offset of the
  4-byte length field. Assumes a well-formed stream after the 8-byte
  signature."
  [u8]
  (loop [i 8 acc []]
    (if (>= (+ i 8) (.-length u8))
      acc
      (let [len (+ (* (aget u8 i) 16777216)
                   (* (aget u8 (+ i 1)) 65536)
                   (* (aget u8 (+ i 2)) 256)
                   (aget u8 (+ i 3)))
            type (js/String.fromCharCode
                  (aget u8 (+ i 4)) (aget u8 (+ i 5))
                  (aget u8 (+ i 6)) (aget u8 (+ i 7)))]
        (recur (+ i 12 len) (conj acc {:type type :start i :length len}))))))

(defn- be32-bytes [n]
  [(bit-and (unsigned-bit-shift-right n 24) 0xFF)
   (bit-and (unsigned-bit-shift-right n 16) 0xFF)
   (bit-and (unsigned-bit-shift-right n 8) 0xFF)
   (bit-and n 0xFF)])

(defn- itxt-chunk
  "Complete iTXt chunk bytes (length + type + data + crc) for an
  uncompressed UTF-8 text with empty language/translated fields."
  [kw text]
  (let [enc (js/TextEncoder.)
        kw-b (.encode enc kw)
        txt-b (.encode enc text)
        data-len (+ (.-length kw-b) 5 (.-length txt-b))
        type+data (js/Uint8Array. (+ 4 data-len))]
    ;; "iTXt"
    (.set type+data (js/Uint8Array.from [105 84 88 116]) 0)
    (.set type+data kw-b 4)
    ;; NUL, compression flag 0, compression method 0, empty language NUL,
    ;; empty translated keyword NUL
    (.set type+data (js/Uint8Array.from [0 0 0 0 0]) (+ 4 (.-length kw-b)))
    (.set type+data txt-b (+ 4 (.-length kw-b) 5))
    (let [out (js/Uint8Array. (+ 4 (.-length type+data) 4))]
      (.set out (js/Uint8Array.from (be32-bytes data-len)) 0)
      (.set out type+data 4)
      (.set out (js/Uint8Array.from (be32-bytes (crc32 type+data)))
            (+ 4 (.-length type+data)))
      out)))

(defn embed-text
  "New Uint8Array with the iTXt chunk inserted right after IHDR."
  [u8 kw text]
  (let [ihdr (first (chunk-seq u8))
        cut (+ (:start ihdr) 12 (:length ihdr))
        chunk (itxt-chunk kw text)
        out (js/Uint8Array. (+ (.-length u8) (.-length chunk)))]
    (.set out (.subarray u8 0 cut) 0)
    (.set out chunk cut)
    (.set out (.subarray u8 cut) (+ cut (.-length chunk)))
    out))

(defn embed-many [u8 pairs]
  (reduce (fn [acc [kw text]] (embed-text acc kw text)) u8 pairs))

(defn extract-text
  "Text of the iTXt chunk with the given keyword, or nil."
  [u8 kw]
  (let [dec (js/TextDecoder.)]
    (or
     (some (fn [{:keys [type start length]}]
            (when (= type "iTXt")
              (let [data (.subarray u8 (+ start 8) (+ start 8 length))
                    z (loop [i 0]
                        (if (or (>= i (.-length data)) (zero? (aget data i)))
                          i
                          (recur (inc i))))]
                (when (= (.decode dec (.subarray data 0 z)) kw)
                  ;; skip NUL, flag, method, then two NUL-terminated
                  ;; (empty) fields
                  (let [after (loop [i (+ z 3) nulls 0]
                                (if (= nulls 2)
                                  i
                                  (recur (inc i)
                                         (if (zero? (aget data i))
                                           (inc nulls)
                                           nulls))))]
                    (.decode dec (.subarray data after)))))))
          (chunk-seq u8))
     nil)))
