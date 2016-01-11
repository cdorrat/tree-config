(ns tree-config.commons-config
  (:require [tree-config.core :as ct]
            [clojure.java.io :as io])
  (:import [org.apache.commons.configuration
            Configuration
            PropertiesConfiguration            
            XMLConfiguration
            ]))

(ct/defsettings CommonsConfiguration [config iname inst ]
  ct/SettingsProtocol
  (has? [_ env app-name key]
    (.containsKey inst (name key)))
  (lookup [_ env app-name key]
    (.getString inst (name key)))
  (prop-names [_ env app-name]
    (->> (.getKeys inst)
        iterator-seq
        (map keyword)))
  (store-name [_] iname))

(defn- config-instance-name [prefix filename]
  (str "commons-" prefix "-"
       (or (some-> (io/file filename) .getName)
           (str filename))))

(defn properties-config [filename & {:as opts}]
  (->CommonsConfiguration (ct/default-settings opts)
                          (config-instance-name  "props" filename)
                          (PropertiesConfiguration. filename)))

(defn xml-config [filename & {:as opts}]
  (->CommonsConfiguration (ct/default-settings opts)
                          (config-instance-name "xml" filename)
                          (XMLConfiguration. filename)))

(defn commons-config [^Configuration config & {:as opts}]
  (->CommonsConfiguration (ct/default-settings opts) "commons-config" config))
