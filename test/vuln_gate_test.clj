(ns vuln-gate-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vuln-gate])
  (:import [java.time LocalDate]))

(def ^:private dir "test/fixtures/vuln-gate")

(defn- fixture [f] (str (fs/path dir f)))

(defn- trivy [] (json/parse-string (slurp (fixture "trivy.json")) true))

(def ^:private today (LocalDate/parse "2026-10-22"))

(defn- evaluate
  ([] (evaluate (slurp (fixture "allowlist.yaml"))))
  ([allowlist-text]
   (vuln-gate/evaluate (vuln-gate/findings [(trivy)])
                       {:kev (vuln-gate/kev-ids (json/parse-string (slurp (fixture "kev.json")) true))
                        :epss (vuln-gate/epss-scores (json/parse-string (slurp (fixture "epss.json")) true))
                        :allowlist (:entries (vuln-gate/parse-allowlist allowlist-text))
                        :today today
                        :epss-min 0.1
                        :cvss-min 7.0})))

(defn- verdicts [result]
  (into {} (map (juxt :id :verdict)) (:findings result)))

(deftest findings-flatten-every-result-of-every-report
  (let [fs (vuln-gate/findings [(trivy)])]
    (is (= 7 (count fs)))
    (is (= {:id "CVE-2021-44228"
            :cves ["CVE-2021-44228"]
            :package "org.apache.logging.log4j:log4j-core"
            :version "2.14.1"
            :fixed "2.15.0, 2.3.1, 2.12.2"
            :severity "CRITICAL"
            :cvss 10.0
            :target "sbom/vulnerable.cdx.json: Java"}
           (-> (first (filter #(= "CVE-2021-44228" (:id %)) fs))
               (select-keys [:id :cves :package :version :fixed :severity :cvss :target]))))))

(deftest findings-dedupe-the-same-vulnerability-in-the-same-package
  (is (= 7 (count (vuln-gate/findings [(trivy) (trivy)])))))

(deftest cvss-takes-the-highest-v3-or-v4-score-of-any-source
  (is (= 9.8 (vuln-gate/cvss {:CVSS {:nvd {:V3Score 7.5 :V2Score 10.0} :ghsa {:V40Score 9.8}}})))
  (testing "without a score, HIGH and CRITICAL count as 7.0, the floor of their CVSS band"
    (is (= 7.0 (vuln-gate/cvss {:Severity "HIGH"})))
    (is (= 7.0 (vuln-gate/cvss {:Severity "CRITICAL" :CVSS {:nvd {:V2Score 9.0}}})))
    (is (nil? (vuln-gate/cvss {:Severity "MEDIUM"})))))

(deftest cve-ids-come-from-the-id-and-the-aliases
  (is (= ["CVE-2024-1" "CVE-2024-2"]
         (vuln-gate/cve-ids {:VulnerabilityID "GHSA-xxxx" :VendorIDs ["CVE-2024-1" "CVE-2024-2" "GHSA-yyyy"]})))
  (is (= [] (vuln-gate/cve-ids {:VulnerabilityID "NSWG-ECO-516"}))))

(deftest the-policy-fails-on-kev-or-on-epss-and-cvss-together
  (let [v (verdicts (evaluate))]
    (is (= :fail (v "CVE-2021-44228")) "in KEV")
    (is (= :fail (v "CVE-2019-17531")) "EPSS 0.4, CVSS 9.8")
    (is (= :report (v "CVE-2020-10650")) "CVSS 8.1, but EPSS 0.05")
    (is (= :report (v "CVE-2020-28500")) "EPSS 0.3, but CVSS 5.3")
    (is (= :report (v "NSWG-ECO-516")) "no CVE: neither KEV nor EPSS knows it")))

(deftest a-failing-finding-says-why
  (let [by-id (into {} (map (juxt :id identity)) (:findings (evaluate)))]
    (is (= ["CISA KEV" "EPSS 1.000 >= 0.1 and CVSS 10.0 >= 7.0"] (:why (by-id "CVE-2021-44228"))))
    (is (= ["EPSS 0.400 >= 0.1 and CVSS 9.8 >= 7.0"] (:why (by-id "CVE-2019-17531"))))
    (is (= 0.4 (:epss (by-id "CVE-2019-17531"))))
    (is (true? (:kev (by-id "CVE-2021-44228"))))))

(deftest a-current-allowlist-entry-suppresses-a-failing-finding
  (let [f (first (filter #(= "CVE-2020-8203" (:id %)) (:findings (evaluate))))]
    (is (= :suppressed (:verdict f)))
    (is (= "zipObjectDeep is never called with untrusted input" (get-in f [:allowed :reason])))))

(deftest an-expired-allowlist-entry-no-longer-suppresses
  (let [result (evaluate)
        f (first (filter #(= "CVE-2019-14892" (:id %)) (:findings result)))]
    (is (= :fail (:verdict f)))
    (is (some #(str/includes? % "allowlist entry expired 2026-09-30") (:why f)))
    (is (= ["CVE-2019-14892"] (map :id (:expired result))))))

(deftest an-entry-is-valid-through-its-expiry-day
  (let [text "ignore:\n  - id: CVE-2019-14892\n    reason: r\n    expires: 2026-10-22\n"]
    (is (= :suppressed ((verdicts (evaluate text)) "CVE-2019-14892")))))

(deftest unused-allowlist-entries-are-reported
  (is (= ["CVE-2000-0001"] (map :id (:unused (evaluate))))))

(deftest the-gate-fails-only-on-failing-findings
  (is (vuln-gate/failed? (evaluate)))
  (is (not (vuln-gate/failed? (evaluate (str "ignore:\n"
                                             "  - {id: CVE-2021-44228, reason: r, expires: 2027-01-01}\n"
                                             "  - {id: CVE-2019-17531, reason: r, expires: 2027-01-01}\n"
                                             "  - {id: CVE-2019-14892, reason: r, expires: 2027-01-01}\n"
                                             "  - {id: CVE-2020-8203, reason: r, expires: 2027-01-01}\n"))))))

(deftest the-allowlist-matches-a-cve-alias-too
  (let [f (vuln-gate/evaluate [{:id "GHSA-aaaa" :cves ["CVE-2024-9"] :cvss 9.0 :package "p" :version "1"}]
                              {:kev #{"CVE-2024-9"} :epss {} :today today :epss-min 0.1 :cvss-min 7.0
                               :allowlist [{:id "CVE-2024-9" :reason "r" :expires (LocalDate/parse "2027-01-01")}]})]
    (is (= :suppressed (:verdict (first (:findings f)))))))

(deftest the-allowlist-ignores-case
  (let [f (vuln-gate/evaluate [{:id "CVE-2024-9" :cves ["CVE-2024-9"] :cvss 9.0 :package "p" :version "1"}]
                              {:kev #{"CVE-2024-9"} :epss {} :today today :epss-min 0.1 :cvss-min 7.0
                               :allowlist [{:id "cve-2024-9" :reason "r" :expires (LocalDate/parse "2027-01-01")}]})]
    (is (= :suppressed (:verdict (first (:findings f)))))))

(deftest parse-allowlist-reads-dates-quoted-or-not
  (is (= {:entries [{:id "CVE-2024-1" :reason "a" :expires (LocalDate/parse "2026-12-31")}
                    {:id "CVE-2024-2" :reason "b" :expires (LocalDate/parse "2027-01-15")}]
          :errors []}
         (vuln-gate/parse-allowlist (str "ignore:\n"
                                         "  - {id: CVE-2024-1, reason: a, expires: 2026-12-31}\n"
                                         "  - {id: CVE-2024-2, reason: b, expires: \"2027-01-15\"}\n")))))

(deftest parse-allowlist-accepts-an-empty-file
  (is (= {:entries [] :errors []} (vuln-gate/parse-allowlist "")))
  (is (= {:entries [] :errors []} (vuln-gate/parse-allowlist "# nothing yet\nignore: []\n"))))

(deftest parse-allowlist-requires-id-reason-and-a-valid-expiry
  (let [{:keys [errors]} (vuln-gate/parse-allowlist
                          (str "ignore:\n"
                               "  - {reason: a, expires: 2026-12-31}\n"
                               "  - {id: CVE-2024-2, expires: 2026-12-31}\n"
                               "  - {id: CVE-2024-3, reason: \"  \", expires: 2026-12-31}\n"
                               "  - {id: CVE-2024-4, reason: d}\n"
                               "  - {id: CVE-2024-5, reason: e, expires: next year}\n"))]
    (is (= ["entry 1: id is missing"
            "entry 2 (CVE-2024-2): reason is missing"
            "entry 3 (CVE-2024-3): reason is missing"
            "entry 4 (CVE-2024-4): expires is missing"
            "entry 5 (CVE-2024-5): expires is not a YYYY-MM-DD date: next year"]
           errors))))

(deftest parse-allowlist-rejects-a-file-without-an-ignore-list
  (is (= ["the file must hold a top-level `ignore:` list"]
         (:errors (vuln-gate/parse-allowlist "- {id: CVE-2024-1, reason: a, expires: 2026-12-31}\n")))))

(deftest an-end-of-life-os-is-a-warning
  ;; Trivy finds nothing in an end-of-life OS: no one publishes its advisories
  (is (= ["image.tar: alpine 3.14.10 is end-of-life; its packages get no security fixes and the scan cannot see their vulnerabilities"]
         (vuln-gate/warnings [{:ArtifactName "image.tar"
                               :Metadata {:OS {:Family "alpine" :Name "3.14.10" :EOSL true}}}
                              {:ArtifactName "ok.tar"
                               :Metadata {:OS {:Family "debian" :Name "12.12"}}}
                              (trivy)])))
  (is (str/includes? (vuln-gate/summary (assoc (evaluate) :warnings ["w1"]) {:epss-min 0.1 :cvss-min 7.0})
                     "### Warnings\n\n- w1")))

(deftest epss-is-asked-for-in-batches-of-100
  (is (= [100 100 37] (map count (vuln-gate/epss-batches (map #(str "CVE-2024-" %) (range 237)))))))

(deftest the-summary-lists-each-group
  (let [md (vuln-gate/summary (evaluate) {:epss-min 0.1 :cvss-min 7.0})]
    (is (str/includes? md "3 failing"))
    (is (str/includes? md "| CVE-2021-44228 |"))
    (is (str/includes? md "### Suppressed"))
    (is (str/includes? md "### Report only"))
    (is (str/includes? md "CVE-2000-0001"))))

;; the script, run the way CI runs it

(defn- temp-file [suffix content]
  (let [f (str (fs/create-temp-file {:suffix suffix}))]
    (spit f content)
    f))

(defn- run-gate
  "Runs the script on the fixtures; `opts` replaces any of its default flags."
  [opts & scans]
  (let [out (str (fs/create-temp-dir {:prefix "vuln-gate-test"}))
        flags (merge {"--kev" (fixture "kev.json") "--epss" (fixture "epss.json")
                      "--allowlist" (fixture "allowlist.yaml") "--today" "2026-10-22"
                      "--summary" (str out "/summary.md") "--report" (str out "/report.json")}
                     opts)
        r (apply p/shell {:out :string :err :string :continue true}
                 "bb" "tools/vuln_gate.clj"
                 (concat (mapcat identity flags) scans))]
    (assoc r :dir out)))

(deftest the-script-exits-1-when-the-gate-fails
  (let [{:keys [exit dir]} (run-gate {} (fixture "trivy.json"))]
    (is (= 1 exit))
    (is (str/includes? (slurp (str dir "/summary.md")) "CVE-2021-44228"))
    (is (= 7 (count (:findings (json/parse-string (slurp (str dir "/report.json")) true)))))))

(deftest the-script-exits-0-with-only-report-only-findings
  (let [{:keys [exit dir]} (run-gate {"--kev" (temp-file ".json" "{\"vulnerabilities\": []}")
                                      "--cvss-min" "10.5"}
                                     (fixture "trivy.json"))]
    (is (= 0 exit))
    (is (str/includes? (slurp (str dir "/summary.md")) "0 failing"))))

(deftest the-script-runs-without-an-allowlist-file
  (is (= 1 (:exit (run-gate {"--allowlist" "no-such-file.yaml"} (fixture "trivy.json"))))))

(deftest the-script-exits-2-on-a-malformed-allowlist
  (let [{:keys [exit err]} (run-gate {"--allowlist" (temp-file ".yaml" "ignore:\n  - {id: CVE-2024-1}\n")}
                                     (fixture "trivy.json"))]
    (is (= 2 exit))
    (is (str/includes? err "reason is missing"))))

(deftest the-script-exits-2-on-unparseable-yaml
  (let [{:keys [exit err]} (run-gate {"--allowlist" (temp-file ".yaml" "ignore:\n  - id: [unclosed\n")}
                                     (fixture "trivy.json"))]
    (is (= 2 exit))
    (is (str/includes? err "is malformed"))))

(deftest the-script-exits-2-on-a-bad-today
  (is (= 2 (:exit (run-gate {"--today" "2026-13-01"} (fixture "trivy.json"))))))

(deftest the-script-fails-closed-when-a-feed-cannot-be-read
  (is (= 2 (:exit (run-gate {"--kev" "no-such-kev.json"} (fixture "trivy.json")))))
  (is (= 2 (:exit (run-gate {"--kev" (fixture "epss.json")} (fixture "trivy.json"))))
      "a KEV file without a vulnerabilities list")
  (is (= 2 (:exit (run-gate {"--epss" (fixture "kev.json")} (fixture "trivy.json"))))
      "an EPSS answer without a data list"))

(deftest the-script-exits-2-without-a-scan
  (is (= 2 (:exit (run-gate {})))))

(deftest the-script-says-so-when-an-option-follows-the-reports
  (let [{:keys [exit err]} (run-gate {} (fixture "trivy.json") "--epss-min" "0.5")]
    (is (= 2 exit))
    (is (str/includes? err "options go before the Trivy reports: --epss-min"))))
