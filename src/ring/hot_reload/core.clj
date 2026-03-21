(ns ring.hot-reload.core
  "Ring middleware for hot reload of server-rendered applications."
  (:require [clojure.tools.logging :as log]
            [ring.hot-reload.client :as client]
            [ring.hot-reload.inject :as inject]
            [ring.hot-reload.watcher :as watcher]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as wsp]))

;; ---------------------------------------------------------------------------
;; Global notification atom for nREPL integration
;; ---------------------------------------------------------------------------

(defonce ^:private notify-fns (atom #{}))

(defn register-notify-fn!
  "Registers a notification function. Called by the middleware on setup
   so that the nREPL middleware can trigger reloads."
  [f]
  (swap! notify-fns conj f))

(defn deregister-notify-fn!
  "Removes a previously registered notification function."
  [f]
  (swap! notify-fns disj f))

(defn notify!
  "Triggers a reload notification to all registered listeners.
   Used by the nREPL middleware."
  []
  (doseq [f @notify-fns]
    (try (f) (catch Exception _))))

;; ---------------------------------------------------------------------------
;; WebSocket client management
;; ---------------------------------------------------------------------------

(defn- make-ws-state []
  (atom #{}))

(defn- notify-ws-clients! [clients-atom]
  (let [clients @clients-atom]
    (log/debug "Notifying" (count clients) "client(s)")
    (doseq [socket clients]
      (try
        (when (ws/open? socket)
          (ws/send socket "{\"type\":\"reload\"}"))
        (catch Exception e
          (log/warn "WebSocket send failed:" (.getMessage e))
          (swap! clients-atom disj socket))))))

(defn- ws-listener [clients-atom]
  (reify wsp/Listener
    (on-open [_ socket]
      (log/debug "WebSocket client connected")
      (swap! clients-atom conj socket))
    (on-message [_ _ _])
    (on-pong [_ _ _])
    (on-error [_ socket _]
      (swap! clients-atom disj socket))
    (on-close [_ socket _ _]
      (swap! clients-atom disj socket))))

(defn- ws-handler [clients-atom]
  (fn [_request]
    {::ws/listener (ws-listener clients-atom)}))

;; ---------------------------------------------------------------------------
;; wrap-hot-reload
;; ---------------------------------------------------------------------------

(defn wrap-hot-reload
  "Ring middleware that provides hot reload for server-rendered HTML responses.

   Watches file system paths for changes and notifies connected browsers via
   WebSocket. The browser re-fetches the page and morphs the DOM in place.

   Options:
     :watch-paths      - directories to watch (default [\"src\"])
     :watch-extensions - file extensions to trigger reload
                         (default #{\"clj\" \".cljc\" \".edn\" \".html\" \".css\"})
     :uri-prefix       - WebSocket endpoint path (default \"/__hot-reload\")
     :inject?          - predicate (fn [request response]) controlling injection
                         (default: always inject into HTML responses)
     :debounce-ms      - debounce window in ms (default 100)
     :bust-css-cache?  - append cache-busting param to stylesheet URLs on
                         reload (default false). Useful when serving plain CSS
                         without Vite or hashed filenames.

   Lifecycle: The caller is responsible for starting/stopping the watcher.
   This middleware returns a map with:
     :handler      - the Ring handler to use
     :watcher      - the Watcher instance
     :start!       - fn [] that starts watching; returns a handle
     :stop!        - fn [handle] that stops watching
     :notify!      - fn [] that manually triggers a reload"
  [handler & [{:keys [watch-paths
                      watch-extensions
                      uri-prefix
                      inject?
                      debounce-ms
                      bust-css-cache?]
               :or {watch-paths      ["src"]
                    watch-extensions #{".clj" ".cljc" ".edn" ".html" ".css"}
                    uri-prefix       "/__hot-reload"
                    inject?          (constantly true)
                    debounce-ms      100
                    bust-css-cache?  false}}]]
  (let [clients     (make-ws-state)
        ws-handle   (ws-handler clients)
        script      (client/standalone-script uri-prefix {:bust-css-cache? bust-css-cache?})
        w           (watcher/beholder-watcher watch-paths watch-extensions)
        {:keys [invoke shutdown]}
        (watcher/debounce (fn [_changed-paths]
                            (log/debug "File change detected, triggering reload")
                            (notify-ws-clients! clients))
                          debounce-ms)
        notify-fn   #(invoke nil)
        ring-handler
        (fn
          ([request]
           (if (= (:uri request) uri-prefix)
             (if (ws/upgrade-request? request)
               (ws-handle request)
               {:status 400 :body "WebSocket upgrade required"})
             (let [response (handler request)]
               (if (inject? request response)
                 (inject/inject-into-response response script)
                 response))))
          ([request respond raise]
           (if (= (:uri request) uri-prefix)
             (if (ws/upgrade-request? request)
               (respond (ws-handle request))
               (respond {:status 400 :body "WebSocket upgrade required"}))
             (handler request
                      (fn [response]
                        (respond (if (inject? request response)
                                   (inject/inject-into-response response script)
                                   response)))
                      raise))))]
    ;; Register for nREPL notifications
    (register-notify-fn! notify-fn)
    {:handler  ring-handler
     :watcher  w
     :start!   (fn [] (watcher/start! w invoke))
     :stop!    (fn [handle]
                 (watcher/stop! w handle)
                 (shutdown)
                 (deregister-notify-fn! notify-fn))
     :notify!  notify-fn}))
