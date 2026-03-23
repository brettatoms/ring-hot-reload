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

;; ---------------------------------------------------------------------------
;; hot-reloader
;; ---------------------------------------------------------------------------

(defn hot-reloader
  "Creates a hot reloader — an immutable map of composable pieces for hot
   reload. Does not start watching; call `start!` to begin.

   Returns a map with:

     :ws-handler           - Ring handler for the WebSocket endpoint. Mount
                             at a route (e.g. \"/__hot-reload\") or let
                             `wrap-hot-reload` handle routing automatically.
                             Handles both sync and async Ring.

     :injection-middleware - Ring middleware (fn [handler] -> handler) that
                             injects the client script into full HTML page
                             responses. Partial responses (htmx fragments,
                             etc.) are left untouched.

     :script               - JavaScript string containing the client code
                             (idiomorph, WebSocket reconnection, error overlay).

     :uri-prefix           - The WebSocket endpoint path.

   Lifecycle functions (call these yourself):

     (start! reloader)        — starts watching; returns a handle
     (stop! reloader handle)  — stops watching, cleans up resources

   Options:
     :watch-paths      - directories to watch (default [\"src\"])
     :watch-extensions - file extensions that trigger reload
                         (default #{\"clj\" \".cljc\" \".edn\" \".html\" \".css\"})
     :uri-prefix       - WebSocket endpoint path (default \"/__hot-reload\")
     :inject?          - predicate (fn [request response]) controlling script
                         injection (default: always inject into HTML responses)
     :debounce-ms      - debounce window in ms (default 100)
     :bust-css-cache?  - append cache-busting param to stylesheet URLs on
                         reload (default false)"
  [& [{:keys [watch-paths
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
        ws-handle   (fn [_request]
                      {::ws/listener (ws-listener clients)})
        script      (client/standalone-script uri-prefix {:bust-css-cache? bust-css-cache?})
        w           (watcher/beholder-watcher watch-paths watch-extensions)
        {:keys [invoke shutdown]}
        (watcher/debounce (fn [_changed-paths]
                            (log/debug "File change detected, triggering reload")
                            (notify-ws-clients! clients))
                          debounce-ms)
        notify-fn   #(invoke nil)]
    ;; Register for nREPL notifications
    (register-notify-fn! notify-fn)
    {:ws-handler
     (fn
       ([request]
        (if (ws/upgrade-request? request)
          (ws-handle request)
          {:status 400 :body "WebSocket upgrade required"}))
       ([request respond _raise]
        (if (ws/upgrade-request? request)
          (respond (ws-handle request))
          (respond {:status 400 :body "WebSocket upgrade required"}))))

     :injection-middleware
     (fn [handler]
       (fn
         ([request]
          (let [response (handler request)]
            (if (inject? request response)
              (inject/inject-into-response response script)
              response)))
         ([request respond raise]
          (handler request
                   (fn [response]
                     (respond (if (inject? request response)
                                (inject/inject-into-response response script)
                                response)))
                   raise))))

     :script     script
     :uri-prefix uri-prefix

     ;; Private — used by start!/stop!
     ::watcher   w
     ::invoke    invoke
     ::shutdown  shutdown
     ::notify-fn notify-fn}))

(defn start!
  "Starts the file watcher for a hot reloader. Returns a handle to pass
   to `stop!`."
  [reloader]
  (watcher/start! (::watcher reloader) (::invoke reloader)))

(defn stop!
  "Stops the file watcher for a hot reloader. `handle` is the value
   returned by `start!`."
  [reloader handle]
  (watcher/stop! (::watcher reloader) handle)
  ((::shutdown reloader))
  (deregister-notify-fn! (::notify-fn reloader)))

;; ---------------------------------------------------------------------------
;; wrap-hot-reload
;; ---------------------------------------------------------------------------

(defn wrap-hot-reload
  "Ring middleware that provides hot reload for server-rendered HTML responses.

   Takes a Ring handler and a hot reloader (from `hot-reloader`), returns a
   new Ring handler that:
   1. Intercepts requests to the WebSocket endpoint
   2. Injects the client script into full HTML page responses
   3. Passes all other requests through unchanged

   This is a standard Ring middleware — it takes a handler and returns a handler.

   Example:
     (let [hr  (hot/hot-reloader {:watch-paths [\"src\"]})
           app (hot/wrap-hot-reload my-handler hr)
           h   (hot/start! hr)]
       ;; app is your Ring handler
       ;; later: (hot/stop! hr h))

   For more control (e.g. mounting the WebSocket endpoint as a router route
   and adding injection as separate middleware), use the reloader map keys
   directly: `:ws-handler`, `:injection-middleware`, `:script`."
  [handler reloader]
  (let [{:keys [ws-handler injection-middleware uri-prefix]} reloader
        inject-handler (injection-middleware handler)]
    (fn
      ([request]
       (if (= (:uri request) uri-prefix)
         (ws-handler request)
         (inject-handler request)))
      ([request respond raise]
       (if (= (:uri request) uri-prefix)
         (ws-handler request respond raise)
         (inject-handler request respond raise))))))
