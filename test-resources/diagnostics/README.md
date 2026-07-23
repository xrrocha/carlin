# The diagnostics corpus

Illegal templates, each paired with the error carlin must raise.

This corpus exists because the golden corpus is **structurally blind to
illegal input**. That one holds 104 legal templates and compares output
bytes; a template that should be *rejected* has no golden to disagree with,
so nothing in it can observe what carlin does with malformed source. Twice
that blindness hid a whole class of defect:

- **S29** — five constructs (`when` outside a case, a stray `default`, a
  non-clause case child, a bare `block`, an undefined mixin call) rendered
  markup invented from a keyword: `when 1` became `<when>1</when>`.
- **S30** — sixteen more. Malformed `each` heads compiled to empty loops
  (`each b xs`, missing `in`, bound the collection to `nil`); bare `if` and
  `case` became dead branches; `p=`, `p!=`, `#{}` and `!{}` with no
  expression died as bare `NullPointerException`s carrying no class, no
  message, no line and no column.

The golden corpus was green throughout both.

## Shape

Every axis is the inverse of the golden corpus:

| golden corpus | diagnostics corpus |
|---|---|
| legal templates | **illegal** templates |
| compares output bytes | compares **error class + position** |
| green = correct rendering | green = correct **rejection** |
| a case that errors is a bug | a case that **compiles** is a bug |

A case is `<name>.carlin` plus `<name>.edn`:

```clojure
{:class :each-expected-in :line 2 :col 9 :data {:found on}}
```

- `:class` — the `:carlin/error` keyword. Required.
- `:line` / `:col` — **part of the contract, not decoration.** A class that
  merely defaulted to line 1 would pass a class-only check while being
  useless to the author, so position is asserted on every case.
- `:data` — optional ex-data keys that must match (e.g. which mixin, which
  keyword was found instead of `in`).
- `:fixture` — a named resolver from the runner, for the include/extends
  cases a template cannot express alone.
- `:entry :compile-ref` with `:ref` — for errors raised before parsing.

Message **prose is never asserted** (§8.3): wording may evolve freely while
the data contract holds.

## Running

```
bb diagnostics        # ratchet: manifest cases MUST keep failing correctly
bb dbaseline          # rewrite diagnostics-manifest.edn from reality
bb dshow <case>       # source, expectation, actual, verdict
```

The runner also **renders** each case, not merely compiles it: a few classes
(`:unsupported-js-value`, merge-attrs conflicts) are raised at render time,
so a case that survives compilation alone is not yet proven rejected.

## Coverage

Authored from an audit of every `:carlin/error` class raised anywhere in
`src/` — the audit handoff rev. 21 §6 item 3 asked for. At the time of
writing 40 classes exist and 8 of them had **no pin at all**
(`:extends-raw`, `:include-cycle`, `:raw-root`, `:dangling-clause`,
`:each-missing-coll`, `:unknown-filter`, `:unsupported-js-value`,
`:unsupported-construct`). All but the last were probed reachable and are
now covered here or in the unit suite.

Three classes are **deliberately uncovered**, being internal-invariant
assertions rather than user errors — unreachable by construction, and
positioned only so that the day they fire they say where:

- `:unsupported-construct` — a node type reaching `gen` with no branch.
- `:extends` — a surviving `extends` node inheritance-merge should have folded.
- `:not-implemented` — the CLJS platform branches, pending the matrix.

## Adding a case

Probe first, then pin — a pin is only as good as its probe (rev. 18).
Write the template, run `bb dshow <case>` to see what carlin *actually*
raises, and only then write the `.edn`. If the actual differs from what you
expected, decide which is wrong before touching either: `unresolvable-ref`
was authored expecting column 3 and carlin answered column 11, which turned
out to be correct — `:ref-pos` is computed precisely so the caret lands on
the unresolvable ref rather than on the `include` keyword.
