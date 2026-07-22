(ns carlin.diagnostics-test
  "Spec §8.3/§12.4 — the diagnostics corpus. Asserts error CLASS and POSITION
  ({:carlin/error kw, :line n, :col n}), never message prose. Red until the
  real compiler lands (the legacy adapter throws unclassified errors)."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.api :as api]))

(defn- err
  ([source] (err source nil))
  ([source opts]
   (try (api/compile-template source (merge {:name "test.carlin"} opts))
        nil
        (catch Exception e (ex-data e)))))

(defn- classed? [d k]
  (and d (= k (:carlin/error d)) (pos-int? (:line d)) (int? (:col d))))

(deftest structural-errors
  (testing "unterminated form in a restrictive-four position (§3.1)"
    (is (classed? (err "a{:href \"x\"") :unterminated-form)))
  (testing "multi-line forms refused outside restrictive-four (§3.1 Q4)"
    (is (classed? (err "if (and a\n  p x") :unterminated-form)))
  (testing "extends must be first (§3.14)"
    (is (classed? (err "p x\nextends /base") :extends-not-first)))
  (testing "extending file top level restricted (§3.14)"
    (is (classed? (err "extends /base\np loose") :invalid-under-extends)))
  (testing "block via include is composition-leak (§3.11 Q9)"
    ;; The stub resolver makes :block-in-include REACHABLE: without it the
    ;; include is (correctly) :unresolvable-ref before the target's tree can
    ;; be seen. Stays red until include-splice lands — the pass that parses
    ;; the resolved target is the pass that can detect the leak.
    (is (classed? (err "include has-block"
                       {:resolver (fn [_from ref]
                                    (when (= ref "has-block")
                                      {:key    "has-block.carlin"
                                       :source "block content\n  p leaked"
                                       :kind   :template}))})
                  :block-in-include)))
  (testing "yield outside an included file (§3.11)"
    (is (classed? (err "yield") :yield-outside-include))
    ;; …including inside an include BODY: the body is written in the root
    ;; file, and yield legality follows the include EDGE, not the composed
    ;; tree (probe values: name ≠ value, rev. 13's lesson)
    (is (classed? (err "include lib\n  yield"
                       {:resolver (fn [_ ref]
                                    (when (= ref "lib")
                                      {:key "lib.carlin"
                                       :source "p in lib\nyield"
                                       :kind :template}))})
                  :yield-outside-include)))
  (testing "yield takes no children (§3.11; pug agrees: syntax error)"
    (is (classed? (err "yield\n  p child") :yield-children)))
  (testing "body on a raw include (§3.11; pug: 'Raw inclusion cannot contain a block')"
    (is (classed? (err "include styles.css\n  p body"
                       {:resolver (fn [_ ref]
                                    (when (= ref "styles.css")
                                      {:key "styles.css"
                                       :source "body { margin: 0 }"
                                       :kind :raw}))})
                  :body-in-raw-include))
    (is (classed? (err "include:verbatim lib\n  p body"
                       {:resolver (fn [_ ref]
                                    (when (= ref "lib")
                                      {:key "lib.carlin"
                                       :source "p in lib"
                                       :kind :template}))})
                  :body-in-raw-include)))
  (testing "body on an include whose target has no yield (§3.11, S17 PROVISIONAL —
            pug buries it at the deepest last block; awaiting ruling)"
    (is (classed? (err "include lib\n  p body"
                       {:resolver (fn [_ ref]
                                    (when (= ref "lib")
                                      {:key "lib.carlin"
                                       :source "p no splice point here"
                                       :kind :template}))})
                  :body-without-yield)))
  (testing "nested mixin definition (§3.13)"
    (is (classed? (err "div\n  mixin f [x]\n    p= x") :nested-mixin)))
  (testing "mixin arity mismatch, compile time (§3.13 D11)"
    (is (classed? (err "mixin card [t]\n  p= t\n+(card 1 2 3)") :mixin-arity)))
  (testing "case: default not last (§3.7)"
    (is (classed? (err "case x\n  default\n    p d\n  when 1\n    p one") :default-not-last)))
  (testing "static attr conflict under :error (§4.6)"
    (is (classed? (err "a#foo{:id \"bar\"}") :attr-conflict)))
  (testing "unresolvable include ref (§5.3)"
    (is (classed? (err "include nowhere") :unresolvable-ref)))
  (testing "while is excluded, with a positioned error (§10)"
    (is (classed? (err "while (pos? n)\n  p x") :unsupported-directive))))

(deftest position-quality
  (testing "line numbers point at the offender, not line 1"
    (let [d (err "p ok\np ok\na{:href \"x\"")]
      (is (= 3 (:line d))))))
