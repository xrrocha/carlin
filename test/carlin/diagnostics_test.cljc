(ns carlin.diagnostics-test
  "Spec §8.3/§12.4 — the diagnostics corpus. Asserts error CLASS and POSITION
  ({:carlin/error kw, :line n, :col n}), never message prose, so wording may
  evolve freely while the data contract holds.

  Since S29 this covers BOTH halves of the pipeline: the back half no longer
  bails out to carlin.legacy on constructs it cannot compile, it raises the
  same positioned :carlin/error the front half does."
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
  (testing "S27: the check battery reaches INSIDE #[…] interpolation"
    ;; Before S27 each of these compiled clean and died at runtime as an
    ;; unclassified sci ArityException — the fragment was an opaque string
    ;; until codegen re-parsed it, so no tree walk could see the call.
    ;; Hoisting fragments at parse time is what makes these positioned.
    (testing "too many args, inline position"
      (is (classed? (err "mixin card [t]\n  p= t\np x #[+(card 1 2 3)] y")
                    :mixin-arity)))
    (testing "too few args, inline position — the S28 spelling with a\n             mixin that actually takes one"
      (is (classed? (err "mixin card [t]\n  p= t\np x #[+card \"text\"] y")
                    :mixin-arity)))
    (testing "inside a fragment nested in another fragment's inline text"
      (is (classed? (err "mixin card [t]\n  p= t\np x #[b deep #[+(card 1 2)] out] y")
                    :mixin-arity)))
    (testing "inside a dot block's captured body"
      (is (classed? (err "mixin card [t]\n  p= t\np.\n  line #[+(card 1 2)] end")
                    :mixin-arity)))
    (testing "inside a tagless text block's captured body"
      (is (classed? (err "mixin card [t]\n  p= t\n.\n  line #[+(card 1 2)] end")
                    :mixin-arity)))
    (testing "a check OTHER than arity also reaches inline — the point of\n             fixing the class rather than the instance"
      (is (classed? (err "p x #[div: yield] y") :yield-outside-include)))
    (testing "but NOT bodies that never interpolate: a comment body emits\n             raw and a filter runs before the model exists, so an arity\n             error written there is not a call and must not fail the build"
      (is (nil? (err "mixin card [t]\n  p= t\n//\n  line #[+(card 1 2)] end")))))
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

(deftest back-half-fails-fast
  "S29 — the five constructs that used to bail out WHOLESALE to carlin.legacy,
   which rendered them as markup invented from a keyword (`when 1` outside a
   case became <when>1</when>; an undefined +nope became the literal text
   `+nope`). pug 3.0.2 errors on every one. They are now positioned
   :carlin/error like any front-half diagnostic, and legacy is retired.

   Each pin puts the offender on line 2+ so a class that merely defaulted to
   line 1 could not pass."
  (testing "`when` outside a case"
    (is (classed? (err "p a\nwhen 1\n  p hi") :stray-when)))
  (testing "`default` outside a case"
    (is (classed? (err "p a\ndefault\n  p hi") :stray-default)))
  (testing "a non-when/default child of a case"
    (is (classed? (err "case 1\n  p not-a-when") :case-clause)))
  (testing "an unnamed block outside a mixin has no yield to bind to —
            pug: 'Anonymous blocks are not allowed unless they are part of
            a mixin'"
    (is (classed? (err "p a\nblock\n  p hi") :anonymous-block)))
  (testing "a call to a mixin defined nowhere, naming the mixin"
    (let [d (err "p a\n+(nope 1)")]
      (is (classed? d :undefined-mixin))
      (is (= 'nope (:mixin d)))))
  (testing "the bare spelling too — the rev. 13 lesson: a lesson learned in
            one position recurs one position over"
    (is (classed? (err "+nope") :undefined-mixin)))
  (testing "and the errors are POSITIONED, not merely classed: the back half
            now carries the compiling template's cursor"
    (is (= 3 (:line (err "div\n  p ok\nwhen 1\n  p hi"))))))

(deftest legal-templates-unaffected
  "S29's converse, and the reason the ratchet did not move: fail-fast must
   reject only what is broken. Each of these was probed as reaching codegen
   with its mixin fully in hand."
  (testing "a named block outside extends is dissolved, never :anonymous-block"
    (is (nil? (err "block foo\n  p hi"))))
  (testing "a legal mixin call compiles"
    (is (nil? (err "mixin g [a]\n  p= a\n+(g 1)"))))
  (testing "a mixin called BEFORE its definition still resolves (positional
            redefinition, not declaration order)"
    (is (nil? (err "+(later)\nmixin later []\n  p x")))))

(deftest mixin-table-invariant
  "S29 — the guard the :undefined-mixin check silently depends on.

   carlin.core/walk-checks collects mixin definitions RECURSIVELY;
   codegen/compile-tree collects them from the TOP LEVEL ONLY. Those two
   walks must agree, and they can only agree because :nested-mixin forbids a
   definition below depth 0. If that guard is ever relaxed without making
   codegen's walk recursive, a legal call to a nested definition reads as
   ::absent and S29 rejects a valid template.

   This pin exists so the guard cannot be removed silently: it is not a test
   of :nested-mixin for its own sake, it is the load-bearing half of the
   arity and undefined-mixin machinery. Fix the class, then pin it."
  (testing "a mixin definition below top level is refused at parse time"
    (is (classed? (err "div\n  mixin inner []\n    p x") :nested-mixin)))
  (testing "which is what makes codegen's top-level-only mixin table sound"
    (is (classed? (err "div\n  mixin inner []\n    p x\n+(inner)") :nested-mixin))))
