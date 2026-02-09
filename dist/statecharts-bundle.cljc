(ns statecharts.utils)

(defn ensure-vector [x]
  (cond
    (vector? x)
    x

    (nil? x)
    []

    :else
    [x]))

(defn ensure-event-map [x]
  (if (map? x)
    x
    {:type x}))

(defn map-kv [f m]
  (->> m
       (map (fn [[k v]]
              (f k v)))
       (into (empty m))))

(defn map-vals [f m]
  (->> m
       (map (fn [[k v]]
              [k (f v)]))
       (into (empty m))))

(defn map-kv-vals [f m]
  (->> m
       (map (fn [[k v]]
              [k (f k v)]))
       (into (empty m))))

(defn remove-vals [pred m]
  (->> m
       (remove (fn [[_ v]]
                 (pred v)))
       (into (empty m))))

(defn find-first [pred coll]
  (->> coll
       (filter pred)
       first))

(defn with-index [coll]
  (map vector coll (range)))

(defn update-some
  "Like update, but only applies f when key k exists in map m."
  [m k f]
  (if (contains? m k)
    (update m k f)
    m))

(defn devectorize
  "Return the first element of x if x is a one-element vector."
  [x]
  (if (= 1 (count x))
    (first x)
    x))
(ns statecharts.clock)

(defprotocol Clock
  (getTimeMillis [this] "Return the current time in milliseconds")
  (setTimeout [this f delay])
  (clearTimeout [this id]))

#?(:cljs
   (deftype WallClock []
     Clock
     (getTimeMillis [this]
       (js/Date.now))
     (setTimeout [this f delay]
       (js/setTimeout f delay))
     (clearTimeout [this id]
       (js/clearTimeout id))))

;; TODO
#?(:clj
   (deftype WallClock []
     Clock
     (getTimeMillis [this]
       (System/currentTimeMillis))
     (setTimeout [this f delay])
     (clearTimeout [this id])))

(defn wall-clock []
  (WallClock.))

(def ^:dynamic ^Clock *clock*
  "The scheduler clock for the current fsm"
  nil)

(defn now
  ""
  []
  (assert *clock*)
  (getTimeMillis *clock*))
(ns statecharts.delayed
  (:require [clojure.walk :refer [postwalk]]
            [statecharts.utils :as u]))

(defprotocol IScheduler
  (schedule [this fsm state event delay])
  (unschedule [this fsm state event]))

(defn scheduler? [x]
  (satisfies? IScheduler x))

(def path-placeholder [:<path>])

(defn delay-fn-id [d]
  (if (int? d)
    d
    #?(:cljs (aget d "name")
       :clj (str (type d)))))

(defn generate-delayed-events [delay txs]
  (let [event [:fsm/delay
               path-placeholder
               ;; When the delay is a context function, after each
               ;; reload its value of change, causing the delayed
               ;; event can't find a match in :on keys. To cope with
               ;; this we extract the function name as the event
               ;; element instead.
               (delay-fn-id delay)]]
    ;; (def vd1 delay)
    {:entry {:action :fsm/schedule-event
             :event-delay delay
             :event event}
     :exit {:action :fsm/unschedule-event
            :event event}
     :on [event (mapv #(dissoc % :delay) txs)]}))

#_(generate-delayed-events 1000 [{:delay 1000 :target :s1 :guard :g1}
                                 {:delay 1000 :target :s2}])

#_(group-by odd? [1 2 3])

;; statecharts.impl/T_DelayedTransition
;; =>
#_[:map
   [:entry]
   [:exit]
   [:on]]
(defn derived-delay-info [delayed-transitions]
  (doseq [dt delayed-transitions]
    (assert (contains? dt :delay)
      (str "no :delay key found in" dt)))
  (->> delayed-transitions
       (group-by :delay)
       ;; TODO: the transition's entry/exit shall be grouped by delay,
       ;; otherwise a delay with multiple targets (e.g. with guards)
       ;; would result in multiple entry/exit events.
       (map (fn [[delay txs]]
              (generate-delayed-events delay txs)))
       (reduce (fn [accu curr]
                 (merge-with conj accu curr))
              {:entry [] :exit [] :on []})))

#_(derived-delay-info [:s1] [{:delay 1000 :target :s1 :guard :g1}
                             {:delay 2000 :target :s2}])

(defn insert-delayed-transitions
  "Translate delayed transitions into internal entry/exit actions and
  transitions."
  [node]
  ;; node
  (let [after (:after node)]
    (if-not after
      node
      (let [{:keys [entry exit on]} (derived-delay-info after)
            on (into {} on)
            vconcat (fn [xs ys]
                      (-> (concat xs ys) vec))]
        (-> node
            (update :entry vconcat entry)
            (update :exit vconcat exit)
            (update :on merge on))))))

(defn replace-path [path form]
  (if (nil? form)
    form
    (postwalk (fn [x]
                x
                (if (= x path-placeholder)
                  path
                  x))
              form)))

(defn replace-delayed-place-holder
  ([fsm]
   (replace-delayed-place-holder fsm []))
  ([node path]
   (let [replace-path (partial replace-path path)]
     (cond-> node
       (:on node)
       (update :on replace-path)

       (:entry node)
       (update :entry replace-path)

       (:exit node)
       (update :exit replace-path)

       (:states node)
       (update :states
               (fn [states]
                 (u/map-kv (fn [id node]
                             [id
                              (replace-delayed-place-holder node (conj path id))])
                           states)))

       (:regions node)
       (update :regions
               (fn [regions]
                 (u/map-kv (fn [id node]
                             [id
                              (replace-delayed-place-holder node (conj path id))])
                           regions)))))))

#_(replace-delayed-place-holder
 {:on {[:fsm/delay [:<path>] 1000] :s2}
  :states {:s3 {:on {[:fsm/delay [:<path>] 1000] :s2}
                :entry [{:fsm/type :schedule-event
                         :fsm/delay 1000
                         :fsm/event [:fsm/delay [:<path>] 1000]}]}}
  :entry [{:fsm/type :schedule-event
           :fsm/delay 1000
           :fsm/event [:fsm/delay [:<path>] 1000]}]} [:root])
(ns statecharts.impl
  (:require [clojure.set]
            [statecharts.clock :refer [*clock*]]
            [statecharts.delayed
             :as fsm.d
             :refer [insert-delayed-transitions
                     replace-delayed-place-holder]]
            [statecharts.utils :as u])
  (:refer-clojure :exclude [send]))

(defn canon-one-transition [x]
  (if (map? x)
    x
    {:target x}))

(defn canon-transitions [x]
  (cond
    (and (vector? x)
         (some map? x))
    (mapv canon-one-transition x)

    (map? x)
    [x]

    :else
    [{:target x}]))

(defn canon-actions [x]
  (if (vector? x)
    x
    [x]))

(defn canon-event [x]
  (if (map? x)
    x
    {:type x}))


