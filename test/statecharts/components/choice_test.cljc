(ns statecharts.components.choice-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.components.choice :as choice]
            [statecharts.components.request :as request]))

;; A size picker whose options come from a request, and can be refreshed.
;; The app hears every change through :size/changed, in any state (root :on).

(def app
  (fsm/machine
    {:id :app :initial :idle
     :context {:size nil}
     :on {:size/changed {:actions (fsm/assign (fn [s e] (assoc s :size (:data e))))}
          :opts/failed  [:> :failed]}
     :states
     {:idle       {:on {:load {:target :loading
                               :actions [(c/spawn [:choice :size] {:on {:choice/changed :size/changed}})
                                         (c/spawn [:request :opts] {:params {:effect :fetch/sizes}
                                                                    :on {:request/ok :opts/loaded
                                                                         :request/failed :opts/failed}})
                                         (c/send [:request :opts] [:request/start])]}}}
      :loading    {:on {:opts/loaded {:target :ready
                                      :actions (c/send [:choice :size] (fn [_ e] [:choice/options (:data e)]))}}}
      :ready      {:on {:refresh {:target :refreshing :actions (c/send [:request :opts] [:request/start])}}}
      ;; selecting stays possible while the options reload
      :refreshing {:on {:opts/loaded {:target :ready
                                      :actions (c/send [:choice :size] (fn [_ e] [:choice/options (:data e)]))}}}
      :failed     {:on {:retry {:target :loading :actions (c/send [:request :opts] [:request/start])}}}}}))

(def sys (c/system {:app app :components [(choice/component) (request/component)]}))

(defn view [vc]
  (let [{:keys [options value]} (c/state vc [:choice :size])]
    [:div
     (c/button vc [:load] "Load")
     (c/button vc [:refresh] "Refresh")
     (c/button vc [:retry] "Retry")
     (into [:div] (for [o ["S" "M" "L" "XL"]]
                    (c/button vc {:class (when (= o value) "on")} [:choice/select :size o] o)))
     (c/button vc [:choice/clear :size] "Clear")
     [:span (count options) " options"]]))

(defn sizes [& batches]
  (let [n (atom 0)]
    {:fetch/sizes (fn [] (let [b (nth batches (min (dec (count batches)) @n))]
                           (swap! n inc)
                           (if (string? b) [:request/failed b] [:request/succeeded b])))}))

(def scenarios
  {:pick        {:actions [[:load] [:choice/select :size "M"] [:choice/select :size "L"] [:choice/clear :size]]
                 :effects (sizes ["S" "M" "L"])}
   :still-there {:actions [[:load] [:choice/select :size "M"] [:refresh]]
                 :effects (sizes ["S" "M" "L"] ["M" "XL"])}
   :failed      {:actions [[:load] [:retry]] :effects (sizes "timeout" ["S"])}
   ;; manual: the user picks L while the refreshed options (without L) are in flight
   :pick-during-refresh {:manual? true :effects (sizes ["S" "M" "L"] ["S" "M"])
                         :actions [[:load] [::c/reply :fetch/sizes]
                                   [:refresh] [:choice/select :size "L"]
                                   [::c/reply :fetch/sizes]]}})

(defn- ch [r] (get-in r [:world :instances [:choice :size] :state]))

(deftest selecting
  (let [r (c/simulate sys [[:load] [:choice/select :size "M"]] {:effects (sizes ["S" "M" "L"])})]
    (is (= [:set "M"] ((juxt :_state :value) (ch r))))
    (is (= "M" (get-in r [:world :app :size])) "the parent heard :choice/changed"))
  (let [r (c/simulate sys (:actions (:pick scenarios)) {:effects (sizes ["S" "M" "L"])})]
    (is (= [:unset nil] ((juxt :_state :value) (ch r))))
    (is (nil? (get-in r [:world :app :size]))))
  (testing "controls for values not offered, or already chosen, disable themselves"
    (let [vc (c/view-ctx sys (:world (c/simulate sys [[:load] [:choice/select :size "M"]] {:effects (sizes ["S" "M" "L"])})))]
      (is (not (c/fires? vc [:choice/select :size "XL"])))
      (is (not (c/fires? vc [:choice/select :size "M"])))
      (is (c/fires? vc [:choice/select :size "L"])))
    (let [vc (c/view-ctx sys (:world (c/simulate sys [[:load]] {:effects (sizes ["S"])})))]
      (is (not (c/fires? vc [:choice/clear :size])) "nothing to clear"))))

(deftest new-options
  (let [r (c/simulate sys (:actions (:still-there scenarios)) {:effects (sizes ["S" "M" "L"] ["M" "XL"])})]
    (is (= [:set "M" ["M" "XL"]] ((juxt :_state :value :options) (ch r))) "value still offered: kept")))

(deftest pick-while-options-reload
  (let [acts (:actions (:pick-during-refresh scenarios))
        fx (sizes ["S" "M" "L"] ["S" "M"])
        mid (c/simulate sys (butlast acts) {:effects fx :manual? true})]
    (is (= :refreshing (get-in mid [:world :app :_state])))
    (is (= "L" (get-in mid [:world :app :size])) "selecting works while the options reload")
    (let [r (c/simulate sys acts {:effects (sizes ["S" "M" "L"] ["S" "M"]) :manual? true})]
      (is (= [:unset nil ["S" "M"]] ((juxt :_state :value :options) (ch r)))
          "L is gone from the new options: the choice clears itself")
      (is (nil? (get-in r [:world :app :size])) "and tells the parent")
      (is (empty? (filter #(= :error (:severity %)) (:problems r)))))))

(deftest custom-type
  (is (= #{:size/select :size/clear :size/options} (:accepts (choice/component {:type :size})))))

(deftest check-is-clean
  (let [ps (c/check {:system sys :view view :scenarios scenarios})]
    (is (empty? (filter #(= :error (:severity %)) ps)) (pr-str ps))
    (is (empty? ps) "no warnings either")))
