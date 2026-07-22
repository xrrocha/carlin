# Carlin — session handoff (rev. 21)

**Supersedes rev. 20 entirely.** State: **S29 ruled and implemented —
`carlin.legacy` is retired.** Ratchet **101/104, baselined, zero regressions
ever**. Spec suites **20 tests, 116 assertions, 0 failures** (was 17/103).
Docket **EMPTY**.

Rev. 20's plan item 2 is **done**, and it was not the item rev. 20 thought it
was. Read §1 before touching anything: the plan item's premise was false, and
what it was hiding is now load-bearing documented law.

## 1. The correction that mattered

Rev. 20 scheduled legacy's deletion as **dead-code removal**: "nothing defers
to it; the moat has no moat-keeper left to protect."

The first clause is true and was verified, not assumed. Instrumenting the
seam over all 104 corpus cases: **101 compile on `:carlin`, 3 raise genuine
`:carlin/error`, zero reach legacy.** The ratchet was never at risk, and
indeed did not move a single golden.

The second clause was **false**. Six `defer!` sites were still live in
codegen, and **the corpus contains no malformed templates**, so it was
structurally incapable of seeing them. Probed directly, five were reachable,
and every one rendered markup invented from a keyword:

| template | before S29 | pug 3.0.2 |
|---|---|---|
| `when 1` outside a case | `<when>1<p>hi</p></when>` | error |
| `default` outside a case | `<default>…</default>` | error |
| non-`when` clause in a case | `<case>1…</case>` | error |
| bare `block` outside a mixin | `<block>…</block>` | error |
| `+nope`, defined nowhere | literal text `+nope` | error |

Legacy had no moat-keeper left because it **was** the moat-keeper — for
malformed input, and it kept the moat badly. Deleting it was therefore never
a refactor; it was a behavior change on five reachable paths, which is why it
went to the docket instead of into a commit.

## 2. What was ruled (S29) and what landed

**Ruling 1 — the five constructs become positioned errors.** Applied.
`:stray-when`, `:stray-default`, `:case-clause`, `:anonymous-block`,
`:undefined-mixin` (carrying `:mixin`). Two more are internal-invariant
assertions, positioned so that the day they fire they say where: `:extends`
(unreachable — core folds inheritance first) and `:unsupported-construct`
(a node type reaching `gen` with no branch).

**The governing doctrine, stated once so it stops being re-derived:
whenever carlin can fail fast at compile time, it does. Period.** Pug raises
the undefined-mixin equivalent at *runtime*; carlin raises it at *compile*
time. Stricter than pug, consistent with §3.13's existing rejection of
absent-is-undefined for arity. A departure by strictness, not semantics — no
legal template changes meaning, which is exactly why the ratchet held.

**Ruling 2 — the `:undefined-mixin` caveat was stale, and elaborating it
found something better.** The comment read *"may live in an unmerged
layout"*: a hedge that this pass might run before `extends` was folded, which
would make a layout's mixin look absent and **reject a legal template**. §3.14
made `resolve-template` mutually recursive with `splice-includes` long ago, so
that ordering no longer exists. Probed four ways — a layout mixin called from
a child's block renders; one arriving via include renders; wrong arity on a
layout mixin is caught *earlier* by `check-arity`, positioned into the child;
only a name defined nowhere reaches the site.

**Ruling 3 — `carlin.api` stays**, against rev. 20's plan to fold it into
`carlin.core`. It is the surface §5 *names* and the one three consumers
import (harness, spec suites, `bin/render`); it houses `render`, which belongs
to neither half. Only its legacy branch is gone. `core/compile-ref`'s lazy
`requiring-resolve` **must stay** and is now documented as deliberate: `api`
→ `codegen` → `core`, so a static require closes a cycle.

## 3. The mixin-table invariant — read this before touching either walk

The elaboration turned up the real find. **Two mixin tables exist, built by
different walks:**

```
carlin.core/walk-checks   collects :defs RECURSIVELY, at every depth
codegen/compile-tree      collects :mixins from the TOP LEVEL only
```

A recursive collector and a top-level collector that must agree is exactly
the drift rev. 13 warns about — one scanner in two places. They cannot
diverge, but **only because of a third guard in a different namespace**:
`:nested-mixin` forbids a definition below depth 0, so every surviving
definition is top-level by construction and the two tables are necessarily
equal.

That was **load-bearing and undocumented**. The top-level filter is not an
optimization; it is sound only while `:nested-mixin` holds. Relax that guard —
plausible, since pug's own scoping is looser — and the table silently stops
seeing nested definitions, `arity` reads `::absent` for a legal call, and
S29's new error rejects **valid** templates. Whoever relaxes `:nested-mixin`
must make codegen's walk recursive **in the same commit**.

It is now stated at both sites and pinned by `mixin-table-invariant`.
Mutation-tested: breaking the table fails the `legal-templates-unaffected`
pins, which is the failure mode that matters (rejecting the legal), not
merely the one that is easy to test.

## 4. Artifacts

