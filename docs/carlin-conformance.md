# Carlin ↔ Pug conformance table

Feature-by-feature mapping from Pug 3 (pugjs) to carlin. Doubles as the test plan:
every ✓/◐/○ row should have at least one template in the conformance suite.

**Status legend**

| Mark | Meaning |
|------|---------|
| ✓ | Implemented in current `carlin.clj` |
| ◐ | Planned — one of the five deferred features (cursor refactor batch) |
| ○ | Gap — new work for full equivalence |
| ≡ | Subsumed by Clojure syntax; document the idiom, no implementation needed |
| ✗ | Deliberately excluded (with rationale in the spec) |
| ⚑ | Open design decision — resolve before implementing |

---

## 1. Tags

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `div` / nesting by indentation | same | ✓ | |
| Default div: `.box`, `#main` | same | ✓ | Empty tag name → `div` |
| Block expansion `a: img` | same | ✓ | Arbitrary depth |
| Explicit self-close `foo/` | same | ✓ | Renders empty element (HTML5 has no self-closing custom elements) |
| Void elements auto-close (`img`, `br`, …) | delegated to hiccup | ✓ | Mode-sensitive; see doctype §7 |
| Tag interpolation `#[b bold]` | same | ✓ | Nested `#[...]` supported via bracket matching |
| Custom elements / any tag name | same | ✓ | Core carlin goal (web components, htmx method elements) |
| Interpolated tag names `#{tag}(attrs)` | `#{expr}` in tag position + normal tag surface | ○ | Q13: dynamic-tag shorthand merges at runtime; dot blocks on dynamic tags never raw-text |

## 2. Plain text

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| Inline text `p hello` | same | ✓ | |
| Piped text `\| hello` / bare `\|` | same | ✓ | |
| Literal HTML line `<hr class="sep">` | same | ✓ | Static text raw, `#{}` still escaped (`raw-pieces`) |
| Block-in-tag text `p.` + indented block | same | ◐ | Dot blocks: min-nonblank-indent stripping, blanks preserved |
| Interpolation inside dot blocks | same | ◐ | `scan-text` over the joined block; forms may span lines |
| `script.` / `style.` escaping context | raw inside `:raw-text-tags` (default `#{:script :style}`) | ○ | Q5: pug-faithful default; configurable set keeps carlin markup-agnostic (`#{}` for pure XML) |
| Whitespace fidelity (`pre`, inline spacing) | same | ○ | Testing discipline more than code; add `pre` cases to suite |

## 3. Attributes

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `a(href='x' title='y')` | `a{:href "x" :title "y"}` | ✓ | Attrs are a literal Clojure map read with the Clojure reader |
| Multiline attributes | multi-line map | ◐ | Falls out of multi-line forms (reader crosses newlines) |
| Expression values `href=url` | `{:href url}` / any Clojure expr | ✓ | |
| Quoted attr names `('(click)'='...')` | string keys `{"(click)" "..."}` | ≡ | Clojure map keys may be strings; verify hiccup passthrough |
| Attribute string interpolation (deprecated) | `(str "/user/" id)` | ≡ | Deprecated in pug itself |
| Boolean attributes `input(checked)` | `{:checked true}` | ✓ | hiccup: `true` → present, `false`/`nil` → omitted |
| Style as map `style={color: 'red'}` | `{:style {:color "red"}}` | ✓ | hiccup 2 renders style maps |
| Class as collection `class=['a' 'b']` | `{:class ["a" "b"]}` | ✓ | hiccup 2 renders class collections |
| Class map-conditional `class={active: isActive}` | `{:class {:active active?}}` → truthy keys | ○ | Normalize in `merge-attrs` helper |
| Shorthand + map class/id merging `a.btn{:class x}` | merged, later id wins | ○ | `merge-attrs`; keyword-encoded shorthand stays as fast path when no conflict |
| `&attributes(obj)` on tags | `&attributes expr` (any map expr) | ○ | Routed through `merge-attrs` |
| Unescaped attribute values `key!= expr` | `{:href (raw expr)}` | ≡ | Q7: subsumed by the runtime raw marker; `(raw …)` is the greppable danger marker; ⚠ injection burden on author |

