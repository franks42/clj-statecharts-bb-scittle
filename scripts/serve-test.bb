#!/usr/bin/env bb

;; Minimal HTTP server for the browser test page.
;; Usage: bb scripts/serve-test.bb
;; Then open http://localhost:8741 in a browser.

(require '[babashka.process :as p])

(println "Serving test-scittle/ at http://localhost:8741")
(println "Open in browser to see test results.")
(println "Press Ctrl+C to stop.")

(p/exec ["python3" "-m" "http.server" "8741" "--directory" "test-scittle"])
