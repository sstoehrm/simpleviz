;; Deterministic generator for large, realistic-looking example graphs.
;;
;;   bb dev/gen-example.clj [n-nodes] [out.edn]      (defaults: 10000, examples/big-10k.edn)
;;
;; Topology: domains (boxes) contain system boxes; systems contain
;; services/workers plus their databases, caches and queues; each domain
;; has a gateway; cross-domain traffic goes gateway-to-gateway; a few
;; shared platform services (auth, metrics, logging) collect sparse
;; fan-in. Seeded LCG makes output reproducible.

(def target (or (some-> (first *command-line-args*) parse-long) 10000))
(def out (or (second *command-line-args*) "examples/big-10k.edn"))

(def domain-names
  ["orders" "billing" "payments" "inventory" "shipping" "catalog" "customers"
   "identity" "search" "pricing" "fraud" "loyalty" "notifications" "analytics"
   "content" "fulfillment" "returns" "support" "partners" "campaigns"])
(def roles ["api" "worker" "sync" "batch" "stream" "admin" "ingest" "export"
            "scheduler" "webhook" "matcher" "resolver"])
(def langs ["clojure" "go" "rust" "kotlin" "typescript"])
(def teams ["platform" "core" "growth" "data" "infra" "web"])
(def db-engines ["postgres" "mysql" "dynamodb" "clickhouse"])

(def seed (atom 42))
(defn rnd!
  "Deterministic 0..n-1."
  [n]
  (swap! seed (fn [s] (mod (+ (* s 1103515245) 12345) 2147483648)))
  (mod @seed n))
(defn pick! [v] (nth v (rnd! (count v))))
(defn chance! [pct] (< (rnd! 100) pct))

(def nodes (atom {}))
(def boxes (atom {}))
(def edges (atom {}))

(defn node! [id m] (swap! nodes assoc id m) id)
(defn edge!
  "Adds [a b] unless a self-loop or either orientation already exists."
  [a b m]
  (when (and (not= a b)
             (not (contains? @edges [a b]))
             (not (contains? @edges [b a])))
    (swap! edges assoc [a b] m)))

(def auth (node! :auth-service {:name "Auth" :type "service" :team "platform" :lang "go"}))
(def metrics (node! :metrics-gw {:name "Metrics" :type "external" :vendor "datadog"}))
(def logsink (node! :log-sink {:name "Log Sink" :type "external"}))

(def gateways (atom []))

(loop [d 0]
  (when (< (count @nodes) target)
    (let [dname (str (nth domain-names (mod d (count domain-names)))
                     (if (>= d (count domain-names)) (str "-" (quot d (count domain-names))) ""))
          gw (node! (keyword (str dname "-gateway"))
                    {:name (str dname " gateway") :type "gateway" :team (pick! teams)})
          _ (swap! gateways conj gw)
          n-systems (+ 4 (rnd! 5))
          sys-boxes
          (vec
           (for [s (range n-systems)]
             (let [sys-id (keyword (str dname "-sys" s))
                   n-svc (+ 6 (rnd! 9))
                   svcs (vec (for [i (range n-svc)]
                               (let [role (pick! roles)]
                                 (node! (keyword (str dname "-" role "-" s "-" i))
                                        {:name (str dname "-" role "-" i)
                                         :type (if (#{"worker" "batch" "scheduler"} role) "worker" "service")
                                         :team (pick! teams)
                                         :lang (pick! langs)
                                         :replicas (inc (rnd! 5))}))))
                   dbs (vec (for [i (range (inc (rnd! 2)))]
                              (node! (keyword (str dname "-db-" s "-" i))
                                     {:name (str dname "-db-" i) :type "database"
                                      :engine (pick! db-engines)})))
                   cache (when (chance! 40)
                           (node! (keyword (str dname "-cache-" s))
                                  {:name (str dname "-cache") :type "cache"}))
                   queue (when (chance! 35)
                           (node! (keyword (str dname "-queue-" s))
                                  {:name (str dname "-queue") :type "queue"}))]
               ;; wiring inside the system
               (doseq [svc svcs]
                 (edge! svc (pick! dbs) {:direction :-> :type "sql"})
                 (when (and cache (chance! 35))
                   (edge! svc cache {:direction :<-> :type "resp"}))
                 (when queue
                   (if (= "worker" (:type (get @nodes svc)))
                     (edge! queue svc {:direction :-> :name "consume" :type "amqp"})
                     (when (chance! 30)
                       (edge! svc queue {:direction :-> :name "publish" :type "amqp"}))))
                 (when (chance! 60)
                   (edge! svc (pick! svcs) {:direction :-> :type (pick! ["http" "grpc"])}))
                 (when (chance! 3) (edge! svc auth {:direction :-> :name "authz" :type "grpc"}))
                 (when (chance! 2) (edge! svc metrics {:direction :-> :name "emit"}))
                 (when (chance! 2) (edge! svc logsink {:direction :-> :name "ship"})))
               ;; gateway fronts one service of each system
               (edge! gw (pick! svcs) {:direction :-> :name "route" :type "http"})
               (swap! boxes assoc sys-id
                      {:type "system"
                       :components (set (concat svcs dbs (filter some? [cache queue])))})
               sys-id)))]
      (swap! boxes assoc (keyword dname)
             {:type "domain" :team (pick! teams)
              :components (set (conj sys-boxes gw))})
      (recur (inc d)))))

;; cross-domain traffic: gateway to gateway
(doseq [gw @gateways]
  (dotimes [_ (inc (rnd! 2))]
    (edge! gw (pick! @gateways) {:direction :-> :name "federate" :type "http"})))

(spit out (pr-str {:nodes @nodes :edges @edges :boxes @boxes}))
(println "wrote" out ":" (count @nodes) "nodes," (count @edges) "edges,"
         (count @boxes) "boxes," (int (/ (.length (java.io.File. out)) 1024)) "KiB")
