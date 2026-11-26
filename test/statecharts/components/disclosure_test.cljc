(ns statecharts.components.disclosure-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.components.disclosure :as disclosure]
            [statecharts.components.request :as request]))

;; --- the type on its own: lazy, as in a tree of rows ---------------------------

(def lazy-sys (c/system {:app (fsm/machine {:id :app :initial :a :states {:a {}}})
                         :components [(disclosure/component {:type :row})]}))

(defn- row-state [r k] (get-in r [:world :instances [:row k] :state :_state]))

(deftest lazy-rows
  (let [w (:world (c/init lazy-sys))]
    (is (empty? (:instances w)) "no instance until first used")
    (is (c/in? (c/view-ctx lazy-sys w) [:row "r9"] :closed) "an unused row reads as closed"))
  (let [r (c/simulate lazy-sys [[:row/toggle "r1"]])]
    (is (= :open (row-state r "r1")))
    (is (= :app (get-in r [:world :instances [:row "r1"] :parent])))
    (is (= #{:row/opened} (:emitted r)) "emitted, but unmapped: a lazy row's outputs go nowhere"))
  (is (= :closed (row-state (c/simulate lazy-sys [[:row/toggle "r1"] [:row/toggle "r1"]]) "r1")))
  (testing "open / close are only accepted where they change something"
    (let [vc (c/view-ctx lazy-sys (:world (c/simulate lazy-sys [[:row/open "r1"]])))]
      (is (not (c/fires? vc [:row/open "r1"])))
      (is (c/fires? vc [:row/close "r1"]))
      (is (c/fires? vc [:row/open "r2"]) "a different, unused row"))))

;; --- composed: load details on first open ---------------------------------------
;; The parent keeps the keys it has requested, so re-opening while the details are
;; still loading doesn't start the request again (that would be :send-rejected).

(def app
  (fsm/machine
    {:id :app :initial :ready
     :context {:requested #{} :details {}}
     :entry (c/spawn-each (fn [_ _] (for [k [:d1 :d2]]
                                      [[:disclosure k] {:on {:disclosure/opened :details/wanted
                                                             :disclosure/closed :details/hidden}}])))
     :states
     {:ready
      {:on {:details/wanted [{:guard (fn [s e] (not (contains? (:requested s) (:key e))))
                              :actions [(fsm/assign (fn [s e] (update s :requested conj (:key e))))
                                        (c/spawn (fn [_ e] [:request (:key e)])
                                                 (fn [_ e] {:replace? true
                                                            :params {:effect :fetch/details :args [(:key e)]}
                                                            :on {:request/ok :details/loaded
                                                                 :request/failed :details/failed}}))
                                        (c/send (fn [_ e] [:request (:key e)]) [:request/start])]}
                             {}]
            :details/hidden {}
            :details/loaded {:actions (fsm/assign (fn [s e] (assoc-in s [:details (:key e)] (:data e))))}
            ;; forget the failed key, so the next open tries again
            :details/failed {:actions (fsm/assign (fn [s e] (update s :requested disj (:key e))))}}}}}))

(def sys (c/system {:app app :components [(disclosure/component {:lazy? false}) (request/component)]}))

(defn view [vc]
  (into [:div]
        (for [k [:d1 :d2]]
          [:section
           (c/button vc [:disclosure/toggle k] (if (c/in? vc [:disclosure k] :open) "▾" "▸"))
           (c/button vc [:disclosure/open k] "open")
           (c/button vc [:disclosure/close k] "close")
           (when (c/in? vc [:request k] :loading) [:span "loading…"])])))

(def ok {:fetch/details (fn [k] [:request/succeeded (str "details of " (name k))])})
(defn fails-once []
  (let [n (atom 0)]
    {:fetch/details (fn [k] (if (= 1 (swap! n inc)) [:request/failed "timeout"] [:request/succeeded (name k)]))}))

(def scenarios
  {:open-close  {:actions [[:disclosure/open :d1] [:disclosure/close :d1] [:disclosure/toggle :d1]
                           [:disclosure/toggle :d2]]
                 :effects ok}
   :fail-reopen {:actions [[:disclosure/open :d1] [:disclosure/close :d1] [:disclosure/open :d1]]
                 :effects (fails-once)}
   ;; manual: close and reopen while the details are still loading
   :reopen-in-flight {:manual? true :effects ok
                      :actions [[:disclosure/open :d1] [:disclosure/close :d1] [:disclosure/open :d1]
                                [::c/reply-all]]}})

(defn- fx-count [r] (count (filter #(= :fetch/details (first (:effect %))) (:fx r))))

(deftest details-on-first-open
  (let [r (c/simulate sys (:actions (:open-close scenarios)) {:effects ok})]
    (is (= {:d1 "details of d1" :d2 "details of d2"} (get-in r [:world :app :details])))
    (is (= :open (get-in r [:world :instances [:disclosure :d1] :state :_state])))))

(deftest reopen-while-loading-requests-once
  (let [acts (:actions (:reopen-in-flight scenarios))
        before (c/simulate sys (butlast acts) {:effects ok :manual? true})]
    (is (= 1 (fx-count before)) "one fetch pending, not two")
    (is (empty? (filter #(= :error (:severity %)) (:problems before))))
    (let [r (c/simulate sys acts {:effects ok :manual? true})]
      (is (= "details of d1" (get-in r [:world :app :details :d1])))
      (is (empty? (:fx r))))))

(deftest failed-details-are-retried-on-next-open
  (let [r (c/simulate sys (:actions (:fail-reopen scenarios)) {:effects (fails-once)})]
    (is (= "d1" (get-in r [:world :app :details :d1])))
    (is (= :ok (get-in r [:world :instances [:request :d1] :state :_state])))))

(deftest check-is-clean
  (let [ps (c/check {:system sys :view view :scenarios scenarios})]
    (is (empty? (filter #(= :error (:severity %)) ps)) (pr-str ps))
    (is (empty? ps) "no warnings either")))
