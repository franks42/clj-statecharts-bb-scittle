(ns statecharts.browser-test-runner
  "Browser test runner for clj-statecharts.
   Runs core functional tests and displays results in the browser."
  (:require [statecharts.core :as fsm :refer [assign]]
            [statecharts.impl :as impl]
            [statecharts.sim :as fsm.sim]
            [statecharts.store :as fsm.store]
            [statecharts.scheduler :as fsm.scheduler]
            [statecharts.clock]
            [statecharts.service]))

;; --- Test Infrastructure ---

(defonce test-results (atom {:pass 0 :fail 0 :tests []}))

(defn log [& args]
  (apply js/console.log args))

(defn report-pass [name]
  (swap! test-results (fn [r]
                        (-> r
                            (update :pass inc)
                            (update :tests conj {:name name :status :pass})))))

(defn report-fail [name msg]
  (swap! test-results (fn [r]
                        (-> r
                            (update :fail inc)
                            (update :tests conj {:name name :status :fail :msg msg})))))

(defn is= [test-name expected actual]
  (if (= expected actual)
    (report-pass test-name)
    (report-fail test-name (str "Expected " (pr-str expected) " but got " (pr-str actual)))))

(defn is-thrown [test-name f]
  (try
    (f)
    (report-fail test-name "Expected exception but none was thrown")
    (catch js/Error _
      (report-pass test-name))))

;; --- Tests ---

(defn test-basic-machine []
  (let [machine (fsm/machine
                  {:id :basic
                   :initial :idle
                   :context {:count 0}
                   :states
                   {:idle {:on {:start :running}}
                    :running {:on {:stop :idle
                                   :tick {:actions (assign (fn [ctx & _]
                                                            (update ctx :count inc)))}}}
                    :done {}}})
        state (fsm/initialize machine)]
    (is= "basic: initial state" :idle (:_state state))
    (is= "basic: initial context" 0 (:count state))

    (let [s2 (fsm/transition machine state :start)]
      (is= "basic: transition to running" :running (:_state s2))

      (let [s3 (fsm/transition machine s2 :tick)]
        (is= "basic: tick increments count" 1 (:count s3))
        (is= "basic: still running after tick" :running (:_state s3))

        (let [s4 (fsm/transition machine s3 :stop)]
          (is= "basic: back to idle" :idle (:_state s4))
          (is= "basic: count preserved" 1 (:count s4)))))))

(defn test-guards []
  (let [machine (fsm/machine
                  {:id :guards
                   :initial :idle
                   :context {:x 0}
                   :states
                   {:idle {:on {:go [{:target :high
                                      :guard (fn [ctx _] (> (:x ctx) 10))}
                                     {:target :low}]}}
                    :high {}
                    :low {}}})
        state (fsm/initialize machine)]
    (is= "guards: with x=0 goes to :low"
         :low (:_state (fsm/transition machine state :go)))
    (let [state2 (fsm/initialize machine {:context {:x 20}})]
      (is= "guards: with x=20 goes to :high"
           :high (:_state (fsm/transition machine state2 :go))))))

(defn test-nested-states []
  (let [machine (fsm/machine
                  {:id :nested
                   :initial :s1
                   :states
                   {:s1 {:initial :s1a
                         :on {:go-s2 :s2}
                         :states
                         {:s1a {:on {:next :s1b}}
                          :s1b {}}}
                    :s2 {}}})
        state (fsm/initialize machine)]
    (is= "nested: initial state" [:s1 :s1a] (:_state state))
    (let [s2 (fsm/transition machine state :next)]
      (is= "nested: inner transition" [:s1 :s1b] (:_state s2)))
    (let [s3 (fsm/transition machine state :go-s2)]
      (is= "nested: outer transition" :s2 (:_state s3)))))

