(defproject tree-config/lein "0.3.6"
  :description "tree-config support for reading values from leinening profiles or project files"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [tree-config/tree-config "0.3.4"]
                 [leiningen-core "2.3.4"]]
  :profiles {:provided {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.53"]
                                       [org.bouncycastle/bcpkix-jdk15on "1.53"]]}}
  :jar-name "tree-config-lein-%s.jar"
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"}
  :config {:prop-2 "from project.clj"})
