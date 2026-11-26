(ns statecharts.integrations.replicant-test
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.integrations.replicant :as sr]))

(def m
  (fsm/machine
    {:id :t
     :initial :idle
     :context {:n 0 :items #{} :q ""}
     :states
     {:idle {:on {:item/add  {:actions (fsm/assign (fn [s e] (update s :items conj (:data e))))}
                  :q/set     {:actions (sr/assoc-data :q)}
                  :go        {:target :busy :guard (fn [s _] (seq (:items s)))}}}
      :busy {:entry (sr/fx [:job/run 42] (fn [s _] [:job/log (count (:items s))]))
             :on {:job/ok :idle
                  :job/fail {:target :idle :actions (sr/update-ctx :n inc)}}}
      :p {:type :parallel
          :regions {:a {:initial :a1 :states {:a1 {:on {:a/next :a2}} :a2 {}}}
                    :b {:initial :b1 :states {:b1 {:on {:b/next :b2}} :b2 {}}}}}}}))

(def s0 (fsm/initialize m))
(def s-items (sr/state-after m [[:item/add "x"]]))

(deftest reading-the-chart
  (is (= #{:item/add :q/set :go :job/ok :job/fail :a/next :b/next} (sr/handled-events m)))
  (is (= #{:item/add :q/set :go} (sr/accepted-events m s0)))
  (is (= #{[:idle] [:busy] [:p] [:p :a] [:p :b] [:p :a :a1] [:p :a :a2] [:p :b :b1] [:p :b :b2]}
         (sr/state-paths m)))
  (is (= #{[:idle] [:busy] [:p :a :a1] [:p :a :a2] [:p :b :b1] [:p :b :b2]} (sr/leaf-paths m)))
  (testing "parallel configuration"
    (let [sp (assoc s0 :_state [:p {:a :a1 :b :b1}])]
      (is (= #{:a/next :b/next} (sr/accepted-events m sp))))))

(deftest guards-are-evaluated
  (is (contains? (sr/accepted-events m s0) :go) "accepted: a transition exists")
  (is (false? (sr/enabled? m s0 [:go])) "but the guard says no with an empty basket")
  (is (true? (sr/enabled? m s-items [:go]))))

(deftest step-and-fx
  (testing "accepted + enabled → transition, entry fx queued as data, not run"
    (let [{:keys [state fx rejected]} (sr/step m s-items [:go])]
      (is (nil? rejected))
      (is (= :busy (:_state state)))
      (is (= [[:job/run 42] [:job/log 1]] fx))
      (is (nil? (:app/fx state)) "queue is drained out of the state")))
  (testing "rejections say why"
    (is (= :no-transition (:reason (sr/step m s0 [:job/ok]))))
    (is (= :guard (:reason (sr/step m s0 [:go])))))
  (testing "payload goes to :data; assoc-data / update-ctx"
    (is (= "abc" (:q (sr/state-after m [[:q/set "abc"]]))))
    (is (= 1 (:n (sr/state-after m [[:item/add "x"] [:go] [:job/fail]]))))))

(deftest state-after-throws-on-rejection
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"rejected \[:go\] \(guard\)"
        (sr/state-after m [[:go]]))))

(deftest actions-and-interpolation
  (is (= [[:a 1]] (sr/actions-of [:a 1])))
  (is (= [[:a] [:b 2]] (sr/actions-of [[:a] [:b 2]])))
  (is (nil? (sr/actions-of (fn []))))
  (is (= {:type :q/set :data "hi"} (sr/action->event [:q/set "hi"])))
  (is (= {:type :x :data 1 :args [1 2]} (sr/action->event [:x 1 2])))
  (is (= [:q/set "typed" "Enter"]
         (sr/interpolate [:q/set :event.target/value :event/key]
                         {:target {:value "typed"} :key "Enter"})))
  (is (sr/has-placeholders? [[:q/set :event.target/value]]))
  (is (not (sr/has-placeholders? [:item/add "x"]))))

(deftest view-helpers
  (let [vs0 (sr/view-state m s0)
        vs1 (sr/view-state m s-items)]
    (is (sr/in? vs0 :idle))
    (is (not (sr/in? vs0 [:busy])))
    (is (= {:disabled true :data-event ":go"} (sr/on vs0 [:go])) "guard blocks → disabled")
    (is (= {:on {:click [:go]} :data-event ":go"} (sr/on vs1 [:go])))
    (is (= {:on {:input [:q/set :event.target/value]} :data-event ":q/set"}
           (sr/on vs0 :input [:q/set :event.target/value])) "placeholders → no guard eval")
    (is (= [:wa-button {:variant "brand" :on {:click [:go]} :data-event ":go"} "Go"]
           (sr/button vs1 {:tag :wa-button :variant "brand"} [:go] "Go")))
    (is (= [:idle-view] (sr/view-for vs0 {[:idle] (fn [_] [:idle-view]) [:busy] (fn [_] [:busy-view])})))))

;; --- check: good views pass, each kind of drift is caught -----------------

(def fixtures {:empty s0 :items s-items :busy (sr/state-after m [[:item/add "x"] [:go]])})

(defn good-view [vs]
  (cond
    (sr/in? vs :idle) [:div
                       [:input (sr/on vs :input [:q/set :event.target/value])]
                       (sr/button vs [:item/add "x"] "Add")
                       (sr/button vs [:go] "Go")]
    (sr/in? vs :busy) [:p "working"]))

(defn problems [view & {:as more}]
  (sr/check (merge {:machine m :view view :fixtures fixtures
                    :external-events #{:job/ok :job/fail}}
                   more)))

(defn kinds [ps] (set (map :problem ps)))

(deftest check-good-view
  (let [ps (problems good-view)]
    (is (empty? (filter #(= :error (:severity %)) ps)))
    (is (= #{:state/no-fixture} (kinds ps)) "only the parallel leaves lack fixtures")))

(deftest check-catches-drift
  (testing "hand-wired button whose guard blocks it → dead control (reason :guard)"
    (let [ps (problems (fn [vs] (when (sr/in? vs :idle) [:button {:on {:click [:go]}} "Go"])))
          dead (filter #(= :control/dead (:problem %)) ps)]
      (is (= [:empty] (map :fixture dead)))
      (is (= :guard (:reason (first dead))))))
  (testing "button for an event this state doesn't handle → dead control (:no-transition)"
    (let [ps (problems (fn [vs] [:button {:on {:click [:job/ok]}} "ok"]))]
      (is (some #(and (= :control/dead (:problem %)) (= :no-transition (:reason %))) ps))))
  (testing "typo'd event → unknown event"
    (is (contains? (kinds (problems (fn [vs] [:button {:on {:click [:itme/add "x"]}} "Add"])))
                   :event/unknown)))
  (testing "fn handler → handler/function"
    (is (contains? (kinds (problems (fn [vs] [:button {:on {:click (fn [_] nil)}} "x"])))
                   :handler/function)))
  (testing "view that throws"
    (is (contains? (kinds (problems (fn [vs] (when (sr/in? vs :busy) (throw (ex-info "boom" {}))))))
                   :view/threw)))
  (testing "accepted event without any control → warning"
    (let [ps (problems (fn [vs] [:p "nothing"]))]
      (is (some #(and (= :event/not-offered (:problem %)) (= :go (:event %))) ps))))
  (testing "fixture with a state the chart doesn't have"
    (is (contains? (kinds (problems good-view :fixtures {:bad (assoc s0 :_state :nope)}))
                   :fixture/invalid-state)))
  (testing "errors sort before warnings"
    (let [ps (problems (fn [vs] [:button {:on {:click [:job/ok]}} "ok"]))]
      (is (= :error (:severity (first ps)))))))

;; --- runtime with fake Replicant and fake effects ---------------------------

(deftest runtime
  (let [log (atom [])
        rendered (atom nil)
        dispatch-fn (atom nil)
        app (sr/make-app {:machine m
                          :effects {:job/run (fn [dispatch id] (swap! log conj [:run id]) (dispatch [:job/ok]))
                                    :job/log (fn [_ n] (swap! log conj [:log n]))}
                          :on-problem (fn [kind data] (swap! log conj [kind (:reason data)]))})]
    (sr/start! app {:render (fn [_el h] (reset! rendered h))
                    :set-dispatch! (fn [f] (reset! dispatch-fn f))
                    :el :fake
                    :view good-view})
    (testing "first paint"
      (is (= :div (first @rendered))))
    (testing "a DOM event goes through placeholders into the chart"
      (@dispatch-fn {:replicant/dom-event {:target {:value "hello"}}} [:q/set :event.target/value])
      (is (= "hello" (:q @(:!state app)))))
    (testing "guard-blocked dispatch is reported, state unchanged"
      (sr/dispatch! app [:go])
      (is (= [:rejected :guard] (last @log)))
      (is (= :idle (:_state @(:!state app)))))
    (testing "effects run after the transition and can dispatch back"
      (sr/dispatch! app [[:item/add "x"] [:go]])
      (is (= [[:run 42] [:log 1]] (take-last 2 (remove #(= :rejected (first %)) @log))))
      (is (= :idle (:_state @(:!state app))) ":job/ok came back through dispatch"))
    (testing "unknown effect is reported"
      (let [app2 (sr/make-app {:machine m :effects {}
                               :on-problem (fn [kind _] (swap! log conj [kind]))})]
        (sr/dispatch! app2 [[:item/add "y"] [:go]])
        (is (some #{[:unknown-effect]} @log))))))
