# Carlin — session handoff (rev. 18)

**Supersedes rev. 17 entirely.** State: **the pools are drained.** The
S20–S26 docket was ruled in batch and applied same-day; six implementation
bugs fixed, five converter repairs, two permanent departures logged, three
goldens mechanically regenerated. Ratchet **100/102, baselined, zero
regressions ever**. Spec suites **17 tests, 96 assertions, 0 failures**.

Every spec section is now COMPLETE except two, and each of those is short by
exactly one permanent departure: §3.11 include 9/10 (`include-with-text`,
S22) and §3.14 inheritance 15/16 (`inheritance.extend.include`, S8). misc
26/26, §3.13 mixins 11/11, §3.5 attributes 8/8, §3.3 text 5/5, §3.6 code 5/5,
§3.10 comments 3/3.

**There is no frontier left in the corpus.** What remains is the roadmap:
`deftemplate`, sci mirroring S15, the CLJS matrix, vendor-vs-depend edamame,
and the deferred inline-tag scanner work (below).

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. Note that the bailout path is now *dormant*: no corpus case defers
to `carlin.legacy` any more. The quarantined engine stands as the frozen
oracle it was always meant to be, and retiring it is a live option — the
first time that has been true.

## 2. What landed this session

**Six implementation bugs**, each probed against pug 3.0.2 (npm) BEFORE the
fix, each detailed in spec rev. 13:

1. **Trailing blanks belong to the block** (`capture-raw`) — dot blocks,
   comment bodies, filter input; dropped only at EOF. Settles the
   "provisional dot-block dedent rule" erratum in pug's favor; the
   provisional marker is gone from §3.4.
2. **`else if` with a falsy condition** — `:else-if` truthiness stood in for
   presence, so `else if false` (which the corpus writes) silently became an
   unconditional `else` and swallowed the real alternative. Now `:else-if?`.
3. **Tagless lone-dot text block** — was misparsed as an empty `div`.
   Documented as new surface in §3.4.
4. **Literal markup lines take children** — deeper-indented lines under a
   `<ul>` line were dropped; pug joins them with newlines.
5. **Empty class and empty style omitted** — `class=""`/`[]`/`[""]`,
   `style={}` all render a bare element.
6. **`doctype xml` reaches the serializer** — `compile-tree` hardcoded
   `:mode :html`, clobbering the doctype profile; `:mode` now carries the
   user's override only, as §7.2 always said.

**Two findings promoted to law**: mixin redefinition is POSITIONAL (S24 —
`positionalize-mixins`; rev. 5's last-wins dedupe premise was never
measured), and a style MAP renders with trailing semicolons (S25 — the
escaper pin had asserted the opposite from assumption).

**Corpus work**: S20 imported two missing raw include targets from the tag;
S21 repaired five converter errors (`block-expansion`, `tags.self-closing`,
`classes`, `styles`, `block-code`) with every premise verified against the
originals first; S22 and S23 logged the two departures; S26 regenerated
three goldens at `pretty:false`, each verified byte-identical to real pug
output. All in the corpus README departure log.

## 3. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | **Rev. 13** — six bug fixes recorded, §3.4 dedent erratum settled, tagless lone-dot documented, S24/S25 promoted to law. |
| `src/carlin/core.cljc` | `capture-raw` trailing-blank rule; `:text-block` branch; `:else-if?` presence flag; `block-nodes` exempts mixin bodies (the D7 wall no longer catches a mixin's own yield point travelling through include). |
| `src/carlin/codegen.cljc` | `positionalize-mixins` (S24); `text-node?` admits literal markup lines; `:literal-html` children; `:mode` no longer hardcoded; string-aware `matching-bracket`. |
| `src/carlin/runtime.cljc` | `css-value` trailing semicolons; empty class AND empty style omitted; `void?` false under `:xml`. |
| `test/carlin/escaper_test.cljc` | Stale style pin corrected to probed behavior; empty-style-map pinned (96 assertions). |
| `test-resources/corpus/README.md` | S20–S26 logged in full. |
| `conformance-manifest.edn` | 100 cases, baselined. |

## 4. Open docket

**EMPTY.** S20–S26 all ruled and applied 2026-07-22.

## 5. Next session's plan — in order

1. **The deferred inline-tag scanner** (Ricardo deferred it explicitly this
   session). `matching-bracket` is now string-aware, which was the first
   half; what remains is inline mixin calls inside `#[…]`
   (`interpolated-mixin` passes via `#[+(linkit "…")]`, but the bare
   `#[+linkit "…"]` spelling still mis-arities) and a general audit of
   `scan-text` against reader syntax. The rev. 13 lesson applies: grep every
   bare-scan-meets-reader site at once rather than one position at a time.
2. **Retire `carlin.legacy`** — newly viable, since nothing defers to it.
   Fold `carlin.api` into `carlin.core` and let `compile-ref` stop going
   through `requiring-resolve`.
3. `deftemplate`; sci `:eval` mirroring S15's template-ns; the CLJS matrix;
   vendor-vs-depend edamame.

## 6. Working agreement (unchanged, one addition)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure (or, for
converter errors, a logged repair) — candidate edits go to the docket FIRST.
Ricardo rules, in batch, fast, when the probe record is laid out plainly.

**New: reconcile the tree before reporting on it.** This session opened with
a status report reconstructed from the previous handoff rather than from the
repo, and it was wrong in both directions — it claimed nothing had landed
when most of the batch had, and it flagged three golden edits as unexplained
departures when they were mechanical regenerations. `git diff` is the source
of truth about what happened; a handoff is only a claim about it. Read the
implementation, not just the narrative — the rev. 17 lesson, turned inward.

## 7. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start
proven four times. NOTE: `bb show` takes ids RELATIVE to `cases/`
(`bb show classes.carlin`, no prefix); a miss yields a blank `:error`.

Probing pug is cheap and has never once been wasted: `npm i pug@3.0.2`,
`pug.render(src, {pretty:false})`. Originals come from the GitHub tag
`pug@3.0.2` under `packages/pug/test/cases/`. Note that pug's own case runner
supplies things the library does not — a `custom` filter, a `verbatim`
filter, and `{title: "Pug"}` locals — so regenerating a golden may require
passing them in.

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11).
- **Records are maps, forever** (rev. 12) — and the species is broader than
  records: any sentinel that collides with a legitimate value. `:else-if`
  truthiness was the fifth sighting, in a costume nobody recognized.
- **Sessions die mid-flight; package early** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13).
- **A corpus case can mask its own bug** (rev. 13).
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice** (rev. 15).
- **Read the implementation, not just the output** (rev. 17).
- **New: a pin is only as good as its probe.** S25's style assertion was a
  hypothesis wearing a test's clothes, and it contradicted a correct
  implementation with all the authority of a regression. An unprobed pin is
  technical debt that accrues interest in the wrong direction.
- **New: retiring a plan item is a measurement, not a verdict.** Rev. 12
  retired "regenerate the text goldens" as fruitless. It was fruitless for
  the cases then measured; three others were waiting. Scope the retirement
  to the evidence.
