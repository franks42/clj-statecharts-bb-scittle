(ns checkout.views
  "Views are pure fns of view-state. Every control is built with sr/on or
  sr/button, so a control is enabled exactly when its action would fire.
  For Web Awesome: (sr/button vs {:tag :wa-button :variant \"brand\"} [:pay/submit] \"Pay\")."
  (:require [clojure.string :as str]
            [statecharts.integrations.replicant :as sr]))

(def seats ["A17" "A18" "A19" "B1" "B2"])

(defn- seat-list [vs]
  (str/join ", " (sort (:basket vs))))

(defn browsing [vs]
  [:section
   [:h2 "Choose seats"]
   [:div.seats
    (for [s seats]
      [:button (merge {:replicant/key s
                       :class (when (contains? (:basket vs) s) "chosen")}
                      (sr/on vs [:seat/toggle s]))
       s])]
   [:p (count (:basket vs)) " seat(s) chosen"]
   (sr/button vs [:checkout/start] "Checkout")])

(defn editing [vs]
  [:section
   [:h2 "Checkout"]
   [:p "Seats: " (seat-list vs)]
   [:label "Email "
    [:input (merge {:type "email" :value (:email vs)}
                   (sr/on vs :input [:form/email :event.target/value]))]]
   [:div.actions
    (sr/button vs [:pay/submit] "Pay")
    (sr/button vs [:checkout/cancel] "Cancel")]])

(defn submitting [_vs]
  [:section [:p "Charging your card…"]])

(defn declined [vs]
  [:section
   [:p.error (:error vs)]
   [:div.actions
    (sr/button vs [:pay/retry] "Try again")
    (sr/button vs [:checkout/cancel] "Cancel")]])

(defn done [vs]
  [:section
   [:p "Booked: " (seat-list vs) " — confirmation to " (:email vs)]
   (sr/button vs [:order/new] "New order")])

(defn root [vs]
  [:main
   (sr/view-for vs {[:browsing]              browsing
                    [:checkout :editing]    editing
                    [:checkout :submitting] submitting
                    [:checkout :declined]   declined
                    [:done]                 done})])
