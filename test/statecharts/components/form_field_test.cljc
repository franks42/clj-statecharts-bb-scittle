(ns statecharts.components.form-field-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.components.form-field :as form-field]
            [statecharts.components.request :as request]))

(defn email-error [v] (when-not (re-find #".+@.+\..+" (str v)) "enter an email address"))

(def email (form-field/component {:type :email :validate email-error}))

;; A signup form: the app keeps the last valid email (nil while the field isn't
;; valid). Submit is guarded on it. Field outputs are handled at the root, so the
;; user can keep typing while the submit is in flight without losing messages.

(def app
  (fsm/machine
    {:id :app :initial :editing
     :context {:email nil :error nil}
     :entry (c/spawn [:email :main] {:on {:email/valid :form/email-ok
                                          :email/invalid :form/email-bad
                                          :email/cleared :form/email-bad}})
     :on {:form/email-ok  {:actions (fsm/assign (fn [s e] (assoc s :email (:data e))))}
          :form/email-bad {:actions (fsm/assign (fn [s _] (assoc s :email nil)))}}
     :states
     {:editing    {:on {:submit {:target :submitting
                                 :guard (fn [s _] (some? (:email s)))
                                 :actions [(c/spawn [:request :signup]
                                                    (fn [s _] {:replace? true
                                                               :params {:effect :post/signup :args [(:email s)]}
                                                               :on {:request/ok :form/done
                                                                    :request/failed :form/failed}}))
                                           (c/send [:request :signup] [:request/start])]}}}
      :submitting {:on {:form/done   :done
                        :form/failed {:target :editing
                                      :actions (fsm/assign (fn [s e] (assoc s :error (:data e))))}}}
      :done       {}}}))

(def sys (c/system {:app app :components [email (request/component)]}))

(defn view [vc]
  (let [{:keys [value error]} (c/state vc [:email :main])]
    [:form
     [:input (merge {:value value} (c/on vc :input [:email/input :main :event.target/value]))]
     (when (c/in? vc [:email :main] :invalid) [:p.error error])
     (c/button vc [:email/reset :main] "Clear")
     (c/button vc [:submit] "Sign up")]))

(def ok {:post/signup (fn [_] [:request/succeeded {:id 1}])})
(def down {:post/signup (fn [_] [:request/failed "server down"])})

(def scenarios
  {:typing    {:actions [[:email/input :main "frank"] [:email/input :main "frank@example.org"]
                         [:email/reset :main]]
               :effects ok}
   :signup    {:actions [[:email/input :main "frank@example.org"] [:submit]] :effects ok}
   :rejected  {:actions [[:email/input :main "frank@example.org"] [:submit]] :effects down}
   ;; manual: the user edits the field while the signup request is in flight
   :edit-in-flight {:manual? true :effects ok
                    :actions [[:email/input :main "frank@example.org"] [:submit]
                              [:email/input :main "frank@"] [::c/reply-all]]}
   :fail-after-edit {:manual? true :effects down
                     :actions [[:email/input :main "frank@example.org"] [:submit]
                               [:email/input :main "frank@"] [::c/reply-all]]}})

(defn- field [r] (get-in r [:world :instances [:email :main] :state]))

(deftest validation
  (let [r (c/simulate sys [[:email/input :main "frank"]])]
    (is (= [:invalid "frank" "enter an email address"] ((juxt :_state :value :error) (field r))))
    (is (nil? (get-in r [:world :app :email]))))
  (let [r (c/simulate sys [[:email/input :main "frank"] [:email/input :main "frank@example.org"]])]
    (is (= [:valid "frank@example.org" nil] ((juxt :_state :value :error) (field r))))
    (is (= "frank@example.org" (get-in r [:world :app :email])) "the parent heard :email/valid"))
  (let [r (c/simulate sys (:actions (:typing scenarios)))]
    (is (= [:pristine ""] ((juxt :_state :value) (field r))))
    (is (nil? (get-in r [:world :app :email])) "reset tells the parent too"))
  (testing "reset is not offered on a pristine field; submit is guarded on validity"
    (let [vc (c/view-ctx sys (:world (c/init sys)))]
      (is (not (c/fires? vc [:email/reset :main])))
      (is (not (c/fires? vc [:submit])))
      (is (c/fires? vc [:email/input :main :event.target/value])))))

(deftest submit
  (let [r (c/simulate sys [[:email/input :main "frank@example.org"] [:submit]] {:effects ok :manual? true})]
    (is (= [[:post/signup "frank@example.org"]] (mapv :effect (:fx r))))
    (is (not (c/fires? (c/view-ctx sys (:world r)) [:submit])) "no double submit"))
  (is (= :done (get-in (c/simulate sys (:actions (:signup scenarios)) {:effects ok}) [:world :app :_state])))
  (let [r (c/simulate sys (:actions (:rejected scenarios)) {:effects down})]
    (is (= [:editing "server down"] ((juxt :_state :error) (get-in r [:world :app]))))
    (is (c/fires? (c/view-ctx sys (:world r)) [:submit]) "the email is still valid: submit again")))

(deftest edits-while-submitting
  (testing "typing during the request: the outputs aren't lost, the request is unaffected"
    (let [acts (:actions (:edit-in-flight scenarios))
          mid (c/simulate sys (butlast acts) {:effects ok :manual? true})]
      (is (= :submitting (get-in mid [:world :app :_state])))
      (is (nil? (get-in mid [:world :app :email])) "the parent knows the field is invalid now")
      (is (empty? (filter #(= :error (:severity %)) (:problems mid))))
      (is (= :done (get-in (c/simulate sys acts {:effects ok :manual? true}) [:world :app :_state])))))
  (testing "the request fails after the edit: back to editing, submit disabled until the email is valid"
    (let [r (c/simulate sys (:actions (:fail-after-edit scenarios)) {:effects down :manual? true})]
      (is (= :editing (get-in r [:world :app :_state])))
      (is (not (c/fires? (c/view-ctx sys (:world r)) [:submit]))))))

(deftest factory
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"validate"
        (form-field/component {:type :name})))
  (testing "one type per validation rule; several in one system"
    (let [pw (form-field/component {:type :password :validate #(when (< (count %) 8) "8 characters or more")})
          s (c/system {:app (fsm/machine {:id :app :initial :a :states {:a {}}})
                       :components [(assoc email :lazy? true) (assoc pw :lazy? true)]})
          r (c/simulate s [[:password/input :p "short"] [:email/input :e "a@b.cd"]])]
      (is (= :invalid (get-in r [:world :instances [:password :p] :state :_state])))
      (is (= :valid (get-in r [:world :instances [:email :e] :state :_state]))))))

(deftest check-is-clean
  (let [ps (c/check {:system sys :view view :scenarios scenarios})]
    (is (empty? (filter #(= :error (:severity %)) ps)) (pr-str ps))
    (is (empty? ps) "no warnings either")))
