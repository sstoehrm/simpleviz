(ns simpleviz.layout-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            ["node:module" :refer [createRequire]]
            [simpleviz.validate :refer [validate]]
            [simpleviz.transform :refer [to-elk]]))

(def require' (createRequire (js* "import.meta.url")))
(def ELK (require' "../../vendor/elk.bundled.js"))

(defn measure [text _font] (* (.-length text) 7))

(test "ELK lays out a nested boxed graph end to end"
  (fn []
    (let [g (validate {:nodes {"a" {:type "svc"} "b" {:type "db"} "c" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}
                               {:nodes ["c" "a"] :direction "<-"}
                               {:nodes ["b" "c"] :direction "<->"}
                               {:nodes ["a" "c"] :direction "-"}]
                       :boxes [{:name "grp" :components ["a" "b"]}]})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   (assert/ok (and (pos? (:width layout)) (pos? (:height layout))))
                   (let [grp (first (filterv (fn [c] (= (:id c) "b:grp")) (:children layout)))]
                     (assert/ok grp "box present in layout")
                     (assert/equal (.-length (:children grp)) 2)
                     (doseq [child (:children grp)]
                       (assert/ok (and (some? (:x child)) (some? (:y child))))))
                   (assert/equal (.-length (:edges layout)) 4)
                   (doseq [e (:edges layout)]
                     (assert/ok (and (:sections e) (pos? (.-length (:sections e))))
                                (str "edge " (:id e) " has sections")))))))))

(test "edges wholly inside a box get container-relative section coordinates"
  (fn []
    (let [g (validate {:nodes {"a" {} "b" {}}
                       :edges [{:nodes ["a" "b"] :direction "->"}]
                       :boxes [{:name "grp" :components ["a" "b"]}]})]
      (-> (.layout (ELK.) (to-elk g measure))
          (.then (fn [layout]
                   ;; the renderer must offset by the container's absolute
                   ;; origin — this documents the contract it relies on
                   (assert/equal (:container (nth (:edges layout) 0)) "b:grp")))))))
