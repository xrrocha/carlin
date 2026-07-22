# Carlin test corpus — morphed from pug4j's test battery

**Verdict: yes, with triage.** The valuable core of pug4j's battery is not pug4j's
own tests but what it vendors: the **official pugjs 3.0.2 golden-file suite**
(`cases/*.pug` + `*.html` expected-output pairs). That corpus is the de-facto
conformance definition of pug — exactly the test plan the carlin spec (§12.1)
calls for. It has been morphed to carlin syntax here. The rest of pug4j's tree
was triaged as below.

## Triage of the pug4j test tree

| Directory | Verdict | Rationale |
|---|---|---|
| `pugjs@3.0.2/cases` (+`auxiliary`, `fixtures`, `dependencies`, …) | **Adopted, morphed** | Official pugjs golden corpus; 115 paired cases + support files |
| `pugjs@3.0.2/anti-cases` | Not morphed | Error-message corpus; diagnostics are expression-language-specific — carlin's diagnostics corpus (spec §12.4) must be written fresh against carlin's own positioned errors |
| `pugjs@3.0.2/cases-es2015`, `output-es2015` | Dropped | Tests ES2015 JS features; meaningless under Clojure |
| `pugjs@3.0.2/__snapshots__`, `temp`, `browser` | Dropped | pug4j/pugjs internals |
| `pug@2.0.4`, `pug@2.0.4_adjusted` | Skipped | Older revision of the same corpus; superseded by 3.0.2 |
| `lexer`, `lexer_0.0.8`, `parser` | Skipped | Snapshot tests of pug4j's own token stream / AST — meaningless for a different implementation |
| `compiler`, `originalTests`, `issues` | **Converted to `legacy/`, unreviewed** | Jade-era + pug4j regression cases; mostly redundant with the official corpus but worth mining selectively; converter flags left in place (29 `CARLIN_TODO` markers, see each `TODO-flags.json`) |
| `kitchensink`, `tests`, `testsGraalVM`, `benchmark`, `template`, `loader`, `errors`, `grammarTest` | Skipped | pug4j integration tests bound to JEXL/GraalJS expression handling and pug4j API specifics |

## What's in this corpus

```
pugjs-3.0.2/
  cases/        108 golden pairs (.carlin + .html), morphed and reviewed
  cases/auxiliary, fixtures/, dependencies/, …   support files, morphed
  _excluded/    cases that carlin deliberately does not pass (see below)
legacy/         compiler/, originalTests/, issues/ — mechanical conversion, UNREVIEWED
pug2carlin.py   the converter (rerunnable, documents every mechanical rule)
```

### Conversion approach

Mechanical converter + hand-fix tier. The converter handles: attribute parens →
Clojure literal maps (JS→Clojure value translation: strings, numbers, arrays,
objects, dotted access → keyword access, `+` concat → `(str …)`, ternary →
`(if …)`, `&&`/`||`/`!` → `and`/`or`/`not`, comparison ops); `- var` runs →
`- (let […])` **with structural re-indentation** (JS's sequential shadowing
becomes nested lets — see `case.carlin` for the pattern); `each v, i in xs` →
`each [i v] in (map-indexed vector xs)`; mixin definitions → binding vectors and
calls to the one-form anatomy (`+(name args)` / bare `+name`, then
shorthand/attrs/`&attributes`, then tag tails);
`key!=v` → `(raw v)`; `extends`/`include` ref renaming; interpolation contents
(including inside dot blocks — filter bodies stay verbatim); Vue-style attr
names → string keys.

Hand-rewritten (JS-idiomatic, semantic re-expression): `attrs.js`,
`code.conditionals`, `code.iteration` (the `count++` closure becomes the
carlin atom idiom), `each.else` (`Object.create` + mutation → map literal;
object iteration `each val, key` → `each [key val] in m` — the converter can't
statically distinguish array-index from map-entry iteration, so these were
reviewed case by case), `regression.784` (`.replace` chains →
`clojure.string/replace` threading), `inline-tag`, `tag.interpolation`,
`attrs`/`attrs-data` (`new Date(1)` → its ISO string, matching the expected
output pug produced).

### `_excluded/` — negative-by-design

- `while.*` — carlin excludes `while` (spec §10). The case is retained as raw
  material for a *negative* test: carlin must reject it with a positioned error.
