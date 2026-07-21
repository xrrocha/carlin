(ns carlin.resolver-test
  "Spec §5.3/§5.4 — resolver contract and the shipped file-resolver battery.
  Targets carlin.core/file-resolver (future); red until it lands."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(defn- file-resolver []
  (try @(requiring-resolve 'carlin.core/file-resolver)
       (catch Throwable _ nil)))

(def root "test-fixtures/templates")

(defn- setup! []
  (doseq [[p c] {"index.carlin" "extends /layouts/base\nblock content\n  p hi"
                 "layouts/base.carlin" "html\n  body\n    block content"
                 "partials/nav.carlin" "nav here"
                 "partials/deep/leaf.carlin" "include ../nav"
                 "styles.css" ".a{}"}]
    (let [f (io/file root p)]
      (io/make-parents f) (spit f c))))

(deftest file-resolver-contract
  (if-let [fr (file-resolver)]
    (let [r (fr root)]
      (setup!)
      (testing "returns {:key :source :kind}; nil on miss"
        (let [{:keys [key source kind]} (r nil "index")]
          (is (string? key)) (is (string? source)) (is (= :template kind)))
        (is (nil? (r nil "nope"))))
      (testing "extension defaulting: as-given first, then .carlin (§5.4)"
        (is (= :template (:kind (r nil "index.carlin"))))
        (is (some? (r nil "index"))))
      (testing "non-.carlin extension -> :kind :raw"
        (is (= :raw (:kind (r nil "styles.css")))))
      (testing "relative refs anchor to the includer; / anchors to root"
        (let [leaf (:key (r nil "partials/deep/leaf"))]
          (is (some? (r leaf "../nav")))
          (is (some? (r leaf "/layouts/base")))))
      (testing "root-jail: escapes are refused, never read"
        (let [k (:key (r nil "index"))]
          (is (nil? (r k "../../../etc/passwd")))
          (is (nil? (r k "/../outside"))))))
    (is false "carlin.core/file-resolver not implemented yet")))

(deftest deps-tracking
  (if-let [cr (try @(requiring-resolve 'carlin.core/compile-ref)
                   (catch Throwable _ nil))]
    (let [fr (file-resolver) _ (setup!)
          c (cr "index" {:resolver (fr root)})]
      (testing ":deps holds every resolver key touched (§5.2)"
        (is (>= (count (:deps c)) 2))))
    (is false "carlin.core/compile-ref not implemented yet")))
