(ns carlin.merge-attrs-test
  "Spec §4.6 — the merge contract. Classes always additive; scalars per
  :on-attr-conflict; source order shorthand -> attrs map -> &attributes."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.api :as api]
            [carlin.runtime :as rt]))

(defn- render1 [source]
  (api/render (api/compile-template source {:name "t.carlin"}) {}))

(defn- m [policy short attrs amp] (rt/merge-attrs policy short attrs amp))

(deftest classes-are-additive
  (testing "concatenated in source order, all sources, duplicates preserved"
    (is (= {:class ["btn" "wide" "hot"]}
           (m :error {:class ["btn"]} {:class "wide"} {:class "hot"})))
    (is (= {:class ["a" "a"]} (m :error {:class ["a"]} {:class "a"} nil))))
  (testing "collections flattened; map-conditional truthy keys contribute"
    (is (= {:class ["x" "y" "active"]}
           (m :error nil {:class ["x" ["y"]]} {:class {:active true :off false}}))))
  (testing "classes are OUTSIDE the policy — no conflict under :error"
    (is (map? (m :error {:class ["a"]} {:class "b"} {:class "c"})))))

(deftest class-order-is-textual
  (testing "the ruling-3 witness: shorthand, map, shorthand"
    (is (= "<a class=\"foo bar baz\"></a>"
           (render1 "a.foo{:class \"bar\"}.baz"))))
  (testing "map before shorthand: class sits at the map's position"
    (is (= "<a class=\"bar baz\"></a>"
           (render1 "a{:class \"bar\"}.baz"))))
  (testing "class ATTRIBUTE placement is source order too (rev. 5, one level
            up): the first class source precedes :href, so class renders first"
    (is (= "<a class=\"foo bar\" href=\"/x\"></a>"
           (render1 "a.foo{:href \"/x\" :class \"bar\"}"))))
  (testing "&attributes stays last (§4.6 source order across ALL sources)"
    (is (= "<div class=\"a b c d e\"></div>"
           (render1 "div.a{:class [\"b\" \"c\"]}.d&attributes {:class \"e\"}")))))

(deftest scalar-policy-matrix
  (testing ":error — runtime conflict throws positioned ex-info"
    (is (thrown-with-msg? Exception #"(?i)conflict"
          (m :error {:id "a"} nil {:id "b"}))))
  (testing ":ignore — declared wins, incoming dropped"
    (is (= "a" (:id (m :ignore {:id "a"} nil {:id "b"})))))
  (testing ":last-wins — later source wins"
    (is (= "b" (:id (m :last-wins {:id "a"} nil {:id "b"}))))
    (is (= "c" (:id (m :last-wins {:id "a"} {:id "b"} {:id "c"})))))
  (testing "no conflict: any policy passes scalars through"
    (is (= {:id "a" :href "/x"} (dissoc (m :error {:id "a"} {:href "/x"} nil) :class)))))

(deftest style-merging
  (testing "style maps merge per-key by policy"
    (is (= {:color "red" :margin "0"}
           (:style (m :last-wins nil {:style {:color "blue" :margin "0"}}
                      {:style {:color "red"}}))))
    (is (= {:color "blue" :margin "0"}
           (:style (m :ignore nil {:style {:color "blue" :margin "0"}}
                      {:style {:color "red"}}))))))

(deftest amp-attributes-bare-symbol-is-a-token
  ;; The bare +name lesson, recurring at the &attributes position: a bare
  ;; symbol scans as a word-char TOKEN, so a trailing = / != is a buffered
  ;; sigil, not a symbol constituent. Before the fix, edamame read
  ;; `attributes=` as one symbol — free-symbol nil, attrs silently dropped,
  ;; tail rendered as literal text (the #1424 corpus case masked it: arg
  ;; name and value were both "work").
  (let [tpl (fn [tail]
              (str "mixin w [x]\n  " tail "\n+(w \"V\"){\"data-p\" \"pp\"}"))]
    (testing "bare symbol + buffered = : attrs forwarded AND value evaluated"
      (is (= "<div data-p=\"pp\">V</div>"
             (render1 (tpl "div&attributes attributes= x")))))
    (testing "bare symbol + buffered != : same anatomy, raw branch"
      (is (= "<div data-p=\"pp\">V</div>"
             (render1 (tpl "div&attributes attributes!= x")))))
    (testing "bare symbol alone still forwards (string keys included)"
      (is (= "<div data-p=\"pp\"></div>"
             (render1 (tpl "div&attributes attributes")))))
    (testing "delimited form keeps the reader, buffered tail follows"
      (is (= "<div data-p=\"pp\" k=\"v\">V</div>"
             (render1 (tpl "div&attributes (merge attributes {:k \"v\"})= x")))))))
