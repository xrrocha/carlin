(ns carlin.legacy
  "Carlin: terse indentation-based markup compiled to hiccup-generating
  Clojure code, then to one fn per template: (fn [model env] hiccup).

  QUARANTINE: this is the pre-refactor implementation, kept byte-stable as
  the behavioral north star for the baselined corpus cases while the real
  passes land (handoff §1). First-class namespace for the editor's sake;
  frozen for the ratchet's. Do not improve; replace delegation-by-delegation
  from carlin.api, then delete this file.

  Cheap-tier initial features:
    !=  expr        unescaped buffered output (also tag!= expr)
    !{expr}         unescaped interpolation
    #[tag ...]      tag interpolation inside text
    //  text        rendered HTML comment
    //- text        silent comment (children swallowed)
    a: b: li text   block expansion
    tag/            explicit self-closing marker (renders as empty element;
                    HTML5 has no self-closing custom elements)
    <literal html>  lines starting with < pass through raw (with #{} interp)
    each BIND in E  BIND is any Clojure binding form: each [k v] in m
    each/if + else  each-else renders when the collection is empty

  Environment: every template fn takes (model, env). Inside templates the
  symbols `model` and `env` are reserved and bound to the whole maps; all
  other free symbols are destructured from the model.

  Still deferred: multi-line forms, dot text blocks, include, mixin sugar,
  positional error reporting."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import (java.io PushbackReader StringReader)))

;; ---------------------------------------------------------------------------
;; Reader utilities

(defn- read-prefix
  "Reads ONE form from the front of s. Returns [form remainder-string]."
  [^String s]
  (let [rdr (PushbackReader. (StringReader. s))
        form (binding [*read-eval* false] (read rdr))
        sb (StringBuilder.)]
    (loop []
      (let [c (.read rdr)]
        (when-not (neg? c)
          (.append sb (char c))
          (recur))))
    [form (str sb)]))

(defn- matching-bracket
  "Index of the ] closing an already-open [ at the front of s."
  [^String s]
  (loop [i 0, depth 0]
    (when (< i (count s))
      (let [c (.charAt s i)]
        (cond
          (= c \[) (recur (inc i) (inc depth))
          (= c \]) (if (zero? depth) i (recur (inc i) (dec depth)))
          :else    (recur (inc i) depth))))))

;; ---------------------------------------------------------------------------
;; Text scanning: #{expr} (escaped), !{expr} (raw), #[tag ...] (nested tag)

(declare parse-line)

(defn- scan-text
  "Text → pieces: strings | {:expr f} | {:raw f} | {:node n}."
  [^String text]
  (loop [s text, pieces []]
    (let [hit (->> [["#{" :expr] ["!{" :raw] ["#[" :node]]
                   (keep (fn [[tok kind]]
                           (let [i (.indexOf s tok)]
                             (when-not (neg? i) [i kind]))))
                   (sort)
                   (first))]
      (if-not hit
        (cond-> pieces (pos? (count s)) (conj s))
        (let [[i kind] hit
              lead (subs s 0 i)
              pieces (cond-> pieces (pos? (count lead)) (conj lead))
              after (subs s (+ i 2))]
          (if (= kind :node)
            (let [j (or (matching-bracket after)
                        (throw (ex-info "Unclosed #[" {:text text})))]
              (recur (subs after (inc j))
                     (conj pieces {:node (parse-line (subs after 0 j))})))
            (let [[form rest*] (read-prefix after)
                  rest* (str/replace-first rest* #"^\s*\}" "")]
              (recur rest* (conj pieces {kind form})))))))))

(defn- raw-pieces
  "For literal-HTML lines: static text stays raw, expressions stay escaped."
  [pieces]
  (mapv #(if (string? %) {:rawtext %} %) pieces))

;; ---------------------------------------------------------------------------
;; Line parsing

(defn- tag-line [trimmed]
  (let [[_ nm shorthand rest*] (re-matches #"([\w-]*)((?:[#.][\w-]+)*)(.*)" trimmed)
        tag (keyword (str (if (str/blank? nm) "div" nm) shorthand))
        rest* (str/triml rest*)
        [attrs rest*] (if (str/starts-with? rest* "{")
                        (read-prefix rest*)
                        [nil rest*])
        rest* (str/triml rest*)
        base (cond-> {:type :tag :tag tag} attrs (assoc :attrs attrs))]
    (cond
      (str/starts-with? rest* "/")
      (assoc base :self-closing true)

      (str/starts-with? rest* ":")
      (assoc base :children [(parse-line (str/triml (subs rest* 1)))])

      (str/starts-with? rest* "!=")
      (assoc base :inline {:type :expr :raw true
                           :form (first (read-prefix (subs rest* 2)))})

      (str/starts-with? rest* "=")
      (assoc base :inline {:type :expr
                           :form (first (read-prefix (subs rest* 1)))})

      (seq rest*)
      (assoc base :inline {:type :text :pieces (scan-text rest*)})

      :else base)))

(defn- parse-line [^String line]
  (let [trimmed (str/trim line)]
    (cond
      (str/starts-with? trimmed "doctype") {:type :doctype
                                            :value (str/trim (subs trimmed 7))}
      (str/starts-with? trimmed "//-")     {:type :comment :silent true}
      (str/starts-with? trimmed "//")      {:type :comment
                                            :text (str/triml (subs trimmed 2))}
      (str/starts-with? trimmed "| ")      {:type :text
                                            :pieces (scan-text (subs trimmed 2))}
      (= trimmed "|")                      {:type :text :pieces []}
      (str/starts-with? trimmed "!= ")     {:type :expr :raw true
                                            :form (first (read-prefix (subs trimmed 3)))}
      (str/starts-with? trimmed "= ")      {:type :expr
                                            :form (first (read-prefix (subs trimmed 2)))}
      (str/starts-with? trimmed "- ")      {:type :code
                                            :form (first (read-prefix (subs trimmed 2)))}
      (str/starts-with? trimmed "<")       {:type :text
                                            :pieces (raw-pieces (scan-text trimmed))}
      (= trimmed "else")                   {:type :else}
      :else
      (if-let [[_ bind coll] (re-matches #"(?:each|for)\s+(.+?)\s+in\s+(.+)" trimmed)]
        {:type :each
         :bind (first (read-prefix bind))
         :coll (first (read-prefix coll))}
        (if-let [[_ test] (re-matches #"if\s+(.+)" trimmed)]
          {:type :if :test (first (read-prefix test))}
          (tag-line trimmed))))))

(defn parse
  "Source → node tree, by indentation."
  [source]
  (let [lines (->> (str/split-lines source)
                   (remove str/blank?))]
    (:tree
     (reduce
      (fn [{:keys [stack] :as acc} line]
        (let [indent (count (take-while #(= \space %) line))
              node (update (parse-line line) :children #(vec (or % [])))
              stack (into [] (take-while #(< (:indent %) indent)) stack)
              stack (if (empty? stack) [{:indent -1 :path []}] stack)
              parent-path (:path (peek stack))
              tree (update-in (:tree acc) (conj parent-path :children) conj node)
              idx (dec (count (get-in tree (conj parent-path :children))))
              path (into parent-path [:children idx])]
          {:tree tree :stack (conj stack {:indent indent :path path})}))
      {:tree {:type :root :children []} :stack [{:indent -1 :path []}]}
      lines))))

;; ---------------------------------------------------------------------------
;; else-attachment (to :if and :each)

(defn- attach-elses [node]
  (let [kids (mapv attach-elses (:children node))
        kids (reduce (fn [acc k]
                       (if (and (= :else (:type k))
                                (contains? #{:if :each} (:type (peek acc))))
                         (conj (pop acc)
                               (assoc (peek acc) :else-children (:children k)))
                         (conj acc k)))
                     [] kids)]
    (assoc node :children kids)))

;; ---------------------------------------------------------------------------
;; Node tree → hiccup-generating code

(declare node->code)

(defn- splice [codes]
  (case (count codes)
    0 nil
    1 (first codes)
    (cons 'list codes)))

(defn- children->code [node]
  (into [] (keep node->code) (:children node)))

(defn- piece->code [p]
  (cond
    (string? p)  p
    (:rawtext p) `(h/raw ~(:rawtext p))
    (:expr p)    (:expr p)
    (:raw p)     `(h/raw (str ~(:raw p)))
    (:node p)    (node->code (:node p))))

(defn- node->code [{:keys [type] :as node}]
  (case type
    :doctype nil
    :comment (when-not (:silent node)
               `(h/raw ~(str "<!--" (:text node) "-->")))
    :text    (splice (mapv piece->code (:pieces node)))
    :expr    (if (:raw node)
               `(h/raw (str ~(:form node)))
               (:form node))
    :tag     (let [kids (cond-> []
                          (:inline node) (conj (node->code (:inline node)))
                          true (into (children->code node)))]
               (cond-> [(:tag node)]
                 (:attrs node) (conj (:attrs node))
                 true (into kids)))
    :each    (let [for-form `(for [~(:bind node) ~'each-coll#]
                               ~(splice (children->code node)))]
               (if-some [ec (:else-children node)]
                 `(let [~'each-coll# ~(:coll node)]
                    (if (seq ~'each-coll#)
                      ~for-form
                      ~(splice (into [] (keep node->code) ec))))
                 `(let [~'each-coll# ~(:coll node)] ~for-form)))
    :if      (list 'if (:test node)
                   (splice (children->code node))
                   (splice (into [] (keep node->code) (:else-children node))))
    :code    (if (seq (:children node))
               (concat (:form node) [(splice (children->code node))])
               (:form node))
    :else    nil
    :root    (splice (children->code node))))

;; ---------------------------------------------------------------------------
;; Free-symbol analysis → model destructuring (env/model reserved)

(defn- interop-symbol? [sym]
  (let [n (name sym)] (or (str/starts-with? n ".") (str/ends-with? n "."))))

(defn- model-symbols [form]
  (->> (tree-seq coll? seq form)
       (filter simple-symbol?)
       (remove special-symbol?)
       (remove interop-symbol?)
       (remove #(str/ends-with? (name %) "#"))
       (remove '#{model env})
       (remove resolve)
       (distinct)))

;; ---------------------------------------------------------------------------
;; Compilation & rendering

(defn compile-template
  "Source → {:code <fn form> :fn (fn [model env] hiccup) :doctype? bool}."
  [source]
  (let [tree (attach-elses (parse source))
        doctype? (boolean (some #(= :doctype (:type %)) (:children tree)))
        body (node->code tree)
        params (vec (model-symbols body))
        code `(fn [{:keys ~params :as ~'model} ~'env] ~body)]
    {:code code :fn (eval code) :doctype? doctype?}))

(defn render
  ([compiled model] (render compiled model {}))
  ([compiled model env]
   (str (when (:doctype? compiled) "<!DOCTYPE html>")
        (h/html ((:fn compiled) model env)))))

;; ---------------------------------------------------------------------------
;; Demo

(def index-src
  "doctype html
html
  head
    title= pageName
  body
    ol#books
      for book in books
        li #{(:title book)} by #{(:author book)}")

(def goodies-src
  "//- silent: never reaches the output
// rendered comment
section#goodies
  p: em: strong block expansion, three deep
  != trusted-snippet
  p Escaped #{markup} vs raw !{markup}
  <hr class=\"sep\">
  dl
    each [k v] in (sort specs)
      dt= (name k)
      dd= v
  ul.results
    each hit in hits
      li= hit
    else
      li.empty no results
  input{:type \"text\" :name \"q\" :value (get-in env [:ring-request :params :q])}/")

(def htmx-src
  "//- method elements are ordinary custom elements here; a later
//- post-processing step gives them dispatch semantics
get
  - (let [q (get-in env [:ring-request :params :q])])
    p This content will render on http #[code GET]
    if q
      p Query was: #[strong #{q}]
post
  p And this one on http #[code POST]
  p Hello, #{(:user env)}!
delete
  button{:hx-delete (str \"/books/\" (:id model)) :hx-confirm \"Sure?\"} Delete #[em forever]")

(def model
  {:books [{:title "Programming Clojure" :author "Alex Miller"}
           {:title "Getting Clojure" :author "Russ Olsen"}]
   :pageName "My Page"
   :markup "<b>bold?</b>"
   :trusted-snippet "<aside>I am trusted markup</aside>"
   :specs {:height "10cm" :width "4cm"}
   :hits []
   :id 42})

(def env
  {:user "ricardo"
   :ring-request {:request-method :get :params {:q "clojure"}}})

(defn -main [& _]
  (doseq [[label src] [["index" index-src]
                       ["goodies" goodies-src]
                       ["htmx" htmx-src]]]
    (let [c (compile-template src)]
      (println (str "===== " label ": generated code ====="))
      (pp/pprint (:code c))
      (println (str "===== " label ": html ====="))
      (println (render c model env))
      (println))))


