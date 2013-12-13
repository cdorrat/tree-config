(defproject config-tree-lein "0.1.0-SNAPSHOT"
  :description "config-tree support for reading values from leinening profiles or project files"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [config-tree/config-tree "0.1.0-SNAPSHOT"]
                 [leiningen-core "2.3.4"]]
  :config {:prop-2 "from project.clj"})
