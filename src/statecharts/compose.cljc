(ns statecharts.compose
  "Composable component charts: many small charts, one per component instance,
  wired together by message passing. Builds on statecharts.integrations.replicant.

  VOCABULARY
  - component type: a chart plus its interface, defined once with `component`.
    Its events all live in one namespace named after the type (:req/start, …),
    and it declares the outputs it may emit (:req/ok, …).
  - instance: [type key], e.g. [:req :portfolio] or [:disc \"row-17\"]. Each has
    its own chart state, a parent (:app or another instance) and an output
    mapping chosen by whoever spawned it.
  - the app: the top-level chart. Its events must not use a component namespace.

  ROUTING
  An action whose event namespace names a component type goes to an instance;
  the instance key is its second element: [:disc/toggle \"row-17\"] → [:disc \"row-17\"].
  Any other action goes to the app: [:accounts/add] → :app.

  MESSAGES
  - down: the parent's chart sends (`send`, `send-each`) or the UI dispatches.
  - up:   a child's chart emits (`emit`). The output reaches the parent only if
          the spawn mapped it, renamed to the parent's event:
          (spawn [:req :prices] {:on {:req/ok :prices/loaded}})
          The parent receives {:type :prices/loaded :key :prices :from [:req :prices] :data …}.

  ORDERING (run to completion, breadth-first)
  1. One external message (UI action or effect reply) is processed at a time.
  2. Delivering a message runs one transition. Everything it causes (outputs to
     the parent, sends to children) is APPENDED to a FIFO queue; spawn and kill
     take effect immediately, in the order the chart's actions ran.
  3. The queue drains completely before the next external message.
  4. Effects are collected while draining and run only after the queue is empty.
     Each effect's replies are new external messages, addressed to the instance
     that asked, tagged with that instance's generation: a reply to an instance
     that was killed or replaced meanwhile is dropped (:stale-reply).
  5. More than `max-messages` deliveries for one external message is treated as
     a loop and throws.

  INSTANCES
  - spawn creates one (initial context from :params); kill removes it and every
    instance it spawned.
  - :lazy? types need no spawn: the first message creates the instance, owned
    by :app with no output mapping. Until then it reads as its initial state, so
    300 unused tree-row disclosures cost nothing.

  Everything except `make-app`/`dispatch!`/`start!` is pure, so the whole system
  runs under bb, nbb and the JVM with fake effects via `simulate`."
  (:refer-clojure :exclude [send descendants deliver])
  (:require [statecharts.core :as fsm]
            [statecharts.integrations.replicant :as sr]))

(def max-messages 1000)

;; ---------------------------------------------------------------------------
;; Chart-side commands (use inside chart definitions)

(defn- push-cmds [f]
  (fsm/assign (fn [s e] (update s :app/cmd (fnil into []) (f s e)))))

(defn- resolve-v [v s e] (if (fn? v) (v s e) v))

(defn emit
  "Chart action: emit output `type` to the parent. `data` is a context key, a
  fn of (state event), a literal, or omitted."
  ([type] (emit type nil))
  ([type data]
   (push-cmds (fn [s e] [[:emit type (cond (nil? data) nil
                                           (keyword? data) (get s data)
                                           (fn? data) (data s e)
                                           :else data)]]))))

(defn spawn
  "Chart action: spawn instance `id` as a child of this chart.
  opts: {:params initial-context :on {child-output parent-event} :replace? bool}.
  `id` and `opts` may be fns of (state event)."
  [id opts]
  (push-cmds (fn [s e] [[:spawn (resolve-v id s e) (resolve-v opts s e)]])))

(defn spawn-each
  "Chart action: (f state event) returns [[id opts] …] to spawn."
  [f]
  (push-cmds (fn [s e] (for [[id opts] (f s e)] [:spawn id opts]))))

(defn send
  "Chart action: send `action` (without instance key) to instance `id`:
  (send [:req :prices] [:req/start])."
  [id action]
  (push-cmds (fn [s e] [[:send (resolve-v id s e) (resolve-v action s e)]])))

(defn send-each
  "Chart action: (f state event) returns [[id action] …] to send."
  [f]
  (push-cmds (fn [s e] (for [[id action] (f s e)] [:send id action]))))

(defn kill
  "Chart action: remove instance `id` and everything it spawned."
  [id]
  (push-cmds (fn [s e] [[:kill (resolve-v id s e)]])))

(defn kill-each
  "Chart action: (f state event) returns [id …] to kill."
  [f]
  (push-cmds (fn [s e] (for [id (f s e)] [:kill id]))))

;; ---------------------------------------------------------------------------
;; Definitions

(defn component
  "Define a component type:
    {:type :req                 ; also the namespace of all its events and outputs
     :machine (fsm/machine …)
     :outputs #{:req/ok …}      ; what it may emit
     :lazy? false}              ; true: instances appear on first message
  Throws if events or outputs are outside the type's namespace."
  [{:keys [type machine outputs] :as c}]
  (when-not (and (keyword? type) (nil? (namespace type)))
    (throw (ex-info (str "component :type must be a simple keyword, got " (pr-str type)) {:type type})))
  (let [nsn (name type)
        handled (sr/handled-events machine)
        foreign (sort (remove #(= nsn (namespace %)) handled))
        foreign-out (sort (remove #(= nsn (namespace %)) outputs))]
    (when (seq foreign)
      (throw (ex-info (str "component " type ": events must be in the " nsn "/ namespace, found "
                           (pr-str (vec foreign)))
                      {:type type :events foreign})))
    (when (seq foreign-out)
      (throw (ex-info (str "component " type ": outputs must be in the " nsn "/ namespace, found "
                           (pr-str (vec foreign-out)))
                      {:type type :outputs foreign-out})))
    (assoc c
           :accepts handled
           :outputs (set outputs)
           :initial-state (fsm/initialize machine))))

(defn system
  "{:app (fsm/machine …) :components [component …]}. Throws if app events use a
  component namespace (routing would be ambiguous)."
  [{:keys [app components]}]
  (let [types (into {} (map (juxt :type identity)) components)
        type-ns (set (map (comp name :type) components))
        clash (sort (filter #(contains? type-ns (namespace %)) (sr/handled-events app)))]
    (when (seq clash)
      (throw (ex-info (str "app events collide with component namespaces: " (pr-str (vec clash))
                           " — rename them, or map the child's outputs to app events")
                      {:events clash})))
    {:app app :types types :type-ns type-ns}))

;; ---------------------------------------------------------------------------
;; Routing

(defn- component-event? [sys t]
  (and (keyword? t) (contains? (:type-ns sys) (namespace t))))

(defn route
  "action → {:to id :event event-map}."
  [sys [t & more :as action]]
  (if (component-event? sys t)
    (let [[k & payload] more]
      {:to [(keyword (namespace t)) k]
       :event (sr/action->event (into [t] payload))})
    {:to :app :event (sr/action->event action)}))

(defn- machine-of [sys id]
  (if (= id :app) (:app sys) (get-in sys [:types (first id) :machine])))

;; ---------------------------------------------------------------------------
;; The pure core

(defn- acc0 [world] {:world world :queue [] :fx [] :problems [] :trace [] :n 0})

(defn- problem [acc severity kind m fix]
  (update acc :problems conj (merge {:severity severity :problem kind :fix fix} m)))

(defn- enqueue [acc msg] (update acc :queue conj msg))

(defn- set-state [acc id s]
  (if (= id :app)
    (assoc-in acc [:world :app] s)
    (assoc-in acc [:world :instances id :state] s)))

(defn- descendants [instances id]
  (let [kids (keep (fn [[k v]] (when (= id (:parent v)) k)) instances)]
    (concat kids (mapcat #(descendants instances %) kids))))

(defn- kill-instance [acc id]
  (if (get-in acc [:world :instances id])
    (let [ids (cons id (descendants (get-in acc [:world :instances]) id))]
      (update-in acc [:world :instances] #(apply dissoc % ids)))
    (problem acc :info :kill-missing {:instance id} "nothing to do")))

(declare spawn-instance)

(defn- run-cmd [sys acc from [op x y]]
  (case op
    :emit
    (if (= from :app)
      (problem acc :warn :app-emit {:event x} "the app has no parent; use an effect instead")
      (let [{:keys [parent on]} (get-in acc [:world :instances from])
            acc (update acc :emitted (fnil conj #{}) x)]
        (if-let [mapped (get on x)]
          (enqueue acc {:to parent :via :output
                        :event {:type mapped :key (second from) :from from :data y}})
          (problem acc :info :unmapped-output {:instance from :event x}
                   "the spawner didn't map this output; it is ignored"))))

    :spawn (spawn-instance sys acc x y from)

    :kill (kill-instance acc x)

    :send
    (let [[t] y]
      (if (and (vector? x) (= (name (first x)) (namespace t)))
        (enqueue acc {:to x :via :send :event (sr/action->event y)})
        (problem acc :error :wrong-target {:instance x :event t}
                 (str "send " t " to an instance of type :" (namespace t)))))))

(defn- absorb
  "Store the new state of `id`; run its queued commands in order; collect its effects."
  [sys acc id s']
  (let [cmds (:app/cmd s')
        fxs (:app/fx s')
        acc (set-state acc id (dissoc s' :app/cmd :app/fx))
        ;; every state ever entered, transient ones included, for coverage
        acc (update acc :reached (fnil into #{})
                    (map (fn [p] [(if (= id :app) :app (first id)) p]) (sr/active-paths s')))
        gen (if (= id :app) 0 (get-in acc [:world :instances id :gen]))
        acc (reduce #(run-cmd sys %1 id %2) acc cmds)]
    (update acc :fx into (map (fn [e] {:origin id :gen gen :effect e}) fxs))))

(defn- spawn-instance [sys acc id {:keys [params on replace?]} parent]
  (let [type (get-in sys [:types (first id)])
        exists? (get-in acc [:world :instances id])
        unknown (sort (remove (:outputs type #{}) (keys on)))]
    (cond
      (not (and (vector? id) (= 2 (count id))))
      (problem acc :error :bad-instance-id {:instance id} "instance ids are [type key]")

      (nil? type)
      (problem acc :error :unknown-type {:instance id} (str "register a component of type " (first id)))

      (and exists? (not replace?))
      (problem acc :warn :already-exists {:instance id} "use :replace? true, or kill it first")

      (seq unknown)
      (problem acc :error :unknown-output {:instance id :outputs unknown :emits (:outputs type)}
               (str "map only outputs " (first id) " declares: " (pr-str (sort (:outputs type)))))

      :else
      (let [acc (if exists? (kill-instance acc id) acc)
            gen (inc (get-in acc [:world :gen]))
            s0 (fsm/initialize (:machine type) {:context params})]
        (absorb sys
                (-> acc
                    (assoc-in [:world :gen] gen)
                    (assoc-in [:world :instances id] {:parent parent :on (or on {}) :gen gen}))
                id s0)))))

(defn- rejection [acc {:keys [to event via]} reason state]
  (let [[severity kind fix]
        (case via
          :action [:warn :rejected "the view offered an action that can't fire; build it with on/button"]
          :output [:error :output-rejected (str "the parent has no transition for " (:type event)
                                                " in this state; the message is lost")]
          :send   [:error :send-rejected "the child can't take this message in its current state"]
          :reply  [:error :reply-rejected "the instance didn't expect this effect reply in its current state"])]
    (problem acc severity kind {:instance to :event (:type event) :reason reason :state (:_state state)} fix)))

(defn- deliver [sys acc {:keys [to event via gen] :as msg}]
  (let [type (when (vector? to) (get-in sys [:types (first to)]))
        acc (if (and type (:lazy? type) (not= via :reply) (nil? (get-in acc [:world :instances to])))
              (spawn-instance sys acc to {} :app)
              acc)
        inst (when (vector? to) (get-in acc [:world :instances to]))]
    (cond
      (and (vector? to) (nil? type))
      (problem acc :error :unknown-type {:instance to :event (:type event)} "no such component type")

      (and (vector? to) (nil? inst))
      (if (= via :reply)
        (problem acc :info :late-reply {:instance to :event (:type event)} "the instance is gone; reply dropped")
        (problem acc (if (= via :action) :warn :error) :no-instance {:instance to :event (:type event)}
                 "spawn the instance first, or make its type :lazy?"))

      (and gen inst (not= gen (:gen inst)))
      (problem acc :info :stale-reply {:instance to :event (:type event)}
               "reply for an earlier instance with this id; dropped")

      :else
      (let [m (machine-of sys to)
            s (if (= to :app) (get-in acc [:world :app]) (:state inst))
            acc (update acc :trace conj {:to to :type (:type event) :via via})]
        (cond
          (not (contains? (sr/accepted-events m s) (:type event))) (rejection acc msg :no-transition s)
          (not (sr/event-enabled? m s event)) (rejection acc msg :guard s)
          :else (absorb sys acc to (fsm/transition m s event)))))))

(defn- run-queue [sys acc]
  (loop [acc acc]
    (if-let [msg (first (:queue acc))]
      (if (>= (:n acc) max-messages)
        (throw (ex-info (str "message loop: more than " max-messages " deliveries for one external message")
                        {:recent (take-last 10 (:trace acc))}))
        (recur (deliver sys (-> acc (update :queue subvec 1) (update :n inc)) msg)))
      (dissoc acc :queue :n))))

(defn init
  "Initial world. Returns {:world :fx :problems :trace}."
  ([sys] (init sys nil))
  ([sys {:keys [context]}]
   (run-queue sys (absorb sys (acc0 {:app nil :instances {} :gen 0})
                          :app (fsm/initialize (:app sys) {:context context})))))

(defn dispatch
  "Process one UI action to completion. Pure. Returns {:world :fx :problems :trace}."
  [sys world action]
  (let [{:keys [to event]} (route sys action)]
    (run-queue sys (enqueue (acc0 world) {:to to :event event :via :action}))))

(defn deliver-reply
  "Process one effect reply to completion. `fx` is an entry from :fx ({:origin :gen …});
  `action` is the reply without instance key, e.g. [:req/succeeded data]. Pure."
  [sys world {:keys [origin gen]} action]
  (run-queue sys (enqueue (acc0 world) {:to origin :gen (when (not= origin :app) gen) :via :reply
                                        :event (sr/action->event action)})))

(defn- combine [r r2]
  (-> r2
      (update :problems #(into (vec (:problems r)) %))
      (update :trace #(into (vec (:trace r)) %))
      (update :fx #(into (vec (:fx r)) %))
      (update :emitted #(into (set (:emitted r)) %))
      (update :reached #(into (set (:reached r)) %))
      (assoc :worlds (:worlds r))))

(defn- drain-fx
  "Resolve pending effects with fake handlers: (fn [& args]) → reply action or nil."
  [sys effects r]
  (loop [r r]
    (if-let [[f & more] (seq (:fx r))]
      (let [r (assoc r :fx (vec more))
            [k & args] (:effect f)
            h (get effects k)]
        (cond
          (nil? h) (recur (problem r :warn :unhandled-effect {:instance (:origin f) :effect k}
                                   "add a fake for this effect to the scenario"))
          :else (if-let [reply (apply h args)]
                  (recur (combine r (deliver-reply sys (:world r) f reply)))
                  (recur r))))
      r)))

(defn- reply-one
  "Resolve the oldest pending effect with key k using its fake."
  [sys effects r k]
  (let [[before [f & after]] (split-with #(not= k (first (:effect %))) (:fx r))]
    (if-not f
      (problem r :warn :no-pending-effect {:effect k} "nothing pending with this key at this step")
      (let [r (assoc r :fx (vec (concat before after)))
            h (get effects k)
            reply (when h (apply h (rest (:effect f))))]
        (cond
          (nil? h) (problem r :warn :unhandled-effect {:instance (:origin f) :effect k}
                            "add a fake for this effect to the scenario")
          reply (combine r (deliver-reply sys (:world r) f reply))
          :else r)))))

(defn simulate
  "Run actions from the initial world with fake effects. Pure.
  Returns {:world :problems :trace :worlds :fx} (:worlds = after init and after
  each step; :fx = effects still pending).

  By default every effect is answered right away, so user actions never meet a
  request in flight. With {:manual? true} effects stay pending until a step
  answers them, which lets a scenario interleave:
    [::c/reply :fetch/prices]   answer the oldest pending :fetch/prices
    [::c/reply-all]             answer everything pending (and what that causes)

    (simulate sys [[:go]] {:effects {:fetch (fn [x] [:req/succeeded x])}})
    (simulate sys [[:load] [::c/reply :fetch/prices] [:retry] [::c/reply-all]]
              {:effects fakes :manual? true})"
  ([sys actions] (simulate sys actions nil))
  ([sys actions {:keys [effects context manual?]}]
   (let [settle (fn [r] (if manual? r (drain-fx sys effects r)))
         r0 (settle (init sys {:context context}))]
     (reduce (fn [r [t k :as step]]
               (let [r (cond
                         (= t ::reply) (reply-one sys effects r k)
                         (= t ::reply-all) (drain-fx sys effects r)
                         :else (settle (combine r (dispatch sys (:world r) step))))]
                 (update r :worlds conj (:world r))))
             (assoc r0 :worlds [(:world r0)])
             actions))))

;; ---------------------------------------------------------------------------
;; Views

(defn view-ctx
  "What a composed view receives: {:sys :world :app app-view-state}. Use the
  sr/ helpers on (:app vc) for the app chart, and the ones below for instances."
  [sys world]
  {:sys sys :world world :app (sr/view-state (:app sys) (:app world))})

(defn state
  "Chart state of instance `id` (or :app). Unused lazy instances read as initial."
  [vc id]
  (if (= id :app)
    (get-in vc [:world :app])
    (or (get-in vc [:world :instances id :state])
        (when (get-in vc [:sys :types (first id) :lazy?])
          (get-in vc [:sys :types (first id) :initial-state])))))

(defn in?
  "(in? vc [:req :prices] :loading)"
  [vc id path]
  (when-let [s (state vc id)]
    (contains? (sr/active-paths s) (if (keyword? path) [path] (vec path)))))

(defn fires?
  "Would this action fire now (guards included; placeholders → transition exists)?"
  [vc action]
  (let [{:keys [to event]} (route (:sys vc) action)
        s (state vc to)
        m (machine-of (:sys vc) to)]
    (boolean
      (and s m
           (if (sr/has-placeholders? action)
             (contains? (sr/accepted-events m s) (:type event))
             (sr/event-enabled? m s event))))))

(defn on
  "Like sr/on, for app or instance actions."
  ([vc handler] (on vc :click handler))
  ([vc dom-event handler]
   (let [[[event :as action]] (sr/actions-of handler)]
     (if (fires? vc action)
       {:on {dom-event handler} :data-event (str event)}
       {:disabled true :data-event (str event)}))))

(defn button
  "Like sr/button, for app or instance actions."
  ([vc handler label] (button vc {} handler label))
  ([vc attrs handler label]
   [(:tag attrs :button) (merge (dissoc attrs :tag) (on vc handler)) label]))

;; ---------------------------------------------------------------------------
;; Verify: wiring and views over scenarios

(defn- control-problems [sys vc hiccup]
  (for [{:keys [tag handler disabled?]} (sr/controls hiccup)
        :when handler
        :let [action (first (sr/actions-of handler))
              {:keys [to event]} (when action (route sys action))
              t (:type event)
              accepts (if (= to :app)
                        (sr/handled-events (:app sys))
                        (get-in sys [:types (first to) :accepts]))
              p (cond
                  (fn? handler)
                  {:problem :handler/function :fix "use action data, e.g. [:disc/toggle row-id]"}

                  (nil? action)
                  {:problem :handler/not-action :fix "handler data must be [:event …] or [[:event …] …]"}

                  (not (contains? accepts t))
                  {:problem :event/unknown :event t :instance to
                   :fix (if (= to :app)
                          (str "no app state handles " t "; a typo, or a component action missing its type namespace?")
                          (str "component " (first to) " has no event " t))}

                  (and (not disabled?) (not (fires? vc action)))
                  {:problem :control/dead :event t :instance to
                   :reason (cond (nil? (state vc to)) :no-instance
                                 (contains? (sr/accepted-events (machine-of sys to) (state vc to)) t) :guard
                                 :else :no-transition)
                   :fix "build the control with c/on or c/button so it disables itself"})]
        :when p]
    (assoc p :severity :error :tag tag)))

(defn check
  "Run scenarios and check the wiring and the views. Pure; returns problems, errors first.

    {:system sys
     :view (fn [vc] hiccup)                          ; optional
     :scenarios {:happy {:actions [[:go] …] :effects {:fetch (fn [& args] reply-or-nil)}}
                 :racy  {:manual? true :actions [[:go] [::c/reply :fetch] [:cancel] [::c/reply-all]] …}}}
  Manual scenarios (see `simulate`) are how you test actions that arrive while
  requests are in flight; automatic ones never produce that interleaving.

  Reports (each tagged with its scenario as :fixture):
  - every non-info problem from processing: :output-rejected (lost message),
    :send-rejected, :reply-rejected, :no-instance, :unknown-output, :wrong-target,
    :unhandled-effect, :rejected (UI action that can't fire), …
  - per rendered world: :view/threw, :handler/function, :handler/not-action,
    :event/unknown, :control/dead (with :reason :guard / :no-transition / :no-instance)
  - coverage over all scenarios (warnings): leaf states of the app and of each
    component type never reached; declared outputs never emitted."
  [{:keys [system view scenarios]}]
  (let [runs (for [[sname {:keys [actions effects context manual?]}] scenarios]
               [sname (try (simulate system actions {:effects effects :context context :manual? manual?})
                           (catch #?(:clj Throwable :cljs :default) e {:threw e}))])
        per-scenario
        (for [[sname r] runs
              p (if-let [e (:threw r)]
                  [{:severity :error :problem :scenario/threw :message (ex-message e)
                    :fix "see the message; a message loop or a throwing chart action"}]
                  (concat
                    (remove #(= :info (:severity %)) (:problems r))
                    (when view
                      (for [w (:worlds r)
                            :let [vc (view-ctx system w)
                                  rendered (try {:ok (view vc)}
                                                (catch #?(:clj Throwable :cljs :default) e {:error e}))]
                            p (if-let [e (:error rendered)]
                                [{:severity :error :problem :view/threw :message (ex-message e)
                                  :fix "make the view handle this world"}]
                                (control-problems system vc (:ok rendered)))]
                        p))))]
          (assoc p :fixture sname))
        ok-runs (remove (comp :threw second) runs)
        reached (into #{} (mapcat (comp :reached second)) ok-runs)
        emitted (into #{} (mapcat (comp :emitted second)) ok-runs)
        seen-by (fn [t] (set (keep (fn [[k p]] (when (= k t) p)) reached)))
        app-reached (seen-by :app)
        coverage
        (concat
          (for [p (sort-by str (sr/leaf-paths (:app system)))
                :when (not (contains? app-reached p))]
            {:severity :warn :problem :state/never-reached :instance :app :state-path p
             :fix "add a scenario that reaches this state"})
          (for [[t {:keys [machine outputs]}] (sort-by key (:types system))
                :let [seen (seen-by t)]
                p (concat
                    (when (seq seen)
                      (for [lp (sort-by str (sr/leaf-paths machine))
                            :when (not (contains? seen lp))]
                        {:severity :warn :problem :state/never-reached :instance t :state-path lp
                         :fix "add a scenario that drives an instance of this type there"}))
                    (when (empty? seen)
                      [{:severity :warn :problem :type/never-used :instance t
                        :fix "no scenario creates an instance of this type"}])
                    (for [o (sort outputs) :when (not (contains? emitted o))]
                      {:severity :warn :problem :output/never-emitted :instance t :event o
                       :fix "add a scenario where this output happens"}))]
            p))]
    (->> (concat per-scenario coverage)
         distinct
         (sort-by #(if (= :error (:severity %)) 0 1))
         vec)))

;; ---------------------------------------------------------------------------
;; Runtime

(defn- report! [{:keys [on-problem]} p]
  (cond
    (fn? on-problem) (on-problem p)
    (= on-problem :throw) (throw (ex-info (sr/format-problem p) p))
    (= on-problem :ignore) nil
    :else #?(:cljs (js/console.warn (sr/format-problem p))
             :clj (println (sr/format-problem p)))))

(defn make-app
  "{:system (system …) :effects {:effect/key (fn [reply & args] …)}
    :on-problem :warn (default) | :throw | :ignore | (fn [problem])}
  An effect handler calls (reply [:req/succeeded data]) zero or more times; the
  reply goes back to the instance (or app) that asked."
  [{:keys [system effects on-problem context] :or {on-problem :warn}}]
  (let [r (init system {:context context})]
    {:system system :effects effects :on-problem on-problem
     :!world (atom (:world r)) :pending (:fx r) :init-problems (:problems r)}))

(declare run-fx!)

(defn- apply-result! [app r]
  (reset! (:!world app) (:world r))
  (doseq [p (:problems r) :when (not= :info (:severity p))] (report! app p))
  (run-fx! app (:fx r)))

(defn- run-fx! [app fxs]
  (doseq [{:keys [origin effect] :as f} fxs]
    (let [[k & args] effect]
      (if-let [h (get (:effects app) k)]
        (apply h (fn [reply] (apply-result! app (deliver-reply (:system app) @(:!world app) f reply))) args)
        (report! app {:severity :error :problem :unknown-effect :instance origin :event k
                      :fix "add a handler for this effect to make-app :effects"})))))

(defn dispatch!
  "Dispatch handler data (one action or several) against the running app."
  ([app handler-data] (dispatch! app handler-data nil))
  ([app handler-data dom-event]
   (if-let [actions (sr/actions-of (sr/interpolate handler-data dom-event))]
     (doseq [a actions] (apply-result! app (dispatch (:system app) @(:!world app) a)))
     (report! app {:severity :error :problem :not-action-data :fix "handlers must be action data"}))))

(defn start!
  "Wire to Replicant: (start! app {:render r/render :set-dispatch! r/set-dispatch!
                                   :el el :view (fn [vc] hiccup)})"
  [app {:keys [render set-dispatch! el view]}]
  (let [paint (fn [w] (render el (view (view-ctx (:system app) w))))]
    (set-dispatch! (fn [event-data handler-data]
                     (dispatch! app handler-data (:replicant/dom-event event-data))))
    (add-watch (:!world app) ::render (fn [_ _ _ w] (paint w)))
    (paint @(:!world app))
    (doseq [p (:init-problems app) :when (not= :info (:severity p))] (report! app p))
    (run-fx! app (:pending app))
    app))
