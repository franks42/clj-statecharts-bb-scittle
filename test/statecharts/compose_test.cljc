(ns statecharts.compose-test
  "Ordering semantics first: these tests define what a composed system does."
  (:require [clojure.test :refer [deftest is testing]]
            [statecharts.core :as fsm]
            [statecharts.compose :as c]
            [statecharts.integrations.replicant :as sr]))

;; --- test component types -----------------------------------------------------

(def req
  "A request: idle → loading → ok/failed; restartable. Emits :req/ok / :req/failed."
  (c/component
    {:type :req
     :outputs #{:req/ok :req/failed}
     :machine
     (fsm/machine
       {:id :req :initial :idle
        :context {:effect nil :args [] :data nil :error nil}
        :states
        {:idle    {:on {:req/start :loading}}
         :loading {:entry (sr/fx (fn [s _] (into [(:effect s)] (:args s))))
                   :on {:req/succeeded {:target :ok
                                        :actions [(sr/assoc-data :data) (c/emit :req/ok :data)]}
                        :req/failed    {:target :failed
                                        :actions [(sr/assoc-data :error) (c/emit :req/failed :error)]}}}
         :ok      {:on {:req/start :loading}}
         :failed  {:on {:req/start :loading}}}})}))

(def disc
  "Lazy disclosure: exists implicitly, closed, until first used."
  (c/component
    {:type :disc :lazy? true
     :machine (fsm/machine {:id :disc :initial :closed
                            :states {:closed {:on {:disc/toggle :open}}
                                     :open   {:on {:disc/toggle :closed}}}})}))

(def echo
  "Emits :echo/out as soon as it receives :echo/ping. For ordering tests."
  (c/component
    {:type :echo :outputs #{:echo/out}
     :machine (fsm/machine {:id :echo :initial :on
                            :states {:on {:on {:echo/ping {:actions (c/emit :echo/out (fn [_ e] (:data e)))}}}}})}))

(def panel
  "A component that owns children: spawns two lazy-free echo children on :panel/open."
  (c/component
    {:type :panel
     :machine (fsm/machine {:id :panel :initial :closed
                            :states {:closed {:on {:panel/open {:target :open
                                                                :actions (c/spawn-each
                                                                           (fn [_ _] [[[:echo :p1] {}] [[:echo :p2] {}]]))}}}
                                     :open {}}})}))

;; --- app chart ---------------------------------------------------------------

(defn- note [k] (fsm/assign (fn [s e] (update s :log conj [k (:key e) (:data e)]))))

