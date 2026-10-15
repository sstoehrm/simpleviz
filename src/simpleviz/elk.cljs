(ns simpleviz.elk
  "ELK off the page's thread: a big graph takes seconds to lay out, and
  in a Web Worker the page stays responsive meanwhile.")

(defn layouter
  "A layout function, ELK graph -> promise of the laid out graph, running
  `ELK` (elkjs' class) in the worker that (spawn url) starts. When no
  worker starts — spawn throws, or the worker fails (its script missing,
  say) — ELK runs in this thread from then on, and a layout the failure
  caught in flight runs again there."
  [ELK url spawn]
  (let [local (atom nil)
        in-thread (fn [g] (.layout (or @local (reset! local (ELK.))) g))
        ;; the worker's ELK, nil once it failed (or never started)
        worker (atom nil)
        ;; the layouts in the worker, each a fn that runs it here instead;
        ;; one leaves when its answer comes, so nothing outlives its run
        in-flight (js/Set.)]
    (try
      (let [w (spawn url)]
        (try
          ;; a factory, not :workerUrl: given that, the bundle looks for the
          ;; web-worker package and, not finding it, lays out in its
          ;; in-thread stand-in
          (reset! worker (ELK. {:workerFactory (fn [_] w)}))
          (finally (when (nil? @worker) (.terminate w))))
        (.addEventListener w "error"
                           (fn [_]
                             (when (some? @worker)
                               (reset! worker nil)
                               (.terminate w)
                               (js/console.warn "ELK lays out in the page's thread, the worker failed")
                               (let [runs (js/Array.from in-flight)]
                                 (.clear in-flight)
                                 (doseq [run-here runs] (run-here)))))))
      (catch :default e
        (js/console.warn "ELK lays out in the page's thread, no worker started:" e)))
    (fn [g]
      (if-let [elk @worker]
        (js/Promise.
         (fn [res rej]
           (let [run-here (fn [] (res (.then (js/Promise.resolve) (fn [] (in-thread g)))))]
             (.add in-flight run-here)
             (.then (.layout elk g)
                    (fn [r] (.delete in-flight run-here) (res r))
                    (fn [e] (.delete in-flight run-here) (rej e))))))
        (in-thread g)))))
