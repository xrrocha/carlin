# Carlin — session handoff (rev. 13)

**Supersedes rev. 12 entirely.** State: **rev. 12's next-steps 0 and 2 are
done; step 1 (rule S16 a/b) is now the gate, and the docket it waits on has
been reshaped by evidence.** Ratchet **74/100, baselined, zero regressions
ever** — reproduced from a **cold clone** of `github.com/xrrocha/carlin`
(public), no flips this session. Spec suites: **17 tests, 90 assertions, 0
failures, 0 errors** (+1 test / +4 assertions: the `&attributes` token pin).
The docket is **OPEN: S16 (a)–(c), recommendations sharpened by premise
verification, awaiting Ricardo.**

Frontier numbers unchanged — **§3.5 attributes (3/8)**, **§3.11 include
(5/11)**, **misc (19/23)**, **§3.13 mixins (8/11)** — and every §3.5/mixins
red touched this session is adjudication-blocked or template-repair-blocked,
not machinery-blocked.

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. New system feature by feature; whole-template bailout to
`carlin.legacy` on `:carlin/defer` only, never on genuine `:carlin/error`s;
legacy behavior-frozen not byte-frozen; `bb ratchet` is the enforcing
instrument; baseline promoted in the same stroke as each gain.

## 2. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | **Rev. 8 revision note WRITTEN** (rev. 12's held item): records S15/template-ns as a design position, rulings 2–3 as enforced, the `&attributes` token fix, the erratum pointer, and S16's opening **including the premise findings**. §3.5's `&attributes` bullet gains the bare-symbol-is-a-name-token clause (with the buffered-sigil example and the delimited-forms carve-out). |
| `src/carlin/core.cljc` | **`&attributes` sigil-swallow FIXED** in `parse-tail`: a bare symbol after `&attributes` scans as a word-char TOKEN (mirroring the bare `+name` fix); `(…)`/`{…}`/`[…]` keep the reader, which is authoritative for its own delimiters. Before: edamame read `attributes= x` as the single symbol `attributes=` → free-symbol nil → attrs silently dropped, tail rendered as literal text. |
| `test/carlin/merge_attrs_test.cljc` | New `amp-attributes-bare-symbol-is-a-token` deftest (4 assertions): bare + `=`, bare + `!=`, bare alone (string keys forwarded), delimited form + `=`. Includes the probe the corpus cannot express (arg name ≠ value). |
| `conformance-manifest.edn` | Untouched — 74 cases, no flips to promote. |
| corpus / departure log | **Untouched.** Zero golden or template edits — every candidate is docketed (S16), per the rev. 12 discipline. |
| pug 3.0.2 sources | GitHub tag tarball fetched and consulted (`packages/pug/test/cases/mixin.attrs.{pug,html}`); the npm-pack detour is moot. Lives only in the sandbox — refetch as needed: `https://github.com/pugjs/pug/archive/refs/tags/pug%403.0.2.tar.gz` (URL-encode the `@`). |

## 3. This session's three moves

1. **Rev. 8 revision note written** (step 0 discharged). It also absorbs this
   session's bug fix and the S16 premise findings, so the spec's record is
   current through today.

2. **S16 (c) premises verified against the pug 3.0.2 tag — both suspect
   deltas are converter artifacts in carlin's TEMPLATES, goldens honest:**
   - **(c)(i)**: pug's original is `+centered#First Hello World` — **no
     argument list**. `Hello World` is inline block text; `title` is
     undefined; `- if (title)` skips the `h1`. The converter (presumably the
     exact-arity repair pass) promoted the inline text into the argument:
     `+(centered "Hello World")#First`. Probe confirms the repaired form
     `+(centered nil)#First Hello World` parses and renders correctly today.
   - **(c)(ii)**: pug's original is `+foo(attr3='baz' … class=classes).thunk`
     — **`.thunk` AFTER the parens**. Textual order: def's `thing`, map's
     `foo bar`, call's `thunk` → `thing foo bar thunk`. The converter moved
     `.thunk` before the map; carlin, correctly textual under ruling 3,
     faithfully renders the converted (wrong) order. Probe confirms
     `+foo{…}.thunk` (shorthand after map) already parses and threads
     correctly — the doctrine and pug's golden AGREE.

