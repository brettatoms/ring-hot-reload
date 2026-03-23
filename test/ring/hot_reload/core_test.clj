(ns ring.hot-reload.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.hot-reload.core :as hot]))

(def html-handler
  (fn [_request]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body "<html><body><h1>Hello</h1></body></html>"}))

(def json-handler
  (fn [_request]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"ok\":true}"}))

(def fragment-handler
  (fn [_request]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body "<li>a todo item</li>"}))

(def async-html-handler
  (fn
    ([request] (html-handler request))
    ([_request respond _raise]
     (respond {:status 200
               :headers {"Content-Type" "text/html"}
               :body "<html><body><h1>Async</h1></body></html>"}))))

(defn- make-handler
  "Creates a hot-reload handler without starting the watcher."
  ([] (make-handler html-handler))
  ([handler] (make-handler handler {}))
  ([handler opts]
   (hot/wrap-hot-reload handler (hot/hot-reloader opts))))

;; ---------------------------------------------------------------------------
;; Sync handler tests
;; ---------------------------------------------------------------------------

(deftest injects-script-into-html-pages-test
  (let [handler (make-handler)]
    (testing "injects script into full HTML page"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "ring-hot-reload"))
        (is (str/includes? (:body response) "Idiomorph"))))))

(deftest skips-non-html-responses-test
  (let [handler (make-handler json-handler)]
    (testing "does not inject into JSON responses"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= "{\"ok\":true}" (:body response)))))))

(deftest skips-html-fragments-test
  (let [handler (make-handler fragment-handler)]
    (testing "does not inject into HTML fragments"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= "<li>a todo item</li>" (:body response)))))))

(deftest websocket-endpoint-test
  (let [handler (make-handler)]
    (testing "returns 400 for non-upgrade requests to WS path"
      (let [response (handler {:uri "/__hot-reload" :request-method :get})]
        (is (= 400 (:status response)))))

    (testing "custom uri-prefix"
      (let [handler (make-handler html-handler {:uri-prefix "/__custom"})]
        (let [response (handler {:uri "/__custom" :request-method :get})]
          (is (= 400 (:status response))))
        ;; Normal requests still work
        (let [response (handler {:uri "/" :request-method :get})]
          (is (= 200 (:status response))))))))

(deftest inject-predicate-test
  (let [handler (make-handler html-handler
                              {:inject? (fn [request _response]
                                          (not= (:uri request) "/skip"))})]
    (testing "injects when predicate returns true"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (str/includes? (:body response) "ring-hot-reload"))))

    (testing "skips when predicate returns false"
      (let [response (handler {:uri "/skip" :request-method :get})]
        (is (not (str/includes? (:body response) "ring-hot-reload")))))))

;; ---------------------------------------------------------------------------
;; Async handler tests
;; ---------------------------------------------------------------------------

(deftest async-injects-script-test
  (let [handler (make-handler async-html-handler)]
    (testing "injects script in async mode"
      (let [response (promise)
            error (promise)]
        (handler {:uri "/" :request-method :get}
                 #(deliver response %)
                 #(deliver error %))
        (is (str/includes? (:body @response) "ring-hot-reload"))))))

(deftest async-skips-fragments-test
  (let [handler (make-handler
                 (fn
                   ([req] (fragment-handler req))
                   ([_req respond _raise]
                    (respond {:status 200
                              :headers {"Content-Type" "text/html"}
                              :body "<li>async fragment</li>"}))))]
    (testing "does not inject into async fragments"
      (let [response (promise)]
        (handler {:uri "/" :request-method :get}
                 #(deliver response %)
                 identity)
        (is (= "<li>async fragment</li>" (:body @response)))))))

(deftest async-websocket-endpoint-test
  (let [handler (make-handler async-html-handler)]
    (testing "returns 400 for non-upgrade async requests to WS path"
      (let [response (promise)]
        (handler {:uri "/__hot-reload" :request-method :get}
                 #(deliver response %)
                 identity)
        (is (= 400 (:status @response)))))))

;; ---------------------------------------------------------------------------
;; hot-reloader tests
;; ---------------------------------------------------------------------------

(deftest hot-reloader-return-value-test
  (let [hr (hot/hot-reloader)]
    (testing "returns expected keys"
      (is (fn? (:ws-handler hr)))
      (is (fn? (:injection-middleware hr)))
      (is (string? (:script hr)))
      (is (string? (:uri-prefix hr))))))

(deftest hot-reloader-ws-handler-test
  (let [{:keys [ws-handler]} (hot/hot-reloader)]
    (testing "returns 400 for non-upgrade requests"
      (let [response (ws-handler {:uri "/__hot-reload" :request-method :get})]
        (is (= 400 (:status response)))))

    (testing "async: returns 400 for non-upgrade requests"
      (let [response (promise)]
        (ws-handler {:uri "/__hot-reload" :request-method :get}
                    #(deliver response %)
                    identity)
        (is (= 400 (:status @response)))))))

(deftest hot-reloader-injection-middleware-test
  (let [{:keys [injection-middleware]} (hot/hot-reloader)]
    (testing "injects script into full HTML page"
      (let [handler (injection-middleware html-handler)
            response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "ring-hot-reload"))))

    (testing "does not inject into JSON responses"
      (let [handler (injection-middleware json-handler)
            response (handler {:uri "/" :request-method :get})]
        (is (= "{\"ok\":true}" (:body response)))))

    (testing "does not inject into HTML fragments"
      (let [handler (injection-middleware fragment-handler)
            response (handler {:uri "/" :request-method :get})]
        (is (= "<li>a todo item</li>" (:body response)))))

    (testing "async: injects script into full HTML page"
      (let [handler (injection-middleware async-html-handler)
            response (promise)]
        (handler {:uri "/" :request-method :get}
                 #(deliver response %)
                 identity)
        (is (str/includes? (:body @response) "ring-hot-reload"))))))

(deftest hot-reloader-injection-predicate-test
  (let [{:keys [injection-middleware]}
        (hot/hot-reloader {:inject? (fn [request _response]
                                      (not= (:uri request) "/skip"))})]
    (testing "injects when predicate returns true"
      (let [handler (injection-middleware html-handler)
            response (handler {:uri "/" :request-method :get})]
        (is (str/includes? (:body response) "ring-hot-reload"))))

    (testing "skips when predicate returns false"
      (let [handler (injection-middleware html-handler)
            response (handler {:uri "/skip" :request-method :get})]
        (is (not (str/includes? (:body response) "ring-hot-reload")))))))

(deftest hot-reloader-script-contains-uri-prefix-test
  (let [{:keys [script]} (hot/hot-reloader {:uri-prefix "/__custom"})]
    (testing "script contains the configured uri-prefix"
      (is (str/includes? script "__custom")))))

(deftest wrap-hot-reload-is-proper-middleware-test
  (let [hr (hot/hot-reloader)
        handler (hot/wrap-hot-reload html-handler hr)]
    (testing "returns a function (not a map)"
      (is (fn? handler)))

    (testing "injects script"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (str/includes? (:body response) "ring-hot-reload"))))

    (testing "handles WS endpoint"
      (let [response (handler {:uri "/__hot-reload" :request-method :get})]
        (is (= 400 (:status response)))))))
