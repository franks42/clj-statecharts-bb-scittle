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
