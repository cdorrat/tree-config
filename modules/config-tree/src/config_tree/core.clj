(ns config-tree.core
  (:require 
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.data.codec.base64 :as b64])
  (:import 
   [java.net Inet4Address InetAddress NetworkInterface InterfaceAddress]
   [java.util Properties Arrays]
   [java.security SecureRandom]
   [javax.crypto Cipher SecretKey SecretKeyFactory]
   [javax.crypto.spec PBEKeySpec PBEParameterSpec SecretKeySpec IvParameterSpec]
   [javax.naming InitialContext]))
 
 
(def env-subnets (atom  [[:dev "10.1.1.1/24" "172.18.39.86/25"]
                         [:test "10.0.1.0/24"]
                         [:prod "10.0.2.0/24"]]))


;; ===================================================================================================
;; support for determining environment based  on subnet

(defn- local-addresses []
  ;; TODO: need to use (NetworkInterface/getNetworkInterfaces) here ...
  (map #(.. % getAddress getHostAddress)
       (->> (Inet4Address/getLocalHost) (NetworkInterface/getByInetAddress) .getInterfaceAddresses)))

(defn- netmask 
  "given a number of bits return the apporpriate netmask as an int"
  [num-bits]
  (bit-shift-left 0xFFFFFFFF (- 32 num-bits)))

