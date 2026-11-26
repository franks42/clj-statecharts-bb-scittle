(ns form.system
  "Composed demo: an order form built from library types only.
  A size `choice` whose options load through a `request`, an email `form-field`,
  and a second `request` that places the order."
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.components.choice :as choice]
            [statecharts.components.form-field :as form-field]
            [statecharts.components.request :as request]))

(defn email-error [v]
  (when-not (re-find #"^[^@\s]+@[^@\s]+\.[^@\s]+$" (str v)) "enter an email address"))

;; Field and choice outputs are handled at the root, in every state: the user may
;; keep editing while sizes load or an order is in flight, and nothing is lost.

(def app
  (fsm/machine
    {:id :app :initial :loading
     :context {:size nil :email nil :error nil :order nil}
     :entry [(c/spawn [:size :main] {:on {:size/changed :form/size}})
             (c/spawn [:email :main] {:on {:email/valid :form/email-ok
                                           :email/invalid :form/email-bad
                                           :email/cleared :form/email-bad}})
             (c/spawn [:request :sizes] {:params {:effect :fetch/sizes}
                                         :on {:request/ok :sizes/loaded
                                              :request/failed :sizes/failed}})
             (c/send [:request :sizes] [:request/start])]
     :on {:form/size      {:actions (fsm/assign (fn [s e] (assoc s :size (:data e))))}
          :form/email-ok  {:actions (fsm/assign (fn [s e] (assoc s :email (:data e))))}
          :form/email-bad {:actions (fsm/assign (fn [s _] (assoc s :email nil)))}}
     :states
     {:loading    {:on {:sizes/loaded {:target :editing
                                       :actions (c/send [:size :main] (fn [_ e] [:size/options (:data e)]))}
                        :sizes/failed :unavailable}}
      :unavailable {:on {:retry {:target :loading :actions (c/send [:request :sizes] [:request/start])}}}
      :editing    {:on {:order {:target :submitting
                                :guard (fn [s _] (and (:size s) (:email s)))
                                :actions [(fsm/assign (fn [s _] (assoc s :error nil)))
                                          (c/spawn [:request :order]
                                                   (fn [s _] {:replace? true
                                                              :params {:effect :post/order
                                                                       :args [{:size (:size s) :email (:email s)}]}
                                                              :on {:request/ok :order/placed
                                                                   :request/failed :order/failed}}))
                                          (c/send [:request :order] [:request/start])]}}}
      :submitting {:on {:order/placed {:target :done
                                       :actions (fsm/assign (fn [s e] (assoc s :order (:data e))))}
                        :order/failed {:target :editing
                                       :actions (fsm/assign (fn [s e] (assoc s :error (:data e))))}}}
      :done       {}}}))

(def system
  (c/system {:app app
             :components [(choice/component {:type :size})
                          (form-field/component {:type :email :validate email-error})
                          (request/component)]}))

(def sizes ["S" "M" "L" "XL"])

(defn scenarios []
  (let [fx {:fetch/sizes (fn [] [:request/succeeded sizes])
            :post/order  (fn [o] [:request/succeeded (assoc o :id 1)])}
        once (fn [ok-result fail]       ; fails the first call, then succeeds
               (let [n (atom 0)] (fn [& args] (if (= 1 (swap! n inc)) [:request/failed fail] (apply ok-result args)))))]
    {:order    {:actions [[:size/select :main "M"] [:email/input :main "me"]
                          [:email/input :main "me@example.org"] [:size/clear :main]
                          [:size/select :main "L"] [:order]]
                :effects fx}
     :reset    {:actions [[:email/input :main "me@example.org"] [:email/reset :main]] :effects fx}
     :sizes-down {:actions [[:retry]]
                  :effects (assoc fx :fetch/sizes (once (:fetch/sizes fx) "timeout"))}
     :order-failed {:actions [[:size/select :main "S"] [:email/input :main "me@example.org"] [:order] [:order]]
                    :effects (assoc fx :post/order (once (:post/order fx) "server down"))}
     ;; manual: the user edits while the order is in flight. The order was sent
     ;; with the old values; a failure returns to editing with the new ones.
     :edit-in-flight {:manual? true
                      :effects (assoc fx :post/order (once (:post/order fx) "server down"))
                      :actions [[::c/reply :fetch/sizes]
                                [:size/select :main "M"] [:email/input :main "me@example.org"] [:order]
                                [:size/select :main "XL"] [:email/input :main "me@"]
                                [::c/reply :post/order]]}
     ;; manual: the user types before the sizes have arrived
     :type-while-loading {:manual? true :effects fx
                          :actions [[:email/input :main "me@example.org"] [::c/reply-all]]}}))
