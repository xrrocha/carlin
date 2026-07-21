# Carlin — Language & Compiler Specification

**First cut.** Consolidates the conformance table, the design docket (Q1–Q12), and the
agreed compiler architecture. Every departure from pug and every carlin-specific
decision carries its rationale (§9, §10) — this document is meant to answer "why"
as well as "what".

Carlin is a Clojure-flavored dialect of [pug](https://pugjs.org): terse,
indentation-based markup compiled to functions that produce hiccup-shaped data,
serialized to HTML/XML by carlin's own renderer. **Clojure is the expression
language** — everything pug does with JavaScript, carlin does with Clojure forms,
and every remaining difference from pug should be traceable to that substitution
or to a documented decision below.

---

## 1. Design principles

These were not axioms declared up front; they crystallized from the decisions and
are stated here because they predict how future questions get answered.

1. **Pug equivalence, modulo Clojure syntax.** Full pug 3 feature parity is the
   target. Where pug embeds JS, carlin embeds Clojure; where a pug feature exists
   only to serve JS's imperative habits, it may be excluded (§10) — with the
   exclusion, not the feature, documented.
2. **Line-oriented ergonomics.** The template's visual skeleton is its indentation
   and its directive lines. Constructs that would blur that skeleton (sprawling
   directive heads) are restricted even where Clojure syntax would permit them.
3. **Markup-agnostic core.** Carlin generates *any* markup (XML included). All
   HTML-awareness — void elements, boolean-attribute style, raw-text tags,
   terse self-closing — lives in configuration and doctype-driven modes, never in
   parser or codegen rules.
4. **A compiler, blissfully unaware of context.** Carlin compiles sources to
   transparent values. Caching, file watching, reloading, HTTP integration are the
   caller's business, enabled (not implemented) by the API.
5. **Declared is authoritative.** What is literally written in a template is not
   silently overridden by dynamic data — by default (§4.6).
6. **Fail as early as knowable.** Conflicts, arity errors, and structural mistakes
   detectable at compile time are compile-time positioned errors; only genuinely
   runtime-dependent failures surface at render.
7. **Zero runtime dependencies, portable core.** `.cljc` throughout, no interop in
   the core, a four-function platform namespace as the only porting surface.

---

## 2. Compilation model

A template compiles to an ordinary Clojure function:

```clojure
(fn [model env] hiccup-data)
```

- **`model`** — the data the template renders. Free symbols in template
  expressions (those not resolvable as known globals and not bound by `each`,
  `let`, mixin params, etc.) are automatically destructured from the model:
  writing `#{(:title book)}` inside `each book in books` requires `books` in the
  model, inferred without declaration.
- **`env`** — a free-form environment map (e.g. the Ring request). Never inferred,
  always explicitly accessed: `(:remote-addr env)`.
- The function's result is hiccup-shaped data (vectors, maps, strings, and carlin's
  raw marker), rendered by `carlin.runtime/render` (§7).

**Reserved symbols.** `model` and `env` everywhere; within mixin bodies only,
`attributes` and bare `block` (§3.13). These are fixed, not configurable —
a deliberate rejection of "flexibility syndrome": four documented words cost less
than a renaming knob costs every spec sentence thereafter.

**Evaluation is eager.** Loop bodies compile to eager sequences (`mapv`-shaped),
never lazy `for`. Rationale: runtime errors surface at the causing template line
with intact stack context instead of erupting later inside the serializer;
evaluation order is deterministic, which matters when `-` code mutates atoms.
Streaming very large documents is explicitly *not* a goal; if it ever becomes one,
it will be a serializer mode, not an accident of laziness.

---

## 3. Language reference

### 3.1 Source model

A template is a sequence of physical lines. Structure is by indentation
(consistent within a template; a child is any line indented deeper than its
parent). Blank lines are insignificant between elements, significant inside
dot blocks (§3.4) and inside multi-line forms (§3.6).

A **logical line** is a physical line plus any continuation lines consumed by a
multi-line form. Multi-line forms are permitted in **exactly four positions**
(the "restrictive-four" rule): attribute maps, and the forms following `=`, `!=`,
and `-`. Everywhere else — `if`/`unless`/`case` tests, `each` collections, mixin
call arguments — the form must end on its physical line. Rationale: directive
lines are the template's visual skeleton; a sprawling `each` head blurs the very
indentation structure that gives the format its legibility, and an unbalanced
form in those positions would silently swallow subsequent template lines until
the reader happened to balance, yielding baffling errors. The idiomatic escape
hatch for a complex generating expression is a preceding code block:

```pug
- (let [items (filter interesting? (mapcat :entries feeds))])
  each item in items
    li= (:title item)
```

### 3.2 Tags

```pug
div                     → <div></div>
section#main.wide       → <section id="main" class="wide">
.card                   → <div class="card">        (default div)
custom-element          → <custom-element>          (any name; web components welcome)
br/                     → explicit self-close
a: img                  → block expansion, arbitrary depth
p This is #[em very] important.   → tag interpolation, nestable
```

**Interpolated tag names.** `#{expr}` in tag position yields a dynamic element
name — the expression may produce a keyword or a string (normalized by a small
runtime helper):

```pug
#{tag}{:foo "bar"} value
#{(if compact? "span" "div")}.item here
```

