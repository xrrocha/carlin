# Carlin — session handoff (rev. 20)

**Supersedes rev. 19 entirely.** State: **S27/S28 ruled and implemented.**
Ratchet **101/104, baselined, zero regressions ever**. Spec suites **17
tests, 103 assertions, 0 failures** (was 96). §3.13 mixins **12/12**, still
complete. Docket **EMPTY**.

Plan item 1 — the deferred inline-tag scanner — is **done**. What remains is
plan item 2 (retire `carlin.legacy`) and the roadmap.

## 1. Two corrections to rev. 18's account (carried from rev. 19)

Both still stand, and both were found by reading the tree rather than the
narrative:

1. **The ratchet denominator was 103, not 102.** Rev. 18's prose said 102;
   the manifest, harness and section table said 103. Narrative typo only. It
   is 104 as of this session (one case added).
2. **`carlin.legacy` has NOT been retired.** The commit titled "Legacy
   finally out of the way" is the S20–S26 batch; it means nothing *defers*
   to legacy any more. `src/carlin/legacy.clj` is still present, still
   required by `carlin.api`, still reached through `requiring-resolve` in
   `core/compile-ref`. Plan item 2 is untouched and remains entirely to do.

## 2. What landed this session

### 2.1 The diagnosis (rev. 19, retained because it explains the fix)

