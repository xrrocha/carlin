# Carlin — session handoff (rev. 15)

**Supersedes rev. 14 entirely.** State: **S16 closed AND ruling 4's
enforcement session pre-ruled** — three decisions Ricardo-ratified in batch
2026-07-22, recorded as **spec rev. 10** (law without code; §3.11 amended).
Ratchet **77/100, baselined, zero regressions ever** (S16's three flips:
`attrs-data`, `attrs.js`, `mixin.attrs`; cold clone reproduced 74/100 first).
Spec suites: **17 tests, 90 assertions, 0 failures, 0 errors**. The docket is
**EMPTY** — and rev. 10's lossiness rule pre-answers the docket items the
probe was expected to open.

Frontier: **§3.5 attributes (5/8)**, **§3.11 include (5/11)**, **misc
(19/23)**, **§3.13 mixins (9/11)**, §3.3 text (3/5). The gate is **ruling 4 —
include-with-body at `yield`** plus its ride-along, **include-with-filter**:
one session, everything in the include branch's context fixed while it is in
context.

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. New system feature by feature; whole-template bailout to
`carlin.legacy` on `:carlin/defer` only, never on genuine `:carlin/error`s;
legacy behavior-frozen not byte-frozen; `bb ratchet` is the enforcing
instrument; baseline promoted in the same stroke as each gain.

## 2. The three pre-rulings (rev. 10 — READ BEFORE PROBING)

1. **The lossiness rule.** Follow pug as faithfully as possible **unless its
   behavior is lossy or grossly unexpected** — then positioned error, logged
   departure. This pre-ratifies the silent-discard class: if the probe shows
   pug dropping an include body (no-`yield` file, `:kind :raw` include),
   carlin errors — no S-docket round-trip, just the departure entry. Results
   that are merely *surprising* still come back for adjudication; "grossly
   unexpected" is Ricardo's call, not the session's.
2. **Multiple `yield`s: splice at EVERY yield.** Carlin law independent of
   the probe (a pug divergence is a logged departure, not a reopened
   question). Body AST replicates per splice site; evaluation — and side
   effects — run per splice; explicitly the author's rope, explicitly in
   §3.11 now.
3. **Include-with-filter rides along** in the same session: filter-attrs
   parse on the include branch + corpus repairs to `include:custom{:opt
   "val"}`. Fix everything in context while it's in context. Corpus edits
   still go to the docket first — riding along changes the session plan,
   not the paperwork.

## 3. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | **Rev. 10** — §3.11 edge semantics rewritten from "to be pinned by probe" to the ratified posture (every-yield splice as law; remaining edges pug-bounded-by-lossiness); rev. 10 revision note appended. Rev. 9 (S16 rulings, §6.3 correction) written earlier the same day. |
| `src/carlin/runtime.cljc` | `js-string` narrowed to escape `<` only (S16 (a)(ii)); docstring records the ruling. |
| `test/carlin/escaper_test.cljc` | One ride-along pin mechanically updated (`\u003E` → `&gt;`); `to_js_test`'s deliberate script-safety pins pass untouched. |
| `test-resources/…/cases/attrs-data.html` | Golden edit (S16 (a)(i)): `Let's` → `Let&#39;s` — third S12 edit, attribute position. |
| `test-resources/…/cases/attrs.js.html` | Golden edit, **permanent departure** (S16 (b)): class de-hoisted, source-order doctrine stands. S8's shape. |
| `test-resources/…/cases/mixin.attrs.carlin` | Two converter-error **repairs** (S16 (c)): `+(centered nil)#First Hello World`; `+foo{…}.thunk`. Goldens untouched, verified honest vs the pug 3.0.2 tag. |
| `test-resources/corpus/README.md` | Departure log current through S16: S12 entry annotated; attrs.js departure; mixin.attrs repairs. |
| `conformance-manifest.edn` | 77 cases, baselined. |

## 4. Next session's plan — in order

1. **Probe pug 3.0.2** — tag tarball:
   `https://github.com/pugjs/pug/archive/refs/tags/pug%403.0.2.tar.gz`
   (URL-encode the `@`); npm also reachable. Pin: (a) body onto a no-`yield`
   include; (b) multiple `yield`s (for the departure record only — carlin's
   answer is already law); (c) body onto a `:kind :raw` include. Route each
   result through the lossiness rule: silent discard → carlin errors, entry
   in the departure log; faithful-and-lossless → adopt verbatim into §3.11;
   merely surprising → back to Ricardo.
2. **Implement the `yield` splice** in include-splice (cycle detection and
   cross-file attribution already there); `:yield-outside-include` joins the
   diagnostics suite; the excluded `yield*` family is negative-test raw
   material. Species watch: `yield` is a bare word in line position — grep
   every place a bare form meets the reader before closing the book (the
   `+name` / `&attributes` lesson, third position over).
3. **Include-with-filter in the same stroke**: filter-attrs parse on the
   include branch; docket the corpus repairs (`include:custom{:opt "val"}`),
   then apply. Candidate flips across 1–3: `include.yield.nested`,
   `filters.include.custom`, `filter` reds in §3.11/§3.12.
4. Baseline the gains; spec §3.11 gets the probe results; handoff rev. 16.
5. Then the pools: attributes (3 left), misc (4), text (tagless lone-dot
   block), mixins (2).
6. Later: `deftemplate`, sci (must mirror S15's template-ns), CLJS matrix,
   vendor-vs-depend edamame.

## 5. Working agreement (one refinement)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure (or, for
converter errors, a logged repair) — candidate edits go to the docket FIRST.
**Refinement (rev. 10): a ratified rule can pre-answer a docket class** — the
lossiness rule stands where per-item S-questions would have been; only its
boundary cases ("grossly unexpected") travel back to Ricardo. Premises get
verified at enforcement time. Spec records decisions with their why. One
decision per exchange otherwise; Ricardo rules — and he rules well, sometimes
before being asked.

## 6. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). When in doubt, ask the S-question; he
answers in batch — and reads minds occasionally (the lossiness rule was his
phrasing adopted verbatim). Repo is public: clone `github.com/xrrocha/carlin`,
install babashka, `bb ratchet` — cold-start proven twice (74/100 both times
before touching anything).

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11) — S16
  (c)'s proof: verified premises made the ruling trivial.
- **Records are maps, forever** (rev. 12, four sightings).
- **Sessions die mid-flight; package early** (rev. 12) — honored again.
- **A small integer can be the whole fix** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13) —
  prospectively: `yield`, the next bare word to meet the reader.
- **A corpus case can mask its own bug** (rev. 13); pins use probe values
  where name ≠ value.
- **Symmetry is not a safety argument** (rev. 14): when an escape's
  rationale is aesthetic, ask what attack it closes.
- **New: replication is the obvious meaning of "splice here" written
  twice.** When a construct's repetition has a natural reading, adopt it and
  hand the author the rope — police the language, not the parameterization.
