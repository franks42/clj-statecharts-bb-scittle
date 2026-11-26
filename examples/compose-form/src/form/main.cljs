(ns form.main
  (:require [replicant.dom :as r]
            [statecharts.compose :as c]
            [form.system :as fs]
            [form.views :as fv]))

;; ?fail=sizes fails the first sizes load; ?fail=order fails the first order.
(def fail (.get (js/URLSearchParams. (.-search js/location)) "fail"))
(def !calls (atom {}))
(defn- fail-now? [k] (and (= fail k) (= 1 (get (swap! !calls update k (fnil inc 0)) k))))

(defn later [ms f] (js/setTimeout f ms))

(def effects
  {:fetch/sizes (fn [reply] (later 200 #(if (fail-now? "sizes")
                                          (reply [:request/failed "timeout"])
                                          (reply [:request/succeeded fs/sizes]))))
   :post/order  (fn [reply order] (later 300 #(if (fail-now? "order")
                                                (reply [:request/failed "server down"])
                                                (reply [:request/succeeded (assoc order :id 42)]))))})

(defonce app (c/make-app {:system fs/system :effects effects :on-problem :warn}))

(c/start! app {:render r/render :set-dispatch! r/set-dispatch!
               :el (js/document.getElementById "app") :view fv/root})