3. **S16 (c)(iii) reclassified and FIXED.** Not a string-key drop — string
   keys forward fine through `&attributes` (probe-verified). The real bug:
   the **sigil-swallow** above, the bare-`+name` lesson recurring one
   position over. The corpus masked half of it: the #1424 case's argument
   name and value are both `work`, so the literal-text symptom *happened to
   equal* the intended output. Fixed, §3.5 amended, pinned at unit level.
   ZERO corpus flips (mixin.attrs' remaining deltas are exactly (c)(i) and
   (c)(ii)'s template repairs — see the docket).

## 4. The docket — S16, OPEN, recommendations sharpened

- **S16 (a) — `attrs-data`, two deltas.** Unchanged from rev. 12:
  (i) apostrophe in attribute position — **recommend golden edit under the
  existing S12 departure entry** (its ratified substance, correctly scoped
  this time). (ii) **Recommend narrowing `js-string` to escape `<` only** —
  `<` alone carries the script-context guard (`</script`, `<!--` ride on
  it); dropping the symmetric `>`/`&` restores pug's `&amp;quot;` shape with
  no golden edit. Existing script-safety tests pin only `<`.

- **S16 (b) — `attrs.js`: pug hoists `class` to the front.** Unchanged:
  head-on collision with rev. 5's ratified source-order doctrine.
  **Recommend keep the doctrine, edit the golden, log the departure** —
  S8's shape, a permanent departure.

- **S16 (c) — `mixin.attrs`: now two TEMPLATE REPAIRS, zero doctrine.**
  Premises verified (see §3.2). **Recommend repairing the converted template
  to pug's actual anatomy**: `+(centered nil)#First Hello World` and
  `+foo{:attr3 "baz" :data-foo val :data-bar (raw val) :class classes}.thunk`.
  Logged as converter-error repairs, NOT departures — no law is touched.
  Both repaired forms are probe-verified green today.

Expected flips if all ratified: `attrs-data`, `attrs.js`, `mixin.attrs` —
ratchet 74 → 77.

## 5. Next steps — in order

1. **Rule S16 (a)/(b)/(c)** — recommendations above; all quick, Ricardo
   answers in batch. Implement: js-string narrowing (if ratified), two
   golden edits with departure entries, two template repairs with log
   entries. Baseline the flips in the same stroke.
2. **Ruling 4 — include-with-body at `yield`** — the last rev. 7 law
   awaiting enforcement. Probe pug 3.0.2 FIRST (tag tarball has the corpus;
   npm also reachable): no-`yield` destination; multiple `yield`s; body on
   `:kind :raw`. Results into §3.11, then implementation;
   `:yield-outside-include` joins diagnostics. Several §3.11 reds are also
   include-with-filter (needs a filter-attrs parse + corpus repair to
   `include:custom{:opt "val"}`).
3. Then the pools: attributes, misc, text (tagless lone-dot block), mixins.
4. Later: `deftemplate`, sci (must mirror S15's template-ns), CLJS matrix,
   vendor-vs-depend edamame.

## 6. Working agreement (unchanged)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure (or, for
converter errors, a logged repair) — candidate edits go to the docket FIRST,
even when existing law seems to cover them; premises get verified at
enforcement time. Spec records decisions with their why. One decision per
exchange, recommendation + rationale, Ricardo rules — and he rules well.

## 7. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). When in doubt, ask the S-question; he
answers in batch. Repo is public: clone `github.com/xrrocha/carlin`, install
babashka, `bb ratchet` — cold-start proven this session.

Lessons carried forward, plus this session's:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11) — paid
  off spectacularly: S16 (c)'s three deltas dissolved into two converter
  artifacts and one mislabeled parser bug. None was what rev. 12 thought.
- **Records are maps, forever** — fourth sighting preempted (rev. 12).
- **Sessions die mid-flight; package early** (rev. 12) — honored: zip
  delivered the moment the work unit landed.
- **A small integer can be the whole fix** (rev. 12).
- **New: a lesson learned in one position recurs one position over.** The
  bare-`+name` sigil-swallow reappeared verbatim at `&attributes`. When a
  fix's rationale is "the reader swallows anatomy sigils", grep for every
  other place a bare form meets `read-line-form` before closing the book.
- **New: a corpus case can mask its own bug.** #1424's arg name and value
  coincide (`work`), so literal-text rendering looked like evaluation. Pins
  now use probe values where name ≠ value.
