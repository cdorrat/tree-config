(ns tree-config.core
  (:require
   [tree-config
    [encryption :as tc-enc]
    [rsa-encryption :as rsa-enc]]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.data.codec.base64 :as b64]
   [clojure.walk :as walk])
  (:import 
   [java.net Inet4Address InetAddress NetworkInterface InterfaceAddress]   
   [java.util Properties Arrays]))
 
 
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
   :encryption-strategy nil ;; something that satisfies the tc-enc/ConfigEncryptor prpotocol
   :throw-on-failure? true   
   :env-subnets []})

(defn default-settings [overrides]
  (cond-> (merge default-settings-vals overrides)
    (not (:env overrides))                       (#(assoc %1 :env (get-env %1)))
    (and (:private-keyfile overrides)
         (nil? (:encryption-strategy overrides))) (assoc :encryption-strategy (rsa-enc/rsa-encryption (:private-keyfile overrides)))))

(defn app-name [config]
  (when-let [n (:app-name config)]
    (name n)))

;; ===================================================================================================
;; support for encrypted properties
(def ^{:private true :cont true}  enc-marker "ENC[")
(def ^{:private true :cont true}  enc-suffix "]")

(defn- is-encrypted? [val]
  (and (string? val)
       (.startsWith val enc-marker)))

(defn- decode-value [enc-strat val]
  (tc-enc/decrypt enc-strat (.substring val (count enc-marker) (- (count val) (count enc-suffix)))))

(defn encode-value
  "Given an encryption streategy (somethig that satisfies tree-config.encryption/ConfigEncryptor) encode a value"
  [enc-strat val]
  (str enc-marker (tc-enc/encrypt enc-strat val) enc-suffix))

 (defn- decrypt-map-leaves [enc-strat m]
   (walk/postwalk (fn [v]
                    (if (is-encrypted? v)
                      (decode-value enc-strat v)
                      v)) m))

(defn extract-value 
  [enc-strat val]
  (cond 
   (is-encrypted? val) (decode-value enc-strat val)
   (map? val) (decrypt-map-leaves enc-strat val)
   :else val))
    
;; ===================================================================================================
(defprotocol SettingsUtilsProtocol
  (key-details [settings key] "get env/app/key/value details for a single key"))

(defprotocol SettingsProtocol
  "This is the protocol describing basic settings capabilities"
  (has? [settings env app-name key] "return true if the specified setting exists")
  (lookup [settings env app-name key] "retrieve the value associated with the setting if it exists")
  (prop-names [settings env app-name] "returns a sequence of property names")
  (store-name [settings] "return the name of this config store"))

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
  (if-let [found-app (find-key-with-value impl key (:env config) (app-name config))]
    (let [raw-val  (apply lookup impl found-app)
          v (extract-value (:encryption-strategy config) raw-val)]
      (when (log/enabled? :debug)
        (let [{:keys [store env app-name prop-name]} (key-details impl key)]
          (log/debug (format "loaded config value: %s[%s/%s/%s] = %s " store (str env) (str app-name) (str prop-name) 
                             (str raw-val)))))
      v)
    (first default)))