Rev. 18 scoped item 1 as "inline mixin calls inside `#[…]` plus a general
audit of `scan-text`." The audit found **six of seven inline spellings
already green**: paren args `#[+(m "x")]`, shorthand `#[+box.cls]`, attrs
`#[+box{...}]`, bare no-args `#[+hr2]`, nesting `#[b x #[i y] z]`, and
bracket-inside-string (S26's string-aware `matching-bracket` holds).

Exactly one was red: `#[+m "arg"]`, bare name plus inline text. And it was
**not a parser bug** — `parse-inline-fragment` already read it as `:argc 0`
with the text as `:inline-text`, which is what pug does (`<a></a>`, quotes
literal). Carlin already implemented the same thing at *line* position
byte-identically to pug (`+box Some text` → `<div>Some text</div>`).

The real defect was a **missing gate**. `check-arity` walks the parse tree,
but a `#[…]` interior is an opaque string at parse time — only re-parsed
later inside `codegen/scan-text`. So inline mixin calls were structurally
invisible to every tree-walking check:

```
+(m)                  ->  :mixin-arity, positioned, compile time   [ok]
p T #[+(m)] end       ->  raw sci ArityException, no position, RUNTIME [bug]
```

Arity was merely the instance the corpus exposed. `:nested-mixin`,
`:yield-children`, `:default-not-last` were equally blind in there.

### 2.2 S27 — ruled (a), implemented

`core/inline-fragments` parses every `#[…]` fragment at parse time,
including fragments nested inside other fragments' inline text.
`node-kids` — the walk's single child accessor — now yields them alongside a
node's real children. One edit at one choke point puts inline position in
reach of the **entire** check battery, present and future, without any
individual check knowing fragments exist.

The render path is deliberately untouched: codegen still re-parses when it
emits, so the hoist can never change output, only diagnose it. That is why
the ratchet did not move a single golden.

`matching-bracket` moved to `carlin.core` (codegen keeps a private alias) so
both halves of the pipeline share **one** scanner instead of two that can
drift — the rev. 13 lesson applied to the tool as well as the bug.

**The one subtlety, and it bit during implementation.** The interpolated
positions must mirror codegen's `scan-text` call sites exactly:
`:inline-text`, `:text`, `:dot-block/:text`, and a `:text-block`'s
`:body/:text`. My first cut read `:body` unconditionally, and the dot-block
pin went red — the dot block lives under `:dot-block`, and `:body` is shared
by three node types with three different meanings. Comments and filters
carry captured bodies under `:body` too, and **neither interpolates**
(comment bodies emit raw; filters run before the model exists), so hoisting
from them invents calls that never render and fails legal templates.
`:body` is now read only for `:text-block`, and a pin asserts the
non-interpolating bodies stay silent.

Same key, three meanings: the sentinel-collision species (rev. 12) in a
structural costume. Sixth sighting.

### 2.3 S28 — ratified, and it was already true

Inside `#[…]`, `+name text` passes **no argument**; the text becomes block
content, `:argc` 0. Not new law — the parser had read it that way all along,
it matches line position, and it matches pug. Only the gate was missing.

### 2.4 Corpus and pins

**`mixin.inline` (new, carlin-authored — not a morphed pug original).** Five
spellings: bare-with-text, bare-no-args, paren args, paren args *plus*
inline text, and shorthand-with-text. Each probed against pug 3.0.2, golden
generated from that run at `pretty:false`. **Carlin matched byte-for-byte on
first execution**, quotes-as-literal-characters included.

**Seven new diagnostics pins** (96 → 103 assertions), covering arity inline,
arity in a fragment nested in another fragment, arity in a dot block, arity
in a tagless text block, a *non-arity* check reaching inline
(`:yield-outside-include`), and the negative case above.

## 3. Artifacts

| Artifact | State |
|---|---|
| `src/carlin/core.cljc` | `matching-bracket` relocated here; `inline-fragments` added; `node-kids` walks fragments from the four interpolated positions, `:body` type-scoped to `:text-block`. |
| `src/carlin/codegen.cljc` | `matching-bracket` now a private alias of `core/matching-bracket`. Render path otherwise unchanged. |
| `test/carlin/diagnostics_test.cljc` | S27 pin block, 7 assertions (103 total). |
| `test-resources/corpus/.../mixin.inline.{carlin,html}` | New case, golden pug-generated. |
| `conformance-manifest.edn` | 101 cases, baselined. |
| `docs/carlin-spec.md` | **Rev. 14** — S27 structural fix, S28 ratification, the `:body` three-meanings note. |
| `test-resources/corpus/README.md` | S27/S28 logged. |

## 4. Open docket

**EMPTY.** S27 and S28 ruled and applied 2026-07-22.

## 5. Next session's plan — in order

1. **Retire `carlin.legacy`** (rev. 18's item 2, still untouched): fold
   `carlin.api` into `carlin.core`, drop the `requiring-resolve` in
   `compile-ref`, delete `legacy.clj`. Nothing defers to it; the moat has no
   moat-keeper left to protect.
2. `deftemplate`; sci `:eval` mirroring S15's template-ns; the CLJS matrix;
   vendor-vs-depend edamame.
3. Consider auditing the *other* now-reachable checks against inline
   position — the machinery covers them, but only arity and
   `:yield-outside-include` have pins proving it.

## 6. Working agreement (unchanged, one addition)

Ratchet green is the invariant; promote in the same commit; never loosen the
comparator; golden/template adjustments only with a logged departure (or, for
converter errors, a logged repair) — candidate edits go to the docket FIRST.
Ricardo rules, in batch, fast, when the probe record is laid out plainly.

**New: fix the class, then pin the instances.** S27 was ruled as (a) — the
structural fix — over two cheaper options that would have fixed arity alone.
The cost was one edit at one choke point; the return was five other
diagnostics reaching inline position for free, plus every check written from
here on. The pins then went in per position (dot block, text block, nested
fragment) because a structural fix is exactly the kind that looks right and
misses a key: the dot-block pin is what caught `:body` vs `:dot-block`.

## 7. Continuity notes

Ricardo: software architect in Quito; bilingual; Borges-adjacent; prose over
bullets; puns, Latin ("nihil obstat"). Repo is public: clone
`github.com/xrrocha/carlin`, install babashka, `bb ratchet` — cold-start
proven five times. NOTE: `bb show` takes ids RELATIVE to `cases/`
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

Lessons carried forward:

- **The corpus finds bugs the spec cannot** (rev. 9).
- **A printer is not a commodity when the goldens are documents** (rev. 9).
- **Check a ruling's factual premises before enforcing it** (rev. 11).
- **Records are maps, forever** (rev. 12) — the species is any sentinel that
  collides with a legitimate value. Sixth sighting at S27: `:body` meaning
  three different things across three node types.
- **Sessions die mid-flight; package early** (rev. 12).
- **A lesson learned in one position recurs one position over** (rev. 13) —
  vindicated twice this session: once at S27 itself, once *inside* the S27
  fix when `:body` turned out to be three keys wearing one name.
- **A corpus case can mask its own bug** (rev. 13). S27's sibling: a corpus
  case can mask a bug *by testing the other spelling*. `interpolated-mixin`
  is green on the paren form; the bare form was never written down.
- **Symmetry is not a safety argument** (rev. 14).
- **Replication is the obvious meaning of "splice here" written twice** (rev. 15).
- **Read the implementation, not just the output** (rev. 17).
- **A pin is only as good as its probe** (rev. 18).
- **Retiring a plan item is a measurement, not a verdict** (rev. 18).
- **A handoff's plan item is a hypothesis about the code** (rev. 19). Rev. 18
  inferred a scanner audit from one failing case; six of seven spellings were
  already green and the seventh was broken elsewhere.
- **New: absence of a corpus case is not absence of a feature.** Six inline
  spellings worked with no case exercising any of them, and the seventh
  failed with none exercising it either. A frontier of zero reds is a
  statement about the corpus, not about the language.
