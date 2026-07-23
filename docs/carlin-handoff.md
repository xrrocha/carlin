# Carlin — session handoff (rev. 22)

**Supersedes rev. 21 entirely.** State: **S30 ruled and implemented; the
diagnostics corpus is built.** Three gates now, all green:

| gate | command | state |
|---|---|---|
| golden conformance | `bb ratchet` | **101 / 104**, zero regressions ever |
| diagnostics corpus | `bb diagnostics` | **43 / 43**, new |
| spec unit suites | `bb spec-tests` | **22 tests / 161 assertions / 0 failures** (was 20/116) |

Docket **EMPTY**. `src/carlin/legacy.clj` is finally gone from git.

Rev. 21's plan item 1 was `deftemplate`. It is **not done**, and it was not
started, because the first probe against the compiled artifact found the
template it was probing with rendering wrong. Read §1 before planning
anything: that probe cost the session and was worth it twice over.

## 1. What a wrong probe found

The intent was to inspect `:code` to see what `deftemplate` must emit. The
probe template used `each b (:books model)` — the `in` omitted, my error, not
a corpus spelling. Carlin **accepted it and rendered an empty `<ul>`**,
compiling `(let [coll1759 nil] (for [b coll1759] …))`: the collection
expression discarded, `nil` in its place, and a phantom `coll1759` hoisted
into the model destructuring as if it were a model key.

Probed outward, the class was seven constructs wide and sixteen spellings
deep. Every one either rendered confidently wrong output or crashed
unclassified:

| template | before S30 | pug 3.0.2 |
|---|---|---|
| `each b xs` (no `in`) | `<ul></ul>` | `PUG:MALFORMED_EACH` |
| `each b` (no collection) | `<ul></ul>` | `PUG:MALFORMED_EACH` |
| `each` (nothing) | **unclassified crash** | `PUG:MALFORMED_EACH` |
| `each b on xs` (wrong keyword) | `<ul></ul>` | `PUG:MALFORMED_EACH` |
| `for b xs` | `<ul></ul>` | `PUG:MALFORMED_EACH` |
| `if` / `unless` (no condition) | dead branch | `PUG:SYNTAX_ERROR` |
| `else if` (no condition) | dead branch | `PUG:SYNTAX_ERROR` |
| `case` (no scrutinee) | matched only `when nil` | `PUG:NO_CASE_EXPRESSION` |
| `when` (no value) | matched only nil | — |
| `mixin` (no name) | **unclassified crash** | — |
| `mixin "str" []` | **unclassified crash** | — |
| `mixin m` (no vector) | compiled and rendered | pug accepts |
| `mixin m {}` | rendered nothing | — |
| `p=` / `p!=` (no expression) | **bare NPE, nil message** | `PUG:UNEXPECTED_TEXT` |
| `#{}` / `!{}` (empty) | **bare NPE, nil message** | `PUG:SYNTAX_ERROR` |

Bare `doctype` is the one spelling in the family where carlin and pug already
agreed: legal, defaults to html. It stays legal.

## 2. The mechanism, and why the fix has the shape it has

Uniform across all sixteen, and not obviously a bug at any single site:
**every one of these reads answered `nil` on absence, and `nil` is a legal
Clojure form.** Codegen compiled it faithfully. No pass was wrong on its own
terms — the defect lived in the seam, where a sentinel meaning "nothing was
there" is indistinguishable from a value meaning "the author wrote nil".

That is the **records-are-maps species** (rev. 12) — a sentinel colliding
with a legitimate value — seventh sighting.

Which dictates the fix. **Presence is read from the READ, never from the
form**: `read-line-form` returns a map or nothing; `read-source-form` flags
`:eof`. Testing the form instead would reject `if nil`, `when false`,
`each x in nil`, `p= nil` — all legal, all deliberately writing that
sentinel. `:else-if?` has carried presence-of-the-keyword separately from the
form since rev. 13 for exactly this reason; S30 generalizes the discipline
across a family.

`falsy-operands-stay-legal` pins it, and **the mutation test is the point**:
swapping presence for truthiness fails there, not in the malformed suite.
The failure mode that matters is rejecting the legal.

**One fix reached below the parser.** `#{}` never gets as far as a
missing-operand check. Handed a lone `}`, edamame *throws* `Unmatched
delimiter` rather than answering `:eof`, and reports it with an
`opened-delimiter-loc` whose `:row` and `:col` are **nil** — there is no
opened delimiter to point at. `platform/rebase` then `dec`ed nil. The
`:reader-error` branch immediately below had defended with `or` since it was
written; the `:unterminated-form` branch above it never had.

