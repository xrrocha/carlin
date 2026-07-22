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
