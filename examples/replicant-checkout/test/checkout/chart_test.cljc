(ns checkout.chart-test
  (:require [clojure.test :refer [deftest is]]
            [statecharts.integrations.replicant :as sr]
            [checkout.chart :as chart]))

(deftest pay-submits-and-charges
  (let [{:keys [state fx]} (sr/run-script chart/machine (:filled chart/fixtures) [[:pay/submit]])]
    (is (= [:checkout :submitting] (:_state state)))
    (is (= [[:pay/charge {:seats ["A18" "A19"] :email "frank@example.org"}]] fx))))

(deftest no-cancel-while-charging
  (is (= :no-transition
         (:reason (sr/step chart/machine (:submitting chart/fixtures) [:checkout/cancel])))))

(deftest pay-needs-a-valid-email
  (is (= :guard (:reason (sr/step chart/machine (:editing chart/fixtures) [:pay/submit])))))
