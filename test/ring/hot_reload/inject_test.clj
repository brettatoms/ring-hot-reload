(ns ring.hot-reload.inject-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.hot-reload.inject :as inject]))

(deftest html-response?-test
  (testing "detects text/html content type"
    (is (inject/html-response? {:headers {"Content-Type" "text/html"}}))
    (is (inject/html-response? {:headers {"Content-Type" "text/html; charset=utf-8"}})))

  (testing "case-insensitive header name"
    (is (inject/html-response? {:headers {"content-type" "text/html"}})))

  (testing "rejects non-HTML content types"
    (is (not (inject/html-response? {:headers {"Content-Type" "application/json"}})))
    (is (not (inject/html-response? {:headers {"Content-Type" "text/plain"}}))))

  (testing "handles missing headers"
    (is (not (inject/html-response? {:headers {}})))))

(deftest full-html-page?-test
  (testing "detects full HTML pages"
    (is (inject/full-html-page? "<html><body>hello</body></html>"))
    (is (inject/full-html-page? "<!DOCTYPE html><html lang='en'><body></body></html>"))
    (is (inject/full-html-page? "<html>")))

  (testing "rejects HTML fragments"
    (is (not (inject/full-html-page? "<div>partial</div>")))
    (is (not (inject/full-html-page? "<li>todo item</li>")))
    (is (not (inject/full-html-page? "plain text")))))

(deftest inject-script-test
  (testing "inserts before </body>"
    (let [result (inject/inject-script "<html><body>hi</body></html>" "alert(1)")]
      (is (clojure.string/includes? result "<script>alert(1)</script>"))
      (is (clojure.string/includes? result "<script>alert(1)</script>\n</body>"))))

  (testing "appends to end when no </body>"
    (let [result (inject/inject-script "<html><body>hi" "alert(1)")]
      (is (clojure.string/ends-with? result "<script>alert(1)</script>\n")))))

(deftest inject-into-response-test
  (testing "injects into full HTML page response"
    (let [response {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body "<html><body>hello</body></html>"}
          result (inject/inject-into-response response "test()")]
      (is (clojure.string/includes? (:body result) "<script>test()</script>"))))

  (testing "skips non-HTML responses"
    (let [response {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body "{\"key\": \"value\"}"}
          result (inject/inject-into-response response "test()")]
      (is (= response result))))

  (testing "skips HTML fragments (no <html tag)"
    (let [response {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body "<div>partial</div>"}
          result (inject/inject-into-response response "test()")]
      (is (= response result))))

  (testing "skips non-string bodies"
    (let [response {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body (.getBytes "<html><body>hi</body></html>")}
          result (inject/inject-into-response response "test()")]
      (is (= response result)))))
