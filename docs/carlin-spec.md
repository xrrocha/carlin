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

A `.` **alone** on its line opens a *tagless* dot block: the captured text
splices at that position with no element wrapping it. `.foo` — a word
character after the dot — remains the class shorthand and is never a text
block; the disambiguator is that the dot stands alone.

A tag line ending in `.` (after any attributes) begins a verbatim text block:
every subsequent deeper-indented line is captured raw — blank lines preserved,
including *trailing* ones (each renders as a newline; pug-faithful, probed —
they are dropped only at EOF, where they are the file's own trailing
whitespace and not a dedent boundary),
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
  merged per §4.6. A **bare symbol is a name token** (word characters), not a
  reader form — so a trailing `=`/`!=` is a buffered-content sigil, exactly as
  in a bare mixin call (§3.13): `div&attributes attributes= x` forwards
  `attributes` and buffers `x`. Delimited forms (`(…)`, `{…}`, `[…]`) are read
  by the reader, which is authoritative for its own delimiters.
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
outside the language (the `yield*-head` corpus files pin this error as
roots; the three includer-side cases were readmitted to the corpus under
the landed law — S19, rev. 12).
The block-in-include ban (D7) is untouched: `yield` is composition's splice
point, `block` is inheritance's, and the wall between them stands.

Edge semantics (ratified rev. 10; probed against pug 3.0.2 and **landed**,
rev. 11, 2026-07-22):

- **Multiple `yield`s: the body splices at EVERY yield found.** Ratified as
  carlin law ahead of the probe — and the probe then showed pug 3.0.2 does
  exactly this, so the law is pug-faithful after all (no departure). The
  body's AST is replicated at each splice point, so its code evaluates once
  *per site* at render time — side-effecting bodies (an atom-bumping
  `- (let …)`) will run per splice. That is the author's rope: replication
  is the obvious meaning of "splice here" written twice, and the language
  does not police what the author parameterizes with.
- **A body with no destination is a positioned error.** A body on a **raw
  (`:kind :raw`) include** or on an **`include:filter`** is
  `:body-in-raw-include` — pug agrees ("Raw inclusion cannot contain a
  block"), adopted verbatim. A body on a template include whose *fully
  resolved* target contains **no `yield`** is `:body-without-yield` — a
  **logged departure** (S17): pug does not discard here but buries the body
  at the *deepest last block* of the included file, a landing site that is
  an accident of the included file's shape (pug-linker's own source marks
  the behavior with a deprecation todo). Camming content at an
  unpredictable, context-dependent location is inadmissible.
- **An unfed `yield` renders nothing** (pug-probed, adopted): including a
  yield-bearing file without a body simply omits the splice point. Unfed
  yields survive splicing untouched, so an *enclosing* include's body can
  still land on them — pug's cascade, adopted: yield bubbles until fed.
- **`yield` anatomy.** The directive fires only **bare** on its line, like
  pug's lexer: `yield trailing text` is an ordinary tag named `yield`
  (markup-agnosticism, §1 — the word is claimed only where the splice point
  is meant). Children under a bare `yield` are `:yield-children` (pug
  agrees: syntax error). A `tag: yield` expansion takes the body as the
  tag's children.
