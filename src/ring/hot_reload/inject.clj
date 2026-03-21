(ns ring.hot-reload.inject
  "HTML response detection and script injection."
  (:require [clojure.string :as str]))

(defn html-response?
  "Returns true if the response has a text/html content type."
  [{:keys [headers]}]
  (some-> (get headers "Content-Type"
               (get headers "content-type"))
          (str/includes? "text/html")))

(defn inject-script
  "Injects a <script> tag into an HTML response body string.
   Inserts before </body> if present, otherwise appends to end."
  [body script]
  (let [tag (str "\n<script>" script "</script>\n")]
    (if (str/includes? body "</body>")
      (str/replace body "</body>" (str tag "</body>"))
      (str body tag))))

(defn full-html-page?
  "Returns true if the string body contains an <html tag, indicating a full
   HTML document rather than a partial/fragment (e.g. htmx or datastar response)."
  [body]
  (str/includes? body "<html"))

(defn inject-into-response
  "If the response is a full HTML page with a string body, injects the script.
   Partial HTML fragments (e.g. htmx/datastar responses) are left untouched.
   Returns the modified response, or the original if not applicable."
  [response script]
  (if (and (html-response? response)
           (string? (:body response))
           (full-html-page? (:body response)))
    (update response :body inject-script script)
    response))
