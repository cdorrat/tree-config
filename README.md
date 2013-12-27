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
  - Designed to make it east to add new configuration store types

Sample use cases include:
 - saving application config in an edn file but reading encrypted credentials from ~/.lein/profiles.clj
 - storing all of an organisations config in a single location 
 - distributing a single property file with some values overridden in dev/test/staging environments
 - loading properties from S3 when an app is deployed on ec2
 
## Usage

FIXME

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
