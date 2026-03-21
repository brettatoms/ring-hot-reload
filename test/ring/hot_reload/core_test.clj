(ns ring.hot-reload.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
   (:handler (hot/wrap-hot-reload handler opts))))

;; ---------------------------------------------------------------------------
;; Sync handler tests
;; ---------------------------------------------------------------------------

(deftest injects-script-into-html-pages-test
  (let [handler (make-handler)]
    (testing "injects script into full HTML page"
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (clojure.string/includes? (:body response) "ring-hot-reload"))
        (is (clojure.string/includes? (:body response) "Idiomorph"))))))

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
        (is (clojure.string/includes? (:body response) "ring-hot-reload"))))

    (testing "skips when predicate returns false"
      (let [response (handler {:uri "/skip" :request-method :get})]
        (is (not (clojure.string/includes? (:body response) "ring-hot-reload")))))))

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
        (is (clojure.string/includes? (:body @response) "ring-hot-reload"))))))

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
;; Return value tests
;; ---------------------------------------------------------------------------

(deftest return-value-test
  (let [result (hot/wrap-hot-reload html-handler)]
    (testing "returns expected keys"
      (is (fn? (:handler result)))
      (is (some? (:watcher result)))
      (is (fn? (:start! result)))
      (is (fn? (:stop! result)))
      (is (fn? (:notify! result))))))
