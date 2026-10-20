(ns steps
  "A graph's :steps — a vector of {:name \"..\" :edits [..]}, each an
  editscript (juji/editscript) edit vector applied to the state before
  it: state 0 is the file without :steps, state k the patch of state
  k-1 by step k. Reading never throws: a step that does not apply warns
  and changes nothing. Editing works on the file text like edit.clj:
  step ops rewrite :steps in place, and graph ops at a step run on that
  state and store its diff from the state before as the step's edits."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [editscript.core :as e]
            [edit]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn- fail! [msg] (throw (ex-info msg {:edit-error true})))

(defn- pr-edn [x]
  (binding [*print-namespace-maps* false] (pr-str x)))

(defn edits-text
  "The EDN text of edit vector `edits`, one edit a line, the lines after
  the first indented by `indent` + 1 spaces (under the first edit)."
  [edits indent]
  (str "[" (str/join (str "\n" (apply str (repeat (inc indent) " "))) (map pr-edn edits)) "]"))

(defn- edits-error
  "nil when `edits` is a vector of edits, else why not."
  [edits]
  (when-not (and (vector? edits)
                 (try (e/edits->script edits) true (catch Throwable _ false)))
    ":edits must be a vector of edits [path op value?], op one of :+ :- :r :s"))

(defn- patch
  "[state nil] with `edits` applied to `state` one by one, or [nil why]
  naming the first edit that does not apply."
  [state edits]
  (reduce (fn [[s _] ed]
            (try [(e/patch s (e/edits->script [ed])) nil]
                 (catch Throwable _
                   (reduced [nil (str "edit " (pr-edn ed) " does not apply")]))))
          [state nil] edits))

(defn step-name
  "Step `s`'s display name: its :name, or \"step k\" (k from 1)."
  [s k]
  (let [nm (when (map? s) (:name s))]
    (if (and (string? nm) (not (str/blank? nm))) nm (str "step " k))))

(defn states
  "{:states [state0 state1 ..] :names [step names] :warnings [..]} of
  raw graph map `raw`: one state per step after the first, a step that
  is malformed or does not apply warning and repeating the state before
  it, so step k is always state k."
  [raw]
  (let [steps (when (map? raw) (:steps raw))
        base (if (map? raw) (dissoc raw :steps) raw)]
    (cond
      (nil? steps) {:states [base] :names [] :warnings []}
      (not (vector? steps)) {:states [base] :names []
                             :warnings [":steps must be a vector of steps, ignoring it"]}
      :else
      (reduce
       (fn [{:keys [states] :as acc} [i s]]
         (let [k (inc i)
               nm (step-name s k)
               prev (peek states)
               [nxt why] (cond
                           (not (map? s)) [prev (str "step " k ": must be a map {:name .. :edits [..]}, skipped")]
                           (nil? (:edits s)) [prev nil]
                           :else (if-let [err (edits-error (:edits s))]
                                   [prev (str "step " k " \"" nm "\": " err ", skipped")]
                                   (let [[out err] (patch prev (:edits s))]
                                     (if (some? err)
                                       [prev (str "step " k " \"" nm "\": " err ", skipped")]
                                       [out nil]))))]
           (cond-> (-> acc (update :states conj nxt) (update :names conj nm))
             (some? why) (update :warnings conj why))))
       {:states [base] :names [] :warnings []}
       (map-indexed vector steps)))))

