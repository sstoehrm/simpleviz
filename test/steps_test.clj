(ns steps-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [steps]))

(def base {:nodes {:api {:name "API"} :db {:type "database"}}
           :edges {[:api :db] {:direction :->}}})

(def two-steps
  (assoc base :steps [{:name "cache"
                       :edits [[[:nodes :cache] :+ {:type "cache"}]
                               [[:edges [:api :cache]] :+ {:direction :->}]]}
                      {:name "drop db"
                       :edits [[[:nodes :db] :-] [[:edges [:api :db]] :-]]}]))

(deftest no-steps-is-one-state
  (let [{:keys [states warnings]} (steps/states base)]
    (is (= [base] states))
    (is (= [] warnings))))

(deftest each-step-patches-the-state-before-it
  (let [{:keys [states names warnings]} (steps/states two-steps)]
    (is (= [] warnings))
    (is (= ["cache" "drop db"] names))
    (is (= base (first states)) "state 0 is the file without :steps")
    (is (= #{:api :db :cache} (set (keys (:nodes (nth states 1))))))
    (is (= #{:api :cache} (set (keys (:nodes (nth states 2))))))
    (is (= {[:api :cache] {:direction :->}} (:edges (nth states 2))))))

(deftest a-step-that-does-not-apply-warns-and-changes-nothing
  (let [g (assoc base :steps [{:name "bad" :edits [[[:nodes :gone :name] :r "x"]]}
                              {:name "ok" :edits [[[:nodes :api :name] :r "API 2"]]}])
        {:keys [states warnings]} (steps/states g)]
    (is (= 3 (count states)) "a broken step keeps its place")
    (is (= base (nth states 1)))
    (is (= "API 2" (get-in (nth states 2) [:nodes :api :name])))
    (is (= 1 (count warnings)))
    (is (str/includes? (first warnings) "step 1 \"bad\""))))

(deftest malformed-steps-warn
  (is (seq (:warnings (steps/states (assoc base :steps {:a 1})))))
  (is (= 1 (count (:states (steps/states (assoc base :steps {:a 1}))))))
  (let [{:keys [states warnings names]} (steps/states (assoc base :steps [42 {:edits "x"} {}]))]
    (is (= 4 (count states)))
    (is (= 2 (count warnings)) "a non-map step and bad :edits warn; an empty step is fine")
    (is (= "step 3" (nth names 2)) "a step without :name is named by its number")))

;; ---- editing the file text ----

(def file-text
  (str "{:nodes {:api {:name \"API\"} ;; the api\n"
       "         :db {:type \"database\"}}\n"
       " :edges {[:api :db] {:direction :->}}\n"
       " :steps [{:name \"cache\" ;; first\n"
       "          :edits [[[:nodes :cache] :+ {:type \"cache\"}]]}\n"
       "         {:name \"drop db\"\n"
       "          :edits [[[:nodes :db] :-] [[:edges [:api :db]] :-]]}]}\n"))

(defn- run [text ops step]
  (let [{:keys [text error]} (steps/apply-ops text ops step)]
    (is (nil? error) error)
    text))

(deftest add-step-inserts-an-empty-step-after-the-one-given
  (let [out (run file-text [{:op "add-step" :after 1 :name "middle"}] nil)
        g (edn/read-string out)]
    (is (= ["cache" "middle" "drop db"] (mapv :name (:steps g))))
    (is (= [] (:edits (nth (:steps g) 1))))
    (is (str/includes? out ";; the api") "comments elsewhere survive")
    (is (str/includes? out ";; first"))))

(deftest add-step-creates-the-steps-key
  (let [out (run "{:nodes {:a nil}}" [{:op "add-step" :after 0 :name "one"}] nil)]
    (is (= [{:name "one" :edits []}] (:steps (edn/read-string out))))))

(deftest delete-and-rename-step
  (let [g (edn/read-string (run file-text [{:op "delete-step" :step 1}] nil))]
    (is (= ["drop db"] (mapv :name (:steps g)))))
  (let [g (edn/read-string (run file-text [{:op "rename-step" :step 2 :name "no db"}] nil))]
    (is (= ["cache" "no db"] (mapv :name (:steps g)))))
  (testing "deleting the last step drops the key"
    (let [out (run file-text [{:op "delete-step" :step 1} {:op "delete-step" :step 1}] nil)]
      (is (not (contains? (edn/read-string out) :steps)))
      (is (str/includes? out ";; the api")))))

(deftest set-step-edits-replaces-the-edits
  (let [out (run file-text [{:op "set-step-edits" :step 1 :edits "[[[:nodes :api :name] :r \"API v2\"]]"}] nil)
        g (edn/read-string out)]
    (is (= [[[:nodes :api :name] :r "API v2"]] (:edits (first (:steps g)))))
    (is (str/includes? out ";; first") "the step's other keys and comments stay")))

(deftest set-step-edits-refuses-what-does-not-apply
  (is (some? (:error (steps/apply-ops file-text [{:op "set-step-edits" :step 1 :edits "[[[:nodes :gone :x] :r 1]]"}] nil))))
  (is (some? (:error (steps/apply-ops file-text [{:op "set-step-edits" :step 1 :edits "[[:bad]]"}] nil))))
  (is (some? (:error (steps/apply-ops file-text [{:op "set-step-edits" :step 1 :edits "[[["}] nil)))))

(deftest graph-ops-at-a-step-rewrite-only-that-steps-edits
  (let [out (run file-text [{:op "set-attr" :section "nodes" :id "cache" :attr "name"
                             :value "\"Redis\"" :fallback true}]
                 1)
        g (edn/read-string out)
        {:keys [states warnings]} (steps/states g)]
    (is (= [] warnings))
    (is (= "API" (get-in g [:nodes :api :name])) "the base stays")
    (is (= {:type "cache" :name "Redis"} (get-in (nth states 1) [:nodes :cache])))
    (is (= (:edits (second (:steps (edn/read-string file-text))))
           (:edits (second (:steps g))))
        "later steps keep their edits")
    (is (str/includes? out ";; the api"))))

(deftest graph-ops-at-a-step-can-add-and-delete
  (let [g (edn/read-string (run file-text [{:op "add-node" :id "queue"}
                                           {:op "delete" :section "nodes" :id "api"}]
                                2))
        {:keys [states]} (steps/states g)]
    (is (= #{:cache :queue} (set (keys (:nodes (nth states 2))))))
    (is (= #{:api :db :cache} (set (keys (:nodes (nth states 1))))))))

(deftest step-zero-edits-the-file-as-before
  (let [out (run file-text [{:op "set-attr" :section "nodes" :id "api" :attr "name"
                             :value "\"Gateway\"" :fallback true}]
                 0)]
    (is (str/includes? out "{:name \"Gateway\"} ;; the api"))))

(deftest an-unknown-step-is-refused
  (is (some? (:error (steps/apply-ops file-text [{:op "add-node" :id "x"}] 5))))
  (is (some? (:error (steps/apply-ops file-text [{:op "delete-step" :step 3}] nil)))))

(deftest state-text-of-a-step
  (is (= #{:api :db :cache} (set (keys (:nodes (edn/read-string (steps/state-text file-text 1))))))))

(deftest edits-text-has-no-commas
  (is (= "[[[:boxes] :+ {:b {:type \"zone\" :components #{:a}}}]\n [[:nodes :a] :-]]"
         (steps/edits-text [[[:boxes] :+ {:b {:type "zone" :components #{:a}}}] [[:nodes :a] :-]] 0)))
  (is (= "{:a 1 :b [1 2]}" (steps/state-text "{:a 1 :b [1 2]}" 0))))

(deftest edits-are-spliced-in-place-also-after-crlf-and-unicode
  (let [text (str "{:nodes {:a {:name \"Ä\"}}\r\n"
                  " :steps [{:name \"one\" ;; keep\r\n"
                  "          :edits []}]}")
        out (run text [{:op "add-node" :id "b"}] 1)]
    (is (= (str "{:nodes {:a {:name \"Ä\"}}\r\n"
                " :steps [{:name \"one\" ;; keep\r\n"
                "          :edits [[[:nodes :b] :+ nil]]}]}")
           out))))

(deftest a-step-without-edits-gets-them
  (let [out (run "{:nodes {:a nil}\n :steps [{:name \"one\"}]}" [{:op "add-node" :id "b"}] 1)]
    (is (= [[[:nodes :b] :+ nil]] (:edits (first (:steps (edn/read-string out))))))))
