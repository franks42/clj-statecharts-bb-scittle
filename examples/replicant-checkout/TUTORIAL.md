# Statecharts + Replicant 101

How to build a UI with `clj-statecharts` and Replicant so that the chart, the app
state and the views can't silently drift apart, and so that `verify` tells you (or
an agent) what to fix next. All code here is from the checkout template in this
directory, and every `verify` output below was captured from a real run.

## 1. The idea

Most UI bugs aren't wrong values. They're **things happening at the wrong time**:
a button that does nothing, a double submit, a Cancel that's possible while a
payment is in flight, a spinner that never ends. Type and schema checks can't see
these, because every individual value is valid.

So we make **timing explicit and checkable**:

- The **chart** is the only place that says which events exist and when each one
  is allowed.
- **Views emit events as data**, so a program can compare what the screen offers
  with what the chart accepts.
- Everything except the final DOM wiring is **pure Clojure**, so it runs in bb, nbb
  and on the JVM, without a browser.

## 2. How the pieces fit

```
          ┌──────────── chart.cljc ─────────────┐
          │ states, events, guards, chart actions│
          └──────────────┬───────────────────────┘
                         │ step (pure)
 action data ──▶  state map (context + :_state)  ──▶ view-state ──▶ views.cljc ──▶ hiccup
 [:pay/submit]           │                     (+ :app/can, :app/active,          │
      ▲                  │ queued effects          :app/enabled?)                 │
      │                  ▼  [:pay/charge …]                                       │
      │         main.cljs effect handlers ── dispatch [:pay/ok] ──┐               │
      └───────────────────────────────────────────────────────────┴── Replicant ◀─┘
```

- **One atom**, holding the chart's own state map: context keys plus `:_state`.
- **`step`** applies one action. It returns the new state and the effects to run,
  or a rejection with a reason (`:no-transition` or `:guard`).
- **Views get a view-state:** the state plus what the chart allows right now.

## 3. Coding conventions

### Events

- **Namespaced keywords, `:noun/verb`, named after the user's intent:**
  `:seat/toggle`, `:checkout/start`, `:pay/submit`, `:form/email`.
- **Outcomes from effects are events too,** named as results: `:pay/ok`,
  `:pay/declined`. List them in `external-events` (see §6).
- **The payload is the first argument after the event:** `[:seat/toggle "A18"]`
  arrives as `{:type :seat/toggle :data "A18"}`. Further arguments go in `:args`.
- **Don't use the `:event/…`, `:event.target/…` or `:fsm/…` namespaces** for your
  own events. They're reserved.

### States

- **Name states as modes, not actions:** `:browsing`, `:editing`, `:submitting`,
  `:declined`, `:done`.
- **Nest states when they share transitions or meaning** (`:checkout` contains
  `:editing`, `:submitting`, `:declined`).
- **Targets:** a bare keyword means a **sibling** of the current state. Use
  `[:> :browsing]` for an absolute target and `[:. :child]` for a child.
  `:browsing` written inside `[:checkout :editing]` would mean
  `[:checkout :browsing]`. The library rejects that at load ("Invalid target"), but
  get it right up front.
- **Forbid things by not having a transition.** The template's `:submitting` state
  has no `:checkout/cancel`, so no Cancel control can be enabled while the charge is
  in flight. You don't write any code to prevent it.

### Context (the data in the state map)

- **Plain data only.**
- **No mode flags.** If you're about to add `:loading? true` or `:step 2`, it's a
  state. Ask the chart instead:

  ```clojure
  ;; no
  (when (:submitting? vs) …)
  ;; yes
  (when (sr/in? vs [:checkout :submitting]) …)
  ```

### Guards

- **Pure functions of `(state event)`: no I/O, cheap.** They run at render time,
  because `sr/on` and `sr/button` ask "would this fire?" for every control.
- **Put the rule in the guard, not in the view.** Pay checks the email in the guard:
  `(re-find #".+@.+\..+" (:email s))`. The button then disables itself without the
  view knowing the rule.

### Chart actions

- **No side effects in chart actions.** Use only `fsm/assign`, `sr/assoc-data`,
  `sr/update-ctx` and `sr/fx`.
