(ns kotoba.dtn.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.dtn :as dtn]
            [kotoba.dtn.link :as link]
            [kotoba.dtn.router :as router]))

(def ^:private src "+819012345678")
(def ^:private dest "+12125550199")
(def ^:private elsewhere "+447700900123")

(deftest route-decision-test
  (testing "forwards over a direct reachable link"
    (let [b (dtn/bundle src dest {})
          l (link/link "L1" dest :internet-overlay :reachable? true)
          decision (router/route-decision b [l])]
      (is (= :forward (:dtn/action decision)))
      (is (= l (:dtn/via decision)))))

  (testing "prefers the transport-kind earliest in the default priority among multiple reachable links"
    (let [b (dtn/bundle src dest {})
          l-mesh (link/link "L1" dest :mesh-radio :reachable? true)
          l-net  (link/link "L2" dest :internet-overlay :reachable? true)
          l-sat  (link/link "L3" dest :satellite :reachable? true)
          decision (router/route-decision b [l-mesh l-net l-sat])]
      (is (= :forward (:dtn/action decision)))
      (is (= l-net (:dtn/via decision)))))

  (testing "priority order is overridable (resilience-first: internet last)"
    (let [b (dtn/bundle src dest {})
          l-mesh (link/link "L1" dest :mesh-radio :reachable? true)
          l-net  (link/link "L2" dest :internet-overlay :reachable? true)
          l-sat  (link/link "L3" dest :satellite :reachable? true)
          decision (router/route-decision b [l-mesh l-net l-sat]
                                           :priority [:mesh-radio :satellite :internet-overlay])]
      (is (= l-mesh (:dtn/via decision)))))

  (testing "no reachable link to the destination stores the bundle"
    (let [b (dtn/bundle src dest {})
          unreachable (link/link "L1" dest :internet-overlay :reachable? false)
          wrong-neighbor (link/link "L2" elsewhere :internet-overlay :reachable? true)]
      (is (= {:dtn/action :store} (router/route-decision b [unreachable wrong-neighbor])))))

  (testing "no links at all stores the bundle"
    (is (= {:dtn/action :store} (router/route-decision (dtn/bundle src dest {}) [])))
    (is (= {:dtn/action :store} (router/route-decision (dtn/bundle src dest {}) nil)))))

(deftest expire-store-test
  (testing "drops expired bundles while keeping live ones"
    (let [live    (dtn/bundle src dest {} :creation-ts 1000 :lifetime-ms 5000)
          expired (dtn/bundle src dest {} :creation-ts 0 :lifetime-ms 100)
          result  (router/expire-store [live expired] 6000)]
      (is (= [live] (:kept result)))
      (is (= [expired] (:expired result)))))
  (testing "all live"
    (let [live (dtn/bundle src dest {} :creation-ts 1000 :lifetime-ms 5000)
          result (router/expire-store [live] 2000)]
      (is (= [live] (:kept result)))
      (is (= [] (:expired result)))))
  (testing "empty input"
    (is (= {:kept [] :expired []} (router/expire-store [] 1000)))))
