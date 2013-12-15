(ns tree-config.lein-test
  (:require [clojure.test :refer :all]
            [tree-config.lein :refer :all]))

(deftest project-clj-test 
  (let [s (lein-settings :config-file "test/test_project.clj" :env :dev :app-name :myapp)]
    (are [key expected] (= (get s key) expected)
         :global.prop "from project.clj"
         :prop.name "app prop"
         :dev.prop "dev prop"
         :test.prop nil)
    (is (= 19 (-> s :map.prop :b)))))

(deftest alternate-config-key 
  (let [s (lein-settings :config-file "test/test_project.clj"  
                         :config-key :alternate-config)]
    (is (= "alternate" (:global.prop s)))))
    
