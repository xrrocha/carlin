(ns carlin.to-js-test
  "Spec §6.3 — the parachute. Narrow domain, script-context injection safety."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.runtime :as rt]))

(deftest domain
  (testing "scalars"
    (is (= "null" (rt/->js nil)))
    (is (= "true" (rt/->js true)))
    (is (= "42" (rt/->js 42)))
    (is (= "1.5" (rt/->js 1.5)))
    (is (= "\"hi\"" (rt/->js "hi"))))
  (testing "collections; keyword and string keys"
    (is (= "[1,2,3]" (rt/->js [1 2 3])))
    (is (= "[1,2]" (rt/->js (map inc [0 1]))))
    (is (= "{\"id\":7,\"tag name\":\"x\"}" (rt/->js {:id 7 "tag name" "x"}))))
  (testing "nesting"
    (is (= "{\"xs\":[{\"a\":null}]}" (rt/->js {:xs [{:a nil}]})))))

(deftest domain-violations-throw
  (testing "never a guess (§6.3)"
    (is (thrown? Exception (rt/->js 'sym)))
    (is (thrown? Exception (rt/->js #{1})))
    (is (thrown? Exception (rt/->js (fn []))))))

(deftest script-context-safety
  (testing "</script> breakout is closed: < escapes to \\u003C"
    (let [out (rt/->js "</script><script>alert(1)")]
      (is (not (re-find #"(?i)</script" out)))
      (is (re-find #"\\u003[Cc]" out))))
  (testing "<!-- comment-opening is neutralized"
    (is (not (re-find #"<!--" (rt/->js "<!-- x")))))
  (testing "quotes and backslashes are JSON-escaped"
    (is (= "\"a\\\"b\\\\c\"" (rt/->js "a\"b\\c")))))
