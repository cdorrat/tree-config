(defproject tree-config/commons-config "0.3.8"
  :description "tree-config support for reading values from apache commons-config files"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [tree-config/tree-config "0.3.8"]
                 [commons-configuration/commons-configuration "1.9"]]
  :profiles {:provided {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.53"]
                                       [org.bouncycastle/bcpkix-jdk15on "1.53"]]}}
  :jar-name "tree-config-%s.jar"
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"})