(defn- normalize-transition [{:keys [actions] :as t}]
  (cond-> t
    actions (update :actions canon-actions)))

(defn decode-delayed-map [m]
  ;; {1000 :s1 2000 :s2}
  ;; =>
  ;; [{delay: 1000 :target :s1} {:delay 2000 :target :s2}]
  (->> m
       (mapcat (fn [[ms target]]
                 (->> target
                      canon-transitions
                      (map #(assoc % :delay ms)))))
       (into [])))

#_(decode-delayed-map {1000 :s1 2000 :s2})
#_(decode-delayed-map {1000 [{:target :s1
                              :cond :c1}
                             {:target :s2}]
                       2000 :s2})

(defn decode-delayed-transitions [x]
  (let [normalized (if (map? x)
                     (decode-delayed-map x)
                     x)]
    (mapv normalize-transition normalized)))


(defn insert-eventless-transitions [node]
  (let [always (:always node)]
    (if-not always
      node
      (update node :on assoc :fsm/always always))))

(declare validate-targets)
(declare normalize-states)

(defn- normalize-on [on]
  (when on
    (u/map-vals (fn [v] (mapv normalize-transition (canon-transitions v))) on)))

(defn- normalize-always [always]
  (when always
    (mapv normalize-transition (canon-transitions always))))

(defn- normalize-state [node]
  (-> node
      (u/update-some :entry canon-actions)
      (u/update-some :exit canon-actions)
      (u/update-some :on normalize-on)
      (u/update-some :after decode-delayed-transitions)
      (u/update-some :always normalize-always)
      (u/update-some :states normalize-states)
      (u/update-some :regions normalize-states)
      insert-delayed-transitions
      insert-eventless-transitions))

(defn- normalize-states [states]
  (u/map-vals normalize-state states))

(defn- normalize-machine [orig]
  (-> orig
      (u/update-some :entry canon-actions)
      (u/update-some :exit canon-actions)
      (u/update-some :on normalize-on)
      (u/update-some :after decode-delayed-transitions)
      (u/update-some :always normalize-always)
      (u/update-some :states normalize-states)
      (u/update-some :regions normalize-states)
      insert-delayed-transitions
      replace-delayed-place-holder))

(defn machine
  "Create a normalized, canonical representation of the machine spec."
  [orig]
  (let [conformed (normalize-machine orig)]
    (validate-targets conformed)
    conformed))

;; TODO: use deftype. We use defrecord because it has a handy
;; toString.
(defrecord ContextAssignment [v])

(defn assign
  "Wrap a function into a context assignment function."
  [f]
  (fn [& args]
    (ContextAssignment. (apply f args))))

(defn- internal-action? [action]
  (and (map? action)
       (= (some-> (:action action) namespace) "fsm")))

(defn- execute-internal-action
  [{:as fsm :keys [scheduler]}
   state
   transition-event
   {:as internal-action :keys [action event event-delay]}]
  (when-not scheduler
    (throw (ex-info
               "Delayed fsm without scheduler configured"
             {:action internal-action})))
  (cond
    (= action :fsm/schedule-event)
    (let [event-delay (if (int? event-delay)
                        event-delay
                        (event-delay state transition-event))]
      (fsm.d/schedule scheduler fsm state event event-delay))

    (= action :fsm/unschedule-event)
    (fsm.d/unschedule scheduler fsm state event)

    :else
    (throw (ex-info (str "Unknown internal action " action) internal-action))))

(defn- execute
  "Execute the actions/entry/exit functions when transitioning."
  ([fsm state event]
   (execute fsm state event nil))
  ([fsm state event {:keys [debug]}]
   (binding [*clock* (some-> (:scheduler fsm)
                             .-clock)]
     (reduce (fn [new-state action]
               (if (internal-action? action)
                 (do
                   (execute-internal-action fsm new-state event action)
                   new-state)
                 (let [retval (action new-state event)]
                   (if (instance? ContextAssignment retval)
                     (:v retval)
                     new-state))))
             (cond-> state
               (not debug)
               (dissoc :_actions))
             (:_actions state)))))

(defn- parallel? [node]
  (some-> (:type node)
          (= :parallel)))

(defn- compound? [node]
  (contains? node :initial))

(defn- atomic? [node]
  (and (not (parallel? node))
       (not (compound? node))))

(defn path->_state
  "Calculate the _state value based on the node paths.

  In our internal code, we need to represent the current state as a series of
  nodes, but when presenting the current state to the user we need to extract the
  simplest form."
  [xs]
  (let [indexed-xs (u/with-index (rest xs))
        ret (->> indexed-xs
                 (reduce
                   (fn [accu [node i]]
                     (if (parallel? node)
                       (let [para-state (u/map-vals path->_state
                                                    (:regions node))]
                         (if (zero? i)
                           ;; If the root is a para node: {:p1 :s1 :p2 :s2}
                           [para-state]
                           ;; If the non-root is a para node: {:p1 {:p2 :s2 :p3
                           ;; :s3}}
                           (update accu
                                   (dec i)
                                   (fn [id]
                                     {id para-state}))))
                       (conj accu (:id node))))
                   []))]
    (u/devectorize ret)))

(defn check-or-throw [x k v & {:keys [] :as map}]
  (when (nil? x)
    (throw (ex-info (str "Unknown fsm " (name k) " " v) (assoc map k v)))))


(defn resolve-target
  "Resolve the given transition target given the current state context.

  Rules for resolving the target:
  - If the target is nil, it's the same as the current state, a.k.a self-transition

  - If the target is a vector and the first element is :>, it's an absolute path

    (f :whatever [:> :s2]) => [:s2]

  - If the target is a vector and the first element is not :>, it's an relative path

    (f [:s1] [:s2]) => :s2
    (f [:s1 :s1.1] [:s1.2]) => [:s1 :s1.2]

  - If the target is a keyword, it's the same as an one-element vector

    (f [:s1] :s2) => :s2
    (f [:s1 :s1.1] :s1.2) => [:s1 :s1.2]

  - If the target is a vector and the first element is :., it's a
    child state of current node:

    (f [:s1] [:. :s1.1]) => [:s1 :s1.1]

  E.g. given current state [:s1 :s1.1] and a target of :s1.2, it
  should resolve to [:s1 :s1.2]"
  [base target]
  (let [base (u/ensure-vector base)
        parent (vec (drop-last base))]
    (cond
      (nil? target)
      base

      (keyword? target)
      (conj parent target)

      (not (sequential? target))
      (throw (ex-info "Invalid fsm target" {:target target}))

      (= (first target) :>)
      (vec (next target))

      (= (first target) :.)
      (vec (concat base (drop 1 target)))

      :else
      (vec (concat parent target)))))

(defn absolute-target? [target]
  (and (sequential? target)
       (= (first target) :>)))

(defn is-prefix? [short long]
  (let [n (count short)]
    (and (<= n (count long))
         (= short (take n long)))))