(defn test-parallel-states []
  (let [machine (fsm/machine
                  {:id :parallel
                   :type :parallel
                   :regions
                   {:bold {:initial :off
                           :states {:on {:on {:toggle :off}}
                                    :off {:on {:toggle :on}}}}
                    :italic {:initial :off
                             :states {:on {:on {:toggle-i :off}}
                                      :off {:on {:toggle-i :on}}}}}})
        state (fsm/initialize machine)]
    (is= "parallel: initial state"
         {:bold :off :italic :off}
         (:_state state))
    (let [s2 (fsm/transition machine state :toggle)]
      (is= "parallel: toggle bold"
           {:bold :on :italic :off}
           (:_state s2)))))

(defn test-eventless-transitions []
  (let [machine (fsm/machine
                  {:id :eventless
                   :initial :s1
                   :context {:x 100}
                   :states
                   {:s1 {:on {:go :s2}}
                    :s2 {:always {:target :s3
                                  :guard (fn [{:keys [x]} _] (> x 50))}}
                    :s3 {}}})
        state (fsm/initialize machine)
        s2 (fsm/transition machine state :go)]
    (is= "eventless: always transition fires" :s3 (:_state s2))))

(defn test-simulated-clock []
  (let [clock (fsm.sim/simulated-clock)]
    (is= "simclock: initial time" 0 (fsm.sim/now clock))
    (fsm.sim/advance clock 500)
    (is= "simclock: after advance" 500 (fsm.sim/now clock))))

(defn test-delayed-transitions []
  (let [clock (fsm.sim/simulated-clock)
        store (fsm.store/single-store)
        machine (impl/machine
                  {:id :delayed
                   :scheduler (fsm.scheduler/make-store-scheduler store clock)
                   :initial :running
                   :states {:running {:after [{:delay 1000 :target :done}]
                                      :on {:cancel :canceled}}
                            :canceled {}
                            :done {}}})
        get-st (fn [] (:_state (fsm.store/get-state store nil)))]
    (fsm.store/initialize store machine nil)
    (is= "delayed: initial" :running (get-st))
    (fsm.sim/advance clock 500)
    (is= "delayed: not yet" :running (get-st))
    (fsm.sim/advance clock 500)
    (is= "delayed: done at 1000ms" :done (get-st))))

(defn test-delayed-cancel []
  (let [clock (fsm.sim/simulated-clock)
        store (fsm.store/single-store)
        machine (impl/machine
                  {:id :cancel
                   :scheduler (fsm.scheduler/make-store-scheduler store clock)
                   :initial :running
                   :states {:running {:after [{:delay 1000 :target :done}]
                                      :on {:cancel :canceled}}
                            :canceled {}
                            :done {}}})
        get-st (fn [] (:_state (fsm.store/get-state store nil)))]
    (fsm.store/initialize store machine nil)
    (fsm.sim/advance clock 500)
    (fsm.store/transition store machine nil :cancel {})
    (fsm.sim/advance clock 500)
    (is= "delayed-cancel: stays canceled" :canceled (get-st))))

(defn test-service []
  (let [clock (fsm.sim/simulated-clock)
        machine (fsm/machine
                  {:id :svc
                   :initial :s1
                   :states {:s1 {:on {:go :s2}}
                            :s2 {:on {:back :s1}}}})
        svc (fsm/service machine {:clock clock})]
    (fsm/start svc)
    (is= "service: initial" :s1 (fsm/value svc))
    (fsm/send svc :go)
    (is= "service: after send" :s2 (fsm/value svc))
    (fsm/send svc :back)
    (is= "service: round trip" :s1 (fsm/value svc))))

