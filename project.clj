(defproject tree-config "0.3.2"
  :description "Hierarchical configuration library for clojure"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :sub ["modules/tree-config" "modules/tree-config-lein" "modules/tree-config-jwe"]
  :plugins [[lein-sub "0.3.0"]]
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"})
