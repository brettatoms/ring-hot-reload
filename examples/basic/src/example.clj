(ns example
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.hot-reload.core :as hot]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.params :refer [wrap-params]]))

;; -----------------------------------------------------------------------
;; In-memory todo store
;; -----------------------------------------------------------------------

(defonce todos (atom []))
(defonce next-id (atom 0))

(defn add-todo! [title]
  (let [id (swap! next-id inc)]
    (swap! todos conj {:id id :title title :done? false})
    id))

(defn toggle-todo! [id]
  (swap! todos (fn [ts] (mapv #(if (= (:id %) id) (update % :done? not) %) ts))))

(defn delete-todo! [id]
  (swap! todos (fn [ts] (vec (remove #(= (:id %) id) ts)))))

;; -----------------------------------------------------------------------
;; HTML rendering
;; -----------------------------------------------------------------------

(defn render-todo-item [{:keys [id title done?]}]
  (str "<li" (when done? " class=\"done\"") ">"
       "<input type=\"checkbox\""
       (when done? " checked")
       " hx-post=\"/todos/" id "/toggle\""
       " hx-target=\"#todo-list\""
       " hx-swap=\"innerHTML\">"
       "<span>" title "</span>"
       "<button class=\"delete-btn\""
       " hx-delete=\"/todos/" id "\""
       " hx-target=\"#todo-list\""
       " hx-swap=\"innerHTML\">"
       "&times;</button>"
       "</li>"))

(defn render-todo-list []
  (str/join "\n" (map render-todo-item @todos)))

(defn render-page []
  (-> (slurp (io/file "resources/templates/page.html"))
      (str/replace "{{todos}}" (render-todo-list))))

;; -----------------------------------------------------------------------
;; Ring handler
;; -----------------------------------------------------------------------

(defn handler [{:keys [uri request-method form-params]}]
  (cond
    ;; GET / — full page
    (and (= uri "/") (= request-method :get))
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (render-page)}

    ;; POST /todos — add todo, return updated list
    (and (= uri "/todos") (= request-method :post))
    (do (add-todo! (get form-params "title"))
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (render-todo-list)})

    ;; POST /todos/:id/toggle — toggle todo
    (and (re-matches #"/todos/(\d+)/toggle" uri) (= request-method :post))
    (let [id (parse-long (second (re-matches #"/todos/(\d+)/toggle" uri)))]
      (toggle-todo! id)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (render-todo-list)})

    ;; DELETE /todos/:id — delete todo
    (and (re-matches #"/todos/(\d+)" uri) (= request-method :delete))
    (let [id (parse-long (second (re-matches #"/todos/(\d+)" uri)))]
      (delete-todo! id)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (render-todo-list)})

    :else
    {:status 404 :body "Not found"}))

;; -----------------------------------------------------------------------
;; Server setup
;; -----------------------------------------------------------------------

(defonce server (atom nil))
(defonce watcher (atom nil))
(defonce stop-fn (atom nil))

(defn start! []
  (let [{:keys [handler start! stop!]}
        (hot/wrap-hot-reload (-> #'handler
                                wrap-params
                                (wrap-file "resources/public"))
                             {:watch-paths ["src" "resources"]
                              :bust-css-cache? true})]
    (reset! stop-fn stop!)
    (reset! watcher (start!))
    (reset! server (jetty/run-jetty handler {:port 3000 :join? false}))
    (println "Server running at http://localhost:3000")
    (println "Edit resources/templates/page.html to see hot reload in action!")))

(defn stop! []
  (when-let [s @server]
    (.stop s)
    (reset! server nil))
  (when-let [w @watcher]
    (when-let [f @stop-fn]
      (f w))
    (reset! watcher nil))
  (println "Server stopped."))

(defn -main [& _args]
  (start!)
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop!))))

(comment
  ;; Start the server from the REPL
  (start!)

  ;; Stop the server
  (stop!)

  ;; Restart
  (do (stop!) (start!))

  ;; After starting, open http://localhost:3000
  ;; Then try evaluating this — the browser should update automatically
  ;; (requires nREPL middleware from .nrepl.edn)
  )
