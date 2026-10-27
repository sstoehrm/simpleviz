(ns lang-clj-test
  (:require [clojure.test :refer [deftest is testing]]
            [lang-clj]))

(def ^:private files
  [{:path "app/core.clj"
    :text "(ns app.core
  (:require [app.util :as u :refer [helper]]
            [clojure.string :as str]))

(declare later)

(defn run
  \"Entry.\"
  [x]
  (str/trim (prep (u/shout x))))

(defn- prep [x] (helper (later x)))

(defn later [x] (u/quiet x))

(defmacro m [& body] `(do ~@body))

(def config {:a 1})

(comment (run 1))"}
   {:path "app/util.cljc"
    :text "(ns app.util)

(defn shout [s] #?(:clj (quiet s) :cljs (js/alert s)))

(defn ^:private quiet [s] (helper s))

(defn helper [s] (quiet s))

(defmulti kind :type)
(defmethod kind :a [x] (shout x))"}
   {:path "broken.clj" :text "(ns broken (:require [x]"}
   {:path "README.md" :text "# not clojure"}])

(defn- module [mods ns-name] (first (filter #(= ns-name (:ns %)) mods)))

(defn- def-of [m n] (first (filter #(= n (:name %)) (:defs m))))

(deftest clj-module-reads-namespaces-and-defs
  (let [mods (lang-clj/clj-module files)
        core (module mods "app.core")
        util (module mods "app.util")]
    (testing "one module per clojure file, other files left out"
      (is (= #{"app/core.clj" "app/util.cljc" "broken.clj"} (set (map :file mods)))))
    (testing "a file that doesn't parse says so"
      (is (string? (:error (first (filter #(= "broken.clj" (:file %)) mods))))))
    (is (= ["run" "prep" "later" "m" "config"] (mapv :name (:defs core))))
    (is (= {:kind "defn" :private false :line 7}
           (select-keys (def-of core "run") [:kind :private :line])))
    (is (true? (:private (def-of core "prep"))))
    (is (true? (:private (def-of util "quiet"))) "^:private on the name")
    (is (= "defmacro" (:kind (def-of core "m"))))
    (testing "calls resolve through aliases, refers and the namespace's own defs"
      (is (= ["app.core/prep" "app.util/shout" "clojure.string/trim"] (:calls (def-of core "run"))))
      (is (= ["app.core/later" "app.util/helper"] (:calls (def-of core "prep"))))
      (is (= ["app.util/quiet"] (:calls (def-of util "shout"))) "#? reads the :clj branch"))
    (testing "a defmethod's calls belong to its multi"
      (is (= ["app.util/shout"] (:calls (def-of util "kind"))))
      (is (= 1 (count (filter #(= "kind" (:name %)) (:defs util))))))))

(deftest public-namespace-graph-boxes-namespaces
  (let [g (lang-clj/public-namespace-graph (lang-clj/clj-module files))]
    (is (= #{"app.core" "app.util"} (set (keys (:boxes g)))))
    (is (= #{"app.core/run" "app.core/later" "app.core/m"} (get-in g [:boxes "app.core" :components]))
        "public fns and macros only: no private fn, no plain def")
    (is (= #{"app.util/shout" "app.util/helper" "app.util/kind"} (get-in g [:boxes "app.util" :components])))
    (is (= {:name "m" :type "macro"} (select-keys (get-in g [:nodes "app.core/m"]) [:name :type])))
    (testing "calls between public fns, through private ones"
      (is (= {["app.core/run" "app.util/shout"] {:direction :->}
              ;; run → prep (private) → later, helper
              ["app.core/later" "app.core/run"] {:direction :<-}
              ["app.core/run" "app.util/helper"] {:direction :->}
              ;; later → quiet, private in its own namespace too → helper
              ["app.core/later" "app.util/helper"] {:direction :->}
              ;; shout → quiet (private) → helper; helper → quiet → helper is itself
              ["app.util/helper" "app.util/shout"] {:direction :<-}
              ["app.util/kind" "app.util/shout"] {:direction :->}}
             (:edges g))))))

(deftest mutual-calls-are-one-edge
  (let [g (lang-clj/public-namespace-graph
           (lang-clj/clj-module [{:path "a.clj" :text "(ns a) (defn f [] (g)) (defn g [] (f))"}]))]
    (is (= {["a/f" "a/g"] {:direction :<->}} (:edges g)))))
