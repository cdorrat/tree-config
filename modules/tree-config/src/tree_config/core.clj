(ns tree-config.core
  (:require 
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.data.codec.base64 :as b64])
  (:import 
   [java.net Inet4Address InetAddress NetworkInterface InterfaceAddress]
   [java.util Properties Arrays]
   [java.security SecureRandom]
   [javax.crypto Cipher SecretKey SecretKeyFactory]
   [javax.crypto.spec PBEKeySpec PBEParameterSpec SecretKeySpec IvParameterSpec]
   [javax.naming InitialContext]))
 
 
;; (def env-subnets (atom  [[:dev "10.1.1.1/24" "172.18.39.86/25"]
;;                          [:test "10.0.1.0/24"]
;;                          [:prod "10.0.2.0/24"]]))


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

(defn- get-net-env
  [env-subnets]
  (some (fn [[env & subnets]]
          (when (some in-subnet? subnets)
            env))
        env-subnets))

(def get-env 
  "determine the environment we're running in as follows:
   1. look for a java property called \"SETTINGS_ENV\"
   2. look for an environmen tvariable called \"SETTINGS_ENV\"
   3. determine the environment based on the ipv4 subnets in the :env-subnets config property
   4. default to :dev"
  (memoize
   (fn [settings]
     (or
      (keyword (System/getProperty "SETTINGS_ENV"))
      (some-> (System/getenv) (.get "SETTINGS_ENV") keyword)
      (get-net-env (:env-subnets settings))
      :dev))))

(def default-settings-vals 
  {:app-name nil
   :enc-key "^a not so secret key7"
   :throw-on-failure? true
   :env-subnets []})

(defn default-settings [overrides]
  (let [merged (merge default-settings-vals overrides)]
    (if (:env overrides)
      merged
      (assoc merged :env (get-env merged)))))  

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
  ([val] (encode-value (:enc-key default-settings-vals) val))
  ([secret-key val]
     (str enc-marker (des-crypt secret-key val))))

(defn extract-value 
  ([val] (extract-value  (:enc-key default-settings-vals) val))
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

(defn find-key-with-value
  "return the most specific [env app-name key-name] tuple that has a value"
  [impl key env app-name]
  (some #(when (apply has? impl %) %)
        (keys-to-search env app-name key)))

(defn fetch-value 
  "lookup a value, searching ancestors and decrypting values as necessary"
  [impl config key & default]
  (or
   (when-let [found-app (find-key-with-value impl key (:env config) (app-name config))]
     (let [v (extract-value (:enc-key config) (apply lookup impl found-app))]
       (log/debug "loaded config value: " (:env config) "/" (app-name config) "/" key " = " v)
       v))
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
         (seq
          (remove #(-> % second (= ::not-found))
                  (map #(.entryAt ^clojure.lang.Associative this# %)
                       (prop-names this# (:env ~config) (app-name ~config)))))))))

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
  (MapSettings. (default-settings opts) m))
    
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
  [uri throw-on-failure?]
  (if-let [is (io/resource uri)]
    (io/input-stream is)
    (try 
      (io/input-stream uri)
      (catch Exception e
        (when throw-on-failure?
          (throw e))))))
      

(defn properties-settings [file-uri & {:as opts}]
  (let [settings (default-settings opts)        
        is (find-stream file-uri (:throw-on-failure? settings))]
      (if is
        (with-open [is is]
          (PropertiesSettings. settings (doto (Properties.) (.load is))))
        (do
          (log/warn "unable to load settings from property file: " file-uri)
          (map-settings {})))))
          
       

;; ===================================================================================================
;; edn config data                

(defn edn-config 
  "Config read from a map in edn format. The file should contain a map in edn format, the top level keys 
will be used as the properties and can optionally contain env and app-name sepcifiers, 
eg. {:global.prop \"a prop visible to everyone\"
     :myapp/app.prop \"prop only visible to myapp\"
     :dev/myapp/dev.app.prop \"prop visible to myapp in the dev environment\"
     :dev//dev.prop \"property visible ot all apps in dev env\"}
Params:
  file-uri - location of the edn file, first looked up in classpath then as a clojure.java.io/input-stream uri
Options:
   :config-key  - the project.clj key to get config values from (default :config)
   :config-file - the leiningen project file to read (default \"project.clj\")
   :env         - the environment
   :app-name    - the app name (keyword or string)
   :enc-key     - the key to use when encrypting & decrypting data"

  [file-uri & {:as opts}]
  (let [settings (default-settings opts)
        failed-loading (fn [& msgs]
                          (log/warn "failed to load edn settings from: " file-uri " " msgs)
                          (map-settings {}))
        is (find-stream file-uri (:throw-on-failure? settings))]
      (if is
        (with-open [is (java.io.PushbackReader. (io/reader is))]
          (try 
            (apply map-settings (edn/read is) (interleave (keys settings) (vals settings)))
            (catch Exception e
              (if (:throw-on-failure? settings)
                (throw e)
                (failed-loading (.getMessage e))))))
        (failed-loading))))

;; ===================================================================================================
;; os & environment settings

(defn env-settings [& opts]
  (let [env (into {} (for [[k v] (System/getenv)]
                       [(keyword k) v]))]
    (apply map-settings env opts)))

                       
(defn java-prop-settings [& opts]
  (PropertiesSettings.  (default-settings opts)
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
  (ChainedSettings. default-settings-vals delegates))
