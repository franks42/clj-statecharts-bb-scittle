(ns tree.views
  (:require [statecharts.compose :as c]
            [statecharts.integrations.replicant :as sr]))

(defn- tree-row [vc {:keys [id label amount]} usd]
  (let [open? (c/in? vc [:row id] :open)]
    [:div.row {:replicant/key id}
     (c/button vc {:class "caret"} [:row/toggle id] (if open? "▾" "▸"))
     [:span label] [:span.amt amount] [:span.usd (when usd (str "$" (* usd amount)))]
     (when open? [:div.detail "details for " label])]))

(defn root [vc]
  (let [app (:app vc)]
    [:main
     [:div.bar
      (c/button vc [:load] "Load")
      (c/button vc [:refresh] "Refresh prices")
      (c/button vc [:retry] "Retry")
      [:span.status (str (:_state app)
                         (when (c/in? vc [:request :prices] :loading) " · prices loading")
                         (when (seq (:activity app)) (str " · " (count (:activity app)) " tx")))]]
     (when (sr/in? app :failed) [:p.error "Loading failed"])
     (when (sr/in? app :ready)
       [:div.tree (for [h (:holdings app)] (tree-row vc h (get-in app [:prices :usd])))])]))
