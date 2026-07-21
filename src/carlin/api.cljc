(ns carlin.api
  "The spec (§5) API surface, as the test battery consumes it.

  This namespace is the SEAM between the battery and the implementation.
  Today it adapts the quarantined pre-refactor implementation (carlin.legacy)
  so the corpus stays baselined; as the real passes land, each legacy/
  delegation below is replaced — the live map of what's left — and the
  battery itself never changes.

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
            [carlin.runtime :as rt]
            [carlin.legacy :as legacy]))

(defn compile-template
  [source opts]
  ;; The real front half always runs: cursor, positioned tree, structural
  ;; diagnostics, include-splice, clause-attachment, :deps. The back half is
  ;; feature-by-feature (S2-S4): carlin.codegen compiles the whole template
  ;; or throws :carlin/defer, and ONLY then does the seam bail out wholesale
  ;; to the quarantined legacy engine on the ORIGINAL source. Genuine compile
  ;; errors (:carlin/error) always propagate; outputs are never mixed.
  (let [{:keys [tree deps]} (core/parse source opts)
        deps (cond-> (set deps) (:name opts) (conj (:name opts)))]
    (try
      (-> (codegen/compile-tree tree opts)
          (assoc :deps deps :engine :carlin))
      (catch clojure.lang.ExceptionInfo e
        (when-not (:carlin/defer (ex-data e)) (throw e))
        (let [c (legacy/compile-template source)]
          {:fn (:fn c)
           :code (:code c)
           :doctype (when (:doctype? c) "html")
           :mode :html
           :symbols (let [p (first (second (:code c)))]     ; [{:keys [...] :as model} env]
                      (set (when (map? p) (:keys p))))
           :deps deps
           :engine :legacy})))))

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
