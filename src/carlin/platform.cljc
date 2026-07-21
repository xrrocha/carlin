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
             (if-let [{r :row c :col} (:edamame/opened-delimiter-loc d)]
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

(defn known-symbol?
  "Is `sym` a known global (not a model key)? Free-symbol analysis (spec §2)
  consults this; unresolvable symbols are destructured from the model.
  Resolution happens in the template namespace (S15), the same surface
  evaluate uses — so `raw` and `->js` are known, and the answer no longer
  depends on the caller's ambient *ns*."
  [sym]
  #?(:clj  (some? (ns-resolve template-ns sym))
     :cljs false))
