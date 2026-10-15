(ns simpleviz.elk
  "ELK off the page's thread: a big graph takes seconds to lay out, and
  in a Web Worker the page stays responsive meanwhile.")

(def ^:private worker-failed #js {})

(defn layouter
  "A layout function, ELK graph -> promise of the laid out graph, running
  `ELK` (elkjs' class) in the worker that (spawn url) starts. When no
  worker starts — spawn throws, or the worker fails (its script missing,
  say) — ELK runs in this thread from then on, and a layout the failure
  caught in flight runs again there."
  [ELK url spawn]
  (let [local (atom nil)
        in-thread (fn [g] (.layout (or @local (reset! local (ELK.))) g))
        worker (atom nil)]
    (try
      (let [w (spawn url)]
        (reset! worker
                {:w w
                 ;; a factory, not :workerUrl: given that, the bundle
                 ;; looks for the web-worker package and, not finding
                 ;; it, lays out in its in-thread stand-in
                 :elk (ELK. {:workerFactory (fn [_] w)})
                 :failed (js/Promise. (fn [res]
                                        (.addEventListener w "error" (fn [_] (res worker-failed)))))}))
      (catch :default e
        (js/console.warn "ELK lays out in the page's thread, no worker started:" e)))
    (fn [g]
      (if-let [{:keys [w elk failed]} @worker]
        (-> (js/Promise.race [(.layout elk g) failed])
            (.then (fn [r]
                     (if (identical? r worker-failed)
                       (do (when (some? @worker)
                             (reset! worker nil)
                             (.terminate w)
                             (js/console.warn "ELK lays out in the page's thread, the worker failed"))
                           (in-thread g))
                       r))))
        (in-thread g)))))