(defn external-self-transition-actions
  "Calculate the actions for an external self-transition.

  if handler is on [:s1 :s1.1]
  and current state is [:s1 :s1.1 :s1.1.1]
  then we shall exit s1.1.1 s1.1 and entry s1.1 s1.1.1 again

  if handler is on [:s1]
  and current state is [:s1 :s1.1 :s1.1.1]
  then we shall exit s1.1.1 s1.1 s1 and entry s1 s1.1 s1.1.1 again

  if handler is on [:s2]
  and current state is [:s2]
  then we shall exit s2 and entry s2 again

  if handler is on []
  and current state is [:s2]
  then we shall exit s2 Machine and entry Machine s2 again
  "
  [handler nodes])

(defn has-eventless-transition? [nodes]
  (boolean (some #(get-in % [:on :fsm/always]) nodes)))

(defn- updatev-last
  "Update the last element of a vector"
  [v f & args]
  (apply update v (dec (count v)) f args))

(defn add-node-type [node]
  (let [type (cond
               (parallel? node)
               :parallel

               (compound? node)
               :compound

               :else
               :atomic)]
    (assoc node :type type)))

(defn resolve-node
  ([root path]
   (resolve-node root path false))
  ([root path full?]
   ;; [:s1 :s1.1]
   (let [node (let [path (u/ensure-vector path)
                    node (reduce
                           (fn [current-root k]
                             (cond
                               (parallel? current-root)
                               (get-in current-root [:regions k])

                               (compound? current-root)
                               (get-in current-root [:states k])

                               :else
                               (reduced nil)))
                           root
                           path)]
                (some-> node
                        (add-node-type)
                        (assoc :path path)))]
     (if full?
       node
       (some-> node
               (select-keys [:on :entry :exit :type :path]))))))

(defn _state->nodes [_state]
  (loop [[head & more] (u/ensure-vector _state)
         prefix []
         ret (sorted-set)]
    (cond
      (keyword? head)
      (let [current (conj prefix head)
            ret (conj ret current)]
        (if (seq more)
          (recur more current ret)
          ret))

      (map? head)
      (do
        (assert (empty? more)
          "invalid _state, parallel state must be the last one")
        (into ret (mapcat (fn [[k v]]
                            (let [prefix (conj prefix k)]
                              (cons prefix
                                    (map #(into prefix %) (_state->nodes v)))))
                          head))))))

(defn _state->configuration
  ;; We always resolve the `_state` in a JIT manner, so the user has the
  ;; flexibility to pass in a literal `_state` (and context) as data, instead of
  ;; forcing the user to keep track of an opaque "State" object like xstate.
  ;;
  ;; E.g. the user pass in `[:s1 :s1.1]` then we would get a set of two nodes:
  ;; - `[:s1]`
  ;; - `[:s1 :s1.1]`
  [fsm _state &
   {:keys [no-resolve?]
    :as _opt}]
  (let [_state (u/ensure-vector _state)]
    (let [paths (conj (_state->nodes _state) [])
          ;; Always reslove to ensure all nodes are resolvable in the fsm. TODO:
          ;; throw an exc here if resolve-node returns nil?
          nodes (mapv #(resolve-node fsm %) paths)]
      (if no-resolve?
        paths
        nodes))))

(defn backtrack-ancestors-as-paths
  "Return a (maybe lazy) sequence of the node path with all its ancestors, starting from the
  node and goes up."
  [fsm path]
  (reductions (fn [accu _]
                (vec (drop-last accu)))
              path
              (range (count path))))

(defn backtrack-ancestors-as-nodes
  "Like backtrack-ancestors-as-paths but resolves the paths into nodes."
  [fsm path]
  ;; [:s1 :s1.1 :s1.1.1]
  (->> (backtrack-ancestors-as-paths fsm path)
       (map #(resolve-node fsm %))))

(defn find-least-common-compound-ancessor [fsm path1 path2]
  (u/find-first (fn [anc]
                  (is-prefix? anc path1))
                (backtrack-ancestors-as-paths fsm path2)))

(defn get-tx-domain
  [fsm {:keys [source target] :as tx}]
  (cond
    ;; internal self transition
    (nil? target)
    nil

    ;; external self transition
    (= source target)
    source

    :else
    (find-least-common-compound-ancessor fsm source target)))

(defn select-one-tx
  "Given an atomic node and an event, find the first satistifed transition by
  walking from the node and then its ancestors, until the root.

  Return a two-tuple:
  - The first element is the a boolean indicates whether any transition is found at
    all (regarding it's satisfied or not)
  - The second element is the found transition, if any.
  "
  [fsm
   {:keys [path]
    :as node} state
   {:keys [type]
    :as event} input-event]
  (let [first-satisfied-tx (fn [txs]
                             (some (fn [{:keys [guard]
                                         :as tx}]
                                     (when (or (not guard)
                                               (guard state input-event))
                                       (dissoc tx :guard)))
                                   txs))
        found (volatile! false)
        tx (when-let [{:keys [source target]
                       :as tx}
                      (some (fn [{:keys [path]
                                  :as node}]
                              (when-let [txs (seq (get-in node [:on type]))]
                                (vreset! found true)
                                (when-let [tx (first-satisfied-tx txs)]
                                  (assoc tx :source path))))
                            (backtrack-ancestors-as-nodes fsm (:path node)))]
             (let [target-resolved (when target
                                     (resolve-target source target))
                   tx (-> tx
                          (assoc :target target-resolved)
                          (assoc :external? (or (absolute-target? target)
                                                (= target-resolved source))))]
               (assoc tx :domain (get-tx-domain fsm tx))))]
    [@found tx]))

(defn get-initial-path [{:keys [path initial] :as _node}]
  (let [initial (u/ensure-vector initial)
        initial (if (= (first initial) :.)
                  (next initial)
                  initial)]
    (into path initial)))

(defn add-ancestors-to-entry-set
  [fsm domain path external?]
  (->> (backtrack-ancestors-as-paths fsm path)
       (take-while (fn [path]
                     (and (not= path [])
                          ;; exclude the domain node when not external,
                          (or external?
                              (not= domain path))
                          (is-prefix? domain path))))))

(defn compute-entry-set
  [fsm txs]
  (let [get-tx-entry-set
        (fn [{:keys [target domain external?]
              :as _tx}]
          ;; target=nil means internal self-transtion, where no entry/exit would
          ;; happen
          (when target
            (loop [entry-set #{target}
                   seeds entry-set]
              (let [exist? #(contains? entry-set %)
                    new
                    (->>
                      seeds
                      (map #(resolve-node fsm % true))
                      (map
                        (fn [{:keys [type path]
                              :as node}]
                          (remove exist?
                            (concat
                              (add-ancestors-to-entry-set fsm
                                                          domain
                                                          path
                                                          external?)
                              (case type
                                :parallel
                                (let [regions
                                      (->> (:regions node)
                                           keys
                                           (map #(conj path %)))]
                                  regions)

                                :compound
                                ;; for compound node that has no descedents in the
                                ;; entry set, add its initial state to the next
                                ;; seeds of next round of iteration.
                                (when-not (some (fn [x]
                                                  (and (not= path x)
                                                       (is-prefix? path x)))
                                                entry-set)
                                  [(get-initial-path node)])

                                ;; an atomic node, nothing to add for it.
                                nil)))))
                      (reduce concat))

                    new (clojure.set/difference (set new) entry-set)]
                (if-not (empty? new)
                  (recur
                    ;; include the new nodes
                    (into entry-set new)
                    ;; new the new nodes in this iteration as the new seeds
                    new)
                  entry-set)))))]
    (->> (map get-tx-entry-set txs)
         (reduce into (sorted-set)))))

(defn get-actions [fsm path k]
  (let [node (resolve-node fsm path)]
    (k node)))

(defn get-entry-actions [fsm entry-set]
  (->> entry-set
       (mapcat #(get-actions fsm % :entry))))

(defn simple-state [x]
  (if (and (sequential? x)
           (= (count x) 1))
    (first x)
    x))

(defn configuration->_state
  "Represent the current configuration in a user-friendly form. It's the reverse
  operation of `_state->configuration`.
  "
  [fsm configuration]
   (-> (loop [paths configuration
              node fsm
              _state []
              parent-compound? false]
         (let [paths (into [] (remove empty? paths))]
           (cond
             (parallel? node)
             (let [children (:regions node)
                   groups (group-by first paths)
                   parallel-state
                   (u/map-kv-vals (fn [k region]
                                    (configuration->_state region
                                                           (map
                                                             ;; remove the common
                                                             ;; prefix
                                                             next
                                                             (get groups k))))
                                  children)]
               ;; If the parent node of the parallel node is a compound node (i.e.
               ;; the parallel node is not the root), the notation is a
               ;; single-valued map, e.g. [:s1 {:s1.1 {:p1 :x :p2 :y}}]
               (if parent-compound?
                 (updatev-last _state
                               (fn [k]
                                 {k parallel-state}))
                 parallel-state))

             (compound? node)
             (do
               (let [ks (set (map first paths))
                     k (first ks)]
                 (assert (= (count ks) 1) (str "invalid paths: " paths))
                 (let [paths (remove empty? (map next paths))]
                   (if (seq paths)
                     (recur
                       paths
                       (get-in node [:states k])
                       (conj _state k)
                       true)
                     (conj _state k)))))

             :else
             ;; some atomic node
             (conj _state (ffirst paths)))))
       simple-state))

(defn -do-transition
  [fsm
   {:keys [_state]
    :as state} event input-event
   ignore-unknown-event?]
  (let [configuration (_state->configuration fsm _state)
        atomic-nodes (filter #(= (:type %) :atomic) configuration)
        txs (->> atomic-nodes
                 (map #(select-one-tx fsm % state event input-event)))
        _ (when-not ignore-unknown-event?
            (when-not (->> txs
                         (map first)
                         (some identity))
              (throw (ex-info (str "fsm " (:id fsm) " got unknown event " (:type event) " when in state " _state)
                              {:_state _state}))))
        txs (->> txs
                 (map second)
                 (remove nil?))]
    (if (not (seq? txs))
      [_state [] false]
      (let [exit-set (->> configuration
                          ;; all active nodes that is covered by some tx domain
                          ;; should
                          ;; exit itself.
                          (filter (fn [{:keys [path]
                                        :as node}]
                                    (some
                                      (fn [{:keys [target domain external?]
                                            :as tx}]
                                        (cond
                                          (= path [])
                                          ;; only exit the root when the target is
                                          ;; the root itself
                                          (and external?
                                               (= target []))

                                          (= domain path)
                                          ;; only include the domain itself
                                          ;; when it's an external transition
                                          external?

                                          :else
                                          (is-prefix? domain path)))
                                      txs)))
                          (map :path)
                          (into (sorted-set)))
            entry-set (compute-entry-set fsm txs)
            exit-actions (->> exit-set
                              reverse
                              (mapcat #(get-actions fsm % :exit)))
            entry-actions (get-entry-actions fsm entry-set)
            tx-actions (->> txs
                            (mapcat :actions))
            actions (concat exit-actions tx-actions entry-actions)
            ;; _ #p exit-set
            ;; _ #p entry-set
            new-configuration (-> (->> (map :path configuration)
                                       (into #{}))
                                  (clojure.set/difference exit-set)
                                  (clojure.set/union entry-set))
            new-value (configuration->_state fsm new-configuration)]
        [new-value actions
         (has-eventless-transition? (map #(resolve-node fsm %)
                                      entry-set))]))))

(defn -do-init
  [fsm]
  (let [tx                     {:source    []
                                :target    []
                                :external? true
                                :domain    []}
        entry-set              (compute-entry-set fsm [tx])
        entry-actions          (get-entry-actions fsm entry-set)
        _state                 (configuration->_state fsm entry-set)
        _pending-eventless-tx? (has-eventless-transition?
                                 (map #(resolve-node fsm %)
                                      entry-set))]
    [_state entry-actions _pending-eventless-tx?]))

(declare transition)

(defn initialize
  ([fsm]
   (initialize fsm nil))
  ([{:keys [initial type]
     :as fsm}
    {:keys [exec debug context]
     :or {exec true
          context nil}
     :as _opts}]
   (let [default-context (:context fsm)
         context (if (some? context)
                   (if (and (map? default-context)
                            (map? context))
                     (merge default-context context)
                     context)
                   (:context fsm))
         event {:type :fsm/init}
         [_state actions _pending-eventless-tx?] (-do-init fsm)
         state (assoc context
                      :_state _state
                      :_actions actions)
         new-state (if exec
                     (execute fsm state event {:debug debug})
                     state)]
     (if-not _pending-eventless-tx?
       new-state
       (transition fsm new-state :fsm/always {:exec exec :debug debug})))))

(defn -transition-once
  "Do the transition, but would not follow new eventless transitions defined on
  the target state."
  [fsm state event
   {:keys [exec debug input-event ignore-unknown-event?]
    :or {exec true}}]
  (let [;; input-event is set to the original event when event is :fsm/always for
        ;; eventless transitions. We pass both along because even in eventless
        ;; transitions, the actions function may want to access the original event
        ;; instead of :fsm/always
        input-event
        (or input-event event)

        [new-value actions _pending-eventless-tx?]
        (-do-transition fsm
                        state
                        event
                        input-event
                        ignore-unknown-event?)

        new-state (assoc state
                    :_state new-value
                    :_pending-eventless-tx? _pending-eventless-tx?
                    :_prev-state (:_state state)
                    :_actions actions)]
    (if exec
      (execute
        fsm
        new-state
        input-event
        {:debug debug})
      new-state)))

(defn -transition-impl
  "Return the new state and the actions to execute."
  [fsm state input-event opts]
  ;; The loop is used to execute eventless transitions.
  (loop [i 0
         state (dissoc state :_actions)
         actions []]

    (when (> i 10)
      ;; Prevent bugs in application's code that two states uses eventless
      ;; transitions and the states jumps back and forth between them.
      (throw (ex-info (str "Possible dead loop on event" (:type input-event))
                      {:state (:_state state)})))

    (let [event
          (if (zero? i)
            input-event
            ;; The first iteration of the loop is the real input event, while the
            ;; following ones are eventless transitions.
            {:type :fsm/always})

          {:keys [_actions _pending-eventless-tx?]
           :as state}
          (-transition-once fsm state event opts)

          actions
          (if _actions
            (into actions _actions)
            actions)]
      (if _pending-eventless-tx?
        (recur (inc i) (dissoc state :_pending-eventless-tx?) actions)
        [state actions]))))

(defn transition
  "Given a machine with its current state, trigger a transition to the
  next state based on the given event.

  The nature and purpose of the transition impl is to get two outputs:
  - the new state
  - the actions to execute

  By default it executes all actions, unless the `exec` opt is false,
  in which case it is a pure function."
  ([fsm state event]
   (transition fsm state event nil))
  ([fsm state event
    {:as opts
     :keys [exec debug]
     :or {exec true}}]
   (let
     [input-event
      (canon-event event)

      opts
      (assoc opts :input-event input-event)

      [new-state actions]
      (-transition-impl
        fsm
        (dissoc state :_actions)
        input-event
        opts)]
     ;; get rid of the internal fields
     (cond-> (dissoc new-state :_pending-eventless-tx? :_prev-state)
       (or (not exec) debug)
       (assoc :_actions actions)))))

(defn- valid-target? [node path]
  (try
    (when-not (resolve-node node path)
      (throw (ex-info "node not found" {:path path ::type :invalid-path})))
    true
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
      (if (= (::type (ex-data e)) :invalid-path)
        false
        (throw e)))))

(defn validate-targets
  "Walk the fsm and try to resolve all transition targets. Raise an
  exception if any target is invalid."
  ([root]
   (validate-targets root root []))
  ([root node current-path]
   (do
     (let [transitions (mapcat identity (-> node :on vals))
           targets (->> transitions
                        (map :target)
                        ;; nil target target means self-transition,
                        ;; which is always valid.
                        (remove nil?))]
       (when (seq targets)
         (doseq [target targets
                 :let [target (resolve-target current-path target)]]
           (when-not (valid-target? root target)
             (throw (ex-info (str "Invalid target " target)
                      {:target target :state current-path}))))))
     (when-let [initial (:initial node)]
       (let [initial-node (get-in node [:states initial])]
         (when-not initial-node
           (throw (ex-info (str "Invalid initial target " initial)
                    {:initial initial :state current-path})))))
     (doseq [[name child] (:states node)]
       (validate-targets root child (conj current-path name)))
     (when (parallel? node)
       (doseq [[name child] (:regions node)]
         (validate-targets root child (conj current-path name)))))))

(defn matches [state value]
  (let [v1 (u/ensure-vector (:value state))
        v2 (u/ensure-vector value)]
    (is-prefix? v2 v1)))

(ns statecharts.store
  "This namespace provides an interface for a mutable datastore for one or more
  states.

  It is used in places that are concerned with storing states as they change _over
  time_. So, [[statecharts.service]], which notifies listeners when a state
  changes, and [[statecharts.integrations.re-frame]], which persists state changes
  in a re-frame app-db, both use it. It is also used in [[statecharts.scheduler]],
  which is concerned with scheduling state changes that will happen at some future
  time."
  (:require [statecharts.impl :as impl]))

(defprotocol IStore
  (unique-id [this state]
    "Get the id that the store uses to identify this state.")
  (initialize [this machine opts]
    "Initialize a state, and save it in the store.")
  (transition [this machine state event opts]
    "Transition a state previously saved in the store. Save and return its new value.")
  (get-state [this id]
    "Get the current value of a state, by its id."))


(defrecord SingleStore [state*]
  IStore
  (unique-id [_ _state] :context)
  (initialize [_ machine opts]
    (reset! state* (impl/initialize machine opts)))
  (transition [_ machine _state event opts]
    (swap! state* #(impl/transition machine % event opts)))
  (get-state [_ _]
    @state*))

(defn single-store
  "A single-store stores the current value of a single state."
  []
  (SingleStore. (atom nil)))

(defrecord ManyStore [states* id]
  IStore
  (unique-id [_ state] (get state id))
  (initialize [this machine opts]
    (let [state (-> (impl/initialize machine opts)
                    (update id #(or % (gensym))))]
      (swap! states* assoc (unique-id this state) state)
      state))
  (transition [this machine state event opts]
    (let [state-id (unique-id this state)]
      (swap! states* update state-id #(impl/transition machine % event opts))
      (get-state this state-id)))
  (get-state [_ id]
    (get @states* id)))

(defn many-store
  "A many-store stores the current values of many states.

  The `opts` provided to `init` should configure the state to have a unique id.
  This ensures that the state can be identified and transitioned later, by both
  external and scheduled events.

  By default, a many-store expects the id to be `:id`.
  ```clojure
  (let [store (store/many-store)]
    (store/initialize store fsm {:context {:id 1}})
    (store/get-state store 1))
  ```
  The id can be configured by providing an `:id` key in the many-store
  configuration options.
  ```clojure
  (let [store (store/many-store {:id :my-id})]
    (store/initialize store fsm {:context {:my-id 1}})
    (store/get-state store 1))
  ```
  "
  ([] (many-store {}))
  ([{:keys [id] :or {id :id}}]
   (ManyStore. (atom {}) id)))
(ns statecharts.scheduler
  (:require [statecharts.store :as store]
            [statecharts.delayed :as delayed]
            [statecharts.clock :as clock]))

(deftype Scheduler [dispatch timeout-ids clock]
  delayed/IScheduler
  (schedule [_ fsm state event delay]
    (let [timeout-id (clock/setTimeout clock #(dispatch fsm state event) delay)]
      (swap! timeout-ids assoc event timeout-id)))

  (unschedule [_ _fsm _state event]
    (when-let [timeout-id (get @timeout-ids event)]
      (clock/clearTimeout clock timeout-id)
      (swap! timeout-ids dissoc event))))

(defn ^{:deprecated "0.1.2"} make-scheduler
  "DEPRECATED: Use [[make-store-scheduler]] instead.

  If we are scheduling events, we must be saving them somewhere, implying that we
  have a store. make-store-scheduler is a neater combination of those
  responsibilities: transition and save."
  ([dispatch clock]
   (Scheduler. dispatch (atom {}) clock)))

(deftype StoreScheduler [store timeout-ids clock]
  delayed/IScheduler
  (schedule [_ fsm state event delay]
    (let [state-id   (store/unique-id store state)
          timeout-id (clock/setTimeout clock #(store/transition store fsm state event nil) delay)]
      (swap! timeout-ids assoc-in [state-id event] timeout-id)))
  (unschedule [_ _fsm state event]
    (let [state-id   (store/unique-id store state)
          timeout-id (get-in @timeout-ids [state-id event])]
      (when timeout-id
        (clock/clearTimeout clock timeout-id)
        (swap! timeout-ids update state-id dissoc event)))))

(defn make-store-scheduler
  "Returns a scheduler that can be used to [[statecharts.delayed/schedule]] events
  afer some delay. The `store`, which is a `statecharts.store/IStore` contains the
  current values of the states, and will be updated as those states are
  transitioned by the scheduled events. The `clock` is part of the delay
  mechanism."
  ([store clock]
   (StoreScheduler. store (atom {}) clock)))
(ns statecharts.sim
  (:require [statecharts.clock :refer [Clock setTimeout clearTimeout]]))

(defprotocol ISimulatedClock
  (now [this])
  (events [this])
  (advance [this ms]))

(deftype SimulatedClock [events
                         ^:volatile-mutable id
                         ^:volatile-mutable now_]
  Clock
  (getTimeMillis [_]
    now_)
  (setTimeout [_ f delay]
    (set! id (inc id))
    (swap! events assoc id {:f f :event-time (+ now_ delay)})
    id)

  (clearTimeout [_ id]
    (swap! events dissoc id)
    nil)

  ISimulatedClock
  (now [this]
    now_)
  (events [this]
    @events)
  (advance [this ms]
    (set! now_ (+ now_ ms))
    (doseq [[id {:keys [f event-time]}] @events]
      (if (>= now_ event-time)
        (do (f)
            (clearTimeout this id))
        [:not-yet now_ event-time f]))))

(defn simulated-clock []
  (SimulatedClock. (atom nil) 0 0))


(comment
  (def simclock (simulated-clock))
  (setTimeout simclock #(println :e1) 1000)
  (setTimeout simclock #(println :e2) 2000)
  (events simclock)
  (advance simclock 1000)
  (now simclock)
  ())
(ns statecharts.service
  (:require [statecharts.clock :as clock]
            [statecharts.store :as store]
            [statecharts.scheduler :as scheduler])
  (:refer-clojure :exclude [send]))

(defn attach-fsm-scheduler [fsm store clock]
  (assoc fsm :scheduler (scheduler/make-store-scheduler store clock)))

(defprotocol IService
  (start [this])
  (send [this event])
  (state [this])
  (add-listener [this id listener])
  (reload [this fsm]))

(defn wrap-listener [f]
  (fn [_ _ old new]
    (f old new)))

(deftype Service [^:volatile-mutable fsm
                  store
                  ^:volatile-mutable running
                  clock
                  transition-opts]
  IService
  (start [this]
    (when-not running
      (set! running true)
      (store/initialize store fsm nil)))
  (state [this]
    (store/get-state store nil))
  (send [_ event]
    (let [old-state (store/get-state store nil)]
      (store/transition store fsm old-state event transition-opts))
    (store/get-state store nil))
  (add-listener [_ id listener]
    ;; Kind of gross to reach down into the store's internals. Then again, the fact
    ;; that the store is a single-store is an implementation detail known only to
    ;; this namespace.
    (add-watch (:state* store) id (wrap-listener listener)))
  (reload [this fsm_]
    (set! fsm (attach-fsm-scheduler fsm_ store clock))))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn service
  ([fsm]
   (service fsm nil))
  ([fsm opts]
   (let [{:keys [clock
                 transition-opts]} (merge (default-opts) opts)
         store (store/single-store)]
     (Service. (attach-fsm-scheduler fsm store clock)
               ;; state store
               store
               ;; running
               false
               clock
               transition-opts))))
(ns statecharts.core
  (:require [statecharts.impl :as impl]
            [statecharts.service :as service]
            [statecharts.utils :refer [ensure-vector]])
  (:refer-clojure :exclude [send]))

(def machine impl/machine)
(def initialize impl/initialize)
(def transition impl/transition)
(def assign impl/assign)

(def service service/service)
(defn start [service]
  (service/start service))
(defn reload [service fsm]
  (service/reload service fsm))

(defn send
  ([service event]
   (send service event nil))
  ([service event _]
   (service/send service event)))

(defn state [service]
  (service/state service))

(defn value [service]
  (-> service state :_state))

(defn matches [state value]
  (let [v1 (ensure-vector
            (if (map? state)
              (:_state state)
              state))
        v2 (ensure-vector value)]
    (impl/is-prefix? v2 v1)))

(defn update-state
  "Provide a pathway to modify the state of a state machine directly
  without going through any event.

  Return the updated context.
  "
  [^statecharts.service.Service service f & args]
  (let [state (.-state service)]
    (swap! state #(apply f % args))))
(ns statecharts.viz
  "Convert clj-statecharts machine definitions to XState v5 JSON
   for visualization at https://stately.ai/viz"
  (:require [statecharts.impl :as impl]
            [clojure.string :as str]))

(defn- kw->str
  "Convert a keyword to a string, stripping the leading colon.
   Returns non-keywords as-is via str."
  [x]
  (if (keyword? x)
    (name x)
    (str x)))

(defn- action-label
  "Extract a human-readable label from an action value.
   Keywords become their string name; ContextAssignment records
   become \"assign\"; functions get their name or a fallback."
  [x fallback]
  (cond
    (keyword? x)
    (kw->str x)

    (instance? impl/ContextAssignment x)
    "assign"

    (map? x)
    ;; Internal actions like {:action :fsm/schedule-event ...}
    (if-let [a (:action x)]
      (kw->str a)
      fallback)

    (fn? x)
    ;; Try calling with dummy args to detect assign-wrapped functions.
    ;; assign returns a fn that produces a ContextAssignment record.
    (let [assign? (try
                    (instance? impl/ContextAssignment (x {} {}))
                    (catch #?(:clj Exception :cljs js/Error) _ false))]
      (if assign?
        "assign"
        fallback))

    (string? x)
    x

    :else
    fallback))

(defn- convert-actions
  "Convert entry/exit/actions to a vector of string labels.
   Handles single values, vectors, and nil."
  [actions fallback]
  (when actions
    (let [actions (if (sequential? actions) actions [actions])]
      (mapv #(action-label % fallback) actions))))

(defn- convert-guard
  "Convert a guard to a string label."
  [guard]
  (when guard
    (action-label guard "guard")))

(defn- convert-one-transition
  "Convert a single transition. Input can be:
   - a keyword target: :s2
   - a map: {:target :s2 :guard fn :actions [...]}
   Returns an XState-compatible map."
  [t]
  (cond
    (keyword? t)
    {"target" (kw->str t)}

    (map? t)
    (let [{:keys [target guard actions]} t]
      (cond-> {}
        target  (assoc "target" (if (sequential? target)
                                  ;; Absolute/relative path targets like [:> :s1 :s1a]
                                  ;; XState uses dot notation: "#machine.s1.s1a"
                                  ;; For simplicity, just use the last segment
                                  (kw->str (last target))
                                  (kw->str target)))
        guard   (assoc "guard" (convert-guard guard))
        actions (assoc "actions" (convert-actions actions "action"))))

    :else
    {"target" (str t)}))

(defn- convert-transitions
  "Convert a transition value which may be:
   - a keyword: :target
   - a map: {:target :s :guard fn}
   - a vector of maps: [{:target :s1 :guard g1} {:target :s2}]
   Returns the XState representation."
  [v]
  (cond
    (keyword? v)
    (kw->str v)

    (and (vector? v) (some map? v))
    (mapv convert-one-transition v)

    (map? v)
    (convert-one-transition v)

    :else
    (str v)))

(defn- convert-on
  "Convert the :on event map."
  [on]
  (when on
    (->> on
         ;; Filter out internal events
         (remove (fn [[k _]] (and (keyword? k)
                                  (= (namespace k) "fsm"))))
         (map (fn [[event-kw transitions]]
                [(kw->str event-kw) (convert-transitions transitions)]))
         (into {}))))

(defn- convert-after
  "Convert :after delayed transitions.
   Input formats:
   - map: {1000 :s1, 2000 :s2}
   - vector: [{:delay 1000 :target :s1}]"
  [after]
  (when after
    (cond
      (map? after)
      (->> after
           (map (fn [[delay-ms target]]
                  [(str delay-ms) (convert-transitions target)]))
           (into {}))

      (sequential? after)
      (->> after
           (map (fn [{:keys [delay target guard actions]}]
                  [(str (or delay "0"))
                   (cond-> {}
                     target  (assoc "target" (kw->str target))
                     guard   (assoc "guard" (convert-guard guard))
                     actions (assoc "actions" (convert-actions actions "action")))]))
           (into {}))

      :else
      {"0" (convert-transitions after)})))

(defn- convert-always
  "Convert :always eventless transitions."
  [always]
  (when always
    (cond
      (keyword? always)
      {"target" (kw->str always)}

      (map? always)
      (convert-one-transition always)

      (sequential? always)
      (mapv convert-one-transition always)

      :else
      (str always))))

(declare convert-state)

(defn- convert-states
  "Convert a :states map recursively."
  [states]
  (when states
    (->> states
         (map (fn [[state-kw state-def]]
                [(kw->str state-kw) (convert-state state-def)]))
         (into {}))))

(defn- convert-state
  "Convert a single state node. Handles :states, :regions, :on, :entry, :exit,
   :after, :always, :initial, :type recursively."
  [node]
  (when (map? node)
    (let [{:keys [initial type states regions on entry exit after always]} node
          parallel? (or (= type :parallel) (some? regions))
          result {}
          result (if initial
                   (assoc result "initial" (kw->str initial))
                   result)
          result (if parallel?
                   (assoc result "type" "parallel")
                   result)
          ;; States: either :states or :regions (for parallel)
          child-states (or states regions)
          result (if child-states
                   (assoc result "states" (convert-states child-states))
                   result)
          result (if on
                   (let [converted (convert-on on)]
                     (if (seq converted)
                       (assoc result "on" converted)
                       result))
                   result)
          result (if entry
                   (assoc result "entry" (convert-actions entry "entry"))
                   result)
          result (if exit
                   (assoc result "exit" (convert-actions exit "exit"))
                   result)
          result (if after
                   (assoc result "after" (convert-after after))
                   result)
          result (if always
                   (assoc result "always" (convert-always always))
                   result)]
      result)))

(defn machine->xstate
  "Convert a clj-statecharts machine definition map to an XState v5
   compatible Clojure map. Works with both user-facing input maps
   and normalized (post fsm/machine) maps.

   Functions (guards, actions, entry/exit) are converted to string labels
   since they can't be serialized to JSON."
  [machine-def]
  (let [{:keys [id initial type context states regions on entry exit after always]} machine-def
        parallel? (or (= type :parallel) (some? regions))
        result {}
        result (if id
                 (assoc result "id" (kw->str id))
                 result)
        result (if initial
                 (assoc result "initial" (kw->str initial))
                 result)
        result (if parallel?
                 (assoc result "type" "parallel")
                 result)
        result (if context
                 (assoc result "context" context)
                 result)
        child-states (or states regions)
        result (if child-states
                 (assoc result "states" (convert-states child-states))
                 result)
        result (if on
                 (let [converted (convert-on on)]
                   (if (seq converted)
                     (assoc result "on" converted)
                     result))
                 result)
        result (if entry
                 (assoc result "entry" (convert-actions entry "entry"))
                 result)
        result (if exit
                 (assoc result "exit" (convert-actions exit "exit"))
                 result)
        result (if after
                 (assoc result "after" (convert-after after))
                 result)
        result (if always
                 (assoc result "always" (convert-always always))
                 result)]
    result))

;;; --- Mermaid State Diagram Converter ---

(declare mermaid-state-lines)

(defn- mermaid-transition-label
  "Build a transition label string. Includes event name, guard, and actions.
   Avoids square brackets which Mermaid interprets as state descriptions."
  [event-str guard-str action-strs]
  (let [parts (cond-> []
                event-str       (conj event-str)
                guard-str       (conj (str "‹" guard-str "›"))
                (seq action-strs) (conj (str "/ " (str/join ", " action-strs))))]
    (str/join " " parts)))

(defn- mermaid-one-transition-lines
  "Generate Mermaid lines for one transition from source-id with indent."
  [indent source-id event-name t]
  (cond
    (keyword? t)
    [(str indent source-id " --> " (kw->str t) " : " event-name)]

    (string? t)
    [(str indent source-id " --> " t " : " event-name)]

    (map? t)
    (let [{:keys [target guard actions]} t
          target-str (when target
                       (if (sequential? target)
                         (kw->str (last target))
                         (kw->str target)))
          guard-str (convert-guard guard)
          action-strs (convert-actions actions "action")
          label (mermaid-transition-label event-name guard-str action-strs)]
      (if target-str
        [(str indent source-id " --> " target-str " : " label)]
        [(str indent source-id " --> " source-id " : " label)]))

    :else
    [(str indent source-id " --> " (str t) " : " event-name)]))

(defn- mermaid-on-lines
  "Generate Mermaid transition lines from :on map for a given source state."
  [indent source-id on]
  (when on
    (->> on
         (remove (fn [[k _]] (and (keyword? k) (= (namespace k) "fsm"))))
         (mapcat (fn [[event-kw transitions]]
                   (let [event-name (kw->str event-kw)]
                     (cond
                       (keyword? transitions)
                       (mermaid-one-transition-lines indent source-id event-name transitions)

                       (and (vector? transitions) (some map? transitions))
                       (mapcat #(mermaid-one-transition-lines indent source-id event-name %) transitions)

                       (map? transitions)
                       (mermaid-one-transition-lines indent source-id event-name transitions)

                       :else
                       [(str indent source-id " --> " (kw->str transitions) " : " event-name)])))))))

(defn- mermaid-after-lines
  "Generate Mermaid lines for :after delayed transitions."
  [indent source-id after]
  (when after
    (cond
      (map? after)
      (mapcat (fn [[delay-ms target]]
                (let [label (str "after " delay-ms "ms")]
                  (cond
                    (keyword? target)
                    [(str indent source-id " --> " (kw->str target) " : " label)]

                    (map? target)
                    (let [t-str (some-> (:target target) kw->str)]
                      [(str indent source-id " --> " (or t-str source-id) " : " label)])

                    (and (vector? target) (some map? target))
                    (mapcat (fn [t]
                              (let [t-str (some-> (:target t) kw->str)
                                    guard-str (convert-guard (:guard t))
                                    lbl (mermaid-transition-label label guard-str nil)]
                                [(str indent source-id " --> " (or t-str source-id) " : " lbl)]))
                            target)

                    :else
                    [(str indent source-id " --> " (kw->str target) " : " label)])))
              after)

      (sequential? after)
      (mapcat (fn [{:keys [delay target]}]
                (let [label (str "after " delay "ms")
                      t-str (some-> target kw->str)]
                  [(str indent source-id " --> " (or t-str source-id) " : " label)]))
              after))))

(defn- mermaid-always-lines
  "Generate Mermaid lines for :always eventless transitions."
  [indent source-id always]
  (when always
    (let [transitions (cond
                        (keyword? always) [{:target always}]
                        (map? always) [always]
                        (sequential? always) always
                        :else [])]
      (mapcat (fn [t]
                (let [t (if (keyword? t) {:target t} t)
                      target-str (some-> (:target t) kw->str)
                      guard-str (convert-guard (:guard t))
                      label (mermaid-transition-label "always" guard-str nil)]
                  [(str indent source-id " --> " (or target-str source-id) " : " label)]))
              transitions))))

(defn- mermaid-state-lines
  "Generate Mermaid lines for a single state and its children.
   Emits the state block (if composite), notes, and all transitions
   originating from this state. Does NOT emit transitions from children
   — those are handled by recursive calls."
  [indent state-id node]
  (when (map? node)
    (let [{:keys [initial type states regions on entry exit after always]} node
          parallel? (or (= type :parallel) (some? regions))
          child-states (or states regions)
          has-children? (seq child-states)
          child-indent (str indent "  ")
          ;; Entry/exit as notes
          entry-strs (convert-actions entry "entry")
          exit-strs (convert-actions exit "exit")
          note-parts (cond-> []
                       (seq entry-strs) (conj (str "entry / " (str/join ", " entry-strs)))
                       (seq exit-strs)  (conj (str "exit / " (str/join ", " exit-strs))))]
      (concat
        ;; Composite state block
        (when has-children?
          (concat
            [(str indent "state " state-id " {")]
            (if parallel?
              ;; Parallel regions separated by --
              (let [region-keys (keys child-states)]
                (->> region-keys
                     (map-indexed
                       (fn [i rk]
                         (let [region-id (kw->str rk)
                               region-def (get child-states rk)]
                           (concat
                             (when (pos? i) [(str child-indent "--")])
                             (mermaid-state-lines child-indent region-id region-def)))))
                     (apply concat)))
              ;; Regular compound state
              (concat
                (when initial
                  [(str child-indent "[*] --> " (kw->str initial))])
                ;; Recurse into children (emits their blocks + their transitions)
                (mapcat (fn [[sk sv]]
                          (mermaid-state-lines child-indent (kw->str sk) sv))
                        child-states)))
            [(str indent "}")]))
        ;; Note for entry/exit
        (when (seq note-parts)
          [(str indent "note right of " state-id)
           (str indent "  " (str/join "\n" note-parts))
           (str indent "end note")])
        ;; Transitions originating from this state
        (mermaid-on-lines indent state-id on)
        (mermaid-after-lines indent state-id after)
        (mermaid-always-lines indent state-id always)))))

(defn machine->mermaid
  "Convert a clj-statecharts machine definition map to a Mermaid state diagram string.
   The string can be rendered by Mermaid.js in the browser.

   Works with both user-facing input maps and normalized (post fsm/machine) maps."
  [machine-def]
  (let [{:keys [id initial type states regions on]} machine-def
        parallel? (or (= type :parallel) (some? regions))
        child-states (or states regions)
        indent "    "
        lines (concat
                ["stateDiagram-v2"
                 "    direction LR"]
                ;; Top-level initial
                (when (and initial (not parallel?))
                  [(str "    [*] --> " (kw->str initial))])
                ;; States
                (if parallel?
                  ;; Mermaid requires -- inside a state block, so wrap in a parent
                  (let [parent-id (or (some-> id kw->str) "parallel")
                        region-keys (keys child-states)
                        inner-indent (str indent "  ")]
                    (concat
                      [(str indent "state " parent-id " {")]
                      (->> region-keys
                           (map-indexed
                             (fn [i rk]
                               (let [region-id (kw->str rk)
                                     region-def (get child-states rk)]
                                 (concat
                                   (when (pos? i) [(str inner-indent "--")])
                                   (mermaid-state-lines inner-indent region-id region-def)))))
                           (apply concat))
                      [(str indent "}")]))
                  ;; Regular: recurse into each top-level state
                  ;; mermaid-state-lines handles both the state block and its transitions
                  (mapcat (fn [[sk sv]]
                            (mermaid-state-lines indent (kw->str sk) sv))
                          child-states))
                ;; Top-level on (rare but possible)
                (when on
                  (let [root-id (or (some-> id kw->str) "root")]
                    (mermaid-on-lines indent root-id on))))]
    (str/join "\n" (remove nil? lines))))

(defn machine->xstate-json
  "Convert a clj-statecharts machine definition map to an XState v5 JSON string.
   The JSON can be pasted into https://stately.ai/viz for visualization.

   Functions (guards, actions, entry/exit) are converted to string labels
   since they can't be serialized.

   Works with both user-facing input maps and normalized (post fsm/machine) maps."
  [machine-def]
  #?(:clj  ;; Simple JSON emitter for CLJ/BB (no external dependency needed)
     (letfn [(to-json [x]
               (cond
                 (nil? x) "null"
                 (string? x) (str "\"" (str/replace (str/replace x "\\" "\\\\") "\"" "\\\"") "\"")
                 (number? x) (str x)
                 (boolean? x) (str x)
                 (keyword? x) (to-json (name x))
                 (map? x) (str "{"
                               (str/join ","
                                 (map (fn [[k v]]
                                        (str (to-json k) ":" (to-json v)))
                                      x))
                               "}")
                 (sequential? x) (str "["
                                      (str/join "," (map to-json x))
                                      "]")
                 :else (to-json (str x))))]
       (to-json (machine->xstate machine-def)))
     :cljs
     (js/JSON.stringify (clj->js (machine->xstate machine-def)) nil 2)))