Note the pin history there, because it is instructive: the first pin for that
guard **did not test it**. Reverting the guard left the suite green, because
codegen's pre-read check intercepts `#{}` before the reader ever sees the
brace. The input that actually reaches the platform hole is `p= }` — an
unmatched closer in a restrictive-four position. *A pin is only as good as
its probe* (rev. 18), demonstrated twice in one session.

## 3. The diagnostics corpus — the session's real deliverable

Two departures logged (both by strictness, no legal template changes
meaning): `mixin m` without a bindings vector is rejected though pug accepts
it — §3.13's grammar is `mixin name [binding-vector]`, so this enforces
existing carlin law; and the family fails at compile time where pug's
equivalents mix compile and runtime.

But the durable outcome is the instrument. **The golden corpus was green
through every defect above, and through all five of S29's.** It could not
have been otherwise: it holds 104 *legal* templates and compares *output
bytes*, so a template that ought to be rejected has no golden to disagree
with. It is constitutionally incapable of observing this territory.

`test-resources/diagnostics/` inverts every axis:

| golden corpus | diagnostics corpus |
|---|---|
| legal templates | illegal templates |
| compares output bytes | compares error class + position |
| green = correct rendering | green = correct rejection |
| a case that errors is a bug | a case that **compiles** is a bug |

`<name>.carlin` + `<name>.edn` holding `{:class :line :col}`, plus optional
`:data` (ex-data keys that must match), `:fixture` (a named resolver — a
template cannot express one) and `:entry :compile-ref`. **Position is part of
the contract**: a class defaulting to line 1 would pass a class-only check
while being useless to an author. Prose is never asserted (§8.3). The runner
*renders* as well as compiles, since `:unsupported-js-value` and runtime
attr conflicts are raised at render.

It had been specified **since spec rev. 1**, as item 4 of the §12 test plan,
and never built. That gap is the direct cause of both S29 and S30.

**The audit rev. 21 asked for, done:** 40 error classes exist in `src/`;
**8 had no pin at all** — `:extends-raw`, `:include-cycle`, `:raw-root`,
`:dangling-clause`, `:each-missing-coll`, `:unknown-filter`,
`:unsupported-js-value`, `:unsupported-construct`. Seven were probed
reachable and are now covered. Three classes stay deliberately uncovered as
internal-invariant assertions: `:unsupported-construct`, `:extends`,
`:not-implemented`.

Mutation-tested end to end: reverting one S30 guard turns a green run into
`UNCLASSIFIED — NullPointerException` and exit 1.

## 4. Artifacts

| Artifact | State |
|---|---|
| `src/carlin/core.cljc` | S30 guards in the `each`/`for`, `if`/`unless`/`case`, `else if`, `when` and `mixin` heads; `parse-when-head` gained `:absent?` to report absence the `:form` cannot. |
| `src/carlin/codegen.cljc` | Empty `#{}`/`!{}` detected before the read (the reader cannot express it). |
| `src/carlin/platform.cljc` | `:unterminated-form` narrowed to delimiter locations with a non-nil row; unmatched closers fall through to `:reader-error`. |
| `src/carlin/legacy.clj` | **`git rm`'d.** S29 removed every reference but never staged the deletion. |
| `test/carlin/diagnostics_harness.clj` | **New.** Corpus runner, fixtures, own ratchet, `dshow`. |
| `test-resources/diagnostics/` | **New.** 43 cases + README. |
| `diagnostics-manifest.edn` | **New.** 43 cases baselined. |
| `test/carlin/diagnostics_test.cljc` | `malformed-directive-heads`, `falsy-operands-stay-legal`; 116 → 161 assertions. |
| `bb.edn` | `diagnostics`, `dbaseline`, `dshow`. |
| `docs/carlin-spec.md` | **Rev. 16** — §8.3 amended with twelve classes and the absence-vs-falsity discipline; §12.5 added; full revision note. |
| `test-resources/corpus/README.md` | S30 logged as a change this corpus could not observe (second time). |
| `README.md` | Three-gate status table, layout map, task table. |
| `conformance-manifest.edn` | Untouched — 101 cases, zero flips. |

## 5. Open docket

**EMPTY.** S30 ruled and applied 2026-07-22.

One item was deliberately **not** actioned and should be raised if it
matters: `:unsupported-js-value` is reachable only through `->js`, so a
function in a *scalar* attribute position (`a{:data-x inc}`) stringifies to
`clojure.core$inc@6ffeb8f0` rather than erroring. `attr-value-str`'s `:else`
branch is `(escape-attr (str v))`. Pug has no equivalent (JS functions
stringify to source), so this is a ruling, not a bug — left alone rather than
changed unilaterally.

## 6. Next session's plan — in order

