(ns s-exp.eftest.runner
  "Functions to run tests written with clojure.test or compatible libraries."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.namespace.find :as find]
            [s-exp.eftest.output-capture :as capture]
            [s-exp.eftest.report :as report]
            [s-exp.eftest.report.progress :as progress])
  (:import (java.util.concurrent ExecutorService Executors)))

(defmethod test/report :begin-test-run [_])

(defn- deterministic-shuffle [seed ^java.util.Collection coll]
  (let [al (java.util.ArrayList. coll)
        rng (java.util.Random. seed)]
    (java.util.Collections/shuffle al rng)
    (vec al)))

(defn- synchronize [f]
  (let [lock (Object.)] (fn [x] (locking lock (f x)))))

(defn- synchronized? [v]
  (or (-> v meta :eftest/synchronized true?)
      (-> v meta :ns meta :eftest/synchronized true?)))

(defn- known-slow? [v]
  (or (-> v meta :eftest/slow true?)
      (-> v meta :ns meta :eftest/slow true?)))

(defn- failed-test? []
  (or (not= :pass (get @report/*context* :state :pass))
      (< 0 (:error @test/*report-counters* 0))
      (< 0 (:fail @test/*report-counters* 0))))

(defn- wrap-test-with-timer [test-fn ns test-warn-time]
  (fn [v]
    (let [start-time (System/nanoTime)
          result (test-fn v)
          end-time (System/nanoTime)
          duration (/ (- end-time start-time) 1e6)]
      (when (and (not (known-slow? v))
                 (number? test-warn-time)
                 (<= test-warn-time duration))
        (binding [clojure.test/*testing-vars* (conj clojure.test/*testing-vars* v)
                  report/*testing-path* [ns v]]
          (test/report {:type :long-test
                        :duration duration
                        :var v})))
      result)))

(defn- bound-callback ^Callable
  [f]
  (let [bindings (get-thread-bindings)]
    (reify Callable
      (call [_]
        (with-bindings* bindings f)))))

(defn- default-thread-count []
  (+ 2 (.availableProcessors (Runtime/getRuntime))))

(defn- threadpool-executor ^ExecutorService
  [{:keys [thread-count] :or {thread-count (default-thread-count)}}]
  (Executors/newFixedThreadPool thread-count))

(defn- pcalls* [executor fs]
  (->> fs
       (map #(.submit executor (bound-callback %)))
       (doall)
       (map #(.get %))
       (doall)))

(defn- pmap* [executor f xs]
  (pcalls* executor (map (fn [x] #(f x)) xs)))

(defn- multithread-vars?
  [{:keys [multithread]}]
  (contains? multithread :vars))

(defn- multithread-namespaces?
  [{:keys [multithread]}]
  (contains? multithread :namespaces))

(defn- multithread?
  [opts]
  (seq (:multithread opts)))

(defn- fixture-exception [throwable]
  {:type :error
   :message "Uncaught exception during fixture initialization."
   :actual throwable})

(defn- test-vars
  [ns vars report
   {:as opts :keys [executor fail-fast capture-output test-warn-time]
    :or {capture-output true}}]
  (let [once-fixtures (-> ns meta ::test/once-fixtures test/join-fixtures)
        each-fixtures (-> ns meta ::test/each-fixtures test/join-fixtures)
        test-var (-> (fn [v]
                       (when-not (and fail-fast (failed-test?))
                         (binding [report/*testing-path* [ns ::test/each-fixtures]]
                           (try
                             (each-fixtures
                              (if capture-output
                                #(binding [test/report report
                                           report/*testing-path* [ns v]]
                                   (capture/with-test-buffer
                                     (test/test-var v)))
                                #(binding [test/report report
                                           report/*testing-path* [ns v]]
                                   (test/test-var v))))
                             (catch Throwable t
                               (test/do-report (fixture-exception t)))))))
                     (wrap-test-with-timer ns test-warn-time))]
    (binding [report/*testing-path* [ns ::test/once-fixtures]]
      (try
        (once-fixtures
         (fn []
           (if (multithread-vars? opts)
             (do (->> vars
                      (filter synchronized?)
                      (map test-var)
                      (dorun))
                 (->> vars
                      (remove synchronized?)
                      (pmap* executor test-var)
                      (dorun)))
             (doseq [v vars] (test-var v)))))
        (catch Throwable t
          (test/do-report (fixture-exception t)))))))

(defn- test-ns [ns vars report opts]
  (let [ns (the-ns ns)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/do-report {:type :begin-test-ns :ns ns})
      (test-vars ns vars report opts)
      (test/do-report {:type :end-test-ns :ns ns})
      @test/*report-counters*)))

(defn- test-all [vars {:as opts
                       :keys [capture-output randomize-seed]
                       :or {capture-output true randomize-seed 0}}]
  (let [report (synchronize test/report)
        executor (delay (Executors/newCachedThreadPool))
        mapf (if (multithread-namespaces? opts)
               (partial pmap* @executor)
               map)
        f #(->> (group-by (comp :ns meta) vars)
                (sort-by (comp str key))
                (deterministic-shuffle randomize-seed)
                (mapf (fn [[ns vars]] (test-ns ns vars report opts)))
                (apply merge-with +))]
    (try
      (if capture-output
        (capture/with-capture (f))
        (f))
      (finally
        (when (realized? executor)
          (.shutdownNow @executor))))))

(defn- require-namespaces-in-dir [dir]
  (map (fn [ns] (require ns) (find-ns ns)) (find/find-namespaces-in-dir dir)))

(defn- find-tests-in-namespace [ns]
  (->> ns ns-interns vals (filter (comp :test meta))))

(defn- find-tests-in-dir [dir]
  (mapcat find-tests-in-namespace (require-namespaces-in-dir dir)))

(defmulti find-tests
  "Find test vars specified by a source. The source may be a var, symbol namespace
  or directory path, or a collection of any of the previous types."
  {:arglists '([source])}
  type)

(defmethod find-tests clojure.lang.IPersistentCollection [coll]
  (mapcat find-tests coll))

(defmethod find-tests clojure.lang.Namespace [ns]
  (find-tests-in-namespace ns))

(defmethod find-tests clojure.lang.Symbol [sym]
  (if (namespace sym) (find-tests (find-var sym)) (find-tests-in-namespace sym)))

(defmethod find-tests clojure.lang.Var [var]
  (when (-> var meta :test) (list var)))

(defmethod find-tests java.io.File [dir]
  (find-tests-in-dir dir))

(defmethod find-tests java.lang.String [dir]
  (find-tests-in-dir (io/file dir)))

(defn combined-reporter
  "Combines the reporters by running first one directly,
  and others with clojure.test/*report-counters* bound to nil."
  [[report & rst]]
  (fn [m]
    (report m)
    (doseq [report rst]
      (binding [clojure.test/*report-counters* nil]
        (report m)))))

(def default-options
  {:dir "test"
   :selector (constantly true)
   :capture-output false
   :fail-fast false
   :multithread #{:vars :namespaces}
   :sort-vars false
   :reporters [progress/report]})

(defn- ret->exit-code
  [{:as _ret :keys [error fail]}]
  (System/exit
   (cond
     (and (pos? fail) (pos? error)) 30
     (pos? fail) 20
     (pos? error) 10
     :else 0)))

(defn sort-vars
  [vars]
  (sort-by (fn [var]
             (let [{:keys [ns line]} (meta var)]
               [(str ns) line]))
           vars))

(defn run-tests
  "Run the supplied test vars. Accepts the following options:

    :fail-fast - if true, stop after first failure or error
    :capture-output - if true, catch test output and print it only if the test
  fails (defaults to true)
    :multithread - set of :namespaces or/and :vars (defaults to
  #{:vars :namespaces}). If set to namespaces and vars are run in parallel;
  otherwise, they are run in serial. If set to :namespaces, namespaces are run
  in parallel but the vars in those namespaces are run serially. If set
  to :vars, the namespaces are run serially, but the vars inside run in
  parallel.
    :thread-count - the number of threads used to run the tests in parallel (as
  per :multithread). If not specified, the number reported by
  java.lang.Runtime.availableProcessors (which is not always accurate) *plus
  two* will be used.
   :randomize-seed - the random seed used to deterministically shuffle test
  namespaces before running tests (defaults to 0).
    :reporters - the test reporting functions to use (defaults
  to [s-exp.eftest.report.progress/report])
    :test-warn-time - print a warning for any test that exceeds this
  time (measured in milliseconds)
    :exit-on-completion - whether to sys.exit or not at the end of the run -
  defaults: false"
  ([vars] (run-tests vars {}))
  ([vars opts]
   (let [{:as opts :keys [exit-on-completion reporters]} (into default-options opts)
         start-time (System/nanoTime)]
     (cond-> (if (empty? vars)
               (do (println "No tests found.")
                   test/*initial-report-counters*)
               (binding [report/*context* (atom {})
                         test/report (combined-reporter reporters)]
                 (test/do-report {:type :begin-test-run :count (count vars)})
                 (let [executor (when (multithread? opts)
                                  (threadpool-executor opts))
                       opts (assoc opts :executor executor)
                       counters (try
                                  (test-all vars opts)
                                  (finally
                                    (when executor (.shutdownNow executor))))
                       duration (/ (- (System/nanoTime) start-time) 1e6)
                       summary (assoc counters :type :summary :duration duration)]
                   (test/do-report summary)
                   summary)))
       exit-on-completion
       ret->exit-code))))

(defn find+run
  [options]
  (-> (find-tests options)
      (run-tests options)))
