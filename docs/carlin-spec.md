# carlin-spec.md — rev. 7 patch

## Edit 1 — §3.4, replace the "Escaping context" paragraph

OLD: "**Escaping context.** Inside tags in the `:raw-text-tags` set (default
`#{:script :style}`), static block text is emitted raw and `#{}` interpolates
without HTML escaping (pug-faithful: entity-escaping corrupts JS/CSS). In all
other dot blocks, normal escaping applies. …"

NEW:

**Escaping context.** Static block text is verbatim *everywhere* — it is
literal template text (§4.1). The `:raw-text-tags` set (default
`#{:script :style}`) now governs only **interpolation**: inside those tags,
`#{}` interpolates without HTML escaping (pug-faithful: entity-escaping
corrupts JS/CSS); in all other dot blocks, `#{}` escapes normally. The tag set
is configuration, not a language rule — set it to `#{}` when generating pure
XML, extend it for custom elements carrying code-like content.

## Edit 2 — §3.5, insert a bullet after the **Class** bullet

- **Collection values**: a map, vector, or seq as the value of an ordinary
  attribute renders as JSON via `->js` (§6.3), then escapes as any attribute
  value does: `{:data-user {:name "tobi"}}` →
  `data-user="{&quot;name&quot;:&quot;tobi&quot;}"`. Pipeline order matters
  and is what produces that shape: `->js` first, attribute escaper second.
  Scalars are untouched; booleans and `nil`/`false` keep their semantics
  above; `:class` and `:style` are exempt because `merge-attrs` consumes them
  under their own typed rules before serialization ever sees them.

## Edit 3 — §3.11, replace the children-are-an-error paragraph

OLD: "Children under an `include` node are a positioned error (pug allows only
filters there; carlin expresses that as `include:filter`)."

NEW:

An `include` may carry an indented **body**. The body splices into the
included template at its `yield` node — composition's parameterization, the
include-side counterpart of inheritance's named blocks. `yield` is meaningful
**only inside a file being included**; encountered anywhere else it is a
positioned `:yield-outside-include` error, so top-level `yield` remains
outside the language (the excluded `yield*` corpus family stays excluded).
The block-in-include ban (D7) is untouched: `yield` is composition's splice
point, `block` is inheritance's, and the wall between them stands. Edge
semantics — the body's default destination when the included file has no
`yield`, the treatment of multiple `yield`s, and a body attached to a raw
(`:kind :raw`) include — are **to be pinned by direct probe of pug 3.0.2**
before implementation, not asserted from memory; the probe results land in
this section when they land in code.

## Edit 4 — §4.1, replace the section body

**Escaping is a property of the dynamic boundary.** Literal template text —
inline tag text, piped `|` lines, dot-block bodies, literal markup lines — is
emitted verbatim: the author's own characters are trusted by construction,
and hand-authored entities (`&#8217;`) survive intact. Escaped dynamic
positions — `#{}` interpolation, buffered `=`, and attribute values whether
literal or computed (quoting integrity lives there) — HTML-escape per §7.1.
Raw positions — `!=`, `!{}`, filter output, `#{}` under `:raw-text-tags`, and
`(raw …)` values anywhere including attribute position — bypass escaping via
the runtime raw marker (§6.1). One marker, every context; and with literal
text verbatim, `raw` returns to marking *dynamic trust decisions* rather than
working around static-text escaping.

## Edit 5 — §4.6, amend the classes bullet's first clause

OLD: "**Classes are additive across all sources, always** — concatenated in
source order, …"

NEW: "**Classes are additive across all sources, always** — concatenated in
**textual source order**, meaning attr-map `:class` contributions interleave
at their written position among the shorthands (`a.foo{:class "bar"}.baz` →
`class="foo bar baz"`), not appended after them; collections flattened, …"
(rest of the bullet unchanged. Note the shorthand → map → `&attributes`
ordering remains the *conflict* ordering for scalars; this clause governs
class *accumulation* order only.)

## Edit 6 — §6.3, append to the `->js` paragraph

Rev. 7 gives `->js` a second, unanticipated job: it is also how collection
values render in attribute position (§3.5) — the `\u003C` guard rides along
harmlessly there.

## Edit 7 — §7.1, replace the section body

Escaped dynamic text (`#{}`, `=`): `& < > " '` → entities. Attribute values:
same, minus what the profile proves unnecessary — pinned by the paranoid test
file, not by prose. **Placement** of escaping is §4.1's: the dynamic boundary
only. The entity **set**, where escaping applies, remains deliberately
stricter than pug's four (rev. 5 ratification, scope narrowed by rev. 7 —
see that revision note).

## Edit 8 — §8.3, in the structural-error enumeration

Remove "children under include"-style wording if present; add
`:yield-outside-include`.

## Append — Revision note (rev. 7)

**Revision note (rev. 7).** Four adjudications, ratified **ahead of
implementation** — a first: the ratchet stood at 67/100 when these were ruled,
and every prior note recorded landed behavior. They are law awaiting
enforcement; the next session implements them in the order given.

- **The escaping boundary is pug's; the escaping set stays carlin's.** §4.1 is
  rewritten around the distinction: literal template text is verbatim
  (escaping static text made hand-authored entities impossible and turned
  `raw` into everyday noise); escaping guards only interpolation, buffered
  code, and attribute values. This *narrows the scope* of rev. 5's
  stricter-escaper ratification without reversing it — that ruling governs
  *how much* to escape where escaping applies (five entities, still stricter
  than pug), this one governs *where* (the dynamic boundary, pug's answer).
  The two semantic-departure goldens edited under S12 both live in attribute
  position, where escaping still applies; they remain authoritative and must
  stay green. The departure log gets a scope-narrowing annotation, not a
  reversal.
- **Collection attribute values are JSON** (§3.5, via §6.3's `->js`).
  Pug-fidelity default, but more decisively: JSON-in-`data-*` is what htmx and
  Alpine actually parse out of attributes, so pug's behavior is the useful
  one, not merely the compatible one. An EDN-emitting helper for
  Clojure-reading clients can be added later, explicitly, if ever wanted.
- **Includes accept a body, spliced at `yield`** (§3.11). This *reverses* the
  first-cut's children-are-an-error rule, with the reversal's reason recorded:
  the rule predated the splice machinery, which now exists, and users
  arriving from pug expect the feature. Adopted narrowly — `yield` legal only
  inside included files; `:yield-outside-include` otherwise; D7 untouched;
  edge semantics to be pinned by pug-3.0.2 probes before code.
- **Class accumulation order is textual source order** (§4.6). Ruled as
  "follow pug", it turned out on inspection to be the doctrine rev. 5 already
  ratified (attribute order is observable output; templates are
  source-ordered documents) applied one level down — the implementation's
  append-map-classes-after-shorthands is a bug under existing law, not a
  departure. No departure log entry.

One finding, negative and valuable: rev. 9's plan to regenerate the §3.3 text
goldens with pug 3.0.2 `pretty: false` was executed and **flipped nothing** —
the harness's canon already absorbed pretty-mode whitespace, and every
remaining mismatch is a content difference (the largest: pug's tagless
lone-dot text block, which carlin misparses as an empty div shorthand). The
plan item is retired; §10's `pretty` exclusion stands with its conformance
irrelevance now *measured* rather than assumed.