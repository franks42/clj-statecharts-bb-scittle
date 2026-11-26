# Composable component charts (`statecharts.compose`)

One chart per component **instance**, many component **types**, all wired together
by message passing. The process is the same for 3 components or 300. Read
`examples/replicant-checkout/TUTORIAL.md` first for single-chart conventions; this
document adds composition.

Worked example: `examples/compose-tree/`. It has three requests, a join on two of
them, a late third request, retry, and a tree of 300 to 3,000 lazy row disclosures.
`examples/compose-form/` is built from library types only: a size choice whose
options load through a request, an email form-field, and an order request.

## Concepts

| Term | Meaning |
|---|---|
| **component type** | `(c/component {:type :request :machine … :outputs #{:request/ok …} :lazy? false})`. All of its events and outputs live in the `request/` namespace. Defined once, checked once, reused. |
| **instance** | `[type key]`, e.g. `[:request :prices]` or `[:row "r17"]`. Has its own chart state, a parent, an output mapping and a generation number. |
| **app** | The top-level chart. Its events must not use a component namespace. `c/system` throws if they do, because routing would be ambiguous. |
| **lazy type** | An instance appears on its first message, owned by `:app`. Until then it reads as its initial state, so unused rows cost nothing. |

## Messages

- **UI to an instance:** `[:row/toggle "r17"]`. The namespace selects the type and
  the second element is the key. Actions in any other namespace go to the app.
- **Parent to child**, from a chart action: `(c/send [:request :prices] [:request/start])`
  or `(c/send-each f)`.
- **Child to parent**, from a chart action: `(c/emit :request/ok :data)`. It reaches
  the parent only if the spawn mapped it, renamed:

  ```clojure
  (c/spawn [:request :prices] {:params {:effect :fetch/prices}
                               :on {:request/ok :prices/loaded, :request/failed :load/failed}})
  ```

  The parent receives `{:type :prices/loaded :key :prices :from [:request :prices] :data …}`.
  Because `:key` is included, one parent event can serve N instances (e.g.
  `:load/failed` from any request).
- **Lifecycle:** `c/spawn`, `c/spawn-each`, `c/kill`, `c/kill-each`. Killing an
  instance also kills everything it spawned.

## Ordering semantics

1. **One external message at a time.** An external message is a UI action or an
   effect reply.
2. **One transition per delivery.** Messages caused by that transition are
   **appended** to a FIFO queue, so processing is breadth-first. `spawn` and `kill`
   take effect immediately, in the order the chart's actions ran.
3. **The queue drains completely** before the next external message.
4. **Effects run after the drain.** Each effect's replies go back to the instance
   that asked, tagged with its generation. A reply to an instance that was killed
   or replaced in the meantime is dropped (`:stale-reply`).
5. **More than 1,000 deliveries** for one external message counts as a loop and
   throws.

The tests in `test/statecharts/compose_test.cljc` define these rules. Each rule was
checked by breaking it on purpose: switching the queue to LIFO fails 8 assertions,
including the join; removing the generation check fails the stale-reply test;
killing without children fails the subtree test.

## Effects

Effects are queued as data with `sr/fx`, for example a request type's
`(sr/fx (fn [s _] (into [(:effect s)] (:args s))))`. Handlers are given to `make-app`:

```clojure
(c/make-app {:system sys
             :effects {:fetch/prices (fn [reply] (-> (fetch …) (.then #(reply [:request/succeeded %]))))}})
```

`reply` takes the event **without** the instance key. Routing back to the instance
that asked is automatic.

## Views

A composed view receives `vc` from `(c/view-ctx sys world)`:

- `(:app vc)` is the app's ordinary view-state, used with the `sr/` helpers.
- `(c/in? vc [:request :prices] :loading)` and `(c/state vc id)` read instance state.
- `(c/button vc [:row/toggle id] "▸")` and `(c/on vc :input …)` work like the `sr/`
  versions for app or instance actions. A control is disabled exactly when its
  action wouldn't fire, and instances that don't exist yet are included.

## Verify

```clojure
(c/check {:system sys :view root :scenarios {…}})
```

Check runs every scenario with fake effects and reports, errors first:

