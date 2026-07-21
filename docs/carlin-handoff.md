# Carlin — session handoff (rev. 11)

**Supersedes rev. 10 entirely.** State: **rev. 7 is reconstituted in-repo and
half enforced.** Ratchet **74/100, baselined, zero regressions ever**. Spec
suites: **14 tests, 73 assertions, 0 failures, 0 errors.** The docket is empty
again — S15, opened and ruled this session. **§4.1 escaping is 3/3, complete.**

Of rev. 7's four rulings: **ruling 1 (escaping boundary) is implemented**;
rulings 2–4 (JSON attrs → class order → include/yield) remain law awaiting
enforcement, in that order. The frontier is **§3.5 attributes (3/8)**,
**§3.11 include (5/11)**, **misc (18/23)**, **§3.13 mixins (8/11)**.

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. Write the new system feature by feature. If it can't handle a
template it bails out COMPLETELY to the old system: codegen throws ex-info with
`:carlin/defer` (never for genuine `:carlin/error`s, which always propagate),
and the seam falls back to `carlin.legacy` wholesale — one template, one
engine, outputs never mixed. No features are ever added to legacy; it is
**behavior-frozen, not byte-frozen**; `bb ratchet` is the enforcing instrument.
The battery proves the swallowing: baseline promoted in the same stroke as each
gain.

## 2. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | **rev. 7, FULL document** — the rev. 6 base with the rev. 7 patch applied and the rev. 7 revision note appended. (Rev. 10's session had left only the patch file in the repo; the base was re-uploaded and merged this session.) One known erratum: see §5 item 0 below |
| corpus | Departure log: the S12 entry now carries a **scope-narrowing annotation** (see §3); `inheritance.alert-dialog.html` reverted to pug's original verbatim apostrophe; `mixin.block-tag-behaviour`'s pending-S12 note released |
| `conformance-manifest.edn` | **74 cases** of **100**, baselined |
| `src/carlin/platform.cljc` | Gains `template-ns` (S15) — see §3 |
| `src/carlin/codegen.cljc` | Escaping boundary: statics emit `rt/raw` in every context; `:static-raw` folded into `:escaped` (now: normal = statics verbatim + `#{}` escaped; `:all-raw` = raw-text tags, interpolation included) |
| `src/carlin/runtime.cljc` | `class-tokens` checks `raw?` before `map?`; class attribute values escape **per token**, Raw tokens verbatim |
| `test/carlin/escaper_test.cljc` | +3 assertions pinning raw-in-class (verbatim single token; mixed lists escape non-raw only; plain strings still escape) |

## 3. This session's three moves

1. **Ruling 1 — the escaping boundary — implemented.** Two edits in codegen
   (`piece->code` string case → always `rt/raw`; the one `:static-raw`
   callsite folded). Five honest flips: `blockquote`, `comments-in-case`,
   `quotes` (the one-line witness: static `"foo"`/`'foo'` verbatim),
   `scripts.non-js`, `utf8bom`.

2. **S12 scope-narrowing correction, Ricardo-confirmed.** Rev. 7's note
   claimed both S12-edited goldens live in attribute position. **False for
   `inheritance.alert-dialog`** — its `I&#39;m` was TEXT position, a
   workaround for the static-text escaping ruling 1 abolishes. Verified
   against the pugjs `pug@3.0.2` tag: pug's original golden carries the
   verbatim apostrophe, so the revert *restores the vendored corpus*. S12 is
   **re-scoped, not reversed**: the `attrs.carlin` edit (`&lt;baz&gt;`,
   attribute position) stands and stayed green throughout, exactly as the
   rev. 7 note requires where its premise held. Departure log annotated.

3. **S15 — opened, ruled, implemented in one session.** Bare `raw` in a
   template expression — the spec's own idiom (§3.5/D5) — never resolved:
   `known-symbol?` used ambient-ns `resolve`, nothing referred
   `carlin.runtime`, so `raw` was inferred as a model key, destructured as
   nil, and *invoked* — NPE. Same for `->js`. **Ruling (Ricardo): dedicated
   template namespace**, not codegen rewriting. `platform/template-ns` refers
   clojure.core plus exactly `raw` and `->js`; `evaluate` binds `*ns*` to it;
   `known-symbol?` resolves against it. Analysis and evaluation agree by
   construction; user names stay user data (rev. 4 hygiene); lexical
   shadowing still works; and templates no longer resolve against whatever
   `*ns*` the caller happened to be in — which was fragility, not a feature.
   Flips: `attrs.unescaped`, `escaping-class-attribute`. En route, the
   **records-are-maps trap, third sighting**: `class-tokens` sent a Raw
   record down its map-conditional branch; fixed and pinned in the paranoid
   escaper file, with per-token class escaping (Raw tokens verbatim, mixed
   lists escape only the non-raw tokens).

## 4. Battery state

