# Carlin — session handoff (rev. 23)

**Supersedes rev. 22 entirely.** State: **S31, S32 and S33 ruled and
implemented; `deftemplate` exists.** Four gates now, all green:

| gate | command | state |
|---|---|---|
| golden conformance | `bb ratchet` | **101 / 104**, zero regressions ever |
| diagnostics corpus | `bb diagnostics` | **43 / 43** |
| deftemplate differential | `bb differential` | **101 identical, 0 differing**, new |
| spec unit suites | `bb spec-tests` | **25 tests / 195 assertions / 0 failures** (was 22/161) |

Docket **EMPTY**.

Rev. 22's plan item 1 was `deftemplate`. It is **done** — but not by the route
rev. 22 described, and the reason is worth reading before planning anything,
because it happened twice in one session: a stated mechanism was wrong, and the
probe that found it also found a second defect nobody was looking for.

## 1. What the plan item got wrong

Rev. 22 said the groundwork was in hand because `:code` is self-contained,
"with only `carlin.runtime` referenced, so the macro emits it directly and skips
`platform/evaluate` entirely." The first half is true. The conclusion does not
follow.

Carlin's *own* emitted structure is fully qualified — `clojure.core/fn`,
`clojure.core/let`, `carlin.runtime/raw` — that is the rev. 12 syntax-quote fix.
But sweeping the corpus for unqualified symbols in generated code turns up
twenty-three, and every one is a name **the author wrote**: `count`, `str`,
`inc`, `atom`, `swap!`, `map-indexed`, `subs`, `name`, `key`, `val`, `list`,
`vector`, and `raw` itself. Those stay bare deliberately — rev. 4 hygiene, user
names stay user data.

They resolve today only because `evaluate` binds `*ns*` to `template-ns`. A
macro expands in the **caller's** namespace, which carries no such promise:

| | renders |
|---|---|
| `p= (count coll)` via `evaluate` | `<p>3</p>` |
| same, expanded in a ns that redefines `count` | `[:p :MY-COUNT]` |

Not an error. Wrong output, from a namespace declaration made in another file
for unrelated reasons. That is S15 exactly one position over — rev. 13's lesson,
arriving on schedule.

On CLJS it is worse. `known-symbol?` had a hardcoded `:cljs false`, so every
free symbol becomes a model key: `count` lands in the `{:keys [...]}`,
destructures to nil, shadows the real function, and the call dies as a **bare
NPE with an empty message** — the unclassified-failure signature §8.3 exists to
abolish, on the platform `deftemplate` is meant to serve first.

## 2. The ruling that had to be withdrawn

S31 was first ruled as *qualify author symbols at codegen time*. I proposed it;
Ricardo agreed; I then found §8.2 had **already rejected exactly that**, in
bold, twice — *ambient vocabulary belongs to namespace mechanics, user names
stay user data, lexical shadowing keeps working*.

The third clause is what kills it. Authors legitimately bind core names, and all
four of these render correctly today:

```
each count in xs                       →  the elements, not their lengths
p= (let [count 99] count)              →  99
p= (let [{:keys [str]} {:str 7}] str)  →  7
p= ((fn [inc] (+ inc 1)) 41)           →  42
```

A textual qualifier has no scope tracking, so it rewrites those bindings too.
Making one safe means a scope-tracking analyzer over arbitrary author Clojure —
`let`, `fn`, `loop`, `letfn`, `binding`, `doseq`, every destructuring shape —
whose subtle failures are silent wrong output: the disease presented as the cure.

Raised before implementing. Re-ruled to the generated-namespace route. *Check a
ruling's factual premises before enforcing it* (rev. 11) — this time against one
of our own, made ninety minutes earlier.

## 3. And then that mechanism was wrong too

The generated-namespace route is what §5.2 names for AOT: emit into a namespace
that reproduces `template-ns` by declaration. It does not work.

**Runtime `in-ns` is unusable under babashka's sci.** A var defined that way is
unreachable through `resolve`, `ns-resolve`, `requiring-resolve` and direct
symbol reference alike — all four answer nil. bb is a declared target platform,
so the namespace had to become something else.

What shipped carries the vocabulary as **data**. Codegen already knows which
ambient names a template borrows: it is the exact complement of the free-symbol
analysis that builds the model destructuring. So the artifact gains
`:vocabulary`, mapping each borrowed name to its resolution in `template-ns`,
and `deftemplate` binds precisely those around `:code`:

