(ns tree-config.lein
  (:require [leiningen.core.project :as lp]
            [tree-config.core :as ct]))

(def ^:const default-settings {:config-key :config
                               :config-file "project.clj"})

(defn lein-settings 
  "Create a settings instance that gets values from leiningen config files (project.clj, ~/.lein/profiles.clj, /etc/leiningen).
Options (all optional):
   :config-key  - the project.clj key to get config values from (default :config)
   :config-file - the leiningen project file to read (default \"project.clj\")
   :env         - the environment
   :app-name    - the app name (keyword or string)
   :enc-key     - the key to use when encrypting & decrypting data"
  [& {:as opts}]  
  (let [settings (merge default-settings  (assoc opts :store-name "lein"))]
    (apply ct/map-settings (get (lp/read (:config-file settings)) (:config-key settings)) 
           (interleave (keys settings) (vals settings)))))

