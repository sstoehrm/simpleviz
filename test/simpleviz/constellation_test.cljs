(ns simpleviz.constellation-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.constellation :as c]))

(test "the constellation is the same every time"
  (fn []
    (assert/deepEqual (c/particles 7) (c/particles 7))
    (assert/notDeepEqual (c/particles 7) (c/particles 8))))

(test "every triangle lies in the view box, in a tone the page colors"
  (fn []
    (let [ps (c/particles 7)]
      (assert/ok (> (count ps) 200))
      (doseq [{:keys [points tone twinkle]} ps]
        (assert/ok (<= 0 tone 5) (str "tone " tone))
        (assert/ok (<= 0 twinkle 3) (str "twinkle " twinkle))
        (doseq [[x y] points]
          (assert/ok (and (<= 0 x c/W) (<= 0 y c/H)) (str x "," y)))))))
