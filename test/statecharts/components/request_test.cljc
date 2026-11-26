(ns statecharts.components.request-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.components.request :as request]))

;; A host app: load one request, show it, reload it, cancel it while in flight.
(def app
  (fsm/machine
    {:id :app :initial :idle
     :context {:data nil :error nil}
     :states
     {:idle    {:on {:load {:target :loading
                            :actions [(c/spawn [:request :r] {:params {:effect :fetch :args ["x"]}
                                                              :on {:request/ok :got :request/failed :lost}})
                                      (c/send [:request :r] [:request/start])]}}}
      :loading {:on {:got    {:target :ready :actions (fsm/assign (fn [s e] (assoc s :data (:data e))))}
                     :lost   {:target :failed :actions (fsm/assign (fn [s e] (assoc s :error (:data e))))}
                     ;; abandon: kill the instance; its reply is then dropped
                     :cancel {:target :idle :actions (c/kill [:request :r])}}}
      :ready   {:on {:reload {:target :loading :actions (c/send [:request :r] [:request/start])}}}
      :failed  {:on {:reload {:target :loading :actions (c/send [:request :r] [:request/start])}}}}}))

(def sys (c/system {:app app :components [(request/component)]}))

(defn view [vc]
  [:div
   (c/button vc [:load] "Load")
   (c/button vc [:reload] "Reload")
   (c/button vc [:cancel] "Cancel")])

(def ok {:fetch (fn [x] [:request/succeeded (str x "!")])})
(def fails {:fetch (fn [_] [:request/failed "timeout"])})
(defn fails-once []
  (let [n (atom 0)]
    {:fetch (fn [x] (if (= 1 (swap! n inc)) [:request/failed "timeout"] [:request/succeeded x]))}))

(def scenarios
  {:ok          {:actions [[:load] [:reload]] :effects ok}
   :fails       {:actions [[:load]] :effects fails}
   :retried     {:actions [[:load] [:reload]] :effects (fails-once)}
   ;; manual: the user acts while the request is in flight
   :cancelled   {:manual? true :effects ok
                 :actions [[:load] [:cancel] [::c/reply-all]]}
   :cancel-load {:manual? true :effects ok
                 :actions [[:load] [:cancel] [:load] [::c/reply-all]]}})

(defn- kinds [r] (set (map :problem (:problems r))))
(defn- st [r] (get-in r [:world :instances [:request :r] :state]))

(deftest lifecycle
  (let [r (c/simulate sys [[:load]] {:effects ok})]
    (is (= :ok (:_state (st r))))
    (is (= "x!" (:data (st r))))
    (is (= :ready (get-in r [:world :app :_state])))
    (is (= "x!" (get-in r [:world :app :data])) "output :request/ok reached the parent with the data"))
  (let [r (c/simulate sys [[:load]] {:effects fails})]
    (is (= [:failed "timeout"] ((juxt :_state :error) (st r))))
    (is (= :failed (get-in r [:world :app :_state]))))
  (testing "restart from ok and from failed"
    (is (= :ok (:_state (st (c/simulate sys [[:load] [:reload]] {:effects ok})))))
    (is (= :ok (:_state (st (c/simulate sys [[:load] [:reload]] {:effects (fails-once)})))))))

(deftest effect-is-queued-with-args
  (let [r (c/simulate sys [[:load]] {:effects ok :manual? true})]
    (is (= [[:fetch "x"]] (mapv :effect (:fx r))))
    (is (= [:request :r] (:origin (first (:fx r)))))
    (is (= :loading (:_state (st r))))))

(deftest in-flight
  (let [r (c/simulate sys [[:load]] {:effects ok :manual? true})
        vc (c/view-ctx sys (:world r))]
    (testing "start is not accepted while loading: its control disables itself"
      (is (not (c/fires? vc [:request/start :r])))
      (is (not (c/fires? vc [:reload])))
      (is (c/fires? vc [:cancel]))))
  (testing "cancel kills the instance; the reply then has nowhere to go and is dropped"
    (let [r (c/simulate sys (:actions (:cancelled scenarios)) {:effects ok :manual? true})]
      (is (= :idle (get-in r [:world :app :_state])))
      (is (nil? (st r)))
      (is (contains? (kinds r) :late-reply))
      (is (nil? (get-in r [:world :app :data])))))
  (testing "cancel then load again: the old reply is stale, the new one lands"
    (let [r (c/simulate sys (:actions (:cancel-load scenarios)) {:effects ok :manual? true})]
      (is (contains? (kinds r) :stale-reply))
      (is (= :ready (get-in r [:world :app :_state])))
      (is (= "x!" (get-in r [:world :app :data])))))
  (testing "a parent that sends start while loading is reported, not silently ignored"
    (let [bad (c/system {:app (fsm/machine {:id :app :initial :a
                                            :states {:a {:on {:go {:actions [(c/spawn [:request :r] {:params {:effect :fetch}})
                                                                             (c/send [:request :r] [:request/start])
                                                                             (c/send [:request :r] [:request/start])]}}}}})
                         :components [(request/component)]})]
      (is (= #{:send-rejected} (kinds (c/simulate bad [[:go]] {:manual? true})))))))

(deftest custom-type
  (let [t (request/component {:type :quote})]
    (is (= :quote (:type t)))
    (is (= #{:quote/ok :quote/failed} (:outputs t)))
    (is (= #{:quote/start :quote/succeeded :quote/failed} (:accepts t)))))

(deftest check-is-clean
  (let [ps (c/check {:system sys :view view :scenarios scenarios})]
    (is (empty? (filter #(= :error (:severity %)) ps)) (pr-str ps))
    (is (empty? ps) "no warnings either: every state reached, every output emitted")))
