(ns statecharts.components.form-field
  "Component type factory: one input field with a validation fn.

    pristine ──input v──▶ valid       when (validate v) is nil; emits <type>/valid v
             └─input v──▶ invalid     otherwise, :error = (validate v); emits <type>/invalid error
    valid / invalid ──input v──▶ valid / invalid   (re-validated on every input)
    valid / invalid ──reset──▶ pristine             (emits <type>/cleared)

  `validate` is a pure fn of the value returning nil (ok) or an error message.
  It runs as a guard, so it must be cheap and must not do I/O; checks that need
  a server (\"is this name taken?\") belong in a request the parent starts on
  <type>/valid.

  Each field kind is its own type, so define one per validation rule:

    (def email (form-field/component {:type :email :validate email-error}))
    ;; view:
    [:input (merge {:value (:value (c/state vc [:email :billing]))}
                   (c/on vc :input [:email/input :billing :event.target/value]))]"
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]))

(defn component
  "A field type. Options:
    {:type :field                ; type and event namespace, e.g. :email → :email/input
     :validate (fn [value] nil-or-error-message)}   ; required"
  [{:keys [type validate] :or {type :field}}]
  (when-not (fn? validate)
    (throw (ex-info (str "form-field " type ": :validate must be a fn of the value") {:type type})))
  (let [e #(keyword (name type) %)
        input [{:target :valid
                :guard (fn [_ ev] (nil? (validate (:data ev))))
                :actions [(fsm/assign (fn [s ev] (assoc s :value (:data ev) :error nil)))
                          (c/emit (e "valid") :value)]}
               {:target :invalid
                :actions [(fsm/assign (fn [s ev] (assoc s :value (:data ev) :error (validate (:data ev)))))
                          (c/emit (e "invalid") :error)]}]
        reset {:target :pristine
               :actions [(fsm/assign (fn [s _] (assoc s :value "" :error nil)))
                         (c/emit (e "cleared"))]}]
    (c/component
      {:type type
       :outputs #{(e "valid") (e "invalid") (e "cleared")}
       :machine
       (fsm/machine
         {:id type :initial :pristine
          :context {:value "" :error nil}
          :states {:pristine {:on {(e "input") input}}
                   :valid    {:on {(e "input") input, (e "reset") reset}}
                   :invalid  {:on {(e "input") input, (e "reset") reset}}}})})))
