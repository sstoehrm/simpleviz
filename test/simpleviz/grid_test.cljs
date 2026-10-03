(ns simpleviz.grid-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.grid :refer [top-of edge-ends top-items grid-cells grid-mode? attach-loose]]))

(defn- n [id] {:id id :name id :type "" :attrs {}})
(defn- box [nm grid comps] {:name nm :grid grid :components comps :type "" :attrs {}})
(defn- e [s t] {:id (str s ">" t) :source s :target t})

;; front [0 0] holds web, back [1 0] holds api; user → web (left of
;; front), api → mail (right of back), x2 hangs off mail, lone and the
;; p1–p2 pair reach no gridded box
(def g
  {:nodes {"user" (n "user") "web" (n "web") "api" (n "api") "mail" (n "mail")
           "x2" (n "x2") "lone" (n "lone") "p1" (n "p1") "p2" (n "p2")}
   :boxes [(box "front" {:col 0 :row 0 :w 1 :h 1} ["n:web"])
           (box "back" {:col 1 :row 0 :w 1 :h 1} ["n:api"])]
   :parent-of {"n:web" "front" "n:api" "back"}
   :edges [(e "user" "web") (e "api" "mail") (e "x2" "mail") (e "p1" "p2")]})

(test "top-of and edge-ends name top-level elk ids"
  (fn []
    (assert/equal (top-of (:parent-of g) "n:web") "b:front")
    (assert/equal (top-of (:parent-of g) "n:user") "n:user")
    (assert/equal (top-of (:parent-of g) "b:front") "b:front")
    (assert/deepEqual (edge-ends (e "user" "web")) ["n:user" "n:web"])
    (assert/deepEqual (edge-ends {:source "a" :target "x" :source-id "b:a"}) ["b:a" "n:x"])))

(test "top-items lists top-level nodes then boxes"
  (fn []
    (assert/deepEqual (top-items g) ["n:user" "n:mail" "n:x2" "n:lone" "n:p1" "n:p2" "b:front" "b:back"])))

(test "grid-cells keeps top-level gridded boxes, first by sorted name on overlap"
  (fn []
    (assert/deepEqual (js/Object.keys (grid-cells g)) ["b:back" "b:front"])
    (assert/ok (grid-mode? g))
    (assert/ok (not (grid-mode? (assoc g :boxes [(box "front" nil ["n:web"])]))))
    (let [o (assoc g :boxes [(box "zeta" {:col 0 :row 0 :w 2 :h 1} ["n:web"])
                             (box "alpha" {:col 1 :row 0 :w 1 :h 1} ["n:api"])])]
      (assert/deepEqual (js/Object.keys (grid-cells o)) ["b:alpha"]))
    ;; a gridded box inside another box is not a cell
    (let [nested (assoc g :parent-of (assoc (:parent-of g) "b:back" "front"))]
      (assert/deepEqual (js/Object.keys (grid-cells nested)) ["b:front"]))))

(test "attach-loose: beside the box with most edges, chains, the rest in the strip"
  (fn []
    (let [{:keys [attached strip]} (attach-loose g (grid-cells g))]
      (assert/deepEqual (get attached "n:user") {:anchor "b:front" :side "left"})
      (assert/deepEqual (get attached "n:mail") {:anchor "b:back" :side "right"})
      (assert/deepEqual (get attached "n:x2") {:anchor "b:back" :side "right"})
      (assert/deepEqual strip ["n:lone" "n:p1" "n:p2"]))))

(test "attach-loose: a tie goes to the box first by sorted id"
  (fn []
    (let [t (-> g
                (assoc-in [:nodes "t"] (n "t"))
                (assoc :edges [(e "t" "web") (e "api" "t")]))
          {:keys [attached]} (attach-loose t (grid-cells t))]
      ;; one edge each way: b:back < b:front; t is the target of api → right
      (assert/deepEqual (get attached "n:t") {:anchor "b:back" :side "right"}))))