Ratchet **74/100**, baselined. inheritance 15/16 (S8's permanent departure) ·
doctype 3/3 · **escaping 3/3** · case 2/2 · iteration 1/1 · misc 18/23 ·
mixins 8/11 · attributes 3/8 · include 5/11 · code 4/5 · text 3/5 ·
tags 2/3 · filters 3/4 · comments 2/3 · interpolation 1/2.

**Spec suites: 14 tests, 73 assertions, 0 failures, 0 errors.**

## 5. Next steps — in order

0. **Spec erratum to fix in passing** (one paragraph): the rev. 7 revision
   note's claim that "the two S12 goldens both live in attribute position" is
   wrong for `inheritance.alert-dialog` (text position; its edit dissolved
   under ruling 1). The departure log already carries the correction; the
   spec note should get a one-line erratum pointing at it. Also worth
   recording S15 (template namespace) in a rev. 8 note when one accrues —
   `raw`/`->js` as *ambient vocabulary via namespace mechanics* is a design
   position, and §8.2's platform table should mention `template-ns`.
1. **Ruling 2 — JSON attribute values** (§3.5 via §6.3). The seam is
   `runtime/attr-value-str`: today a map value renders as CSS (`css-value`)
   and a coll joins with spaces; under rev. 7, non-`:style` maps and colls
   render via `->js` then the attribute escaper (`->js` first, escaper
   second — the `&quot;` shape). `:style` keeps `css-value`; `:class` never
   reaches this branch (`merge-attrs` consumes it first). The smoke test
   already proves the pipeline shape: `button{:hx-vals (->js {:id 7})}` →
   `hx-vals="{&quot;id&quot;:7}"`.
2. **Ruling 3 — class accumulation in textual source order.** The
   implementation appends map `:class` after shorthands;
   `a.foo{:class "bar"}.baz` must yield `class="foo bar baz"`. A bug under
   existing law (rev. 5's attribute-order doctrine one level down), not a
   departure. Touches how codegen threads shorthand vs attrs-map class
   sources into `merge-attrs` — the parser knows the textual positions.
3. **Ruling 4 — include-with-body spliced at `yield`.** Probe pug 3.0.2
   FIRST (npm reachable): the body's destination when the included file has
   no `yield`; multiple `yield`s; body on a `:kind :raw` include. Probe
   results land in spec §3.11, then implementation; `:yield-outside-include`
   joins the diagnostics corpus. Several §3.11 reds are also
   include-with-filter (`include:custom(opt='val')` — the include branch
   still swallows the paren group into the ref; needs a filter-attrs parse
   plus corpus repair to carlin syntax `include:custom{:opt "val"}`).
4. Then the pools: **attributes (3/8)** — `mixin.attrs` now *fails* rather
   than NPEs (raw resolves; remaining mismatch is deeper in mixin attr
   merging, likely helped by rulings 2–3); **misc (18/23)**; **text (3/5)**
   (the known content-level cases: pug's tagless lone-dot text block, which
   carlin misparses as an empty div shorthand); **mixins (8/11)**.
5. Later: `deftemplate`, sci, CLJS matrix, vendor-vs-depend edamame. The sci
   `:eval` strategy must mirror S15 (a sci context exposing `raw`/`->js`) —
   noted so the namespaces don't drift.

## 6. Working agreement (unchanged)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure; spec
records decisions with their why. Docket discipline: one decision per
exchange, recommendation + rationale, Ricardo rules — and he rules well.

## 7. The docket — EMPTY (S8–S15 all ruled)

S8 declined (D7 permanent) · S9/S13 applied (harness `case-model` /
`case-filters`, minimal, zero-collateral additions only) · S10 ratified
(`:include-cycle` + `{:via :extends}`) · S11 withdrawn · S12 applied, **scope
narrowed by rev. 7** (five-entity escaper wherever escaping applies; the
boundary is the dynamic one; one golden edit survives, one dissolved) ·
S14 applied (denominator 100, honest) · **S15 applied** (template namespace;
`raw`/`->js` ambient).

Standing principles, by name: **pug-fidelity is a default, not a constraint**
(S12); **the battery supplies corpus-authored inputs only through public
options, kept minimal** (S9/S13); and now: **generated code is hermetic and
user names are user data** (rev. 4) — S15 chose namespace mechanics over
symbol rewriting precisely to keep that principle intact.

## 8. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). When in doubt, ask the S-question; he
answers in batch.

Lessons carried forward, plus this session's:

- **The corpus finds bugs the spec cannot** (the operator-capture mixin named
  `list`; rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9's
  normalize-both-sides probe technique — still the tool when a section is
  stuck).
- **New: check a ruling's factual premises before enforcing it.** Rev. 7's
  note asserted both S12 goldens were attribute-position; one wasn't. The
  probe that settled it — fetch the original golden from the pugjs tag and
  compare — took one command and turned a would-be regression into a corpus
  restoration. Law can be ratified ahead of implementation (rev. 7 was, on
  purpose); its *premises* still get verified at enforcement time.
- **New: records are maps, forever.** Third sighting (`class-tokens`). Any
  `(map? v)` branch in code that can meet a Raw must test `raw?` first;
  grep for `map?` when adding one.
