(ns carlin.platform
  "The four-function porting surface (spec §8.2): read-form-at, evaluate,
  resolve-source, known-symbol?. JVM/bb variants here; the CLJS branches land
  with the matrix activation (handoff §5.5). edamame is the reader everywhere —
  it is what makes multi-line forms, dot-block interpolation spans, and
  expression-level error positions one mechanism.

  `read-form-at` returns positions in TEMPLATE coordinates: the caller hands it
  a suffix of the source plus the template (row, col) where that suffix begins,
  and gets back the form and the exclusive end position (where the reader
  stopped). Reader failures are classified:

    :unterminated-form — EOF with an unclosed delimiter; positioned at the
                         OPENING delimiter (stable, meaningful — spec §8.3)
    :reader-error      — anything else the reader rejects

  both thrown as ex-info with {:carlin/error class :line n :col n} in template
  coordinates. carlin.core decides which of these to propagate where (strict in
  restrictive-four positions, lenient in directive heads while the step-1
  scanner stands in for the full grammar)."
  (:require [edamame.core :as e]
            #?(:clj [clojure.tools.reader.reader-types :as rt])))

(def ^:private reader-opts
  ;; :all covers quote/deref/fn/syntax-quote/var/read-cond; :regex is separate.
  ;; :read-eval stays off: templates are data until carlin itself evaluates.
  {:all true :regex true :read-eval false})

(def ^:private eof-sentinel ::e/eof)

(defn- rebase
  "Reader-local (row, col) -> template coordinates, given that reader row 1
  starts at template (base-row, base-col)."
  [base-row base-col row col]
  {:line (+ base-row (dec row))
   :col  (if (= 1 row) (+ base-col (dec col)) col)})

(defn read-form-at
  "Read exactly ONE Clojure form from `text`, a suffix of the template whose
  first character sits at template (row, col). Returns
    {:form f, :end-line n, :end-col n}   (end = exclusive, template coords)
  or {:eof true} when `text` holds nothing but whitespace.
  Throws classified, template-positioned ex-info on reader failure (see ns doc)."
  [text row col]
  #?(:clj
     (try
       (let [rdr  (e/reader text)
             form (e/parse-next rdr reader-opts)]
         (if (= eof-sentinel form)
           {:eof true}
           (let [{:keys [line col]} (rebase row col
                                            (rt/get-line-number rdr)
                                            (rt/get-column-number rdr))]
             {:form form :end-line line :end-col col})))
       (catch clojure.lang.ExceptionInfo ex
         (let [d (ex-data ex)]
           (if (= :edamame/error (:type d))
             ;; S30 — an OPENED-delimiter location means a delimiter was left
             ;; unclosed, which is :unterminated-form. But edamame also
             ;; supplies this key for an unmatched CLOSER (`}` with nothing
             ;; open, as an empty `#{}` interpolation produces) — and there
             ;; it carries nil :row/:col, because no delimiter was ever
             ;; opened to point at. `rebase` then `dec`ed nil and the whole
             ;; compile died as a bare NullPointerException with no class,
             ;; message or position. Requiring a non-nil row is what
             ;; distinguishes the two: a real unterminated form always knows
             ;; where it opened, and an unmatched closer falls through to
             ;; :reader-error below, which has defended its coordinates with
             ;; `or` since it was written.
             (if-let [{r :row c :col} (let [loc (:edamame/opened-delimiter-loc d)]
                                        (when (:row loc) loc))]
               (throw (ex-info (ex-message ex)
                               (merge {:carlin/error :unterminated-form}
                                      (rebase row col r c))))
               (throw (ex-info (ex-message ex)
                               (merge {:carlin/error :reader-error}
                                      (rebase row col (or (:row d) 1) (or (:col d) 1))))))
             (throw ex)))))
     :cljs
     (throw (ex-info "read-form-at: CLJS branch lands with the matrix activation"
                     {:carlin/error :not-implemented}))))

#?(:clj
   (def ^:private template-ns
     "The namespace template expressions resolve and evaluate in (S15).
  Refers clojure.core plus exactly two carlin.runtime helpers — `raw` and
  `->js`, the spec's own expression-position idioms (§3.5/D5, §6.3) — making
  them ambient vocabulary through ordinary namespace mechanics rather than
  reserved words rewritten by codegen (user names stay user data, rev. 4
  hygiene; lexical shadowing still works). evaluate and known-symbol? both
  use it, so free-symbol analysis and evaluation agree by construction —
  and templates no longer resolve against whatever *ns* the caller happens
  to be in, which was fragility, not a feature."
     (let [ns-sym 'carlin.template-env]
       (or (find-ns ns-sym)
           (let [n (create-ns ns-sym)]
             (binding [*ns* n]
               (clojure.core/refer 'clojure.core)
               (require 'carlin.runtime)
               (clojure.core/refer 'carlin.runtime :only '[raw ->js]))
             n)))))

(defn evaluate
  "Evaluate a form per the platform's default strategy (spec §8.2), inside
  the template namespace (S15). The sci backend arrives as an opt-in :eval
  strategy later; this is the plain path."
  [form]
  #?(:clj  (binding [*ns* template-ns] (eval form))
     :cljs (throw (ex-info "evaluate: use the deftemplate macro path or sci on CLJS"
                           {:carlin/error :not-implemented}))))

(defn resolve-source
  "Ask the caller's resolver (spec §5.2) for `ref` as seen from `from`.
  Returns {:key :source :kind} or nil. A nil resolver resolves nothing."
  [resolver from ref]
  (when resolver (resolver from ref)))

(def template-vocabulary
  "The vocabulary template expressions resolve against (S15), as DATA:
  clojure.core, plus exactly these names from carlin.runtime.

  One fact, read from two places. `template-ns` above refers these at
  runtime for the `evaluate` path; `carlin.api/deftemplate` (S31) emits an
  `ns` form declaring the same ones for the macro path. Stating it once is
  the point — S15's guarantee is that analysis and evaluation agree *by
  construction*, and two hand-maintained lists of the same two symbols is
  precisely the drift the rev. 13 lesson warns about (one scanner living in
  two places). Add a name to carlin's ambient vocabulary here, or the two
  paths silently disagree about what a template may say."
  '[raw ->js])

(defn qualify
  "The namespace-qualified symbol `sym` resolves to in the template namespace,
  or nil if it resolves to nothing there.

  S31's portability seam. `deftemplate` cannot bind `*ns*` around the code it
  emits, so it binds the template's borrowed vocabulary explicitly —
  `(let [count clojure.core/count, raw carlin.runtime/raw] …)` — and needs
  the right-hand side of each pair. Resolution happens HERE, against
  `template-ns`, so the macro path and the `evaluate` path answer from the
  same surface: S15's agree-by-construction guarantee, extended to a second
  consumer rather than reimplemented beside it.

  This is a lookup, NOT the codegen symbol-rewriting §8.2 rejected: nothing
  in `:code` is altered. The author's `count` stays the symbol `count`; only
  the binding that gives it meaning is made explicit at the point of use.

  MACROS ANSWER nil, deliberately. A macro cannot be `let`-bound — binding
  one is `Can't take value of a macro`, a hard compile failure — and it does
  not need to be: a macro in operator position is expanded by the compiler
  at the call site, before any runtime binding could matter. Templates do
  reach macros (`when`, `cond`, `->`, and `let` itself in an author's code
  block), so this is a live case, not a hypothetical. They are excluded here
  rather than at the call site so that every consumer of `qualify` inherits
  the exclusion instead of rediscovering it — the rev. 13 lesson, applied
  before rather than after.

  UNVERIFIED ON CLJS, and this is the known weak point of S31 (handoff
  rev. 23 §9 item 1). `template-ns` is a JVM Clojure namespace, so this
  answers `clojure.core/count` — correct for JVM and bb, and quite possibly
  WRONG when `deftemplate` is macroexpanding for a ClojureScript target,
  where the name wanted is `cljs.core/count`. The macro runs on the JVM
  inside the CLJS compiler's process, so nothing here fails loudly; it would
  simply emit a binding to the wrong namespace. If that proves out, this
  function needs the compilation target as an input and `:vocabulary`
  becomes target-dependent. Nobody has run it. Do not assume the answer from
  the fact that the JVM tests pass — that is precisely the evidence that
  cannot distinguish the two cases."
  [sym]
  #?(:clj  (when-let [v (ns-resolve template-ns sym)]
             (when (and (var? v) (not (:macro (meta v))))
               (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))))
     :cljs nil))