- **Yield legality follows the include EDGE, not the composed tree.**
  `yield` is legal only in the source of a file reached via `include`
  (transitively). It is `:yield-outside-include` in the root template —
  including inside an include *body* written there — and in a file reached
  via `extends`, even when the extending file was itself included: extends
  is inheritance, include is composition, and `yield` belongs to
  composition alone (D7's wall, seen from the other side).

An `include:filter` may carry an **attrs map** between the filter name and
the ref — `include:custom{:opt "val"} some/file` — the same surface a
standalone filter gets (§3.12), read by the reader (it may span lines like
any attr map; the ref follows on the map's closing line). The map is passed
to the filter fn as its `attrs` argument. It is sought only when a filter
name is present: a bare include's ref is never sniffed for braces.

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
(carlin.api/compile-template source opts)   ; source string → compiled map
(carlin.api/compile-ref ref opts)           ; pull root through the resolver
(carlin.api/deftemplate name ref-or-source opts)  ; macro: compile at compile time
```

`deftemplate` is the cross-platform workhorse: templates compile through the
ordinary Clojure/ClojureScript compiler at macroexpansion time — full speed, no
interpreter, the primary CLJS mode and the natural JVM mode for static templates.

Pass `:source? true` to treat the second argument as literal source rather than
a resolver ref. `opts` is evaluated at macroexpansion — it must be, since the
resolver has to run to read the template — so it must be a compile-time constant.

**The namespace is `carlin.api` (rev. 17, S33).** Every revision through 16 said
`carlin.core` here, and the code has never agreed: `compile-template` and
`compile-ref` have lived in `carlin.api` since the battery was built, and S29
kept that namespace deliberately as the front door §5 names. The spec was
simply stale, so `deftemplate` joins its two siblings rather than being the one
entry point in a different namespace from the other two.

**Where template expressions resolve under `deftemplate` (rev. 17, S31).**
`:code` contains unqualified symbols the *author* wrote — `count`, `str`,
`raw` — because carlin passes user names through as user data (§8.2, rev. 4
hygiene). Under `evaluate` they resolve because `*ns*` is bound to the template
namespace. A macro has no such binding: it expands in the *caller's* namespace,
where `count` means whatever that namespace decided it means. A caller who
excludes and redefines `count` would silently render `[:p :MY-COUNT]` where the
template says `[:p 3]` — wrong output, not an error.

The vocabulary therefore travels with the artifact, as data. `:vocabulary` maps
each borrowed ambient name to what it resolves to in the template namespace, and
`deftemplate` binds exactly those around the emitted code:

```clojure
(let [count clojure.core/count, raw carlin.runtime/raw]
  (fn [{:keys [...]} env] ...))
```

Same vocabulary as `evaluate` supplies through `*ns*`, established by ordinary
lexical binding instead — which is expected to work identically on
ClojureScript, where namespaces are a compile-time construct and `ns-resolve`
does not exist.

**That CLJS claim is designed, not demonstrated (rev. 17).** It has never run
under a ClojureScript compiler. One consequence in particular is unverified and
is the likeliest thing to be wrong: `platform/qualify` resolves against
`template-ns`, a **JVM** namespace, so it answers `clojure.core/count` — while a
CLJS target wants `cljs.core/count`. `deftemplate` macroexpands on the JVM
inside the CLJS compiler's process, so nothing would fail loudly; it would emit
a binding to the wrong namespace. If that proves out, `qualify` needs the
compilation target as an input and `:vocabulary` becomes target-dependent. Until
the matrix runs, treat every CLJS statement in this section as a design
intention.

This is **not** the codegen symbol-rewriting §8.2 rejected. Nothing in `:code`
is altered; only the binding that gives a name meaning is made explicit. That
distinction is load-bearing, because §8.2's third clause is that *lexical
shadowing keeps working*: authors legitimately bind core names
(`each count in xs`, `(let [count 99] count)`, `(fn [inc] …)`), and a textual
qualifier — having no scope tracking — would rewrite those bindings too. Making
one safe means a scope-tracking analyzer over arbitrary author Clojure, whose
subtle failures are silent wrong output: the disease, not the cure. Binding
instead of rewriting preserves shadowing for free, since the author's inner
binding shadows the outer one by ordinary scoping.

Two consequences worth stating, both verified rather than assumed:

- **Macros are excluded from `:vocabulary`.** A macro cannot be bound
  (`Can't take value of a macro`), and needs no binding — it is expanded at the
  call site before any runtime binding could matter. Templates do reach macros
  (`when`, `cond`, `->`, and `let` in an author's code block), so this is a live
  case.
- **The vocabulary walk is scope-blind, deliberately.** A template that shadows
  a core name still lists it. Harmless for the same reason §4.4's
  over-collection is harmless — the inner binding wins — and the alternative was
  the analyzer above.

### 5.2 The compiled artifact — a transparent value

```clojure
{:fn         (fn [model env] hiccup)   ; ordinary function: memoize it, pass it around
 :code       '(fn [{:keys [...]} env] ...)  ; the full form, as data
 :vocabulary '{count clojure.core/count}    ; ambient names :code borrows (rev. 17)
 :doctype    "html" | nil
 :mode       :html | :xml
 :symbols    #{books user ...}         ; inferred model keys
 :deps       #{key ...}}               ; every resolver key touched (root, includes, extends)
```

`:symbols` and `:vocabulary` are complements over the same candidate symbols:
every free simple symbol in `:code` is either a model key the caller supplies or
an ambient name carlin supplies. Neither reaches the rendered bytes, which is
why both are pinned by unit suites rather than by the golden corpus (§12.6).

**`:symbols` holds model keys only (rev. 17, S32).** Codegen mints gensyms for
its own bindings — the collection bound once by `each`, the scrutinee bound once
by `case` — and through rev. 16 those leaked into `:symbols` for eight corpus
cases. They are not model keys: no caller can supply `coll1866`, and the name
changes on every compile. They are now excluded by *identity*, through a ledger
recording each gensym as it is minted, rather than by name shape. The shape test
is the tempting fix and the wrong one: `#"(coll|scrut)\d+"` also eats model keys
an author legitimately wrote (`coll1`, `scrut2`), trading a phantom key —
harmless, since the binding shadows it — for a missing one, which silently stops
binding real data. Sentinel colliding with legitimate value, the rev. 12 species,
eighth sighting.

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
escape `<` (as `\u003C`) — and `<` *only* (S16, rev. 9) — closing the
`</script>`-breakout injection that generic JSON emitters leave open by
default; `</script` and `<!--` both ride on `<`, so the formerly symmetric
`>`/`&` escapes bought no safety and cost pug's `&amp;quot;` attribute shape. `->js` is the script-context
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
| `known-symbol?` | `resolve` / sci ctx | resolved at macroexpansion, by `deftemplate` |
| `qualify` | `ns-resolve` in `template-ns`, macros excluded (rev. 17) | at macroexpansion — **unverified**, may need the target (see §5.1) |
| `template-ns` | dedicated namespace (clojure.core + `raw`/`->js`) | sci ctx / analyzer env exposing the same vocabulary |

**`known-symbol?`'s CLJS branch is now a classified error, not `false`
(rev. 17, S31).** It had been hardcoded `false`, which reads as a harmless
stub and is not one: answering `false` for everything means every free
symbol is taken for a model key, so `count` lands in the destructuring,
binds to nil, shadows the real function, and the call dies as a bare
`NullPointerException` with no message — the unclassified-failure signature
§8.3 exists to abolish, on the very platform `deftemplate` serves first.
CLJS has no runtime `ns-resolve`; resolution belongs at macroexpansion,
where `deftemplate` runs on the JVM inside the CLJS compiler's own process
and both `ns-resolve` and the analyzer exist. Reaching this function at CLJS
*runtime* means the `:eval` path, which needs sci — so it now says so
instead of returning the one specific wrong answer.

`template-ns` (S15) is the namespace template expressions resolve and evaluate
against: clojure.core plus exactly `raw` and `->js`, referred from
`carlin.runtime`. `evaluate` binds `*ns*` to it and `known-symbol?` resolves
against it, so analysis and evaluation agree by construction. This is a design
position, not a convenience: `raw` and `->js` are *ambient vocabulary via
namespace mechanics*, never codegen symbol rewriting — user names stay user
data (rev. 4 hygiene), lexical shadowing still works, and templates no longer
resolve against whatever `*ns*` the caller happened to be in. Any sci or CLJS
evaluation strategy must mirror it (a context exposing the same two names) so
the namespaces don't drift.

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

**Both halves report identically (rev. 15, S29).** Diagnostics are not a
front-half privilege. The code generator raises the same positioned
`:carlin/error` through the same `carlin.cursor/fail!`, so a caller cannot
tell from the shape of an error which pass produced it — and need not care.
The back half's classes are `:stray-when` and `:stray-default` (a `when` or
`default` outside a case), `:case-clause` (a case child that is neither),
`:anonymous-block` (an unnamed `block` with no enclosing mixin to yield
into), and `:undefined-mixin` (a call to a name defined nowhere, carrying
`:mixin`). Two further classes are internal-invariant assertions rather than
user errors, reported positioned so that the day they fire they say where:
`:extends` (a surviving `extends` node, which inheritance-merge should have
folded) and `:unsupported-construct` (a node type reaching `gen` with no
branch).

**Malformed directive heads (rev. 16, S30).** A directive whose operand is
MISSING is a positioned error, not a nil that compiles. The classes are
`:each-missing-binding`, `:each-missing-in`, `:each-expected-in` (carrying
`:found`) and `:each-missing-coll` for the `each`/`for` family;
`:missing-condition` for `if`, `unless` and `else if`; `:missing-scrutinee`
for `case`; `:missing-when-value` for a `when` clause; `:missing-expression`
for an empty `=`, `!=`, `#{}` or `!{}`; and `:mixin-missing-name`,
`:mixin-bad-name`, `:mixin-missing-bindings`, `:mixin-bad-bindings` for
definition heads.

The discipline these share, and it is the load-bearing one: **absence is
tested, never falsity.** `if nil`, `when false`, `each x in nil` and `p= nil`
are all legal templates, and they reach the parser carrying the identical
`:form nil` that a missing operand does. Presence is therefore read from the
READ — the map `read-line-form` returns, or `read-source-form`'s `:eof`
flag — never from the truthiness of the form it carries. This is the rev. 13
else-if-falsy lesson generalized across a family: `:else-if?` already
carried presence-of-the-keyword separately from the form for exactly this
reason. A check written the other way rejects legal templates, which is the
failure mode that matters and the one `falsy-operands-stay-legal` pins.

This replaced the **deferral contract**, under which the back half threw an
unpositioned `:carlin/defer` sentinel and the seam silently recompiled the
whole template on the retired `carlin.legacy` engine. See the rev. 15
revision note for why that fallback had become a liability rather than a
safety net.

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
4. **Diagnostics corpus** (§12.5) — one deliberately broken template per
   positioned error class in §8.3, asserting class and `{:line :col}`. Error
   quality is a feature; test it like one.
5. **Cross-platform matrix** — the golden corpus under: JVM `eval`, JVM
   `deftemplate`, bb, CLJS `deftemplate`, sci. Output must be identical;
   diagnostics may differ per §8.3's platform notes.
6. **Resolver tests** — file-resolver anchoring, extension defaulting, root-jail
   escapes, `:kind` routing, cycle detection, `:deps` completeness; the
   three-line in-memory resolver doubles as the test harness for include/extends.

### 12.5 The diagnostics corpus (rev. 16)

Built at S30, having been specified since rev. 1 as item 4 above and never
constructed — a gap that cost two sessions of defects (§8.3's S29 and S30
classes) before it was closed.

It lives at `test-resources/diagnostics/`, runs under `bb diagnostics`, and
carries **its own manifest ratchet** (`diagnostics-manifest.edn`),
independent of the golden one. Its necessity is structural, not incidental:
the golden corpus holds only LEGAL templates and compares OUTPUT BYTES, so a
template that ought to be *rejected* has no golden to disagree with. It is
constitutionally incapable of observing what carlin does with malformed
source, and was green through both S29 and S30.

Every axis inverts:

| golden corpus | diagnostics corpus |
|---|---|
| legal templates | illegal templates |
| compares output bytes | compares error class + position |
| green = correct rendering | green = correct rejection |
| a case that errors is a bug | a case that compiles is a bug |

`<name>.carlin` pairs with `<name>.edn` holding `{:class :line :col}`, plus
optional `:data` (ex-data keys that must match), `:fixture` (a named
resolver, since a template cannot express one) and `:entry :compile-ref`.
**Position is part of the contract**: a class defaulting to line 1 would pass
a class-only check while being useless to an author. Prose is never
asserted, per §8.3.

The runner RENDERS as well as compiles, because a few classes
(`:unsupported-js-value`, runtime attr conflicts) are raised at render time;
a case surviving compilation alone is not yet proven rejected.

Three classes stay deliberately uncovered, being internal-invariant
assertions unreachable by construction: `:unsupported-construct`,
`:extends`, and `:not-implemented` (the CLJS branches).

### 12.6 The deftemplate differential (rev. 17)

Item 7 of §12's plan asks that every evaluation strategy produce identical
output. `deftemplate` is the second such strategy: it compiles through the
host compiler and never calls `platform/evaluate`. `bb differential` renders
every golden case both ways and compares bytes, and any disagreement is
exit 1.

It runs inside the conformance harness rather than as a unit suite, and that
placement is the point. The differential is only meaningful over templates
with real inputs, and the corpus resolver, `case-model` and `case-filters`
live there. The first version of this check was a standalone probe that
compiled whatever it could and reported *84 identical, 0 differing* — while
silently skipping 45 cases, which is to say every include, every extends and
every filter case: precisely the templates whose `:code` is most complicated.
Moved into the harness it reports **101 identical, 0 differing, 3
uncompilable**, and those three are the known golden reds, each failing with
its correct positioned error. Same code, same verdict, honest denominator.
*A pin is only as good as its probe* (rev. 18), a third time.

The macro itself cannot be exercised here — its argument must be a
compile-time constant — so the check reproduces its *emission*: bind the
artifact's `:vocabulary`, evaluate `:code`, compare. Both the macro and the
check read `:vocabulary` rather than deriving it independently, so they
cannot drift apart (rev. 13: one scanner living in two places).

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
  reversal. *(Erratum, rev. 8: the attribute-position claim was wrong for
  `inheritance.alert-dialog` — its `I&#39;m` was text position, a workaround
  this very ruling abolishes; the golden was reverted to pug's vendored
  original. See the departure log's scope-narrowing annotation, which is the
  authoritative record. The `attrs.carlin` edit stands.)*
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

**Revision note (rev. 8).** Rev. 7's first two rulings are enforced; one
design position is recorded; one parser bug — of a familiar species — is
fixed; and the docket reopens.

- **The template namespace is a design position, not a convenience** (S15,
  §8.2). Template expressions resolve and evaluate against a dedicated
  namespace: clojure.core plus exactly `raw` and `->js`, referred from
  `carlin.runtime`. `evaluate` binds `*ns*` to it; `known-symbol?` resolves
  against it — analysis and evaluation agree *by construction*, which is the
  point. The alternative (codegen rewriting of reserved-ish symbols) was
  rejected: ambient vocabulary belongs to namespace mechanics, user names stay
  user data (rev. 4 hygiene), lexical shadowing keeps working, and templates
  stop resolving against whatever `*ns*` the caller happened to be in. Any sci
  or CLJS strategy must expose the same two names, or the platforms drift.
- **Ruling 2 enforced** (§3.5, §6.3): collection attribute values are JSON.
  The seam is the serializer's `attr-value-str`: `:style` maps keep CSS
  rendering; every other map or collection goes `->js` first, attribute
  escaper second — the pipeline order that produces the `&quot;` shape.
  `->js` now **rejects the raw marker explicitly** (`:unsupported-js-value`):
  a Raw is a record, records are maps, and it would otherwise have sailed
  down the `map?` branch and JSON-encoded as `{"s":…}` — the fourth sighting
  of that trap, and the first caught before the bug rather than after.
- **Ruling 3 enforced** (§4.6): class accumulation order is textual source
  order. The fix is one recorded integer plus one transform: the parser
  already *saw* where the attrs map sat among the shorthand classes and now
  records it (`:classes-before-attrs`); a codegen transform
  (`thread-class-order`) splits the shorthands around the map's position and
  folds the trailing ones after the map's own `:class` value, applied at the
  top of both tag and mixin-call codegen so every downstream consumer sees
  the threaded node. No new machinery — `class-tokens`' recursive flatten
  does the rest. The lesson generalizes: before designing machinery, ask what
  the parser already knows.
- **`&attributes` bare symbols are name tokens** (§3.5), closing a
  sigil-swallowing bug of the same species as the bare mixin call's: the
  reader treats `=` as a symbol constituent, so `&attributes attributes= x`
  read as the single symbol `attributes=` — free-symbol nil, attributes
  silently dropped, tail rendered as literal text. The corpus's #1424
  regression case masked half of it: the argument's name and value were both
  `work`, so the literal text *happened to equal* the intended output.
  Delimited forms keep the reader. Pinned in the merge-attrs suite with a
  probe the corpus cannot express.
- **The erratum in rev. 7's note** (S12 scope): recorded inline where the
  wrong claim was made; the departure log is the authoritative record.
- **S16 opened** — the first non-empty docket since S15: two escaper-shape
  questions in attribute position (`attrs-data`), pug's class-hoisting
  against the source-order doctrine (`attrs.js`), and `mixin.attrs`'s
  residual deltas. Premise verification against the pug 3.0.2 tag has
  already reshaped the third: two of its three deltas are converter
  artifacts in carlin's *templates* (an inline-text-promoted-to-argument
  call; a `.thunk` moved ahead of the attrs map), not golden defects — the
  goldens are pug's honest output and the doctrine already agrees with them.
  Rulings pending.

**Revision note (rev. 9).** S16 ruled in batch — all three recommendations
ratified (2026-07-22) — implemented, and closed. Ratchet 74 → 77
(`attrs-data`, `attrs.js`, `mixin.attrs`), zero regressions, exactly the
predicted flips.

- **S16 (a) — two shapes, two instruments.** (i) The apostrophe in attribute
  position is S12's ratified substance, correctly scoped this time:
  `attrs-data`'s golden edited (`Let's` → `Let&#39;s`), logged under the S12
  departure entry. (ii) `js-string` **narrowed to escape `<` only** (§6.3):
  `<` alone carries the script-context guard — `</script` and `<!--` both
  ride on it — so the symmetric `>`/`&` escapes were safety theater with a
  cost, pug's `&amp;quot;` attribute shape. Dropping them restored that shape
  with **no golden edit**; in attribute position the HTML escaper handles `&`
  downstream anyway, and in script position neither `>` nor `&` opens
  anything. The script-safety pins (which always pinned only `<`) pass
  untouched; the one serializer ride-along pin updated to `&gt;`.
- **S16 (b) — the doctrine outranks the golden.** Pug hoists `class` to the
  front of the attribute list; carlin's rev. 5 source-order doctrine renders
  attributes in textual order, and that is the point of owning the
  serializer. `attrs.js`'s golden edited (`href` before `class`, four
  occurrences), logged as a **permanent departure** — S8's shape: the wall is
  worth more than the case.