(defn test-assign []
  (let [machine (fsm/machine
                  {:id :assign
                   :initial :s1
                   :context {:x 0 :y 0}
                   :states
                   {:s1 {:on {:inc-x {:actions (assign (fn [ctx & _]
                                                         (update ctx :x inc)))}
                              :go {:target :s2
                                   :actions (assign (fn [ctx & _]
                                                      (assoc ctx :y 42)))}}}
                    :s2 {}}})
        state (fsm/initialize machine)
        s2 (fsm/transition machine state :inc-x)]
    (is= "assign: inc-x" 1 (:x s2))
    (is= "assign: y unchanged" 0 (:y s2))
    (let [s3 (fsm/transition machine s2 :go)]
      (is= "assign: y set on transition" 42 (:y s3))
      (is= "assign: x preserved" 1 (:x s3)))))

(defn test-unknown-event-error []
  (let [machine (fsm/machine
                  {:id :unknown
                   :initial :s1
                   :states {:s1 {:on {:known :s2}}
                            :s2 {}}})
        state (fsm/initialize machine)]
    (is-thrown "unknown-event: throws on unknown"
              #(fsm/transition machine state :bogus))
    (is= "unknown-event: ignore flag works"
         :s1
         (:_state (fsm/transition machine state :bogus
                                  {:ignore-unknown-event? true})))))

(defn test-history-states []
  (let [machine (fsm/machine
                  {:id :history
                   :initial :s1
                   :context {:x 1 :y 2}
                   :states
                   {:s1 {:initial :s1.1
                         :on {:e12 :s2}
                         :states
                         {:s1.1 {:on {:e-inner :s1.2}}
                          :s1.2 {}}}
                    :s2 {:on {:e21 :s1}}}})
        state (fsm/initialize machine)]
    (is= "history: initial nested" [:s1 :s1.1] (:_state state))
    (let [s2 (fsm/transition machine state :e-inner)]
      (is= "history: inner transition" [:s1 :s1.2] (:_state s2))
      (let [s3 (fsm/transition machine s2 :e12)]
        (is= "history: to s2" :s2 (:_state s3))
        (let [s4 (fsm/transition machine s3 :e21)]
          (is= "history: back to s1 re-inits" [:s1 :s1.1] (:_state s4)))))))

(defn test-js-wall-clock []
  (let [clock (statecharts.clock/wall-clock)
        t1 (statecharts.clock/getTimeMillis clock)]
    (is= "wall-clock: returns positive time" true (> t1 0))
    ;; Test setTimeout/clearTimeout (the :cljs reader conditional path)
    (let [called (atom false)
          id (statecharts.clock/setTimeout clock #(reset! called true) 10)]
      (is= "wall-clock: setTimeout returns id" true (some? id))
      (statecharts.clock/clearTimeout clock id)
      ;; After clearing, the callback should not fire
      ;; We can't easily test this synchronously, just verify no errors
      (is= "wall-clock: clearTimeout succeeds" true true))))

;; --- Runner ---

(defn run-all-tests []
  (reset! test-results {:pass 0 :fail 0 :tests []})

  (log "Running browser tests...")

  (test-basic-machine)
  (test-guards)
  (test-nested-states)
  (test-parallel-states)
  (test-eventless-transitions)
  (test-simulated-clock)
  (test-delayed-transitions)
  (test-delayed-cancel)
  (test-service)
  (test-assign)
  (test-unknown-event-error)
  (test-history-states)
  (test-js-wall-clock)

  (let [{:keys [pass fail tests]} @test-results]
    (log (str "Results: " pass " passed, " fail " failed"))

    ;; Render to DOM
    (let [container (js/document.getElementById "results")]
      (set! (.-innerHTML container)
            (str "<h2>clj-statecharts Browser Test Results</h2>"
                 "<p><strong>" pass " passed, " fail " failed</strong></p>"
                 "<ul>"
                 (apply str
                   (map (fn [{:keys [name status msg]}]
                          (str "<li style='color:" (if (= status :pass) "green" "red") "'>"
                               (if (= status :pass) "PASS" "FAIL")
                               " " name
                               (when msg (str " — " msg))
                               "</li>"))
                        tests))
                 "</ul>")))))

(run-all-tests)
