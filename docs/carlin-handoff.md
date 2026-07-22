# Carlin — session handoff (rev. 12)

**Supersedes rev. 11 entirely.** State: **rulings 1–3 of rev. 7 are enforced;
ruling 4 (include/yield) is the last law awaiting enforcement.** Ratchet
**74/100, baselined, zero regressions ever** — no flips this session, and the
diffs explain why (see §4: everything left in §3.5 is gated on the S16 docket,
not on machinery). Spec suites: **16 tests, 86 assertions, 0 failures, 0
errors.** The docket is **OPEN: S16 (a)–(c), recommendations drafted, awaiting
Ricardo** — the first non-empty docket since S15.

The frontier is unchanged in numbers — **§3.5 attributes (3/8)**, **§3.11
include (5/11)**, **misc (19/23)**, **§3.13 mixins (8/11)** — but changed in
kind: §3.5's reds are now adjudication-blocked, not implementation-blocked.

## 1. The charter (Ricardo-confirmed, S1–S7)

Unchanged. New system feature by feature; whole-template bailout to
`carlin.legacy` on `:carlin/defer` only, never on genuine `:carlin/error`s;
legacy behavior-frozen not byte-frozen; `bb ratchet` is the enforcing
instrument; baseline promoted in the same stroke as each gain.

## 2. Artifacts

