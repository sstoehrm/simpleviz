(ns simpleviz.tween-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.tween :as tween]))

(defn- node [id x y] {:kind "node" :id id :x x :y y :w 40 :h 20
                      :bbox {:x0 x :y0 y :x1 (+ x 40) :y1 (+ y 20)}})

(defn- edge [id s t pts] {:kind "edge" :id id :source-id s :target-id t
                          :sections [pts] :points pts
                          :bbox {:x0 0 :y0 0 :x1 100 :y1 100}})

(defn- scene [items] {:items items :width 200 :height 100})

(defn- item [sc id] (some (fn [it] (when (= (:id it) id) it)) (:items sc)))

(test "a node in both scenes moves part of the way"
  (fn []
    (let [f (tween/frame (scene [(node "n:a" 0 0)]) (scene [(node "n:a" 100 50)]) 0.5)
          a (item f "n:a")]
      (assert/equal (:x a) 50)
      (assert/equal (:y a) 25)
      (assert/equal (or (:alpha a) 1) 1))))

(test "what only the old scene has fades out, what only the new has fades in"
  (fn []
    (let [f (tween/frame (scene [(node "n:gone" 0 0)]) (scene [(node "n:new" 10 10)]) 0.25)]
      (assert/equal (:alpha (item f "n:gone")) 0.75)
      (assert/equal (:alpha (item f "n:new")) 0.25)
      (assert/equal (:x (item f "n:new")) 10 "a new item stays where it lands"))))

(test "the ends of the tween are the scenes"
  (fn []
    (let [from (scene [(node "n:a" 0 0)])
          to (scene [(node "n:a" 100 50)])]
      (assert/equal (:x (item (tween/frame from to 0) "n:a")) 0)
      (assert/equal (:x (item (tween/frame from to 1) "n:a")) 100))))

(test "edges match by their ends, not their ids, and keep their ends while bending"
  (fn []
    (let [from (scene [(edge "e0" "n:a" "n:b" [{:x 0 :y 0} {:x 100 :y 0}])])
          to (scene [(edge "e3" "n:a" "n:b" [{:x 0 :y 100} {:x 50 :y 100} {:x 50 :y 0} {:x 100 :y 0}])])
          f (tween/frame from to 0.5)
          edges (filterv (fn [it] (= (:kind it) "edge")) (:items f))
          pts (first (:sections (first edges)))]
      (assert/equal (count edges) 1)
      (assert/equal (or (:alpha (first edges)) 1) 1)
      (assert/deepEqual (first pts) {:x 0 :y 50})
      (assert/deepEqual (last pts) {:x 100 :y 0}))))

(test "an edge's label follows its edge"
  (fn []
    (let [lbl (fn [eid x] {:kind "edge-label" :id (str eid "-label") :edge-id eid :x x :y 0 :w 10 :h 10
                           :bbox {:x0 x :y0 0 :x1 (+ x 10) :y1 10}})
          from (scene [(edge "e0" "n:a" "n:b" [{:x 0 :y 0} {:x 100 :y 0}]) (lbl "e0" 0)])
          to (scene [(edge "e5" "n:a" "n:b" [{:x 0 :y 0} {:x 100 :y 0}]) (lbl "e5" 100)])
          f (tween/frame from to 0.5)
          l (some (fn [it] (when (= (:kind it) "edge-label") it)) (:items f))]
      (assert/equal (:x l) 50)
      (assert/equal (or (:alpha l) 1) 1))))

(test "boxes draw before edges, edges before nodes"
  (fn []
    (let [box {:kind "box" :id "b:x" :x 0 :y 0 :w 100 :h 100 :bbox {:x0 0 :y0 0 :x1 100 :y1 100}}
          f (tween/frame (scene [(node "n:a" 0 0)])
                         (scene [box (edge "e0" "n:a" "n:b" [{:x 0 :y 0} {:x 1 :y 1}]) (node "n:a" 0 0)])
                         0.5)]
      (assert/deepEqual (mapv :kind (:items f)) ["box" "edge" "node"]))))

(test "ease starts and ends slow and keeps its ends"
  (fn []
    (assert/equal (tween/ease 0) 0)
    (assert/equal (tween/ease 1) 1)
    (assert/equal (tween/ease 0.5) 0.5)
    (assert/ok (< (tween/ease 0.1) 0.1))))

(test "a highlight coming on fades in, one going off fades out"
  (fn []
    (let [on (assoc (node "n:a" 0 0) :highlight true)
          off (assoc (node "n:a" 0 0) :highlight false)]
      (assert/equal (:highlight (item (tween/frame (scene [off]) (scene [on]) 0.25) "n:a")) 0.25)
      (assert/equal (:highlight (item (tween/frame (scene [on]) (scene [off]) 0.25) "n:a")) 0.75))))
