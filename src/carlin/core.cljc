(ns carlin.core
  "The front half of the pipeline (spec §8.1): source → cursor → positioned
  node tree, with structural validation (spec §8.3).

  Step-2 cut: the scanner is now a real AST producer. Every node retains what
  codegen needs — tag/call anatomy (name, shorthand classes/ids, attr map
  form, &attributes form, self-close), buffered/code forms, inline text,
  captured raw bodies (dot blocks §3.4, comments §3.10, filters §3.12) with
  their dedent base, directive forms (if/unless/case/when/each), block
  names and modes, include refs and filters, mixin definitions and calls.

  Positions: every node is stamped :pos {:key :line :col}; captured bodies
  keep :from-line and :base-indent so interpolation errors can be rebased.

  Diagnostics owned here (unchanged from step 1): :unterminated-form,
  :unsupported-directive, :extends-not-first, :invalid-under-extends,
  :nested-mixin, :include-children, :default-not-last, :mixin-arity,
  :attr-conflict, :unresolvable-ref. Splice-dependent checks
  (:block-in-include) arrive with include-splice.

  Where the full grammar hasn't landed, the scanner stays LENIENT: reader
  failures propagate only where the reader is authoritative — unclosed
  delimiters anywhere, any reader error inside restrictive-four positions
  (§3.1) — because the ratchet's green baseline is the invariant."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [carlin.cursor :as cur]
            [carlin.platform :as platform]))

;; ---------------------------------------------------------------------------
;; small line utilities

(defn- indent-of [line]
  (count (take-while #(or (= \space %) (= \tab %)) line)))

(defn- rest-of-line [cursor line col]
  (let [l (or (cur/line-at cursor line) "")]
    (if (<= col (count l)) (subs l (dec col)) "")))

(defn- skip-raw
  "First line >= from-line that is non-blank with indent <= opener-indent
  (blank lines are preserved inside raw blocks, §3.4). Returns that line
  number; EOF -> line-count + 1."
  [cursor opener-indent from-line]
  (loop [n from-line]
    (let [line (cur/line-at cursor n)]
      (cond
        (nil? line) n
        (str/blank? line) (recur (inc n))
        (<= (indent-of line) opener-indent) n
        :else (recur (inc n))))))

(defn- capture-raw
  "Capture the raw body of a block opened at opener-indent: the lines from
  from-line up to the first shallower non-blank line, dedented by the indent
  of the first non-blank captured line (relative indentation beyond that is
  preserved). Returns {:text :from-line :base-indent :next-line}; :text is
  nil when nothing was captured."
  [cursor opener-indent from-line]
  (let [end  (skip-raw cursor opener-indent from-line)
        ls   (mapv #(or (cur/line-at cursor %) "") (range from-line end))
        ;; drop trailing blank lines (the gap before the next sibling)
        ls   (loop [v ls] (if (and (seq v) (str/blank? (peek v))) (recur (pop v)) v))
        base (some->> (remove str/blank? ls) seq (map indent-of) (reduce min))
        cut  (fn [s] (if (str/blank? s) "" (subs s (min base (indent-of s)))))]
    {:text        (when base (str/join "\n" (map cut ls)))
     :from-line   from-line
     :base-indent base
     :next-line   end}))

;; ---------------------------------------------------------------------------
;; reading forms, with the two-position/two-policy discipline (ns doc)

(defn- effective-end
  "The reader reports the EXCLUSIVE stop position; a stop at column 1 means
  nothing was consumed on that line — the form ended on the line before."
  [{:keys [end-line end-col] :as r}]
  (if (and end-line (= 1 end-col))
    (assoc r :end-line (max 1 (dec end-line)))
    r))

(defn- read-source-form
  "Read one form from (line, col) through EOF — the restrictive-four
  positions (§3.1): attribute maps and the forms after = != -. The reader is
  authoritative here: both :unterminated-form and :reader-error propagate."
  [cursor line col]
  (try
    (effective-end
     (platform/read-form-at (cur/suffix-from cursor line col) line col))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) ex
      (let [d (ex-data ex)]
        (if-let [class (:carlin/error d)]
          (cur/fail! cursor class d {:carlin/cause (ex-message ex)})
          (throw ex))))))

(defn- read-line-form
  "Read one form confined to the physical line — directive heads, mixin call
  forms, &attributes expressions (§3.1: the form must end on its line).
  An unclosed delimiter here IS the multi-line-refused error and propagates
  as :unterminated-form; other reader errors are swallowed (nil) while the
  step-2 scanner stands in for the full grammar (e.g. `when x: tag`
  expansion colons read as invalid tokens)."
  [cursor line col]
  (try
    (let [r (platform/read-form-at (rest-of-line cursor line col) line col)]
      (when-not (:eof r) (effective-end r)))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) ex
      (let [d (ex-data ex)]
        (cond
          (= :unterminated-form (:carlin/error d))
          (cur/fail! cursor :unterminated-form d {:carlin/cause (ex-message ex)})

          (:carlin/error d) nil                              ; lenient: not our grammar yet
          :else (throw ex))))))