- **S16 (c) — two converter repairs, zero doctrine.** The rev. 13 premise
  verification held: both residual deltas were converter artifacts in
  carlin's *templates*, pug's goldens honest. Repaired to pug's actual
  anatomy: `+(centered nil)#First Hello World` (inline text had been promoted
  into the argument; `title` is undefined in pug's original) and
  `+foo{…}.thunk` (the shorthand had been moved ahead of the attrs map;
  textual order is `thing foo bar thunk`). Logged as **converter-error
  repairs, not departures** — no law was touched, and both forms were
  probe-verified before the ruling, per the rev. 11 lesson.

The docket is empty. The last rev. 7 law awaiting enforcement is ruling 4
(include-with-body at `yield`, §3.11) — probe pug first.

**Revision note (rev. 10).** Three pre-rulings for ruling 4's enforcement
session, Ricardo-ratified in batch 2026-07-22, ahead of implementation (the
rev. 7 pattern). Ratchet untouched — 77/100 stands; this note is law without
code, and §3.11 is amended to match.

- **The lossiness rule, named.** "Pug-fidelity is a default, not a
  constraint" (S8–S14) gains its operative boundary: **follow pug as
  faithfully as possible unless its behavior is lossy or grossly
  unexpected**, in which case carlin raises a positioned error and logs the
  departure. This pre-ratifies the whole class: probe results that show pug
  silently discarding author content do not need an S-docket round-trip —
  the ruling is already made, only the departure entry remains to be
  written. Probe results that are merely *surprising* still come back for
  adjudication; "grossly unexpected" is Ricardo's call, not the session's.
- **Multiple `yield`s splice everywhere** (§3.11). The include body's AST
  replicates at every `yield` in the included file; evaluation is per splice
  site, so side effects run per splice — explicitly the author's
  responsibility, now explicitly in the spec. Ratified as carlin law
  independent of the probe; a pug divergence, if found, is a logged
  departure, not a reopened question.
- **Include-with-filter rides along** in the same enforcement session: the
  filter-attrs parse on the include branch and the corpus repairs to
  `include:custom{:opt "val"}` share the context ruling 4 opens, and the
  working principle is to fix and complete everything in context while it is
  in context. Corpus edits still go to the docket first (rev. 12
  discipline) — riding along changes the session plan, not the paperwork.

**Revision note (rev. 11).** Ruling 4 enforced — the last rev. 7 law is code.
Probe-first honored: every §3.11 edge was pinned against pug 3.0.2 (npm
package and pug-linker source, 2026-07-22) before implementation, never
asserted from memory. Ratchet 77 → **80/100**, baselined, zero regressions
(flips: `include.yield.nested`, `include-only-text`,
`filters.include.custom`); spec suites 17 tests / 95 assertions / 0 failures.

- **The probe record.** Multiple yields: pug splices at every yield — the
  rev. 10 law turns out pug-faithful, no departure. Body on a raw or
  filtered include: pug raises "Raw inclusion cannot contain a block" —
  adopted verbatim as `:body-in-raw-include`. Unfed yields render nothing;
  a nested include's unfed yields catch an enclosing body (the cascade) —
  both adopted. `yield` with trailing text is a tag; with children, an
  error — both adopted (`:yield-children`).
- **S17 ruled and enforced** (the probe's one merely-surprising result,
  returned to Ricardo per the lossiness rule): a body on a no-`yield`
  include is `:body-without-yield`, a **logged departure**. Pug buries the
  body at the deepest last block of the included file — not lossy, but
  "camming content at an unpredictable context-dependent location is
  inadmissible" (the ruling, verbatim), and pug-linker's own source marks
  the behavior for deprecation.
- **S18 ruled and applied**: `filters.include.custom` repaired from pug
  attr syntax to `include:custom{:opt "val" :num 2}`; the case includes
  itself, so the golden's embedded source line changed in consequence
  (regenerated mechanically, verified byte-equal to the file). Repair +
  repair-consequent golden edit, not a departure.
- **Ride-along landed**: the include branch parses a filter-attrs map,
  passed to the filter fn as `attrs` (§3.11/§3.12).
- **`:include-children` retired**; children under `include` are the body.
  New diagnostics: `:yield-outside-include`, `:yield-children`,
  `:body-in-raw-include`, `:body-without-yield` — all pinned.
- **S19 opened**: the three includer-side `yield*` exclusions
  (`yield`, `yield-title`, `yield-before-conditional`) were probe-verified
  green under the new law; readmitting them moves the denominator
  (100 → 103) and awaits ruling.

**Revision note (rev. 12).** S19 ruled and applied: the three includer-side
`yield*` exclusions (`yield`, `yield-title`, `yield-before-conditional`)
readmitted to `cases/` — their exclusion rationale (children under
`include` were an error) died with `:include-children`, and all three were
probe-verified green before the ruling (the rev. 11 lesson, honored).
Denominator 100 → **103**; ratchet lands at **83/103, baselined, zero
regressions** in the same stroke — an S14-shaped adjustment in reverse:
S14 removed cases whose failure misstated the engine downward; S19 restores
cases whose passing was hidden. The `yield*-head` include targets moved to
`cases/` as support files without golden pairing; compiled as roots they
raise `:yield-outside-include`, pinned in the diagnostics suite, and their
goldens remain in `_excluded/`, negative-by-design. No code changed —
this revision is corpus population only.

**Revision note (rev. 13).** The pools session. No new language law was
ruled: every change below either fixes an implementation that already
contradicted the spec, or records behavior the spec had left to inference.
The docket (S20–S26) was ruled in batch and applied same-day; ratchet
**83/103 → 100/102, zero regressions**, spec suites **17 tests, 96
assertions, 0 failures**. Two cases remain red, both permanent departures
(`inheritance.extend.include`, S8; `include-with-text`, S22).

Six implementation bugs, all probe-verified against pug 3.0.2 before the fix:

- **Trailing blank lines belong to the block** (§3.4, §3.10, §3.12).
  `capture-raw` had dropped them as "the gap before the next sibling."
  Probed: pug renders each as a newline in dot blocks, comment bodies and
  filter input alike — and drops them only at EOF, where they are the file's
  own trailing whitespace rather than a dedent boundary. The spec's
  "provisional dot-block dedent rule" erratum is hereby settled in pug's
  favor and the provisional marker removed. This single fix accounts for the
  whole `comments` delta.
- **`else if` with a falsy condition** (§3.7). The parser stored the
  condition as `:else-if` and the attacher tested its *truthiness*, so
  `else if false` — which the corpus writes verbatim — silently degraded to
  an unconditional `else`, swallowing the real alternative. Presence is now
  carried separately (`:else-if?`). A sentinel colliding with a legitimate
  value: the `records-are-maps` species in a new costume, fifth sighting.
- **Tagless lone-dot text block** (§3.3/§3.4). A `.` alone on its line opens
  a dot block with no tag, splicing its captured text at that position.
  Carlin had misparsed it as an empty `div` shorthand. `.foo` (word char
  after the dot) is still the class shorthand and never reaches the branch.
- **Literal markup lines take children** (§3.3). Deeper-indented lines under
  a `<ul>` line are its children; pug renders them newline-joined with
  indentation stripped. Carlin had dropped them entirely.
- **Empty class and empty style are omitted** (§3.5/§4.6). `class=""`, `[]`,
  `[""]` and `style={}` all render a bare element in pug. A value that
  renders to nothing is no value.
- **`doctype xml` reaches the serializer** (§3.9/§7.2). `compile-tree` had
  hardcoded `:mode :html`, clobbering the doctype-selected profile back to
  the HTML void table, so `link http://google.com` lost its children. `:mode`
  now carries the *user's override only*, and the doctype selects the profile
  at render as §7.2 always said it did. Under `:xml` no element is void.

Two further findings, both recorded as law:

- **Mixin redefinition is positional, not last-wins** (§3.13, S24). A call
  site binds to the definition in force at its own source position. Rev. 5's
  dedupe-last-wins was written to satisfy `letfn`'s one-binding-per-name
  constraint and its premise was never measured; `mixin.block-tag-behaviour`
  is the golden proof. Later same-name definitions are now suffixed and each
  call rewritten to the name current at its position, so a definition sees
  itself under its new name and self-recursion still binds correctly.
- **A style map renders with trailing semicolons** (§3.5, S25). Probed:
  `k:v;` per pair, terminator included; a style *string* passes through
  untouched. The escaper suite had pinned the opposite — written from
  assumption, never probed. The implementation was right and the pin was
  corrected. **A pin is only as good as its probe**: an unprobed assertion
  is a hypothesis wearing a test's clothes, and it will eventually contradict
  a correct implementation with all the authority of a regression.

**Revision note (rev. 14).** One structural fix and one ratification, both
concerning inline `#[…]` interpolation (§3.3, §3.13).

- **Checks reach inside `#[…]`** (S27, option (a) ruled). A fragment's
  interior was, until now, invisible to every structural check: it stayed an
  opaque string through parsing and was only re-parsed inside codegen's
  `scan-text`, long after the check battery had run. The measurable symptom
  was arity — `+(m)` at line position gave a positioned `:mixin-arity` at
  compile time, while `p x #[+(m)] y` compiled clean and died at *runtime*
  with an unclassified sci `ArityException` carrying no position at all. But
  arity was only the instance the corpus happened to expose; `:nested-mixin`,
  `:yield-children`, `:default-not-last` and every check yet to be written
  were equally blind there.

  `core/inline-fragments` now parses every `#[…]` fragment at parse time —
  including fragments nested inside other fragments — and `node-kids`, the
  walk's single child accessor, yields them alongside a node's real
  children. The check battery therefore reaches inline position without any
  individual check knowing that fragments exist, and every check added later
  inherits the coverage for free. The render path is deliberately untouched:
  codegen still re-parses when it emits, so this hoist can never change
  output, only diagnose it. `matching-bracket` moved to `carlin.core` so
  both halves of the pipeline share one scanner rather than two that can
  drift.

  The interpolated positions are exactly codegen's `scan-text` call sites:
  `:inline-text`, `:text`, a dot block's `:dot-block/:text`, and a
  `:text-block`'s `:body/:text`. That last one is read *only* for
  `:text-block`: comments and filters carry their captured body under the
  same `:body` key and neither interpolates — comment bodies emit raw,
  filters run before the model exists — so hoisting from them would invent
  calls that never render and fail templates that are perfectly legal. Same
  key, three meanings; the sentinel-collision species (rev. 12) wearing a
  structural costume, and caught this time by a pin that asserted the
  non-interpolating bodies stay silent.

- **Bare mixin name plus inline text, inline** (S28, ratified). Inside
  `#[…]`, `+name text` passes **no argument**: the trailing text becomes the
  mixin's block content, `:argc` is 0, and arity is checked against that.
  This is not new law so much as the recognition that existing law already
  covered the position — the parser had read it this way all along, it
  matches line position (`+box Some text`), and it matches pug 3.0.2, whose
  output carries the quotes through as literal characters. Only the gate was
  missing. Pinned by `mixin.inline`, whose golden was generated from pug and
  matched byte-for-byte on first execution.

**A frontier of zero reds is a statement about the corpus, not about the
language.** Six inline spellings worked with no case exercising any of them,
and a seventh was broken with none exercising it either. The corpus measures
what it contains.

**Revision note (rev. 15).** `carlin.legacy` is **retired**, and with it the
deferral contract. S29 — ruled in batch, implemented and measured in one
session; ratchet held at 101/104 with zero flips, spec suites 17/103 → 20/116.

**The plan item's premise was false, and measuring it is what found the real
bug.** Rev. 20 scheduled legacy's deletion as dead-code removal: "nothing
defers to it any more." Instrumenting the seam over all 104 corpus cases
confirmed the visible half of that — 101 compile on `:carlin`, three raise
genuine `:carlin/error`, **zero** reach legacy. But six live `defer!` sites
remained in the code generator, and the corpus contains no malformed
templates, so it could not see them. Probed directly, five were reachable,
and each produced markup invented from a keyword:

| template | before S29 | pug 3.0.2 |
|---|---|---|
| `when 1` outside a case | `<when>1<p>hi</p></when>` | error |
| `default` outside a case | `<default>…</default>` | error |
| non-`when` clause in a case | `<case>1…</case>` | error |
| bare `block` outside a mixin | `<block>…</block>` | error |
| `+nope`, defined nowhere | literal text `+nope` | error |

Legacy had no moat-keeper left to protect because it *was* the moat-keeper —
for malformed input, and it kept the moat badly. Emitting a plausible-looking
`<when>` element for input the compiler has already recognized as broken is
precisely the **grossly unexpected** outcome the §7 lossiness rule exists to
forbid, and pug agrees on every one. So the five became positioned errors
(§8.3) and the fallback was deleted rather than preserved.

**The governing rule, stated once so it stops being re-derived: whenever
carlin can fail fast at compile time, it does.** Pug raises
`:undefined-mixin`'s equivalent at *runtime*; carlin raises it at compile
time, which is stricter and consistent with §3.13's existing doctrine that
compile-time guarantees are the point — the same reasoning that rejected
pug's absent-is-undefined for arity. This is a departure by strictness, not
by semantics: no legal template changes meaning, which is why the ratchet did
not move a single golden.

**A stale comment was hiding a real invariant.** The `:undefined-mixin` site
carried the hedge *"may live in an unmerged layout"* — a claim that this pass
might run before `extends` was folded, which would make a layout's mixin look
absent and reject a **legal** template. That premise was stale: §3.14 made
`resolve-template` mutually recursive with `splice-includes`, so inheritance
is merged base-upward, includes spliced and named blocks dissolved before the
generator sees a tree. Probed four ways — a layout mixin called from a child's
block renders; one arriving via include renders; a layout mixin called with
the wrong arity is caught *earlier* by `check-arity`, positioned into the
child; and only a name defined nowhere reaches the site. Checking a ruling's
factual premises before enforcing it (rev. 11) is what turned a comment into
a measurement.

**What the investigation found underneath is worth more than the comment.**
There are **two** mixin tables, built by different walks:
`carlin.core/walk-checks` collects definitions **recursively**;
`codegen/compile-tree` collects them from the **top level only**. A recursive
collector and a top-level collector that must agree is exactly the drift
rev. 13 warns about — one scanner in two places. They cannot diverge, but
only because of a *third* guard in a different namespace: `:nested-mixin`
forbids a definition below depth 0, so every surviving definition is
top-level by construction and the tables are necessarily equal.

That invariant is **load-bearing and was undocumented**. The top-level filter
is not an optimization; it is sound only while `:nested-mixin` holds. Relax
that guard — a plausible future move, since pug's own scoping is looser —
and the table silently stops seeing nested definitions, `arity` reads
`::absent` for a legal call, and S29's new error rejects valid templates.
It is now stated at both sites and pinned: `mixin-table-invariant` asserts
`:nested-mixin` fires, so the guard the arity machinery depends on cannot be
removed silently. Mutation-testing the table confirmed the pins bite.

**`carlin.api` stays**, against rev. 20's plan to fold it into
`carlin.core`. It is the surface §5 *names* and the one three consumers
already import (harness, spec suites, `bin/render`); a seam that adapts
nothing is still a useful front door, and it houses `render`, which belongs
to neither the parser nor the generator. Only its legacy branch is gone.
`core/compile-ref`'s lazy `requiring-resolve` therefore stays too, and is
now documented as **deliberate**: `api` requires `codegen` requires `core`,
so a static require would close a cycle.

**hiccup leaves the runtime dependencies**, completing the trajectory §13
predicted. It remained only because legacy needed it; nothing under `src/`
has referenced the library since carlin's own serializer landed. It stays a
test dependency permanently, as the differential oracle (§12.3). Carlin's
sole runtime dependency is now edamame.

**Lesson: a fallback that only catches what is already broken is not a
safety net.** Deferral earned its keep while the new engine was incomplete —
it kept the corpus baselined through six sessions of feature-by-feature
landing. The moment the last legal construct compiled, its remaining
population was exactly the templates that should have been rejected, and it
converted each of them into confident, wrong output. A fallback's value
should be re-measured when the thing it backstops changes, not assumed to
persist.

---

## Revision note — rev. 16 (S30: malformed directive heads; the diagnostics corpus)

Two changes, one of which is the reason the other was findable.

**S30 — a directive head with a missing operand is now a positioned error.**
Sixteen spellings across seven constructs used to be accepted. Not one of
them produced a diagnostic; they produced *output*. `each b xs` — the `in`
omitted — bound the collection to `nil` and rendered an empty loop. A bare
`if` compiled to `(if nil …)`, a permanently dead branch. A bare `case`
compiled to a scrutinee no `when` could match except `when nil`. `mixin m`
without a bindings vector compiled and rendered. Four spellings were worse
than silent: `p=`, `p!=`, `#{}` and `!{}` with nothing after them died as a
bare `NullPointerException` with a nil message — no class, no line, no
column, the worst diagnostic in the codebase.

The mechanism was uniform and worth naming, because it is not obviously a
bug at the site: every one of these reads answered `nil` on absence, and
**`nil` is a legal Clojure form**. Codegen then compiled it faithfully. No
pass was wrong on its own terms; the defect lived in the seam, where a
sentinel meaning "nothing was there" is indistinguishable from a value
meaning "the author wrote nil". That is the sixth species of the
records-are-maps trap (rev. 12) — a sentinel colliding with a legitimate
value — and the seventh sighting overall.

Which dictates the fix's shape. Presence is read from the READ, never from
the form: `read-line-form` returns a map or nothing, `read-source-form`
flags `:eof`. Testing the form instead would reject `if nil`, `when false`,
`each x in nil` and `p= nil` — all legal, all writing that sentinel
deliberately. `:else-if?` has carried presence-of-the-keyword separately
from the form since rev. 13 for precisely this reason; S30 generalizes the
discipline to a family. The `falsy-operands-stay-legal` suite pins it, and
mutation-testing confirms that swapping presence for truthiness fails there
— the failure mode that matters is rejecting the legal, not accepting the
broken.

One fix reached deeper than the parser. `#{}` never gets as far as a
missing-operand check: handed a lone `}`, edamame *throws* `Unmatched
delimiter` rather than answering `:eof`, and reports it with an
`opened-delimiter-loc` whose `:row` and `:col` are **nil** — there is no
opened delimiter to point at. `platform/rebase` then `dec`ed nil. The
`:reader-error` branch immediately below had defended its coordinates with
`or` since it was written; the `:unterminated-form` branch above it never
had. Narrowing that branch to locations that actually know where they opened
routes unmatched closers to `:reader-error`, where they belong.

**Two departures by strictness are logged.** `mixin m` without a bindings
vector is rejected though pug accepts it — §3.13's grammar is
`mixin name [binding-vector]` and the vector is required even when empty, so
this enforces existing carlin law rather than inventing it. And the whole
S30 family fails at *compile* time where pug's equivalents are a mix of
compile and runtime errors. No legal template changes meaning, which is why
the golden ratchet held at 101/104 with zero flips throughout.

**The diagnostics corpus (§12.5) was built.** It had been specified since
rev. 1, as item 4 of the §12 test plan, and never constructed. That gap is
the direct cause of both S29 and S30: the golden corpus holds only legal
templates and compares output bytes, so it cannot observe what carlin does
with input that ought to be rejected — and it was green through every defect
above. The unit suite was the only instrument covering that territory, and
it had grown reactively, a class at a time, as bugs surfaced.

Auditing §8.3's enumeration against what `src/` actually raises — the audit
rev. 21 asked for — found **40 error classes, 8 of them with no pin at
all**. All but one were probed reachable and are now covered.

The corpus inverts every axis of the golden one: illegal templates, compared
on error class and position rather than bytes, where a case that *compiles*
is the failure. It carries its own manifest ratchet at 43/43. Mutation
testing confirms it catches what it was built for: reverting a single S30
guard turns a green run into `UNCLASSIFIED — NullPointerException` and a
non-zero exit.

**Lesson: a corpus can only find defects in the population it samples.** The
golden corpus was never inadequate at what it does — it has held 104 cases
baselined across a dozen sessions with zero regressions ever. It simply
samples legal templates, and no amount of it would ever have found a
malformed one mishandled. Coverage of a *space* is not coverage of its
*complement*. When a test artifact is green through a defect, the question
is not whether it is passing but whether the defect was ever in its reach.

**Revision note (rev. 17).** `deftemplate` lands — the last unimplemented §5
entry point — with two corrections it forced and one gate it justified.

**S33** moves §5.1's stated namespace from `carlin.core` to `carlin.api`. The
spec had said `core` since rev. 1 and the code has never agreed: both siblings
have lived in `api` since the battery was built, and S29 kept that namespace
deliberately as the front door §5 names. Stale prose, corrected rather than
implemented around.

**S31** began as a ruling to qualify author symbols at codegen time, and was
withdrawn before implementation because §8.2 had already rejected exactly that,
twice and by name — *ambient vocabulary belongs to namespace mechanics, user
names stay user data, lexical shadowing keeps working*. The third clause is
what kills it. Authors legitimately bind core names — `each count in xs`,
`(let [count 99] count)`, `(let [{:keys [str]} …] str)`, `((fn [inc] …) 41)`,
all of which render correctly today — and a textual qualifier has no scope
tracking, so it rewrites those bindings too. Making one safe means a
scope-tracking analyzer over arbitrary author Clojure, whose subtle failures are
silent wrong output: the disease presented as the cure. *Check a ruling's
factual premises before enforcing it* (rev. 11), this time against a ruling of
our own making rather than an inherited one.

What shipped instead carries the vocabulary as **data**. The artifact gains
`:vocabulary`, mapping each borrowed ambient name to its resolution in
`template-ns`, and `deftemplate` binds precisely those around `:code`. Nothing
is rewritten, so shadowing survives by ordinary lexical scoping. The decisive
test: a template reading `p= (count coll)`, expanded inside a namespace that
excludes and redefines `count`, renders `<p>3</p>` — where the naive macro
rendered `[:p :MY-COUNT]`, wrong output rather than an error, from a
declaration made in another file for unrelated reasons.

The route there was not the one planned. Emitting into a generated namespace
via runtime `in-ns` — the mechanism §5.2 names for AOT — is **unusable under
babashka's sci**: a var defined that way is unreachable through `resolve`,
`ns-resolve`, `requiring-resolve` and direct symbol reference alike, all four
answering nil. bb is a declared target platform, so the namespace had to become
a `let`. *A handoff's plan item is a hypothesis about the code* (rev. 19), and
so is a ruling's stated mechanism.

**S32** removes codegen's own gensyms from `:symbols`, which §5.2 defines as
inferred model keys. `(gensym "coll")` yields `coll1793` — no `__`, so the
existing filter (which catches syntax-quote's `x__123__auto__`) never saw it,
and eight corpus cases advertised a model key no caller could supply and that
changed on every compile. Excluded now by identity, through a minting ledger,
not by name shape: `#"(coll|scrut)\d+"` passes every obvious assertion and
quietly eats `coll1` and `scrut2` from a caller's real model, trading a phantom
key for a missing one. Mutation-tested — the shape filter passes the golden
ratchet and fails the new pin. Eighth sighting of the rev. 12 species.

Neither `:symbols` nor `:vocabulary` reaches the rendered bytes, so the golden
corpus was green through S32 and would be green through a `:vocabulary` naming
nothing at all. §12.6 adds the differential gate for the second defence, and
`carlin.artifact-test` for the first. Both ratchets held at 101/104 and 43/43
with zero flips; suites 22/161 → 25/195.

**New lesson: a contract key that never reaches the output has no corpus.**
The golden corpus samples rendered documents, so it can only ever police what
renders. `:symbols`, `:vocabulary`, `:deps` and `:code` are load-bearing parts
of §5.2's promise that nothing is hidden, and every one of them is invisible to
the instrument that guards everything else. Rev. 22 observed that a corpus
cannot find defects outside the population it samples; this is the same
observation along the other axis — not illegal input this time, but legal input
whose defect never shows up in the bytes.
