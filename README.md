# Carlin test battery

The executable definition of done for the carlin implementation
(`carlin-spec.md` §12). Written **before** the implementation, deliberately:
the cursor refactor proceeds against a wall of red that only ever turns green.

## Layout

```
bb.edn / deps.edn            tasks (babashka) / JVM classpath & test alias
conformance-manifest.edn     the ratchet: cases that MUST pass
docs/                        carlin-spec.md (the authority), carlin-conformance.md, mascot art
src/carlin/api.cljc          spec §5 API — the public front door (compile-template, compile-ref, render)
src/carlin/core.cljc         the front half: cursor, parse, structural checks, include-splice, inheritance
src/carlin/codegen.cljc      the back half: checked tree -> (fn [model env] hiccup)
src/carlin/runtime.cljc      spec §6/§7 runtime: escaper, raw marker, merge-attrs, ->js, render-hiccup
test/carlin/harness.clj      golden runner + manifest ratchet + per-section report
test/carlin/*_test.cljc      spec-derived suites (details below)
test-resources/corpus/       morphed pugjs 3.0.2 golden corpus (see its README.md)
```

## Tasks

| Task | Meaning |
|---|---|
| `bb ratchet` | **The CI gate.** Runs the corpus; any manifest case failing = exit 1. Newly passing cases are listed for promotion. |
| `bb baseline` | Rewrites the manifest from current reality (used once per legitimate jump; never to paper over a regression). |
| `bb show <case>.carlin` | One case's expected/actual diff or error. |
| `bb spec-tests` | The spec-derived unit suites — the contract for `carlin.runtime`, `file-resolver`, and diagnostics. Written red before the implementation; **green since S29** (20 tests, 116 assertions). |

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
- **diagnostics_test** — §8.3/§12.4: asserts error **class**
  (`:carlin/error` keyword) and **position** (`:line`/`:col`), never message
  prose — wording may improve freely without test churn. Covers: unterminated
  forms (restrictive-four), extends-not-first, top-level-under-extends,
  block-via-include, include children, nested mixins, compile-time arity,
  default-not-last, static attr conflict, unresolvable refs, and `while` as a
  positioned exclusion.

## Status

`101 / 104` conformance, zero regressions ever; spec suites 20 tests / 116
assertions / 0 failures. `carlin.legacy` was retired at S29 — carlin now
compiles every template itself and **fails fast at compile time**, with
positioned errors from both halves of the pipeline (spec §8.3). Its sole
runtime dependency is edamame.

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
