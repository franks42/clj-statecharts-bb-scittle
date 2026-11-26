#!/usr/bin/env bb

;; Phase 2: SCI Interpreter Compatibility Test
;;
;; This script simulates what Scittle does: feeds clj-statecharts source
;; files as text through SCI's eval-string* interpreter. This tests
;; compatibility with the SCI execution model rather than bb's classloader.
;;
;; Usage: bb scripts/test-sci.bb

(require '[sci.core :as sci])
(require '[clojure.set])
(require '[clojure.test])

(println "=== SCI Interpreter Compatibility Test ===")
(println (str "bb version: " (System/getProperty "babashka.version")))
(println "")

;; --- SCI Context Setup ---

;; The prog1 macro from macros.clj can't be loaded as a .clj file in SCI.
;; We register it as a proper SCI macro using new-macro-var.
(def prog1-macro
  (fn [_form _env expr & body]
    `(let [~'<> ~expr]
       (do ~@body)
       ~'<>)))

(def prog1-var (sci/new-macro-var 'prog1 prog1-macro))

(def sci-ctx
  (sci/init
    {:features #{:clj}
     :classes {'System                    System
               'java.lang.System          System
               'Exception                 Exception
               'java.lang.Exception       Exception
               'Throwable                 Throwable
               'java.lang.Throwable       Throwable
               'clojure.lang.ExceptionInfo clojure.lang.ExceptionInfo}
     :namespaces
     {'clojure.set     (sci/copy-ns clojure.set (sci/create-ns 'clojure.set))
      'clojure.test    (sci/copy-ns clojure.test (sci/create-ns 'clojure.test))
      'statecharts.macros {'prog1 prog1-var}}}))

;; --- Load Source Files ---

(def source-files
  ["src/statecharts/utils.cljc"
   "src/statecharts/clock.cljc"
   "src/statecharts/delayed.cljc"
   "src/statecharts/impl.cljc"
   "src/statecharts/store.cljc"
   "src/statecharts/scheduler.cljc"
   "src/statecharts/sim.cljc"
   "src/statecharts/service.cljc"
   "src/statecharts/core.cljc"
   "src/statecharts/integrations/replicant.cljc"
   "src/statecharts/compose.cljc"
   "src/statecharts/components/request.cljc"
   "src/statecharts/components/disclosure.cljc"
   "src/statecharts/components/choice.cljc"
   "src/statecharts/components/form_field.cljc"
   "examples/replicant-checkout/src/checkout/chart.cljc"])

(println "--- Loading source files through SCI eval-string* ---")
(doseq [f source-files]
  (sci/eval-string* sci-ctx (slurp f))
  (println (str "  loaded: " f)))
(println "")

;; --- Load Test Support ---

(sci/eval-string* sci-ctx (slurp "test-support/kaocha/stacktrace.cljc"))
(println "  loaded: test-support/kaocha/stacktrace.cljc")
(println "")

;; --- Load and Run Test Files ---

(println "--- Loading test files through SCI eval-string* ---")

(def test-files
  ["test/statecharts/utils_test.cljc"
   "test/statecharts/impl_test.cljc"
   "test/statecharts/service_test.cljc"
   "test/statecharts/integrations/replicant_test.cljc"
   "test/statecharts/compose_test.cljc"
   "test/statecharts/components/request_test.cljc"
   "test/statecharts/components/disclosure_test.cljc"
   "test/statecharts/components/choice_test.cljc"
   "test/statecharts/components/form_field_test.cljc"
   "examples/replicant-checkout/test/checkout/chart_test.cljc"])

(doseq [f test-files]
  (sci/eval-string* sci-ctx (slurp f))
  (println (str "  loaded: " f)))
(println "")

;; --- Run Tests ---

(println "--- Running tests ---")
(println "")

(let [result (sci/eval-string* sci-ctx
               "(require '[clojure.test :refer [run-tests]])
                (run-tests 'statecharts.utils-test
                           'statecharts.impl-test
                           'statecharts.service-test
                           'statecharts.integrations.replicant-test
                           'statecharts.compose-test
                           'statecharts.components.request-test
                           'statecharts.components.disclosure-test
                           'statecharts.components.choice-test
                           'statecharts.components.form-field-test
                           'checkout.chart-test)")]
  (println "")
  (let [failures (+ (:fail result) (:error result))]
    (if (zero? failures)
      (do (println "=== ALL SCI TESTS PASSED ===")
          (System/exit 0))
      (do (println (str "=== FAILURES: " failures " ==="))
          (System/exit 1)))))
