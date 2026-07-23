(ns carlin.diagnostics-harness
  "The DIAGNOSTICS corpus runner (spec §12.5) — the mirror of the golden
  corpus, and the answer to a structural blind spot.

  The golden corpus (carlin.harness) holds only LEGAL templates and compares
  OUTPUT BYTES. It therefore cannot observe what carlin does with illegal
  input: a template that should be rejected has no golden to disagree with.
  S29 found five constructs rendering markup invented from a keyword; S30
  found sixteen more accepting malformed input silently or dying as
  unclassified NullPointerExceptions. Both were invisible to a 104-case
  corpus that was green throughout.

  This corpus inverts every axis of that one:

    golden corpus                 diagnostics corpus
    -------------                 ------------------
    legal templates               ILLEGAL templates
    compares output bytes         compares ERROR CLASS + POSITION
    green = correct rendering     green = correct REJECTION
    a case that errors is a bug   a case that COMPILES is a bug

  A case is `<name>.carlin` plus `<name>.edn` holding
  {:class kw, :line n, :col n} — optionally :data (ex-data keys that must
  match), :fixture (a named resolver, since a template cannot express one),
  and :entry :compile-ref with :ref (for errors raised before parsing).

  POSITION IS PART OF THE CONTRACT, not decoration. A class that merely
  defaulted to line 1 would pass a class-only check while being useless to
  the author, so every case asserts the line and column too. Prose is never
  asserted — the ex-info message may evolve freely (§8.3)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [carlin.api :as api]))

(def corpus-dir "test-resources/diagnostics/cases")
(def manifest-file "diagnostics-manifest.edn")

;; ---------------------------------------------------------------------------
;; fixtures — resolvers a template cannot express

(def fixtures
  {:block-source (fn [_from ref]
                   (when (= ref "has-block")
                     {:key "has-block.carlin"
                      :source "block content\n  p leaked"
                      :kind :template}))
   :cycle        (fn [_from ref]
                   {:key (str ref ".carlin")
                    :source (str "include " ref)
                    :kind :template})
   :raw-target   (fn [_from ref]
                   (when (= ref "r")
                     {:key "r.txt" :source "raw text" :kind :raw}))})

;; ---------------------------------------------------------------------------

(defn- case-ids []
  (->> (file-seq (io/file corpus-dir))
       (filter #(str/ends-with? (.getName %) ".carlin"))
       (map #(str/replace (.getName %) #"\.carlin$" ""))
       sort))

(defn- expectation [id]
  (edn/read-string (slurp (io/file corpus-dir (str id ".edn")))))

(defn- actual
  "Compile the case and return what it RAISED, as {:class :line :col :data}.
  A case that compiles returns {:class nil} — which never matches, because
  every expectation names a class. That is the point: in this corpus,
  compiling successfully IS the failure."
  [id {:keys [fixture entry ref]}]
  (let [src  (slurp (io/file corpus-dir (str id ".carlin")))
        opts (cond-> {:name (str id ".carlin")}
               fixture (assoc :resolver (get fixtures fixture)))]
    (try
      (let [c (if (= entry :compile-ref)
                (api/compile-ref ref opts)
                (api/compile-template src opts))]
        ;; Render too: some classes (->js, merge-attrs) are raised at render,
        ;; not compile. A case that survives both is genuinely not rejected.
        (api/render c {} {})
        {:class nil})
      (catch Exception e
        (let [d (ex-data e)]
          (if-let [k (:carlin/error d)]
            {:class k :line (:line d) :col (:col d) :data d}
            {:class :UNCLASSIFIED
             :exception (.getName (class e))
             :message (ex-message e)}))))))

(defn- check
  "nil when the case meets its expectation, else a human-readable reason."
  [id]
  (let [{:keys [class line col data] :as exp} (expectation id)
        got (actual id exp)]
    (cond
      (nil? (:class got))
      "COMPILED — an illegal template was accepted"

      (= :UNCLASSIFIED (:class got))
      (str "UNCLASSIFIED — " (:exception got) ": " (pr-str (:message got)))

      (not= class (:class got))
      (str "class " (:class got) ", expected " class)

      (and line (not= line (:line got)))
      (str "line " (:line got) ", expected " line)

      (and col (not= col (:col got)))
      (str "col " (:col got) ", expected " col)

      (and data (not (every? (fn [[k v]] (= v (get (:data got) k))) data)))
      (str "ex-data mismatch: " (pr-str (select-keys (:data got) (keys data)))
           ", expected " (pr-str data)))))

(defn- results []
  (into {} (map (juxt identity check)) (case-ids)))

(defn- manifest []
  (if (.exists (io/file manifest-file))
    (set (edn/read-string (slurp manifest-file)))
    #{}))

(defn ratchet
  "Cases in the manifest MUST pass. Regression -> exit 1."
  []
  (let [res  (results)
        man  (manifest)
        pass (set (keep (fn [[id r]] (when-not r id)) res))
        regressions (sort (remove pass man))
        promotions  (sort (remove man pass))]
    (println (format "\n== diagnostics corpus == %d / %d\n" (count pass) (count res)))
    (doseq [[id r] (sort res) :when r]
      (println (format "  %-42s %s" id r)))
    (when (seq promotions)
      (println "\npromotions (passing, not yet in the manifest):")
      (doseq [id promotions] (println "  +" id)))
    (if (seq regressions)
      (do (println "\nREGRESSIONS:")
          (doseq [id regressions] (println "  -" id (get res id)))
          (System/exit 1))
      (println "\ndiagnostics ratchet ok."))))

(defn baseline
  "Rewrite the manifest from current reality."
  []
  (let [pass (sort (keep (fn [[id r]] (when-not r id)) (results)))]
    (spit manifest-file
          (str ";; Diagnostics corpus ratchet (spec §12.5). Cases here MUST keep\n"
               ";; failing to compile, with the SAME class and position.\n"
               (pr-str (vec pass)) "\n"))
    (println "baselined" (count pass) "cases")))

(defn show
  "Show one case: bb dshow <case>"
  [id]
  (let [exp (expectation id)]
    (println "--- source ---")
    (println (slurp (io/file corpus-dir (str id ".carlin"))))
    (println "--- expected ---" (pr-str exp))
    (println "--- actual   ---" (pr-str (actual id exp)))
    (println "--- verdict  ---" (or (check id) "PASS"))))
