# Statecharts + Replicant template (Scittle, no build)

A seat-booking checkout that shows how `statecharts.integrations.replicant` keeps
the chart, the app state and the views in sync. Copy this directory to start a new app.
**Start with [TUTORIAL.md](TUTORIAL.md)** for the process and the coding conventions.

| File | Role | Runs in |
|---|---|---|
| `src/checkout/chart.cljc` | The chart (the only place events and their timing are defined), fixtures built by replaying actions, external events | bb, nbb, JVM, Scittle |
| `src/checkout/views.cljc` | Pure views of view-state; every control built with `sr/on` / `sr/button` | bb, nbb, JVM, Scittle |
| `src/checkout/main.cljs` | Browser glue: effect handlers, `sr/start!` with Replicant's fns | Scittle |
| `src/checkout/verify.cljc` | Chart/view sync check over all fixtures | bb, nbb, JVM |
| `test/checkout/chart_test.cljc` | Logic tests with `step` / `run-script` (no browser) | bb, nbb, JVM |
| `smoke.mjs` | Headless browser walk-through (puppeteer-core) | node + Chrome |

## The rules that keep things in sync

1. **The app state is the chart's state map.** Never keep "mode" flags in context;
   ask `(sr/in? vs [:checkout :submitting])`.
2. **Handlers are action data:** `[:seat/toggle "A18"]`. The first element is a chart
   event. DOM values come in through placeholders: `:event.target/value`,
   `:event.target/checked`, `:event/key`.
3. **Build controls with `sr/on` / `sr/button`.** They disable themselves when the
   action wouldn't fire, with guards evaluated. So a control can't be enabled and do
   nothing.
4. **Chart actions don't do I/O.** Queue effects with `(sr/fx [:pay/charge …])`.
   Handlers in `main.cljs` receive `dispatch` to send results back as events, which
   you list in `external-events`.
5. **Fixtures come from `sr/state-after`**, replaying actions through the chart, so a
   fixture can't describe a state the chart can't reach.

## Run

From the repository root:

```bash
# chart/view sync check (any of these)
npx nbb -cp src:examples/replicant-checkout/src -m checkout.verify
bb      -cp src:examples/replicant-checkout/src -m checkout.verify

# in the browser
python3 -m http.server 8741 --bind 127.0.0.1
open http://127.0.0.1:8741/examples/replicant-checkout/

# headless walk-through (server above must be running; set CHROME if not macOS default)
npm i puppeteer-core && node examples/replicant-checkout/smoke.mjs \
  http://127.0.0.1:8741/examples/replicant-checkout/index.html
```

Tested so far: nbb (helper tests + verify) and Chrome via `smoke.mjs`. For many components, see `../../COMPOSE.md`. The bb and JVM
runs are written for (`#?(:clj …)` branches) but **not yet run**.

`verify` reports, per fixture: dead controls (enabled, but a guard or a missing
transition means nothing happens), handlers that are fns, typo'd events, views that
throw, accepted events with no control (warning), and leaf states with no fixture
(warning).
