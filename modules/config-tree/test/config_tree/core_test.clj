(ns config-tree.core-test
  (:require [clojure.test :refer :all]
            [config-tree.core :refer :all]))



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
                         :dev/myapp/enc-prop "enc:dleJEQVeDr5aXYsZ8L2qRw=="}
                        :env :dev :app-name "myapp.child" :enc-key "secret")]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))
                                          
(deftest map-get-value-no-env 
  (let [s (map-settings {:myapp.child/int-prop 17 :myapp/str-prop "a str" :myapp/enc-prop "enc:dleJEQVeDr5aXYsZ8L2qRw=="}
                        :env nil :app-name "myapp.child" :enc-key "secret")]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))

(deftest map-get-value-no-app
  (let [s (map-settings {:int-prop 17 :str-prop "a str"  :enc-prop "enc:dleJEQVeDr5aXYsZ8L2qRw=="}
                        :env :dev :app-name nil :enc-key "secret")]
    (are [k expected] (= expected (get s k))
         :int-prop 17
         :str-prop "a str"
         :enc-prop "hello")))

(deftest map-seq-test 
  (let [s (map-settings {:dev/myapp.child/int-prop 17 :dev/myapp/str-prop "a str"
                         :dev/myapp/enc-prop "enc:dleJEQVeDr5aXYsZ8L2qRw=="
                         :test//prop.a.prop 19 :dev/ather.app/a-key 99}                         
                        :env :dev :app-name "myapp.child" :enc-key "secret")]
    (let [key-vals (seq s)
          vals (into #{} (vals s))]
      (is (= 3 (count key-vals)))
      (are [v] (contains? vals v)
           17
           "a str"
           "hello"))))

(deftest chained-props-test
  (let [s (chained-settings 
           (map-settings {:prop1 "p1" :prop2 "p1-again"})
           (map-settings {:prop1 "hidden" :prop3 "accessible"}))]
    (are [k expected] (= expected (get s k))
         :prop1 "p1"
         :prop2 "p1-again"
         :prop3 "accessible"
         :prop4 nil)))

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
                    (get (edn-config "test.edn" :env env :app-name app) key))]
    (are [env app key expected] (= expected (get-prop env app key))
         :dev  nil :global.prop "a prop visible to everyone"
         :dev :myapp :app.prop "prop only visible to myapp"
         :dev :myapp :dev.app.prop "prop visible to myapp in the dev environment"
         :dev :otherapp :dev.app.prop nil         
         :dev :otherapp :dev.prop "property visible ot all apps in dev env")))

(deftest edn-file-missing
  (try 
    (edn-config "i dont exist.edn" :throw-on-failure? true)
    (is false "expected an exception on missing edn file")
    (catch Exception e))

  (let [s (edn-config "i dont exist.edn" :throw-on-failure? false)]
    (is (empty? s))))
      

(deftest edn-invalid-file
  (try 
    (edn-config "test_bad.edn" :throw-on-failure? true)
    (is false "expected an exception on missing edn file")
    (catch Exception e))

  (let [s (edn-config "test_bad.edn" :throw-on-failure? false)]
    (is (empty? s))))
  