The ordinary tag surface follows (shorthand, attrs map, `&attributes`, tails).
Two consequences, both documented limitations of dynamism: shorthand on a
dynamic tag cannot be baked into the tag keyword at compile time, so it merges
at runtime via `merge-attrs` (§4.6); and raw-text dot-block treatment (§3.4) is
a compile-time decision keyed on the literal tag name, so a dynamic tag is
never raw-text — `#{expr}.` blocks always use normal escaping, whatever the
expression evaluates to.

Void elements (`br`, `img`, …) auto-close under the HTML profile; the void list
is a profile property, not a language rule (§7.2).

### 3.3 Plain text

```pug
p Inline text after a tag
| Piped text line
|                      (empty pipe: blank text line)
<hr class="sep">       (literal markup line: static text raw, #{} still escaped)
```

### 3.4 Dot text blocks

A tag line ending in `.` (after any attributes) begins a verbatim text block:
every subsequent deeper-indented line is captured raw — blank lines preserved,
relative indentation preserved by stripping the minimum indentation of the
non-blank captured lines (more robust than pug's first-line rule).

Interpolation applies over the *joined* block, so `#{…}` / `!{…}` work, and an
interpolated form may span lines within the block (there is nothing structural
to conflict with).

**Escaping context.** Static block text is verbatim *everywhere* — it is
literal template text (§4.1). The `:raw-text-tags` set (default
`#{:script :style}`) now governs only **interpolation**: inside those tags,
`#{}` interpolates without HTML escaping (pug-faithful: entity-escaping
corrupts JS/CSS); in all other dot blocks, `#{}` escapes normally. The tag set
is configuration, not a language rule — set it to `#{}` when generating pure
XML, extend it for custom elements carrying code-like content.

### 3.5 Attributes

Attributes are a **literal Clojure map**, read by the Clojure reader, values
arbitrary expressions; the map may span lines (restrictive-four):

```pug
input{:type  "text"
      :name  "q"
      :value (get-in model [:query :text])}
```

- **Booleans**: `true` renders per profile (bare in HTML, `attr="attr"` in XML);
  `false`/`nil` omit the attribute.
- **Style**: maps render as CSS declarations; string styles pass through.
- **Class**: strings, collections (flattened), and map-conditional form
  `{:class {:active active?}}` (truthy keys contribute).
- **Collection values**: a map, vector, or seq as the value of an ordinary
  attribute renders as JSON via `->js` (§6.3), then escapes as any attribute
  value does: `{:data-user {:name "tobi"}}` →
  `data-user="{&quot;name&quot;:&quot;tobi&quot;}"`. Pipeline order matters
  and is what produces that shape: `->js` first, attribute escaper second.
  Scalars are untouched; booleans and `nil`/`false` keep their semantics
  above; `:class` and `:style` are exempt because `merge-attrs` consumes them
  under their own typed rules before serialization ever sees them.
- **String keys** are legal map keys for exotic attribute names:
  `{"(click)" "handler()"}`.
- **`&attributes expr`** after the map: any expression evaluating to a map,
  merged per §4.6.
- **Unescaped values**: there is **no `key!=` syntax**. Wrap the value in the raw
  marker: `a{:href (raw pre-escaped)}`. Attribute values are arbitrary
  expressions, so the capability falls out of the runtime; `(raw …)` is itself
  the greppable danger marker. ⚠ Raw attribute values admit quote-breakout and
  `javascript:` URL injection; the burden of safety is entirely the author's.

### 3.6 Code and interpolation

```pug
- form                 unbuffered code (form evaluated, output discarded)
= expr                 buffered, escaped        (also tag= expr)
!= expr                buffered, raw            (also tag!= expr)
#{expr}  !{expr}       escaped / raw interpolation in text
#[tag …]               tag interpolation
\#{  \!{               literal, escape the interpolation marker
```

`nil` interpolates as empty in both escaped and raw positions.

**Carlin extension** (no pug equivalent): an unbuffered `-` form with children
wraps them as its body, giving lexical scope over a subtree:

```pug
- (let [total (reduce + prices)])
  p Total: #{total}
```

Forms after `=`, `!=`, `-` may span lines; all interpolation positions read one
Clojure form.

### 3.7 Conditionals and case

```pug
if (pos? balance)
  p In the black
else if (zero? balance)
  p Even
else
  p In the red

unless (:admin? user)
  p Access denied
```

`if`/`else if`/`else` chains compile to `cond`; `unless` to `if-not`.

```pug
case (:status order)
  when :pending
  when :processing
    p In flight
  when :shipped: p On its way
  default
    p Unknown status
```

Semantics: the scrutinee is bound once; each `when` compares with Clojure `=`
(values are runtime expressions — this is `condp =`-shaped, not Clojure's
literal-dispatch `case`); consecutive bodiless `when`s group with the next bodied
one as `(or (= e v1) (= e v2))` — pug's fall-through; `when x: tag` block
expansion works; `default` must be last (positioned error otherwise); **no match
and no default renders nothing** (nil), matching both pug and Clojure `when`
sensibilities.

### 3.8 Iteration

```pug
each book in books
  li= (:title book)
else
  li (empty)

for x in xs            (alias)
```

The binding position accepts **any Clojure binding form** — destructuring is the
index/key sugar:

```pug
each [i book] in (map-indexed vector books)
each [isbn book] in books-by-isbn
```

`else` renders when the collection is empty. Bodies evaluate eagerly (§2).
**There is no `while`** (§10).

### 3.9 Doctype

`doctype html` (and the named table: `transitional`, `strict`, `frameset`, `1.1`,
`basic`, `mobile`, `plist`) emit their canonical DOCTYPE lines; any other value
is emitted verbatim (custom passthrough). `doctype xml` emits the XML declaration
and selects the XML serializer profile; all others select the HTML profile
(§7.2). The compile option `:mode` overrides the doctype-derived profile;
`:mode :xml` with no doctype line is the pure-XML-generation configuration.

### 3.10 Comments

```pug
// rendered comment            → <!-- rendered comment -->
//
  rendered block comment       → children captured verbatim into the comment
//- silent, children swallowed
```

Rendered block comments capture children through the dot-block mechanism and emit
them as-is; HTML's prohibition of `--` inside comments is the author's concern —
sanitizing comment innards is beyond a compiler's station. Conditional comments
(`<!--[if IE]>`) are written as literal markup lines.

### 3.11 Includes

```pug
include ./partials/header
include /shared/nav            (root-anchored, file-resolver semantics)
include styles.css             (raw include: content spliced as literal text)
include:markdown intro.md      (filtered include, §3.12)
```

Includes are **compile-time tree splices**: the included template's root children
replace the include node before codegen. Consequences: free-symbol analysis sees
included expressions (their model keys are inferred); included content shares the
enclosing lexical scope (`each` bindings visible inside — pug-faithful); mixins
defined in an included file are hoisted with everything else, so **a file of
mixin definitions included at the top is a mixin library**, by construction.

Whether a resolved source is parsed or spliced raw is the **resolver's** decision
(`:kind`, §5.2) — carlin itself has no filename awareness.

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

`block` nodes inside included files are a **positioned compile error** (§9, Q9):
extends is inheritance, include is composition, and the two don't leak into each
other.

### 3.12 Filters

```pug
:markdown
  # A heading
  Some *emphasis*.

:less {:compress true}
  .a { .b { color: red } }
```

Filters are **compile-time text transformations**: the body is captured verbatim
(dot-block mechanism), the registered function `(fn [text attrs] → html-string)`
is applied during compilation, and the result becomes a raw text node. No
interpolation inside filter bodies (pug-faithful — filters run before the model
exists). The registry is the `:filters` compile option; carlin ships only
dependency-free built-ins (`:verbatim`, `:cdata`) — markdown, scss, etc. are
user-supplied functions, which makes the extension point explicit rather than an
npm-style package convention.

### 3.13 Mixins

```pug
mixin card [title & body-items]
  .card&attributes attributes
    h3= title
    each item in body-items
      p= item
    footer
      block

+(card "Greetings" (:items model)).wide{:data-x 1}
  em This indented content is the block.

+(item "contact") Contact
+list
```

**Definition**: `mixin name [binding-vector]`, **top-level only** (positioned
error elsewhere). The binding vector is full Clojure: `& rest`, destructuring,
`:or` defaults — pug's default/rest arguments are subsumed. All definitions are
hoisted into a `letfn` wrapping the template body: mutual recursion and
use-before-definition work without ordering rules.

**Call anatomy**: `+` followed by exactly **one Clojure form** — a bare symbol
for a zero-argument call (`+list`), a list for a call with arguments
(`+(comment "This" "that")`) — then optionally the same shorthand/attrs surface
a tag gets (`.wide#hero{:data-x 1}`, `&attributes expr`), compiled into the
hidden `attributes` argument, then the standard tag tails: inline text,
`= expr` / `!= expr` buffered content, `: tag` block expansion, or a dot
block. Indented children become the block.

The one-form rule exists because the corpus proved the alternative ambiguous:
with bare args-to-end-of-line, `+item "contact" Contact` cannot distinguish a
third argument from inline text. Pug's parentheses delimited arguments from
text; carlin restores that delimitation with the instrument it already uses
everywhere — the reader. The form must end on its physical line (the
restrictive-four principle, §3.1, now a corollary rather than a separate rule).

**Inside a mixin body**: `attributes` is **always bound** (to `{}` when the
caller passed none — bodies compose unconditionally); bare `block` splices the
caller's block content (nil-safe when absent). Bare `block` = mixin block;
`block name` = inheritance block — pug's own disambiguation.

**Compile-time arity checking**: all definitions are known to the compiler, so a
call with an incompatible argument count is a positioned compile error, not a
runtime `ArityException` pointing into generated code. *Incompatible* means: not
exactly n for a fixed binding vector, fewer than n before `&` for a variadic one.
Pug's absent-argument-is-`undefined` is deliberately **not** imported: a
compile-time guarantee that admits silent nils is no guarantee at all (rev. 3 —
adjudicated against a corpus morph artifact; see the corpus departure log).

Implementation note (looks like a bug, isn't): mixin parameter names are
collected by free-symbol analysis and spuriously destructured from the model as
nils — then shadowed by the `letfn` parameters, exactly as `each` bindings
shadow theirs. Harmless by construction; commented in the code.

### 3.14 Template inheritance

```pug
//- layouts/base.carlin
doctype html
html
  head
    block head
      title Default title
  body
    block content

//- page.carlin
extends /layouts/base
block head
  title My page
block append content
  footer …
```

`extends` must be the first meaningful node. The extending file's top level may
contain only `block`, `mixin`, and `include` nodes (positioned error otherwise).
`block name` in a layout renders its default children unless overridden;
`block append name` / `block prepend name` concatenate. Multi-level chains fold
from the base layout upward. Inheritance resolves as the **first** compiler pass;
everything downstream is unaware it happened.

---

## 4. Semantics

### 4.1 Escaping model

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

### 4.2 Interpolation reading

Every interpolation/expression position reads exactly one Clojure form via
edamame; positions (`:row`/`:col`) ride on the form's metadata.

### 4.3 Scope

Templates close over nothing; all data arrives via `model`/`env`. Lexical scope
inside a template: `each` bindings, `- (let …)` bodies, mixin parameters —
ordinary Clojure scoping in the generated code.

### 4.4 Free-symbol inference

After codegen, free simple symbols not resolvable via the platform's
`known-symbol?` and not locally bound become the model destructuring
`{:keys [...]}`. Over-collection (mixin params, loop bindings) is neutralized by
shadowing; the destructured nils are unreachable.

### 4.5 Eagerness

All child sequences (loops, conditionals' branches, mixin blocks) are realized
eagerly, in source order (§2).

### 4.6 Attribute merging — `merge-attrs`

Sources, in order: tag shorthand (`#id`, `.class`), the attrs map, `&attributes`.

- **Classes are additive across all sources, always** — concatenated in
  **textual source order**, meaning attr-map `:class` contributions interleave
  at their written position among the shorthands (`a.foo{:class "bar"}.baz` →
  `class="foo bar baz"`), not appended after them; collections flattened,
  map-conditional truthy keys included, duplicates preserved (browsers don't
  care; dedup can mask intent). Classes are outside the conflict policy:
  nothing declared is ever lost. (The shorthand → attrs map → `&attributes`
  ordering remains the *conflict* ordering for scalars; this clause governs
  class *accumulation* order only.)
- **Scalar attributes** (including `:id`; style-map keys treated per-key) are
  governed by one uniform policy:

  `:on-attr-conflict` ∈ `#{:error :ignore :last-wins}` — default **`:error`**.

  | Policy | Static conflict (shorthand vs map literal) | Runtime conflict (`&attributes`) |
  |---|---|---|
  | `:error` | positioned **compile-time** error | throws positioned ex-info at render |
  | `:ignore` | declared wins, silently | declared wins, incoming dropped |
  | `:last-wins` | later source wins | later source wins |

  The regimes differ only in *diagnostic timing* — attrs-map keys are literals,
  so shorthand-vs-map conflicts are always statically knowable and reported at
  compile time; `&attributes` keys are knowable only at render. Under
  `:last-wins`, `a#foo{:id "bar"}` is a legible deliberate override, so the
  policy governs static conflicts too.

  Default `:error` rationale: it enforces declared-is-authoritative in its
  strictest form, catches the common authoring mistake for free at compile time,
  and forces teams passing dynamic attr maps to *choose* a policy deliberately —
  one loud crash in week one beats silent drops forever.

---

## 5. Public API

### 5.1 Entry points

```clojure
(carlin.core/compile-template source opts)   ; source string → compiled map
(carlin.core/compile-ref ref opts)           ; pull root through the resolver
(carlin.core/deftemplate name ref-or-source opts)  ; macro: compile at compile time
```

`deftemplate` is the cross-platform workhorse: templates compile through the
ordinary Clojure/ClojureScript compiler at macroexpansion time — full speed, no
interpreter, the primary CLJS mode and the natural JVM mode for static templates.

### 5.2 The compiled artifact — a transparent value

```clojure
{:fn       (fn [model env] hiccup)   ; ordinary function: memoize it, pass it around
 :code     '(fn [{:keys [...]} env] ...)  ; the full form, as data
 :doctype  "html" | nil
 :mode     :html | :xml
 :symbols  #{books user ...}         ; inferred model keys
 :deps     #{key ...}}               ; every resolver key touched (root, includes, extends)
```

Nothing is hidden (the anti-pug4j clause): `:code`-as-data enables spitting
generated namespaces for AOT — mechanically the same thing `deftemplate` does —
and `:deps` enables caller-side watch-and-recompile caches without carlin knowing
caches exist.

### 5.3 The resolver contract

```clojure
(fn [from ref] → {:key k, :source s, :kind :template | :raw} | nil)
```

- `ref` — the string after `include`/`extends`.
- `from` — the `:key` of the referencing template (nil for the root).
- `:key` — **opaque to carlin** (path, URL, keyword into a map…). Used only for
  cycle detection, error provenance (`:pos` carries it), and passed back as
  `from` — relativity semantics belong entirely to the resolver.
- `:kind` — the resolver decides parsed-vs-raw. Carlin has no filename awareness.
- Synchronous by contract (keeps browser-side include sane). `nil` = not found
  (positioned "cannot resolve" error at the include/extends line).

### 5.4 Shipped battery: `file-resolver`

`(file-resolver root-dir)` with pug-compatible semantics: relative refs anchor to
the including file's directory; leading-`/` refs anchor to `root-dir` (the layout
idiom: `extends /layouts/base`); refs with an extension are tried as-given
(non-`.carlin` → `:kind :raw`); extensionless refs try as-given, then with
`.carlin` appended. **Root-jail**: the canonicalized path must remain inside
`root-dir`; escapes (`../..`) are a positioned compile error, never a file read.
An in-memory map resolver is a three-liner shown in the docs.

### 5.5 Options

| Option | Default | Purpose |
|---|---|---|
| `:name` | `nil` | diagnostic identity for string-compiled templates |
| `:resolver` | none | §5.3; required if the template includes/extends |
| `:filters` | built-ins | filter registry, name → fn |
| `:mode` | doctype-derived | serializer profile override (`:html`/`:xml`) |
| `:raw-text-tags` | `#{:script :style}` | dot-block raw context (§3.4) |
| `:on-attr-conflict` | `:error` | §4.6 |
| `:eval` | platform default | evaluation strategy (§8.2) |

---

## 6. The runtime namespace — `carlin.runtime`

Small, cljc, dependency-free. Generated code targets this namespace only.

### 6.1 `raw`

The raw-string marker: wraps a string so the serializer emits it without
escaping, honored in element and attribute positions alike. The single mechanism
behind `!=`, `!{}`, filter output, raw includes, and unescaped attribute values.

### 6.2 `merge-attrs`

Implements §4.6; takes the conflict policy as an argument (the compiler applies
the same policy statically, earlier).

### 6.3 `->js`

Dependency-free emission of Clojure data as JS/JSON source, for crossing from
Clojure data into script contexts:

```pug
script.
  window.APP_STATE = !{(->js state)};
button{:hx-vals (->js {:id id})} Remove
```

Domain, ruthlessly narrowed: maps with keyword/string keys, vectors and seqs,
strings, numbers, booleans, nil → `null`; anything else is a positioned runtime
error, never a guess. **Script-context safety is the point**: emitted strings
escape `<` (as `\u003C`) and friends, closing the `</script>`-breakout injection
that generic JSON emitters leave open by default. `->js` is the script-context
sibling of the HTML escaper — the other half of the safety story that Q5's raw
script blocks opened. Users with richer serialization needs bring their own
library through `env`. Status: a parachute — rarely needed; when needed, oh boy.
An unanticipated consequence of replacing JS as the expression language.

Rev. 7 gives `->js` a second, unanticipated job: it is also how collection
values render in attribute position (§3.5) — the `\u003C` guard rides along
harmlessly there.

### 6.4 `render` and the serializer

Carlin owns its serializer (§9, Q1): escaping, raw handling, attribute rendering,
doctype emission, profiles. Hiccup's *data notation* is retained wholesale — only
the printer is replaced, and the printer is a commodity; hiccup2 remains a JVM
**differential-test oracle** (§11).

---

## 7. Serializer profiles

### 7.1 Escaping

Escaped dynamic text (`#{}`, `=`): `& < > " '` → entities. Attribute values:
same, minus what the profile proves unnecessary — pinned by the paranoid test
file, not by prose. **Placement** of escaping is §4.1's: the dynamic boundary
only. The entity **set**, where escaping applies, remains deliberately
stricter than pug's four (rev. 5 ratification, scope narrowed by rev. 7 —
see that revision note).

### 7.2 Profiles

| Property | `:html` (default) | `:xml` |
|---|---|---|
| Void elements | HTML5 list, no closing tag | none |
| Boolean attrs | bare (`checked`) | `checked="checked"` |
| Self-closing | void elements only | `<foo/>` for empty elements |
| Raw-text tags | per `:raw-text-tags` | per `:raw-text-tags` (set `#{}` for pure XML) |

Selected by doctype (`xml` → `:xml`, all else → `:html`), overridden by `:mode`.
The profile is where all HTML knowledge lives — the parser and codegen have none.

---

## 8. Compiler architecture

### 8.1 Pipeline

```
source ──cursor──▶ parse (filters applied during capture)
       ──▶ resolve-template           (mutually recursive, per template — rev. 4)
             ├── include-splice       (recursive, cycle-detected, :deps collected)
             └── inheritance-merge    (extends/block/append/prepend)
       ──▶ dissolve-blocks            (named blocks → their merged children)
       ──▶ clause-attachment          (else / else-if chains / when-grouping)
       ──▶ mixin-hoist                (collect defs → letfn; arity table)
       ──▶ codegen                    (hiccup-building forms; ^{:line} metadata)
       ──▶ free-symbol destructure    (known-symbol? per platform)
       ──▶ evaluate                   (per :eval strategy)
```

Every pass is tree → tree except the ends. Every node carries
`:pos {:key :line :col}` from the cursor onward.

### 8.2 The platform namespace — the entire porting surface

| Function | JVM / bb | CLJS |
|---|---|---|
| `read-form-at` | edamame | edamame |
| `evaluate` | `eval` (or sci) | `deftemplate` macro path, or sci for runtime compilation |
| `resolve-source` | caller's resolver | resolver at macro time; preloaded map at runtime |
| `known-symbol?` | `resolve` / sci ctx | `cljs.analyzer.api` in macro mode |

edamame (pure cljc; the reader under sci and babashka) replaces
`PushbackReader`: one-form reads with `:row`/`:col` metadata — positions for
free, and it is what makes multi-line forms, dot-block interpolation spans, and
expression-level error positions one mechanism. Porting carlin to Glojure,
ClojureRust, or the next implementation means providing these four functions —
possibly fewer, since edamame itself is pure Clojure.

Evaluation strategies: plain `eval` (JVM, bb); the `deftemplate` macro
(everywhere, compile-time); sci (opt-in, uniform across platforms, enables
runtime template compilation in a browser at interpretation speed, requires a
configured context exposing user functions).

### 8.3 Diagnostics

**Compile time**: every error is `ex-info` with `{:key :line :col}` plus a
formatted excerpt — the offending line with a caret. Structural errors (extends-not-first, block-via-include,
nested mixin, arity mismatch, static attr conflict under `:error`, unterminated
form in a restrictive-four position, root-jail escape, unresolvable ref, include
cycle, `yield` outside an included file, dangling else clause) all report this
way, as do reader errors, rebased to template coordinates.

**Runtime** (JVM path): emitted list forms carry `^{:line n}` metadata and the
template's key binds `*file*` during eval — the Clojure compiler honors both, so
a runtime exception inside `#{(:title book)}` produces a stack frame pointing at
the template line. Eagerness (§2) is what keeps those frames adjacent to the
cause. Under bb/sci, sci's own positional bookkeeping applies; under the
`deftemplate` CLJS path, positions map through the ordinary compiler.

---

## 9. Departures from pug — with rationale

| # | Pug | Carlin | Rationale |
|---|---|---|---|
| D1 | JS expressions | Clojure forms everywhere | The founding substitution; everything in §10 and half of this table follows from it |
| D2 | `attrs(a=1, b=2)` parens syntax | literal Clojure map | The map *is* the natural Clojure surface; buys string keys, arbitrary expressions, reader-checked syntax for free |
| D3 | Multi-line permitted loosely | restrictive-four | Directive lines are the template's skeleton; unbalanced forms elsewhere would swallow template lines and yield baffling errors; complex expressions belong in a preceding `- (let …)` |
| D4 | Class/id duplicate handling (crusty corners; sometimes errors, sometimes merges) | uniform `:on-attr-conflict`, classes always additive | One documented rule instead of replicated accidents; declared-is-authoritative by default; `:last-wins` available for pug-ish permissiveness |
| D5 | `key!= expr` unescaped-attr syntax | `(raw expr)` as value | No new grammar: attr values are arbitrary expressions, so the runtime marker subsumes the feature; `(raw …)` is the greppable danger marker |
| D6 | `script.`/`style.` raw behavior hardcoded | `:raw-text-tags` option, same default | Markup-agnostic core: HTML-awareness is configuration; pure-XML generation sets `#{}`; custom code-carrying elements extend it |
| D7 | Blocks inside includes: loosely specified, folklore behavior | positioned compile error | Extends = inheritance, include = composition; the error teaches the model; relaxing an error later is compatible, the reverse isn't |
| D8 | Include extension sniffing by the engine | resolver returns `:kind` | Filesystem-orientation doesn't belong in the compiler; resolvers own identity, relativity, and parsed-vs-raw; `file-resolver` reproduces pug's UX privately |
| D9 | Filters as an npm package convention | explicit `:filters` fn registry | Zero deps; the extension point is a visible option, not an ambient ecosystem |
| D10 | Mixin params in parens; defaults/rest as JS syntax | binding vector | Clojure binding vectors subsume defaults, rest, and destructuring; consistent with `each` |
| D11 | Mixin arity errors at runtime | compile-time arity check | All definitions are known to the compiler (top-level, hoisted); fail-as-early-as-knowable |
| D12 | `each val, i in xs` index sugar | `each [i v] in (map-indexed vector xs)` | Binding forms are the sugar; no grammar spent on what destructuring already says |
| D13 | Lazy-ish evaluation order (JS semantics) | eager, deterministic | Error locality; deterministic order for `-` code with atoms |
| D14 | pug4j: file+cache coupled engine, opaque internal form | context-blind compiler, transparent artifact | Direct answer to friction experienced with pug4j when synthesizing templates on the fly; `:deps` gives callers better caching than a built-in could |
| D15 | Mixin call `+name(args) text` | `+(name args) text` — the call is one Clojure form | Pug's parens delimited args from inline text; without them, `+item "contact" Contact` is ambiguous (corpus-discovered). One reader-delimited form restores the delimitation, simplifies the anatomy, and re-enables inline text and `:` expansion on calls |

## 10. Deliberate exclusions — with rationale

| Excluded | Rationale |
|---|---|
| `while` | Exists in pug because JS mutates its way through loops; carlin replaced that world. Its absence isn't a gap — it's the point. (The workaround, atoms mutated in `-` code driving `loop`, is deliberately not blessed with syntax.) |
| `pretty` output | Deprecated in pug itself; a pretty-printer is a can of whitespace-significance worms; out of scope |
| Attribute string interpolation `"/#{x}"` | Deprecated in pug; `(str …)` is the idiom |
| JS statement blocks, `var` | `-` code with `let`/atoms; D1 |
| Configurable reserved symbols | Four fixed contextual words (`model`, `env`, `attributes`, `block`) beat a renaming knob — flexibility syndrome |
| Streaming serialization | Not a goal; templated data fits in memory; noted as a possible future serializer mode |
| Interpolated mixin names (`+#{expr}(...)`) | Dynamic dispatch by computed name defeats compile-time arity checking and has no natural rendition under letfn hoisting; a map of functions in the model is the idiom if ever needed |

## 11. Carlin-specific additions — with rationale

| Addition | Rationale |
|---|---|
| `env` second parameter | Templates need request/context data that isn't the model (e.g. Ring request); explicit access, never inferred |
| Free-symbol model inference | The terseness dividend of Clojure-as-expression-language: `#{(:title book)}` declares its own model dependency |
| `- form` with children as body | Lexical scoping over a subtree; the blessed home for complexity displaced by restrictive-four |
| `(raw …)` in attribute position | Feature *gained* by the expression-language swap (see D5) |
| `->js` | Compensates a capability lost with JS (`JSON.stringify`); script-context injection safety baked in; the parachute |
| Transparent compiled artifact + `:deps` | §5.2; compiler-not-framework |
| `deftemplate` macro | Cross-platform compile-time path; CLJS's primary mode; free AOT story |
| Compile-time mixin arity check | See D11 |
| Interpolated tag names as ordinary tags | Pug parity (Q13); hiccup's data model makes dynamic element names nearly free — `[(keyword expr) …]`; useful for data-driven custom elements |
| Root-jail in `file-resolver` | Path traversal from template refs is an attack, not a feature; pug is lax here, carlin is not |

## 12. Conformance and test plan

The suite mirrors the conformance table (`carlin-conformance.md`): every
✓/◐/○ row has at least one template; every ⚑ row (all now resolved) has tests
pinning the decided behavior. Structure:

1. **Golden files** — template + model → expected output string, grouped by spec
   section. Both profiles where behavior diverges.
2. **The paranoid escaper file** — the serializer's security surface: text
   entities, attribute-value contexts, raw-marker boundaries in element and
   attribute position, `->js` string escaping (`</script>`, `<!--`), quote
   handling for raw attribute values.
3. **Differential oracle (JVM)** — render the golden corpus through both carlin's
   serializer and hiccup2; diff. Hiccup goes from dependency to free reference
   implementation. Documented-divergence list excepted (boolean attrs style,
   raw-in-attrs).
4. **Diagnostics corpus** — one deliberately broken template per positioned
   error class in §8.3, asserting `{:key :line :col}` and message shape. Error
   quality is a feature; test it like one.
5. **Cross-platform matrix** — the golden corpus under: JVM `eval`, JVM
   `deftemplate`, bb, CLJS `deftemplate`, sci. Output must be identical;
   diagnostics may differ per §8.3's platform notes.
6. **Resolver tests** — file-resolver anchoring, extension defaulting, root-jail
   escapes, `:kind` routing, cycle detection, `:deps` completeness; the
   three-line in-memory resolver doubles as the test harness for include/extends.

## 13. Deferred / future work

- Streaming serializer mode (noted, not planned).
- Additional shipped filters beyond `:verbatim`/`:cdata` (user-supplied fns cover
  the rest today).
- The m4-style directive expansion idea (pre-configured custom elements
  recursively expanded) — explicitly outside the language, as is the htmx
  `get`/`post`/`put`/`delete` post-processing step.
- Vendoring vs depending on edamame (the sole core dependency question left).

---

*Companion documents: `carlin-conformance.md` (feature table, aligned with this
revision: all ⚑ resolved, `while` under exclusions, Q13/Q14 rows added),
`carlin-corpus.zip` (morphed pugjs 3.0.2 golden-file suite),
`carlin-handoff.md` (session history; superseded by this spec).*

**Revision note.** Amended after the corpus morph surfaced two gaps: §3.2 gains
interpolated tag names (Q13); §3.13's call anatomy is rewritten around the
one-form call (Q14), superseding the first-cut's args-to-end-of-line rule.

**Revision note (rev. 3).** Implementation-phase adjudications, all
corpus-arbitrated (see the corpus README departure log). §3.13's arity
paragraph now states *exact-unless-`&`* explicitly, reaffirmed against
morph-artifact goldens that leaned on pug's absent-is-undefined; the
top-level-only rule for mixin definitions (Q10) is reaffirmed against goldens
whose defs had been mechanically nested under `- (let …)` bodies — the corpus
was restructured, not the language. §8.3's error-class enumeration gains
include cycle, dangling clause, and reader errors. §3.4's minimum-indent
dedent stands as written (conforming the implementation to it promoted a
golden). §10's exclusion of `pretty` output likewise stands; whether a
serializer *profile* (§7) someday offers cosmetic indentation is left open,
but conformance never tests against pug's deprecated pretty mode.

**Revision note (rev. 4).** Inheritance-merge landed; the pass order in §8.1 is
amended to record what the corpus forced. Inheritance and include are **not
sequential passes** but one mutually recursive resolution per template: an
included file may itself `extends` (`include-extends-*`), and a layout may
`include` (`layout.include.carlin`), so each chain member splices its includes
*before* its blocks are collected — which is also what makes a mixin library
included at the top of an extending file hoistable in the merged tree.

The composition/inheritance wall (Q9/D7) survives contact intact, by a
distinction worth naming: an included file that extends resolves its own
inheritance **privately** and its named blocks dissolve at the splice point, so
nothing enters the includer's block namespace and `:block-in-include` keeps its
teeth for the file that merely *declares* blocks. Inheritance is thus private to
the file that declares `extends` — a stronger statement than "blocks don't leak",
and the one that lets three `include-extends-*` goldens pass with the error
still green in the diagnostics suite.

Two mechanical rules are pinned by goldens: overrides apply in **source order**
within a level (`layout.multi.append.prepend.block` — the last `append` ends up
outermost-last, the last `prepend` outermost-first), and each derivation level
re-walks the whole merged tree, which is what folds multi-level chains from the
base upward (§3.14) without a separate chain-flattening step. Overrides naming
no target vanish silently, pug-faithful. Extends of a `:raw` target is the new
`:extends-raw`; an extends cycle reuses `:include-cycle` carrying `{:via
:extends}` rather than growing the §8.3 enumeration (pending ratification).

**Codegen hygiene (rev. 4).** Emitted code now syntax-quote-qualifies every
hand-consed operator (`list`, `not`, `=`, `or`, `cond`). Unqualified, a user
mixin named `list` captured the splice operator across the whole `letfn` scope —
generated code must be hermetic against the user's own namespace, and the
template author's names are user data. Special forms (`if`) need no qualification
and take none. Relatedly, duplicate mixin definitions now dedupe with the last
winning (pug-faithful redefinition); sci's `letfn` rejects duplicate binding
names outright, which is how the corpus found it.

**Revision note (rev. 5).** Step 3 landed: carlin owns its serializer, and
`carlin.runtime` is real (escaper, raw marker, `merge-attrs`, `->js`,
`render-hiccup`, doctype). hiccup2 reverts to differential-test oracle (§11),
which is what §6.4 always intended. Three adjudications, all golden-arbitrated:

**§7.2 gains `:terse?` — the void/self-closing three-way.** The profile table's
two values cannot express what pug does and the corpus therefore requires:
`doctype html` → terse HTML5 (`<br>`, bare boolean attributes); *no doctype at
all* → `<br/>`, pug's XHTML-ish default (`mixin.blocks`, `source`,
`text-block`); an explicit source-level `/` → `<br/>` regardless of profile
(`self-closing-html`, which carries `doctype html` and still self-closes).
So `:mode` selects the profile and `:terse?` selects HTML5 terseness, set by
the *presence* of an html doctype rather than by the mode. The explicit
self-close rides from the parser's `:self-close?` on a namespaced attribute key
that the serializer consumes and never emits.

