(defproject tree-config "0.2.0"
  :description "Hierarchical configuration library for clojure"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.bouncycastle/bcprov-jdk15on "1.51"]
                 [org.bouncycastle/bcpkix-jdk15on "1.51"]
                 #_[org.bouncycastle/bcprov-ext-jdk15on "1.51"]]
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"})
