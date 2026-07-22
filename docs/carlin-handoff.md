# Carlin — session handoff (rev. 17)

**Supersedes rev. 16 entirely.** State: **ruling 4 ENFORCED and the docket
EMPTY.** Include-with-body at `yield` landed with its ride-along
(include-with-filter attrs), probe-first honored throughout; the session's
three S-questions (S17, S18, S19) were Ricardo-ratified and applied
same-day, recorded as **spec revs. 11 and 12**. Ratchet **83/103,
baselined, zero regressions ever** (77 → 80 from the feature flips:
`include.yield.nested`, `include-only-text`, `filters.include.custom`;
80/100 → 83/103 from S19's readmission — the denominator moved, S14-shaped
in reverse; cold clone reproduced 77/100 first — third cold-start proof).
Spec suites: **17 tests, 95 assertions, 0 failures**. **§3.12 filters is
COMPLETE (4/4).**

Frontier: **§3.11 include (7/11)**, **§3.5 attributes (5/8)**, **misc
(19/23)**, §3.3 text (3/5), §3.13 mixins (9/11). No single gate remains —
the pre-ruled law is exhausted; what's left is the pools.

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. New system feature by feature; whole-template bailout to
`carlin.legacy` on `:carlin/defer` only, never on genuine `:carlin/error`s;
legacy behavior-frozen not byte-frozen; `bb ratchet` is the enforcing
instrument; baseline promoted in the same stroke as each gain.

## 2. What landed this session (rev. 11)

1. **The probe** (pug 3.0.2, npm package cross-checked against pug-linker's
   source — never memory): multiple yields → pug splices at EVERY yield, so
   rev. 10's law is pug-faithful, no departure; body on raw/filtered
   include → pug errors ("Raw inclusion cannot contain a block"), adopted
   as `:body-in-raw-include`; unfed yields render nothing and survive for
   an enclosing body (the cascade), adopted; `yield`+trailing-text is a
   tag, `yield`+children an error, both adopted.
2. **S17** (the one merely-surprising probe result, returned per the
   lossiness rule): body on a no-`yield` include. Pug buries it at the
   *deepest last block* (`defaultYieldLocation`, own deprecation todo in
   source). Ruled grossly unexpected — "camming content at an unpredictable
   context-dependent location is inadmissible" — so carlin raises
   `:body-without-yield`, logged departure.
3. **The splice**: body spliced in the *includer's* context first, then
   replicated at every `yield` in the fully resolved target; `tag: yield`
   expansion takes the body as children; yield legality follows the include
   EDGE (root and extends-reached files error with
   `:yield-outside-include`, even inside include bodies written there).
4. **Ride-along**: `include:custom{:opt "val"} ref` — filter-attrs map on
   the include branch, passed to the filter fn; may span lines, ref follows
   on the closing line; sought only when a filter name is present.
5. **S18**: `filters.include.custom` repaired from pug attr syntax; the
   case includes ITSELF, so the golden's embedded source line changed in
   consequence — regenerated mechanically, verified `BEGIN`+bytes+`END`.
6. `:include-children` retired; new diagnostics all pinned:
   `:yield-outside-include`, `:yield-children`, `:body-in-raw-include`,
   `:body-without-yield` (suites 17/95/0).
7. **S19** (spec rev. 12, "prodigal offsprings return"): the three
   includer-side `yield*` exclusions readmitted to `cases/` — their
   exclusion rationale died with `:include-children`, and all three were
   probe-verified green BEFORE the ruling. Denominator 100 → 103, landing
   at 83/103 in the same stroke. The `yield*-head` include targets moved
   to `cases/` as support files WITHOUT golden pairing (the harness pairs
   `.carlin` only with an adjacent `.html`); as roots they raise
   `:yield-outside-include`, pinned; their goldens stay in `_excluded/`,
   negative-by-design.