- `yield*-head.html` (3 goldens) — the only surviving `yield*` exclusions.
  The family's history: excluded wholesale when children under `include`
  were an error; spec rev. 10 adopted include-with-body at `yield`, and the
  three *includer-side* cases (`yield`, `yield-title`,
  `yield-before-conditional`) were probe-verified green under the landed
  law and **readmitted to `cases/`, Ricardo-ratified (S19), 2026-07-22**
  ("it's always good to see prodigal offsprings return") — an S14-shaped
  denominator adjustment in reverse: 100 → 103, landing at 83/103 in the
  same stroke. Their `*-head` include TARGETS moved to `cases/` as support
  files *without* golden pairing (the harness only pairs `.carlin` with an
  adjacent `.html`): compiled as roots they correctly raise
  `:yield-outside-include`, which the diagnostics suite pins — so their
  goldens stay here, negative-by-design.

- **Dependency-heavy filter cases (8)** — `filters.markdown`, `filters.less`,
  `filters.stylus`, `filters.coffeescript`, `filters.nested` (uglify-js +
  coffee-script), `filters.include` (markdown-it + coffee-script),
  `filter-in-include` (less), and `pipeless-filters` (markdown-it). Each needs
  a filter carlin **deliberately does not ship**: §3.12 ships `:verbatim` and
  `:cdata` only, and markdown/less/stylus/coffee are user-supplied through the
  `:filters` option — an explicit extension point rather than an npm-style
  package convention. These are not gaps; they are the design position working
  as intended, and counting them in the denominator made the conformance
  number understate the engine by eight. Retained as raw material: any of them
  becomes a *positive* test the moment a caller supplies the filter, and
  `filters.custom` (which the harness supplies via `case-filters`) is the
  worked example. Ricardo-ratified (S14), 2026-07-19.

`fixtures/{include,layout}.syntax.error.carlin` are intentionally broken
(unterminated attrs) and were copied unconverted — they should break carlin too,
just with better error messages.

## Two spec gaps discovered by the corpus — both resolved (spec rev. 2)

1. **Interpolated tag names** — adopted (Q13): `#{expr}` in tag position with
   the normal tag surface; see `tag.interpolation.carlin`,
   `intepolated-elements.carlin`. Dynamic-tag shorthand merges at runtime;
   dot blocks on dynamic tags are never raw-text.

2. **Mixin call anatomy** — resolved (Q14): the call is **one Clojure form**,
   `+(name args)` or bare `+name`, followed by shorthand/attrs/`&attributes`
   and the standard tag tails. `+(item "contact") Contact` is unambiguous
   because the reader delimits the form. The corpus uses this syntax
   throughout.

One pug corner encountered during the morph is **excluded**: interpolated
*mixin names* (`+#{expr}(...)`, exercised once in `mixins.pug`). Dynamic
dispatch by computed name would defeat compile-time arity checking and has no
letfn-natural rendition; the corpus morphs that call to its static equivalent
with a marker comment (spec §10).

## Known review items for golden outputs

Expected `.html` files were copied verbatim. Under carlin's *documented*
departures a few may diverge and will need adjudication when the suite first
runs — most notably class-duplicate handling (pug dedups in some merge paths;
carlin preserves duplicates by spec §4.6) and whitespace in a handful of
pretty-printed-era outputs. Divergences should be resolved by editing the
golden file and logging the departure, never by silently matching pug.

## Departure log

- `tag.interpolation.carlin`, `mixin.attrs.carlin` — mixin definitions were
  nested under `- (let …)` bodies, an artifact of the mechanical `- var` →
  `let`-with-children morph. Definitions are top-level only (spec §3.13, Q10);
  the defs were hoisted, calls left in place. Goldens unchanged (defs emit
  nothing positionally). Adjudicated 2026-07-19.
- `mixin.attrs.carlin` — `+(centered Hello World)` was a morph artifact
  (dropped quotes), now `+(centered "Hello World")`.
