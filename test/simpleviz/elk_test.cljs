(ns simpleviz.elk-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.elk :refer [layouter]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(def graph {:id "root" :children [{:id "a" :width 10 :height 10}] :edges []})

(defn fake-worker
  "A stand-in for a Web Worker: it answers each layout with
  {:from \"worker\"} unless `answer?` says no; `posted` collects the
  commands it got."
  [posted answer?]
  (let [w (js/EventTarget.)]
    (set! (.-postMessage w)
          (fn [m]
            (.push posted (:cmd m))
            (when (answer?)
              (js/setTimeout (fn [] ((.-onmessage w) {:data {:id (:id m) :data {:from "worker"}}})) 0))))
    (set! (.-terminate w) (fn [] (.push posted "terminate")))
    w))

(test "a layout runs in the worker"
  (fn []
    (let [posted #js []
          urls #js []
          lay (layouter ELK "/vendor/elk-worker.min.js"
                        (fn [url] (.push urls url) (fake-worker posted (fn [] true))))]
      (-> (lay graph)
          (.then (fn [r]
                   (assert/deepEqual urls ["/vendor/elk-worker.min.js"])
                   (assert/equal (:from r) "worker")
                   (assert/ok (.includes posted "layout"))))))))

(test "no worker starting, ELK lays out in this thread"
  (fn []
    (let [lay (layouter ELK "/x.js" (fn [_] (throw (js/Error. "no workers here"))))]
      (-> (lay graph)
          (.then (fn [r]
                   (assert/ok (pos? (:width r)))
                   (assert/ok (some? (:x (first (:children r)))))))))))

(test "a worker that fails takes no more layouts, and the one in flight runs here"
  (fn []
    (let [posted #js []
          w (fake-worker posted (fn [] false))
          lay (layouter ELK "/x.js" (fn [_] w))
          first-run (lay graph)]
      ;; the script could not load: no answer comes, an error event does
      (js/setTimeout (fn [] (.dispatchEvent w (js/Event. "error"))) 10)
      (-> first-run
          (.then (fn [r]
                   (assert/ok (pos? (:width r)) "laid out in this thread")
                   (assert/ok (.includes posted "terminate"))
                   (let [n (.-length posted)]
                     (-> (lay graph)
                         (.then (fn [r2]
                                  (assert/ok (pos? (:width r2)))
                                  (assert/equal (.-length posted) n "the worker got nothing more")))))))))))
