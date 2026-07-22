(ns carlin.runtime
  "Spec §6/§7 — the runtime surface. Small, cljc, dependency-free: generated
  code targets THIS namespace and nothing else.

  Step 3: carlin owns its serializer (§9, Q1). Hiccup's data notation is
  retained wholesale; only the printer is replaced, and hiccup2 reverts to
  what it was always meant to be — a JVM differential-test oracle (§11).

  What the interim hiccup2 printer could not do, and this one does:

    ORDER-PRESERVING ATTRIBUTES. hiccup2 sorts attributes alphabetically;
    templates are source-ordered documents and the goldens are written that
    way (`form{:method m :action a}` → method then action). Clojure's reader
    hands us array-maps for literal attribute maps, which preserve insertion
    order — so order costs nothing but honoring what we were given. (The
    array-map→hash-map promotion above 8 entries is the one place order can
    still be lost.)

    THE VOID/SELF-CLOSING THREE-WAY (rev. 5, golden-arbitrated). Pug — and so
    the corpus — distinguishes three cases, and no two-valued profile can
    express them:
      `doctype html`     → terse HTML5: <br>, bare boolean attributes
      no doctype         → <br/> (the XHTML-ish default; `mixin.blocks`)
      explicit `br/`     → <br/> ALWAYS, terse or not (`self-closing-html`)
    Hence :terse? alongside :mode, and the ::self-close attribute key that
    the parser's :self-close? rides in on and that never reaches the output."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; §6.1 the raw marker

(defrecord Raw [s])

(defn raw
  "Wrap a string so the serializer emits it unescaped — element or attribute
  position alike (§6.1). One marker, every context."
  [s]
  (->Raw (if (nil? s) "" (str s))))

(defn raw? [x] (instance? Raw x))

;; ---------------------------------------------------------------------------
;; §7.1 escaping

(defn escape
  "HTML-escape text content (§7.1). nil renders empty (§4.2).

  Carlin escapes the apostrophe where pug does not: ' → &#39;. The extra
  entity is never wrong in text position and closes the single-quoted
  attribute hole for anyone who reuses this function; the paranoid escaper
  file pins it (§12.2)."
  [s]
  (if (nil? s)
    ""
    (-> (str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&#39;"))))

(defn escape-attr
  "Escape an attribute value (§7.1). Same five entities: the profile has not
  proven any of them unnecessary, and proving it is the paranoid file's job,
  not prose's."
  [s]
  (escape s))

;; ---------------------------------------------------------------------------
;; §4.6 class normalization — shared by merge-attrs and the serializer

(defn- class-tokens
  "One :class value → a seq of class-name strings. Collections flatten;
  a map contributes its truthy keys (the conditional-class idiom);
  strings split on whitespace; keywords and symbols use their name.
  Duplicates are preserved — browsers do not care, and dedup masks intent.
  A Raw value (§6.1: honored in attribute position) passes through as ONE
  token, whitespace and all — checked before map?, because records are maps
  (the same trap the serializer's attrs test hit; third sighting)."
  [v]
  (cond
    (nil? v)     nil
    (false? v)   nil
    (raw? v)     [v]
    (map? v)     (keep (fn [[k truthy]] (when truthy (name k))) v)
    (coll? v)    (mapcat class-tokens v)
    (keyword? v) [(name v)]
    (symbol? v)  [(name v)]
    :else        (remove str/blank? (str/split (str v) #"\s+"))))

;; ---------------------------------------------------------------------------
;; §4.6 merge-attrs

(defn- conflict! [policy k a b]
  (throw (ex-info (str "attribute conflict on " k ": " (pr-str a) " vs " (pr-str b))
                  {:carlin/error :attr-conflict :attr k :declared a :incoming b
                   :policy policy})))

(defn- merge-scalar [policy k a b]
  (case policy
    :error     (conflict! policy k a b)
    :ignore    a
    :last-wins b
    (conflict! policy k a b)))

(defn- merge-one
  "Fold one source into the accumulator: style maps merge per key, every
  other scalar goes through the policy. Classes are handled outside."
  [policy acc src]
  (reduce
   (fn [acc [k v]]
     (cond
       ;; classes accumulate IN PLACE: outside the conflict policy, and
       ;; positioned where the first contributing source put them (the
       ;; shorthand's class precedes a later source's id — mixin.merge)
       (= :class k)
       (let [tokens (class-tokens v)]
         (if (seq tokens)
           (assoc acc :class (vec (concat (:class acc) tokens)))
           acc))

       (nil? v)     acc

       (and (= :style k) (map? v) (map? (:style acc)))
       (assoc acc :style
              (reduce (fn [st [sk sv]]
                        (if (contains? st sk)
                          (assoc st sk (merge-scalar policy sk (get st sk) sv))
                          (assoc st sk sv)))
                      (:style acc) v))

       (contains? acc k) (assoc acc k (merge-scalar policy k (get acc k) v))
       :else             (assoc acc k v)))
   acc src))

(defn merge-attrs
  "Merge attribute sources per §4.6, in source order: tag shorthand, the
  attrs map, &attributes.

  Classes are additive across ALL sources, always — outside the conflict
  policy entirely, because nothing declared should ever be silently lost.
  Every other scalar (:id included; :style maps per key) obeys
  policy ∈ #{:error :ignore :last-wins}."
  [policy shorthand attrs amp]
  (reduce #(merge-one policy %1 %2) {} (remove nil? [shorthand attrs amp])))

;; ---------------------------------------------------------------------------
;; §6.3 ->js — the parachute

(def ^:private dq "\"")

(defn- js-string
  "JSON string literal, SCRIPT-CONTEXT SAFE: < becomes \\u003C, which closes
  the </script> breakout that generic JSON emitters leave open (and
  neutralizes <!-- along the way). < ALONE carries that guard — `</script`
  and `<!--` both ride on it — so > and & pass through untouched (S16 (a),
  ratified 2026-07-22): the symmetric escapes bought no safety and cost
  pug's `&amp;quot;` attribute shape (attrs-data), since in attribute
  position the HTML escaper handles & downstream anyway."
  [s]
  (str dq
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace dq "\\\"")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r")
           (str/replace "\t" "\\t")
           (str/replace "<" "\\u003C"))
       dq))

(defn ->js
  "Clojure data → JS/JSON source (§6.3). Domain, ruthlessly narrowed: maps
  with keyword/string keys, vectors and seqs, strings, numbers, booleans,
  nil. Anything else is an error, never a guess."
  [x]
  (cond
    (nil? x)     "null"
    (boolean? x) (str x)
    (number? x)  (str x)
    (string? x)  (js-string x)
    ;; records are maps, forever (fourth sighting, preempted): a Raw would
    ;; sail down the map? branch and JSON-encode as {"s":...}. It is a
    ;; serializer marker, not JSON data — error, never a guess.
    (raw? x)     (throw (ex-info "->js: (raw ...) is a serializer marker, not JSON data"
                                 {:carlin/error :unsupported-js-value :value x}))
    (map? x)
    (str "{"
         (str/join ","
                   (map (fn [[k v]]
                          (let [k (cond
                                    (keyword? k) (name k)
                                    (string? k)  k
                                    :else (throw (ex-info
                                                  (str "->js: unsupported key " (pr-str k))
                                                  {:carlin/error :unsupported-js-value
                                                   :value k})))]
                            (str (js-string k) ":" (->js v))))
                        x))
         "}")

    (or (vector? x) (seq? x))
    (str "[" (str/join "," (map ->js x)) "]")

    :else (throw (ex-info (str "->js: unsupported value " (pr-str x))
                          {:carlin/error :unsupported-js-value :value x}))))

;; ---------------------------------------------------------------------------
;; §7.2 the serializer

(def void-elements
  "The HTML5 void element list (§7.2). The profile is where ALL HTML
  knowledge lives — the parser and codegen have none."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta"
    "param" "source" "track" "wbr"})

(def self-close-key
  "The attribute key an explicit source-level `/` rides in on. Consumed by
  the serializer, never emitted."
  ::self-close)

(defn- parse-tag
  "\"div#user.a.b\" → [\"div\" \"user\" (\"a\" \"b\")]. Keyword or string
  tags alike (dynamic tag names, Q13, arrive as either)."
  [t]
  (let [s    (if (keyword? t) (name t) (str t))
        base (or (re-find #"^[^#.]+" s) "div")
        id   (second (re-find #"#([^#.]+)" s))
        cls  (map second (re-seq #"\.([^#.]+)" s))]
    [base id cls]))

(defn- css-value [m]
  (str/join ";" (map (fn [[k v]] (str (name k) ":" v)) m)))

(defn- attr-value-str [k v]
  (cond
    (raw? v)     (:s v)
    (= :class k) (str/join " " (map #(if (raw? %) (:s %) (escape-attr %))
                                    (class-tokens v)))
    ;; :style keeps CSS rendering (§3.5); every OTHER map or coll is JSON via
    ;; ->js, THEN the attribute escaper — ->js first, escaper second, which is
    ;; what produces the `&quot;` shape (rev. 7 ruling 2).
    (= :style k) (escape-attr (if (map? v) (css-value v) (str v)))
    (map? v)     (escape-attr (->js v))
    (coll? v)    (escape-attr (->js v))
    (keyword? v) (escape-attr (name v))
    :else        (escape-attr (str v))))

(defn- render-attrs
  "Attributes in SOURCE ORDER (the whole point, see the ns docstring), with
  shorthand id/class first per §4.6's source order. Boolean true renders
  bare when terse, k=\"k\" otherwise; false and nil are omitted entirely."
  [attrs sh-id sh-classes {:keys [terse?]}]
  (let [hoist-class? (boolean (seq sh-classes))
        pairs (concat
               (when sh-id [[:id sh-id]])
               (when hoist-class?
                 [[:class (vec (concat sh-classes (class-tokens (:class attrs))))]])
               (remove (fn [[k _]]
                         (or (= k self-close-key)
                             (and hoist-class? (= k :class))
                             (and sh-id (= k :id))))
                       (seq attrs)))]
    (apply str
           (for [[k v] pairs
                 :when (and (some? v) (not (false? v)))
                 :let  [n (name k)]]
             (if (true? v)
               (if terse? (str " " n) (str " " n "=" dq n dq))
               (str " " n "=" dq (attr-value-str k v) dq))))))

(declare render-node)

(defn- render-children [children opts]
  (apply str (map #(render-node % opts) children)))

(defn- attrs-map?
  "The optional second element of a hiccup vector. Raw is a defrecord and
  therefore map?, so the marker must be excluded explicitly — records are
  maps, but this one is content."
  [x]
  (and (map? x) (not (raw? x))))

(defn- render-element [[t & body] opts]
  (let [[tag sh-id sh-classes] (parse-tag t)
        attrs    (when (attrs-map? (first body)) (first body))
        children (if (attrs-map? (first body)) (next body) body)
        xml?     (= :xml (:mode opts))
        void?    (contains? void-elements tag)
        closed?  (get attrs self-close-key)
        open     (str "<" tag (render-attrs attrs sh-id sh-classes opts))
        inner    (render-children children opts)]
    (cond
      ;; explicit `/` in source, or an empty element in XML: <foo/>
      (or closed? (and xml? (str/blank? inner)))
      (str open "/>")

      ;; void elements: terse HTML5 drops the slash, everything else keeps it
      void?
      (if (:terse? opts) (str open ">") (str open "/>"))

      :else (str open ">" inner "</" tag ">"))))

(defn- render-node [x opts]
  (cond
    (nil? x)     ""
    (raw? x)     (:s x)
    (string? x)  (escape x)
    (vector? x)  (render-element x opts)
    (seq? x)     (render-children x opts)
    (keyword? x) (escape (name x))
    :else        (escape (str x))))

(defn render-hiccup
  "Serialize hiccup data under a profile (§7.2).
  (render-hiccup data {:mode :html|:xml, :terse? bool})

  :terse? governs the void/self-closing three-way documented above; it
  defaults to true for :mode :html so the profile's own unit contract
  ([:br] → <br>) holds without ceremony. The compiler passes :terse? false
  for a template that declares no doctype."
  [data opts]
  (let [opts (merge {:mode :html} opts)
        opts (if (contains? opts :terse?)
               opts
               (assoc opts :terse? (= :html (:mode opts))))]
    (render-node data opts)))

;; ---------------------------------------------------------------------------
;; §3.9 doctype

(def ^:private doctypes
  {"html"         "<!DOCTYPE html>"
   "xml"          "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
   "transitional" "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">"
   "strict"       "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
   "frameset"     "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Frameset//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-frameset.dtd\">"
   "1.1"          "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">"
   "basic"        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML Basic 1.1//EN\" \"http://www.w3.org/TR/xhtml-basic/xhtml-basic11.dtd\">"
   "mobile"       "<!DOCTYPE html PUBLIC \"-//WAPFORUM//DTD XHTML Mobile 1.2//EN\" \"http://www.openmobilealliance.org/tech/DTD/xhtml-mobile12.dtd\">"
   "plist"        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"})

(defn doctype-string
  "The emitted doctype line for a `doctype` value (§3.9). Known names map to
  their canonical string; anything else is emitted literally, pug-faithful —
  `doctype custom stuff` → <!DOCTYPE custom stuff>."
  [value]
  (when value
    (let [v (str/trim (str value))
          v (if (str/blank? v) "html" v)]
      (or (doctypes (str/lower-case v))
          (str "<!DOCTYPE " v ">")))))

(defn doctype-mode
  "The serializer profile a doctype selects (§7.2): xml → :xml, anything
  else → :html. Terseness is separate: only an html doctype is terse, which
  is the three-way the goldens pin (see the ns docstring)."
  [value]
  (if (nil? value)
    ;; NO doctype at all: not terse — pug's XHTML-ish default, <br/>
    {:mode :html :terse? false}
    ;; a bare `doctype` line means html (§3.9), and html is the terse one
    (let [v (str/lower-case (str/trim (str value)))
          v (if (str/blank? v) "html" v)]
      {:mode   (if (= "xml" v) :xml :html)
       :terse? (= "html" v)})))

;; ---------------------------------------------------------------------------
;; §3.12 built-in filters

(def built-in-filters
  "The filters carlin ships (§3.12): dependency-free ones only. Markdown,
  scss, coffee and friends are USER-SUPPLIED through the `:filters` compile
  option — which makes the extension point explicit rather than an
  npm-style package convention.

  A filter is (fn [text attrs] -> html-string), applied at COMPILE time; its
  result becomes a raw text node."
  {:verbatim (fn [text _attrs] text)
   :cdata    (fn [text _attrs] (str "<![CDATA[" text "]]>"))})

(defn filter-registry
  "Built-ins plus the caller's `:filters`, keyed by BOTH keyword and string:
  templates name filters as bare words and callers reach for either."
  [user]
  (let [both (fn [m] (reduce-kv (fn [acc k v]
                                  (assoc acc (keyword (name k)) v (name k) v))
                                {} m))]
    (merge (both built-in-filters) (both (or user {})))))