**Attribute order is part of the output, not an implementation detail.**
Templates are source-ordered documents and every golden is written that way;
the interim printer sorted alphabetically, which was invisible until enough of
the language worked to notice. The serializer emits source order, which
Clojure's array-maps give us for free. §4.6's source order (shorthand → attrs
map → `&attributes`) is now observable in the output, and `mixin.merge` pins
it: `class="bar baz hello"`. Above 8 attributes the reader promotes to a
hash-map and order is lost — recorded here rather than papered over.

**Escaping is carlin's, and it is stricter than pug's.** §7.1's five entities
apply in attribute position too, where pug escapes only `"` and `&`. The one
golden that notices (`attrs.carlin`, `bar="<baz>"`) stays red pending
ratification rather than being quietly conformed: an escaper is exactly the
place where matching pug is the wrong instinct.

**Ratifications (rev. 5, Ricardo).** The rev. 5 adjudications above are
confirmed as spec: §7.2's `:terse?` three-way and attribute order as observable
output both stand. Two further rulings:

- **The escaper's strictness is deliberate and permanent** (S12). §7.1 applies
  its five entities in attribute position too. Two goldens were edited to match
  rather than the escaper relaxed to match pug (corpus README departure log).
  The principle: where carlin and pug differ on *safety*, carlin improves on
  the status quo and documents the divergence — pug-fidelity is a default, not
  a constraint.
