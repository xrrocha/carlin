(ns carlin.codegen
  "The back half, feature by feature (S2–S6): the spliced, clause-attached
  tree from carlin.core → {:code :fn :doctype :mode :symbols}, one
  (fn [model env] hiccup) per template.

  ERROR CONTRACT (S29): every construct this namespace cannot compile is a
  positioned `:carlin/error` raised through carlin.cursor/fail!, exactly
  like the front half's diagnostics. There is no second engine and no
  bail-out: fail fast at compile time, always.

  This REPLACES the deferral contract that stood until S29. Deferral existed
  while carlin.legacy was still a working fallback: codegen threw
  :carlin/defer, and the seam silently re-compiled the whole template on the
  old engine. Once every legal construct compiled here, the only templates
  still reaching legacy were MALFORMED ones — and legacy rendered them as
  markup invented from a keyword (`when 1` outside a case became
  `<when>1</when>`, an undefined `+nope` became the literal text `+nope`)
  where pug 3.0.2 raises an error on every one. Silently emitting bogus
  markup for input the compiler has already recognized as broken is the
  \"grossly unexpected\" outcome the §7 lossiness rule exists to forbid, so
  the five reachable paths became errors and legacy was retired:

    :stray-when      `when` outside a case
    :stray-default   `default` outside a case
    :case-clause     a non-when/default child of a case
    :undefined-mixin a call to a mixin defined nowhere (see below)
    :anonymous-block an unnamed `block` outside a mixin

  A sixth, `:extends`, is unreachable from a well-formed tree — carlin.core
  folds inheritance before codegen — and is kept as a defensive assertion.
  `unsupported-construct` is the catch-all for a node type that reaches `gen`
  without a branch, which is an internal invariant failure rather than a
  user error, but is still reported positioned rather than as a raw NPE.

  SERIALIZATION (step 3, landed): emitted code targets hiccup DATA with
  carlin.runtime/raw markers, and carlin.runtime/render-hiccup prints it —
  carlin owns its serializer (§9, Q1). hiccup2 reverts to what it was always
  meant to be: a JVM differential-test oracle (§11). Escaping, attribute
  order, void/self-closing behavior and profiles are all carlin's now.

  Text semantics mirror the goldens: #{expr} escaped interpolation, !{expr} raw, #[tag …]
  nested-tag interpolation, \\-escape to render any of those literally;
  consecutive piped-text lines join with newline; dot blocks on
  :raw-text-tags (default #{:script :style}, Q5) emit raw with raw
  interpolation, on other tags escaped with normal interpolation; rendered
  block comments emit verbatim (Q12)."
  (:require [clojure.string :as str]
            [carlin.core :as core]
            [carlin.cursor :as cur]
            [carlin.platform :as platform]
            [carlin.runtime :as rt]))

(defn- fail!
  "The ONE error constructor for the back half (S29), delegating to
  carlin.cursor/fail! so a codegen error is indistinguishable from a parse
  error in shape: same {:carlin/error :key :line :col} data, same caret
  excerpt. `ctx` carries the cursor of the template being compiled; `node`
  supplies the position. Replaces the pre-S29 `defer!`, which threw an
  unpositioned :carlin/defer sentinel for the seam to catch."
  ([ctx class node] (fail! ctx class node nil))
  ([ctx class node extra]
   (cur/fail! (:cursor ctx) class (or (:pos node) {:line 1 :col 1}) extra)))

;; ---------------------------------------------------------------------------
;; text scanning: #{expr} !{expr} #[tag …], with backslash escapes

(defn- consumed-chars
  "How many characters of `text` a read consumed, given read-form-at's
  end position when called with base (1,1)."
  [text {:keys [end-line end-col]}]
  (if (= 1 end-line)
    (dec end-col)
    (let [lines (str/split text #"\n" -1)]
      (+ (reduce + (map #(inc (count %)) (take (dec end-line) lines)))
         (dec end-col)))))

(def ^:private matching-bracket
  "Index of the ] closing an already-open [ at the front of s. STRING-AWARE.
  Moved to carlin.core at S27 so the parse-time fragment hoist and this
  render path share ONE scanner; kept here as an alias so the reading order
  of this namespace survives."
  core/matching-bracket)

(defn- scan-text
  "Text → pieces: strings | {:expr form} | {:raw form} | {:node n}.
  `pos` approximates error positions (interpolation reads rebased to the
  node, not the exact character — refine when positions matter more)."
  [text pos]
  (loop [s text, pieces []]
    (let [hit (->> [["#{" :expr] ["!{" :raw] ["#[" :node]]
                   (keep (fn [[tok kind]]
                           (when-let [i (str/index-of s tok)]
                             [i tok kind])))
                   sort
                   first)]
      (if-not hit
        (cond-> pieces (pos? (count s)) (conj s))
        (let [[i tok kind] hit]
          (if (and (pos? i) (= \\ (nth s (dec i))))
            ;; \-escaped: emit literally, drop the backslash
            (recur (subs s (+ i 2))
                   (conj pieces (str (subs s 0 (dec i)) tok)))
            (let [lead   (subs s 0 i)
                  pieces (cond-> pieces (pos? (count lead)) (conj lead))
                  after  (subs s (+ i 2))]
              (if (= kind :node)
                (let [j (or (matching-bracket after)
                            (throw (ex-info "unclosed #[ interpolation"
                                            (merge {:carlin/error :unterminated-form}
                                                   pos))))]
                  (recur (subs after (inc j))
                         (conj pieces {:node (core/parse-inline-fragment
                                              (subs after 0 j))})))
                (let [r    (platform/read-form-at after 1 1)
                      n    (consumed-chars after r)
                      rest* (str/replace-first (subs after n) #"^\s*\}" "")]
                  (recur rest* (conj pieces {kind (:form r)})))))))))))

;; ---------------------------------------------------------------------------
;; pieces → code, per escaping context

(declare gen)

(defn- piece->code
  "context: :escaped (normal text) — statics VERBATIM (§4.1 rev. 7: escaping
  is a property of the dynamic boundary; literal template text is the
  author's own characters, hand-authored entities survive), #{} escaped by
  the serializer; :all-raw (raw-text dot blocks, Q5) — everything raw, #{}
  included (§3.4 rev. 7: :raw-text-tags governs interpolation only, statics
  being verbatim everywhere anyway). The former :static-raw context (literal
  markup lines) is now indistinguishable from :escaped and was folded in."
  [ctx context p]
  (cond
    (string? p) `(rt/raw ~p)
    (:expr p)   (case context
                  :escaped (:expr p)
                  :all-raw `(rt/raw (str ~(:expr p))))
    (:raw p)    `(rt/raw (str ~(:raw p)))
    (:node p)   (gen ctx (:node p))))

(defn- text-code [ctx context text pos]
  (when text
    (let [codes (mapv #(piece->code ctx context %) (scan-text text pos))]
      (case (count codes)
        0 nil
        1 (first codes)
        (cons `list codes)))))

;; ---------------------------------------------------------------------------
;; node → code

(defn- splice [codes]
  (case (count codes)
    0 nil
    1 (first codes)
    (cons `list codes)))

(defn- text-node?
  "A node whose rendering is a text line: piped text, or a literal markup
  line (pug 3.0.2, probed 2026-07-22: consecutive text-html lines join with
  newlines exactly as piped lines do). Verbatim raw-include splices carry
  whole files and stay out of the line-join game."
  [n]
  (or (= :text (:type n))
      (and (= :literal-html (:type n)) (not (:verbatim? n)))))

(defn- children-code
  "Children → code vector; consecutive text-line siblings join with a
  newline (pug text-block semantics — the goldens' line breaks)."
  [ctx node]
  (let [kids (:children node)]
    (into []
          (comp (map-indexed
                 (fn [i k]
                   (let [c (gen ctx k)]
                     (if (and (pos? i) (text-node? k) (text-node? (nth kids (dec i))))
                       (list `list "\n" c)
                       c))))
                (remove nil?))
          kids)))

