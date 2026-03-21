(ns ring.hot-reload.watcher
  "File watcher abstraction with a default beholder implementation."
  (:require [nextjournal.beholder :as beholder])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defprotocol Watcher
  (start! [this callback]
    "Start watching. Calls (callback) on file changes. Returns a value
     that can be passed to stop! to terminate watching.")
  (stop! [this handle]
    "Stop watching. `handle` is the value returned by start!."))

(defn- watched-extension? [extensions path]
  (let [s (str path)]
    (some #(.endsWith s %) extensions)))

(defn debounce
  "Returns a debounced function that collects arguments during a delay window.
   `f` is called with the set of all values passed to :invoke during the window.
   Returns a map with :invoke (fn [value]) and :shutdown (to clean up)."
  [f delay-ms]
  (let [^ScheduledExecutorService executor (Executors/newSingleThreadScheduledExecutor)
        pending (atom nil)
        accumulated (atom #{})]
    {:invoke
     (fn [value]
       (when value (swap! accumulated conj value))
       (when-let [prev @pending]
         (.cancel prev false))
       (reset! pending
               (.schedule executor
                          ^Runnable (fn []
                                      (let [paths @accumulated]
                                        (reset! accumulated #{})
                                        (f paths)))
                          (long delay-ms)
                          TimeUnit/MILLISECONDS)))
     :shutdown
     (fn []
       (.shutdown executor))}))

(defn beholder-watcher
  "Creates a Watcher backed by nextjournal/beholder.

   `paths`      - collection of directory paths to watch
   `extensions` - collection of file extension strings (e.g. [\".clj\" \".cljc\"])"
  [paths extensions]
  (let [abs-paths (mapv #(.getCanonicalPath (java.io.File. (str %))) paths)]
    (reify Watcher
      (start! [_ callback]
        (apply beholder/watch
               (fn [{:keys [path]}]
                 (let [s (str path)]
                   (when (watched-extension? extensions s)
                     (callback s))))
               abs-paths))
      (stop! [_ handle]
        (beholder/stop handle)))))