- **Extends cycles keep `:include-cycle`** (S10, ratified), carrying
  `{:via :extends}` in the ex-data rather than growing §8.3's closed error
  enumeration with a near-duplicate class. One cycle concept, one error class,
  the referencing mechanism as data.
- **`inheritance.extend.include` is a permanent conformance departure** (S8,
  ratified): D7 stands, blocks do not leak through include, and the corpus case
  that expects otherwise will never pass. Recorded in §10's departures rather
  than left as an unexplained red case.

**Revision note (rev. 6).** Filters (§3.12) implemented, plus one grammar
addition the corpus forced.

**§3.12 as built.** Filters are compile-time text transformations, applied
during codegen; the result becomes a raw text node. Three refinements the
first cut left implicit:

- **Chains.** `:cdata:uglify-js` is two filters, applied **right to left**, so
  the leftmost name is the outermost wrapper. (`filters.nested` is only
  coherent under this reading.)
- **Inline bodies.** `#[:cdata inside]` takes the rest of the line as its
  body; the captured-block form is the alternative, not the only form.
- **Unknown filters are a positioned `:unknown-filter` error**, never a
  silent passthrough. A filter that isn't registered means the author expected
  a transformation that will not happen — failing loudly is the only safe
  reading, and it is what makes the `:filters` registry an honest contract
  rather than a suggestion. Carlin ships `:verbatim` and `:cdata` only.

