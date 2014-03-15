(defproject tree-config/lein "0.1.0"
  :description "tree-config support for reading values from leinening profiles or project files"
  :url "https://github.com/cdorrat/tree-config"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [tree-config/tree-config "0.1.0"]
                 [leiningen-core "2.3.4"]]
  :jar-name "tree-config-lein-%s.jar"
  :scm {:name "git"
         :url "https://github.com/cdorrat/tree-config.git"}
  :config {:prop-2 "from project.clj"})
