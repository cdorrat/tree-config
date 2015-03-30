# tree-config

A hierarchical configuration library for Clojure designed to provide access to config for multiple apps
Config values are sepcfied in a hierarchy of environment/app/prop.name and accessed in the same way as clojure maps.

The library has some unique features:
  - the ability to automatically determine the environment (dev/test/etc) from IPv4 netmasks, host/domain name regexes, environment variables or java system properties
  - support for encrypted propertes
  - Flexible configuration sources, default options include:
      - clojure maps (classpath, file, http, lein config)
      - Java property files (classpath, file, http)
      - environment variables
      - java system properties
  - Multiple configuration sources can be chanined together (eg. environment then a property file)
  - Designed to make it easy to add new configuration store types

Sample use cases include:
 - saving application config in an edn file but reading encrypted credentials from ~/.lein/profiles.clj
 - storing all of an organisations config in a single location 
 - distributing a single property file with some values overridden in dev/test/staging environments
 - loading properties from S3 when an app is deployed on ec2
 
## Usage

````clojure
(require '[tree-config.core :as tc])

(def some-config
   {:global.prop              "a global prop"
    :prop.name                "another global value"
    :myapp/prop.name          "a property ony visible to myapp"
    :myapp.inst1/prop.name2   "a value only visible to instance 1 of myapp"
    :dev//prop.name3          "a property only visible in the dev environment"
	:qa//prop.name3           "prop.name3 has a different value in qa"
    :dev/other.app/prop.name5 "a property only visible to other.app in the dev environment"
    :dev/myapp/prop.name5     "myapps property in dev"})

(def myapp-config (tc/map-settings some-config :env dev :app-name :myapp))

;; config instance can be used like maps
(:prop.name myapp-config)       ;; => "a property ony visible to myapp"
(get myapp-config :global.prop) ;; => "a global prop"

;; multiple config sources can be chained together
;; this config instance returna the first match from
;; 1. an environment variable
;; 2. a java system property
;; 3. the contents on an edn file
(def chain-sample
	(tc/chained-settings
		[(tc/env-settings)
		 (tc/java-prop-settings)
		 (tc/edn-settings "/etc/myapp/settings.edn")]
		 :app-name "my-app"))

;; encrypt a value
(tc/encode-value "a password" "my-secret") ;; => "enc:5NRKeBHkEKAEUjdW7+Csl2bGFnOQeL/K"

;; create a config instance with the correct private key 
(def config-with-secret (tc/map-settings {:the-password "enc:5NRKeBHkEKAEUjdW7+Csl2bGFnOQeL/K"
                                          :unencypyed-val "some text"}
	                        :private-keyfile "path/to/the/private_key.pem"))

;; read the encrypted value back
(:the-password config-with-secret ) ;; => "my-secret"
							
````
## Generating key pairs for encrypted config

Key pairs for encrypting config can be generated with openssl as follows:

````bash

# generate a private key & strip the passphrase
openssl genrsa -des3 -out private.pem 2048
openssl rsa -in private.pem -out private.pem

# generate a public key 
openssl rsa -in private.pem -outform PEM -pubout -out public.pem

# certificates can also be used as public keys, to test this self sign a key
openssl req -new -key private.pem  -out server.csr
openssl x509 -req -days 365 -in server.csr -signkey private.pem -out server.crt

````

## Environment Determination

Tree config allows you to override some or all settings for different environments (eg. dev, test, prod).
The environment can be specified when loading the config by specifying the :env key, if left blank the environment
will determined as follows:

   1. look for a java property called "SETTINGS_ENV"
   2. look for an environment variable called "SETTINGS_ENV"
   3. determine the environment based on the ipv4 subnets in the :env-subnets config property
   4. default to :dev

Subnets may be sepcified as follows:

````clojure
(def env-subnets [[:dev "10.1.1.1/24" "172.18.39.86/25"]
                  [:test "10.0.1.0/24"]
                  [:prod "10.0.2.0/24"]])
````

## License

Copyright © 2013 Cameorn Dorrat

Distributed under the Eclipse Public License, the same as Clojure.
