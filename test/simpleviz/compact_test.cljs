(ns simpleviz.compact-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.transform :refer [to-elk]]
            [simpleviz.grid :refer [label-at]]
            [simpleviz.compact :refer [auto-cells with-cells layout-compact best-columns]]
            [simpleviz.scene :refer [build-scene]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(defn- run-elk [input] (.layout (ELK.) input))

(defn- measure [text _font] (* (.-length text) 7))

(defn- node [id] {:id id :name id :type "" :attrs {}})

(defn- edge [i a b & [nm]]
  {:id (str "e" i) :source a :target b :arrows {:source false :target true}
   :name (or nm "") :type "" :attrs {}})

(defn- box [nm comps & [grid]] {:id (str "b:" nm) :name nm :type "" :grid grid :components comps :attrs {}})

(defn- graph [g]
  {:nodes (or (:nodes g) {})
   :edges (or (:edges g) [])
   :boxes (or (:boxes g) [])
   :boxes-by-name (reduce (fn [acc b] (assoc acc (:name b) b)) {} (or (:boxes g) []))
   :parent-of (or (:parent-of g) {})
   :warnings []})

;; four boxes a–d, one node each; a–b and c–d joined twice, b–c once
(def four
  (graph {:nodes (into {} (map (fn [n] [n (node n)])) ["a1" "b1" "c1" "d1"])
          :boxes [(box "a" ["n:a1"]) (box "b" ["n:b1"]) (box "c" ["n:c1"]) (box "d" ["n:d1"])]
          :parent-of {"n:a1" "a" "n:b1" "b" "n:c1" "c" "n:d1" "d"}
          :edges [(edge 0 "a1" "b1") (edge 1 "b1" "a1") (edge 2 "c1" "d1") (edge 3 "d1" "c1")
                  (edge 4 "b1" "c1")]}))

(defn- dist [p q] (+ (js/Math.abs (- (:col p) (:col q))) (js/Math.abs (- (:row p) (:row q)))))

(test "auto-cells: a square of cells, joined boxes next to each other"
  (fn []
    (let [cells (auto-cells four)]
      (assert/equal (count cells) 4)
      ;; four boxes: two columns
      (assert/ok (every? (fn [c] (< (:col c) 2)) (js/Object.values cells)))
      (assert/equal (count (distinct (mapv (fn [c] (str (:col c) "," (:row c))) (js/Object.values cells)))) 4)
      (assert/equal (dist (get cells "a") (get cells "b")) 1)
      (assert/equal (dist (get cells "c") (get cells "d")) 1)
      (assert/equal (dist (get cells "b") (get cells "c")) 1))))

(test "auto-cells keeps a box's own :grid and fills around it"
  (fn []
    (let [g (assoc four :boxes (assoc (:boxes four) 0 (box "a" ["n:a1"] {:col 0 :row 0 :w 2 :h 1})))
          cells (auto-cells g)]
      (assert/ok (nil? (get cells "a")) "a keeps its own cell")
      (doseq [c (js/Object.values cells)]
        (assert/ok (pos? (:row c)) "nothing on the row a spans"))
      (assert/deepEqual (:grid (first (:boxes (with-cells g)))) {:col 0 :row 0 :w 2 :h 1}))))

(test "label-at turns a label on a vertical segment when asked"
  (fn []
    (let [down [{:x 0 :y 0} {:x 0 :y 100}]]
      (assert/deepEqual (label-at down 40 14 [] true) {:x 4 :y 30 :w 14 :h 40 :rotated true})
      ;; not asked, or horizontal: upright
      (assert/deepEqual (label-at down 40 14) {:x 4 :y 43})
      (assert/equal (:rotated (label-at [{:x 0 :y 0} {:x 100 :y 0}] 40 14 [] true)) false))))

(test "layout-compact wraps a graph without boxes into rows"
  (fn []
    (let [ids (mapv (fn [i] (str "n" i)) (range 24))
          g (graph {:nodes (into {} (map (fn [n] [n (node n)])) ids)
                    :edges (mapv (fn [i] (edge i (nth ids i) (nth ids (inc i)))) (range 23))})
          elk (to-elk g measure)]
      (-> (js/Promise.all [(run-elk (js/JSON.parse (js/JSON.stringify elk))) (layout-compact g elk run-elk nil)])
          (.then (fn [[plain compact]]
                   (assert/ok (< (:width compact) (/ (:width plain) 3)) (str (:width compact) " vs " (:width plain)))
                   (assert/ok (> (:height compact) (* 3 (:height plain))))))))))

;; two boxes, each a chain of 8 labelled nodes: far wider than tall left to right
(def chains
  (let [ids (fn [b] (mapv (fn [i] (str b i)) (range 8)))
        nodes (into {} (map (fn [n] [n (node n)])) (into (ids "p") (ids "q")))
        chain (fn [b off] (mapv (fn [i] (edge (+ off i) (nth (ids b) i) (nth (ids b) (inc i)) "next")) (range 7)))]
    (graph {:nodes nodes
            :boxes [(box "p" (mapv (fn [n] (str "n:" n)) (ids "p")))
                    (box "q" (mapv (fn [n] (str "n:" n)) (ids "q")))]
            :parent-of (into {} (map (fn [n] [(str "n:" n) (.slice n 0 1)])) (into (ids "p") (ids "q")))
            :edges (conj (into (chain "p" 0) (chain "q" 10)) (edge 20 "p7" "q0" "hands over"))})))

(test "layout-compact turns wide boxes top to bottom, their labels along the edges"
  (fn []
    (-> (layout-compact chains (to-elk chains measure) run-elk nil)
        (.then (fn [l]
                 (let [p (first (filterv (fn [c] (= (:id c) "b:p")) (:children l)))]
                   ;; a chain of eight laid out downwards: taller than wide
                   (assert/ok (> (:height p) (:width p)) (str (:width p) "x" (:height p)))
                   (assert/equal (:dir (get (:runs l) "b:p")) "DOWN"))
                 (let [sc (build-scene {:layout l :graph chains :colors {:node {} :box {}}})
                       labels (filterv (fn [it] (= (:kind it) "edge-label")) (:items sc))
                       inner (filterv (fn [lb] (= (:text lb) "next")) labels)]
                   (assert/equal (count inner) 14)
                   (assert/ok (every? :rotated inner) "labels inside run along the vertical edges")
                   (assert/ok (every? (fn [lb] (> (:h lb) (:w lb))) inner))
                   ;; every edge drawn
                   (assert/equal (count (filterv (fn [it] (= (:kind it) "edge")) (:items sc))) 15))
                 ;; an edit keeps a box's direction
                 (-> (layout-compact chains (to-elk chains measure) run-elk l)
                     (.then (fn [l2] (assert/equal (:dir (get (:runs l2) "b:p")) "DOWN")))))))))

(test "best-columns: tall boxes get more columns, so the whole comes out screen-shaped"
  (fn []
    (let [names (mapv (fn [i] (str "b" i)) (range 9))
          g (graph {:nodes (into {} (map (fn [n] [(str n "x") (node (str n "x"))])) names)
                    :boxes (mapv (fn [n] (box n [(str "n:" n "x")])) names)
                    :parent-of (into {} (map (fn [n] [(str "n:" n "x") n])) names)})
          tall (into {} (map (fn [n] [n {:w 60 :h 300}])) names)
          wide (into {} (map (fn [n] [n {:w 300 :h 60}])) names)]
      ;; 8 columns: 1200 x 840, the closest to 1.6 : 1
      (assert/equal (best-columns g tall) 8)
      (assert/ok (< (best-columns g wide) 3)))))
