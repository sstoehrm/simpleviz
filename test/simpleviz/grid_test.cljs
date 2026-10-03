(ns simpleviz.grid-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.grid :refer [top-of edge-ends top-items grid-cells grid-mode? attach-loose
                                   slots port-side tracks base-gaps track-sizes axis centres widen]]))

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

(test "slots: cells on 3i+1 tracks, stacks beside their anchor's span"
  (fn []
    (let [cells {"b:front" {:col 0 :row 0 :w 1 :h 1} "b:wide" {:col 1 :row 1 :w 2 :h 1}}
          att {"n:user" {:anchor "b:front" :side "left"} "n:out" {:anchor "b:wide" :side "right"}}
          s (slots cells att)]
      (assert/deepEqual (get s "b:front") {:c0 1 :c1 1 :r0 1 :r1 1})
      (assert/deepEqual (get s "b:wide") {:c0 4 :c1 7 :r0 4 :r1 4})
      (assert/deepEqual (get s "n:user") {:c0 0 :c1 0 :r0 1 :r1 1})
      (assert/deepEqual (get s "n:out") {:c0 8 :c1 8 :r0 4 :r1 4}))))

(test "port-side faces the other slot"
  (fn []
    (let [a {:c0 1 :c1 1 :r0 1 :r1 1}]
      (assert/equal (port-side a {:c0 4 :c1 4 :r0 1 :r1 1}) "EAST")
      (assert/equal (port-side a {:c0 0 :c1 0 :r0 1 :r1 1}) "WEST")
      (assert/equal (port-side a {:c0 1 :c1 4 :r0 4 :r1 4}) "SOUTH")
      (assert/equal (port-side {:c0 1 :c1 1 :r0 4 :r1 4} a) "NORTH")
      (assert/equal (port-side a a) "EAST"))))

(test "tracks and base gaps"
  (fn []
    (assert/deepEqual (tracks 2 [5 0]) [0 1 4 5])
    (assert/deepEqual (tracks 3 []) [1 4 7])
    (assert/deepEqual (base-gaps 2) [30 80 30])))

(test "track-sizes: largest single item, spans grow their last track"
  (fn []
    (let [sz (track-sizes [1 4] [{:t0 1 :t1 1 :size 100} {:t0 4 :t1 4 :size 50}
                                 {:t0 1 :t1 4 :size 300}] [30 80 30])]
      (assert/equal (get sz 1) 100)
      ;; 100 + 80 + 50 = 230 < 300: the last track grows by 70
      (assert/equal (get sz 4) 120))
    ;; an empty track stays 0 wide but keeps its gaps
    (let [ax (axis [1 4 7] [{:t0 1 :t1 1 :size 100} {:t0 7 :t1 7 :size 40}] [30 80 80 30])]
      (assert/deepEqual (:size ax) [100 0 40])
      (assert/deepEqual (:pos ax) [50 230 310]))))

(test "axis positions and gap centres"
  (fn []
    (let [ax (axis [1 4] [{:t0 1 :t1 1 :size 100} {:t0 4 :t1 4 :size 50}
                          {:t0 1 :t1 4 :size 300}] [30 80 30])]
      (assert/deepEqual (:size ax) [100 120])
      (assert/deepEqual (:pos ax) [50 230])
      (assert/equal (:end ax) 380)
      (assert/deepEqual (centres ax) [35 190 365]))))

(test "widen makes room for the lanes"
  (fn []
    (assert/deepEqual (widen [30 80 30] {"v1" 6 "h0" 9} "v") [30 100 30])
    (assert/deepEqual (widen [30 80 30] {"v1" 6 "h0" 9} "h") [130 80 30])))