(defn attach
  "Normalized graph `g` of raw graph `raw` with, when raw has steps,
  :steps [{:name :edits (text) :graph (normalize state)}] and the
  steps' warnings — their own, and those a state's normalize adds to
  the base's, prefixed with the step — added to g's."
  [g raw normalize]
  (let [{:keys [states names warnings]} (states raw)]
    (if (and (empty? names) (empty? warnings))
      g
      (let [base-warnings (set (:warnings g))
            raw-steps (when (vector? (:steps raw)) (:steps raw))
            entries (map-indexed
                     (fn [i st]
                       (let [ng (normalize st)
                             s (nth raw-steps i nil)
                             edits (when (map? s) (:edits s))]
                         {:name (nth names i)
                          :edits (if (vector? edits) (edits-text edits 0) (pr-edn (or edits [])))
                          :graph (assoc ng :warnings [])
                          :warnings (map (fn [w] (str "step " (inc i) " \"" (nth names i) "\": " w))
                                         (remove base-warnings (:warnings ng)))}))
                     (rest states))]
        (-> g
            (assoc :steps (mapv #(dissoc % :warnings) entries))
            (update :warnings (fn [ws] (-> (vec ws) (into warnings) (into (mapcat :warnings entries))))))))))

;; ---- editing ----

(defn- tracked-root [text]
  (edit/zroot text)
  (z/of-string text {:track-position? true}))

(defn- form-at
  "loc, or the first form right of it that is no `#_` discard."
  [loc]
  (loop [c loc] (if (and (some? c) (= :uneval (z/tag c))) (recur (z/right c)) c)))

(defn- steps-loc
  "zloc of the :steps vector, nil when absent; fails on another value."
  [root]
  (when-let [v (edit/find-val root #(= % :steps))]
    (when-not (vector? (z/sexpr v)) (fail! ":steps must be a vector to edit it"))
    v))

(defn- step-locs [sv]
  (loop [c (form-at (z/down sv)) acc []]
    (if (nil? c) acc (recur (form-at (z/right c)) (conj acc c)))))

(defn- step-loc
  "zloc of step k (from 1) in text's :steps; fails when there is none."
  [root k]
  (let [sv (steps-loc root)
        locs (when (some? sv) (step-locs sv))]
    (when-not (and (integer? k) (<= 1 k (count locs)))
      (fail! (str "no step " k)))
    (let [loc (nth locs (dec k))]
      (when-not (map? (z/sexpr loc)) (fail! (str "step " k " is not a map")))
      loc)))

(defn- column [loc] (dec (second (z/position loc))))

(defn- set-key
  "Map zloc `m` with key `k` set to the node `v` (rewrite-clj node)."
  [m k v]
  (if-let [vloc (edit/find-val m #(= % k))]
    (z/up (z/replace vloc v))
    (-> m (z/append-child k) (z/append-child v))))

(defn- node-of [s] (z/node (z/of-string s)))

(defn- step-node [nm]
  (node-of (str "{:name " (pr-edn nm) " :edits []}")))

(defn add-step
  "Text with an empty step `name` after step `after` (0: first)."
  [text {:keys [after name]}]
  (let [nm (if (str/blank? (str name)) nil (str name))
        root (tracked-root text)
        sv (steps-loc root)
        locs (if (some? sv) (step-locs sv) [])
        node (step-node (or nm (str "step " (inc (or after 0)))))]
    (when-not (and (integer? after) (<= 0 after (count locs)))
      (fail! (str "no step " after " to add after")))
    (z/root-string
     (cond
       (nil? sv)
       (if (some? (z/down root))
         (-> root
             (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces 1))
             (z/append-child* (n/keyword-node :steps)) (z/append-child* (n/spaces 1))
             (z/append-child* (n/vector-node [(step-node (or nm "step 1"))])))
         (-> root (z/append-child :steps) (z/append-child (n/vector-node [(step-node (or nm "step 1"))]))))

       (empty? locs) (z/append-child sv node)

       (zero? after)
       (let [first-loc (first locs)]
         (-> first-loc
             (z/insert-left* node) (z/insert-left* (n/newlines 1))
             (z/insert-left* (n/spaces (column first-loc)))))

       :else
       (let [loc (nth locs (dec after))]
         (-> loc
             (z/insert-right* node) (z/insert-right* (n/spaces (column (first locs))))
             (z/insert-right* (n/newlines 1))))))))

(defn delete-step
  "Text without step k; without :steps once the last step is gone."
  [text {:keys [step]}]
  (let [root (tracked-root text)
        loc (step-loc root step)]
    (if (= 1 (count (step-locs (steps-loc root))))
      (z/root-string (edit/remove-pair (edit/find-key (edit/zroot text) #(= % :steps))))
      (z/root-string (z/remove loc)))))

(defn rename-step [text {:keys [step name]}]
  (when (str/blank? (str name)) (fail! "a step needs a name"))
  (z/root-string (set-key (step-loc (tracked-root text) step) :name (n/coerce (str name)))))

(defn state-of
  "State k (0: the base) of text's graph; fails on a step that doesn't
  apply."
  [text k]
  (let [raw (try (edn/read-string text) (catch Exception _ (fail! "file does not parse as EDN")))
        {:keys [states]} (states raw)]
    (when-not (and (integer? k) (< -1 k (count states))) (fail! (str "no step " k)))
    (nth states k)))

(defn state-text
  "The EDN text of state k (0: the base) of text's graph."
  [text k]
  (pr-edn (state-of text k)))

(defn- write-edits
  "Text with step k's :edits set to `edits`."
  [text k edits]
  (let [loc (step-loc (tracked-root text) k)
        col (if-let [v (edit/find-val loc #(= % :edits))]
              (column v)
              (+ (column loc) 8))]
    (z/root-string (set-key loc :edits (node-of (edits-text edits col))))))

(defn- apply-edits-check
  "Refuses `edits` for step k unless they apply to state k-1."
  [text k edits]
  (when-let [err (edits-error edits)] (fail! err))
  (step-loc (tracked-root text) k)
  (let [[_ err] (patch (state-of text (dec k)) edits)]
    (when (some? err) (fail! err))))

(defn set-step-edits
  "Text with step k's :edits the edit vector in EDN text `edits`."
  [text {:keys [step edits]}]
  (let [v (try (edn/read-string (str edits)) (catch Exception _ (fail! "the edits do not parse as EDN")))]
    (apply-edits-check text step v)
    (write-edits text step v)))

(defn- edit-at-step
  "Text with graph op `op` applied to state k: step k's edits become the
  diff from state k-1 to the result."
  [text k op]
  (let [prev (state-of text (dec k))
        after (edn/read-string (edit/apply-op (state-text text k) op))]
    (write-edits text k (e/get-edits (e/diff prev after)))))

(def ^:private step-ops
  {"add-step" add-step "delete-step" delete-step
   "rename-step" rename-step "set-step-edits" set-step-edits})

(defn- apply-op [step text op]
  (let [f (get step-ops (:op op))]
    (cond
      (some? f) (f text op)
      ;; the theme belongs to the file, not to a state of it
      (or (nil? step) (zero? step) (= "set-theme" (:op op))) (edit/apply-op text op)
      :else (edit-at-step text step op))))

(defn apply-ops
  "Apply a batch of ops to text atomically, graph ops to state `step`
  (nil or 0: the file itself): {:text new-text} or {:error msg}."
  [text ops step]
  (try
    {:text (reduce (partial apply-op step) text ops)}
    (catch clojure.lang.ExceptionInfo ex
      (if (:edit-error (ex-data ex)) {:error (ex-message ex)} (throw ex)))))
