(defproject tree-config/jwe "0.3.6"
  :description "tree-config support for reading JWE formatted encrypted values"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [tree-config/tree-config "0.3.4"]
                 [com.nimbusds/nimbus-jose-jwt "3.10"]]
  :profiles {:provided {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.53"]
                                       [org.bouncycastle/bcpkix-jdk15on "1.53"]]}}
  :jar-name "tree-config-jwe-%s.jar"
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"})
