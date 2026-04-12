(ns defport.util.progress
  "Progress tracking infrastructure for long-running operations.

  Supports progress notifications for protocols that implement progress reporting
  (MCP, LSP, DAP). Progress is reported via JSON-RPC notifications."
  (:require [defport.util.platform :as platform]))

(def ^:dynamic *progress-stdout* nil)

(defn generate-progress-token
  "Generate a unique progress token for tracking an operation.

  Returns string token.

  Platform-agnostic using reader conditionals for timestamp."
  []
  (let [timestamp #?(:clj (System/currentTimeMillis)
                     :cljs (.now js/Date))
        random (rand-int 10000)]
    (str "progress-" timestamp "-" random)))

(defn send-progress-notification
  "Send a progress notification to the client.

  token: progress token string
  progress: 0.0-1.0 (optional, if total/current provided)
  opts: optional map with:
    - :total - total number of items
    - :current - current item index
    - :message - progress message
    - :method - notification method (default 'notifications/progress')

  Uses *progress-stdout* dynamic binding to send notification.
  If not bound, notification is silently dropped."
  ([token progress]
   (send-progress-notification token progress nil))
  ([token progress opts]
   (when-let [stdout *progress-stdout*]
     (let [method (get opts :method "notifications/progress")
           params (cond-> {:progressToken token}
                    (and (:total opts) (:current opts))
                    (assoc :total (:total opts) :current (:current opts))

                    (and (not (:total opts)) (not (:current opts)))
                    (assoc :progress progress)

                    (:message opts)
                    (assoc :message (:message opts)))
           notification {:jsonrpc "2.0"
                        :method method
                        :params params}]
       (binding [*out* stdout]
         (println (platform/json-encode notification))
         (.flush *out*))))))

(defn create-progress-callback
  "Create a progress callback function for operations to report progress.

  token: progress token string
  total: total number of items (nil if unknown)
  opts: optional map with:
    - :max-estimated - maximum estimated progress when total unknown (default 0.9)
    - :estimation-window-ms - time window for estimation (default 60000ms)
    - :method - notification method (default 'notifications/progress')

  Returns function (current-item & [message]) that sends progress notifications.

  If total is provided, calculates progress as current-item/total.
  If total is nil, estimates progress based on elapsed time (max 90% by default)."
  ([token total]
   (create-progress-callback token total nil))
  ([token total opts]
   (let [start-time #?(:clj (System/currentTimeMillis)
                       :cljs (.now js/Date))
         max-estimated (get opts :max-estimated 0.9)
         estimation-window (get opts :estimation-window-ms 60000)
         method (get opts :method "notifications/progress")]
     (fn [current-item & [message]]
       (let [progress (if (and total (> total 0))
                        (min 1.0 (/ (double current-item) total))
                        ;; Estimate based on elapsed time if no total known
                        (let [current-time #?(:clj (System/currentTimeMillis)
                                              :cljs (.now js/Date))
                              elapsed (- current-time start-time)]
                          (min max-estimated (/ elapsed (double estimation-window)))))]
         (send-progress-notification token progress
                                    (cond-> {:method method}
                                      total (assoc :total total)
                                      current-item (assoc :current current-item)
                                      message (assoc :message message))))))))

(defn with-progress
  "Execute a function with progress reporting.

  f: function to execute, receives progress callback as first argument
  token: progress token (generated if not provided)
  total: total items (optional)
  opts: options map (passed to create-progress-callback)

  Returns result of f.

  Example:
    (with-progress
      (fn [progress]
        (doseq [i (range 100)]
          (progress i \"Processing item\")
          (do-work i)))
      nil  ; auto-generate token
      100) ; total items"
  ([f]
   (with-progress f nil nil nil))
  ([f token]
   (with-progress f token nil nil))
  ([f token total]
   (with-progress f token total nil))
  ([f token total opts]
   (let [token (or token (generate-progress-token))
         progress-callback (create-progress-callback token total opts)]
     (f progress-callback))))
