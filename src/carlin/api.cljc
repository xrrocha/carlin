(ns carlin.api
  "The spec (§5) API surface — carlin's public front door.

  This namespace began as the SEAM between the battery and a half-built
  implementation, adapting the quarantined pre-refactor engine so the corpus
  could stay baselined while the real passes landed one at a time. That job
  is finished: legacy is retired (S29) and nothing is adapted any more.

  It is kept, rather than folded into carlin.core, because it is the surface
  the spec NAMES in §5 and the one three consumers already import (the
  harness, two spec suites, bin/render). A seam that adapts nothing is still
  a useful front door: it keeps §5's names where §5 says they are, and it
  holds `render` — which belongs to neither the parser nor the code
  generator — in the one place a caller looks.

  Contract (carlin-spec.md §5):
    (compile-template source opts) -> {:fn (fn [model env] hiccup)
                                       :code <form>
                                       :doctype string-or-nil
                                       :mode :html|:xml
                                       :symbols #{...}
                                       :deps #{...}}
    (compile-ref ref opts)         -> same, root pulled through (:resolver opts)
    (render compiled model env)    -> html string
  opts: :name :resolver :filters :mode :raw-text-tags :on-attr-conflict :eval"
  (:require [carlin.core :as core]
            [carlin.platform :as platform]
            [carlin.codegen :as codegen]
            [carlin.runtime :as rt]))

(defn compile-template
  "Source → compiled artifact. ONE engine, ONE outcome (S29): the front half
  (cursor, positioned tree, structural diagnostics, include-splice,
  clause-attachment, :deps) runs, then carlin.codegen compiles the whole
  tree. Anything neither half can handle is a positioned :carlin/error that
  propagates to the caller — carlin fails fast at compile time rather than
  rendering something questionable.

  Until S29 this function was a SEAM: codegen could throw :carlin/defer and
  the whole template was silently re-compiled on carlin.legacy, the frozen
  pre-refactor engine. That fallback is gone along with legacy.clj — by then
  nothing legal deferred, and the only templates still reaching it were
  malformed ones it rendered as invented markup. `:engine :carlin` is
  retained in the returned map, now a constant, so callers that branched on
  it keep working."
  [source opts]
  (let [{:keys [tree deps cursor]} (core/parse source opts)
        deps (cond-> (set deps) (:name opts) (conj (:name opts)))]
    (-> (codegen/compile-tree tree opts cursor)
        (assoc :deps deps :engine :carlin))))

(defn compile-ref
  [ref {:keys [resolver] :as opts}]
  (let [{:keys [key source kind]} (or (resolver nil ref)
                                      (throw (ex-info "cannot resolve root ref"
                                                      {:carlin/error :unresolvable-ref :ref ref})))]
    (when (= kind :raw)
      (throw (ex-info "root template cannot be :raw" {:carlin/error :raw-root :key key})))
    (compile-template source (assoc opts :name key))))

#?(:clj
   (defmacro deftemplate
     "Compile a template at MACROEXPANSION time and def `name` to the
  artifact — the cross-platform workhorse of §5.1, and CLJS's primary mode.
  The generated `(fn [model env] …)` is compiled by the ordinary
  Clojure/ClojureScript compiler: full speed, no interpreter, and a free AOT
  story (§5.2's `:code`-as-data is mechanically what this emits).

      (deftemplate page \"page.carlin\" {:resolver r})   ; a ref
      (deftemplate page \"h1= title\" {:source? true})   ; literal source

  S31 — WHERE TEMPLATE EXPRESSIONS RESOLVE, and why this is not a symbol
  rewrite. `:code` contains unqualified symbols the AUTHOR wrote — `count`,
  `str`, `raw`, `inc` — which carlin deliberately passes through as user
  data (rev. 4 hygiene). Carlin's own emitted structure is fully qualified
  (`clojure.core/fn`, `carlin.runtime/raw`), so those bare names are the
  template's vocabulary, not carlin's.

  Under `platform/evaluate` they resolve because `*ns*` is bound to
  `template-ns`. A macro has no such binding: it expands in the CALLER's
  namespace. Emitting `:code` there would resolve `count` against whatever
  the caller's `ns` form means by `count` — and a namespace that defines its
  own renders `[:p :MY-COUNT]` where the template says `[:p 3]`. Silent
  wrong output from a declaration made in another file for unrelated
  reasons; S15 exactly one position over, and the rev. 13 lesson on the nose.

  The fix that suggests itself — qualify author symbols at codegen time — is
  the one §8.2 already REJECTED, twice and by name: ambient vocabulary
  belongs to namespace mechanics, user names stay user data, and *lexical
  shadowing keeps working*. That last clause is load-bearing and is what
  kills the rewrite: authors legitimately bind core names
  (`each count in xs`, `(let [count 99] count)`, `(fn [inc] …)`), all of
  which render correctly today. A textual qualifier has no scope tracking,
  so it would rewrite those bindings too; making it safe means a
  scope-tracking analyzer over arbitrary author Clojure, whose subtle
  failures are — again — silent wrong output.

  So the vocabulary travels by DECLARATION instead. The template is emitted
  into its own namespace, whose `ns` form refers exactly what `template-ns`
  refers: clojure.core plus `platform/template-vocabulary`. Same vocabulary,
  same guarantee, no rewriting — and it works identically on CLJS, where
  namespaces are a compile-time construct and `ns-resolve` does not exist.
  §5.2 already named this mechanism: spitting generated namespaces is
  \"mechanically the same thing `deftemplate` does\".

  The portability this buys is exactly what it says: `:code` is self-
  contained relative to a namespace carrying carlin's vocabulary — which
  carlin now declares rather than inheriting from the caller's accident.

  `opts` is evaluated at macroexpansion (it must be: the resolver has to run
  to read the template), so it must be a compile-time-constant form. Pass
  `:source? true` to treat the second argument as literal source instead of
  a ref."
     [name ref-or-source opts]
     (let [o        (eval opts)
           compiled (if (:source? o)
                      (compile-template ref-or-source (dissoc o :source?))
                      (compile-ref ref-or-source o))
           vocab    (:vocabulary compiled)
           ;; The borrowed vocabulary, bound explicitly around the code:
           ;;   [count clojure.core/count, raw carlin.runtime/raw]
           ;; Only names the template actually references, so an author's
           ;; own `each count in xs` or `(let [count 99] …)` is untouched —
           ;; a locally bound name is not free in the emitted form and so
           ;; never appears here. §8.2's shadowing clause is preserved.
           bindings (into [] (mapcat (fn [[s q]] [s q])) (sort-by key vocab))]
       `(do
          ;; Every artifact key except :fn is DATA and must be emitted as
          ;; such. Splicing them live would evaluate them — `:code` is a
          ;; form full of the author's free symbols, and `:symbols` a set of
          ;; them, so an unquoted splice tries to resolve `title` at the
          ;; macro site and dies there. Quote the data; build only :fn.
          (def ~name
            {:code       '~(:code compiled)
             :vocabulary '~vocab
             :doctype    ~(:doctype compiled)
             :mode       ~(:mode compiled)
             :symbols    '~(:symbols compiled)
             :deps       '~(:deps compiled)
             :engine     :carlin
             ;; The host compiler compiles this — no evaluate, no
             ;; interpreter — and the let supplies the vocabulary that
             ;; `evaluate` would otherwise have supplied through *ns*.
             :fn         (let ~bindings ~(:code compiled))})))))

(defn render
  "Compiled artifact + model + env -> HTML string, through CARLIN'S OWN
  serializer (§6.4, step 3). The doctype selects the profile (§7.2): its
  value picks :html vs :xml, and — the three-way the corpus pins — only an
  html doctype is terse, so a template declaring no doctype emits <br/>
  rather than <br>. :mode overrides the profile, never terseness."
  ([compiled model] (render compiled model {}))
  ([compiled model env]
   (let [dt      (:doctype compiled)
         profile (rt/doctype-mode dt)
         profile (cond-> profile (:mode compiled) (assoc :mode (:mode compiled)))]
     (str (rt/doctype-string dt)
          (rt/render-hiccup ((:fn compiled) model env) profile)))))
