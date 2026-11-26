(ns statecharts.components.disclosure
  "Component type: something that is open or closed (a tree row, a details panel).

    closed ──toggle / open──▶ open      (emits <type>/opened)
    open ──toggle / close──▶ closed     (emits <type>/closed)

  Lazy by default: an instance appears on its first message, owned by the app
  with no output mapping, so 3,000 unused rows cost nothing. Its outputs then go
  nowhere. To react to opening (e.g. load details on first open), make the type
  non-lazy and spawn the instance with an :on mapping:

    (c/spawn [:disclosure :d1] {:on {:disclosure/opened :details/wanted}})

  :open in the open state and :close in the closed state are not accepted, so
  their controls disable themselves; :toggle always fires."
  (:require [statecharts.core :as fsm]
            [statecharts.compose :as c]))

(defn component
  "The disclosure type. Options:
    {:type :disclosure   ; type and event namespace, e.g. :row → :row/toggle
     :lazy? true}"
  ([] (component {}))
  ([{:keys [type lazy?] :or {type :disclosure lazy? true}}]
   (let [e #(keyword (name type) %)
         open {:target :open :actions (c/emit (e "opened"))}
         close {:target :closed :actions (c/emit (e "closed"))}]
     (c/component
       {:type type :lazy? lazy?
        :outputs #{(e "opened") (e "closed")}
        :machine
        (fsm/machine
          {:id type :initial :closed
           :states {:closed {:on {(e "toggle") open, (e "open") open}}
                    :open   {:on {(e "toggle") close, (e "close") close}}}})}))))
