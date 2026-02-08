## Fork: Scittle/Babashka Compatibility

This is a fork of [lucywang000/clj-statecharts](https://github.com/lucywang000/clj-statecharts) with changes to enable compatibility with [Scittle](https://github.com/babashka/scittle) and [Babashka](https://github.com/babashka/babashka).

Changes from upstream:
- Removed [malli](https://github.com/metosin/malli) dependency (the sole external dep and only blocker to standalone Scittle usage). Replaced with hand-written `normalize-machine` functions. All existing tests pass unchanged.
- Fixed defrecord field access (`.-v` → `:v`) for SCI/Scittle compatibility.

### Scittle Usage

Load the bundled library via CDN with two script tags:

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/clj-statecharts-bb-scittle@v0.1.7-scittle/dist/statecharts-bundle.cljc"></script>
```

Then use it in your Scittle code:

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

---

State Machine and StateCharts for Clojure(Script). Inspired by [XState](https://github.com/davidkpiano/xstate).

[![Clojars Project](https://img.shields.io/clojars/v/clj-statecharts.svg)](https://clojars.org/clj-statecharts)
![build](https://github.com/lucywang000/clj-statecharts/actions/workflows/build.yml/badge.svg?branch=master)
[![project chat](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/messages/C01C7RJA81M)

## Features

Most features of statecharts are supported:

* Declarative statecharts definition
* Hierarchical states (a.k.a compound or nested states)
* Parallel states (a.k.a concurrent states or orthogonal regions)
* Transition actions & Entry/Exit actions
* Guarded transitions
* Delayed transitions
* First-class Re-frame Integration

## Documentation

Please visit https://lucywang000.github.io/clj-statecharts/ for the documentation.

## Related Projects

- [Statecharts 101](https://statecharts.github.io/)
- [XState](https://github.com/davidkpiano/xstate), which inspired this project

## Articles & Show Cases

* [Using clj-statecharts to Manage Character Animations](https://doughamil.github.io/gamedev/2021/03/24/statecharts-for-animation.html)


## License

Copyright © 2020-2021 Lucy Wang

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