**§3.2 gains namespaced tag names.** `fb:users` is one tag, not a tag with a
block expansion — XML namespaces are ordinary in HTML-adjacent markup and the
corpus uses them. The disambiguator is the space: a colon followed immediately
by a word character continues the tag name; a colon followed by anything else
opens a `: ` expansion. This costs nothing — `a: img` is unaffected — and it
fixed `namespaces.carlin` as a side effect of fixing `filters-empty.carlin`.

**Ratifications (rev. 6, Ricardo).** Rev. 6's additions stand: filter chains
right-to-left, inline filter bodies, `:unknown-filter` as a positioned error,
and namespaced tag names (§3.2). Two conformance-instrument rulings:

- **The battery supplies what the corpus was authored against, through public
  options** (S13): a `custom` filter via `:filters`, as it already supplies a
  model via the harness's `case-model`. The discipline in both cases is
  minimality — the battery must never become the place where §3.12's
  dependency-free position quietly erodes.
- **Cases requiring filters carlin does not ship are excluded, not endured**
  (S14): eight moved to `_excluded/`, joining `while` and the `yield*` family.
  §12.1's conformance denominator is now **100**, and it means something —
  a conformance number that counts cases unreachable *by design* is a worse
  instrument than a smaller honest one. Each excluded case remains raw
  material: it becomes a positive test the moment a caller supplies the
  filter, which is precisely what `filters.custom` demonstrates.

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