## 4. Code & interpolation

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| Unbuffered `- code` | `- form` | ✓ | Carlin extension: a form with children wraps them as body (`- (let [...])` scoping) — pug has no equivalent |
| Buffered `= expr` | same | ✓ | Also `tag= expr` |
| Unescaped buffered `!= expr` | same | ✓ | Also `tag!= expr` |
| Multi-line code / expressions | reader-driven continuation | ◐ | Permitted exactly after `=`, `!=`, `-`, and in attr maps |
| Escaped interpolation `#{expr}` | same | ✓ | Expr is one Clojure form; trailing `}` tolerated for pug familiarity |
| Unescaped interpolation `!{expr}` | same | ✓ | |
| `nil`/undefined interpolates as empty | `nil` → empty | ✓ | Verify both escaped and raw paths in suite |
| Escaping literal `\#{` / `\!{` | same | ○ | One-line check in `scan-text`; matters inside `script.` blocks |
| JS statements, `var` assignment | `-` code with `let`, atoms | ≡ | Clojure is the expression language |

## 5. Conditionals & case

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `if expr` / `else` | same | ✓ | `else` attachment pass |
| `else if expr` | same | ○ | Generalize attach-elses to chains → `cond` codegen |
| `unless expr` | same | ○ | Compiles to `if-not` |
| `case expr` / `when val` / `default` | same | ○ | Scrutinee bound once; `condp =`-style codegen (values are runtime exprs, so not Clojure `case`) |
| `when` fall-through (bodiless) | same | ○ | Group bodiless whens: `(or (= e# v1) (= e# v2))` |
| `when val: p x` block expansion | same | ○ | Reuses existing `:` machinery |

## 6. Iteration

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `each val in coll` / `for` alias | same | ✓ | Binding is any Clojure binding form |
| `each val, index in coll` | `each [i v] in (map-indexed vector coll)` | ≡ | Binding forms are the sugar |
| `each val, key in obj` | `each [k v] in m` | ✓ | Maps are seqable |
| `each … else` (empty collection) | same | ✓ | |
| `while expr` | **excluded** | ✗ | Q11: exists to serve JS mutation habits; its absence is the point (spec §10); corpus case retained as a negative test |
| Laziness of loop bodies | eager (`mapv`/`doall`) | ○ | Deliberate change for error locality (see §10) |

## 7. Doctype

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `doctype html` | same | ✓ | Currently the only honored value |
| Named variants (`xml`, `transitional`, `strict`, `frameset`, `1.1`, `basic`, `mobile`, `plist`) | same | ○ | Lookup table |
| Custom doctype string | same | ○ | Passthrough |
| Doctype selects terse vs XML rendering | wire to hiccup `:mode` | ○ | `doctype xml` → `:xhtml`; store mode in compiled map |

## 8. Comments

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `// text` rendered | same | ✓ | |
| `//- text` silent (children swallowed) | same | ✓ | |
| Rendered block comments (indented body under `//`) | same | ○ | Currently children of a rendered comment are parsed but dropped; should emit inside `<!-- -->` — small capture fix |
| Conditional comments `<!--[if IE]>` | literal-HTML lines | ≡ | |

