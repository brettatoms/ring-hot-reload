(ns ring.hot-reload.watcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.hot-reload.watcher :as watcher]))

(deftest watched-extension?-test
  (testing "matches file extensions"
    (is (#'watcher/watched-extension? #{".clj" ".cljc"} "src/foo.clj"))
    (is (#'watcher/watched-extension? #{".clj" ".cljc"} "src/foo.cljc"))
    (is (#'watcher/watched-extension? #{".html"} "/tmp/templates/page.html")))

  (testing "rejects non-matching extensions"
    (is (not (#'watcher/watched-extension? #{".clj"} "src/foo.js")))
    (is (not (#'watcher/watched-extension? #{".clj"} "src/foo.cljs")))))

(deftest debounce-test
  (testing "coalesces rapid calls into one"
    (let [calls (atom [])
          {:keys [invoke shutdown]} (watcher/debounce
                                     (fn [paths] (swap! calls conj paths))
                                     50)]
      (invoke "a.clj")
      (invoke "b.clj")
      (invoke "c.clj")
      (Thread/sleep 200)
      (is (= 1 (count @calls)))
      (is (= #{"a.clj" "b.clj" "c.clj"} (first @calls)))
      (shutdown)))

  (testing "fires separately for calls outside debounce window"
    (let [calls (atom [])
          {:keys [invoke shutdown]} (watcher/debounce
                                     (fn [paths] (swap! calls conj paths))
                                     50)]
      (invoke "a.clj")
      (Thread/sleep 200)
      (invoke "b.clj")
      (Thread/sleep 200)
      (is (= 2 (count @calls)))
      (is (= #{"a.clj"} (first @calls)))
      (is (= #{"b.clj"} (second @calls)))
      (shutdown)))

  (testing "handles nil values from manual notify"
    (let [calls (atom [])
          {:keys [invoke shutdown]} (watcher/debounce
                                     (fn [paths] (swap! calls conj paths))
                                     50)]
      (invoke nil)
      (Thread/sleep 200)
      (is (= 1 (count @calls)))
      (is (= #{} (first @calls)))
      (shutdown))))