```clojure
(let [count clojure.core/count, raw carlin.runtime/raw]
  (fn [{:keys [...]} env] ...))
```

Same vocabulary `evaluate` supplies through `*ns*`, established by ordinary
lexical binding instead — which works identically on CLJS, where namespaces are
compile-time only. Nothing in `:code` is rewritten, so shadowing survives for
free: the author's inner binding shadows the outer one.

The decisive test passes. `p= (count coll)`, expanded inside a namespace that
excludes and redefines `count`, renders `<p>3</p>`.

Two subtleties the probes forced, both now pinned:

- **Macros are excluded from `:vocabulary`.** Binding one is a hard
  `Can't take value of a macro`, and none is needed — a macro expands at the
  call site before any runtime binding matters. Templates do reach `when`,
  `cond`, `->` and `let`, so this is live, not hypothetical. Excluded inside
  `platform/qualify` so every consumer inherits it (rev. 13).
- **The vocabulary walk is scope-blind, deliberately.** A template shadowing a
  core name still lists it. Harmless for §4.4's reason — the inner binding wins,
  verified identical through both paths — and the alternative was the analyzer
  we had just declined to build.

## 4. The second defect, found by the same probe

`(gensym "coll")` yields `coll1793`. No `__`, so codegen's gensym filter — which
catches syntax-quote's `x__123__auto__` — never saw it. Eight corpus cases were
advertising model keys no caller could supply and that change on every compile:
`each.else`, `code.iteration`, `case`, `case-blocks`, `comments-in-case`,
`mixins.rest-args`, `block-code`, `filters-empty`.

Fixed by a **minting ledger**: the two sites that create these gensyms record
them, and `model-symbols` excludes by identity.

**Not by name shape, and the mutation test is the point.** `#"(coll|scrut)\d+"`
passes every obvious assertion and quietly eats `coll1` and `scrut2` from a
caller's real model — trading a phantom key (harmless, the `let` shadows it) for
a missing one (the destructuring silently stops binding real data). Reverting
the ledger to the shape filter **passes `bb ratchet`** and fails
`artifact-test`. Eighth sighting of the rev. 12 species.

## 5. Why both defects were invisible

Neither `:symbols` nor `:vocabulary` reaches the rendered bytes. The golden
corpus was green through S32 across all eight cases, and would be green through
a `:vocabulary` naming nothing at all.

This is the third consecutive session logging *zero goldens moved*, but the
reason is new. S29 and S30 were invisible because the corpus samples only
**legal** templates. S31 and S32 are invisible because it compares only **output
bytes**. Two axes, not one:

| the corpus cannot see | answered by |
|---|---|
| what carlin does with illegal input | diagnostics corpus (§12.5) |
| contract keys that never render | `artifact_test` + `bb differential` (§12.6) |

## 6. The differential, and a lesson about its first version

`bb differential` renders every golden case through both evaluation strategies
and compares bytes. It lives in `carlin.harness`, and that placement is the
finding.

Its first version was a standalone script in `/tmp`. It reported **84 identical,
0 differing** — while silently skipping 45 cases, which is to say every include,
every extends and every filter case: precisely the templates whose `:code` is
most complicated. It had no resolver and no fixtures, because those live in the
harness.

Moved there it reports **101 identical, 0 differing, 3 uncompilable**, and those
three are the known golden reds, each failing with its correct positioned error.
Same code, same verdict, honest denominator. *A pin is only as good as its
probe* (rev. 18), a third time.

## 7. Artifacts

| Artifact | State |
|---|---|
| `src/carlin/api.cljc` | **`deftemplate` (S31/S33).** Compiles at macroexpansion, binds `:vocabulary`, emits every other artifact key as quoted data. |
| `src/carlin/codegen.cljc` | `mint!` ledger + `*minted*` (S32); `model-symbols` excludes by identity; new `vocabulary-used`; `:vocabulary` in the artifact. |
| `src/carlin/platform.cljc` | New `qualify` (macros excluded); `template-vocabulary` as data; `known-symbol?`'s CLJS branch is a classified error, no longer `false`. |
| `test/carlin/harness.clj` | **`differential` + `run-differential` + `deftemplate-shaped-fn`.** |
| `test/carlin/artifact_test.cljc` | **New.** §5.2 contract: 3 suites, 34 assertions, incl. the mutation guard and four shadowing spellings. |
| `bb.edn` | `differential` task; `artifact-test` registered. |
| `docs/carlin-spec.md` | **Rev. 17** — §5.1 namespace corrected, §5.1 vocabulary discipline, §5.2 `:vocabulary` + `:symbols` law, §8.2 table + CLJS note, §12.6, revision note. |
| `test-resources/corpus/README.md` | S31/S32 logged — third "no goldens moved", first for the *second* axis. |
| `README.md` | Four-gate table, layout, suite list. |
| Both manifests | **Untouched.** 101 and 43, zero flips. |

