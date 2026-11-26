(ns tree.main
  (:require [replicant.dom :as r]
            [statecharts.compose :as c]
            [tree.system :as ts]
            [tree.views :as tv]))

(def params (js/URLSearchParams. (.-search js/location)))
(def n (or (some-> (.get params "n") js/parseInt) 300))
(def fail-prices? (= "prices" (.get params "fail")))
(def !price-calls (atom 0))

(defn later [ms f] (js/setTimeout f ms))

;; Effect handlers: (fn [reply & args]); reply goes back to the instance that asked.
(def effects
  {:fetch/holdings (fn [reply] (later 300 #(reply [:request/succeeded (ts/fake-holdings n)])))
   :fetch/prices   (fn [reply] (later 200 #(if (and fail-prices? (= 1 (swap! !price-calls inc)))
                                             (reply [:request/failed "timeout"])
                                             (reply [:request/succeeded {:usd (+ 1 (rand-int 5))}]))))
   :fetch/activity (fn [reply] (later 600 #(reply [:request/succeeded ["tx1" "tx2"]])))})

(defonce app (c/make-app {:system ts/system :effects effects :on-problem :warn}))

(c/start! app {:render r/render :set-dispatch! r/set-dispatch!
               :el (js/document.getElementById "app") :view tv/root})
