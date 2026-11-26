(ns statecharts.components.request
  "Component type: one asynchronous request.

    idle ──start──▶ loading ──succeeded──▶ ok      (emits <type>/ok with the data)
                       │                    │
                       └──failed──▶ failed  └──start──▶ loading
                                      └──start──▶ loading

  The parent spawns it with the effect to run and its arguments:

    (c/spawn [:request :prices] {:params {:effect :fetch/prices :args [\"EUR\"]}
                                 :on {:request/ok :prices/loaded
                                      :request/failed :load/failed}})
    (c/send [:request :prices] [:request/start])

  On entering :loading it queues [effect & args]. The handler replies with
  [:request/succeeded data] or [:request/failed error].

  In flight, :start is not accepted (a control for it is disabled; a parent's
  send is reported as :send-rejected). To abandon a request, kill or replace the
  instance: a reply to it is then dropped (:late-reply / :stale-reply)."
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.integrations.replicant :as sr]))

(defn component
  "The request type. Options: {:type :request} — the type, which is also the
  namespace of its events (:request/start …) and outputs (:request/ok …)."
  ([] (component {}))
  ([{:keys [type] :or {type :request}}]
   (let [e #(keyword (name type) %)]
     (c/component
       {:type type
        :outputs #{(e "ok") (e "failed")}
        :machine
        (fsm/machine
          {:id type :initial :idle
           :context {:effect nil :args [] :data nil :error nil}
           :states
           {:idle    {:on {(e "start") :loading}}
            :loading {:entry (sr/fx (fn [s _] (into [(:effect s)] (:args s))))
                      :on {(e "succeeded") {:target :ok
                                            :actions [(sr/assoc-data :data) (c/emit (e "ok") :data)]}
                           (e "failed")    {:target :failed
                                            :actions [(sr/assoc-data :error) (c/emit (e "failed") :error)]}}}
            :ok      {:on {(e "start") :loading}}
            :failed  {:on {(e "start") :loading}}}})}))))
