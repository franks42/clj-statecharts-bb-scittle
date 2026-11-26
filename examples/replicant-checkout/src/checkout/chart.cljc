(ns checkout.chart
  "The chart is the single source of truth: which events exist, and when each is
  allowed. The app state is the chart's state map; views only read it."
  (:require [statecharts.core :as fsm]
            [statecharts.integrations.replicant :as sr]))

(defn- toggle [s x]
  (if (contains? s x) (disj s x) (conj s x)))

(def machine
  (fsm/machine
    {:id :checkout
     :initial :browsing
     :context {:basket #{} :email "" :error nil}
     :states
     {:browsing
      {:on {:seat/toggle    {:actions (fsm/assign (fn [s e] (update s :basket toggle (:data e))))}
            :checkout/start {:target :checkout
                             :guard (fn [s _] (seq (:basket s)))}}}

      :checkout
      {:initial :editing
       :states
       {:editing    {:on {:form/email      {:actions (sr/assoc-data :email)}
                          :pay/submit      {:target :submitting
                                            :guard (fn [s _] (re-find #".+@.+\..+" (:email s)))}
                          :checkout/cancel [:> :browsing]}}
        ;; No cancel while the charge is in flight: the chart simply has no
        ;; transition for it here, so a Cancel button can't be enabled.
        :submitting {:entry (sr/fx (fn [s _] [:pay/charge {:seats (sort (:basket s))
                                                           :email (:email s)}]))
                     :on {:pay/ok       [:> :done]
                          :pay/declined {:target :declined
                                         :actions (sr/assoc-data :error)}}}
        :declined   {:on {:pay/retry       :editing
                          :checkout/cancel [:> :browsing]}}}}

      :done
      {:on {:order/new {:target :browsing
                        :actions (fsm/assign (fn [s _] (assoc s :basket #{} :email "" :error nil)))}}}}}))

(def external-events
  "Events that effects dispatch, not controls."
  #{:pay/ok :pay/declined})

;; Fixtures are replayed through the chart, so each is a reachable state.
;; state-after throws if a step is rejected (no transition, or a guard says no).
(def ^:private chosen  [[:seat/toggle "A18"] [:seat/toggle "A19"]])
(def ^:private editing (conj chosen [:checkout/start]))
(def ^:private filled  (conj editing [:form/email "frank@example.org"]))

(def fixtures
  {:empty      (fsm/initialize machine)
   :chosen     (sr/state-after machine chosen)
   :editing    (sr/state-after machine editing)
   :filled     (sr/state-after machine filled)
   :submitting (sr/state-after machine (conj filled [:pay/submit]))
   :declined   (sr/state-after machine (conj filled [:pay/submit] [:pay/declined "Card declined"]))
   :done       (sr/state-after machine (conj filled [:pay/submit] [:pay/ok]))})