(def app
  (fsm/machine
    {:id :app :initial :idle
     :context {:log [] :a nil :b nil}
     :states
     {:idle
      {:on {:go {:target :loading
                 :actions [(c/spawn [:req :a] {:params {:effect :fetch :args ["a"]}
                                               :on {:req/ok :a/done :req/failed :a/failed}})
                           (c/spawn [:req :b] {:params {:effect :fetch :args ["b"]}
                                               :on {:req/ok :b/done}})
                           (c/send [:req :a] [:req/start])
                           (c/send [:req :b] [:req/start])]}
            :fan {:actions [(c/spawn-each (fn [_ _] [[[:echo :x] {:on {:echo/out :x/out}}]
                                                    [[:echo :y] {:on {:echo/out :y/out}}]]))
                            (c/send [:echo :x] [:echo/ping 1])
                            (c/send [:echo :y] [:echo/ping 2])]}
            :x/out {:actions (note :x)}
            :y/out {:actions (note :y)}
            :panel {:actions (c/spawn [:panel :main] {})}
            :close-panel {:actions (c/kill [:panel :main])}
            :bad-map {:actions (c/spawn [:req :z] {:on {:req/nope :z/x}})}
            :orphan {:actions (c/send [:req :missing] [:req/start])}}}
      ;; join: the second of the two arrivals moves on
      :loading
      {:on {:a/done [{:target :ready :guard (fn [s _] (:b s))}
                     {:actions (fsm/assign (fn [s e] (assoc s :a (:data e))))}]
            :b/done [{:target :ready :guard (fn [s _] (:a s))}
                     {:actions (fsm/assign (fn [s e] (assoc s :b (:data e))))}]
            :a/failed :error}}
      :ready {:on {:reload {:target :loading
                            :actions [(fsm/assign #(assoc %1 :a nil :b nil))
                                      (c/kill [:req :a])
                                      (c/spawn [:req :a] {:params {:effect :fetch :args ["a2"]}
                                                          :on {:req/ok :a/done}})
                                      (c/send [:req :a] [:req/start])
                                      (c/send [:req :b] [:req/start])]}}}
      :error {}}}))

(def sys (c/system {:app app :components [req disc echo panel]}))

(defn trace-of [r] (mapv (juxt :to :type :via) (:trace r)))
(defn kinds [r] (set (map :problem (:problems r))))

;; --- 1. ordering ---------------------------------------------------------------

(deftest run-to-completion-is-breadth-first-fifo
  ;; :fan sends ping to x then y. x's output is queued BEHIND y's ping:
  ;; everything one message causes is appended to the queue, never jumps it.
  (let [r (c/dispatch sys (:world (c/init sys)) [:fan])]
    (is (= [[:app :fan :action]
            [[:echo :x] :echo/ping :send]
            [[:echo :y] :echo/ping :send]
            [:app :x/out :output]
            [:app :y/out :output]]
           (trace-of r)))
    (is (= [[:x :x 1] [:y :y 2]] (get-in r [:world :app :log]))
        "outputs reach the parent with :key (instance key) and :data")))

(deftest effects-run-after-the-queue-drains
  (let [r (c/dispatch sys (:world (c/init sys)) [:go])]
    (is (= [[:app :go :action]
            [[:req :a] :req/start :send]
            [[:req :b] :req/start :send]]
           (trace-of r)))
    (is (= [{:origin [:req :a] :effect [:fetch "a"]} {:origin [:req :b] :effect [:fetch "b"]}]
           (mapv #(select-keys % [:origin :effect]) (:fx r)))
        "effects are returned with their origin, not run")))

(deftest replies-each-run-to-completion-and-join
  (let [r (c/simulate sys [[:go]] {:effects {:fetch (fn [x] [:req/succeeded (str x "!")])}})]
    (is (= [[:app :go :action]
            [[:req :a] :req/start :send]
            [[:req :b] :req/start :send]
            [[:req :a] :req/succeeded :reply]
            [:app :a/done :output]
            [[:req :b] :req/succeeded :reply]
            [:app :b/done :output]]
           (trace-of r)))
    (is (= :ready (get-in r [:world :app :_state])) "join: both arrived")
    (is (= "a!" (get-in r [:world :app :a])))
    (is (empty? (filter #(= :error (:severity %)) (:problems r))))))

(deftest stale-replies-are-dropped
  ;; reload kills and respawns [:req :a]; a reply addressed to the old generation is dropped
  (let [{w :world} (c/simulate sys [[:go]] {:effects {:fetch (fn [x] [:req/succeeded x])}})
        old-gen (get-in w [:instances [:req :a] :gen])
        {w2 :world fx :fx} (c/dispatch sys w [:reload])
        r (c/deliver-reply sys w2 {:origin [:req :a] :gen old-gen} [:req/succeeded "stale"])]
    (is (= #{:stale-reply} (kinds r)))
    (is (= :loading (get-in r [:world :instances [:req :a] :state :_state])) "new instance untouched")
    (is (= [:fetch "a2"] (:effect (first fx))) "the new instance asked again")))

;; --- 2. instances --------------------------------------------------------------

(deftest lazy-instances
  (let [w (:world (c/init sys))
        vc (c/view-ctx sys w)]
    (is (= :closed (:_state (c/state vc [:disc :row-1]))) "unused lazy instance reads as initial")
    (is (nil? (get-in w [:instances [:disc :row-1]])) "and costs nothing")
    (let [r (c/dispatch sys w [:disc/toggle :row-1])]
      (is (= :open (get-in r [:world :instances [:disc :row-1] :state :_state])))
      (is (= :app (get-in r [:world :instances [:disc :row-1] :parent]))))))

(deftest kill-removes-the-subtree
  (let [w (:world (c/dispatch sys (:world (c/init sys)) [:panel]))
        w (:world (c/dispatch sys w [:panel/open :main]))]
    (is (= #{[:panel :main] [:echo :p1] [:echo :p2]} (set (keys (:instances w)))))
    (is (= [:panel :main] (get-in w [:instances [:echo :p1] :parent])))
    (is (empty? (:instances (:world (c/dispatch sys w [:close-panel]))))
        "children of a killed instance go with it")))

;; --- 3. problems are reported, not thrown --------------------------------------

(deftest wiring-problems
  (let [w (:world (c/init sys))]
    (is (= #{:unknown-output} (kinds (c/dispatch sys w [:bad-map]))) "mapping an output the type doesn't emit")
    (is (= #{:no-instance} (kinds (c/dispatch sys w [:orphan]))))
    (is (= :error (:severity (first (:problems (c/dispatch sys w [:orphan]))))))
    (let [r (c/dispatch sys w [:req/start :nobody-spawned-me])]
      (is (= #{:no-instance} (kinds r)) "UI action to a missing non-lazy instance")
      (is (= :warn (:severity (first (:problems r)))) "a warning: the view should not offer it"))
    (testing "a parent that can't take a mapped output is an error: the message would be lost"
      (let [r (c/simulate sys [[:go]] {:effects {:fetch (fn [x] (if (= x "a") [:req/failed "x"] [:req/succeeded x]))}})]
        ;; a failed → :error state; b's :b/done then arrives in :error, which has no transition
        (is (some #(and (= :output-rejected (:problem %)) (= :error (:severity %))) (:problems r)))))))

(deftest definitions-are-checked-up-front
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"namespace"
        (c/component {:type :foo :machine (fsm/machine {:id :foo :initial :a :states {:a {:on {:bar/x :a}}}})})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"collide"
        (c/system {:app (fsm/machine {:id :app :initial :a :states {:a {:on {:req/start :a}}}})
                   :components [req]}))))

(deftest message-loops-are-caught
  (let [ping (c/component {:type :ping :outputs #{:ping/out}
                           :machine (fsm/machine {:id :ping :initial :on
                                                  :states {:on {:on {:ping/in {:actions (c/emit :ping/out)}}}}})})
        loop-app (fsm/machine {:id :app :initial :a
                               :states {:a {:on {:start {:actions [(c/spawn [:ping :p] {:on {:ping/out :again}})
                                                                   (c/send [:ping :p] [:ping/in])]}
                                                 :again {:actions (c/send [:ping :p] [:ping/in])}}}}})
        s (c/system {:app loop-app :components [ping]})]
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"loop"
          (c/dispatch s (:world (c/init s)) [:start])))))

;; --- 4. views --------------------------------------------------------------------

(deftest view-helpers
  (let [w (:world (c/simulate sys [[:go]] {:effects {:fetch (fn [x] [:req/succeeded x])}}))
        vc (c/view-ctx sys w)]
    (is (c/in? vc [:req :a] :ok))
    (is (c/fires? vc [:req/start :a]) "ok → start again (refresh) is allowed")
    (is (c/fires? vc [:disc/toggle :never-used]) "lazy instance: checked against its initial state")
    (is (not (c/fires? vc [:req/succeeded :a "x"])) "not accepted in :ok")
    (is (= {:disabled true :data-event ":req/succeeded"} (c/on vc [:req/succeeded :a "x"])))
    (is (= [:button {:on {:click [:disc/toggle :r]} :data-event ":disc/toggle"} "▸"]
           (c/button vc [:disc/toggle :r] "▸")))
    (is (sr/in? (:app vc) :ready) "the app part is a normal replicant view-state")))

;; --- 5. check ------------------------------------------------------------------

(def ok-fx {:fetch (fn [x] [:req/succeeded x])})

(defn good-view [vc]
  [:div
   (c/button vc [:go] "Go")
   (when (c/in? vc [:req :a] :ok) (c/button vc [:req/start :a] "Refresh a"))
   (c/button vc [:disc/toggle :row-1] "▸")])

(deftest check-good-view
  (let [ps (c/check {:system sys :view good-view
                     :scenarios {:happy {:actions [[:go] [:disc/toggle :row-1]] :effects ok-fx}}})]
    (is (empty? (filter #(= :error (:severity %)) ps)) (pr-str (filter #(= :error (:severity %)) ps)))
    (testing "coverage warnings are honest about what no scenario reached"
      (is (some #(and (= :state/never-reached (:problem %)) (= :app (:instance %)) (= [:error] (:state-path %))) ps))
      (is (not-any? #(= [:loading] (:state-path %)) ps) "transient states (entered mid-drain) count as reached")
      (is (some #(and (= :output/never-emitted (:problem %)) (= :req/failed (:event %))) ps))
      (is (some #(and (= :type/never-used (:problem %)) (= :panel (:instance %))) ps)))))

(deftest check-catches-wiring-and-view-drift
  (let [bad-view (fn [vc]
                   [:div
                    [:button {:on {:click [:go]}} "Go"]                ; hand-wired: dead after :go
                    [:button {:on {:click [:req/strat :a]}} "typo"]    ; unknown component event
                    [:button {:on {:click [:req/start :ghost]}} "x"]]) ; instance never spawned
        ps (c/check {:system sys :view bad-view
                     :scenarios {:happy {:actions [[:go]] :effects ok-fx}
                                 :a-fails {:actions [[:go]]
                                           :effects {:fetch (fn [x] (if (= x "a") [:req/failed "x"] [:req/succeeded x]))}}}})
        errs (filter #(= :error (:severity %)) ps)
        has? (fn [kind & {:as m}] (some #(and (= kind (:problem %)) (= m (select-keys % (keys m)))) errs))]
    (is (has? :control/dead :event :go :reason :no-transition))
    (is (has? :event/unknown :event :req/strat :instance [:req :a]))
    (is (has? :control/dead :event :req/start :instance [:req :ghost] :reason :no-instance))
    (is (has? :output-rejected :fixture :a-fails :event :b/done)
        "the lost message from the join is reported for the scenario where it happens")
    (is (= :error (:severity (first ps))) "errors sort first")))

(deftest check-reports-loops-per-scenario
  (let [ping (c/component {:type :ping :outputs #{:ping/out}
                           :machine (fsm/machine {:id :ping :initial :on
                                                  :states {:on {:on {:ping/in {:actions (c/emit :ping/out)}}}}})})
        s (c/system {:app (fsm/machine {:id :app :initial :a
                                        :states {:a {:on {:start {:actions [(c/spawn [:ping :p] {:on {:ping/out :again}})
                                                                            (c/send [:ping :p] [:ping/in])]}
                                                          :again {:actions (c/send [:ping :p] [:ping/in])}}}}})
                     :components [ping]})]
    (is (= :scenario/threw (:problem (first (c/check {:system s :scenarios {:loop {:actions [[:start]]}}})))))))

;; --- 6. manual effects: actions while requests are in flight ---------------------

(deftest manual-effects-interleave
  (let [r (c/simulate sys [[:go] [::c/reply :fetch] [:disc/toggle :x]] {:effects ok-fx :manual? true})]
    (is (= [[:fetch "b"]] (mapv :effect (:fx r))) "b is still pending after answering the oldest :fetch")
    (is (= :loading (get-in r [:world :app :_state])))
    (is (= "a" (get-in r [:world :app :a])))
    (is (= 4 (count (:worlds r))) "a world after init and after each step"))
  (let [r (c/simulate sys [[:go] [::c/reply-all]] {:effects ok-fx :manual? true})]
    (is (= :ready (get-in r [:world :app :_state])))
    (is (empty? (:fx r))))
  (is (= #{:no-pending-effect}
         (set (map :problem (:problems (c/simulate sys [[::c/reply :fetch]] {:effects ok-fx :manual? true})))))))
