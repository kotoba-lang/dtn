(ns kotoba.dtn.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.dtn :as dtn]
            [kotoba.dtn.auth :as auth]))

(def ^:private src "+819012345678")
(def ^:private dest "+12125550199")
(def ^:private secret "correct-horse-battery-staple")
(def ^:private wrong-secret "an-attackers-guess")

(deftest sign-bundle-test
  (testing "adds a :dtn/signature hex string, leaves other fields untouched"
    (let [b (dtn/bundle src dest {:body "hi"})
          signed (auth/sign-bundle b secret)]
      (is (nil? (:dtn/signature b)) "precondition: unsigned bundle has no signature field")
      (is (string? (:dtn/signature signed)))
      (is (re-matches #"[0-9a-f]{64}" (:dtn/signature signed))
          "HMAC-SHA256 hex digest is 64 lowercase hex chars")
      (is (= b (dissoc signed :dtn/signature))
          "signing does not alter any field other than :dtn/signature")))
  (testing "signing is deterministic for the same bundle+secret"
    (let [b (dtn/bundle src dest {:body "hi"} :creation-ts 1000)]
      (is (= (:dtn/signature (auth/sign-bundle b secret))
             (:dtn/signature (auth/sign-bundle b secret))))))
  (testing "different secrets produce different signatures"
    (let [b (dtn/bundle src dest {:body "hi"})]
      (is (not= (:dtn/signature (auth/sign-bundle b secret))
                (:dtn/signature (auth/sign-bundle b wrong-secret)))))))

(deftest verify-bundle-test
  (testing "a validly signed bundle verifies true against the same secret"
    (let [b (dtn/bundle src dest {:body "hi"})
          signed (auth/sign-bundle b secret)]
      (is (true? (auth/verify-bundle signed secret)))))

  (testing "a tampered body fails verification (tamper-evidence)"
    (let [b (dtn/bundle src dest {:body "hi"})
          signed (auth/sign-bundle b secret)
          tampered (assoc-in signed [:dtn/payload :body] "TAMPERED")]
      (is (false? (auth/verify-bundle tampered secret)))))

  (testing "a tampered :dtn/source (forged origin) fails verification"
    (let [b (dtn/bundle src dest {:body "hi"})
          signed (auth/sign-bundle b secret)
          forged (assoc signed :dtn/source "dtn:+15551234567")]
      (is (false? (auth/verify-bundle forged secret)))))

  (testing "verifying with the wrong secret fails"
    (let [b (dtn/bundle src dest {:body "hi"})
          signed (auth/sign-bundle b secret)]
      (is (false? (auth/verify-bundle signed wrong-secret)))))

  (testing "a bundle with no :dtn/signature at all fails verification (not an exception)"
    (let [b (dtn/bundle src dest {:body "hi"})]
      (is (false? (auth/verify-bundle b secret)))))

  (testing "a bundle signed with the wrong secret, then verified with the right one, fails"
    (let [b (dtn/bundle src dest {:body "hi"})
          mis-signed (auth/sign-bundle b wrong-secret)]
      (is (false? (auth/verify-bundle mis-signed secret))))))
