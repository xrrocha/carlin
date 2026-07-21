(ns carlin.escaper-test
  "Spec §4.1/§6.1/§7 — the paranoid escaper file (spec §12.2).
  The serializer's security surface: red until carlin.runtime lands."
  (:require [clojure.test :refer [deftest is testing]]
            [carlin.runtime :as rt]))

(deftest text-escaping
  (testing "the five entities (§7.1)"
    (is (= "&amp;" (rt/escape "&")))
    (is (= "&lt;script&gt;" (rt/escape "<script>")))
    (is (= "&quot;q&quot; &#39;a&#39;" (rt/escape "\"q\" 'a'"))))
  (testing "no double escaping"
    (is (= "&amp;amp;" (rt/escape "&amp;"))))
  (testing "nil renders empty (§4.2)"
    (is (= "" (rt/escape nil)))))

(deftest attribute-escaping
  (testing "attribute-value context"
    (is (= "&quot;&gt;&lt;img onerror=x&gt;" (rt/escape-attr "\"><img onerror=x>")))
    (is (= "a&amp;b" (rt/escape-attr "a&b")))))

(deftest raw-marker
  (testing "constructor and predicate"
    (is (rt/raw? (rt/raw "<b>")))
    (is (not (rt/raw? "<b>"))))
  (testing "raw in ELEMENT position bypasses escaping"
    (is (= "<div><b>x</b></div>"
           (rt/render-hiccup [:div (rt/raw "<b>x</b>")] {:mode :html}))))
  (testing "raw in ATTRIBUTE position bypasses escaping (§3.5/Q7)"
    (is (= "<a href=\"/a?x=1&y=2\"></a>"
           (rt/render-hiccup [:a {:href (rt/raw "/a?x=1&y=2")}] {:mode :html}))))
  (testing "escaped text next to raw stays escaped"
    (is (= "<p>&lt;i&gt;<b>x</b></p>"
           (rt/render-hiccup [:p "<i>" (rt/raw "<b>x</b>")] {:mode :html})))))

(deftest profiles
  (testing "void elements: html vs xml (§7.2)"
    (is (= "<br>"   (rt/render-hiccup [:br] {:mode :html})))
    (is (= "<br/>"  (rt/render-hiccup [:br] {:mode :xml}))))
  (testing "boolean attributes: bare vs attr=\"attr\""
    (is (= "<input checked>" (rt/render-hiccup [:input {:checked true}] {:mode :html})))
    (is (= "<input checked=\"checked\"/>" (rt/render-hiccup [:input {:checked true}] {:mode :xml}))))
  (testing "false/nil attributes are omitted"
    (is (= "<input>" (rt/render-hiccup [:input {:checked false :x nil}] {:mode :html}))))
  (testing "dynamic (non-keyword) tag names normalize (§3.2 Q13)"
    (is (= "<p>x</p>" (rt/render-hiccup ["p" "x"] {:mode :html})))))
