(ns tree-config.encryption)


(defprotocol ConfigEncryptor
  "Support for encrypting configuration values"
  (encrypt [this plain-text])
  (decrypt [this cipher-text]))
