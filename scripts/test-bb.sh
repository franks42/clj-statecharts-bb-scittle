#!/usr/bin/env bash
set -euo pipefail

echo "=== Babashka classpath test ==="
echo "bb version: $(bb --version)"
echo ""

bb -cp src:test:test-support:examples/replicant-checkout/src:examples/replicant-checkout/test -e "
(require '[clojure.test :refer [run-tests]])
(require '[statecharts.impl-test])
(require '[statecharts.service-test])
(require '[statecharts.utils-test])
(require '[statecharts.integrations.replicant-test])
(require '[statecharts.compose-test])
(require '[checkout.chart-test])

(let [result (run-tests
               'statecharts.impl-test
               'statecharts.service-test
               'statecharts.utils-test
               'statecharts.integrations.replicant-test
               'statecharts.compose-test
               'checkout.chart-test)]
  (System/exit (if (and (zero? (:fail result))
                        (zero? (:error result)))
                 0 1)))
"
