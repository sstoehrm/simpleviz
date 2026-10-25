(ns minify-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [minify]))

(deftest index-html-loads-the-bundle-without-an-import-map
  (let [out (minify/index-html (slurp "public/index.html"))]
    (is (not (str/includes? out "importmap")))
    (is (= ["<script type=\"module\" src=\"js/app.min.mjs\"></script>"]
           (re-seq #"<script type=\"module\"[^>]*></script>" out)))
    (is (str/includes? out "<script src=\"vendor/elk-api.js\"></script>"))))

(deftest index-html-names-its-files-relative-to-the-page
  ;; the page lives below the server's secret token: /style.css would miss it
  (let [html (slurp "public/index.html")]
    (is (nil? (re-seq #"(?:src|href)=\"/[^\"]*\"" html)))
    (is (nil? (re-seq #"\"/js/[^\"]*\"" html)) "nor does the import map")))

(deftest index-html-refuses-a-page-it-does-not-recognise
  ;; a changed index.html must fail the release, not ship a page that loads nothing
  (is (thrown? Exception (minify/index-html "<html><body></body></html>"))))

(deftest index-html-refuses-a-page-with-other-js-references
  ;; a second module or a preload would point at a file the release deletes
  (doseq [href ["js/simpleviz/canvas.mjs" "/js/simpleviz/canvas.mjs"]]
    (is (thrown? Exception
                 (minify/index-html (str (slurp "public/index.html")
                                         "<link rel=\"modulepreload\" href=\"" href "\">"))))))

(deftest minify-public-leaves-one-bundle-and-the-licenses
  ;; needs bb build's output: bb test runs build first
  (let [tmp (fs/create-temp-dir {:prefix "simpleviz-minify"})
        dir (fs/path tmp "public")]
    (try
      (fs/copy-tree "public" dir)
      (minify/minify-public! dir)
      (is (= #{"js/app.min.mjs" "js/vendor/squint-cljs/LICENSE"}
             (set (map #(str (fs/relativize dir %))
                       ;; hidden ones and symlinks too (reagami's node_modules/.bin)
                       (remove #(fs/directory? % {:nofollow-links true})
                               (fs/glob dir "js/**" {:hidden true}))))))
      (is (= [] (filter #(fs/directory? % {:nofollow-links true})
                        (fs/glob dir "js/simpleviz" {:hidden true}))))
      (is (str/includes? (slurp (str (fs/path dir "index.html"))) "\"js/app.min.mjs\""))
      ;; one squint runtime: reagami's own nested copy would be a second,
      ;; older one that dev (import map) and the tests never run
      (is (= 1 (count (re-seq #"squint\.core/IMap" (slurp (str (fs/path dir "js" "app.min.mjs")))))))
      (is (fs/exists? (fs/path dir "vendor" "elk-worker.min.js")))
      (finally (fs/delete-tree tmp)))))