- **`sr/fx` queues effects as data;** it doesn't run them:

  ```clojure
  :submitting {:entry (sr/fx (fn [s _] [:pay/charge {:seats (sort (:basket s)) :email (:email s)}]))
               :on {:pay/ok [:> :done]
                    :pay/declined {:target :declined :actions (sr/assoc-data :error)}}}
  ```

### Effects (in `main.cljs`)

- **A handler is `(fn [dispatch & args])`.** `args` are the rest of the effect
  vector. Report the outcome by dispatching an event:

  ```clojure
  (defn fake-charge [dispatch {:keys [seats]}]
    (js/setTimeout #(if (some #{"B2"} seats)
                      (dispatch [:pay/declined "Card declined (try without B2)"])
                      (dispatch [:pay/ok]))
                   800))

  (sr/make-app {:machine chart/machine :effects {:pay/charge fake-charge}})
  ```

- **`main.cljs` is the only file with interop.** Keep it short.

### Views

- **Pure functions of view-state.** Read only from `vs`.
- **Route on state with `sr/view-for`.** Use one view per leaf state, or per
  subtree if they share a view:

  ```clojure
  (sr/view-for vs {[:browsing] browsing, [:checkout :editing] editing, …})
  ```

- **Build every control with `sr/on` or `sr/button`.** They wire the action, or
  disable the control when it wouldn't fire (guards evaluated). They also set
  `data-event`, which tests use as a selector.

  ```clojure
  (sr/button vs [:pay/submit] "Pay")
  (sr/button vs {:tag :wa-button :variant "brand"} [:pay/submit] "Pay")   ; Web Awesome
  [:input (merge {:value (:email vs)} (sr/on vs :input [:form/email :event.target/value]))]
  ```

- **DOM values come in through placeholders:** `:event.target/value`,
  `:event.target/checked`, `:event.target/valueAsNumber`, `:event/key`.
- **Never put a function in `:on`.** Handlers are action data, so they can be
  checked.
- **Use `:replicant/key` on repeated elements.**

### Fixtures

- **Build fixtures by replaying actions,** never by writing state maps by hand:

  ```clojure
  (def ^:private filled (conj editing [:form/email "frank@example.org"]))
  (def fixtures {… :filled (sr/state-after machine filled) …})
  ```

  `state-after` throws if any step is rejected. So a fixture can't describe a state
  the chart can't reach, and a chart change that breaks a fixture fails loudly.
- **At least one fixture per leaf state.** `verify` warns about uncovered leaves.
- **Add fixtures on both sides of every guard.** The template has `:editing` (no
  email) and `:filled` (valid email). That pair is what catches a Pay button that's
  enabled but blocked (§5).

### Files

| File | Contains | Must not contain |
|---|---|---|
| `chart.cljc` | machine, `external-events`, fixtures | I/O, `js/` |
| `views.cljc` | view fns | state outside `vs`, `js/`, fns in `:on` |
| `main.cljs` | effect handlers, `make-app`, `start!` | business rules |
| `verify.cljc` | `sr/check` call | — |

## 4. The workflow: adding a feature

Example feature: a promo code on the checkout form.

**Step 1: change the chart first.**

```clojure
:editing {:on {:form/email  {:actions (sr/assoc-data :email)}
               :promo/apply {:actions (sr/assoc-data :promo)}     ; new
               …}}
```

**Step 2: run verify.** The warnings are your to-do list. The chart accepts the
event, but no screen offers it:

```
$ npx nbb -cp src:examples/replicant-checkout/src -m checkout.verify
WARN event/not-offered · fixture :editing · :promo/apply
  fix: offer a control for :promo/apply, or list it in :external-events
WARN event/not-offered · fixture :filled · :promo/apply
  fix: offer a control for :promo/apply, or list it in :external-events
chart/view sync: 0 errors, 2 warnings, 7 fixtures
```

**Step 3: add the control.** Suppose it's wired by hand, with a typo:

```clojure
[:button {:on {:click [:promo/aply "SPRING"]}} "Apply SPRING"]
```

```
FAIL event/unknown · fixture :editing · <button> · :promo/aply
  fix: add a transition for :promo/aply to the chart, or fix the typo
…
chart/view sync: 2 errors, 2 warnings, 7 fixtures        (exit code 1)
```

**Step 4: fix it the conventional way,** `(sr/button vs [:promo/apply "SPRING"] "Apply SPRING")`:

