(ns s-exp.eftest.report-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [puget.printer :as puget]
            [s-exp.eftest.output-capture :as output-capture]
            [s-exp.eftest.report :as report]
            [s-exp.eftest.report.junit :as junit]
            [s-exp.eftest.report.pretty :as pretty]
            [s-exp.eftest.runner :as sut]))

(in-ns 's-exp.eftest.test-ns.single-failing-test)
(clojure.core/refer-clojure)
(clojure.core/require 'clojure.test)
(clojure.test/deftest single-failing-test
  (clojure.test/is (= 1 2)))

(in-ns 's-exp.eftest.report-test)

(defn delete-dir [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(deftest report-to-file-test
  (delete-dir (io/file "target/test-out"))
  (-> 's-exp.eftest.test-ns.single-failing-test
      sut/find-tests
      (sut/run-tests {:reporters [(report/report-to-file junit/report "junit.xml")]}))
  (is (string? (slurp "junit.xml"))))

(def this-ns *ns*)

(deftest file-and-line-in-pretty-fail-report
  (let [pretty-nil (puget/pprint-str nil {:print-color true
                                          :print-meta false})
        result (with-out-str
                 (binding [*test-out* *out*
                           pretty/*fonts* {}
                           report/*testing-path* [this-ns #'file-and-line-in-pretty-fail-report]
                           *report-counters* (ref *initial-report-counters*)]
                   (output-capture/with-test-buffer
                     (pretty/report {:type :fail
                                     :file "report_test.clj"
                                     :line 999
                                     :message "foo"}))))]
    (is (= (str "\nFAIL in s-exp.eftest.report-test/file-and-line-in-pretty-fail-report"
                " (report_test.clj:999)\n"
                "foo\n"
                "expected: "
                pretty-nil
                "\n  actual: "
                pretty-nil
                "\n")
           result))))
