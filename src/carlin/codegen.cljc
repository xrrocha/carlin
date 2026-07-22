(ns carlin.codegen
  "The back half, feature by feature (S2–S6): the spliced, clause-attached
  tree from carlin.core → {:code :fn :doctype :mode :symbols}, one
  (fn [model env] hiccup) per template.

  DEFERRAL CONTRACT: on meeting a construct outside the implemented set this
  namespace throws ex-info carrying :carlin/defer — the seam's cue to bail
  out WHOLESALE to carlin.legacy for that template. Deferral is never used
  for genuine compile errors (those carry :carlin/error and propagate), and
  outputs are never mixed: one template, one engine. Currently deferred:
  filters, extends and named blocks (inheritance-merge), dynamic tags,
  &attributes, shorthand/anatomy on mixin calls beyond a plain attrs map.

  SERIALIZATION (step 3, landed): emitted code targets hiccup DATA with
  carlin.runtime/raw markers, and carlin.runtime/render-hiccup prints it —
  carlin owns its serializer (§9, Q1). hiccup2 reverts to what it was always
  meant to be: a JVM differential-test oracle (§11). Escaping, attribute
  order, void/self-closing behavior and profiles are all carlin's now.

  Text semantics mirror the goldens (and legacy where legacy is
  golden-correct): #{expr} escaped interpolation, !{expr} raw, #[tag …]
  nested-tag interpolation, \\-escape to render any of those literally;
  consecutive piped-text lines join with newline; dot blocks on
  :raw-text-tags (default #{:script :style}, Q5) emit raw with raw
  interpolation, on other tags escaped with normal interpolation; rendered
  block comments emit verbatim (Q12)."
  (:require [clojure.string :as str]
            [carlin.core :as core]
            [carlin.platform :as platform]
            [carlin.runtime :as rt]))

(defn- defer! [construct node]
  (throw (ex-info (str "carlin: " (name construct) " deferred to legacy")
                  {:carlin/defer construct :pos (:pos node)})))

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

(defn- matching-bracket
  "Index of the ] closing an already-open [ at the front of s."
  [^String s]
  (loop [i 0, depth 0]
    (when (< i (count s))
      (let [c (nth s i)]
        (cond
          (= c \[) (recur (inc i) (inc depth))
          (= c \]) (if (zero? depth) i (recur (inc i) (dec depth)))
          :else    (recur (inc i) depth))))))

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

(defn- text-node? [n] (= :text (:type n)))

(defn- children-code
  "Children → code vector; consecutive piped-text siblings join with a
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
                (when-not (#{:when :default} (:type k)) (defer! :case-clause k)))
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
    (when (= ::absent arity) (defer! :undefined-mixin node)) ; may live in an unmerged layout
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
                      (text-code ctx :escaped (:text node) (:pos node)))
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
    :when           (defer! :stray-when node)
    :default        (defer! :stray-default node)
    :mixin-def      nil                                      ; hoisted separately
    :mixin-call     (mixin-call-code ctx node)
    :block          (if (and (:in-mixin? ctx) (nil? (:name node)))
                      `(when ~'block (~'block))              ; the yield point (Q10)
                      (defer! :inheritance node))
    :extends        (defer! :inheritance node)
    :filter         (filter-code ctx node)
    (defer! type node)))

;; ---------------------------------------------------------------------------
;; mixin hoisting (Q10/Q14): letfn, top level, `attributes` and `block` bound

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
  caller). Throws :carlin/defer for constructs outside the implemented set."
  [tree opts]
  (let [ctx     {:raw-text-tags (or (:raw-text-tags opts) #{:script :style})
                 :policy (or (:on-attr-conflict opts) :error)
                 :filters (rt/filter-registry (:filters opts))
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
     :mode    :html
     :symbols (set params)}))