```
chart/view sync: 0 errors, 0 warnings, 7 fixtures
```

**Step 5: test the logic.** This is pure, so it runs in bb or nbb:

```clojure
(deftest pay-submits-and-charges
  (let [{:keys [state fx]} (sr/run-script chart/machine (:filled chart/fixtures) [[:pay/submit]])]
    (is (= [:checkout :submitting] (:_state state)))
    (is (= [[:pay/charge {:seats ["A18" "A19"] :email "frank@example.org"}]] fx))))

(deftest no-cancel-while-charging
  (is (= :no-transition
         (:reason (sr/step chart/machine (:submitting chart/fixtures) [:checkout/cancel])))))
```

**Step 6: smoke-test in a real browser.** Run `node smoke.mjs <url>` (see the
README). It clicks through the flow, using the `data-event` attributes as selectors,
and fails on any console error.

Run the steps in this order. Each one is slower than the one before it, and each
one only catches what the earlier ones can't.

## 5. Reading verify output

| Problem | Meaning | Usual fix |
|---|---|---|
| `control/dead` (reason `:guard`) | The control is enabled, but a guard blocks the event in this fixture. Clicking does nothing. | Build it with `sr/on` / `sr/button` |
| `control/dead` (reason `:no-transition`) | The control is enabled, but this state has no transition for the event | Use the helpers, or add the transition if the click *should* work here |
| `event/unknown` | No state handles this event at all | A typo, or a missing transition |
| `handler/function` | `:on` holds a function | Replace it with action data `[:my/event payload]` |
| `handler/not-action` | Handler data isn't `[:e …]` or `[[:e …] …]` | Fix the shape |
| `view/threw` | Rendering this fixture threw | Make the view handle that state |
| `fixture/invalid-state` | A fixture's `:_state` isn't in the chart | Build it with `state-after` |
| `event/not-offered` (warning) | The chart accepts it here, but nothing on screen offers it | Add a control, or list the event in `external-events` |
| `state/no-fixture` (warning) | No fixture reaches this leaf state | Add a fixture |

Here's a real `control/dead` from hand-wiring Pay as `[:button {:on {:click [:pay/submit]}} "Pay"]`:

```
FAIL control/dead · fixture :editing · <button> · :pay/submit
  fix: a guard blocks :pay/submit here; build the control with (on vs …) / (button vs …) so it disables itself
```

It fails in `:editing` (no email yet) and passes in `:filled`. That's why §3 asks for
fixtures on both sides of every guard.

## 6. Limits and gotchas

- **`verify` only sees the fixtures you give it.** A state that no fixture reaches
  isn't checked, apart from the `state/no-fixture` warning for leaf states.
- **Guards must be pure,** because they run at render time.
- **Inputs whose value comes from the event** (`:event.target/value`) are checked
  only for whether a transition exists. Their guards aren't run, because the value
  isn't known until the user types.
- **Reserved names:** `:app/fx` in the state; `:app/can`, `:app/active` and
  `:app/enabled?` in view-state; the `:event/*` and `:event.target/*` placeholder
  namespaces.
- **Delayed transitions (`:after`) need a scheduler.** `make-app` doesn't set one up
  yet, so avoid `:after` for now.
- **`dispatch!` assumes one thread** (the browser). On the JVM, test with
  `step`/`run-script`, not the runtime.

## 7. For agents: paste into CLAUDE.md

```markdown
## UI conventions (statecharts + Replicant)
- Change the chart (chart.cljc) first; views only read view-state `vs`.
- Events: namespaced :noun/verb; payload is the first arg. Effect outcomes are events listed in external-events.
- No mode flags in context; use (sr/in? vs [...]). No I/O in chart actions; queue effects with sr/fx.
- Every control via (sr/on vs …) / (sr/button vs …). Never a fn in :on. DOM values via :event.target/value.
- Fixtures only via sr/state-after; one per leaf state; both sides of every guard.
- After every change run: npx nbb -cp src:examples/replicant-checkout/src -m checkout.verify
  Fix FAIL lines first, in order, using their `fix:` line. Warnings are a to-do list.
- Then the logic tests, then smoke.mjs. Never edit fixtures to make verify pass; fix the chart or the view.
```
