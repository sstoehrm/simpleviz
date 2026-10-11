(ns paths-test
  (:require [clojure.test :refer [deftest is]]
            [paths]))

(deftest resolve-ref-collapses-segments-and-refuses-escapes
  (is (= "sub/api.edn" (paths/resolve-ref "root.edn" "sub/api.edn")))
  (is (= "sub/deep/db.edn" (paths/resolve-ref "sub/api.edn" "deep/db.edn")))
  (is (= "root.edn" (paths/resolve-ref "sub/api.edn" "../root.edn")))
  (is (= "sub/x.edn" (paths/resolve-ref "sub/api.edn" "./x.edn")))
  (is (nil? (paths/resolve-ref "root.edn" "../x.edn")))
  (is (nil? (paths/resolve-ref "root.edn" "/etc/x.edn")))
  (is (nil? (paths/resolve-ref "root.edn" "  ")))
  (is (nil? (paths/resolve-ref "root.edn" nil))))

(deftest md-path-is-any-case-dot-md
  (is (paths/md-path? "notes.md"))
  (is (paths/md-path? "a/B.MD"))
  (is (not (paths/md-path? "g.edn")))
  (is (not (paths/md-path? "md")))
  (is (not (paths/md-path? nil))))
