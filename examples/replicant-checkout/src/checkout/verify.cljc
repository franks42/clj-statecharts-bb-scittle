(ns checkout.verify
  "Chart ↔ view sync check. Exit code = number of errors.
    nbb: npx nbb -cp src:examples/replicant-checkout/src -m checkout.verify
    bb:  bb -cp src:examples/replicant-checkout/src -m checkout.verify"
  (:require [statecharts.integrations.replicant :as sr]
            [checkout.chart :as chart]
            [checkout.views :as views]))

(defn run []
  (let [problems (sr/check {:machine chart/machine
                            :view views/root
                            :fixtures chart/fixtures
                            :external-events chart/external-events})
        errors (count (filter #(= :error (:severity %)) problems))]
    (doseq [p problems] (println (sr/format-problem p)))
    (println (str "chart/view sync: " errors " errors, "
                  (- (count problems) errors) " warnings, "
                  (count chart/fixtures) " fixtures"))
    errors))

(defn -main [& _]
  (let [n (run)]
    #?(:clj (System/exit (min n 1))
       :cljs (set! (.-exitCode js/process) (min n 1)))
    nil))
