(ns tree-config.rsa-encryption
   (:require
    [tree-config.encryption :as tc-enc]
    [clojure.tools.logging :as log]
    [clojure.java.io :as io]
    [clojure.data.codec.base64 :as b64])
   (:import 
    [java.security SecureRandom KeyPairGenerator Security KeyFactory]
    [javax.crypto Cipher SecretKey SecretKeyFactory]
    [javax.crypto.spec PBEKeySpec PBEParameterSpec SecretKeySpec IvParameterSpec]
    [java.security.spec RSAKeyGenParameterSpec X509EncodedKeySpec]
    [org.bouncycastle.asn1.x509 SubjectPublicKeyInfo]
    [javax.naming InitialContext]
    [org.bouncycastle.openssl PEMWriter PEMParser PEMEncryptedKeyPair PEMKeyPair]
    [org.bouncycastle.openssl.jcajce JcePEMDecryptorProviderBuilder JcaPEMKeyConverter]
    [org.bouncycastle.operator.jcajce JcaContentSignerBuilder]
    [org.bouncycastle.cert X509CertificateHolder]
    [org.bouncycastle.asn1 DERBitString ASN1Sequence]
    [org.bouncycastle.asn1.pkcs PrivateKeyInfo]))


(def ^:const BC-PROVIDER "BC")
(def ^:const BC-CIPHER "RSA/ECB/OAEPWithSHA1AndMGF1Padding")

(when-not (java.security.Security/getProvider BC-PROVIDER)
 (Security/addProvider (org.bouncycastle.jce.provider.BouncyCastleProvider.)))

(defn create-new-keypair 
  "utility function to create a new public/private key pair for encryption"
  [public-key-file private-key-file]  
  (let [random (SecureRandom.)
        spec (RSAKeyGenParameterSpec. 2048 RSAKeyGenParameterSpec/F4)
        generator (doto (KeyPairGenerator/getInstance "RSA", BC-PROVIDER)
                    (.initialize spec random))
        key-pair (.generateKeyPair generator)]
    (with-open [pub (PEMWriter. (io/writer private-key-file))]
      (.writeObject pub (.getPrivate key-pair)))
    (with-open [pub (PEMWriter. (io/writer public-key-file))]
      (.writeObject pub (.getPublic key-pair)))
    key-pair))

(defn- public-key-from-cert
  "given an X509 cert folder return the RSA public key"
  [^SubjectPublicKeyInfo pk-info]
  (let [xspec (-> (DERBitString. pk-info) .getBytes (X509EncodedKeySpec.))]
    (-> (.. pk-info getAlgorithmId getObjectId getId)
        (KeyFactory/getInstance BC-PROVIDER) 
        (.generatePublic xspec))))

(defn load-private-key 
  "Load a PEM formatted private key file"
  ([pem-file]
   (load-private-key pem-file ""))
  ([pem-file password]
   (with-open [pem-reader (io/reader pem-file)]
     (let [object (.readObject (PEMParser. pem-reader))
           pem-converter (doto (JcaPEMKeyConverter.)
                           (.setProvider BC-PROVIDER))
           keypair  #(.getKeyPair pem-converter object)
           private-key #(.getPrivateKey pem-converter object)]
       (condp instance? object
         PEMEncryptedKeyPair (.getPrivate (.decryptKeyPair (keypair) (-> (JcePEMDecryptorProviderBuilder.) 
                                                                         (.build password))))
         PrivateKeyInfo (private-key)
         PEMKeyPair (.getPrivate (keypair))
         (throw (RuntimeException. (str "unknown PEM file conents from " pem-file))))))))

(defn load-public-key 
  "Load a public key from either a PEM formatted key file or x509 certificate"
  ([pem-file]
   (load-public-key pem-file ""))
  ([pem-file password]
   (with-open [pem-reader (io/reader pem-file)]
     (let [object (.readObject (PEMParser. pem-reader))
           keypair  #(.getKeyPair (doto (JcaPEMKeyConverter.)
                                    (.setProvider BC-PROVIDER)) 
                                  object)]
       (condp instance? object
         PEMEncryptedKeyPair (.getPublic (.decryptKeyPair (keypair) (-> (JcePEMDecryptorProviderBuilder.) 
                                                                        (.build password))))
         PEMKeyPair (.getPublic (keypair))
         X509CertificateHolder (public-key-from-cert (.getSubjectPublicKeyInfo object))
         SubjectPublicKeyInfo (public-key-from-cert object)
         (throw (RuntimeException. (str "unknown PEM file conents from " pem-file))))))))


(defn- pk-crypt [public-key val]
  (let  [cipher (doto (Cipher/getInstance BC-CIPHER BC-PROVIDER)
                  (.init Cipher/ENCRYPT_MODE public-key))]
    (String. (b64/encode (byte-array (.doFinal cipher (.getBytes val "UTF8")))))))


(defn- pk-decrypt [private-key enc-val]
  (let [cipher (doto (Cipher/getInstance BC-CIPHER BC-PROVIDER)
                 (.init Cipher/DECRYPT_MODE private-key))]
    (try 
      (String. (.doFinal cipher (-> (.getBytes enc-val "UTF8") b64/decode byte-array)))
      (catch Exception e
        (log/error e "failed decoding encrypted config property: " val)
        nil))))

(defrecord RSAEncryption [public-key private-key]
  tc-enc/ConfigEncryptor
  (encrypt [this plain-text]
    (pk-crypt public-key plain-text))
  (decrypt [this cipher-text]
    (pk-decrypt private-key cipher-text)))

(defn rsa-encryption
  "Create a new RSA encryption strategy, expects a pem formatter private key for decryption & either a pem formatted public key or x509 certificate for encryption"
  ([private-key-filename]
   (rsa-encryption nil private-key-filename))
  ([public-key-filename private-key-filename]
   (->RSAEncryption (when public-key-filename
                      (load-public-key public-key-filename))
                    (when private-key-filename
                        (load-private-key private-key-filename)))))
