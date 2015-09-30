(ns tree-config.core-test
  (:require [clojure.test :refer :all]
            [tree-config
             [core :refer :all]
             [encryption :as tc-enc]
             [rsa-encryption :as rsa-enc]]))

;; ===================================================================================================
;; you can generate key pairs for testign as follows
;; 
;; # generate a private key & strip the passphrase
;; openssl genrsa -des3 -out private.pem 2048
;; openssl rsa -in private.pem -out private.pem

;; # generate a public key 
;; openssl rsa -in private.pem -outform PEM -pubout -out public.pem

;; # generate a self signed certificate (optional, we only need the key)
;; openssl req -new -key private.pem  -out server.csr
;; openssl x509 -req -days 365 -in server.csr -signkey private.pem -out server.crt

(def private-keyfile "test/sample_private_key.pem")
(def public-keyfile "test/sample_public_key.pem")

(def encrypted-hello (encode-value (rsa-enc/rsa-encryption public-keyfile nil) "hello"))

(deftest scoped-name-resolution
  ;; simple.prop             - a prop name only, applied to all applications & environments
  ;; myapp/prop.name         - this property is only visible ot the application myapp and its children (myapp.child)
  ;; myapp.child/prop.name   - this property is only visible to myapp.child and its children, not myapp
  ;; dev//prop.name          - prop.name is visible to all applications in the dev environment
  ;; dev/other.app/prop.name - prop.name is only visible to other.app in the dev environment
  (let [props {:simple.prop "global"
               :myapp/prop.name "child-accessible"
               :myapp.child/prop.name2 "child-only"
               :other.app/prop "other-app-only"
               :dev//prop.name3 "dev-only"
               :test//prop.name4 "test-only"
               :dev/other.app/prop.name5 "other app only"
               :dev/myapp/prop.name5 "myapp"}
        get-prop (fn [env app prop-name]
                   (get (map-settings props :env env :app-name app) prop-name))]

    (testing "global variables are accessible from all environemnts"
      (are [env app key expected] (= expected (get-prop env app key))         
           :dev  ""       :simple.prop "global"
           :dev  "my.app" :simple.prop "global"
           :test ""       :simple.prop "global"))

    (testing "app specific properties are only available to app and children"
      (are [env app key expected] (= expected (get-prop env app key))         
           :dev  :myapp             :prop.name "child-accessible"
           :dev  :myapp.child       :prop.name "child-accessible"
           :dev  :myapp.child.child :prop.name "child-accessible"
           :test :myapp             :prop.name "child-accessible"
           :dev  :other.app         :prop.name nil))

    (testing "environment sepcific properties are only seen from that environemnt"
      (are [env app key expected] (= expected (get-prop env app key))         
           :test :some-app  :prop.name4 "test-only"
           :test ""         :prop.name4 "test-only"
           :dev  :some-app  :prop.name4  nil

           :dev  :other.app :prop.name5 "other app only"
           :test :other.app :prop.name5 nil

           :dev  ""         :prop.name3 "dev-only"
           :dev  :my.app    :prop.name3 "dev-only"
           :test :my.app    :prop.name3 nil))))
           
(deftest map-get-value
  (let [s (map-settings {:dev/myapp.child/int-prop 17 :dev/myapp/str-prop "a str"
                         :dev/myapp/enc-prop encrypted-hello}
                        :env :dev :app-name "myapp.child" :private-keyfile private-keyfile)]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))
                                          
(deftest map-get-value-no-env 
  (let [s (map-settings {:myapp.child/int-prop 17 :myapp/str-prop "a str" :myapp/enc-prop encrypted-hello}
                        :env nil :app-name "myapp.child" :private-keyfile  private-keyfile )]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))

(deftest map-get-value-no-app
  (let [s (map-settings {:int-prop 17 :str-prop "a str"  :enc-prop encrypted-hello}
                        :env :dev :app-name nil :private-keyfile  private-keyfile)]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))

