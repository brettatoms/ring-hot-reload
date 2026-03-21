(ns ring.hot-reload.client
  "Generates client-side JavaScript for hot reload."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- load-resource [path]
  (slurp (io/resource path)))

(def ^:private idiomorph-js (delay (load-resource "ring/hot_reload/idiomorph.min.js")))
(def ^:private websocket-js (delay (load-resource "ring/hot_reload/js/websocket.js")))

(def ^:private reload-js
  (delay
    (let [template (load-resource "ring/hot_reload/js/overlay.html")
          ;; Collapse to single line and escape for JS string literal
          escaped (-> template
                      str/trim
                      (str/replace "\n" "")
                      (str/replace "'" "\\'"))
          js (load-resource "ring/hot_reload/js/reload.js")]
      (str/replace js "{{overlay-template}}" escaped))))

(defn standalone-script
  "Generates the full client script for standalone WebSocket mode."
  [uri-prefix {:keys [bust-css-cache?]}]
  (str "(function() {\n"
       @idiomorph-js "\n"
       (str/replace @reload-js "{{bust-css-cache}}" (if bust-css-cache? "true" "false"))
       "\n"
       (str/replace @websocket-js "{{uri-prefix}}" (pr-str uri-prefix))
       "\n})();"))