1. **`deftemplate`** — genuinely untouched. The groundwork is in hand: `:code`
   is a self-contained form (`(fn [{:keys [...]} model] ...)`) with only
   `carlin.runtime` referenced, so the macro emits it directly and skips
   `platform/evaluate` entirely. Check how `:symbols` and the model
   destructuring interact with CLJS's analyzer before writing it.
2. sci `:eval` mirroring S15's template-ns; the CLJS matrix; vendor-vs-depend
   edamame (still the only runtime dep).
3. The three remaining golden reds — `include-only-text-body`,
   `include-with-text`, and `inheritance.extend.include` (the last is a
   **permanent departure**, S8; do not chase it).
4. Rev. 20's item 3 still stands: audit the other now-reachable checks
   against inline position; only arity and `:yield-outside-include` have pins.
5. The diagnostics corpus is a floor, not a ceiling. It covers every class
   that exists *today*; new classes need cases in the same commit.

## 7. Working agreement (unchanged, one addition)

Ratchet green is the invariant — **now both ratchets**; promote in the same
commit; never loosen either comparator; golden/template adjustments only with
a logged departure (or, for converter errors, a logged repair) — candidate
edits go to the docket FIRST. A plan item that is a behavior change is a
docket item. Ricardo rules, in batch, fast, when the probe record is laid out
plainly.

**New: a new error class ships with its corpus case.** The diagnostics corpus
only stays honest if it grows with the code. S30 added twelve classes; all
twelve have cases. The moment a class exists without one, the corpus is back
to being a snapshot of whatever someone remembered to test.

## 8. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start
proven eight times, including this session (babashka installed from scratch;
baseline reproduced exactly before anything was touched).

`bb show` takes ids RELATIVE to `cases/` (`bb show mixin.inline.carlin`, no
prefix); a miss yields a blank `:error`. `bb dshow` takes a diagnostics case
id with **no** extension (`bb dshow each-expected-in`).

`bin/render` note: it threads through `api/compile-template` and `api/render`,
so a mixin failure inside the template can surface as an exception reported at
`bin/render`'s own line 54. The script is fine; the trace is misleading.
Compile and render separately when isolating.

Probing pug is cheap and has never once been wasted: `npm i pug@3.0.2`,
`pug.render(src, {pretty:false})`. Error codes come back on `e.code`
(`PUG:MALFORMED_EACH` etc.), which is more legible than the message. Originals
come from the GitHub tag `pug@3.0.2` under `packages/pug/test/cases/`. Pug's
own case runner supplies things the library does not — a `custom` filter, a
`verbatim` filter, `{title: "Pug"}` locals. Carlin's `&attributes` spelling
takes **no parens**; carlin mixin definitions take an explicit bindings vector
even when empty (`mixin plain []`) — and as of S30 that is enforced.

Probing carlin's own behavior is equally cheap: `bb -cp src:test` with a
script calling `api/compile-template` and printing `(:engine c)` or the
`ex-data`. **Harden the probe's catch clause**: this session's first sweep
crashed because it called `ex-message` on an exception whose message was nil,
which — being itself the signature of an unclassified failure — nearly hid
the finding. Print the exception *class* when there is no `:carlin/error`.

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11).
- **Records are maps, forever** (rev. 12) — any sentinel colliding with a
  legitimate value. Seventh sighting at S30, and the whole of it: `nil` as
  "operand absent" versus `nil` as "the author wrote nil".
- **Sessions die mid-flight; package early** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13).
- **A corpus case can mask its own bug** (rev. 13).
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice** (rev. 15).
- **Read the implementation, not just the output** (rev. 17).
- **A pin is only as good as its probe** (rev. 18) — twice this session.
- **Retiring a plan item is a measurement, not a verdict** (rev. 18).
- **A handoff's plan item is a hypothesis about the code** (rev. 19).
- **Absence of a corpus case is not absence of a feature** (rev. 20).
- **Fix the class, then pin the instances** (rev. 20).
- **A fallback that only catches what is already broken is not a safety net**
  (rev. 21).
- **New: a corpus can only find defects in the population it samples.** The
  golden corpus is not inadequate — 104 cases, baselined across a dozen
  sessions, zero regressions ever. It samples *legal* templates, and no
  quantity of it would ever have found a malformed one mishandled. Coverage
  of a space is not coverage of its complement. When a test artifact is green
  through a defect, the question is not whether it is passing but whether the
  defect was ever within its reach.
- **New: the wrong probe is still a probe.** S30 was found by a typo in a
  throwaway script written for an unrelated purpose. The template was
  malformed by accident and carlin rendered it confidently. When a probe
  surprises you, finish the surprise before returning to the plan — the plan
  will still be there, and rev. 19's lesson says it was only a hypothesis
  anyway.
