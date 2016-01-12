(defproject tree-config "0.3.7"
  :description "Hierarchical configuration library for clojure"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]]
  :profiles {:provided {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.53"]
                                       [org.bouncycastle/bcpkix-jdk15on "1.53"]]}
             :dev {:dependencies [[collection-check "0.1.6"]]}}
  :scm {:name "git"
        :url "https://github.com/cdorrat/tree-config.git"})