## 8. Open docket

**EMPTY.** S31, S32, S33 ruled and applied 2026-07-23.

Carried forward from rev. 22, still not actioned, raise if it matters:
`:unsupported-js-value` is reachable only through `->js`, so a function in a
*scalar* attribute position (`a{:data-x inc}`) stringifies to
`clojure.core$inc@…` rather than erroring. Pug has no equivalent, so this is a
ruling, not a bug.

## 9. Next session's plan — in order

**Ricardo reordered this after rev. 23 was drafted: the CLJS matrix goes
first.** The reasoning is worth keeping, because it is a principle and not a
scheduling preference. sci is *additive* — it adds a third evaluation
strategy alongside two that work. CLJS is *corrective*: it can invalidate
design decisions already shipped. S31 chose its mechanism partly on the
argument that a `let`-bound vocabulary "works identically on CLJS, where
namespaces are a compile-time construct" — and that argument has never been
run. An unpaid debt on a feature just shipped outranks a new feature.

1. **The CLJS matrix.** `deftemplate` is the CLJS path, is written, and has
   never run under a CLJS compiler: the sandbox had no toolchain and no route
   to Maven Central. Treat CLJS support as **designed, not demonstrated**
   until this is done. `deps.edn` now carries a `:cljs-test` alias so this
   does not start with toolchain archaeology; verify the ClojureScript
   version before the first run rather than trusting the one written there.

   What must actually be probed, in rough order of how much would have to
   change if it fails:

   - **`deftemplate`'s emission compiles under the CLJS analyzer.** The macro
     emits `(let [count clojure.core/count, raw carlin.runtime/raw] (fn …))`.
     On CLJS those right-hand sides must be `cljs.core/count` and
     `carlin.runtime/raw`. `platform/qualify` resolves through
     `ns-resolve` against `template-ns`, which is a **JVM Clojure**
     namespace — so it will answer `clojure.core/count` even when compiling
     for CLJS. **This is the single most likely failure in the whole
     session**, it is a consequence of the mechanism S31 chose, and it was
     never tested. If it breaks, `qualify` needs the compilation target as
     an input, and `:vocabulary` becomes target-dependent.
   - **`known-symbol?`'s new `:not-implemented` error is unreachable on the
     macro path.** It should be: `deftemplate` runs on the JVM inside the
     CLJS compiler's process, so the `:clj` branch is what executes. Confirm
     rather than assume — if the `:cljs` branch fires during macroexpansion,
     the reader conditional is being read in the wrong context.
   - **The reader conditionals in `platform.cljc` behave under
     self-hosted CLJS**, which is a different question from ordinary CLJS
     and the one that matters for a browser-side `:eval`.
   - **Output is byte-identical to the JVM.** §12 item 7 requires it, and
     `bb differential` is the shape to copy — a `carlin.cljs-matrix`
     namespace that renders the corpus under Node and compares against the
     goldens. Whether that can reuse the harness or needs its own runner is
     itself a finding.

   A negative result here is a *good* outcome, not a setback: it converts
   "designed" into a docket item with a probe record, which is the state
   everything else in this project reaches before it gets ruled on.

2. **sci `:eval` mirroring S15.** Sharper than it was: `platform/qualify` and
   `template-vocabulary` give a sci context an exact specification of what to
   expose, and `bb differential` is the gate proving a third strategy agrees.
   The pattern is set — add the strategy, extend the differential. Do this
   *after* CLJS, since a target-dependent `qualify` would change what a sci
   context has to be handed.
3. Vendor-vs-depend edamame — still the only runtime dependency.
4. The three remaining golden reds — `include-only-text-body`,
   `include-with-text`, and `inheritance.extend.include` (the last a **permanent
   departure**, S8; do not chase it).
5. Rev. 20's item 3 still stands: audit the now-reachable checks against inline
   position; only arity and `:yield-outside-include` have pins.
6. The diagnostics corpus is a floor, not a ceiling; new classes need cases in
   the same commit.

