(ns checkout.main
  "Browser glue only: effects that touch the world, and wiring to Replicant.
  Everything testable lives in chart.cljc and views.cljc."
  (:require [replicant.dom :as r]
            [statecharts.integrations.replicant :as sr]
            [checkout.chart :as chart]
            [checkout.views :as views]))

;; Effect handlers: (fn [dispatch & args]). dispatch takes an action vector.
;; A stand-in for a payment call: seat B2 always declines.
(defn fake-charge [dispatch {:keys [seats]}]
  (js/setTimeout
    #(if (some #{"B2"} seats)
       (dispatch [:pay/declined "Card declined (try without B2)"])
       (dispatch [:pay/ok]))
    800))

(defonce app
  (sr/make-app {:machine chart/machine
                :effects {:pay/charge fake-charge}
                :on-problem :warn}))

(sr/start! app {:render r/render
                :set-dispatch! r/set-dispatch!
                :el (js/document.getElementById "app")
                :view views/root})