(defn- body-code [ctx node]
  (splice (children-code ctx node)))

(defn- thread-class-order
  "Ruling 3 — §4.6's source-order doctrine one level down: class tokens
  accumulate in TEXTUAL source order. When shorthand classes and a map
  :class coexist, split the shorthands around the map's position (the
  parser records :classes-before-attrs) and fold the trailing ones after
  the map's own value: a.foo{:class \"bar\"}.baz → class=\"foo bar baz\".
  Applied before any consumer reads :classes or :attrs; downstream
  machinery (keyword suffix, shorthand-map, render-attrs' hoist,
  class-tokens' recursive flatten) then produces the right order
  unchanged. A bug fix under existing law (rev. 5), not a departure."
  [node]
  (let [classes (:classes node)
        attrs   (:attrs node)]
    (if (and (seq classes) (map? attrs) (contains? attrs :class))
      (let [n    (or (:classes-before-attrs node) (count classes))
            pre  (vec (take n classes))
            post (drop n classes)
            v    (:class attrs)]
        (assoc node
               :classes pre
               :attrs   (if (seq post)
                          (assoc attrs :class (vec (cons v post)))
                          attrs)))
      node)))

(defn- shorthand-suffix [node]
  (str (apply str (map #(str "#" (:name %)) (:ids node)))
       (apply str (map #(str "." %) (:classes node)))))

(defn- tag-keyword
  "Static names resolve at compile time; interpolated tag names (Q13) build
  the keyword — shorthand merged — at runtime. The parsed head of #{expr}
  is a reader SET holding the one expression.

  `shorthand?` false drops the #id/.class suffix: when &attributes is present
  merge-attrs owns every source (§4.6) and the shorthand must not ALSO ride
  in on the tag keyword, or classes would be counted twice."
  ([node] (tag-keyword node true))
  ([node shorthand?]
   (let [node (if shorthand? node (dissoc node :ids :classes))]
     (if (:dynamic? node)
       (let [form (let [f (:name node)] (if (set? f) (first f) f))]
         `(keyword (str (let [v# ~form] (if (ident? v#) (name v#) v#))
                        ~(shorthand-suffix node))))
       (keyword (str (or (:name node) "div") (shorthand-suffix node)))))))

(defn- shorthand-map
  "The tag's shorthand as merge-attrs' FIRST source (§4.6 source order):
  #id then .class. nil when the tag carries no shorthand."
  [node]
  (let [ids (map :name (:ids node))]
    (not-empty
     (cond-> {}
       (seq ids)            (assoc :id (last ids))
       (seq (:classes node)) (assoc :class (vec (:classes node)))))))

(defn- merged-attrs-code
  "attrs code for a tag or call carrying &attributes: every source through
  the runtime merge (§4.6), policy from :on-attr-conflict."
  [ctx node]
  `(rt/merge-attrs ~(:policy ctx) ~(shorthand-map node) ~(:attrs node)
                   ~(:amp-attrs node)))

(defn- dot-block-code [ctx node]
  ;; dot blocks on dynamic tags are never raw-text (Q13): HTML-awareness
  ;; needs a name known at compile time
  (let [raw? (and (not (:dynamic? node))
                  (contains? (:raw-text-tags ctx) (keyword (:name node))))]
    (text-code ctx (if raw? :all-raw :escaped)
               (:text (:dot-block node)) (:pos node))))

(defn- inline-content
  "The terminal tail of a tag or call, as content code (at most one)."
  [ctx node] (cond
                 (:buffered node)
                 (let [{:keys [form raw?]} (:buffered node)]
                   (if raw? `(rt/raw (str ~form)) form))

                 (:dot-block node)
                 (dot-block-code ctx node)

                 (:inline-text node)
                 (text-code ctx :escaped (:inline-text node) (:pos node))

                 (:expanded node)
                 (gen ctx (:expanded node))))

(defn- tag-attrs
  "The attrs map as emitted. An explicit source-level `/` rides in on
  runtime/self-close-key: the serializer consumes it and never emits it —
  the third leg of the void/self-closing three-way (§7.2, rev. 5)."
  [node]
  (let [a (:attrs node)]
    (cond-> a
      (:self-close? node) ((fnil assoc {}) rt/self-close-key true))))

(defn- tag-code [ctx node]
  (let [node   (thread-class-order node)
        amp?   (some? (:amp-attrs node))
        inline (inline-content ctx node)
        attrs  (if amp? (merged-attrs-code ctx node) (tag-attrs node))]
    (cond-> [(tag-keyword node (not amp?))]
      attrs (conj attrs)
      (some? inline) (conj inline)
      true (into (children-code ctx node)))))

(defn- comment-code [ctx node]
  (let [body (:text (:body node))]
    `(rt/raw ~(str "<!--" (:inline node)
                  (when body (str "\n" body "\n"))
                  "-->"))))

(defn- else-chain-code
  "An :if/:unless with its attached :else-chain (§3.7): nested (if …)s,
  the trailing :else-body as the final alternative."
  [ctx node]
  (let [test (if (= :unless (:type node))
               (list `not (:form node))
               (:form node))
        then (body-code ctx node)
        else (reduce (fn [alt clause]
                       (if (= :else-body (:type clause))
                         (body-code ctx clause)
                         (list 'if (:form clause) (body-code ctx clause) alt)))
                     nil
                     (reverse (:else-chain node)))]
    (list 'if test then else)))

(defn- each-code [ctx node]
  (let [coll (gensym "coll")
        for-form `(for [~(:binding node) ~coll] ~(body-code ctx node))]
    (if-some [ec (:else-children node)]
      `(let [~coll ~(:coll node)]
         (if (seq ~coll)
           ~for-form
           ~(splice (into [] (keep #(gen ctx %)) ec))))
      `(let [~coll ~(:coll node)] ~for-form))))

(defn- case-code
  "Q12: bound scrutinee compared with =, bodiless whens fall through to the
  next body, no match and no default → nil."
  [ctx node]
  (let [scrut (gensym "scrut")
        kids  (remove #(#{:comment :comment-silent} (:type %)) (:children node))
        _     (doseq [k kids]
                (when-not (#{:when :default} (:type k)) (fail! ctx :case-clause k)))
        clauses
        (loop [ks kids, pending [], out []]
          (if-let [k (first ks)]
            (case (:type k)
              :when
              (let [body (splice (cond-> (children-code ctx k)
                                   (:expanded k) (conj (gen ctx (:expanded k)))))]
                (if (some? body)
                  (recur (next ks) []
                         (conj out [(conj pending (:form k)) body]))
                  (recur (next ks) (conj pending (:form k)) out)))
              :default
              (recur (next ks) []
                     (conj out [:default
                                (splice (cond-> (children-code ctx k)
                                          (:expanded k) (conj (gen ctx (:expanded k)))))])))
            out))
        branches (mapcat (fn [[vals body]]
                           (if (= :default vals)
                             [:else body]
                             [(if (= 1 (count vals))
                                (list `= scrut (first vals))
                                (cons `or (map #(list `= scrut %) vals)))
                              body]))
                         clauses)]
    `(let [~scrut ~(:form node)]
       ~(if (seq branches) (cons `cond branches) nil))))

(defn- call-attributes
  "The `attributes` value a call passes (Q14): its literal attr map with
  shorthand ids/classes merged — classes additively (§4.6), a preexisting
  :class form joined as a collection."
  [node]
  (let [classes (:classes node)
        ids     (map :name (:ids node))
        attrs   (or (:attrs node) {})
        ;; class BEFORE id: the shorthand is written `.hello#world`, and the
        ;; merged map is a source whose order §4.6 preserves downstream
        attrs   (cond
                  (empty? classes) attrs
                  (contains? attrs :class)
                  (assoc attrs :class [(str/join " " classes) (:class attrs)])
                  :else (assoc attrs :class (str/join " " classes)))
        attrs   (cond-> attrs (seq ids) (assoc :id (last ids)))]
    (not-empty attrs)))

(defn- mixin-call-code [ctx node]
  (let [node  (thread-class-order node)
        arity (get (:mixins ctx) (:name node) ::absent)]
    ;; A call to a name defined NOWHERE. Fail fast, at compile time (S29).
    ;;
    ;; The comment that stood here until S29 read "may live in an unmerged
    ;; layout" — a hedge against this pass running before `extends` had been
    ;; folded, which would make a layout's mixin look absent and reject a
    ;; LEGAL template. That premise is stale, and was stale when written down:
    ;; §3.14 made carlin.core/resolve-template mutually recursive with
    ;; splice-includes, so by the time compile-tree sees a tree, inheritance
    ;; is merged base-upward, includes are spliced, and named blocks are
    ;; dissolved. Probed at S29: a mixin defined in a layout and called from
    ;; a child's block compiles and renders here; so does one arriving via
    ;; include; and a layout mixin called with the wrong arity is caught
    ;; EARLIER, by core/check-arity, positioned into the child. Nothing
    ;; unmerged survives to this point, so ::absent means genuinely absent.
    (when (= ::absent arity)
      (fail! ctx :undefined-mixin node {:mixin (:name node)}))
    (let [inline  (inline-content ctx node)
          content (into (if (some? inline) [inline] [])
                        (children-code ctx node))
          block   (when (seq content) `(fn [] ~(splice content)))
          args    (:args node)]                            ; arity exact (§3.13): no nil-fill
      `(~(:name node) ~(if (:amp-attrs node)
                          (merged-attrs-code ctx node)
                          (call-attributes node))
        ~block ~@args))))

(defn- filter-code
  "Filters are COMPILE-TIME text transformations (§3.12): the captured body
  is handed to each registered fn and the result becomes a raw text node.
  A chain applies RIGHT TO LEFT, so `:cdata:uglify-js` uglifies and then
  wraps. No interpolation inside — filters run before the model exists."
  [ctx node]
  (let [names (or (seq (:names node)) [(:name node)])
        text  (or (:text (:body node)) "")
        attrs (:attrs node)
        out   (reduce (fn [t nm]
                        (if-let [f (get (:filters ctx) nm)]
                          (str (f t attrs))
                          (throw (ex-info (str "unknown filter :" nm)
                                          (merge {:carlin/error :unknown-filter
                                                  :filter nm}
                                                 (:pos node))))))
                      text
                      (reverse names))]
    `(rt/raw ~out)))

(defn- gen [ctx {:keys [type] :as node}]
  (case type
    :doctype        nil
    :yield          nil ; unfed splice point renders nothing (§3.11, probed)
    :comment-silent nil
    :comment        (comment-code ctx node)
    :text           (or (text-code ctx :escaped (:text node) (:pos node)) "")
    :text-block     (text-code ctx :escaped (:text (:body node)) (:pos node))
    :literal-html   (if (:verbatim? node)
                      `(rt/raw ~(:text node))
                      ;; deeper-indented lines under a literal markup line
                      ;; are its children; pug (probed 2026-07-22) renders
                      ;; them newline-joined, indentation stripped — the
                      ;; parser already stripped it (content starts at the
                      ;; line's own head)
                      (let [own  (text-code ctx :escaped (:text node) (:pos node))
                            kids (children-code ctx node)]
                        (if (seq kids)
                          (splice (into [own] (mapcat (fn [c] ["\n" c])) kids))
                          own)))
    :buffered       (if (:raw? node) `(rt/raw (str ~(:form node))) (:form node))
    :code           (if (seq (:children node))
                      (if (:form node)
                        (concat (:form node) [(body-code ctx node)])
                        (body-code ctx node))
                      (:form node))
    :tag            (tag-code ctx node)
    :if             (else-chain-code ctx node)
    :unless         (else-chain-code ctx node)
    :each           (each-code ctx node)
    :case           (case-code ctx node)
    :when           (fail! ctx :stray-when node)
    :default        (fail! ctx :stray-default node)
    :mixin-def      nil                                      ; hoisted separately
    :mixin-call     (mixin-call-code ctx node)
    :block          (if (and (:in-mixin? ctx) (nil? (:name node)))
                      `(when ~'block (~'block))              ; the yield point (Q10)
                      ;; An unnamed `block` outside a mixin has no yield to
                      ;; bind to. Named blocks never arrive here — core's
                      ;; dissolve-blocks removes them, whether or not the
                      ;; template extends anything — so this is exactly
                      ;; pug's "Anonymous blocks are not allowed unless they
                      ;; are part of a mixin" (probed, pug 3.0.2).
                      (fail! ctx :anonymous-block node))
    ;; Unreachable from a well-formed tree: core folds `extends` before
    ;; codegen runs, so a surviving :extends node is an internal invariant
    ;; failure. Kept as a positioned assertion rather than deleted, so the
    ;; day it fires it says where.
    :extends        (fail! ctx :extends node)
    :filter         (filter-code ctx node)
    (fail! ctx :unsupported-construct node {:node-type type})))

;; ---------------------------------------------------------------------------
;; mixin hoisting (Q10/Q14): letfn, top level, `attributes` and `block` bound

(defn- positionalize-mixins
  "Pug redefinition is POSITIONAL, not last-wins: a call site uses the
  definition in force at its source position (`mixin.block-tag-behaviour`
  is the golden proof; rev. 4's 'last definition wins' premise measured
  wrong, S24). letfn wants one binding per name, so later same-name
  definitions get a __N suffix and every call — top-level or nested,
  including inside mixin bodies — is rewritten to the name current at its
  own source position. A definition sees itself under its new name, so
  self-recursion binds to the definition being written."
  [tree]
  (let [seen (volatile! {})
        nth* (volatile! {})]
    (letfn [(rewrite [n]
              (let [n (cond-> n
                        (and (= :mixin-call (:type n)) (:name n))
                        (as-> m (let [nm (get @seen (:name m) (:name m))]
                                  (cond-> (assoc m :name nm)
                                    (:call m) (assoc-in [:call :name] nm)))))
                    n (cond-> n (:expanded n) (update :expanded rewrite))]
                (update n :children #(mapv rewrite %))))
            (top [n]
              (if (and (= :mixin-def (:type n)) (:name n))
                (let [nm  (:name n)
                      k   (get (vswap! nth* update nm (fnil inc 0)) nm)
                      nm' (if (> k 1) (symbol (str nm "__" k)) nm)]
                  (vswap! seen assoc nm nm')
                  (rewrite (assoc n :name nm')))
                (rewrite n)))]
      (mapv top tree))))

(defn- hoist-mixins
  "Top-level :mixin-def nodes → letfn bindings. Each mixin becomes
  (fn name [attributes block & params] body): `attributes` is the call's
  attr map (or nil), `block` a thunk of the call's children (or nil), and
  the user's binding vector splices in — & variadics included. letfn gives
  mutual recursion between mixins for free."
  [ctx tree]
  (->> (filter #(= :mixin-def (:type %)) tree)
       ;; redefinition: the LAST definition wins (pug-faithful) — and letfn
       ;; (sci's in particular) refuses duplicate binding names outright
       (reduce (fn [m n] (assoc m (:name n) n)) {})
       vals
       (mapv (fn [n]
               (list (:name n)
                     (into ['attributes 'block] (:bindings n))
                     (body-code (assoc ctx :in-mixin? true) n))))))

;; ---------------------------------------------------------------------------
;; free-symbol analysis → model destructuring (§2)

(def ^:private reserved '#{model env attributes block})

(defn- interop-symbol? [sym]
  (let [n (name sym)]
    (or (str/starts-with? n ".") (str/ends-with? n "."))))

(defn- model-symbols
  "Free simple symbols in the emitted code, minus specials, interop, known
  globals (platform/known-symbol?), the four contextual reserved words, and
  gensyms. Over-collection of template-local bindings is deliberate and
  harmless: the local always shadows the destructured nil."
  [form]
  (->> (tree-seq coll? seq form)
       (filter simple-symbol?)
       (remove special-symbol?)
       (remove interop-symbol?)
       (remove #(str/includes? (name %) "__"))               ; gensym'd
       (remove #(str/ends-with? (name %) "#"))
       (remove reserved)
       (remove platform/known-symbol?)
       (distinct)))

;; ---------------------------------------------------------------------------
;; entry point

(defn compile-tree
  "Spliced, clause-attached tree → the compiled artifact
  {:code :fn :doctype :mode :symbols} (spec §5.2 shape, :deps added by the
  caller). Raises positioned :carlin/error (S29) for any construct it cannot
  compile; `cursor` is the compiling template's cursor, used to position
  those errors, and is required — without it a back-half error could not
  name a line.

  MIXIN-TABLE INVARIANT (S29, load-bearing — read before changing either
  side). Two mixin tables exist in this codebase and they are built by
  DIFFERENT walks:

    carlin.core/walk-checks  collects :defs RECURSIVELY, at every depth
    compile-tree (here)      collects :mixins from the TOP LEVEL only

  A recursive collector and a top-level collector that must agree is exactly
  the drift the rev. 13 lesson warns about — one scanner living in two
  places. They cannot diverge here, but only because of a THIRD guard in a
  different namespace: core's `:nested-mixin` check fails any `:mixin-def`
  at depth > 0. Every definition that survives parsing is therefore
  top-level by construction, and the two tables are necessarily equal.

  So the top-level filter below is not an optimization and not an
  assumption about typical templates — it is sound ONLY while
  `:nested-mixin` holds. If mixin definitions are ever admitted below top
  level (a plausible future relaxation, since pug's own scoping is looser),
  this table silently stops seeing them, `arity` reads ::absent for a
  perfectly legal call, and S29's :undefined-mixin rejects valid templates.
  Whoever relaxes `:nested-mixin` must make this walk recursive in the same
  commit. A diagnostics pin asserts `:nested-mixin` fires, so the guard this
  depends on cannot be removed silently."
  [tree opts cursor]
  (let [tree    (positionalize-mixins tree)
        ctx     {:cursor cursor
                 :raw-text-tags (or (:raw-text-tags opts) #{:script :style})
                 :policy (or (:on-attr-conflict opts) :error)
                 :filters (rt/filter-registry (:filters opts))
                 ;; TOP LEVEL ONLY — sound only while :nested-mixin holds.
                 ;; See the MIXIN-TABLE INVARIANT above before changing this.
                 :mixins (into {} (comp (filter #(= :mixin-def (:type %)))
                                        (map (juxt :name :arity)))
                              tree)}
        fns     (hoist-mixins ctx tree)
        body    (splice (into []
                              (comp (remove #(= :mixin-def (:type %)))
                                    (keep #(gen ctx %)))
                              tree))
        body    (if (seq fns) `(letfn [~@fns] ~body) body)
        doctype (some #(when (= :doctype (:type %))
                         (let [v (:value %)] (if (str/blank? v) "html" v)))
                      tree)
        params  (vec (model-symbols body))
        code    `(fn [{:keys ~params :as ~'model} ~'env] ~body)]
    {:code    code
     :fn      (platform/evaluate code)
     :doctype doctype
     ;; :mode carries the USER'S override only (§7.2: ':mode overrides the
     ;; profile'); when absent the doctype selects the profile at render.
     ;; The old hardcoded :html clobbered `doctype xml` back to the HTML
     ;; void table — <link>…</link> lost its children (the xml case).
     :mode    (:mode opts)
     :symbols (set params)}))
