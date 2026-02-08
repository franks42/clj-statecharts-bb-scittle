# clj-statecharts: Scittle Compatibility

## Summary

This fork makes [clj-statecharts](https://github.com/lucywang000/clj-statecharts) v0.1.7 compatible with [Scittle](https://github.com/babashka/scittle) (SCI in the browser). The upstream already works with [Babashka](https://github.com/babashka/babashka) as-is — only Scittle required changes.

**Test results**: 37 browser tests passing, covering basic transitions, guards, nested/parallel states, eventless transitions, delayed transitions with simulated clock, service abstraction, assign actions, error handling, history states, and wall clock.

## What Changed (and Why)

### 1. Removed malli dependency

**Why**: Malli works fine in Babashka and JVM Clojure, but no Scittle malli plugin exists. It was the sole external dependency and the only blocker to standalone Scittle usage.

**What**: Malli was used exclusively as a recursive tree-walker to normalize machine specs (converting shorthand like `:idle` into `{:target :idle}`). Replaced with hand-written `normalize-machine` functions that apply the same transforms. All existing tests pass unchanged across JVM, CLJS, and bb.

### 2. Fixed defrecord field access (`.-v` → `:v`)

**Why**: Scittle's SCI does not support `.-field` interop syntax on defrecords — it returns `nil`. Babashka's SCI handles `.-field` correctly, so this only affects Scittle.

**Where**: `src/statecharts/impl.cljc:168` — the `execute` function checks if an action returned a `ContextAssignment` record and extracts its value.

```clojure
;; Before (fails in Scittle's SCI):
(.-v retval)

;; After (works everywhere):
(:v retval)
```

**Impact**: Without this fix, all `assign` actions silently return `nil` for context updates. Keyword access works across all Clojure dialects (JVM, CLJS, Babashka, Scittle).

## What We Expected to Fail (But Didn't)

### `volatile-mutable` in deftype

Both `service.cljc` and `sim.cljc` use `deftype` with `^:volatile-mutable` fields. This was predicted to fail in SCI based on documentation, but **works correctly in Scittle 0.7.30**. All service and simulated clock tests pass.

### `instance?` on defrecord types

`(instance? ContextAssignment retval)` works correctly in Scittle's SCI.

### Dynamic var binding

`(binding [*clock* ...] ...)` in `impl.cljc` works correctly.

### All reader conditional `:cljs` branches

The library has seven reader conditionals, all with correct `:cljs` branches using standard browser APIs (`js/Date.now`, `js/setTimeout`, `js/clearTimeout`, `js/console.warn`, `js/Error`).

## Babashka Compatibility (Upstream)

The upstream v0.1.7 works with Babashka without any changes:

- Malli is bb-compatible (loads fine via deps)
- `.-field` on defrecords works in bb's SCI
- All `deftype` constructs work
- All 36 test suite tests pass (155 assertions)

The changes in this fork are harmless for bb (`:v` keyword access and removing malli both work fine), but they are not required.

## Scittle Usage

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/clj-statecharts-bb-scittle@v0.1.7-scittle/dist/statecharts-bundle.cljc"></script>
```

Then in your Scittle code:

```clojure
(ns my-app
  (:require [statecharts.core :as fsm :refer [assign]]))

(def machine
  (fsm/machine
    {:id :toggle
     :initial :off
     :states {:off {:on {:toggle :on}}
              :on  {:on {:toggle :off}}}}))

(def state (fsm/initialize machine))
(fsm/transition machine state :toggle)  ;; => {:_state :on}
```

### Bundle

The `dist/statecharts-bundle.cljc` file is a concatenation of all source files in dependency order. Scittle evaluates multiple `ns` forms sequentially within a single file. Regenerate with:

```bash
scripts/build-scittle-bundle.sh
```

Source file dependency order:
1. `statecharts.utils` — utility functions
2. `statecharts.clock` — Clock protocol + WallClock
3. `statecharts.delayed` — delayed transition transforms
4. `statecharts.impl` — core FSM engine (machine, initialize, transition, assign)
5. `statecharts.store` — state store protocol + SingleStore/ManyStore
6. `statecharts.scheduler` — delayed event scheduling
7. `statecharts.sim` — SimulatedClock for testing
8. `statecharts.service` — Service abstraction (start, send, state)
9. `statecharts.core` — public API

## SCI Compatibility Notes

For anyone porting other Clojure libraries to Scittle, here are the key SCI differences we found:

| Construct | Babashka's SCI | Scittle's SCI |
|---|---|---|
| `(.-field record)` on defrecord | Works | Returns nil — use `(:field record)` |
| `(set! js/window.prop val)` | N/A | Fails — use `(set! (.-prop js/window) val)` |
| `deftype` with `volatile-mutable` | Works | Works (as of Scittle 0.7.30) |
| `instance?` on defrecord | Works | Works |
| `binding` with dynamic vars | Works | Works |
| Protocols | Works | Works |
| `defrecord` | Works | Works |
| Reader conditionals | `:bb`, `:clj` | `:cljs`, `:scittle` |

## Test Infrastructure

| File | Description |
|---|---|
| `test-scittle/scittle-test.html` | Loads source files individually (9 script tags) |
| `test-scittle/scittle-test-bundle.html` | Loads concatenated bundle (1 script tag) |
| `test-scittle/scittle-test-cdn.html` | Loads bundle from jsdelivr CDN |
| `test-scittle/scittle-tests.cljs` | 37 functional tests with DOM rendering + `window.testResults` for Playwright |
| `scripts/build-scittle-bundle.sh` | Concatenates source files into `dist/statecharts-bundle.cljc` |
| `scripts/test-sci.bb` | SCI interpreter tests via Babashka (36 tests, 155 assertions) |

## Architecture

The library is well-suited for Scittle because:

- Core FSM logic is pure, platform-agnostic Clojure (no reader conditionals)
- Only seven reader conditionals total, all for platform concerns (timing, logging, exceptions)
- Protocol-based design with clean abstraction boundaries
- Minimal external dependencies (now zero after malli removal)
- Small footprint (~1,364 lines bundled)
