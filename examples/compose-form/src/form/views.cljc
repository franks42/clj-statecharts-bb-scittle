(ns form.views
  (:require [statecharts.compose :as c]
            [statecharts.integrations.replicant :as sr]))

(defn- size-picker [vc]
  (let [{:keys [options value]} (c/state vc [:size :main])]
    [:div.sizes
     (for [o options]
       (c/button vc {:class (if (= o value) "opt on" "opt") :replicant/key o} [:size/select :main o] o))
     (c/button vc {:class "clear-size"} [:size/clear :main] "×")]))

(defn- email-field [vc]
  (let [{:keys [value error]} (c/state vc [:email :main])]
    [:div.field
     [:input (merge {:type "email" :placeholder "email" :value value}
                    (c/on vc :input [:email/input :main :event.target/value]))]
     (c/button vc {:class "clear-email"} [:email/reset :main] "Clear")
     (when (c/in? vc [:email :main] :invalid) [:p.error.field-error error])]))

(defn root [vc]
  (let [app (:app vc)]
    [:main
     [:p.status (str (:_state app)
                     (when (:size app) (str " · size " (:size app)))
                     (when (:email app) (str " · " (:email app))))]
     (when (sr/in? app :loading) [:p "Loading sizes…"])
     (when (sr/in? app :unavailable)
       [:p.error "Sizes unavailable " (c/button vc [:retry] "Retry")])
     (size-picker vc)
     (email-field vc)
     (c/button vc {:class "order"} [:order] (if (sr/in? app :submitting) "Ordering…" "Order"))
     (when-let [err (:error app)] [:p.error.order-error "Order failed: " err])
     (when-let [{:keys [id size email]} (:order app)]
       [:p.done (str "Order " id ": size " size " for " email " ")
        (c/button vc {:class "new-order"} [:new-order] "New order")])]))