(defmacro defsettings 
  "This macro provides implmentations of all the clojure interfaces required to make our settings look like a map (IFn, IAssociative, ILookup,...)
implemented with the methods in SettingsProtocol"
  [type-name fields & specifics]
  (let [[config & _] fields]
    `(deftype ~type-name [~@fields]
       ~@specifics

       ~@(when-not (some #(and (symbol? %) 
                               (= #'SettingsUtilsProtocol (ns-resolve *ns* %)))
                          specifics)
           `(SettingsUtilsProtocol
             (key-details [this# key#]
                (when-let [[env# app-name# prop-name#] (find-key-with-value this# key# (:env ~config) (app-name  ~config))]
                  {:store (store-name this#) :env env# :app-name app-name# :prop-name prop-name#}))))

       clojure.lang.IFn
       (invoke [this#] this#)
       (invoke [this# key#] (fetch-value this# ~config key#))
       (invoke [this# key# not-found#] (fetch-value this# ~config key# not-found#))
       (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#))

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

(defn prop-key-name [env app-name k]
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

(defn- most-specific-key [config the-key]
  (keyword (prop-key-name (:env config) (:app-name config) the-key)))

(defsettings MapSettings [config m]
  SettingsProtocol
  (has? [_ env app-name key]
        (contains? m (keyword (prop-key-name env app-name key))))
  (lookup [settings env app-name key]
          (get m (keyword (prop-key-name env app-name key))))
  (prop-names [settings env app-name]
              (distinct (map #(-> % key-name keyword) (keys m))))
  (store-name [_] (or (:store-name config) "map-config"))


  java.lang.Iterable
  (iterator [coll]
            (let [s (atom (seq coll))]
              (proxy [java.util.Iterator] []
                (hasNext [] (not (empty? @s)))
                (next []
                  (let [e (first @s)]
                    (swap! s rest)
                    e))
                (remove []))))  
  
  clojure.core.protocols.CollReduce
  (coll-reduce [coll f]
               (clojure.core.protocols/coll-reduce m f))
  (coll-reduce [coll f val]
               (clojure.core.protocols/coll-reduce m f val))

  clojure.core.protocols.IKVReduce
  (kv-reduce [amap f init]
             (clojure.core.protocols/kv-reduce m f init))
  
  clojure.lang.IPersistentMap
  (assoc [_ a-key a-val]
         (MapSettings. config (.assoc m (most-specific-key config a-key) a-val)))
  (assocEx [_  a-key  a-val]
           (MapSettings. config (.assocEx m  (most-specific-key config a-key) a-val)))  
  (without [_ a-key]
           (MapSettings. config
                         (apply dissoc m (for [[env app-name kname] (keys-to-search (:env config) (:app-name config) a-key)]
                                           (keyword (prop-key-name env app-name kname))))))
  clojure.lang.IPersistentCollection
  (count [_]
         (count m))
  (cons [_ o]
        (MapSettings. config (.cons m o)))
  (empty [_]
         (MapSettings. config {}))  
  (equiv [_ o]
         (.equiv m o)))

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
                             (seq (.keySet props)))))
  (store-name [_] (or (:store-name config) "prop-file")))


(defn- find-stream 
  "Try to open a file in order of:
  a). classpath
  b). any uri understood by clojure.java.io/input-stream"
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
        store-name (str "prop:" file-uri)
        is (find-stream file-uri (:throw-on-failure? settings))]
      (if is
        (with-open [is is]
          (PropertiesSettings. (assoc settings :store-name store-name)  
                               (doto (Properties.) (.load is))))
        (do
          (log/warn "unable to load settings from property file: " file-uri)
          (map-settings {} :store-name store-name)))))
          
       

;; ===================================================================================================
;; edn config data                

(defn edn-settings 
  "Config read from a map in edn format. The file should contain a map in edn format, the top level keys 
will be used as the properties and can optionally contain env and app-name sepcifiers, 
eg. {:global.prop \"a prop visible to everyone\"
     :myapp/app.prop \"prop only visible to myapp\"
     :dev/myapp/dev.app.prop \"prop visible to myapp in the dev environment\"
     :dev//dev.prop \"property visible ot all apps in dev env\"}
Params:
  file-uri - location of the edn file, first looked up in classpath then as a clojure.java.io/input-stream uri
Options:
   :config-key          - the project.clj key to get config values from (default :config)
   :config-file         - the leiningen project file to read (default \"project.clj\")
   :env                 - the environment
   :app-name            - the app name (keyword or string)
   :private-keyfile     - the private key pem file used when decrypting data (if using the default rsa encryption scheme)
   :encryption-strategy - something that satisfies the tree-config/ConfigEncryptor protocol, when specified :private-keyfile will be ignored"

  [file-uri & {:as opts}]
  (let [store-name (str "edn:" file-uri)
        settings (default-settings (assoc opts :store-name store-name))
        failed-loading (fn [& msgs]
                          (log/warn "failed to load edn settings from: " file-uri " " msgs)
                          (map-settings {} :store-name store-name))
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
    (apply map-settings env :store-name "env-settings" opts)))

                       
(defn java-prop-settings [& opts]
  (PropertiesSettings.  (default-settings (assoc opts :store-name "java-prop"))
                        (System/getProperties)))


;; ===================================================================================================
;; chanined settings

(defsettings ChainedSettings [config delegates]
  SettingsProtocol
  (has? [_ env app-name key]
        (some #(has? % env app-name key) delegates))
  (lookup [settings env app-name key]
          (->> delegates              
               (filter #(has? % env app-name key))
               first
               (#(lookup % env app-name key))))  
  (prop-names [settings env app-name]
              (into #{} (mapcat #(prop-names % env app-name) (reverse delegates))))
  (store-name [_] "chained-config")

  SettingsUtilsProtocol
  (key-details [_ key]
               (first (remove nil? (map #(key-details % key) delegates)))))

(defn chained-settings 
  "Return a config instance that returns the first match from a collection of config files.
  any options will be applied to all the chained settings instances (eg. app-name or env) "
  [delegates & {:as opts}]
  (ChainedSettings. (default-settings opts) delegates))
