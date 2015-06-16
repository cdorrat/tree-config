(ns tree-config.jwe-encryption-test
  (:require [clojure.test :refer :all]
            [tree-config.encryption :as tc-enc]
            [tree-config.jwe-encryption :as jwe]))


(deftest round-trip-encryption-works
  (let [strat (jwe/jwe-encryption "test/sample_public_key.pem" "test/sample_private_key.pem" {})
        plain-text "my secret"
        enc-text (tc-enc/encrypt strat plain-text)
        dec-text (tc-enc/decrypt strat enc-text)]
    (is (not= plain-text enc-text))
    (is (= plain-text dec-text))))
