(ns repl-tools.trace
  (:require
   [clojure.data.json :as json]
   [clj-time.format :as format]
   [clj-time.coerce :as coerce]
   [probe.core :as p]
   [probe.wrap :as wrap]
   [clojure.stacktrace :as stacktrace])
  (:import (java.io PrintWriter)))

;; Writes JSON to trace.log with the following keys:
;;
;; fname     - function name
;; thread-id - thread ID
;; ns        - namespace (Note: seems to be incorrect - bug in probe library?)
;; tags      - probe tags for filtering e.g. :probe/fn-exit
;; value     - return value of function
;; args      - function args
;; line      - line number
;; ts        - timestamp
;;
;; You can then pipe the log to any aggregation service or grep and filter it
;; e.g.
;; tail -n 1000 log/trace.log | grep 'some-function' | jq '{fname, args, value}' | less -S

(defn current-hour []
  (.get (java.util.Calendar/getInstance) java.util.Calendar/HOUR_OF_DAY))

(defn date-hour-format [date]
  (format/unparse (format/formatters :date-hour) (coerce/from-date date)))

(defn new-log [_log path]
  {
   :path path
   :hour (current-hour)
   :writer (clojure.java.io/writer #spy/d (str path "/trace-" (date-hour-format (java.util.Date.)) ".log") :append true)})

(defn close-log [log]
  (when (not= log ::no-log)
    (.close (:writer log)))
  ::no-log)

(def log (agent
          ::no-log
          :error-handler (fn [_ e]
                           (prn "Error in agent")
                           (stacktrace/print-cause-trace e)
                           (prn))))

(defn make-json-friendly* [[key value]]
  [(name key)
   (if (string? value) value (pr-str value))])

(def make-json-friendly (memoize make-json-friendly*))

(defn loggable-str [data]
  (str
   (json/write-str
    (->> data (map make-json-friendly) (into {})))
   "\n"))

(defn date-hour-format [date]
  (format/unparse (format/formatters :date-hour) (coerce/from-date date)))

(defn iso8601-date [date]
  (format/unparse (format/formatters :date-time) (coerce/from-date date)))

(defn update-writer [log]
  (let [hour (current-hour)]
    (if (= (:hour log) hour)
      log
      (do
        (close-log log)
        (new-log nil (:path log))))))

(defn write [log data]
  (let [new-log (update-writer log)
        writer (:writer new-log)

        log-str (-> data
                    (update-in [:ts] iso8601-date)
                    loggable-str)]
    (.write writer log-str)
    (.flush writer)
    new-log))

(defn custom-log-sink
  [state]
  (send-off log write state))

(defn build-sink []
  (when-not (contains? @p/sinks :log)
    (p/add-sink :log custom-log-sink)
    (p/subscribe #{:probe/fn-exit} :log)
    (p/subscribe #{:probe/watch} :log)))

;; Temporary implementation to work around probe bug
;; https://github.com/VitalLabs/probe/issues/9
(defn- unprobe-var-fns
  "Unprobe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get wrap/as-var))
        (map p/unprobe-fn!))))

(defn unprobe-ns-all! [ns]
  (unprobe-var-fns (keys (ns-interns ns))))

(defn matching-namespaces [prefix namespaces]
  (let [regex (re-pattern (str "^" prefix))]
    (->> namespaces
         (map #(.name %))
         (filter
          (fn [sym]
            (and (not= sym 'repl-tools.trace)
                 (re-find regex (name sym))))))))

(defn untrace-all [prefix]
  (p/unsubscribe-all)
  (p/rem-sink :log)
  (send log close-log)

  (doseq [ns-sym (matching-namespaces prefix (all-ns))]
    (println "Untracing" ns-sym)
    ;; Probe has a bug where you cannot instrument a namespace unless
    ;; you are already in it. https://github.com/VitalLabs/probe/issues/9
    (binding [*ns* ns-sym]
      (unprobe-ns-all! ns-sym))))

(defn trace-all
    "Traces all functions in a namespace prefix.
   Example: (radiator-web.system-trace/trace-all 'my-namespace \"log\")"
    [prefix path]
    (untrace-all prefix)
    (send log new-log path)
    (build-sink)

    (doseq [ns-sym (matching-namespaces prefix (all-ns))]
      (println "Tracing" ns-sym)
      ;; Probe has a bug where you cannot instrument a namespace unless
      ;; you are already in it. https://github.com/VitalLabs/probe/issues/9
      (binding [*ns* ns-sym]
        (p/probe-ns-all! ns-sym))))
