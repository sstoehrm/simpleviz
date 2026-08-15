(ns edit
  "Semantic edit operations on the raw text of a graph EDN file.
  rewrite-clj zipper patches: comments and formatting survive. Map-form
  files only. Every public op is text -> text; failures throw ex-info
  with {:edit-error true} and a user-facing message."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(defn- fail! [msg] (throw (ex-info msg {:edit-error true})))

(defn- ident->str [x] (if (keyword? x) (subs (str x) 1) (str x)))

(defn ident-node
  "New ids as keywords when legal, else strings."
  [id]
  (if (re-matches #"[A-Za-z0-9*+!_'?<>=./-]+" id) (keyword id) id))

(defn- edn-value
  "Parse an op's EDN-text value. :fallback true: unparseable or bare
  symbol becomes the raw string. :fallback false: unparseable fails."
  [s fallback]
  (let [v (try (edn/read-string s) (catch Exception _ ::bad))]
    (cond
      (and (= ::bad v) fallback) s
      (= ::bad v) (fail! (str "value " (pr-str s) " does not parse as EDN"))
      (and (symbol? v) fallback) s
      :else v)))

(defn- zroot [text]
  (let [zl (try (z/of-string text) (catch Exception _ (fail! "file does not parse as EDN")))]
    (when-not (map? (z/sexpr zl)) (fail! "root must be a map"))
    zl))

(defn- find-key
  "zloc of the KEY in the map at zmap whose sexpr satisfies pred, nil if none."
  [zmap pred]
  (loop [k (z/down zmap)]
    (when (some? k)
      (if (pred (z/sexpr k)) k (recur (z/right (z/right k)))))))

(defn- find-val [zmap pred] (some-> (find-key zmap pred) z/right))

(defn- sect-val
  "zloc of the top-level section map (:nodes/:edges/:boxes). Fails on
  vector form; nil when the key is absent."
  [root section]
  (when-let [v (find-val root #(= % section))]
    (when-not (map? (z/sexpr v))
      (fail! (str "pre-v2 vector form: convert " (name section) " to map form to edit")))
    v))

(defn- edge-key-pred [[from to]]
  (fn [s] (and (vector? s) (= 2 (count s)) (= [from to] (mapv ident->str s)))))

(defn- entry-pred [section id]
  (if (= section :edges)
    (edge-key-pred id)
    (fn [s] (and (or (keyword? s) (string? s)) (= (ident->str s) id)))))

(defn- unknown! [section id]
  (fail! (str "unknown " (case section :nodes "node" :edges "edge" :boxes "box")
              " " (if (vector? id) (str "[" (str/join " " id) "]") (pr-str id)))))

(defn- entry-val
  "zloc of the entry VALUE for id in section. Fails when missing."
  [root section id]
  (let [sect (or (sect-val root section) (unknown! section id))]
    (or (find-val sect (entry-pred section id)) (unknown! section id))))

(defn- remove-pair
  "Remove a key zloc and its value; returns the root zipper."
  [kloc]
  (-> kloc z/right z/remove z/remove z/up))

(defn set-attr [text {:keys [section id attr value fallback]}]
  (let [v (edn-value value fallback)
        entry (entry-val (zroot text) section id)]
    (z/root-string
     (if (nil? (z/sexpr entry))
       (z/replace entry {attr v})
       (if-let [av (find-val entry #(= % attr))]
         (z/replace av v)
         (-> entry (z/append-child attr) (z/append-child v)))))))

(defn del-attr [text {:keys [section id attr]}]
  (let [entry (entry-val (zroot text) section id)]
    (when (nil? (z/sexpr entry)) (fail! (str "no attribute " attr " to delete")))
    (let [k (or (find-key entry #(= % attr))
                (fail! (str "no attribute " attr " to delete")))]
      (z/root-string (remove-pair k)))))