- **Wiring:** `output-rejected` (a parent can't take a child's output in its
  current state, so the **message is lost**), `send-rejected`, `reply-rejected`,
  `no-instance`, `unknown-output` (mapping an output the type doesn't declare),
  `wrong-target`, `unhandled-effect`.
- **Views, per world:** `control/dead` (with reason `:guard`, `:no-transition` or
  `:no-instance`), `event/unknown`, `handler/function`, `view/threw`.
- **Coverage, as warnings:** app and component leaf states never reached (transient
  states count), component types never used, declared outputs never emitted.

**Manual scenarios: actions while requests are in flight.** Normal scenarios answer
every effect immediately, so a user action can never meet a pending request. With
`:manual? true`, effects wait until a step answers them:

```clojure
{:manual? true
 :actions [[:load]
           [::c/reply :fetch/prices]   ; prices fail while holdings is still loading
           [:retry]                    ; the user retries right away
           [::c/reply-all]]}
```

This scenario exists because the first browser run of the demo found a bug that
automatic scenarios couldn't find. Retry restarted *every* request, including one
still in flight (`send-rejected`). The manual scenario reproduces the bug without a
browser. The fix was to record which requests failed (outputs carry `:key`) and
retry only those. **Add a manual scenario for every user action that can happen
while something is loading.**

## Measured (`examples/compose-tree`)

| | 300 rows | 3,000 rows |
|---|---|---|
| Full view render plus a "would it fire?" check per control (nbb, SCI) | ~15 ms | ~120 ms |
| Dispatching a row toggle (pure) | ~0.2 ms | ~0.2 ms |
| Click to paint in headless Chrome (Scittle + Replicant) | 37–58 ms | 116–130 ms |
| Load to 300/3,000 rows painted (300 ms of that is fake latency) | ~410 ms | ~960 ms |

Rendering cost is linear in the number of controls, about 0.04 ms each under SCI.
Dispatch cost doesn't depend on size. 300 rows feel instant; 3,000 are usable but
visibly slower. If that matters, cache "would it fire?" per (type, state, event) for
the many instances that share an initial state. Not done yet.

## Conventions, in addition to the tutorial

- **One namespace per component type,** equal to its `:type`. App events never use a
  component namespace.
- **Instance keys are domain ids** (an address, a row id), not positions.
- **Map every output you care about** in the spawn's `:on`. Unmapped outputs are
  ignored on purpose (reported at `:info` level).
- **Use `:key` in parent handlers** to tell instances apart; don't create one parent
  event per instance.
- **Give each type its own tests** with `c/simulate` (pure), plus one
  `c/check` for the whole system, with automatic *and* manual scenarios.

## Component library

Four ready-made types live in `src/statecharts/components/`. Each namespace has a
`component` factory. Its `:type` option sets the type and therefore the event
namespace: `(disclosure/component {:type :row})` accepts `:row/toggle`. Two
instances of a type in one system share the type; two *kinds* (say rows and
panels) need two types.

| Type         | Factory                                             | States                    | Events (accepted)              | Outputs                       |
| ------------ | --------------------------------------------------- | ------------------------- | ------------------------------ | ----------------------------- |
| `request`    | `(request/component)`                               | idle, loading, ok, failed | `start`, `succeeded`, `failed` | `ok` (data), `failed` (error) |
| `disclosure` | `(disclosure/component {:lazy? true})`              | closed, open              | `toggle`, `open`, `close`      | `opened`, `closed`            |
| `choice`     | `(choice/component)`                                | unset, set                | `select`, `clear`, `options`   | `changed` (value or nil)      |
| `form-field` | `(form-field/component {:type :email :validate f})` | pristine, valid, invalid  | `input`, `reset`               | `valid`, `invalid`, `cleared` |

### request

Spawn with `{:params {:effect :fetch/x :args [...]}}`, then send `start`. Entering
`loading` queues `[effect & args]`; the handler replies `succeeded` or `failed`.
`start` is not accepted while loading, so its control disables itself and a
parent's send is reported as `send-rejected`. To abandon a request, kill or
replace the instance; its reply is then dropped.

Manual scenarios in `test/statecharts/components/request_test.cljc`: cancel while
loading (reply dropped as `:late-reply`), and cancel then load again (the old
reply is a `:stale-reply`, the new one lands).

### disclosure

Lazy by default, as in the compose-tree demo (`{:type :row}`): unused instances
cost nothing, but their outputs go nowhere. To react to opening, use
`{:lazy? false}` and spawn with an `:on` mapping.

Manual scenario in `disclosure_test.cljc`: details load on first open; the user
closes and reopens while they're in flight. The parent records which keys it has
requested, so the request starts once. Without that guard the test sees two
fetches.

### choice

Options are plain values (ids); labels belong to the view. `select` is guarded:
the value must be offered and differ from the current one. `options` replaces
the list; if the current value is no longer offered, the choice clears itself and
emits `changed` with nil.

Manual scenario in `choice_test.cljc`: the user picks a size while the options
reload, and the new options no longer contain it. The choice clears and the
parent hears about it.

### form-field

A factory: one type per validation rule. `validate` is a pure fn of the value
that returns nil or an error message. It runs as a guard, so no I/O; a server-side
check belongs in a request the parent starts on `valid`.

Manual scenarios in `form_field_test.cljc`: the user edits the field while the
signup request is in flight, with the request succeeding and failing. The parent
must take field outputs in every state (here at the root `:on`); handling them
only in `:editing` loses them, which `c/check` reports as `output-rejected`.

Every type has its own test file with `c/simulate` tests, a small host app, and
a `c/check` over automatic and manual scenarios that reports no errors and no
warnings. Pages load the component files as separate scripts after
`compose.cljc`, as `examples/compose-tree/index.html` does.

## Status and limits

- **Tested:** 26 tests, 106 assertions (across compose, the Replicant helper and
  the checkout template) under nbb, bb, SCI (`scripts/test-sci.bb`) and the JVM,
  plus `checkout.verify` and the compose-tree `c/check` under bb and the JVM; and
  headless Chrome with Scittle 0.8.33 + Replicant (both examples).
  `scripts/test-bb.sh` and `scripts/test-sci.bb` include these tests.
- **Component library tested:** 20 tests, 88 assertions across the four types,
  under bb, SCI, the JVM and nbb; each type's `c/check` is clean. The tests were
  mutation-checked (e.g. field outputs handled only in `:editing`, no dedupe
  guard on reopen, a choice that keeps a value no longer offered). In the
  browser: the compose-tree smoke test (request, disclosure as `:row`); ~~choice
  and form-field are only loaded and simulated in Scittle, not on a page yet.~~
  the compose-form smoke test (choice, form-field, request; 14 checks, including
  editing while an order is in flight). Its `c/check` is clean under bb, the JVM
  and nbb.
- **`compose.cljc` isn't in `dist/statecharts-bundle.cljc`;** pages load it as a
  separate script.
- **Delayed transitions (`:after`) aren't supported** in composed instances (no
  scheduler).
- **Manual scenarios are hand-written.** Nothing yet explores all interleavings
  automatically.
- ~~**Not built yet:** the component library (request, disclosure, choice, form,
  list editor). The demo defines its own `request` and `row`.~~ Built: request,
  disclosure, choice, form-field (see Component library); the demo uses them.
- **Not built yet:** the list editor type, and view helpers for the library types.
