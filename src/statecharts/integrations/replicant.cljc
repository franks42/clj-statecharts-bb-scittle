(ns statecharts.integrations.replicant
  "Glue between clj-statecharts and Replicant that keeps chart, state and views in sync.

  The design keeps a single source of truth:

  - The app state IS the statechart state map (context keys + :_state). There is
    no second atom and no separate 'mode' flag, so they cannot drift apart.
  - Views emit actions as data: [:event/type payload & more]. An action's first
    element is a chart event, so every handler is checkable against the chart.
  - Chart actions never perform side effects. They queue effects as data with `fx`
    (under :app/fx). The runtime runs them after the transition, so `step` stays
    pure and testable in bb, nbb and the JVM.
  - Views receive a view-state: the state plus :app/can (events the chart accepts
    now) and :app/active (active state paths). `on`/`button` disable a control
    whose event the chart would reject, so no control does nothing when clicked.
  - `check` renders the views against fixture states and reports where chart and
    views disagree. Run it from `bb verify` / tests.

  No dependency on Replicant: `start!` receives replicant.dom/render and
  replicant.dom/set-dispatch! as arguments, so this namespace loads in bb, nbb,
  Scittle and the JVM.

  Reserved keys in state: :app/fx (queued effects), and in view-state only
  :app/can and :app/active. Reserved keyword namespaces in actions, used as
  placeholders filled from the DOM event: :event.target/* and :event/*."
  (:require [clojure.walk :as walk]
            [statecharts.core :as fsm]
            [statecharts.impl :as impl]))

;; ---------------------------------------------------------------------------
;; Chart-side helpers: use these inside machine definitions

(defn fx
  "Chart action that queues effects as data instead of performing them.

  Each effect is a vector [:effect/key & args], or a fn of (state event) that
  returns such a vector (or nil to skip). Effects run after the transition,
  via the :effects map given to `make-app`.

    {:on {:pay/submit {:target :submitting
                       :actions (fx (fn [s _] [:pay/charge (:basket s) :pay/ok :pay/declined]))}}}"
  [& effects]
  (fsm/assign
    (fn [state event]
      (update state :app/fx (fnil into [])
              (keep #(if (fn? %) (% state event) %) effects)))))

(defn assoc-data
  "Chart action: store the event's payload (:data) at `path` in the context."
  [path]
  (let [path (if (keyword? path) [path] (vec path))]
    (fsm/assign (fn [state event] (assoc-in state path (:data event))))))

(defn update-ctx
  "Chart action: (apply update-in state path f args)."
  [path f & args]
  (let [path (if (keyword? path) [path] (vec path))]
    (fsm/assign (fn [state _] (apply update-in state path f args)))))

;; ---------------------------------------------------------------------------
;; Reading the chart (a small analysis subset until statecharts.analysis lands)

(defn- internal-event?
  "Events the library generates itself: :fsm/always and [:fsm/delay …] vectors."
  [e]
  (or (vector? e)
      (and (keyword? e) (= "fsm" (namespace e)))))

(defn- children [node]
  (concat (:states node) (:regions node)))

(defn state-paths
  "Every state node path in the machine, e.g. #{[:browsing] [:checkout] [:checkout :editing]}."
  [machine]
  (letfn [(walk-node [path node]
            (cons path (mapcat (fn [[k child]] (walk-node (conj path k) child))
                               (children node))))]
    (set (rest (walk-node [] machine)))))

(defn leaf-paths
  "State paths with no child states or regions."
  [machine]
  (set (filter #(empty? (children (impl/resolve-node machine % true)))
               (state-paths machine))))

(defn handled-events
  "Every user event the chart has a transition for, in any state."
  [machine]
  (->> (tree-seq map? #(vals (merge (:states %) (:regions %))) machine)
       (mapcat (comp keys :on))
       (remove internal-event?)
       set))

(defn active-paths
  "The active configuration as a set of paths (ancestors included)."
  [state]
  (set (impl/_state->nodes (:_state state))))

(defn accepted-events
  "Events with a transition on some node of the active configuration (root included).
  A guard may still reject the event at runtime; accepted means the chart has a
  transition for it here, not that a guard will allow it."
  [machine state]
  (->> (impl/_state->configuration machine (:_state state))
       (keep :on)
       (mapcat keys)
       (remove internal-event?)
       set))

;; ---------------------------------------------------------------------------
;; Actions as data

(defn- action? [x]
  (and (vector? x) (keyword? (first x))))

(defn actions-of
  "Normalize Replicant handler data into a vector of actions, or nil if it isn't
  action data. Accepts a single action [:e …] or a sequence of them [[:e1] [:e2 …]]."
  [handler-data]
  (cond
    (action? handler-data) [handler-data]
    (and (sequential? handler-data)
         (seq handler-data)
         (every? action? handler-data)) (vec handler-data)
    :else nil))

(defn action->event
  "[:seat/toggle \"A18\"] => {:type :seat/toggle :data \"A18\"}
  Extra arguments go in :args (all of them, including the first)."
  [[type & args]]
  (cond-> {:type type}
    (seq args) (assoc :data (first args))
    (next args) (assoc :args (vec args))))

(defn- placeholder? [x]
  (and (keyword? x) (#{"event" "event.target"} (namespace x))))

(defn has-placeholders?
  "Does handler data still contain :event/* or :event.target/* placeholders?"
  [handler-data]
  (boolean (some placeholder? (tree-seq coll? seq handler-data))))

(defn enabled?
  "Would `action` actually fire a transition in `state`, with guards evaluated?
  Uses the library's own transition selection (impl/select-one-tx), so the
  answer matches `fsm/transition`. Guards must be pure for this to be safe."
  [machine state action]
  (let [event (action->event action)]
    (->> (impl/_state->configuration machine (:_state state))
         (filter #(= :atomic (:type %)))
         (some #(second (impl/select-one-tx machine % state event event)))
         boolean)))

(defn- dom-get [obj k]
  (when obj
    (if (map? obj)
      (get obj (keyword k))           ; simulated events in tests: {:target {:value "x"}}
      #?(:cljs (aget obj k)
         :clj (get obj (keyword k))))))

(defn interpolate
  "Fill placeholders in handler data from the DOM event:
  :event.target/value, :event.target/checked, :event.target/valueAsNumber,
  :event/key, … (any property name). In tests pass a map such as
  {:target {:value \"x\"} :key \"Enter\"} instead of a DOM event."
  [handler-data dom-event]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (namespace x))
        (case (namespace x)
          "event.target" (dom-get (dom-get dom-event "target") (name x))
          "event" (dom-get dom-event (name x))
          x)
        x))
    handler-data))

;; ---------------------------------------------------------------------------
;; The pure core

(defn step
  "Apply one action to state. Pure apart from the chart's own actions (which,
  written with `fx`/`assoc-data`/`update-ctx`, are pure too).

  Returns {:state new-state :fx [effects…]}, or
  {:state state :rejected action :reason :no-transition|:guard} when nothing fires."
  [machine state action]
  (let [{:keys [type] :as event} (action->event action)]
    (cond
      (not (contains? (accepted-events machine state) type))
      {:state state :rejected action :reason :no-transition}

      (not (enabled? machine state action))
      {:state state :rejected action :reason :guard}

      :else
      (let [s (fsm/transition machine state event)]
        {:state (dissoc s :app/fx)
         :fx (vec (:app/fx s))}))))

(defn run-script
  "Apply a sequence of actions from `state`, collecting effects. Stops at the first
  rejected action. For tests: (run-script m s [[:checkout/start] [:pay/submit]])."
  [machine state actions]
  (reduce (fn [{:keys [state fx]} action]
            (let [r (step machine state action)]
              (if (:rejected r)
                (reduced (assoc r :fx fx :at action))
                (update r :fx #(into fx %)))))
          {:state state :fx []}
          actions))

(defn state-after
  "The state reached by replaying `actions` from `state` (default: the initial state).
  Throws if any action is rejected. Use it to build fixtures, so every fixture is a
  state the chart can actually reach:

    (def fixtures {:editing (state-after machine [[:seat/toggle \"A18\"] [:checkout/start]])})"
  ([machine actions] (state-after machine (fsm/initialize machine) actions))
  ([machine state actions]
   (let [{:keys [rejected reason at] :as r} (run-script machine state actions)]
     (when rejected
       (throw (ex-info (str "fixture script rejected " (pr-str at) " (" (name reason) ")"
                            " in state " (pr-str (:_state (:state r))))
                       {:action at :reason reason :state (:_state (:state r))})))
     (:state r))))

;; ---------------------------------------------------------------------------
;; View helpers: views receive a view-state

(defn view-state
  "State plus :app/can (accepted events), :app/active (active paths) and
  :app/enabled? (fn of an action, guards evaluated), as handed to the view."
  [machine state]
  (assoc state
         :app/can (accepted-events machine state)
         :app/active (active-paths state)
         :app/enabled? #(enabled? machine state %)))

(defn can?
  "Does the chart have a transition for `event` here? (Guards not evaluated.)"
  [vs event]
  (contains? (:app/can vs) event))

(defn fires?
  "Would this exact action fire now, guards included? Falls back to `can?` when
  the action still has DOM placeholders, since guards can't see their values yet."
  [vs action]
  (let [[event] action]
    (if (or (has-placeholders? action) (nil? (:app/enabled? vs)))
      (can? vs event)
      ((:app/enabled? vs) action))))

(defn in?
  "Is the state at `path` active? (in? vs :checkout) (in? vs [:checkout :submitting])"
  [vs path]
  (contains? (:app/active vs) (if (keyword? path) [path] (vec path))))

(defn on
  "Attribute map that wires `dom-event` to `handler` (an action or actions), or
  disables the control when the first action wouldn't fire (guards included, see
  `fires?`). Always sets data-event, for selectors in smoke tests.

    [:button (on vs [:pay/submit]) \"Pay\"]
    [:input (merge {:value (:email vs)} (on vs :input [:form/email :event.target/value]))]"
  ([vs handler] (on vs :click handler))
  ([vs dom-event handler]
   (let [[[event :as action]] (actions-of handler)]
     (if (fires? vs action)
       {:on {dom-event handler} :data-event (str event)}
       {:disabled true :data-event (str event)}))))

(defn button
  "A button wired with `on`. Optional attrs may include :tag (default :button),
  e.g. {:tag :wa-button :variant \"brand\"} for Web Awesome.

    (button vs [:pay/submit] \"Pay\")
    (button vs {:tag :wa-button :variant \"brand\"} [:pay/submit] \"Pay\")"
  ([vs handler label] (button vs {} handler label))
  ([vs attrs handler label]
   [(:tag attrs :button) (merge (dissoc attrs :tag) (on vs handler)) label]))

(defn view-for
  "Route on state: `views` maps state paths to view fns of view-state. Picks the
  deepest active path that has a view.

    (view-for vs {[:browsing] browsing-view, [:checkout] checkout-view})"
  [vs views]
  (when-let [path (->> (:app/active vs)
                       (filter #(contains? views %))
                       (sort-by count >)
                       first)]
    ((get views path) vs)))

;; ---------------------------------------------------------------------------
;; Runtime (browser, or anywhere with an atom)

(defn- report! [{:keys [on-problem]} kind data]
  (cond
    (fn? on-problem) (on-problem kind data)
    (= on-problem :throw) (throw (ex-info (str "statecharts/replicant: " (name kind)) data))
    (= on-problem :ignore) nil
    :else #?(:cljs (js/console.warn "statecharts/replicant:" (name kind) (pr-str data))
             :clj (println "WARN statecharts/replicant:" (name kind) (pr-str data)))))

(defn make-app
  "{:machine  (fsm/machine …)
    :context  initial context overrides (optional)
    :effects  {:effect/key (fn [dispatch & args] …)}  ; dispatch takes an action
    :on-problem :warn (default) | :throw | :ignore | (fn [kind data])}"
  [{:keys [machine context effects on-problem]
    :or {on-problem :warn}}]
  (let [s0 (fsm/initialize machine {:context context})]
    {:machine machine
     :effects effects
     :on-problem on-problem
     :!state (atom (dissoc s0 :app/fx))
     :init-fx (vec (:app/fx s0))}))

(declare dispatch!)

(defn- run-fx! [app effects]
  (doseq [[k & args :as e] effects]
    (if-let [handler (get (:effects app) k)]
      (apply handler #(dispatch! app %) args)
      (report! app :unknown-effect {:effect e :known (set (keys (:effects app)))}))))

(defn dispatch!
  "Run handler data (one action or several) against the app. Rejected actions are
  reported per :on-problem and leave the state unchanged."
  ([app handler-data] (dispatch! app handler-data nil))
  ([app handler-data dom-event]
   (if-let [actions (actions-of (interpolate handler-data dom-event))]
     (doseq [action actions]
       (let [{:keys [state fx rejected reason]} (step (:machine app) @(:!state app) action)]
         (if rejected
           (report! app :rejected {:action rejected
                                   :reason reason
                                   :state (:_state state)
                                   :accepted (accepted-events (:machine app) state)})
           (do (reset! (:!state app) state)
               (run-fx! app fx)))))
     (report! app :not-action-data {:handler-data handler-data}))))

(defn start!
  "Wire the app to Replicant and render. Pass Replicant's functions in:

    (start! app {:render r/render :set-dispatch! r/set-dispatch!
                 :el (js/document.getElementById \"app\") :view root-view})

  `view` is a fn of view-state returning hiccup."
  [app {:keys [render set-dispatch! el view]}]
  (let [machine (:machine app)
        paint (fn [state] (render el (view (view-state machine state))))]
    (set-dispatch! (fn [event-data handler-data]
                     (dispatch! app handler-data (:replicant/dom-event event-data))))
    (add-watch (:!state app) ::render (fn [_ _ _ state] (paint state)))
    (paint @(:!state app))
    (run-fx! app (:init-fx app))
    app))

;; ---------------------------------------------------------------------------
;; Sync checks: render views against fixture states, compare with the chart

(defn- elements
  "Hiccup elements in a tree (tag vectors), skipping attribute maps."
  [h]
  (cond
    (and (vector? h) (keyword? (first h)))
    (cons h (mapcat elements (if (map? (second h)) (drop 2 h) (rest h))))

    (seq? h) (mapcat elements h)
    :else nil))

(defn controls
  "Every event handler in a hiccup tree:
  [{:tag :button :dom-event :click :handler [...] :disabled? false :event :pay/submit}]
  Also reports controls that `on` disabled (they carry data-event but no handler)."
  [hiccup]
  (for [[tag attrs] (elements hiccup)
        :when (map? attrs)
        c (concat
            (for [[dom-event handler] (:on attrs)]
              {:tag tag :dom-event dom-event :handler handler
               :disabled? (boolean (:disabled attrs))
               :event (ffirst (actions-of handler))})
            (when (and (:disabled attrs) (:data-event attrs) (empty? (:on attrs)))
              (let [s (:data-event attrs)]
                [{:tag tag :disabled? true
                  :event (if (= \: (first s)) (keyword (subs s 1)) (keyword s))}])))]
    c))

(defn check
  "Render `view` for each fixture and compare with the chart. Returns a vector of
  problems, errors first. Pure; doesn't throw.

  {:machine         (fsm/machine …)
   :view            fn of view-state → hiccup
   :fixtures        {:name state-map …}    ; state maps as fsm/initialize returns them
   :external-events #{:pay/ok …}           ; events dispatched by effects, not by controls}

  Problem kinds:
    :fixture/invalid-state  fixture's :_state isn't a configuration of the chart
    :view/threw             rendering the fixture threw
    :handler/function       a handler is a fn, not action data
    :handler/not-action     handler data isn't [:event …] or [[:event …] …]
    :event/unknown          a control emits an event no state handles
    :control/dead           an enabled control's event isn't accepted in that state
    :event/not-offered      (warning) accepted in that state but no control and not external
    :state/no-fixture       (warning) a leaf state no fixture covers"
  [{:keys [machine view fixtures external-events]
    :or {external-events #{}}}]
  (let [handled (handled-events machine)
        covered (atom #{})
        per-fixture
        (for [[fname state] fixtures
              :let [config (impl/_state->configuration machine (:_state state))]]
          (if (some nil? config)
            [{:severity :error :problem :fixture/invalid-state :fixture fname
              :state (:_state state)
              :fix "use a :_state that exists in the chart (see state-paths)"}]
            (let [_ (swap! covered into (active-paths state))
                  vs (view-state machine state)
                  rendered (try {:ok (view vs)}
                                (catch #?(:clj Throwable :cljs :default) e {:error e}))]
              (if-let [e (:error rendered)]
                [{:severity :error :problem :view/threw :fixture fname
                  :message (ex-message e)
                  :fix "make the view total over this state, or fix the fixture"}]
                (let [cs (controls (:ok rendered))
                      ;; a disabled control still counts as offered: a guard is
                      ;; correctly holding it back in this state
                      offered (set (keep :event cs))]
                  (concat
                    (for [{:keys [tag handler event disabled?]} cs
                          :when handler
                          p (cond
                              (fn? handler)
                              [{:problem :handler/function
                                :fix "use action data, e.g. [:my/event payload], not a fn"}]

                              (nil? (actions-of handler))
                              [{:problem :handler/not-action :handler handler
                                :fix "handler data must be [:event …] or [[:event …] …]"}]

                              (not (contains? handled event))
                              [{:problem :event/unknown :event event
                                :fix (str "add a transition for " event " to the chart, or fix the typo")}]

                              (and (not disabled?) (not (fires? vs (first (actions-of handler)))))
                              [{:problem :control/dead :event event
                                :accepted (:app/can vs)
                                :reason (if (can? vs event) :guard :no-transition)
                                :fix (if (can? vs event)
                                       (str "a guard blocks " event " here; build the control with (on vs …) / (button vs …) so it disables itself")
                                       (str "use (on vs …) / (button vs …) so it disables itself, "
                                            "or add a transition for " event " in " (:_state state)))}]

                              :else nil)]
                      (assoc p :severity :error :fixture fname :tag tag))
                    (for [e (sort (remove (into offered external-events) (:app/can vs)))]
                      {:severity :warning :problem :event/not-offered :fixture fname
                       :event e :state (:_state state)
                       :fix (str "offer a control for " e ", or list it in :external-events")})))))))
        problems (vec (apply concat per-fixture))   ; realize before reading `covered`
        missing (for [p (sort-by str (leaf-paths machine))
                      :when (not (contains? @covered p))]
                  {:severity :warning :problem :state/no-fixture :state-path p
                   :fix (str "add a fixture whose :_state is in " p)})]
    (vec (sort-by #(if (= :error (:severity %)) 0 1)
                  (concat problems missing)))))

(defn format-problem
  "One line per problem, for bb verify output."
  [{:keys [severity problem fixture event tag state-path fix] :as p}]
  (str (if (= severity :error) "FAIL " "WARN ")
       (subs (str problem) 1)
       (when fixture (str " · fixture " fixture))
       (when tag (str " · <" (name tag) ">"))
       (when event (str " · " event))
       (when state-path (str " · " state-path))
       (when-let [m (:message p)] (str " · " m))
       "\n  fix: " fix))
