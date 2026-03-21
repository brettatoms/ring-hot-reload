(ns ring.hot-reload.nrepl
  "Optional nREPL middleware that triggers hot reload on eval completion.

   Add to .nrepl.edn:
     {:middleware [ring.hot-reload.nrepl/wrap-hot-reload-nrepl]}"
  (:require [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.transport :as t]
            [ring.hot-reload.core :as core]))

(defn wrap-hot-reload-nrepl
  "nREPL middleware that triggers a hot reload when an eval operation completes.
   Uses the global notification mechanism in ring.hot-reload.core."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= op "eval")
      (handler (assoc msg :transport
                      (reify t/Transport
                        (recv [_]
                          (t/recv (:transport msg)))
                        (recv [_ timeout]
                          (t/recv (:transport msg) timeout))
                        (send [this {:keys [status] :as response}]
                          (t/send (:transport msg) response)
                          (when (and (set? status) (contains? status :done))
                            (future
                              (Thread/sleep 50) ;; brief delay for file writes to settle
                              (core/notify!)))
                          this))))
      (handler msg))))

(set-descriptor! #'wrap-hot-reload-nrepl
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {}})
