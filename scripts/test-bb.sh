#!/usr/bin/env bash
set -euo pipefail

echo "=== Babashka classpath test ==="
echo "bb version: $(bb --version)"
echo ""

bb -e "
(require '[clojure.test :refer [run-tests]])
(require '[statecharts.impl-test])
(require '[statecharts.service-test])
(require '[statecharts.utils-test])

(let [result (run-tests
               'statecharts.impl-test
               'statecharts.service-test
               'statecharts.utils-test)]
  (System/exit (if (and (zero? (:fail result))
                        (zero? (:error result)))
                 0 1)))
"