- `tag.interpolation.carlin` — `+item{:href "/contact"}` called a 1-ary mixin
  with no argument (pug's absent-is-undefined). Arity is exact (spec §3.13);
  the call now passes an explicit nil. Golden unchanged.
- `mixin-block-with-space.carlin` — `mixin m [id]` declared a never-used
  parameter its only call omitted; now `mixin m []`. Golden unchanged.
- `blocks-in-if.carlin` — `if ajax` / `else` had been morphed into `-if{:ajax
  true}` / `-else` (tag-shaped artifacts of the JS-conditional morph); restored
  to carlin `if`/`else` directives. Golden unchanged. Adjudicated 2026-07-19.
- `mixins.carlin` — two morph artifacts. (1) `mixin foobar [str]` bound a
  parameter named `str`, shadowing `clojure.core/str` inside the body's
  `(str s "interpolated")`; renamed the parameter to `s`. (2) A `//- carlin:
  …` annotation authored on the same physical line as `+(foobar "This is ")`
  was read as inline text and became block content; moved to its own line
  above the `- (let …)`. Golden unchanged. Adjudicated 2026-07-19.
- `mixin-hoist.carlin` — no template change. The golden renders `h1= title` as
  `<h1>Pug</h1>` from a template that never mentions `title`: the value comes
  from pug's own case-runner locals. The harness now renders the corpus with a
  minimal `case-model` (`{:title "Pug"}`) instead of `{}` — corpus fidelity,
  not a comparator change. Measured at zero collateral across the then-50
  green cases before adoption. Ricardo-ratified (S9), 2026-07-19.
- `mixin.merge.carlin` — pug's `p.bar&attributes(attributes)(class="baz")`
  had been morphed to a trailing bare paren group, which carlin reads as
  inline text (it became the tag's content, `(class="baz") Four`). The second
  attribute source is an ordinary attrs map in carlin:
  `p.bar&attributes attributes{:class "baz"}`. Golden unchanged — and it
  pins §4.6 source order exactly: `class="bar baz hello"`, shorthand then
  map then &attributes. Adjudicated 2026-07-19.
- `attrs.carlin`, `inheritance.alert-dialog.carlin` — **golden edits, and the
  first departures that are semantic rather than morph repairs.** Carlin's
  escaper is deliberately stricter than pug's: spec §7.1's five entities apply
  in text AND attribute position, where pug escapes `'` nowhere and leaves `<`
  alone inside attribute values. So `attrs.html` now reads
  `bar="&lt;baz&gt;"` (was `bar="<baz>"`) and `inheritance.alert-dialog.html`
  now reads `I&#39;m an alert!` (was `I'm an alert!`). Both make output
  strictly safer; the paranoid escaper file (§12.2) is the pinned contract and
  matching pug here would mean rewriting it first. Ricardo-ratified (S12),
  2026-07-19: "an opportunity to improve on the status quo… as long as we
  document the divergence properly."
  **Scope narrowed by spec rev. 7 (escaping boundary), 2026-07-21.** Rev. 7
  moves escaping to the dynamic boundary: literal template text is verbatim;
  the five-entity escaper (S12's substance) still applies wherever escaping
  applies — interpolation, buffered code, attribute values. Consequences for
  the two edited goldens, position by position: `attrs.html` (`&lt;baz&gt;`,
  ATTRIBUTE position) **stands** — attribute values remain escaped, stricter
  than pug, per S12. `inheritance.alert-dialog.html` (`I&#39;m`, TEXT
  position) **reverted to pug's original verbatim apostrophe** — its edit was
  a workaround for static-text escaping, which rev. 7 abolishes; the revert
  was verified byte-identical (modulo the apostrophe) against the pugjs
  pug@3.0.2 tag. Rev. 7's revision note claimed both edits lived in attribute
  position; that premise was wrong for alert-dialog, and this annotation is
  the correction. So S12 is not reversed but re-scoped: one golden edit
  survives it, one dissolves under the boundary it never should have crossed.
  NOTE for whoever works `mixin.block-tag-behaviour.carlin` next: its golden
  carries the same `I'm` — under rev. 7 now correct as vendored, no S12 edit
  pending; the case still has its unrelated defect (a spurious nested `<p>`).
  **Third S12 golden edit: `attrs-data.html`** (`Let's` → `Let&#39;s`) —
  apostrophe in ATTRIBUTE position, squarely inside S12's surviving scope
  (attribute values remain escaped by the five-entity escaper). Docketed as
  S16 (a)(i) per the rev. 12 discipline (candidate edits go to the docket
  first even when existing law seems to cover them), Ricardo-ratified
  2026-07-22. The case's other delta needed no edit: narrowing `js-string`
  to `<` only (S16 (a)(ii), spec §6.3 / rev. 9) restored pug's `&amp;quot;`
  shape at the source.
- `attrs.js.html` — **golden edit, permanent departure (source order vs
  pug's class hoisting).** Pug hoists `class` to the front of the rendered
  attribute list; carlin renders attributes in textual source order (spec
  §4.6, rev. 5 doctrine, threaded through codegen by rev. 8's ruling-3
  enforcement) — owning the order is the point of owning the serializer.
  Four occurrences edited: `<a class="button" href="/user/5">` →
  `<a href="/user/5" class="button">`, matching the template's
  `{:href … :class …}`. S8's shape: the wall is worth more than the case.
  Ricardo-ratified (S16 (b)), 2026-07-22.
- `mixin.attrs.carlin` — **two converter-error repairs, NOT departures**
  (no law touched; goldens verified honest against the pugjs pug@3.0.2 tag).
  (1) Pug's original `+centered#First Hello World` carries NO argument list —
  `Hello World` is inline block text and `title` is undefined, so the `h1`
  never renders; the converter's exact-arity repair pass had promoted the
  inline text into the argument. Repaired to `+(centered nil)#First Hello
  World`. (2) Pug's original `+foo(attr3='baz' …).thunk` has `.thunk` AFTER
  the attribute list — textual class order `thing foo bar thunk`; the
  converter had moved it ahead of the map. Repaired to
  `+foo{:attr3 "baz" :data-foo val :data-bar (raw val) :class classes}.thunk`.
  Both repaired forms were probe-verified green before the ruling.
  Ricardo-ratified (S16 (c)), 2026-07-22.
- `inheritance.extend.include.carlin` — **permanent departure, will never
  pass.** The layout includes `window.carlin`, which declares
  `block window-content`, and the page overrides it: pug allows extending a
  block that arrived via include; carlin makes it the positioned
  `:block-in-include` error (spec §3.11 Q9 / D7 — extends is inheritance,
  include is composition, and the two do not leak). The diagnostics suite pins
  the error. Ricardo-ratified (S8), 2026-07-19: the wall is worth more than the
  case. Relaxing later stays compatible; the reverse would not.
- **harness `case-filters`** — the corpus is compiled with a `custom` filter
  registered (`(fn [text attrs] (str "BEGIN" text "END"))`), which pug's own
  case runner supplies. Delivered through carlin's public `:filters` compile
  option, so the battery exercises the documented extension point rather than
  a compiler special case. Keep it minimal, as with `case-model`.
  Ricardo-ratified (S13), 2026-07-19.
- **`:body-without-yield` — behavioral departure (S17).** A body on an
  include whose target (fully resolved) contains no `yield` is a positioned
  compile error in carlin. Pug 3.0.2 does NOT discard the body — probed
  2026-07-22 against both npm `pug@3.0.2` and pug-linker's source: its
  `defaultYieldLocation` buries the body inside the *deepest last block* of
  the included file (e.g. producing `<p>` nested inside `<p>`), a landing
  site that is an accident of the included file's shape, and the linker's
  own source carries `// todo: probably should deprecate this with a
  warning`. Ruled grossly unexpected under the rev. 10 lossiness rule:
  "camming content at an unpredictable context-dependent location is
  inadmissible." No corpus case exercises the pug behavior; the diagnostics
  suite pins the error. Ricardo-ratified (S17), 2026-07-22.
- `filters.include.custom.carlin` — **converter-error repair + consequent
  golden edit (S18), NOT a departure.** The template still carried pug
  attribute syntax: `include:custom(opt='val' num=2)` → repaired to
  `include:custom{:opt "val" :num 2}` (the include branch now parses a
  filter-attrs map, spec §3.11/§3.12, rev. 10 ride-along). The case includes
  *itself* and the `custom` filter embeds the file's own source, so the
  golden's embedded line necessarily changes with the repair (it read
  `…filters.include.custom.pug`, the pre-morph source). The embedded text
  was regenerated mechanically from the repaired file and verified to equal
  `BEGIN` + file bytes + `END`. Ricardo-ratified (S18), 2026-07-22.
