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
           (rt/render-hiccup [:p "<i>" (rt/raw "<b>x</b>")] {:mode :html}))))
  (testing "raw in CLASS position: one verbatim token, whitespace and all
            (records-are-maps: Raw must not fall into class-tokens' map branch)"
    (is (= "<foo class=\"<%= bar %> lol rofl\"></foo>"
           (rt/render-hiccup [:foo {:class (rt/raw "<%= bar %> lol rofl")}]
                             {:mode :html}))))
  (testing "mixed class list: non-raw tokens escape, raw tokens pass through"
    (is (= "<foo class=\"a b<c on\"></foo>"
           (rt/render-hiccup [:foo {:class ["a" (rt/raw "b<c") {:on true}]}]
                             {:mode :html}))))
  (testing "plain string class still escapes (the boundary: raw is opt-in)"
    (is (= "<foo class=\"&lt;%= bar %&gt;\"></foo>"
           (rt/render-hiccup [:foo {:class "<%= bar %>"}] {:mode :html})))))

(deftest json-attribute-values
  (testing "the pipeline shape (§3.5, rev. 7 ruling 2): ->js first, escaper second"
    (is (= "<button hx-vals=\"{&quot;id&quot;:7}\"></button>"
           (rt/render-hiccup [:button {:hx-vals {:id 7}}] {:mode :html})))
    (is (= "<div data-user=\"{&quot;name&quot;:&quot;tobi&quot;}\"></div>"
           (rt/render-hiccup [:div {:data-user {:name "tobi"}}] {:mode :html}))))
  (testing "vectors and seqs JSON-encode too"
    (is (= "<div data-xs=\"[1,2,3]\"></div>"
           (rt/render-hiccup [:div {:data-xs [1 2 3]}] {:mode :html}))))
  (testing ":style is exempt — maps stay CSS, strings pass through"
    ;; Pug 3.0.2, probed 2026-07-22: a style MAP renders `k:v;` per pair,
    ;; trailing semicolon included; a style STRING passes through untouched.
    ;; The original pin asserted no terminator — written from assumption,
    ;; never probed (S25). The implementation was right; the pin was wrong.
    (is (= "<div style=\"color:red;\"></div>"
           (rt/render-hiccup [:div {:style {:color "red"}}] {:mode :html})))
    (is (= "<div style=\"color:red\"></div>"
           (rt/render-hiccup [:div {:style "color:red"}] {:mode :html})))
    ;; an empty style map is omitted, like an empty class (same probe run)
    (is (= "<div></div>"
           (rt/render-hiccup [:div {:style {}}] {:mode :html}))))
  (testing "raw wrapping a whole value still bypasses everything (§6.1 wins)"
    (is (= "<div data-x=\"{\"></div>"
           (rt/render-hiccup [:div {:data-x (rt/raw "{")}] {:mode :html}))))
  (testing "the ->js \\u003C guard rides along harmlessly (§6.3); > is the
            HTML escaper's job now that js-string is narrowed to < (S16 (a))"
    (is (= "<div data-s=\"[&quot;\\u003C/script&gt;&quot;]\"></div>"
           (rt/render-hiccup [:div {:data-s ["</script>"]}] {:mode :html})))))

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
