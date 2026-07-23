# Carlin test battery

The executable definition of done for the carlin implementation
(`carlin-spec.md` §12). Written **before** the implementation, deliberately:
the cursor refactor proceeds against a wall of red that only ever turns green.

## Layout

```
bb.edn / deps.edn            tasks (babashka) / JVM classpath & test alias
conformance-manifest.edn     the golden ratchet: cases that MUST render correctly
diagnostics-manifest.edn     the diagnostics ratchet: cases that MUST be REJECTED
docs/                        carlin-spec.md (the authority), carlin-conformance.md, mascot art
src/carlin/api.cljc          spec §5 API — the public front door (compile-template, compile-ref, render)
src/carlin/core.cljc         the front half: cursor, parse, structural checks, include-splice, inheritance
src/carlin/codegen.cljc      the back half: checked tree -> (fn [model env] hiccup)
src/carlin/runtime.cljc      spec §6/§7 runtime: escaper, raw marker, merge-attrs, ->js, render-hiccup
test/carlin/harness.clj      golden runner + manifest ratchet + per-section report
test/carlin/diagnostics_harness.clj  diagnostics runner: illegal templates, class+position
test/carlin/artifact_test.cljc  §5.2 artifact contract: :symbols and :vocabulary
test/carlin/*_test.cljc      spec-derived suites (details below)
test-resources/corpus/       morphed pugjs 3.0.2 golden corpus (see its README.md)
test-resources/diagnostics/  the diagnostics corpus: illegal templates (see its README.md)
```

## Tasks

| Task | Meaning |
|---|---|
| `bb ratchet` | **CI gate 1.** Runs the golden corpus; any manifest case failing = exit 1. Newly passing cases are listed for promotion. |
| `bb baseline` | Rewrites the manifest from current reality (used once per legitimate jump; never to paper over a regression). |
| `bb show <case>.carlin` | One case's expected/actual diff or error. |
| `bb diagnostics` | **CI gate 2.** Runs the diagnostics corpus (illegal templates); any manifest case that compiles, or errs with the wrong class or position, = exit 1. |
| `bb dbaseline` | Rewrites `diagnostics-manifest.edn` from current reality. |
| `bb dshow <case>` | One diagnostics case: source, expectation, actual, verdict. |
| `bb differential` | **CI gate 3.** Renders every golden case through BOTH evaluation strategies — `platform/evaluate` and `deftemplate`'s emission — and compares bytes; any disagreement = exit 1 (spec §12.6). |
| `bb spec-tests` | The spec-derived unit suites — the contract for `carlin.runtime`, `file-resolver`, diagnostics, and the §5.2 artifact. Written red before the implementation; **green since S29** (25 tests, 195 assertions). |

## Comparison mode

**Structural-whitespace-insensitive, text-exact.** The vendored goldens are pug
*pretty-mode* output; carlin excludes pretty printing (spec §10). Newline+indent
at tag boundaries — exactly the pretty-mode delta — is collapsed on both sides;
whitespace inside text nodes is compared exactly. Whitespace-significant cases
(`pre`) may need individual golden adjustment: adjust the golden, log the
departure (corpus README rule), never loosen the comparator.

Serializer whitespace correctness is NOT entrusted to this comparator — that is
the differential oracle's job (spec §12.3, hiccup2 on the JVM), to be wired
when `carlin.runtime/render-hiccup` exists.

## The suites

- **harness** — spec §12.1. 108 golden cases across every language section,
  reported per spec section.
- **escaper_test** — spec §12.2, the paranoid file: the five entities,
  attribute-value context, the raw marker in element AND attribute position,
  §7.2 profile rows (void elements, boolean attrs, html vs xml), dynamic tag
  normalization (Q13).
- **merge_attrs_test** — the §4.6 contract: classes additive always (order,
  flattening, map-conditional, duplicates preserved), the three-policy scalar
  matrix, style-map per-key merging.
- **to_js_test** — §6.3: the narrow domain, never-a-guess errors, and the
  script-context safety that justifies the helper's existence (`</script>`
  breakout, `<!--`, quote/backslash escaping).
- **resolver_test** — §5.3/§5.4 against the future `carlin.core/file-resolver`:
  contract shape, extension defaulting, `:kind`, relative/root anchoring,
  the root-jail, and `:deps` completeness via `compile-ref`.
- **artifact_test** — §5.2: `:symbols` holds model keys and nothing else
  (S32's gensym exclusion, with the mutation guard that a name-shape filter
  fails), and `:vocabulary` names the ambient globals `:code` borrows (S31),
  with the four shadowing spellings §8.2 requires keep working. Neither key
  reaches the rendered bytes, so neither has a corpus.
- **diagnostics_test** — §8.3/§12.4: asserts error **class**
  (`:carlin/error` keyword) and **position** (`:line`/`:col`), never message
  prose — wording may improve freely without test churn. Covers: unterminated
  forms (restrictive-four), extends-not-first, top-level-under-extends,
  block-via-include, include children, nested mixins, compile-time arity,
  default-not-last, static attr conflict, unresolvable refs, and `while` as a
  positioned exclusion. Since S30 it also covers malformed directive heads
  and — in `falsy-operands-stay-legal` — the discipline that makes them safe:
  absence is tested, never falsity, since `if nil` and `p= nil` are legal
  templates carrying the same `:form nil` a missing operand does.

## Status

Four gates, all green:

| gate | command | state |
|---|---|---|
| golden conformance | `bb ratchet` | **101 / 104**, zero regressions ever |
| diagnostics corpus | `bb diagnostics` | **43 / 43** |
| deftemplate differential | `bb differential` | **101 identical, 0 differing** (3 uncompilable = the known reds) |
| spec unit suites | `bb spec-tests` | 25 tests / 195 assertions / 0 failures |

All four run on the JVM and babashka. **ClojureScript is designed, not
demonstrated**: `deftemplate` is carlin's CLJS path and has never run under a
CLJS compiler. A `:cljs-test` alias in `deps.edn` is the starting point; see
the handoff's plan item 1 for what needs probing first.

`carlin.legacy` was retired at S29 — carlin now compiles every template
itself and **fails fast at compile time**, with positioned errors from both
halves of the pipeline (spec §8.3). Its sole runtime dependency is edamame.

The **diagnostics corpus** (`test-resources/diagnostics/`, spec §12.5) was
built at S30 and is the mirror of the golden one: illegal templates, compared
on error class and position rather than output bytes, where a case that
*compiles* is the failure. It exists because the golden corpus holds only
legal templates and so cannot observe what carlin does with malformed
source — it was green through two whole classes of defect (S29, S30) for
that reason alone.

## Historical baseline (2026-07-16, legacy implementation)

`15 / 108` — the ratchet's starting floor. The per-section zeros are the
feature roadmap in numeric form: includes 0/11, inheritance 0/16, mixins 0/11,
filters 0/11 land with their passes; attributes 0/8 lands with `merge-attrs`;
text 0/5 with dot blocks. Verified-fair: sampled failures are genuine feature
gaps (class merge semantics, dot blocks, `\#{` escaping), not harness artifacts.

## Working agreement

1. `bb ratchet` green is the invariant; it runs on every change.
2. A case flips to passing → promote it into the manifest in the same commit.
3. A golden needs adjusting → the departure is documented (corpus README);
   the comparator is never loosened case-by-case.
4. `src/carlin/api.cljc` is the only file the battery and the implementation
   share. The battery does not import implementation internals — ever.
