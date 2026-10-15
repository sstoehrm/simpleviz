(ns simpleviz.slides
  "A presentation of an md page: its slides, parsed by server/deck.cljc
  (which `simpleviz check` shares, so it reports the malformed questions
  the slides show), what a `>>` target shows as, and where a step stops
  for a `???` submit."
  (:require [deck :as deck]))

(defn slides
  "The slides of md `text`, in order: [{:text md :side path-or-nil}]."
  [text]
  (deck/slides text))

(defn side-kind
  "What a `>>` target shows as: \"graph\" (.edn), \"md\", \"export\" (.png
  or .svg: a graph when it carries simpleviz EDN, else a picture),
  \"image\" (.jpg .jpeg .gif .webp); nil for anything else."
  [dest]
  (let [low (.toLowerCase dest)]
    (get {"edn" "graph" "md" "md" "png" "export" "svg" "export"
          "jpg" "image" "jpeg" "image" "gif" "image" "webp" "image"}
         (.slice low (inc (.lastIndexOf low ".")))
         nil)))

(defn answer
  "The answer to question `id` in `answers`, or nil — its own entry
  only, never one an object inherits (a question named constructor)."
  [answers id]
  (if (and (some? answers) (js/Object.hasOwn answers id)) (get answers id) nil))

(defn- waiting?
  "Does slide `s` wait for its ??? answer to be submitted?"
  [s answers]
  (let [a (:ask s)]
    (and (some? a) (:submit a) (nil? (:error a)) (nil? (answer answers (:id a))))))

(defn stop-at
  "Where a step from slide `from` to slide `to` of deck `ds` lands: at the
  first ??? on the way still waiting for its submit, else at `to`."
  [ds answers from to]
  (or (first (filter (fn [k] (waiting? (nth ds k) answers)) (range from to)))
      to))