## 9. Includes, inheritance, filters, mixins

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| `include file.pug` | `include file.carlin` | ◐ | Compile-time tree splice; `:resolver` option; cycle detection; per-file `:pos` |
| Include non-pug file as text | non-`.carlin` → raw text node | ◐ | |
| Filtered include `include:markdown x.md` | same | ○ | Resolver fetch → filter fn → raw text node |
| Filters `:name` + indented body | same | ○ | Options `{:filters {name (fn [text attrs] html)}}`; body captured via dot-block mode; compile-time; no interpolation inside (pug-faithful) |
| Filter attributes `:less(compress)` | `:less {:compress true}` | ○ | Attrs map via `read-form-at` |
| `extends layout` | same | ○ | Inheritance pass runs first; multi-level chains fold from base upward |
| `block name` with default content | same | ○ | Unoverridden block renders defaults; codegen = splice |
| `block append name` / `block prepend name` | same | ○ | Concatenate with parent's children |
| Top-level restriction in extending file | positioned compile error | ○ | Only blocks / mixins / includes allowed |
| Blocks inside included files | positioned compile error | ○ | Q9: extends = inheritance, include = composition; error message teaches the idiom |
| `mixin name(a, b)` | `mixin name [a b]` | ◐ | Binding vector; top-level only; hoisted into `letfn` (mutual recursion free) |
| Mixin call `+name(x, y)` | `+(name x y)` / bare `+name` | ◐ | Q14: the call is one Clojure form (reader-delimited); shorthand/attrs/&attributes follow; then standard tag tails |
| Inline text / `:` expansion on calls | `+(item "contact") Contact` | ◐ | Restored by the one-form rule — unambiguous by construction |
| Mixin block content (`block` in body) | same | ◐ | Children of `+call` → hidden `block#` param |
| Bare `block` vs `block name` | context/arity disambiguation | ○ | Bare = mixin block; named = inheritance block (pug's own rule) |
| Default arg values `mixin m(t='x')` | `(or t "x")` or `:or` destructuring | ≡ | Clojure binding vector covers it |
| Rest arguments `...items` | `& items` | ≡ | |
| Mixin `&attributes` / implicit `attributes` | shorthand + attrs on `+` line → hidden param bound to `attributes` | ○ | Parallel to `block#`; `+card.wide{:data-x 1}` |
| Mixins defined in includes usable after splice | works by construction | ◐ | Include-splice precedes mixin hoist — mixin libraries for free |
| Interpolated mixin names `+#{expr}(...)` | **excluded** | ✗ | Defeats compile-time arity checking; no letfn-natural rendition (spec §10) |

## 10. Diagnostics

| Pug | Carlin | Status | Notes |
|-----|--------|--------|-------|
| Compile errors with file:line:col + source excerpt | `ex-info` `{:file :line :col}` + caret line | ◐ | Cursor stamps `:pos` on every node; reader errors rebased to template coordinates |
| Runtime errors traceable to template line | `^{:line n}` metadata on emitted forms + `*file*` binding | ◐ | JVM compiler honors form metadata; bb/sci does its own bookkeeping — frames differ there |
| Errors inside loops point at the right line | eager `each` | ◐ | See §6 laziness note |

## 11. Deliberately excluded

| Pug | Reason |
|-----|--------|
| JavaScript as expression language | Replaced wholesale by Clojure — the "modulo Clojure syntax" clause |
| `pretty` output option | Deprecated in pug; hiccup has no pretty-printer; out of scope |
| Attribute interpolation `"#{x}"` in attr strings | Deprecated in pug; `(str …)` idiom |
| Legacy `each` index/key sugar | Clojure binding forms + `map-indexed` |

## 12. Carlin extensions beyond pug

Not conformance items, but part of the language surface the suite should also pin down:

- `env` map: every template fn is `(fn [model env] …)`; `model`/`env` reserved symbols
- Free-symbol analysis → automatic `{:keys [...]}` destructuring from the model
- `-` code with children wraps them as its body (`- (let [...])` lexical scoping over a subtree)
- `each` accepts arbitrary Clojure binding forms (destructuring in the loop head)
- Custom elements as first-class citizens (htmx `get`/`post`/`put`/`delete` post-processing convention)

---

## Open decisions — all resolved

Former ⚑1/⚑2/⚑3 were settled in the design docket (Q5, Q7, Q9) and are folded
into their rows above. Two corpus-discovered items (Q13 interpolated tag names,
Q14 one-form mixin calls) are likewise incorporated. The authoritative record of
every decision and its rationale is `carlin-spec.md` §9–§11.