| Artifact | State |
|---|---|
| `docs/carlin-spec.md` | rev. 7 + this session's two in-place edits: (1) the rev. 7 note's S12 bullet carries an inline **erratum** (attribute-position claim wrong for `inheritance.alert-dialog`; departure log is authoritative; `attrs.carlin` edit stands); (2) **§8.2 gains a `template-ns` row** and a paragraph recording S15 as a design position (ambient vocabulary via namespace mechanics; sci/CLJS must mirror). The **rev. 8 revision note is NOT yet written** — deliberately held so it records this session's landed rulings 2–3 in one stroke. Write it next session. |
| `src/carlin/runtime.cljc` | **Ruling 2 enforced** in `attr-value-str`: `:style` map → `css-value`; every other map or coll → `(escape-attr (->js v))` — ->js first, escaper second, the `&quot;` shape. **`->js` rejects Raw explicitly** (records-are-maps, fourth sighting, preempted): a Raw would have sailed down the `map?` branch and JSON-encoded as `{"s":...}`; now `:unsupported-js-value`. |
| `src/carlin/core.cljc` | Parser's `parse-tail` `{` branch records **`:classes-before-attrs`** — how many shorthand classes preceded the attrs map (ruling 3's positional fact). |
| `src/carlin/codegen.cljc` | **Ruling 3 enforced**: new `thread-class-order` transform splits shorthand classes around the map's position and folds the trailing ones after the map's own `:class` value; applied at the top of BOTH `tag-code` and `mixin-call-code` (so `call-attributes`, `merged-attrs-code`, keyword suffix, and render-attrs' hoist all see the threaded node). Downstream machinery unchanged — `class-tokens`' recursive flatten does the rest. |
| `test/carlin/to_js_test.cljc` | +2 assertions: Raw rejected bare and nested in a map. |
| `test/carlin/escaper_test.cljc` | New `json-attribute-values` deftest (6 assertions): the hx-vals smoke shape, data-user, vectors, `:style` exemption (map and string), raw-whole-value bypass, and the `\u003C` guard riding along (note: ->js escapes `>` to `\u003E` itself — the attr escaper never meets it). |
| `test/carlin/merge_attrs_test.cljc` | Now requires `carlin.api`; helper `render1` (compile + `api/render`). New `class-order-is-textual` deftest (4 assertions): the witness `a.foo{:class "bar"}.baz` → `foo bar baz`; map-first keeps class at the map's position; class ATTRIBUTE placement follows the first class source (`.foo` before `:href` ⇒ class renders first); `&attributes` stays last. |
| `conformance-manifest.edn` | Untouched — 74 cases, no flips to promote. |
| corpus / departure log | **Untouched.** No golden was edited this session; every candidate edit is docketed (S16). |

## 3. This session's three moves

1. **Item 0 (erratum) done, minus the rev. 8 note** — held on purpose; see §2.

2. **Ruling 2 enforced, with a preempted records-are-maps sighting.** The
   seam was exactly as predicted (`attr-value-str`). Pinned at unit level;
   ZERO corpus flips, and honestly so: `attrs-data` now matches on every JSON
   shape and differs only on two escaper-shape questions (S16 a). The law is
   in force; the goldens are what's in question.

3. **Ruling 3 enforced end-to-end.** The parser SAW the textual positions but
   didn't RECORD them — one integer (`:classes-before-attrs`) fixes that, and
   one codegen transform consumes it. Verified through `bin/render` on four
   shapes before pinning. `mixin.attrs` moved visibly (the `bottom foo bar`
   and `thing baz` lines flipped within the case) but has three residual
   deltas (S16 c).

## 4. The docket — S16, OPEN, recommendations drafted

All three block §3.5 (and one blocks a mixins case). In discovery order:

- **S16 (a) — `attrs-data`, two deltas.** (i) Pug leaves `'` verbatim in
  attribute position (`Let's rock!`); carlin's five-entity escaper emits
  `&#39;`. This is S12's ratified substance in attribute position —
  **recommend: golden edit under the existing S12 departure entry.**
  (ii) `->js` escapes `&`→`\u0026` and `>`→`\u003E` "for symmetry"
  (docstring's word); pug's pipeline yields `&amp;quot;` (JSON.stringify
  leaves `&` alone, attr escaper then hits it). Both parse to identical
  JSON. The REAL script-context guard is `<` alone (`</script`, `<!--` both
  ride on it); the existing script-safety tests pin only `<`.
  **Recommend: narrow `js-string` to escape `<` only** — restores pug shape
  here, keeps the guard, no golden edit; the alternative (golden edit
  logging the `\u0026` shape as a departure) is defensible but buys nothing.

- **S16 (b) — `attrs.js`: pug hoists `class` (and reorders) to the FRONT of
  the attribute list even when the source writes `:href` first.** Head-on
  collision with rev. 5's ratified source-order doctrine ("attribute order
  is observable output; templates are source-ordered documents").
  **Recommend: keep the doctrine, edit the golden, log the departure** —
  same shape as S8's permanent departure.

- **S16 (c) — `mixin.attrs`, three residual deltas, premises UNVERIFIED.**
  (i) First `centered` call: golden shows `Hello World` bare inside the div,
  no `<h1>` — but the carlin (and seemingly the pug) mixin wraps title in
  `h1`. Smells like a converter artifact or a pug-original divergence —
  **verify against the pug 3.0.2 tag before ruling** (the standing lesson:
  check a ruling's factual premises). A `pug-3.0.2.tgz` npm pack was fetched
  to `/tmp` (now lost with the sandbox); NOTE: the npm pack may not carry
  the test corpus — fetch the GitHub tag tarball instead
  (`https://github.com/pugjs/pug/archive/refs/tags/pug%403.0.2.tar.gz`,
  needs URL-encoding of the `@`).
  (ii) `+foo.thunk{... :class classes}` expects `thing foo bar thunk`;
  carlin emits `thing thunk foo bar` — the call-site shorthand vs
  attrs-map-`:class` order INSIDE `&attributes attributes` forwarding;
  possibly pug hoisting again (b), possibly a threading gap in
  `call-attributes` + `merge-attrs` interplay.
  (iii) The #1424 regression `+(work_filmstrip_item "work"){"data-profile"
  "profile" ...}` renders `<div>work</div>` — STRING-KEYED attrs dropped
  entirely somewhere in the mixin `attributes` path. This one looks like a
  genuine bug, not a docket question: exotic string keys are spec-legal
  (§3.5).

## 5. Next steps — in order

0. **Write the rev. 8 revision note** (erratum pointer, S15/template-ns,
   rulings 2–3 landed, S16 opened).
1. **Rule S16 (a)/(b)** — recommendations above; both are quick.
   Implement: js-string narrowing (if ratified) + two golden edits with
   departure entries. Expected flips: `attrs-data`, `attrs.js`.
2. **S16 (c)**: fetch the pug tag, verify (i)'s premise; fix (iii) — the
   string-key drop — as a bug regardless of the docket.
3. **Ruling 4 — include-with-body at `yield`.** Probe pug 3.0.2 FIRST (npm
   IS reachable from the sandbox — proven this session): no-`yield`
   destination; multiple `yield`s; body on `:kind :raw`. Results into
   §3.11, then implementation; `:yield-outside-include` joins diagnostics.
   Several §3.11 reds are also include-with-filter (needs a filter-attrs
   parse + corpus repair to `include:custom{:opt "val"}`).
4. Then the pools: attributes, misc, text (tagless lone-dot block), mixins.
5. Later: `deftemplate`, sci (must mirror S15's template-ns), CLJS matrix,
   vendor-vs-depend edamame.

## 6. Working agreement (unchanged)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure — and,
after S16's discipline this session: **candidate golden edits go to the docket
first, even when existing law seems to cover them** (S12's scope was
mis-applied once already; premises get verified at enforcement time). Spec
records decisions with their why. One decision per exchange, recommendation +
rationale, Ricardo rules — and he rules well.

## 7. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). When in doubt, ask the S-question; he
answers in batch.

Lessons carried forward, plus this session's:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11) — now
  applied prospectively: S16 (c)(i) is explicitly premise-unverified.
- **Records are maps, forever** — FOURTH sighting, this time preempted:
  routing new values into an existing `map?` branch (`->js`) counts as
  "adding one"; grep fired before the bug did.
- **New: sessions die mid-flight; package early.** Sandbox interruption cost
  a pug-source fetch and nearly the session's work. Deliverables (zip +
  handoff) now get produced the moment a work unit lands, not at session
  end. The handoff's job is to make any interruption a non-event.
- **New: a small integer can be the whole fix.** Ruling 3 needed exactly one
  recorded fact the parser already possessed. Before designing machinery,
  ask what the parser already knows.