## 3. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | **Rev. 12** — §3.11 edge semantics rewritten from ratified-ahead to probed-and-landed law (probe record, S17, yield anatomy, edge rule, include-filter attrs surface); rev. 11 note (ruling 4 enforced) and rev. 12 note (S19) appended. |
| `src/carlin/core.cljc` | `yield` directive (bare-only token — the `+name`/`&attributes` lesson honored prospectively, as rev. 13 predicted); include branch filter-attrs; `splice-yields` (every-yield replication, cascade-preserving); `included?` edge flag on `resolve-template`; `walk-checks` include case retired, `:yield-children` added; header diagnostics inventory current. |
| `src/carlin/codegen.cljc` | `:yield → nil` (unfed splice point renders nothing). |
| `test/carlin/diagnostics_test.cljc` | `:include-children` pin replaced by five new pins across four classes (probe values, name ≠ value). |
| `test-resources/…/cases/filters.include.custom.{carlin,html}` | S18 repair + repair-consequent golden edit. |
| `test-resources/corpus/README.md` | S17 departure entry; S18 repair entry; S19 readmission entry (exclusion note now covers only the three `yield*-head` goldens). |
| `test-resources/…/cases/` | +6 files via S19: three golden pairs readmitted, three `*-head` support templates (no goldens). |
| `conformance-manifest.edn` | 83 cases, baselined. |

## 4. Open docket

**EMPTY.** S17, S18, S19 all ruled and applied 2026-07-22.

## 5. Next session's plan — in order

1. **The pools**, biggest first: §3.11 include (4 reds:
   `includes`, `includes-with-ext-js`, `include-with-text`,
   `include-only-text-body` — note the last is an include TARGET that also
   stands as its own case; as a root it contains bare text with
   interpolation, likely a §3.3 problem wearing §3.11's name), misc (4:
   `html`, `xml`, `block-code`, `block-expansion`), attributes (3:
   `classes`, `classes-empty`, `styles`), text (`text`, tagless lone-dot
   block), mixins (2: `interpolated-mixin`, `mixin-at-end-of-file`,
   `mixin.block-tag-behaviour` has the known spurious-`<p>` defect), plus
   `comments`, `inline-tag`, `code.conditionals`, `tags.self-closing`.
   (`inheritance.extend.include` is the permanent departure — never
   counts.)
2. Later: `deftemplate`, sci (must mirror S15's template-ns), CLJS matrix,
   vendor-vs-depend edamame.

## 6. Working agreement (unchanged, one observation)

Ratchet green is the invariant; promote in the same commit; never loosen
the comparator; golden/template adjustments only with a logged departure
(or, for converter errors, a logged repair) — candidate edits go to the
docket FIRST. A ratified rule can pre-answer a docket class (rev. 10);
premises get verified at enforcement time. One decision per exchange
otherwise; Ricardo rules — and rules in batch, fast, when the probe record
is laid out plainly.

## 7. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose
over bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start
proven three times (74, 74, 77 before touching anything). NOTE: `bb show`
takes ids RELATIVE to `cases/` (`bb show filters.include.custom.carlin`,
no `cases/` prefix) — a session lost twenty minutes to a blank `:error`
from that; the harness swallows the message when the id misses.

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11).
- **Records are maps, forever** (rev. 12, four sightings).
- **Sessions die mid-flight; package early** (rev. 12) — honored again.
- **A lesson learned in one position recurs one position over** (rev. 13) —
  and the prophecy self-fulfilled benignly: `yield` was implemented as a
  bare word token from the start, so the species never bit.
- **A corpus case can mask its own bug** (rev. 13); pins use probe values
  where name ≠ value.
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice**
  (rev. 15) — now also pug's meaning, per the probe.
- **New: read the implementation, not just the output.** The probe's
  decisive fact — pug's own `// todo: probably should deprecate` — was in
  pug-linker's source, not in any rendered result. When behavior looks
  accidental, the source usually says so out loud.
