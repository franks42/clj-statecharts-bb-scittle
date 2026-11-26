# HOWTO: restore machine validation and add chart analysis

Instructions for Claude Code. The goal is two additions to this fork, each tested in
**Babashka (bb)** and **Scittle**:

1. **`validate-machine`**: structural checks on a machine definition. These checks were
   lost when Malli was removed in commit `8370538`.
2. **`statecharts.analysis`**: pure functions over a machine. They power the checks in
   an app's `bb verify` ladder: reachable states, dead ends, which events a state
   accepts, and which events the chart handles.

Read this whole file before writing code. Work on a branch named
`validation-and-analysis`. **Do not tag, push or publish without asking Frank.**

---

## 0. Background: why this is needed

Before `8370538`, `impl/machine` decoded the definition with Malli and then ran
`(ma/validate T_Machine …)`. On failure it threw `ex-info` with `malli.error/humanize`
output. The removed schema (see `git show 8370538 -- src/statecharts/impl.cljc`) had:

- **closed** state maps (`::state` was `[:map {:closed true} …]`)
- **closed** transition maps: only `:target :guard :actions`
- **closed** delayed transitions: only `:delay :target :guard :actions`
- a required `:id` keyword on the root
- `:type` limited to `:parallel`
- a closed `:integrations` map with `:re-frame` → `:path`, `:transition-event`,
  `:initialize-event`

Upstream once made these maps open (`12bd798`, issue #11) and then reverted that
change (`ce9e49d`). Closed maps were upstream's final decision.

Now `machine` only normalizes and then calls `validate-targets`. So a typo like
`{:target :s2 :gaurd ok?}` is accepted, the guard is **silently dropped**, and the
transition fires unconditionally. Restoring validation catches this when the machine is
built, in both bb and the browser.

`docs/scittle-compatibility.md` says Malli was used "exclusively as a recursive
tree-walker to normalize machine specs". That is wrong: Malli also did the validation
above. Section 5 corrects the doc.

## 1. Ground rules

- **Only `.cljc`, no new dependencies.** The whole point of the fork is no Malli and
  nothing Scittle can't load.
- **Use only constructs SCI and Scittle accept.** Don't use `.-field` on records (use
  keywords). Don't use Java-only classes outside reader conditionals. Throw `ex-info`
  only. Don't add new macros; `statecharts.macros` already needs a workaround in
  `scripts/test-sci.bb`.
- **Don't change the public API.** `fsm/machine`, `initialize` and `transition` must
  keep their behavior for every machine that is valid today. All existing tests must
  pass unchanged.
- **Read before writing:** `src/statecharts/impl.cljc` (normalization,
  `resolve-target`, `_state->configuration`, `validate-targets`),
  `src/statecharts/delayed.cljc` (how `:after` becomes `[:fsm/delay …]` events),
  `src/statecharts/viz.cljc` (an existing full-tree walker you can copy patterns
  from), `scripts/build-scittle-bundle.sh`, `scripts/test-bb.sh`,
  `scripts/test-sci.bb`, `test-scittle/scittle-tests.cljs`.
- Any server you start binds to **127.0.0.1**, never 0.0.0.0.

## 2. Task 1: `validate-machine`

### Where

Put it in a new namespace, `src/statecharts/validate.cljc`, that depends only on
`clojure.string`/`clojure.set`. That keeps `impl.cljc` readable and avoids circular
requires. Call it from `impl/machine` **on the raw input, before
`normalize-machine`**. Raw input gives better error messages: the user sees their own
keys, not internal ones like `:fsm/always` or `[:fsm/delay …]`.

### API

```clojure
(validate/problems m)  ;=> [] or a vector of problem maps, in tree order. Pure; never throws.
(validate/check! m)    ;=> m, or throws ex-info built from the FIRST problem.
```

`impl/machine` calls `check!`. `bb verify` in an app calls `problems`.

Shape of a problem map. Keep it stable, because apps print it:

