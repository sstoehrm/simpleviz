(ns vuln-gate
  "The vulnerability gate: reads Trivy JSON reports and fails only on
  findings that are being exploited or likely to be — in CISA's KEV
  catalog, or with EPSS >= 0.1 and CVSS >= 7.0 — unless a current entry in
  .vuln-ignore.yaml suppresses them. Everything else is reported only.

    bb tools/vuln_gate.clj [options] trivy.json...

  Exit 0: passed. Exit 1: a finding fails the gate. Exit 2: the gate could
  not decide (unreadable scan, KEV or EPSS feed, or a malformed allowlist)
  — it fails closed. Needs nothing beyond babashka's built-in libraries,
  so other repos run this one file as it is."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.string :as str])
  (:import [java.time LocalDate ZoneOffset]
           [java.time.format DateTimeParseException]))

(def kev-url "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json")
(def epss-url "https://api.first.org/data/v1/epss")

(defn- fail! [msg] (throw (ex-info msg {:gate/error true})))

;; findings

(defn cvss
  "The highest CVSS v3 or v4 base score any source gives `vuln`. Without
  one, HIGH and CRITICAL count as 7.0, the floor of their CVSS band, so a
  missing score cannot hide a severe finding."
  [vuln]
  (let [scores (mapcat (juxt :V3Score :V40Score) (vals (:CVSS vuln)))]
    (if-let [s (seq (keep identity scores))]
      (double (apply max s))
      (when (#{"HIGH" "CRITICAL"} (:Severity vuln)) 7.0))))

(defn cve-ids
  "The CVE IDs of `vuln`: its own ID and its aliases. KEV and EPSS know
  CVEs only."
  [vuln]
  (->> (cons (:VulnerabilityID vuln) (:VendorIDs vuln))
       (filter #(str/starts-with? (str %) "CVE-"))
       distinct
       vec))

(defn findings
  "One finding per vulnerability and package across the Trivy `reports`."
  [reports]
  (->> (for [report reports
             result (:Results report)
             v (:Vulnerabilities result)]
         {:id (:VulnerabilityID v)
          :cves (cve-ids v)
          :package (:PkgName v)
          :version (:InstalledVersion v)
          :fixed (:FixedVersion v)
          :severity (:Severity v)
          :cvss (cvss v)
          :title (:Title v)
          :url (:PrimaryURL v)
          :target (str (:ArtifactName report) ": " (:Target result))})
       (reduce (fn [acc f]
                 (let [k ((juxt :id :package :version) f)]
                   (if (contains? (:seen acc) k)
                     acc
                     (-> acc (update :seen conj k) (update :out conj f)))))
               {:seen #{} :out []})
       :out))

(defn warnings
  "What the reports cannot show: an end-of-life OS has no advisories, so
  its image scans clean."
  [reports]
  (vec (for [r reports
             :let [{:keys [Family Name EOSL]} (get-in r [:Metadata :OS])]
             :when EOSL]
         (str (:ArtifactName r) ": " Family " " Name " is end-of-life; its packages get no "
              "security fixes and the scan cannot see their vulnerabilities"))))

;; feeds

(defn kev-ids
  "The CVE IDs in a CISA KEV catalog."
  [catalog]
  (let [vulns (:vulnerabilities catalog)]
    (when-not (sequential? vulns)
      (fail! "the KEV catalog holds no vulnerabilities list"))
    (into #{} (map :cveID) vulns)))

(defn epss-scores
  "CVE ID -> EPSS probability, from a FIRST EPSS API answer."
  [answer]
  (let [data (:data answer)]
    (when-not (sequential? data)
      (fail! "the EPSS answer holds no data list"))
    (into {} (map (juxt :cve #(parse-double (str (:epss %))))) data)))

(defn epss-batches
  "The API answers at most 100 CVEs per request."
  [cves]
  (partition-all 100 cves))

(defn- get-json
  "GETs `url` as JSON, trying three times before giving up."
  [url query]
  (loop [attempt 1]
    (let [r (try (http/get url {:query-params query :throw false :timeout 60000})
                 (catch Exception e {:error e}))]
      (cond
        (= 200 (:status r)) (json/parse-string (:body r) true)
        (< attempt 3) (do (Thread/sleep (* attempt 5000)) (recur (inc attempt)))
        :else (fail! (str "cannot fetch " url ": "
                          (or (some-> (:error r) ex-message) (str "HTTP " (:status r)))))))))

(defn- read-json [path what]
  (when-not (fs/exists? path)
    (fail! (str "no " what " at " path)))
  (try (json/parse-string (slurp path) true)
       (catch Exception e (fail! (str "cannot read " what " " path ": " (ex-message e))))))

(defn- load-kev [source]
  (kev-ids (if (re-find #"^https?://" source)
             (get-json source {})
             (read-json source "KEV catalog"))))

(defn- load-epss [source cves]
  (if source
    (epss-scores (read-json source "EPSS answer"))
    (into {} (for [batch (epss-batches cves)]
               (epss-scores (get-json epss-url {"cve" (str/join "," batch)
                                                "limit" "100"}))))))

;; allowlist

(defn- ->date [v]
  (cond
    (instance? java.util.Date v) (LocalDate/ofInstant (.toInstant ^java.util.Date v) ZoneOffset/UTC)
    (string? v) (try (LocalDate/parse (str/trim v)) (catch DateTimeParseException _ nil))
    :else nil))

(defn parse-allowlist
  "The entries of a .vuln-ignore.yaml text and what is wrong with it:
  every entry needs an id, a reason and an `expires` date."
  [text]
  (let [doc (yaml/parse-string text)
        items (if (nil? doc) [] (:ignore doc))]
    (if-not (or (nil? doc) (and (map? doc) (or (nil? items) (sequential? items))))
      {:entries [] :errors ["the file must hold a top-level `ignore:` list"]}
      (let [checked
            (map-indexed
             (fn [i {:keys [id reason expires]}]
               (let [id (some-> id str str/trim not-empty)
                     reason (some-> reason str str/trim not-empty)
                     date (->date expires)
                     at (str "entry " (inc i) (when id (str " (" id ")")) ": ")]
                 {:entry {:id id :reason reason :expires date}
                  :error (cond
                           (nil? id) (str at "id is missing")
                           (nil? reason) (str at "reason is missing")
                           (nil? expires) (str at "expires is missing")
                           (nil? date) (str at "expires is not a YYYY-MM-DD date: " expires))}))
             items)]
        {:entries (vec (keep #(when-not (:error %) (:entry %)) checked))
         :errors (vec (keep :error checked))}))))

;; policy

(defn- gate-reasons [{:keys [cvss]} kev? epss {:keys [epss-min cvss-min]}]
  (cond-> []
    kev? (conj "CISA KEV")
    (and epss cvss (>= epss epss-min) (>= cvss cvss-min))
    (conj (format "EPSS %.3f >= %s and CVSS %s >= %s" epss epss-min cvss cvss-min))))

(defn evaluate
  "Decides each finding: :fail, :suppressed (a current allowlist entry
  covers it) or :report. An entry is current through its expiry day; an
  expired one suppresses nothing."
  [findings {:keys [kev epss allowlist ^LocalDate today] :as opts}]
  (let [current? #(not (.isAfter today (:expires %)))
        decided
        (for [f findings]
          (let [ids (set (cons (:id f) (:cves f)))
                kev? (boolean (some kev (:cves f)))
                score (some->> (seq (keep epss (:cves f))) (apply max))
                why (gate-reasons f kev? score opts)
                entries (filter #(ids (:id %)) allowlist)
                allowed (first (filter current? entries))
                expired (first (remove current? entries))]
            (assoc f
                   :kev kev?
                   :epss score
                   :why (cond-> why
                          (and (seq why) expired (not allowed))
                          (conj (str "allowlist entry expired " (:expires expired))))
                   :allowed allowed
                   :entries entries
                   :verdict (cond allowed :suppressed
                                  (seq why) :fail
                                  :else :report))))
        matched (set (mapcat :entries decided))
        order {:fail 0 :suppressed 1 :report 2}]
    {:findings (->> decided
                    (map #(dissoc % :entries))
                    (sort-by (juxt (comp order :verdict)
                                   #(- (or (:epss %) -1))
                                   #(- (or (:cvss %) -1))
                                   :id))
                    vec)
     :expired (vec (filter #(and (matched %) (not (current? %))) allowlist))
     :unused (vec (remove matched allowlist))}))

(defn failed? [result]
  (boolean (some #(= :fail (:verdict %)) (:findings result))))

;; report

(def ^:private report-rows 100)

(defn- cell [v] (if (nil? v) "–" (str/replace (str v) "|" "\\|")))

(defn- table [header rows]
  (str/join "\n" (concat [(str "| " (str/join " | " header) " |")
                          (str "|" (str/join "|" (repeat (count header) "---")) "|")]
                         (for [r rows] (str "| " (str/join " | " (map cell r)) " |")))))

(defn- epss-cell [f] (some->> (:epss f) (format "%.3f")))

(defn summary
  "The gate's result as GitHub-flavoured markdown."
  [{:keys [findings expired unused warnings] :as result} {:keys [epss-min cvss-min]}]
  (let [by (group-by :verdict findings)
        fail (:fail by) suppressed (:suppressed by) report (:report by)]
    (str/join
     "\n\n"
     (remove nil?
             [(str "## Vulnerability gate: " (if (failed? result) "FAILED" "passed")
                   " — " (count fail) " failing, " (count suppressed) " suppressed, "
                   (count report) " report only")
              (str "Fails on a finding in the CISA KEV catalog, or with EPSS >= " epss-min
                   " and CVSS >= " cvss-min ". Suppress one in `.vuln-ignore.yaml`.")
              (when (seq warnings)
                (str "### Warnings\n\n" (str/join "\n" (map #(str "- " %) warnings))))
              (when (seq fail)
                (str "### Failing\n\n"
                     (table ["ID" "Package" "Installed" "Fixed in" "CVSS" "EPSS" "Why"]
                            (for [f fail]
                              [(:id f) (:package f) (:version f) (:fixed f) (:cvss f) (epss-cell f)
                               (str/join "; " (:why f))]))))
              (when (seq suppressed)
                (str "### Suppressed\n\n"
                     (table ["ID" "Package" "Installed" "Reason" "Expires"]
                            (for [f suppressed]
                              [(:id f) (:package f) (:version f)
                               (get-in f [:allowed :reason]) (get-in f [:allowed :expires])]))))
              (when (seq expired)
                (str "### Expired allowlist entries\n\n"
                     (table ["ID" "Reason" "Expired"]
                            (for [e expired] [(:id e) (:reason e) (:expires e)]))))
              (when (seq unused)
                (str "### Unused allowlist entries\n\nNo finding matches these; remove them.\n\n"
                     (table ["ID" "Reason" "Expires"]
                            (for [e unused] [(:id e) (:reason e) (:expires e)]))))
              (when (seq report)
                (str "### Report only\n\n"
                     (table ["ID" "Package" "Installed" "Fixed in" "Severity" "CVSS" "EPSS"]
                            (for [f (take report-rows report)]
                              [(:id f) (:package f) (:version f) (:fixed f) (:severity f)
                               (:cvss f) (epss-cell f)]))
                     (when (> (count report) report-rows)
                       (str "\n\n…and " (- (count report) report-rows)
                            " more in the JSON report."))))]))))

(defn- json-safe [result]
  (let [entry #(some-> % (update :expires str))]
    (-> result
        (update :findings (partial mapv #(update % :allowed entry)))
        (update :expired (partial mapv entry))
        (update :unused (partial mapv entry)))))

;; CLI

(def ^:private spec
  {:allowlist {:default ".vuln-ignore.yaml" :desc "allowlist file; a missing file allows nothing"}
   :kev {:default kev-url :desc "KEV catalog: URL or local file"}
   :epss {:desc "EPSS API answer as a local file (default: ask api.first.org)"}
   :epss-min {:default 0.1 :coerce :double}
   :cvss-min {:default 7.0 :coerce :double}
   :today {:desc "YYYY-MM-DD the allowlist expiry is checked against (default: today, UTC)"}
   :summary {:desc "append the markdown summary to this file (e.g. $GITHUB_STEP_SUMMARY)"}
   :report {:desc "write the full result as JSON to this file"}})

(defn run
  "Runs the gate; returns the exit code."
  [args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec spec})
        {:keys [allowlist kev epss] summary-file :summary report-file :report} opts]
    (try
      (when (empty? args)
        (fail! "no Trivy JSON report given"))
      (when-let [late (seq (filter #(str/starts-with? % "--") args))]
        (fail! (str "options go before the Trivy reports: " (str/join " " late))))
      (let [today (if (:today opts) (LocalDate/parse (:today opts)) (LocalDate/now ZoneOffset/UTC))
            {:keys [entries errors]} (if (fs/exists? allowlist)
                                       (parse-allowlist (slurp allowlist))
                                       {:entries [] :errors []})
            _ (when (seq errors)
                (fail! (str allowlist " is malformed:\n  " (str/join "\n  " errors))))
            reports (mapv #(read-json % "Trivy report") args)
            found (findings reports)
            cves (sort (distinct (mapcat :cves found)))
            result (-> (evaluate found (assoc opts
                                              :kev (load-kev kev)
                                              :epss (if (seq cves) (load-epss epss cves) {})
                                              :allowlist entries
                                              :today today))
                       (assoc :warnings (warnings reports)))
            md (summary result opts)]
        (println md)
        (when summary-file (spit summary-file (str md "\n") :append true))
        (when report-file (spit report-file (json/generate-string (json-safe result) {:pretty true})))
        (if (failed? result) 1 0))
      (catch clojure.lang.ExceptionInfo e
        (if (:gate/error (ex-data e))
          (do (binding [*out* *err*] (println "vuln-gate:" (ex-message e))) 2)
          (throw e))))))

(defn -main [& args]
  (System/exit (run args)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
