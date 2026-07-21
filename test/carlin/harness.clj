(ns carlin.harness
  "Golden-file conformance runner over the morphed pugjs corpus (spec §12.1),
  with a manifest ratchet: cases listed in conformance-manifest.edn MUST pass
  (regression -> exit 1); passing cases not yet in the manifest are reported
  as promotions. `baseline` rewrites the manifest from current reality.

  Comparison is STRUCTURAL-WHITESPACE-INSENSITIVE, TEXT-EXACT: the vendored
  goldens are pug pretty-mode output, and carlin excludes pretty printing
  (spec §10), so newline+indent at tag boundaries — exactly the pretty-mode
  delta — is collapsed on both sides before an otherwise exact comparison.
  Whitespace inside text nodes is never touched; whitespace-significant cases
  (pre) may need individual golden adjustment, logged per corpus README."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [carlin.api :as api]))

(def corpus-dir "test-resources/corpus/pugjs-3.0.2/cases")
(def manifest-file "conformance-manifest.edn")

;; ---------------------------------------------------------------------------
;; spec-section tagging (for the report only)

(def ^:private section-prefixes
  [["attrs"        "§3.5 attributes"]
   ["mixin"        "§3.13 mixins"]
   ["case"         "§3.7 case"]
   ["code"         "§3.6 code"]
   ["each"         "§3.8 iteration"] ["for" "§3.8 iteration"]
   ["doctype"      "§3.9 doctype"]
   ["comment"      "§3.10 comments"]
   ["include"      "§3.11 include"]
   ["inheritance"  "§3.14 inheritance"] ["extends" "§3.14 inheritance"]
   ["blocks"       "§3.14 inheritance"] ["layout" "§3.14 inheritance"]
   ["filter"       "§3.12 filters"]
   ["escap"        "§4.1 escaping"] ["interpolation" "§4.2 interpolation"]
   ["inline-tag"   "§4.2 interpolation"] ["intepolated" "§3.2 tags"]
   ["tag"          "§3.2 tags"]
   ["text"         "§3.3 text"] ["quotes" "§3.3 text"]
   ["classes"      "§3.5 attributes"] ["styles" "§3.5 attributes"]
   ["html"         "§3.3 text"] ["template" "misc"]
   ["vars"         "§3.6 code"] ["source" "misc"]])

(defn- section-of [id]
  (let [base (last (str/split id #"/"))]
    (or (some (fn [[p s]] (when (str/starts-with? base p) s)) section-prefixes)
        "misc")))

;; ---------------------------------------------------------------------------
;; corpus resolver: file-based, .carlin defaulting, :kind by extension
;; (the battery's stand-in for the shipped file-resolver, spec §5.4)

(defn corpus-resolver [root]
  (fn [from ref]
    (let [base (if (and from (str/includes? from "/"))
                 (subs from 0 (str/last-index-of from "/"))
                 root)
          ref  (str/replace ref #"^\./" "")
          cand (if (str/starts-with? ref "/")
                 (str root ref)
                 (str base "/" ref))
          tryf (fn [p] (let [f (io/file p)] (when (.isFile f) f)))
          f    (or (tryf cand)
                   (when-not (re-find #"\.[\w]+$" cand) (tryf (str cand ".carlin"))))]
      (when f
        {:key (.getPath f)
         :source (slurp f)
         :kind (if (str/ends-with? (.getName f) ".carlin") :template :raw)}))))

;; ---------------------------------------------------------------------------
;; runner

(def case-model
  "The model the pugjs cases were authored against. Pug's own case runner
  supplies locals; the morphed corpus inherited goldens that depend on them
  (`mixin-hoist` renders `h1= title` as `<h1>Pug</h1>` from a template that
  never mentions `title`). Supplying the model is corpus fidelity, NOT a
  loosening of the comparator — the comparison stays structural-whitespace-
  insensitive and text-exact. Ricardo-ratified, S9.

  Keep this MINIMAL: every key added here can silently change other cases'
  meaning, so add one only with a measured zero-collateral run."
  {:title "Pug"})

(def case-filters
  "Filters the pugjs cases were authored against. Pug's own case runner
  registers a `custom` filter; carlin's `:filters` compile option (§5.5) is
  the documented way to supply one, so this is corpus fidelity through a
  public contract — not a special case in the compiler. Ricardo-ratified, S13.

  Keep this MINIMAL, like `case-model`: carlin ships `:verbatim` and `:cdata`
  and nothing else on purpose (§3.12), and the battery must not quietly
  become a place where that position erodes."
  {:custom (fn [text _attrs] (str "BEGIN" text "END"))})

(defn- golden-cases []
  (->> (file-seq (io/file corpus-dir))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".carlin")))
       (keep (fn [f]
               (let [html (io/file (str/replace (.getPath f) #"\.carlin$" ".html"))]
                 (when (.isFile html)
                   {:id (str/replace (.getPath f) (str corpus-dir "/") "")
                    :src (slurp f)
                    :expected (slurp html)}))))
       (sort-by :id)))

(defn- canon [s]
  (-> s
      (str/replace #">[ \t]*\n[ \t]*" ">")
      (str/replace #"[ \t]*\n[ \t]*<" "<")
      (str/replace #"[ \t]*\n[ \t]*" "\n")   ; residual text-internal indentation
      (str/trim)))

(defn run-case [{:keys [id src expected]}]
  (try
    (let [compiled (api/compile-template src {:name (str corpus-dir "/" id)
                                              :resolver (corpus-resolver corpus-dir)
                                              :filters case-filters})
          actual   (api/render compiled case-model {})]
      (if (= (canon actual) (canon expected))
        {:id id :status :pass}
        {:id id :status :fail :actual actual :expected expected}))
    (catch Throwable t
      {:id id :status :error :message (str (.getMessage t))})))

(defn- load-manifest []
  (let [f (io/file manifest-file)]
    (if (.isFile f) (set (edn/read-string (slurp f))) #{})))

(defn report [results manifest]
  (let [by-section (group-by #(section-of (:id %)) results)
        pass? #(= :pass (:status %))]
    (println "\n== conformance by spec section ==")
    (doseq [[sec rs] (sort-by key by-section)]
      (println (format "  %-22s %3d / %-3d" sec (count (filter pass? rs)) (count rs))))
    (println (format "  %-22s %3d / %-3d" "TOTAL"
                     (count (filter pass? results)) (count results)))
    (let [passing    (set (map :id (filter pass? results)))
          regressions (remove passing manifest)
          promotions  (remove manifest passing)]
      (when (seq promotions)
        (println "\n== newly passing (add to manifest) ==")
        (doseq [id (sort promotions)] (println "  +" id)))
      (when (seq regressions)
        (println "\n== REGRESSIONS (in manifest, not passing) ==")
        (doseq [id (sort regressions)] (println "  -" id)))
      {:regressions (vec regressions) :promotions (vec promotions)})))

(defn ratchet [& _]
  (let [results (mapv run-case (golden-cases))
        {:keys [regressions]} (report results (load-manifest))]
    (when (seq regressions)
      (println "\nRATCHET FAILED:" (count regressions) "regression(s).")
      (System/exit 1))
    (println "\nratchet ok.")))

(defn baseline [& _]
  (let [results (mapv run-case (golden-cases))
        passing (->> results (filter #(= :pass (:status %))) (map :id) sort vec)]
    (spit manifest-file (with-out-str
                          (println ";; cases the ratchet requires to pass — regenerate with: bb baseline")
                          (prn passing)))
    (report results (set passing))
    (println (format "\nbaseline written: %d case(s) -> %s" (count passing) manifest-file))))

(defn show [& [id]]
  (let [c (first (filter #(= id (:id %)) (golden-cases)))
        r (run-case c)]
    (println "status:" (:status r))
    (when (:message r) (println "error:" (:message r)))
    (when (:actual r)
      (println "---- expected ----") (println (:expected r))
      (println "---- actual ----") (println (:actual r)))))
