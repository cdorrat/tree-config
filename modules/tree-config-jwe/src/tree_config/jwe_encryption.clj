(ns tree-config.jwe-encryption
  (:require
   [tree-config
    [encryption :as tc-enc]
    [rsa-encryption :as rsa-enc]])
  (:import [com.nimbusds.jose JWEAlgorithm JWEHeader EncryptionMethod JWEObject Payload EncryptionMethod ]
           [com.nimbusds.jose.crypto RSAEncrypter RSADecrypter]))

(defn- jwe-crypt [alg enc public-key plain-text]
  (let [header (JWEHeader. (JWEAlgorithm/parse alg) (EncryptionMethod/parse enc))
        obj (JWEObject. header (Payload. ^String plain-text))
        encryptor (RSAEncrypter. public-key)]
    (.encrypt obj encryptor)
    (.serialize obj)))

(defn- jwe-decrypt [private-key cipher-text]
  (let [decryptor (RSADecrypter. private-key)]
    (some-> (doto (JWEObject/parse cipher-text)
              (.decrypt decryptor))
            .getPayload
            str)))

(defrecord JWEEncryption [public-key private-key alg enc]
  tc-enc/ConfigEncryptor
  (encrypt [this plain-text]
    (jwe-crypt alg enc public-key plain-text))
  (decrypt [this cipher-text]
    (jwe-decrypt private-key cipher-text)))

(defn jwe-encryption
  "Create a new JWE encryption strategy, expects a pem formatter private key for decryption & either a pem formatted public key or x509 certificate for encryption"
  ([private-key-filename]
   (jwe-encryption nil private-key-filename {}))
  ([public-key-filename private-key-filename]
      (jwe-encryption public-key-filename private-key-filename {}))
  ([public-key-filename private-key-filename {:keys [alg enc] :or {alg "RSA-OAEP" enc "A128GCM"} :as opts}]
   (->JWEEncryption (when public-key-filename
                      (rsa-enc/load-public-key public-key-filename))
                    (when private-key-filename
                      (rsa-enc/load-private-key private-key-filename))
                    alg enc)))

