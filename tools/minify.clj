(ns minify
  "The release's browser code: one minified esbuild bundle in place of the
  squint modules and the vendored reagami and squint-cljs packages that
  index.html loads through an import map. `bb dev` and `bb serve` keep
  the unbundled modules."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private entry-src "/js/simpleviz/app.mjs")
(def ^:private bundle-src "/js/app.min.mjs")

(def ^:private importmap-re #"(?s)<script type=\"importmap\">.*?</script>\n?")

(defn index-html
  "index.html loading the bundle: without the import map, its module script
  pointing at the bundle. Throws when either is missing, or when the page
  references another file under /js/ (the release deletes those), so a
  changed page fails the release instead of shipping one that loads nothing."
  [html]
  (let [entry (str "src=\"" entry-src "\"")]
    (when-not (and (re-find importmap-re html) (str/includes? html entry))
      (throw (ex-info "index.html has no import map or module script to rewrite" {})))
    (let [out (-> html
                  (str/replace importmap-re "")
                  (str/replace entry (str "src=\"" bundle-src "\"")))]
      (when-let [other (seq (remove #{bundle-src} (re-seq #"/js/[^\"'\s>]*" out)))]
        (throw (ex-info (str "index.html references " (str/join ", " other) ", which the bundle replaces") {})))
      out)))

(def ^:private kept
  "Files under js/ the release keeps beside the bundle: squint-cljs's
  license, whose text THIRD-PARTY-NOTICES.md points to."
  #{"vendor/squint-cljs/LICENSE"})

(defn minify-public!
  "Bundles and minifies the staged public dir `dir` in place: writes
  js/app.min.mjs, rewrites index.html to load it and deletes every other
  file under js/ but the `kept` licenses. Bare imports resolve from this
  repo's node_modules, squint-cljs always to its top-level copy: reagami
  brings its own older one, which dev's import map never loads."
  [dir]
  (let [js (fs/path dir "js")
        bundle (fs/path dir (subs bundle-src 1))
        index (str (fs/path dir "index.html"))]
    (p/shell {:extra-env {"NODE_PATH" (str (fs/absolutize "node_modules"))}}
             "npx" "--no-install" "esbuild" (str (fs/path dir (subs entry-src 1)))
             "--bundle" "--minify" "--format=esm" "--log-level=warning"
             (str "--alias:squint-cljs=" (fs/absolutize "node_modules/squint-cljs"))
             (str "--outfile=" bundle))
    (spit index (index-html (slurp index)))
    ;; hidden paths too: reagami's package carries node_modules/.bin links
    (doseq [f (fs/glob js "**" {:hidden true})
            :when (not (fs/directory? f {:nofollow-links true}))
            :let [rel (str (fs/relativize js f))]
            :when (not (or (= f bundle) (kept rel)))]
      (fs/delete f))
    (doseq [d (sort-by #(- (count (str %))) (fs/glob js "**" {:hidden true}))
            :when (and (fs/directory? d {:nofollow-links true}) (empty? (fs/list-dir d)))]
      (fs/delete d))))