```clojure
{:type       :statecharts/invalid-machine
 :machine-id :checkout                      ; nil if :id itself is missing
 :path       [:states :checkout :on :pay/submit 0]  ; path into the RAW definition
 :problem    :unknown-key                   ; see the list below
 :key        :gaurd                         ; when relevant
 :value      …                              ; offending value, when relevant
 :expected   "#{:target :guard :actions}"   ; short human string
 :suggestion :guard}                        ; nearest allowed key, or nil
```

`check!` message format, on one line, ready for an agent to act on:

```
Invalid machine :checkout at [:states :checkout :on :pay/submit 0]: unknown key :gaurd — did you mean :guard? (allowed: :target :guard :actions)
```

When there are several problems, also put the full vector in the ex-data as
`:problems`, and append `(+N more)` to the message.

### Rules

**Key policy:** reject unqualified keys that aren't in the allowed set. **Allow
namespaced keys** (e.g. `:ui/label`, `:doc/description`) everywhere except under the
`fsm` namespace, which is internal. This answers the need behind issue #11 (attaching
metadata) without giving up typo detection.

| Node | Allowed unqualified keys | Value rules |
|---|---|---|
| root | `:id :context :scheduler :integrations :initial :type :states :regions :on :after :always :entry :exit` | `:id` required, keyword |
| state | `:initial :type :states :regions :on :after :always :entry :exit` | |
| transition (map form) | `:target :guard :actions` | |
| delayed transition | `:delay :target :guard :actions` | `:delay` required: int ≥ 0 or fn |
| `:integrations` | `:re-frame` | `:re-frame` map: `:path` required; `:transition-event` and `:initialize-event` keywords |

Value rules:

- **target:** a keyword, or a vector of keywords (`:>` and `:.` prefixes included; see
  `resolve-target`). `nil` or missing means a self-transition.
- **transitions under `:on`, `:always`, and `:after` values:** a target, a transition
  map, or a vector of targets and transition maps. Match `canon-transitions` exactly.
- **`:on`:** a map from keyword to transitions. Reject keys in the `fsm` namespace.
- **`:after`:** either a map from delay (int or fn) to transitions, or a vector of
  delayed-transition maps (see `decode-delayed-transitions`).
- **actions / `:entry` / `:exit` / `:guard`:** `ifn?`, or for actions a vector of
  `ifn?`. The old schema used `ifn?`, so keywords pass, which is upstream behavior.
  Don't tighten this.
- **`:type`:** only `:parallel`.
- **`:states` / `:regions`:** a map from keyword to state.
- **`:scheduler`:** `statecharts.delayed/scheduler?`. Check it in `impl` if requiring
  `delayed` from `validate` causes an ordering problem in the bundle.

**New structural rules.** These are failures the old schema couldn't catch. They're
silent today because `compound?` means "has `:initial`" and `parallel?` means
"`:type :parallel`":

- `:states` present but no `:initial` → `:missing-initial`. Without this the node is
  treated as atomic and its children can never be entered.
- `:regions` present without `:type :parallel` → `:regions-without-parallel`.
- `:type :parallel` without `:regions` → `:parallel-without-regions`.
- `:type :parallel` together with `:initial` or `:states` → `:parallel-with-states`.

Before adding each rule, grep the existing tests and samples for machines that would
break. If any exist, stop and report rather than changing the tests.

**Suggestions:** use Levenshtein distance against the allowed set. Suggest when the
distance is ≤ 2, or when one key is a prefix of the other (`:action` → `:actions`).
Write a small, plain `levenshtein` function: no Java and no regex tricks.

**Leave alone:** target resolution. `validate-targets` already runs after
normalization. Keep it, and don't duplicate it in `validate`.

### Ordering

The bundle is files concatenated in dependency order. Add `validate.cljc` to
`scripts/build-scittle-bundle.sh` **before** `impl.cljc`, and to the `source-files`
vector in `scripts/test-sci.bb` in the same position.

## 3. Task 2: `statecharts.analysis`

New file `src/statecharts/analysis.cljc`. It requires `statecharts.impl` and works on
the **normalized** machine (the result of `fsm/machine`), so transitions are already in
canonical form. Pure functions only.

