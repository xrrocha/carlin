(ns carlin.artifact-test
  "Spec §5.2 — the compiled artifact as a transparent VALUE. These suites pin
  the two keys that describe a template's relationship to its environment:
  `:symbols` (what the caller must supply) and `:vocabulary` (what carlin
  must supply).

  Neither key reaches the rendered bytes, which is exactly why they need
  pins here. The golden corpus compares OUTPUT — it was green through eight
  cases leaking gensyms into `:symbols`, and it would be green through a
  `:vocabulary` that named nothing at all. A corpus can only find defects in
  the population it samples (rev. 22), and the population it samples is
  rendered documents."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.api :as api]
            [carlin.platform :as platform]))

(defn- compiled [source]
  (api/compile-template source {:name "test.carlin"}))

(defn- syms [source] (:symbols (compiled source)))
(defn- vocab [source] (:vocabulary (compiled source)))

;; ---------------------------------------------------------------------------
;; S32 — :symbols holds model keys, and nothing else

(deftest gensyms-are-not-model-keys
  (testing "an `each` collection binding is codegen's own machinery — the
            author cannot see it, no caller could ever supply it, and §5.2
            calls this key `inferred model keys`"
    (let [s (syms "ul\n  each b in (:books model)\n    li= (:name b)")]
      (is (not-any? #(re-matches #"coll\d+" (name %)) s))
      (is (contains? s 'b))))
  (testing "a `case` scrutinee binding, likewise"
    (let [s (syms "case v\n  when 1\n    p A")]
      (is (not-any? #(re-matches #"scrut\d+" (name %)) s))
      (is (contains? s 'v))))
  (testing "nested loops mint one binding each and leak none of them"
    (let [s (syms "each a in xs\n  each b in ys\n    p= a")]
      (is (not-any? #(re-matches #"coll\d+" (name %)) s))))

  ;; THE MUTATION GUARD. The cheap fix for the above is to filter on the
  ;; NAME SHAPE — drop anything matching #"(coll|scrut)\d+". It passes every
  ;; assertion above and is wrong: it also eats model keys an author
  ;; legitimately wrote, turning a phantom key (harmless, the let shadows it)
  ;; into a MISSING one (the destructuring silently stops binding real data).
  ;; That is the rev. 12 species yet again — a name shape is a sentinel, and
  ;; it collides with values authors write. Codegen therefore excludes its
  ;; gensyms by IDENTITY, via the mint! ledger. Swap the ledger for a regex
  ;; and this test is what fails.
  (testing "model keys that merely LOOK like gensyms are still model keys"
    (let [s (syms "p= coll1\np= scrut2\np= coll\np= scrut")]
      (is (= '#{coll1 scrut2 coll scrut} s))))
  (testing "a gensym-shaped key survives alongside a real loop binding"
    (let [s (syms "each x in coll7\n  p= x")]
      (is (contains? s 'coll7)))))

;; ---------------------------------------------------------------------------
;; S31 — :vocabulary holds what carlin must supply

(deftest vocabulary-names-the-borrowed-globals
  (testing "ambient names the template references are reported with what
            they resolve to in template-ns — this is what lets deftemplate
            bind them without re-deriving the analysis"
    (is (= '{count clojure.core/count} (vocab "p= (count coll)")))
    (is (= '{raw carlin.runtime/raw} (vocab "p!= (raw x)"))))
  (testing "model keys are never vocabulary: the two sets are complements
            over the same candidate symbols"
    (let [c (compiled "p= (count coll)")]
      (is (= '#{coll} (:symbols c)))
      (is (= '#{count} (set (keys (:vocabulary c)))))
      (is (empty? (clojure.set/intersection
                    (:symbols c) (set (keys (:vocabulary c))))))))
  (testing "carlin's OWN emitted structure is already qualified (the rev. 12
            syntax-quote fix) and so never appears here — only names the
            AUTHOR wrote are borrowed"
    (is (not (contains? (vocab "ul\n  each b in xs\n    li= b") 'for)))
    (is (not (contains? (vocab "p x") 'fn))))

  ;; Macros cannot be let-bound — `(let [when clojure.core/when] …)` is a
  ;; hard "Can't take value of a macro". They also need no binding: a macro
  ;; in operator position is expanded by the compiler at the call site,
  ;; before any runtime binding could matter. platform/qualify excludes them
  ;; so every consumer inherits the exclusion rather than rediscovering it.
  (testing "macros are excluded from the vocabulary"
    (is (nil? (platform/qualify 'let)))
    (is (nil? (platform/qualify 'when)))
    (is (nil? (platform/qualify 'cond)))
    (is (nil? (platform/qualify '->)))
    (is (not (contains? (vocab "p= (let [y 5] y)") 'let))))
  (testing "functions are included — the whole point"
    (is (= 'clojure.core/count (platform/qualify 'count)))
    (is (= 'carlin.runtime/raw (platform/qualify 'raw)))
    (is (= 'carlin.runtime/->js (platform/qualify '->js))))
  (testing "a symbol that resolves to nothing is a model key, not vocabulary"
    (is (nil? (platform/qualify 'title)))
    (is (nil? (platform/qualify 'books)))))

(deftest vocabulary-preserves-lexical-shadowing
  ;; §8.2's standing ruling, and the clause that killed the symbol-rewriting
  ;; alternative: `user names stay user data ... lexical shadowing still
  ;; works`. Authors legitimately bind core names. Every one of these renders
  ;; correctly today through platform/evaluate, and must render IDENTICALLY
  ;; through deftemplate's let-bound vocabulary — which it does because the
  ;; author's inner binding shadows the outer one by ordinary scoping.
  ;;
  ;; The differential gate (bb differential) proves this across the corpus;
  ;; these pin the specific spellings, because the corpus contains no
  ;; template that shadows a core name and a future one might not either.
  (letfn [(both-ways [src model]
            (let [c (compiled src)
                  bindings (into [] (mapcat (fn [[s q]] [s q]))
                                 (sort-by key (:vocabulary c)))
                  macro-fn (eval (list 'clojure.core/let bindings (:code c)))]
              [(api/render c model {})
               (api/render (assoc c :fn macro-fn) model {})]))]
    (testing "an `each` binding may shadow a core name"
      (let [[a b] (both-ways "each count in xs\n  p= count" {:xs [7 8]})]
        (is (= "<p>7</p><p>8</p>" a))
        (is (= a b))))
    (testing "an author's `let` may shadow a core name"
      (let [[a b] (both-ways "p= (let [count 99] count)" {})]
        (is (= "<p>99</p>" a))
        (is (= a b))))
    (testing "so may a destructuring binding"
      (let [[a b] (both-ways "p= (let [{:keys [str]} {:str 7}] str)" {})]
        (is (= "<p>7</p>" a))
        (is (= a b))))
    (testing "so may an author's `fn` parameter"
      (let [[a b] (both-ways "p= ((fn [inc] (+ inc 1)) 41)" {})]
        (is (= "<p>42</p>" a))
        (is (= a b))))
    (testing "shadowing one name while borrowing another"
      (let [[a b] (both-ways "each count in xs\n  p= (str count)" {:xs [1 2]})]
        (is (= "<p>1</p><p>2</p>" a))
        (is (= a b))))))