(deftest map-update-key-val
  (let [s (map-settings {:dev//a 1 :dev/app-name/a 2 :a 3 :b 19}
                        :env :dev :app-name nil :private-keyfile  private-keyfile)]
    (are [k expected] (= expected (get (assoc s :a 99) k))
         :a 99
         :b 19)))

(deftest map-merges
  (let [s1 (map-settings {:a 1 :b 2 :c 4} :env :dev :app-name nil :private-keyfile  private-keyfile)
        s2 (map-settings {:a 5 :b 6 :d 7} :env :dev :app-name nil :private-keyfile  private-keyfile)
        merged (merge s1 s2)]
    (are [k expected] (= expected (get merged k))
         :a 5
         :b 6
         :c 4
         :d 7)))

(deftest map-dissoc
  (let [s (map-settings {:dev//a 1 :dev/app-name/a 2 :a 3 :dev//b 76 :b 19 :c 17}
                        :env :dev :app-name nil :private-keyfile  private-keyfile)]
    (are [k expected] (= expected (get (dissoc s :a :c) k))
         :a nil
         :b 76
         :c nil)))

(deftest map-settings-can-loopup-false-values
  (let [s (map-settings {:a false}
                        :env :dev :app-name nil :private-keyfile  private-keyfile)]
    (is (= false (:a s)))
    (is (contains? s :a))
    (is (= :a (ffirst (seq s))))))

(deftest map-can-use-into
  (let [res (into {:a 1} (map-settings {:b 2}
                                       :env :dev :app-name nil :private-keyfile  private-keyfile))]
    (is (= 1 (:a res)))
    (is (= 2 (:b res)))))


(deftest map-seq-test 
  (let [s (map-settings {:dev/myapp.child/int-prop 17 :dev/myapp/str-prop "a str"
                         :dev/myapp/enc-prop encrypted-hello
                         :test//prop.a.prop 19 :dev/ather.app/a-key 99}                         
                        :env :dev :app-name "myapp.child" :private-keyfile private-keyfile)]
    (let [key-vals (seq s)
          vals (into #{} (vals s))]
      (is (= 3 (count key-vals)))
      (are [v] (contains? vals v)
           17
           "a str"
           "hello"))))

(deftest chained-props-test
  (let [s (chained-settings 
           [(map-settings {:prop1 "p1" :prop2 "p1-again"})
            (map-settings {:prop1 "hidden" :prop3 "accessible"})])]
    (are [k expected] (= expected (get s k))
         :prop1 "p1"
         :prop2 "p1-again"
         :prop3 "accessible"
         :prop4 nil)))

(deftest chained-props-honour-env
  (let [s (chained-settings 
           [(map-settings {:prod//prop1 "visible" :prop1 "hidden" :someapp/prop2 "p1" 
                           :otherapp/prop3 "hidden"})
            (map-settings {:prop1 "hidden" :myapp/prop2 "visible" :prop3 "accessible"})]
           :env :prod
           :app-name :myapp)]
    (are [k expected] (= expected (get s k))
         :prop1 "visible"
         :prop2 "visible"
         :prop3 "accessible"
         :prop4 nil))
  )

(deftest env-settings-test 
  (let [env (into {} (for [[k v] (System/getenv)] [(keyword k) v]))
        s (env-settings)]

    ;; we should have a key for every environment value
    (is (= (count env) (count s)))

    ;; settings values match this in the environment
    (is (every? #(= (get env %) (get s %)) (keys env)))))

(deftest prop-file-test                                
  (let [s (properties-settings "test.properties" :env :dev :app-name :myapp.child)]
    (are [k expected] (= expected (get s k))
         :global.prop "99"
         :nested.foo "60"
         :foo "43"
         :str-prop "a dev string"
         :sample-db "jdbc:a-url:414/something"
         :test-only nil)
    (is (= 6 (count s)))))

(deftest prop-file-missing-no-throw                               
  (let [s (properties-settings "i dont exist.properties" :throw-on-failure? false)]
    ;; we'd also expect it to log a warning but we wont test that..
    (is (= 0 (count s)))))

(deftest java-props-test
  (let [s (java-prop-settings)]
    (is (not (zero? (count  s))))
    (is (= (:os.name s) (.. (System/getProperties) (get "os.name"))))))

(deftest edn-test 
  (let [ get-prop (fn [env app key] 
                    (get (edn-settings "test.edn" :env env :app-name app) key))]
    (are [env app key expected] (= expected (get-prop env app key))
         :dev  nil :global.prop "a prop visible to everyone"
         :dev :myapp :app.prop "prop only visible to myapp"
         :dev :myapp :dev.app.prop "prop visible to myapp in the dev environment"
         :dev :otherapp :dev.app.prop nil         
         :dev :otherapp :dev.prop "property visible ot all apps in dev env")))

(deftest edn-file-missing
  (try 
    (edn-settings "i dont exist.edn" :throw-on-failure? true)
    (is false "expected an exception on missing edn file")
    (catch Exception e))

  (let [s (edn-settings "i dont exist.edn" :throw-on-failure? false)]
    (is (empty? s))))
      

(deftest edn-invalid-file
  (try 
    (edn-settings "test_bad.edn" :throw-on-failure? true)
    (is false "expected an exception on missing edn file")
    (catch Exception e))

  (let [s (edn-settings "test_bad.edn" :throw-on-failure? false)]
    (is (empty? s))))

(defn- reverse-str [a-str]
  (apply str (reverse a-str)))

(defrecord ReverseEnc []
  tc-enc/ConfigEncryptor
  (encrypt [_ plain-text]
    (reverse-str plain-text))
  (decrypt [this cipher-text]
    (reverse-str cipher-text)))

(deftest can-specify-encryption-strategies
  (let [enc-strat (->ReverseEnc)
        plaintext "my secret text"
        cipher-text (encode-value enc-strat plaintext)
        cfg (map-settings {:secret cipher-text} :encryption-strategy enc-strat)]
    (is (not= plaintext cipher-text))
    (is (= plaintext (:secret cfg)))))