```clojure
(state-paths fsm)          ;=> #{[:browsing] [:checkout] [:checkout :editing] …} every node path
(handled-events fsm)       ;=> #{:seat/toggle :checkout/start …} user events only
(accepted-events fsm _state) ;=> events with a transition on any node in the active configuration
(edges fsm)                ;=> [{:from [...] :event :e :to [...] :guarded? bool :kind :on|:after|:always} …]
(reachable fsm)            ;=> set of node paths reachable from the initial configuration
(unreachable fsm)          ;=> (set/difference (state-paths fsm) (reachable fsm))
(dead-ends fsm)            ;=> leaf paths that are reachable, have no outgoing edge on
                           ;   themselves or any ancestor, and aren't marked final
(transition-cover fsm)     ;=> event sequences from the initial state that together take every edge once
```

Implementation notes:

- **Internal events:** `handled-events` and `accepted-events` must exclude
  `:fsm/always` and every vector event starting with `:fsm/delay` (see
  `delayed/generate-delayed-events`). `edges` keeps them, with `:kind :always` /
  `:after`.
- **`accepted-events`:** use `impl/_state->configuration` to get the active nodes
  (ancestors included, root included). Take the union of their `:on` keys, minus
  internal events. That gives XState's `state.can` for every event at once.
- **Edge targets:** resolve with `impl/resolve-target` relative to the source node's
  path. Entering a compound node also enters its `:initial` descendants. Entering a
  parallel node enters all of its regions. Reuse impl's entry logic if it can be called
  cleanly; otherwise write a small `entry-closure`.
- **Guards are opaque:** treat every guarded edge as possibly taken. Say in the
  docstrings that `reachable` and `transition-cover` therefore **over-approximate**:
  a path may need a guard to be true when it never can be.
- **Final states:** the library has no `:final` concept. Count a leaf as intentionally
  terminal if it has the key `:ui/final true` (allowed under the namespaced-key policy
  above), and treat other terminal leaves as dead ends. State this convention in the
  `analysis` ns docstring.
- **Parallel state explosion:** `transition-cover` works on edges, not on combinations
  of active states. Use BFS over node paths and stop once every edge has been taken. It
  doesn't need to be minimal.

Add `analysis.cljc` to the bundle **after** `viz.cljc`. The viz ns is also a good place
for a later optional step: `machine->mermaid` could highlight unreachable states and
dead ends. Not in scope now.

## 4. Tests: in bb, in SCI, and in Scittle

### Test files

- `test/statecharts/validate_test.cljc`: one `deftest` per problem kind, plus:
  - every machine in `test/statecharts/impl_test.cljc` and `docs/samples/` passes
    `problems` with `[]`. This guards against false positives; extract the machine
    literals or call `problems` inside the existing tests.
  - a namespaced key (`:ui/label`) passes; an `:fsm/…` key fails.
  - the exact message text for the `:gaurd` example.
  - `(+N more)` and `:problems` when there are several problems.
- `test/statecharts/analysis_test.cljc`: a flat machine, a nested machine (inherited
  `:on`), a parallel machine, a delayed-transition machine, an eventless machine. Plus
  one unreachable state, one dead end, and one guarded edge, each covered by a test.
  Include the checkout example:

  ```clojure
  {:id :checkout :initial :browsing
   :states {:browsing {:on {:seat/toggle :browsing :checkout/start :checkout}}
            :checkout {:initial :editing
                       :on {:checkout/cancel :browsing}
                       :states {:editing    {:on {:pay/submit :submitting}}
                                :submitting {:on {:pay/ok :done :pay/declined :declined}}
                                :declined   {:on {:pay/retry :editing}}}}
            :done {:ui/final true}}}
  ```

  `(accepted-events fsm [:checkout :submitting])` must be
  `#{:pay/ok :pay/declined :checkout/cancel}`. It must **not** include `:pay/submit`:
  that's the double-submit guarantee.

Only `clojure.test` and reader conditionals. No fixtures that touch the filesystem.

### Runners, in this order (stop at the first failure)