## 10. Working agreement (unchanged, one addition)

Ratchet green is the invariant — **now four gates**; promote in the same commit;
never loosen any comparator; golden/template adjustments only with a logged
departure (or, for converter errors, a logged repair) — candidate edits go to
the docket FIRST. A plan item that is a behavior change is a docket item. A new
error class ships with its corpus case. Ricardo rules, in batch, fast, when the
probe record is laid out plainly.

**New: a ruling is a hypothesis too.** Rev. 19 established that a handoff's plan
item is a hypothesis about the code. S31 shows the same is true of a *ruling* —
including one made this session, agreed in good faith by both parties, that
turned out to contradict standing law. And of a ruling's stated *mechanism*: the
generated-namespace route was ratified and then defeated by sci's `in-ns`.
Neither cost anything, because both were probed before being built on. Rule
fast, then probe before implementing.

## 11. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start proven
ten times, including this session (babashka installed from scratch; all three
prior gates reproduced exactly before anything was touched).

`bb show` takes ids RELATIVE to `cases/` (`bb show mixin.inline.carlin`, no
prefix); a miss yields a blank `:error`. `bb dshow` takes a diagnostics case id
with **no** extension (`bb dshow each-expected-in`).

`bin/render` threads through `api/compile-template` and `api/render`, so a mixin
failure inside the template can surface as an exception reported at
`bin/render`'s own line 54. The script is fine; the trace is misleading. Compile
and render separately when isolating.

Probing pug is cheap and has never once been wasted: `npm i pug@3.0.2`,
`pug.render(src, {pretty:false})`. Error codes come back on `e.code`. Originals
come from the GitHub tag `pug@3.0.2` under `packages/pug/test/cases/`. Carlin's
`&attributes` spelling takes **no parens**; mixin definitions take an explicit
bindings vector even when empty (`mixin plain []`), enforced since S30.

Probing carlin is equally cheap: `bb -cp src:test` with a script calling
`api/compile-template` and printing `(:engine c)` or the `ex-data`. **Harden the
probe's catch clause** — print the exception *class* when there is no
`:carlin/error`, since a nil message is itself the signature of an unclassified
failure. Note that `bb` is sci, not the JVM Clojure compiler: this session it
accepted a `let`-bound qualified symbol the real compiler rejects, and it cannot
resolve vars created by runtime `in-ns` at all. **Maven Central is outside the
sandbox's allowed domains**, so a real Clojure jar cannot be fetched to
cross-check; where sci and Clojure might differ, say so rather than claiming a
JVM result.

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11) — and a
  ruling made this session is still a ruling.
- **Records are maps, forever** (rev. 12) — any sentinel colliding with a
  legitimate value. Eighth sighting at S32: a gensym's *name shape* as a
  sentinel, colliding with model keys authors actually write.
- **Sessions die mid-flight; package early** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13).
- **A corpus case can mask its own bug** (rev. 13).
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice** (rev. 15).
- **Read the implementation, not just the output** (rev. 17).
- **A pin is only as good as its probe** (rev. 18) — third sighting, §12.6.
- **Retiring a plan item is a measurement, not a verdict** (rev. 18).
- **A handoff's plan item is a hypothesis about the code** (rev. 19).
- **Absence of a corpus case is not absence of a feature** (rev. 20).
- **Fix the class, then pin the instances** (rev. 20).
- **A fallback that only catches what is already broken is not a safety net**
  (rev. 21).
- **A corpus can only find defects in the population it samples** (rev. 22).
- **The wrong probe is still a probe** (rev. 22).
- **New: a contract key that never reaches the output has no corpus.** The
  golden corpus samples rendered documents, so it can only police what renders.
  `:symbols`, `:vocabulary`, `:deps` and `:code` are load-bearing parts of
  §5.2's promise that nothing is hidden, and every one is invisible to the
  instrument that guards everything else. Rev. 22 saw that a corpus cannot find
  defects outside the population it samples; this is the same observation along
  the other axis — not illegal input, but legal input whose defect never shows
  up in the bytes.
- **New: the sandbox is not the platform.** `bb` is sci. It accepted a construct
  the JVM compiler rejects and rejected one the JVM compiler accepts, both in
  the same hour, and Maven Central is unreachable so there is no second opinion
  available. Every claim in this handoff about JVM or CLJS behavior that was not
  actually run is marked as designed rather than demonstrated — see §9 item 2.
