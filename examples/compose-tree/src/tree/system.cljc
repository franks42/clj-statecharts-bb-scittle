(ns tree.system
  "Composed demo: three requests (two joined), a tree of lazy row disclosures."
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.integrations.replicant :as sr]))

(def request
  (c/component
    {:type :request
     :outputs #{:request/ok :request/failed}
     :machine
     (fsm/machine
       {:id :request :initial :idle
        :context {:effect nil :args [] :data nil :error nil}
        :states
        {:idle    {:on {:request/start :loading}}
         :loading {:entry (sr/fx (fn [s _] (into [(:effect s)] (:args s))))
                   :on {:request/succeeded {:target :ok :actions [(sr/assoc-data :data) (c/emit :request/ok :data)]}
                        :request/failed    {:target :failed :actions [(sr/assoc-data :error) (c/emit :request/failed :error)]}}}
         :ok      {:on {:request/start :loading}}
         :failed  {:on {:request/start :loading}}}})}))

(def row
  (c/component
    {:type :row :lazy? true
     :machine (fsm/machine {:id :row :initial :closed
                            :states {:closed {:on {:row/toggle :open}}
                                     :open   {:on {:row/toggle :closed}}}})}))

(defn- req [k effect]
  [[:request k] {:params {:effect effect} :on {:request/ok (keyword (name k) "loaded")
                                               :request/failed :load/failed}}])

(defn- got [k] (fsm/assign (fn [s e] (assoc s k (:data e)))))

(defn- note-failed []
  ;; outputs carry the instance key: {:type :load/failed :key :prices …}
  (fsm/assign (fn [s e] (update s :failed conj (:key e)))))

(def app
  (fsm/machine
    {:id :app :initial :idle
     :context {:holdings nil :prices nil :activity nil :failed #{}}
     :states
     {:idle    {:on {:load {:target :loading
                            :actions [(c/spawn-each (fn [_ _] [(req :holdings :fetch/holdings)
                                                               (req :prices :fetch/prices)
                                                               (req :activity :fetch/activity)]))
                                      (c/send-each (fn [_ _] (for [k [:holdings :prices :activity]]
                                                               [[:request k] [:request/start]])))]}}}
      ;; join: holdings and prices must both arrive; activity may come any time
      :loading {:on {:holdings/loaded [{:target :ready :guard (fn [s _] (:prices s)) :actions (got :holdings)}
                                       {:actions (got :holdings)}]
                     :prices/loaded   [{:target :ready :guard (fn [s _] (:holdings s)) :actions (got :prices)}
                                       {:actions (got :prices)}]
                     :activity/loaded {:actions (got :activity)}
                     :load/failed {:target :failed :actions (note-failed)}}}
      :ready   {:on {:activity/loaded {:actions (got :activity)}
                     :refresh {:actions (c/send [:request :prices] [:request/start])}
                     :prices/loaded {:actions (got :prices)}
                     :load/failed {}}}
      ;; retry only what failed: a request still in flight can't be restarted
      :failed  {:on {:retry {:target :loading
                             :actions [(c/send-each (fn [s _] (for [k (:failed s)] [[:request k] [:request/start]])))
                                       (fsm/assign #(assoc % :failed #{}))]}
                     :load/failed {:actions (note-failed)}
                     :holdings/loaded {:actions (got :holdings)}
                     :prices/loaded {:actions (got :prices)}
                     :activity/loaded {:actions (got :activity)}}}}}))

(def system (c/system {:app app :components [request row]}))

(defn fake-holdings [n] (vec (for [i (range n)] {:id (str "r" i) :label (str "Asset " i) :amount (* 7 (inc i))})))

(declare racy-scenario)

(defn scenarios [n]
  (let [fx {:fetch/holdings (fn [] [:request/succeeded (fake-holdings n)])
            :fetch/prices   (fn [] [:request/succeeded {:usd 1.5}])
            :fetch/activity (fn [] [:request/succeeded ["tx1"]])}]
    {:happy   {:actions [[:load] [:row/toggle "r0"] [:refresh]] :effects fx}
     :fails   {:actions [[:load]] :effects (assoc fx :fetch/prices (fn [] [:request/failed "timeout"]))}
     :retried {:actions [[:load] [:retry]]
               :effects (let [n (atom 0)]
                          (assoc fx :fetch/prices (fn [] (if (= 1 (swap! n inc)) [:request/failed "x"] [:request/succeeded {:usd 2}]))))}
     :racy    (racy-scenario n)}))

(defn racy-scenario
  "Prices fail while holdings are still loading; the user retries at once."
  [n]
  {:manual? true
   :effects (assoc {:fetch/holdings (fn [] [:request/succeeded (fake-holdings n)])
                    :fetch/activity (fn [] [:request/succeeded ["tx1"]])}
                   :fetch/prices (let [k (atom 0)]
                                                                     (fn [] (if (= 1 (swap! k inc))
                                                                              [:request/failed "timeout"]
                                                                              [:request/succeeded {:usd 2}]))))
   :actions [[:load]
             [::c/reply :fetch/prices]   ; prices fail first
             [:retry]                    ; user retries while holdings is in flight
             [::c/reply-all]]})
