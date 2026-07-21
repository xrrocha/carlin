(ns carlin.merge-attrs-test
  "Spec §4.6 — the merge contract. Classes always additive; scalars per
  :on-attr-conflict; source order shorthand -> attrs map -> &attributes."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.runtime :as rt]))

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