(defn- addr-from-str
  "Given an ipv4 address as a dotted quad string (eg. \"127.0.0.1\") return the address as an int"
  [str-addr]
  (reduce  (fn [addr part]
             (bit-or (bit-shift-left addr 8)
                     (bit-and 0xff (Integer/parseInt part))))
           0 (take 4 (str/split str-addr #"\.|/"))))

(defn- in-subnet? 
  "return true if the specified ipv4 address is inthe given subnet.
subnets are specified as \"a.b.c.d/bits\".
If no address is suplied it will check all of the addresses of the local machine against subnet"
  ([subnet]
     (true? (some (partial in-subnet? subnet) (local-addresses))))
  ([subnet addr]
     (let [[sub-addr sub-bits] (str/split subnet #"/")
           mask (netmask (Integer/parseInt sub-bits))]
       (= (bit-and mask (addr-from-str addr))
          (bit-and mask (addr-from-str sub-addr))))))

(defn as-dotted-quad 
  "Given an address as an integer return the equivalent as a dotted quad string"
  [val]
  (str/join "." (map #(format "%d" (bit-and 0xff (bit-shift-right val (* % 8)))) (range 3 -1 -1))))

(def ^{:private true} get-net-env
  (memoize
   #(some (fn [[env & subnets]]
            (when (some in-subnet? subnets)
              env))
          @env-subnets)))

(defn get-env []
  (get-net-env))

(def default-settings 
  {:env (get-env)
   :app-name nil
   :enc-key "^a not so secret key7"})

(defn- app-name [config]
  (when-let [n (:app-name config)]
    (name n)))

;; ===================================================================================================
;; support for encrypted properties
(def ^{:private true :cont true}  enc-marker "enc:")

(defn- create-salt []
  (let [salt (byte-array 8)]
    (.nextBytes (SecureRandom.) salt)
    salt))

(defn- des-crypt [enc-key val]
  (let [iteration-count 19
        salt (create-salt)
        keyspec (PBEKeySpec. (.toCharArray enc-key) salt iteration-count)
        secret (-> (SecretKeyFactory/getInstance "PBEWithMD5AndDES")
                   (.generateSecret keyspec))
        cipher (doto (Cipher/getInstance (.getAlgorithm secret))
                 (.init Cipher/ENCRYPT_MODE secret (PBEParameterSpec. salt iteration-count)))]
    (String. (b64/encode (byte-array (concat salt (.doFinal cipher (.getBytes val "UTF8"))))))))


(defn- des-decrypt [enc-key val]
  (let [iteration-count 19
        b64-decoded (b64/decode (.getBytes val "UTF8"))
        salt (byte-array 8 b64-decoded)
        enc-val (byte-array (drop 8 b64-decoded))
        keyspec (PBEKeySpec. (.toCharArray enc-key) salt iteration-count)
        secret (-> (SecretKeyFactory/getInstance "PBEWithMD5AndDES")
                   (.generateSecret keyspec))
        cipher (doto (Cipher/getInstance (.getAlgorithm secret))
                 (.init Cipher/DECRYPT_MODE secret (PBEParameterSpec. salt iteration-count)))]
    (try 
      (String. (.doFinal cipher enc-val))
      (catch Exception e
        ;; TODO - log an error here
        nil))))

(defn- is-encrypted? [val]
  (and (string? val)
       (.startsWith val enc-marker)))

(defn- decode-value [enc-key val]
  (des-decrypt enc-key (.substring val (count enc-marker))))

(defn encode-value 
  ([val] (encode-value (:enc-key default-settings) val))
  ([secret-key val]
     (str enc-marker (des-crypt secret-key val))))

(defn extract-value 
  ([val] (extract-value  (:enc-key default-settings) val))
  ([secret-key val]
     (if (is-encrypted? val)
       (decode-value secret-key val)
       val)))
    
;; ===================================================================================================
(defprotocol SettingsUtilsProtocol
  (key-details [settings key] "get env/app/key/value details for a single key"))

(defprotocol SettingsProtocol
  "This is the protocol describing basic settings capabilities"
  (has? [settings env app-name key] "return true if the specified setting exists")
  (lookup [settings env app-name key] "retrieve the value associated with the setting if it exists")
  (prop-names [settings env app-name] "returns a sequence of property names"))

(defn get-prop-prefixes 
  "return a list of prefixes that may have properties for an app from highest to lowest priority"
  [app-name]
  (if (empty? app-name)
    [""]
    (conj (vec (reverse (reductions (fn [base val] (str base "." val))
                                    (seq (.split app-name "\\.")))))
          "")))

(defn- keys-to-search 
  "return a tuple of [env app key] that should be searched, most specific first"
  [env app-name key-name]
  (for [e [env nil] app (get-prop-prefixes app-name)]
    [e app key-name]))

;;impl
(defn find-key-with-value
  "return the most specific [env app-name key-name] tuple that has a value"
  [impl key env app-name]
  (some #(when (apply has? impl %) %)
        (keys-to-search env app-name key)))
;;impl
(defn fetch-value 
  "lookup a value, searching ancestors and decrypting values as necessary"
  [impl config key & default]
  (or
   (when-let [found-app (find-key-with-value impl key (:env config) (app-name config))]
     (extract-value (:enc-key config) (apply lookup impl found-app)))
   (first default)))

(defmacro defsettings 
  [type-name fields & specifics]
  (let [[config & _] fields]
    `(deftype ~type-name [~@fields]
       ~@specifics

       SettingsUtilsProtocol
       (key-details [this# key#]
         (when-let [[env# app-name# prop-name#] (find-key-with-value this# key# (:env ~config) (app-name  ~config))]
           {:env env# :app-name app-name# :prop-name prop-name#}))

       clojure.lang.ILookup
       (valAt [this# key#]
         (fetch-value this# ~config key#))
       (valAt [this# key# not-found#]
         (fetch-value this# ~config key# not-found#))

       clojure.lang.Associative
       (containsKey [this# k#]
         (if (find-key-with-value this# k# (:env ~config) (app-name ~config)) true false))
       (entryAt [this# k#]
         (let [val# (fetch-value this# ~config k# ::not-found)]
           (when (not= ::not-found val)
             (clojure.lang.MapEntry. k# val#))))

       clojure.lang.Seqable
       (seq [this#]
         (remove #(-> % second (= ::not-found))
                 (map #(.entryAt ^clojure.lang.Associative this# %)
                      (prop-names this# (:env ~config) (app-name ~config))))))))

(defn prop-details 
  "given a properties instace return a seq of maps with one entry per property.
Each map has the following keys:
    :env          - the environment the property was defined for
    :app-name     - the application qualifier (if any)
    :prop-name    - the proprety name
    :value        - the value"
  [settings]
  (for [[k v] (seq settings)]
    (merge 
     {:env nil
      :app-name nil
      :prop-name k
      :value v}
     (key-details settings k))))


;; ===================================================================================================
;; methods to encode and decode env, app-name and key values from a string
;; used by map and properties file implementations

(def ^:const key-delim
  "the delimiter to separate dev and app-name portions of a key in the map and properties file implementations"
  "/")

(defn- prop-key-prefix [env app-name]
  (letfn [(has-value [v] (and v (not (empty? (name v)))))]
    (str (when (has-value env) 
           (str (name env) key-delim))
         (when (or (has-value app-name) (has-value env))
           (str app-name key-delim)))))

(defn- prop-key-name [env app-name k]
  (str (prop-key-prefix env app-name) (name k)))

(defn- key-name 
  "given a qualified property naem (from a call to prop-key-name) return the property name (without the env or app name prefixes)"
  [k]
  (let [str-k (name k)
        sep-idx (.lastIndexOf str-k key-delim)]
    (if (>= sep-idx 0)
      (.substring str-k (inc sep-idx))
      str-k)))
    
;; ===================================================================================================
;; java properties file implementation

(defsettings PropertiesSettings [config props]
  SettingsProtocol
  (has? [settings env app-name key]
        (.containsKey props (prop-key-name env app-name key)))
  (lookup [settings env app-name key]
          (.getProperty props (prop-key-name env app-name key)))
  (prop-names [settings env app-name]
              (distinct (map #(-> % key-name keyword)
                             (seq (.keySet props))))))

(defn- find-stream 
  "Try to open a file in order of:
  a). classpath
  b). any uri nuderstood by clojure.java.io/input-stream"
  [uri]
  (if-let [is (io/resource uri)]
    (io/input-stream is)
    (io/input-stream uri)))

(defn properties-settings [file-uri & {:as opts}]
  (with-open [is (find-stream file-uri)]
    (PropertiesSettings. (merge default-settings opts)
                         (doto (Properties.) (.load is)))))

;; ===================================================================================================
;; in memory map based implementation

(defsettings MapSettings [config m]
  SettingsProtocol
  (has? [_ env app-name key]
        (contains? m (keyword (prop-key-name env app-name key))))
  (lookup [settings env app-name key]
          (get m (keyword (prop-key-name env app-name key))))
  (prop-names [settings env app-name]
              (distinct (map #(-> % key-name keyword) (keys m)))))

(defn map-settings [m & {:as opts}]
  (MapSettings. (merge default-settings opts) m))
                

;; ===================================================================================================
;; os & environment settings

(defn env-settings [& opts]
  (let [env (into {} (for [[k v] (System/getenv)]
                       [(keyword k) v]))]
    (apply map-settings env (flatten (seq opts)))))

                       
(defn java-prop-settings [& opts]
  (PropertiesSettings.  (merge default-settings opts)
                        (System/getProperties)))


;; ===================================================================================================
;; chanined settings

(defsettings ChainedSettings [config delegates]
  SettingsProtocol
  (has? [_ env app-name key]
        (some #(has? % env app-name key) delegates))
  (lookup [settings env app-name key]
        (some #(lookup % env app-name key) delegates))
  (prop-names [settings env app-name]
              (into #{} (mapcat #(prop-names % env app-name) (reverse delegates)))))

(defn chained-settings [& delegates]
  (ChainedSettings. default-settings delegates))
