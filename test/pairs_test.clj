(ns pairs-test
  (:require [clojure.test :refer [deftest is]]
            [graph]
            [pairs]))

(defn- g [raw] (graph/normalize raw))

(defn- reader
  "A :read for attach over a map of root-relative path -> raw EDN data."
  [files]
  (fn [rel]
    (if-let [raw (get files rel)]
      (g raw)
      (throw (ex-info (str rel " not found") {})))))

(defn- ctx [path files & {:keys [index fork-of]}]
  {:path path :read (reader files) :index (or index {})
   :fork-of (or fork-of (constantly nil))})

(deftest parse-value-accepts-a-string-or-a-vector-of-strings
  (is (= {:pairs [{:raw "d.edn#a" :path "d.edn" :id "a"}] :problems []}
         (pairs/parse-value "d.edn#a")))
  (is (= ["d.edn" "r.edn"] (map :path (:pairs (pairs/parse-value ["d.edn#a" "r.edn#b"])))))
  (is (= {:pairs [] :problems []} (pairs/parse-value nil)))
  ;; the last # splits, so a # in a folder name survives
  (is (= {:raw "x#y/d.edn#a" :path "x#y/d.edn" :id "a"}
         (first (:pairs (pairs/parse-value "x#y/d.edn#a"))))))

(deftest parse-value-reports-malformed-values
  (doseq [v ['("d.edn#a") #{"d.edn#a"} :d ["d.edn#a" 3] 7]]
    (is (= [":pair must be \"file#id\" or a vector of them"] (:problems (pairs/parse-value v)))
        (pr-str v)))
  (is (= ["pair \"d.edn\" needs a file and an id, as in \"deploy.edn#api\""]
         (:problems (pairs/parse-value "d.edn"))))
  (is (= ["pair \"d.edn#\" needs a file and an id, as in \"deploy.edn#api\""]
         (:problems (pairs/parse-value "d.edn#"))))
  (is (= ["pair \"#x\" needs a file; pairs within one file aren't supported"]
         (:problems (pairs/parse-value "#x")))))

(deftest attach-resolves-outgoing-pairs-against-the-declaring-file
  (let [out (pairs/attach (g {:nodes {:api {:pair "deploy.edn#api-svc"}}})
                          (ctx "views/overview.edn"
                               {"views/deploy.edn" {:nodes {:api-svc {}}}}))]
    (is (= [{:dir "out" :file "views/deploy.edn" :id "api-svc" :kind "node" :raw "deploy.edn#api-svc"}]
           (get-in out [:nodes "api" :pairs])))
    (is (= [] (:warnings out)))))

(deftest attach-reports-every-broken-outgoing-pair
  (let [files {"views/deploy.edn" {:nodes {:other {}}}}
        out (pairs/attach
             (g {:nodes {:api {:pair ["deploy.edn#api-svc" "gone.edn#x" "../../x.edn#a"
                                      "deploy-next.edn#a"]}}
                 :boxes {:grp {:components #{} :pair 5}}
                 :edges {[:api :api2] {:pair "d.edn#a"}}})
             (ctx "views/overview.edn" files
                  :fork-of (fn [rel] (when (= rel "views/deploy-next.edn") "views/deploy.edn"))))]
    (is (= ["node \"api\": pair \"deploy.edn#api-svc\": no node or box api-svc in views/deploy.edn"
            "node \"api\": pair \"gone.edn#x\": views/gone.edn not found"
            "node \"api\": pair \"../../x.edn#a\": ../../x.edn leaves the served folder"
            "node \"api\": pair \"deploy-next.edn#a\": views/deploy-next.edn is the fork of views/deploy.edn — pair with the original"
            "box \"grp\": :pair must be \"file#id\" or a vector of them"]
           (filterv #(re-find #"pair" %) (:warnings out))))
    (is (= 4 (count (get-in out [:nodes "api" :pairs]))))
    (is (every? :problem (get-in out [:nodes "api" :pairs])))
    ;; the edge endpoint api2 is unknown, but the :pair warning is about the edge's own attr
    (is (not-any? #(re-find #"^edge \[api api2\]: :pair" %) (:warnings out))
        "an edge normalize skipped has no :pair warning")))

(deftest attach-warns-about-a-pair-on-an-edge
  (let [out (pairs/attach (g {:nodes {:a {} :b {}} :edges {[:a :b] {:pair "d.edn#x"}}})
                          (ctx "g.edn" {}))]
    (is (some #{"edge [a b]: :pair applies to nodes and boxes"} (:warnings out)))))

(deftest attach-prefers-the-node-when-a-box-shares-its-id
  (let [target {:nodes {:api {}} :boxes {:api {:components #{}}}}
        out (pairs/attach (g {:nodes {:x {:pair "t.edn#api"}}}) (ctx "g.edn" {"t.edn" target}))]
    (is (= "node" (get-in out [:nodes "x" :pairs 0 :kind]))))
  (let [idx (pairs/index [["other.edn" (g {:nodes {:y {:pair "t.edn#api"}}})]])
        out (pairs/attach (g {:nodes {:api {}} :boxes {:api {:components #{}}}})
                          (ctx "t.edn" {} :index idx))]
    (is (= [{:dir "in" :file "other.edn" :id "y" :kind "node"}] (get-in out [:nodes "api" :pairs])))
    (is (nil? (:pairs (first (:boxes out)))) "the box doesn't get the node's incoming pair")))

(deftest index-maps-each-target-to-the-elements-pairing-into-it
  (let [idx (pairs/index [["views/a.edn" (g {:nodes {:n {:pair "b.edn#t"}}})]
                          ["views/c.edn" (g {:boxes {:grp {:components #{} :pair ["b.edn#t" "../r.edn#u"]}}})]
                          ["top.edn" (g {:nodes {:m {:pair "../escape.edn#z"}}})]])]
    (is (= {"views/b.edn#t" [{:file "views/a.edn" :id "n" :kind "node"}
                             {:file "views/c.edn" :id "grp" :kind "box"}]
            "r.edn#u" [{:file "views/c.edn" :id "grp" :kind "box"}]}
           idx))))

(deftest attach-adds-incoming-pairs-from-the-index
  (let [idx (pairs/index [["views/overview.edn" (g {:nodes {:api {:pair "deploy.edn#api-svc"}}})]])
        out (pairs/attach (g {:nodes {:api-svc {}}}) (ctx "views/deploy.edn" {} :index idx))]
    (is (= [{:dir "in" :file "views/overview.edn" :id "api" :kind "node"}]
           (get-in out [:nodes "api-svc" :pairs])))
    (is (= [] (:warnings out)))))

(deftest pair-files-lists-the-paths-of-well-formed-pairs
  (is (= ["d.edn" "sub/r.edn"]
         (pairs/pair-files (g {:nodes {:a {:pair ["d.edn#x" "sub/r.edn#y" "bad"]}}
                               :boxes {:b {:components #{} :pair "d.edn#z"}}})))))
