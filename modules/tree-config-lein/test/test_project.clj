(defproject tree-config-lein "0.1.0-SNAPSHOT"
  :description "A sample project.clj file with config values"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [tree-config/tree-config "0.1.0-SNAPSHOT"]
                 [leiningen-core "2.3.4"]]
  :config {:global.prop "from project.clj"
           :myapp/prop.name "app prop"
           :dev//dev.prop "dev prop"
           :test/myapp/test.prop 1
           :map.prop {:a 12 :b 19}}
  :alternate-config {:global.prop "alternate"})
