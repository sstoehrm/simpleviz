(ns simpleviz.slides
  "A presentation of an md page: its slides, parsed by server/deck.cljc
  (which `simpleviz check` shares, so it reports the malformed questions
  the slides show), what a `>` or `>>` target shows as, and where a step stops
  for a `???` submit."
  (:require [deck :as deck]
            [mdlinks :as mdlinks]))

(def slides
  "The slides of md text (deck/slides)."
  deck/slides)

(def side-kind
  "What a `>` or `>>` target shows as (mdlinks/side-kind)."
  mdlinks/side-kind)

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
