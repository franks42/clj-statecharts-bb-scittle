(ns statecharts.components.choice
  "Component type: pick at most one value from a list of options.

    unset ──select v──▶ set           (v must be in :options; emits <type>/changed v)
    set ──select w──▶ set             (w ≠ current value; emits <type>/changed w)
    set ──clear──▶ unset              (emits <type>/changed nil)
    any ──options opts──▶             replace the options; if the current value
                                      is no longer offered: → unset, emits changed nil

  Options are plain values (ids); labels belong to the view. Spawn with
  {:params {:options [...]}}, or send [:choice/options opts] later, e.g. when a
  request that loads them succeeds. A control for a value that isn't offered,
  or for the value already chosen, disables itself (the guard says no)."
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]))

(defn- offered? [s v] (boolean (some #(= v %) (:options s))))

(defn component
  "The choice type. Options: {:type :choice} — type and event namespace."
  ([] (component {}))
  ([{:keys [type] :or {type :choice}}]
   (let [e #(keyword (name type) %)
         changed (c/emit (e "changed") :value)
         pick {:target :set
               :guard (fn [s ev] (and (offered? s (:data ev)) (not= (:value s) (:data ev))))
               :actions [(fsm/assign (fn [s ev] (assoc s :value (:data ev)))) changed]}
         set-options (fsm/assign (fn [s ev] (assoc s :options (vec (:data ev)))))]
     (c/component
       {:type type
        :outputs #{(e "changed")}
        :machine
        (fsm/machine
          {:id type :initial :unset
           :context {:options [] :value nil}
           :states
           {:unset {:on {(e "select")  pick
                         (e "options") {:actions set-options}}}
            :set   {:on {(e "select")  pick
                         (e "clear")   {:target :unset
                                        :actions [(fsm/assign (fn [s _] (assoc s :value nil))) changed]}
                         (e "options") [{:guard (fn [s ev] (offered? {:options (:data ev)} (:value s)))
                                         :actions set-options}
                                        {:target :unset
                                         :actions [set-options
                                                   (fsm/assign (fn [s _] (assoc s :value nil)))
                                                   changed]}]}}}})}))))
