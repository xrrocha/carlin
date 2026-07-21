(ns carlin.cursor
  "Positioned source access and the ONE error constructor (spec §8.3).

  Every carlin compile-time error is an `ex-info` whose data carries
  {:carlin/error <class-kw>, :key <template-key>, :line n, :col n} and whose
  message includes a formatted excerpt — the offending line with a caret.
  All passes construct errors through `fail!`; the diagnostics suite
  (test/carlin/diagnostics_test.cljc) asserts class + position, never prose,
  so message wording may evolve freely while the data contract holds.

  Lines and columns are 1-based, template-relative. `offset-of` and
  `pos-of` convert between (line, col) and character offsets so that
  passes can hand suffixes of the source to the reader (carlin.platform)
  and rebase what comes back."
  (:require [clojure.string :as str]))

(defn make
  "A cursor over `source`, identified by `key` (the resolver key / :name
  compile option; purely diagnostic)."
  [source key]
  (let [lines (str/split (or source "") #"\n" -1)]         ; -1: keep trailing empties
    {:key    (or key "<template>")
     :source (or source "")
     :lines  lines
     ;; character offset of the start of each line (newline = 1 char)
     :starts (vec (reductions (fn [off l] (+ off (count l) 1)) 0 lines))}))

(defn line-count [cur] (count (:lines cur)))

(defn line-at
  "The text of 1-based line `n`, or nil past EOF."
  [cur n]
  (get (:lines cur) (dec n)))

(defn offset-of
  "Character offset of 1-based (line, col) into the full source."
  [cur line col]
  (+ (get (:starts cur) (dec line)) (dec col)))

(defn suffix-from
  "The source text from (line, col) to EOF — what the reader consumes from."
  [cur line col]
  (subs (:source cur) (offset-of cur line col)))

(defn excerpt
  "The offending line with a caret under `col`."
  [cur line col]
  (let [l (or (line-at cur line) "")]
    (str l "\n" (apply str (repeat (max 0 (dec col)) \space)) "^")))

(defn fail!
  "Throw the positioned carlin error. `class` is the error keyword the
  diagnostics suite matches on (:carlin/error); `pos` is {:line n :col n}
  (missing :col defaults to 1); `extra` merges into ex-data."
  ([cur class pos] (fail! cur class pos nil))
  ([cur class {:keys [line col] :or {col 1}} extra]
   (throw (ex-info (str (name class) " at " (:key cur) ":" line ":" col "\n"
                        (excerpt cur line col))
                   (merge {:carlin/error class
                           :key  (:key cur)
                           :line line
                           :col  col}
                          extra)))))