| Artifact | State |
|---|---|
| `src/carlin/legacy.clj` | **DELETED.** |
| `src/carlin/codegen.cljc` | `defer!` → `fail!` (delegates to `cursor/fail!`); ctx carries `:cursor`; `compile-tree` takes it as a third arg; ns docstring states the error contract; the MIXIN-TABLE INVARIANT documented at the table and at the check. |
| `src/carlin/api.cljc` | legacy require + bail-out branch gone; `:engine :carlin` retained as a constant so callers that branched on it keep working; docstring records why the seam is kept. |
| `src/carlin/core.cljc` | `compile-ref`'s `requiring-resolve` documented as deliberate cycle-avoidance. |
| `test/carlin/diagnostics_test.cljc` | Three new deftests: `back-half-fails-fast` (8), `legal-templates-unaffected` (3), `mixin-table-invariant` (2). 103 → 116 assertions. |
| `deps.edn` | **hiccup removed from runtime deps** (§13 trajectory complete) — legacy was its last user; permanent `:test` dep as the differential oracle. Sole runtime dep is now edamame. |
| `docs/carlin-spec.md` | **Rev. 15** — §8.3 amended (both halves report identically, new classes enumerated); full revision note. |
| `test-resources/corpus/README.md` | S29 logged: a behavior change **the corpus could not observe**, and why. |
| `README.md` | Layout map corrected (it still advertised `legacy.clj`); status section replaces the stale 15/108 baseline, kept below as history. |
| `conformance-manifest.edn` | Untouched — 101 cases, zero flips. |

## 5. Open docket

**EMPTY.** S29 ruled and applied 2026-07-22.

## 6. Next session's plan — in order

1. `deftemplate`; sci `:eval` mirroring S15's template-ns; the CLJS matrix;
   vendor-vs-depend edamame (now the *only* runtime dep, so the question is
   sharper than when it was raised).
2. The three remaining reds — §3.11 include 9/11, §3.14 inheritance 15/16 —
   are `include-only-text-body`, `include-with-text`, and
   `inheritance.extend.include` (the last is a **permanent departure**, S8;
   do not chase it).
3. **Consider a diagnostics-corpus pass in its own right.** S29's lesson is
   that the golden corpus is blind to illegal input by construction. The
   diagnostics suite is the only thing testing that territory, and it grew
   reactively — a class at a time, as bugs surfaced. Auditing §8.3's
   enumeration against it would say what is actually covered.
4. Rev. 20's item 3 still stands: audit the *other* now-reachable checks
   against inline position; only arity and `:yield-outside-include` have pins.

## 7. Working agreement (unchanged, one addition)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure (or, for
converter errors, a logged repair) — candidate edits go to the docket FIRST.
Ricardo rules, in batch, fast, when the probe record is laid out plainly.

**New: a plan item that is a behavior change is a docket item.** Rev. 20's
item 2 arrived phrased as cleanup and was one ruling away from silently
changing what five malformed templates do. The tell was cheap to find —
`grep defer!` — and the rule generalizes: before executing a deletion,
enumerate what currently reaches the thing being deleted, rather than
trusting the narrative that nothing does.

## 8. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start
proven six times, including once this session from a clean copy with `.git`
removed. NOTE: `bb show` takes ids RELATIVE to `cases/`
(`bb show mixin.inline.carlin`, no prefix); a miss yields a blank `:error`.

`bin/render` note: it threads through `api/compile-template` and
`api/render`, so a mixin failure inside the template can surface as an
exception reported at `bin/render`'s own line 54. The script is fine; the
trace is misleading. Compile and render separately when isolating.

Probing pug is cheap and has never once been wasted: `npm i pug@3.0.2`,
`pug.render(src, {pretty:false})`. Originals come from the GitHub tag
`pug@3.0.2` under `packages/pug/test/cases/`. Note that pug's own case runner
supplies things the library does not — a `custom` filter, a `verbatim`
filter, and `{title: "Pug"}` locals — so regenerating a golden may require
passing them in. Carlin's `&attributes` spelling takes **no parens**
(`div&attributes attributes`); pug's `&attributes(attributes)` will fail
confusingly if it slips into a probe template. Carlin mixin definitions take
an explicit bindings vector even when empty (`mixin plain []`).

**Probing carlin's own behavior is equally cheap and was the whole of this
session:** `bb -cp src:test` with a script that calls `api/compile-template`
and prints `(:engine c)` or the `ex-data`. That is how the five reachable
`defer!` paths were found, how the "unmerged layout" premise was disproved,
and how the mutation test on the mixin table was run.

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11) —
  vindicated hardest this session: the premise was a code comment, it was
  stale, and disproving it exposed the invariant in §3.
- **Records are maps, forever** (rev. 12) — any sentinel colliding with a
  legitimate value. Sixth sighting at S27.
- **Sessions die mid-flight; package early** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13).
- **A corpus case can mask its own bug** (rev. 13).
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice** (rev. 15).
- **Read the implementation, not just the output** (rev. 17).
- **A pin is only as good as its probe** (rev. 18).
- **Retiring a plan item is a measurement, not a verdict** (rev. 18).
- **A handoff's plan item is a hypothesis about the code** (rev. 19).
- **Absence of a corpus case is not absence of a feature** (rev. 20). Its
  converse ran this session: absence of a corpus case is not absence of a
  *bug* either — the corpus holds only legal templates, so it can never
  observe what carlin does with illegal ones.
- **Fix the class, then pin the instances** (rev. 20).
- **New: a fallback that only catches what is already broken is not a safety
  net.** Deferral earned its keep while the new engine was incomplete — it
  held the corpus baselined through six sessions of feature-by-feature
  landing. The moment the last legal construct compiled, its entire remaining
  population was templates that should have been *rejected*, and it turned
  each one into confident, wrong output. Re-measure a fallback's value when
  the thing it backstops changes; do not assume it persists.