1. **bb:** add the two namespaces to `scripts/test-bb.sh`, then
   `bash scripts/test-bb.sh`.
2. **SCI, a stand-in for Scittle that runs in bb:** add both source files and both test
   files to `scripts/test-sci.bb` in dependency order, then `bb scripts/test-sci.bb`.
   This catches most Scittle-only problems without a browser.
3. **JVM (optional, if the Clojure CLI is installed):** `clojure -M:test` / kaocha per
   `tests.edn`. It picks up `*-test` namespaces automatically.
4. **Bundle:** `bash scripts/build-scittle-bundle.sh`. Check that the line count grew
   and that `validate` comes before `impl` and `analysis` after `viz`.
5. **Scittle in a real browser:**
   - Add test functions to `test-scittle/scittle-tests.cljs` and register them in
     `run-all-tests`: at least `validation-typo`, `validation-missing-initial`,
     `analysis-accepted-events`, `analysis-reachable`. Mirror the bb tests' assertions
     rather than inventing new ones.
   - **Fix `scripts/serve-test.bb`.** It runs `python3 -m http.server 8741` with no
     bind address, which listens on all interfaces. It also serves only
     `test-scittle/`, but `scittle-test-bundle.html` loads `/dist/…` and
     `/test-scittle/…`, so it needs the **repo root**. Change it to
     `python3 -m http.server 8741 --bind 127.0.0.1 --directory .` and update its usage
     comment to `http://127.0.0.1:8741/test-scittle/scittle-test-bundle.html`.
   - Run it headless if a browser is available. The page sets `window.testResults` to
     `{pass, fail, done}` for this purpose. With Playwright: load the page, wait for
     `window.testResults?.done`, and fail if `fail > 0`. Without Playwright,
     `chrome --headless=new --virtual-time-budget=10000 --dump-dom <url>` and grep for
     `" 0 failed"`. If neither works in your environment, **say so explicitly** and
     leave the browser run to Frank. Don't claim it passed.
   - Expected result: the previous 37 tests plus the new ones, all passing.
6. **The CDN page (`scittle-test-cdn.html`)** still loads the old jsDelivr tag. Don't
   change it until a new tag exists (section 6).

## 5. Docs

- `docs/scittle-compatibility.md`: correct the Malli section. Malli normalized **and
  validated**. Validation is now restored by `statecharts.validate`, without Malli, and
  is stricter in four structural rules (list them).
- `README.md` (fork section): list `validate` and `analysis` in the changes, with the
  `:gaurd` error message as the example, and the namespaced-key policy.
- `CHANGELOG.md`: a new entry. Mark it as **potentially breaking** for machines that
  relied on unknown keys being ignored, or on `:states` without `:initial`.

## 6. Release: ask first

When everything above passes, stop and report to Frank:

- which runners passed (bb, SCI, JVM, browser), and which were not run and why
- the bundle's line count, and the diff stat
- any machine in tests or samples that a new rule rejected

Only when he says so: rebuild the bundle, commit, tag `v0.1.7-scittle.2`, push, and
update `scittle-test-cdn.html` and the README's CDN snippet to the new tag.

## Out of scope

- Restoring Malli, or adding any other dependency.
- History nodes, `:final` semantics in the runtime, or other library features.
- Mermaid highlighting of analysis results.
- Changing behavior of `transition`/`initialize` for valid machines.

## Done when

- [ ] `problems`/`check!` exist; `machine` calls `check!` on raw input
- [ ] `:gaurd` example throws with the exact one-line message
- [ ] the four structural rules are enforced, with no existing test or sample broken (or broken ones reported)
- [ ] `statecharts.analysis` functions exist with docstrings stating the over-approximation and the `:ui/final` convention
- [ ] the checkout example's `accepted-events` excludes `:pay/submit` in `:submitting`
- [ ] bb, SCI and the bundle build all pass; browser result reported honestly
- [ ] `serve-test.bb` binds 127.0.0.1 and serves the repo root
- [ ] docs corrected; CHANGELOG notes the possible break
- [ ] nothing tagged or pushed
