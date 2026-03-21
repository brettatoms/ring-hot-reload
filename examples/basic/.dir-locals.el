((clojure-mode
  (eval . (progn
            (make-local-variable 'cider-jack-in-nrepl-middlewares)
            (add-to-list 'cider-jack-in-nrepl-middlewares "ring.hot-reload.nrepl/wrap-hot-reload-nrepl")))))
