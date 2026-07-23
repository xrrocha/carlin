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

(deftest malformed-directive-heads
  "S30 — a directive head whose operand is MISSING. Before S30 these did not
   error: every one read as nil, and nil is a legal Clojure form, so codegen
   compiled it faithfully into well-formed, silently wrong output —
   `each b xs` (no `in`) became (let [coll nil] (for [b coll] ...)) and
   rendered an EMPTY loop; a bare `if` became a dead `(if nil ...)`; a bare
   `case` matched only `when nil`. Two spellings were worse than silent:
   `mixin` with no name reached codegen and died as a raw Clojure
   'Unsupported binding form' carrying no position at all.

   This is the S29 species one position over — a template that should be
   rejected turned into confident wrong output — and the golden corpus is
   structurally blind to it, holding only legal templates. pug 3.0.2 errors
   on every spelling below (PUG:MALFORMED_EACH, PUG:SYNTAX_ERROR,
   PUG:NO_CASE_EXPRESSION), probed directly.

   Offenders sit on line 2+ wherever the grammar allows, so a class that
   merely defaulted to line 1 could not pass."
  (testing "each/for — the four operand positions, each named separately"
    (is (classed? (err "ul\n  each b xs\n    li= b") :each-expected-in))
    (is (classed? (err "ul\n  each b\n    li hi") :each-missing-in))
    (is (classed? (err "ul\n  each\n    li hi") :each-missing-binding))
    (is (classed? (err "ul\n  each b on xs\n    li= b") :each-expected-in))
    (testing "and `for`, the alias, is the same grammar — a lesson learned in
              one position recurs one position over (rev. 13)"
      (is (classed? (err "ul\n  for b xs\n    li= b") :each-expected-in))))
  (testing "the `in` diagnostics point at where `in` SHOULD BE, not at the
            directive — the binding has already been read, so its end column
            is the informative position"
    (let [d (err "ul\n  each b xs\n    li= b")]
      (is (= 2 (:line d)))
      (is (= 9 (:col d))))
    (testing "and the wrong keyword is reported as found, for the message"
      (is (= 'on (:found (err "ul\n  each b on xs\n    li= b"))))))
  (testing "if/unless/case — a missing operand"
    (is (classed? (err "div\n  if\n    p hi") :missing-condition))
    (is (classed? (err "div\n  unless\n    p hi") :missing-condition))
    (is (classed? (err "div\n  case\n    when 1\n      p hi") :missing-scrutinee)))
  (testing "`else if` with no condition — :else-if? already carried presence
            of the KEYWORD separately from the form (rev. 13); this adds
            presence of the CONDITION on the same principle"
    (is (classed? (err "div\n  if false\n    p a\n  else if\n    p b")
                  :missing-condition)))
  (testing "a `when` clause with no value"
    (is (classed? (err "div\n  case 1\n    when\n      p hi") :missing-when-value)))
  (testing "buffered code and interpolation with no expression. These were
            not silent either — they were the WORST diagnostic in the
            codebase: a bare NullPointerException with a nil message, no
            class, no line and no column, because read-source-form answers
            {:eof true} with no :end-line and the caller `inc`ed it."
    (is (classed? (err "div\n  p=") :missing-expression))
    (is (classed? (err "div\n  p!=") :missing-expression))
    (testing "and empty #{} / !{} interpolation, which could not even reach
              that path: handed a lone `}` the reader THROWS 'Unmatched
              delimiter' rather than answering :eof, and edamame reports
              that with an opened-delimiter-loc whose row and col are NIL —
              so carlin.platform/rebase `dec`ed nil one layer deeper still.
              Detected before the read, since the reader cannot express it."
      (is (classed? (err "div\n  p #{}") :missing-expression))
      (is (classed? (err "div\n  p !{}") :missing-expression))))
  (testing "mixin definition heads — name and bindings vector both required
            (§3.13 grammar: `mixin name [binding-vector]`). These two were
            not silent but UNCLASSIFIED: no class, no line, no column."
    (is (classed? (err "p a\nmixin\n  p hi") :mixin-missing-name))
    (is (classed? (err "p a\nmixin \"str\" []\n  p hi") :mixin-bad-name))
    (is (classed? (err "p a\nmixin m\n  p hi") :mixin-missing-bindings))
    (is (classed? (err "p a\nmixin m {}\n  p hi") :mixin-bad-bindings))))

(deftest falsy-operands-stay-legal
  "S30's converse, and the discipline that makes it safe: ABSENCE is not
   FALSITY. Every check above tests the presence of the READ (a map from
   read-line-form), never the truthiness of the form it carries — because
   nil and false are perfectly legal operands a template may write
   deliberately, and they arrive carrying the identical :form nil.

   This is the rev. 13 else-if-falsy lesson applied across a whole family:
   `else if false` is a legal condition whose form is falsy, so truthiness
   could never mean presence. Had S30 tested the form instead of the read,
   every template below would now be rejected — which is the failure mode
   that matters (rejecting the legal), and the one these pins exist to catch."
  (testing "each over a falsy or empty collection"
    (is (nil? (err "ul\n  each b in nil\n    li= b")))
    (is (nil? (err "ul\n  each b in []\n    li= b"))))
  (testing "destructuring bindings still read as one form"
    (is (nil? (err "ul\n  each [i x] in (map-indexed vector xs)\n    li= x"))))
  (testing "falsy conditions and scrutinees"
    (is (nil? (err "div\n  if nil\n    p hi")))
    (is (nil? (err "div\n  if false\n    p hi")))
    (is (nil? (err "div\n  unless nil\n    p hi")))
    (is (nil? (err "div\n  case nil\n    when nil\n      p hi"))))
  (testing "`when nil` and `when false` — the clauses that spell a falsy
            scrutinee value deliberately"
    (is (nil? (err "div\n  case nil\n    when nil\n      p matched")))
    (is (nil? (err "div\n  case false\n    when false\n      p matched"))))
  (testing "`else if nil` — the exact rev. 13 case"
    (is (nil? (err "div\n  if false\n    p a\n  else if nil\n    p b"))))
  (testing "`when value: head` expansion still parses (the colon defeats a
            naive read, so absence is detected AFTER the retry, not before)"
    (is (nil? (err "div\n  case 1\n    when 1: p hi"))))
  (testing "mixin definitions with an empty, fixed, or variadic vector"
    (is (nil? (err "mixin m []\n  p hi\n+(m)")))
    (is (nil? (err "mixin m [a b]\n  p= a\n+(m 1 2)")))
    (is (nil? (err "mixin m [a & r]\n  p= a\n+(m 1 2)"))))
  (testing "buffered expressions that are merely falsy — `p= nil` renders
            empty and is legal; only the ABSENT expression is an error"
    (is (nil? (err "div\n  p= nil")))
    (is (nil? (err "div\n  p!= nil")))
    (is (nil? (err "div\n  p #{nil}"))))
  (testing "an empty `-` code line stays legal (pug accepts it too) — the
            one spelling in this family carlin does NOT reject"
    (is (nil? (err "div\n  -"))))
  (testing "an ESCAPED \\#{} is literal text, not an empty interpolation"
    (is (nil? (err "div\n  p \\#{}"))))
  (testing "an unmatched CLOSER in a restrictive-four position — the input
            that actually reaches the platform hole. `#{}` never gets there
            (codegen intercepts it before the read), so THIS is the probe
            that pins carlin.platform's nil-row guard: without it edamame
            reports an opened-delimiter-loc carrying nil :row, rebase decs
            nil, and the compile dies as a bare NullPointerException.
            A pin is only as good as its probe (rev. 18)."
    (is (classed? (err "div\n  p= }") :reader-error))
    (is (classed? (err "div\n  p= )") :reader-error))
    (is (classed? (err "div\n  - }") :reader-error)))
  (testing "a genuinely unterminated form still classifies as
            :unterminated-form — the platform guard narrowed that branch to
            locations that actually know where they opened, and must not
            have swallowed the real case"
    (is (classed? (err "a{:href \"x\"") :unterminated-form)))
  (testing "a bare `doctype` remains legal and defaults to html — the one
            spelling in this family where carlin and pug already agreed"
    (is (nil? (err "doctype\np hi")))))