;; ---------------------------------------------------------------------------
;; tag / mixin-call anatomy (§3.2, §3.5, §3.13, §3.14)

(declare classify-line)

(defn- word-char? [c]
  (or (#?(:clj Character/isLetterOrDigit :cljs (fn [c] (re-matches #"[A-Za-z0-9]" (str c)))) c)
      (= \_ c) (= \- c)))

(defn- consume-while [s from pred]
  (loop [i from]
    (if (and (< i (count s)) (pred (nth s i))) (recur (inc i)) i)))

(defn- parse-tail
  "The surface a tag or mixin call gets after its head (§3.2/§3.13):
  shorthand classes/ids, attribute map, &attributes, self-close, then ONE
  terminal tail — dot block, = / != buffered content, `:` expansion, or
  inline text. Returns the anatomy: {:classes :ids :attrs :attrs-pos
  :amp-attrs :self-close? :buffered :dot-block :expanded :inline-text
  :next-line}. Shorthand ids carry positions for the static :attr-conflict
  check (§4.6) against a literal :id key."
  [cursor node-indent line col]
  (loop [line line, col col, a {}]
    (let [rem (rest-of-line cursor line col)]
      (cond
        ;; end of line — children (if any) are ordinary nodes
        (str/blank? rem)
        (assoc a :next-line (inc line))

        ;; .class shorthand
        (and (str/starts-with? rem ".") (> (count rem) 1) (word-char? (nth rem 1)))
        (let [end (consume-while rem 1 word-char?)]
          (recur line (+ col end)
                 (update a :classes (fnil conj []) (subs rem 1 end))))

        ;; #id shorthand
        (and (str/starts-with? rem "#") (> (count rem) 1) (word-char? (nth rem 1)))
        (let [end (consume-while rem 1 word-char?)]
          (recur line (+ col end)
                 (update a :ids (fnil conj [])
                         {:name (subs rem 1 end) :line line :col col})))

        ;; literal attribute map — restrictive-four: may span lines
        (str/starts-with? rem "{")
        (let [{:keys [form end-line end-col]} (read-source-form cursor line col)]
          (when (and (map? form) (seq (:ids a)) (contains? form :id))
            (cur/fail! cursor :attr-conflict {:line line :col col}
                       {:attr :id :shorthand (first (:ids a))}))
          (recur end-line end-col
                 (assoc a :attrs form :attrs-pos {:line line :col col}
                        ;; ruling 3 (§4.6): class tokens accumulate in TEXTUAL
                        ;; source order, so codegen needs to know where the
                        ;; map sat among the .class shorthands
                        :classes-before-attrs (count (:classes a)))))

        ;; &attributes expr — the expression ends on its line (§3.1 corollary)
        (str/starts-with? rem "&attributes")
        (let [col' (+ col (count "&attributes"))
              col' (+ col' (count (take-while #(= \space %) (rest-of-line cursor line col'))))
              r    (read-line-form cursor line col')]
          (if r
            (recur line (:end-col r) (assoc a :amp-attrs (:form r)))
            (assoc a :next-line (inc line))))

        ;; explicit self-close
        (str/starts-with? rem "/")
        (recur line (inc col) (assoc a :self-close? true))

        ;; dot block: children captured raw (§3.4)
        (= rem ".")
        (let [cap (capture-raw cursor node-indent (inc line))]
          (assoc a :dot-block cap :next-line (:next-line cap)))

        ;; buffered content tails — restrictive-four: may span lines
        (str/starts-with? rem "!=")
        (let [{:keys [form end-line]} (read-source-form cursor line (+ col 2))]
          (assoc a :buffered {:form form :raw? true} :next-line (inc end-line)))

        (str/starts-with? rem "=")
        (let [{:keys [form end-line]} (read-source-form cursor line (inc col))]
          (assoc a :buffered {:form form :raw? false} :next-line (inc end-line)))

        ;; block expansion `a: img` — the rest of the line is a nested head
        (str/starts-with? rem ":")
        (let [after (subs rem 1)
              pad   (count (take-while #(= \space %) after))]
          (if (str/blank? after)
            (assoc a :next-line (inc line))
            (let [{:keys [node consumed]}
                  (classify-line cursor line node-indent (+ col 1 pad))]
              (assoc a :expanded node :next-line (:next-line consumed)))))

        ;; inline text (one separating space stripped, §3.3)
        (str/starts-with? rem " ")
        (assoc a :inline-text (subs rem 1) :next-line (inc line))

        :else
        (assoc a :inline-text rem :next-line (inc line))))))

(defn- parse-tag
  "A tag line: literal name, `.`/`#` shorthand start, or `#{expr}` dynamic
  tag name (§3.2 Q13 — tag position is not one of the restrictive four, so
  the form ends on its line)."
  [cursor line indent col node-fn]
  (let [rem (rest-of-line cursor line col)
        [head cont-col]
        (if (str/starts-with? rem "#{")
          (let [r (read-line-form cursor line col)]
            [{:name (:form r) :dynamic? true}
             (if r (:end-col r) (inc (count (cur/line-at cursor line))))])
          ;; NAMESPACED TAG NAMES (§3.2): `fb:users` is one tag, `a: img` is
          ;; block expansion. The disambiguator is the space — a colon
          ;; followed immediately by a word character continues the name,
          ;; a colon followed by anything else opens an expansion.
          (let [end (loop [i (consume-while rem 0 word-char?)]
                      (if (and (< (inc i) (count rem))
                               (= \: (nth rem i))
                               (word-char? (nth rem (inc i))))
                        (recur (consume-while rem (inc i) word-char?))
                        i))]
            [{:name (when (pos? end) (subs rem 0 end))}
             (+ col end)]))
        a (parse-tail cursor indent line cont-col)]
    {:node     (merge (node-fn :tag) head (dissoc a :next-line))
     :consumed {:next-line (:next-line a)}}))

;; ---------------------------------------------------------------------------
;; head classification

(def ^:private directive-re
  #"^(extends|include|append|prepend|mixin|each|for|case|when|default|while|doctype|if|unless|else|block)(?![\w-])")

(defn- mixin-arity
  "Arity of a mixin binding vector: destructuring forms count as one
  positional each; `&` makes it at-least-n (§3.13, D11)."
  [bindings]
  (when (vector? bindings)
    (let [[fixed [amp _]] (split-with #(not= '& %) bindings)]
      {:min (count fixed) :variadic? (some? amp)})))

(defn- maybe-expansion
  "`: head` expansion after a scrutinee/default (§3.7). Returns
  {:expanded node :next-line n} or nil when the remainder isn't an expansion."
  [cursor line col node-indent]
  (let [rem (rest-of-line cursor line col)
        i   (str/index-of rem ":")]
    (when (and i (str/blank? (subs rem 0 i)))
      (let [after (subs rem (inc i))
            pad   (count (take-while #(= \space %) after))]
        (when-not (str/blank? after)
          (let [{:keys [node consumed]}
                (classify-line cursor line node-indent (+ col i 1 pad))]
            {:expanded node :next-line (:next-line consumed)}))))))

(defn- parse-when-head
  "A `when` clause head: one literal/form scrutinee value, optionally
  followed by `: head` expansion (§3.7). The colon defeats a naive read
  (`when :shipped: p …` reads as an invalid keyword), so on reader refusal
  we retry against the text left of the colon."
  [cursor line col node-indent]
  (if-let [r (read-line-form cursor line col)]
    (merge {:form (:form r) :next-line (inc line)}
           (maybe-expansion cursor line (:end-col r) node-indent))
    (let [txt (rest-of-line cursor line col)]
      (if-let [[_ v] (re-find #"^(.+?):\s+\S" txt)]
        (let [r (try (platform/read-form-at v line col)
                     (catch #?(:clj Exception :cljs :default) _ nil))]
          (merge {:form (:form r) :next-line (inc line)}
                 (when r
                   (maybe-expansion cursor line (+ col (count v)) node-indent))))
        {:form nil :next-line (inc line)}))))

(defn- classify-line
  "Classify the head at (line, col) and consume its logical extent.
  Returns {:node <node-or-nil> :consumed {:next-line n}}."
  [cursor line indent col]
  (let [content (rest-of-line cursor line col)
        pos     {:line line :col col}
        node    (fn [type & {:as extra}]
                  (merge {:type type :indent indent
                          :pos (assoc pos :key (:key cursor))}
                         extra))
        next+   (fn [n] {:next-line n})]
    (cond
      ;; silent + rendered comments: children are captured text (§3.10)
      (str/starts-with? content "//-")
      (let [cap (capture-raw cursor indent (inc line))]
        {:node (node :comment-silent :inline (subs content 3) :body cap)
         :consumed (next+ (:next-line cap))})

      (str/starts-with? content "//")
      (let [cap (capture-raw cursor indent (inc line))]
        {:node (node :comment :inline (subs content 2) :body cap)
         :consumed (next+ (:next-line cap))})

      ;; piped text / literal markup line (§3.3)
      (str/starts-with? content "|")
      (let [t (subs content 1)]
        {:node (node :text :text (cond-> t (str/starts-with? t " ") (subs 1)))
         :consumed (next+ (inc line))})

      (str/starts-with? content "<")
      {:node (node :literal-html :text content) :consumed (next+ (inc line))}

      ;; buffered code at line start (§3.6) — multi-line permitted
      (str/starts-with? content "!=")
      (let [{:keys [form end-line]} (read-source-form cursor line (+ col 2))]
        {:node (node :buffered :form form :raw? true)
         :consumed (next+ (inc end-line))})

      (str/starts-with? content "=")
      (let [{:keys [form end-line]} (read-source-form cursor line (inc col))]
        {:node (node :buffered :form form :raw? false)
         :consumed (next+ (inc end-line))})

      ;; unbuffered code (§3.6) — multi-line permitted; children are its body
      (or (= content "-") (str/starts-with? content "- "))
      (if (str/blank? (subs content 1))
        {:node (node :code) :consumed (next+ (inc line))}
        (let [{:keys [form end-line]} (read-source-form cursor line (+ col 2))]
          {:node (node :code :form form)
           :consumed (next+ (inc end-line))}))

      ;; filter (§3.12): a CHAIN of names, optional attr map, verbatim body.
      ;; `:cdata:uglify-js` is two filters; they apply right to left, so the
      ;; leftmost name is the outermost wrapper (pug-faithful — and the only
      ;; reading under which `filters.nested`'s goldens make sense). The body
      ;; is either the rest of the line (inline, `#[:cdata inside]`) or the
      ;; captured block beneath.
      (and (str/starts-with? content ":") (> (count content) 1) (word-char? (nth content 1)))
      (let [chain-end (loop [i 0]
                        (if (and (< i (count content)) (= \: (nth content i)))
                          (let [e (consume-while content (inc i) word-char?)]
                            (if (> e (inc i)) (recur e) i))
                          i))
            names    (->> (str/split (subs content 0 chain-end) #":")
                          (remove str/blank?)
                          vec)
            rest-str (subs content chain-end)
            [attrs end-line consumed-to]
            (if (str/starts-with? (str/triml rest-str) "{")
              (let [bi (str/index-of content "{" chain-end)
                    r  (read-source-form cursor line (+ col bi))]
                [(:form r) (:end-line r) (when (= line (:end-line r))
                                           (- (:end-col r) col))])
              [nil line chain-end])
            trailing (when consumed-to (str/trim (subs content (min consumed-to (count content)))))
            cap      (if (seq trailing)
                       {:text trailing :from-line line :base-indent 0
                        :next-line (inc line)}
                       (capture-raw cursor indent (inc end-line)))]
        {:node (node :filter :name (first names) :names names
                     :attrs attrs :body cap)
         :consumed (next+ (:next-line cap))})

      ;; mixin call (§3.13, Q14): + then exactly ONE form — a parenthesized
      ;; `(name args)` (reader-delimited, authoritative) or a bare name. A
      ;; bare name is a NAME TOKEN (word-chars, like a tag head), not an
      ;; arbitrary reader form: `=`, `.`, `#`, `{`, `:` are symbol
      ;; constituents to the reader but are anatomy/buffered sigils here, so
      ;; reading a form would swallow them (`+baz= "x"` → the symbol `baz=`).
      (str/starts-with? content "+")
      (let [after (rest-of-line cursor line (inc col))]
        (if (str/starts-with? after "(")
          (let [r    (read-line-form cursor line (inc col))
                form (:form r)
                call (when (and (seq? form) (symbol? (first form)))
                       {:name (first form) :args (vec (rest form))})]
            (if r
              (let [a (parse-tail cursor indent line (:end-col r))]
                {:node (merge (node :mixin-call
                                    :call (some-> call (assoc :argc (count (:args call))
                                                              :pos pos)))
                              call (dissoc a :next-line))
                 :consumed (next+ (:next-line a))})
              {:node (node :mixin-call) :consumed (next+ (inc line))}))
          (let [end  (consume-while after 0 word-char?)
                nm   (when (pos? end) (symbol (subs after 0 end)))
                call (when nm {:name nm :args []})
                a    (parse-tail cursor indent line (+ (inc col) end))]
            {:node (merge (node :mixin-call
                                :call (some-> call (assoc :argc 0 :pos pos)))
                          call (dissoc a :next-line))
             :consumed (next+ (:next-line a))})))

      :else
      (if-let [[_ kw] (re-find directive-re content)]
        (let [after-col (+ col (count kw))
              arg-col   (fn [] (let [r (rest-of-line cursor line after-col)]
                                 (+ after-col (count (take-while #(= \space %) r)))))]
          (case kw
            "while"
            (cur/fail! cursor :unsupported-directive pos {:directive 'while})

            "extends"
            (let [ref (str/trim (subs content (count kw)))]
              {:node (node :extends :ref ref
                           :ref-pos (when (seq ref)
                                      {:line line
                                       :col (+ col (str/index-of content ref (count kw)))}))
               :consumed (next+ (inc line))})

            "include"
            (let [after (subs content (count kw))
                  [fname after] (if (str/starts-with? after ":")
                                  (let [e (consume-while after 1 word-char?)]
                                    [(subs after 1 e) (subs after e)])
                                  [nil after])
                  ref (str/trim after)]
              {:node (node :include :ref ref :filter fname
                           :ref-pos (when (seq ref)
                                      {:line line
                                       :col (+ col (str/last-index-of content ref))}))
               :consumed (next+ (inc line))})

            ("block" "append" "prepend")
            (let [[w1 w2 w3] (str/split (str/trim content) #"\s+")
                  nested?    (and (= w1 "block") (#{"append" "prepend"} w2))
                  mode       (cond nested?              (keyword w2)
                                   (= w1 "block")       :block
                                   :else                (keyword w1))
                  nm         (if nested? w3 w2)]
              {:node (node :block :mode mode :name nm)
               :consumed (next+ (inc line))})

            "mixin"
            (let [nm (read-line-form cursor line (arg-col))
                  bv (when (and nm (symbol? (:form nm)))
                       (read-line-form cursor line (:end-col nm)))]
              {:node (node :mixin-def
                           :name (when (symbol? (:form nm)) (:form nm))
                           :bindings (:form bv)
                           :arity (some-> bv :form mixin-arity))
               :consumed (next+ (inc line))})

            ("if" "unless" "case")
            (let [r (read-line-form cursor line (arg-col))]
              {:node (node (keyword kw) :form (:form r))
               :consumed (next+ (inc line))})

            "else"
            (let [after (str/triml (subs content (count kw)))]
              (if (str/starts-with? after "if")
                (let [r (read-line-form cursor line (+ (arg-col) 2))]
                  {:node (node :else :else-if (:form r))
                   :consumed (next+ (inc line))})
                {:node (node :else) :consumed (next+ (inc line))}))

            ("each" "for")
            (let [binding (read-line-form cursor line (arg-col))
                  in-form (when binding (read-line-form cursor line (:end-col binding)))
                  coll    (when (and in-form (= 'in (:form in-form)))
                            (read-line-form cursor line (:end-col in-form)))]
              {:node (node :each :binding (:form binding) :coll (:form coll))
               :consumed (next+ (inc line))})

            "when"
            (let [{:keys [form expanded next-line]}
                  (parse-when-head cursor line (arg-col) indent)]
              {:node (node :when :form form :expanded expanded)
               :consumed (next+ next-line)})

            "default"
            (let [exp (maybe-expansion cursor line after-col indent)]
              {:node (node :default :expanded (:expanded exp))
               :consumed (next+ (or (:next-line exp) (inc line)))})

            "doctype"
            {:node (node :doctype :value (str/trim (subs content (count kw))))
             :consumed (next+ (inc line))}))

        ;; anything else is a tag line (unknown names are tags — web
        ;; components welcome, §3.2); leniency lives in the anatomy
        (parse-tag cursor line indent col node)))))

;; ---------------------------------------------------------------------------
;; scan → flat nodes → tree

(defn- scan [cursor]
  (loop [n 1, nodes []]
    (if (> n (cur/line-count cursor))
      nodes
      (let [line (cur/line-at cursor n)]
        (if (str/blank? line)
          (recur (inc n) nodes)
          (let [indent (indent-of line)
                {:keys [node consumed]} (classify-line cursor n indent (inc indent))]
            (recur (max (inc n) (:next-line consumed))
                   (if node (conj nodes node) nodes))))))))

(defn- build-tree
  "Nest the flat scan by indentation: a node's children are the subsequent
  nodes indented deeper than it."
  [nodes]
  (letfn [(level [ns min-indent]
            (loop [ns ns, acc []]
              (if-let [n (first ns)]
                (if (< (:indent n) min-indent)
                  [acc ns]
                  (let [[children ns'] (level (next ns) (inc (:indent n)))]
                    (recur ns' (conj acc (assoc n :children children)))))
                [acc ns])))]
    (first (level (seq nodes) 0))))

;; ---------------------------------------------------------------------------
;; structural validation over the tree (spec §8.3)

(defn- comment? [n] (#{:comment :comment-silent} (:type n)))

(defn- node-kids
  "A node's structural descendants for walking purposes: tree children plus
  the `: `-expanded head, which is a real node wherever it appears."
  [n]
  (cond-> (:children n)
    (:expanded n) (concat [(:expanded n)])))

(defn- check-extends [cursor roots]
  (let [meaningful (remove comment? roots)]
    (when-let [[head & body] (seq meaningful)]
      (doseq [n body]
        (when (= :extends (:type n))
          (cur/fail! cursor :extends-not-first (:pos n))))
      (when (= :extends (:type head))
        (doseq [n body]
          (when-not (#{:block :mixin-def :include} (:type n))
            (cur/fail! cursor :invalid-under-extends (:pos n))))))))

(defn- walk-checks
  "Depth-first structural checks; collects mixin definitions and calls on the
  way down. Returns {:defs {name arity} :calls [..]}."
  [cursor tree]
  (letfn [(walk [acc node depth]
            (let [acc
                  (case (:type node)
                    :mixin-def
                    (do (when (pos? depth)
                          (cur/fail! cursor :nested-mixin (:pos node)))
                        (cond-> acc
                          (and (:name node) (:arity node))
                          (assoc-in [:defs (:name node)] (:arity node))))

                    :mixin-call
                    (cond-> acc (:call node) (update :calls conj (:call node)))

                    :include
                    (do (when (seq (:children node))
                          (cur/fail! cursor :include-children (:pos node)))
                        acc)

                    :case
                    (do (let [kids (remove comment? (:children node))
                              di   (first (keep-indexed
                                           (fn [i k] (when (= :default (:type k)) i)) kids))]
                          (when (and di (some #(= :when (:type %)) (drop (inc di) kids)))
                            (cur/fail! cursor :default-not-last
                                       (:pos (nth kids di)))))
                        acc)

                    acc)]
              (reduce (fn [a child] (walk a child (inc depth))) acc (node-kids node))))]
    (reduce (fn [a n] (walk a n 0)) {:defs {} :calls []} tree)))

(defn- check-arity
  "Compile-time mixin arity (§3.13, D11): exact unless `&` (at least n) —
  checked AFTER include-splice, over the composed namespace of definitions
  (mixin libraries arrive via include, §3.11). Each call carries the cursor
  of the file it was written in, so the error points into the right source."
  [{:keys [defs calls]}]
  (doseq [{:keys [name argc pos cursor]} calls]
    (when-let [{:keys [min variadic?]} (get defs name)]
      ;; exact unless & (spec §3.13, reaffirmed): compile-time guarantees
      ;; are the point — pug's absent-is-undefined is not imported
      (when (if variadic? (< argc min) (not= argc min))
        (cur/fail! cursor :mixin-arity pos
                   {:mixin name :expected min :variadic? variadic? :got argc})))))

;; ---------------------------------------------------------------------------
;; include-splice (§3.11, Q9): include composes

(defn- parse-one
  "Scan ONE template into a checked tree. Per-file checks run here — before
  splicing — so judgments like :nested-mixin and :invalid-under-extends apply
  to each file as written, never to its post-composition shape (a mixin
  library spliced under a nested include must not look nested). Returns
  {:cursor :tree :defs :calls}, calls tagged with their file's cursor."
  [source key]
  (let [cursor (cur/make source key)
        tree   (build-tree (scan cursor))]
    (check-extends cursor tree)
    (let [{:keys [defs calls]} (walk-checks cursor tree)]
      {:cursor cursor :tree tree :defs defs
       :calls  (mapv #(assoc % :cursor cursor) calls)})))

(defn- block-nodes [tree]
  (letfn [(walk [acc n]
            (let [acc (if (= :block (:type n)) (conj acc n) acc)]
              (reduce walk acc (node-kids n))))]
    (reduce walk [] tree)))

(defn- add-dep [state key]
  (update state :deps (fn [d] (if (some #{key} d) d (conj d key)))))

(declare resolve-template dissolve-blocks)

(defn- splice-includes
  "Replace every :include node in `nodes` (belonging to the file `cursor`
  reads) with its resolved content:

    :kind :template   parsed with its own cursor — every spliced node keeps
                      a :pos keyed to ITS file — checked (blocks in an
                      included file are the composition leak, Q9), then
                      spliced recursively
    :kind :raw        a verbatim text node
    include:filter    a filter node whose body is the raw target

  `path` is the chain of keys currently being spliced: membership is a
  cycle (:include-cycle, positioned at the offending include). `state`
  threads {:deps [first-inclusion order] :defs {} :calls []} through the
  composition. Returns [nodes' state]."
  [cursor nodes opts path state]
  (reduce
   (fn [[out state] node]
     (if (= :include (:type node))
       (let [ref (:ref node)
             pos (or (:ref-pos node) (:pos node))
             {:keys [key source kind] :as hit}
             (platform/resolve-source (:resolver opts) (:key cursor) ref)]
         (when-not hit
           (cur/fail! cursor :unresolvable-ref pos {:ref ref}))
         (let [state (add-dep state key)]
           (cond
             (:filter node)
             [(conj out {:type :filter :name (:filter node) :attrs nil
                         :body {:text source}
                         :pos (:pos node) :indent (:indent node) :children []})
              state]

             (= :raw kind)
             [(conj out {:type :literal-html :text source :verbatim? true
                         :pos (:pos node) :indent (:indent node) :children []})
              state]

             :else
             (do
               (when (some #{key} path)
                 (cur/fail! cursor :include-cycle (:pos node)
                            {:ref ref :chain (conj path key)}))
               (let [{ttree :tree extended? :extended? state :state}
                     (resolve-template source key opts (conj path key) state)]
                 (if extended?
                   ;; the included file ran its OWN inheritance (§3.14/§3.11):
                   ;; that resolution is private — its named blocks dissolve
                   ;; here, so nothing leaks into the includer's namespace and
                   ;; D7 keeps its teeth for the non-extending case below
                   [(into out (dissolve-blocks ttree)) state]
                   (do
                     (when-let [b (first (block-nodes ttree))]
                       (cur/fail! cursor :block-in-include (:pos node)
                                  {:target key :block-pos (:pos b)}))
                     [(into out ttree) state])))))))
       ;; not an include: recurse structurally (children + expanded head)
       (let [[kids state] (splice-includes cursor (:children node) opts path state)
             [exp state]  (if (:expanded node)
                            (let [[e s] (splice-includes cursor [(:expanded node)]
                                                         opts path state)]
                              [(first e) s])
                            [nil state])]
         [(conj out (cond-> (assoc node :children (vec kids))
                      exp (assoc :expanded exp)))
          state])))
   [[] state]
   nodes))

;; ---------------------------------------------------------------------------
;; inheritance-merge (§3.14): extends / block / append / prepend

(defn- named-block? [n] (and (= :block (:type n)) (:name n)))

(defn- apply-block-overrides
  "One layout block, the extending file's override nodes for its name, in
  source order: :block replaces the children, :append/:prepend concatenate
  (§3.14) — so `block append x` twice stacks appends outward, exactly as
  multi-level chains stack them across files."
  [node overrides]
  (reduce (fn [n {:keys [mode children]}]
            (case mode
              :block   (assoc n :children (vec children))
              :append  (update n :children into children)
              (:prepend) (update n :children #(into (vec children) %))))
          node overrides))

(defn- merge-blocks
  "One inheritance level: the extending file's named block nodes applied
  over the layout tree. Nested layout blocks (a block inside a block's
  default children) are reachable; children substituted at THIS level are
  not re-walked — the next derivation level walks the whole merged tree
  afresh, which is how multi-level chains fold from the base upward.
  Overrides whose name has no target vanish silently (pug-faithful)."
  [nodes overrides]
  (let [by-name (group-by :name overrides)]
    (letfn [(walk-nodes [ns] (mapv walk ns))
            (walk [n]
              (if (named-block? n)
                (let [n (update n :children walk-nodes)]
                  (if-let [ovs (by-name (:name n))]
                    (apply-block-overrides n ovs)
                    n))
                (cond-> (update n :children walk-nodes)
                  (:expanded n) (update :expanded walk))))]
      (walk-nodes nodes))))

(defn- dissolve-blocks
  "Named :block nodes dissolve into their (merged) children: inheritance
  resolves before codegen and everything downstream is unaware it happened
  (§3.14). Unnamed `block` survives — it is the mixin yield point, pug's
  own disambiguation (§3.13)."
  [nodes]
  (into []
        (mapcat (fn [n]
                  (let [n (update n :children dissolve-blocks)]
                    (if (named-block? n)
                      (:children n)
                      [n]))))
        nodes))

(defn- resolve-template
  "parse-one + include-splice + inheritance-merge for ONE template — the
  passes are mutually recursive because included files may extend (their
  inheritance resolves privately during splice) and layouts may include.
  Includes splice BEFORE the merge so a mixin library included at the top
  of an extending file lands, hoistable, in the merged tree. Named blocks
  are NOT yet dissolved: the caller either merges further overrides into
  them (the next chain level) or dissolves them (the root / a splice).
  Returns {:cursor :tree :extended? :state}."
  [source key opts path state]
  (let [{:keys [cursor tree defs calls]} (parse-one source key)
        state (-> state (update :defs merge defs) (update :calls into calls))
        [tree state] (splice-includes cursor tree opts path state)
        head  (first (remove comment? tree))]
    (if-not (= :extends (:type head))
      {:cursor cursor :tree tree :extended? false :state state}
      (let [ref (:ref head)
            pos (or (:ref-pos head) (:pos head))
            {lkey :key lsource :source lkind :kind :as hit}
            (platform/resolve-source (:resolver opts) key ref)]
        (when-not hit
          (cur/fail! cursor :unresolvable-ref pos {:ref ref}))
        (when (= :raw lkind)
          (cur/fail! cursor :extends-raw pos {:ref ref :target lkey}))
        (when (some #{lkey} path)
          (cur/fail! cursor :include-cycle pos
                     {:ref ref :via :extends :chain (conj path lkey)}))
        (let [state (add-dep state lkey)
              {ltree :tree state :state}
              (resolve-template lsource lkey opts (conj path lkey) state)
              body     (remove #(= :extends (:type %)) tree)
              blocks   (filter named-block? body)
              ;; mixin defs, spliced include content, comments — carried
              ;; ahead of the layout so definitions hoist from top level
              carried  (remove named-block? body)]
          {:cursor cursor
           :tree (into (vec carried) (merge-blocks ltree blocks))
           :extended? true
           :state state})))))

;; ---------------------------------------------------------------------------
;; the shipped file resolver (spec §5.4)

#?(:clj
   (defn file-resolver
     "The file-resolver battery (§5.4): relative-to-includer, `/`-to-root,
     as-given-then-.carlin, :kind by extension — and the ROOT-JAIL: refs that
     escape `root` are refused before any read happens (nil, exactly like a
     miss). Returns (fn [from ref] -> {:key :source :kind} | nil)."
     [root]
     (let [root-file  (io/file root)
           root-canon (.getCanonicalPath root-file)
           jailed?    (fn [^java.io.File f]
                        (let [p (.getCanonicalPath f)]
                          (or (= p root-canon)
                              (str/starts-with?
                               p (str root-canon java.io.File/separator)))))
           hit        (fn [^java.io.File f]
                        (when (and (jailed? f) (.isFile f)) f))]
       (fn [from ref]
         (when (and (string? ref) (seq ref))
           (let [base (if (str/starts-with? ref "/")
                        root-file
                        (or (some-> from io/file .getParentFile) root-file))
                 rel  (cond-> ref (str/starts-with? ref "/") (subs 1))
                 cand (io/file base rel)
                 f    (or (hit cand)
                          (when-not (re-find #"\.\w+$" (.getName cand))
                            (hit (io/file (str (.getPath cand) ".carlin")))))]
             (when f
               {:key    (.getPath f)
                :source (slurp f)
                :kind   (if (str/ends-with? (.getName f) ".carlin")
                          :template :raw)})))))))

;; ---------------------------------------------------------------------------
;; clause-attachment (§3.7)

(defn- attach-clauses
  "Attach every :else to its conditional: `else` and `else if` pair with the
  nearest previous non-comment sibling when it is an :if, :unless, or :each
  (each/else renders on the empty collection, §3.8). `else if` chains nest:
  the else slot of the parent holds a fresh :if carrying its own chain.
  An :else with no such sibling is a positioned :dangling-clause."
  [cursor nodes]
  (let [attach
        (fn attach [nodes]
          (reduce
           (fn [acc n]
             (let [n (cond-> (update n :children attach)
                       (:expanded n) (update :expanded #(first (attach [%]))))]
               (if (= :else (:type n))
                 (let [prev-i (->> (range (dec (count acc)) -1 -1)
                                   (drop-while #(comment? (nth acc %)))
                                   first)
                       prev   (when prev-i (nth acc prev-i))
                       clause (if (:else-if n)
                                {:type :if :form (:else-if n) :pos (:pos n)
                                 :indent (:indent n) :children (:children n)}
                                nil)]
                   (cond
                     (and prev (#{:if :unless} (:type prev)))
                     (assoc acc prev-i
                            (update prev :else-chain (fnil conj [])
                                    (or clause {:type :else-body :pos (:pos n)
                                                :children (:children n)})))

                     (and prev (= :each (:type prev)) (nil? (:else-if n)))
                     (assoc acc prev-i (assoc prev :else-children (:children n)))

                     :else
                     (cur/fail! cursor :dangling-clause (:pos n))))
                 (conj acc n))))
           [] nodes))]
    (attach nodes)))

(defn parse-inline-fragment
  "Parse a single-line fragment — the inside of #[tag …] interpolation
  (§3.3) — into one node. Used by codegen; positions are fragment-local."
  [text]
  (:node (classify-line (cur/make text "<fragment>") 1 0 1)))

;; ---------------------------------------------------------------------------
;; entry point

(defn parse
  "The front half of the pipeline: source → positioned node tree with
  structural validation, inheritance merged (§3.14), includes spliced,
  named blocks dissolved, dependencies collected. Returns
  {:cursor c :tree t :deps [resolver keys, first-reference order]}. Throws
  positioned ex-info ({:carlin/error class :key :line :col}) on any error."
  [source opts]
  (let [key (or (:name opts) "<template>")
        {:keys [cursor tree state]}
        (resolve-template source (:name opts) opts [key]
                          {:deps [] :defs {} :calls []})
        tree (dissolve-blocks tree)
        tree (attach-clauses cursor tree)]
    (check-arity state)
    {:cursor cursor :tree tree :deps (:deps state)}))

#?(:clj
   (defn compile-ref
     "Public API (§5.1): compile the template `ref` resolves to, root pulled
     through (:resolver opts). Delegates to the seam (carlin.api) lazily —
     the API migrates fully into this namespace when carlin.legacy retires
     and the codegen require can point this way without a cycle."
     [ref opts]
     ((requiring-resolve 'carlin.api/compile-ref) ref opts)))