(defn known-symbol?
  "Is `sym` a known global (not a model key)? Free-symbol analysis (spec §2)
  consults this; unresolvable symbols are destructured from the model.
  Resolution happens in the template namespace (S15), the same surface
  evaluate uses — so `raw` and `->js` are known, and the answer no longer
  depends on the caller's ambient *ns*.

  S31 — THE CLJS BRANCH IS NOT A STUB ANY MORE, and the old `false` was
  actively dangerous rather than merely incomplete. Answering `false` for
  everything means every free symbol is taken for a model key, so `count`
  lands in the `{:keys [...]}` destructuring, binds to nil, shadows the real
  function, and the call dies as a bare NullPointerException with no message
  — the unclassified-failure signature §8.3 exists to abolish, on the very
  platform `deftemplate` is meant to serve first.

  CLJS has no runtime `ns-resolve`. Resolution there belongs at
  MACROEXPANSION, against the vocabulary the emitted `ns` form declares —
  which means it belongs to `deftemplate` (running on the JVM, in the CLJS
  compiler's own process, where `ns-resolve` and the analyzer both exist),
  not to a runtime call in the browser. So the CLJS branch of *this*
  function is now a classified error rather than a silent `false`: reaching
  it means someone is doing free-symbol analysis at CLJS runtime, which is
  the `:eval` path, and that path needs sci (still open) rather than a
  wrong answer.

  Not left as `false` with a TODO: `false` is not neutral here, it is the
  specific wrong answer that turns `count` into a nil-bound model key. An
  error says so; `false` renders confidently broken output."
  [sym]
  #?(:clj  (some? (ns-resolve template-ns sym))
     :cljs (throw (ex-info "known-symbol?: CLJS runtime analysis needs the sci :eval strategy; the deftemplate path resolves at macroexpansion instead"
                           {:carlin/error :not-implemented :symbol sym}))))
