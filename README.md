# hconfig

A hierarchical configuration library for Clojure designed to provide access to config for multiple apps
Config values are sepcfied in a hierarchy of environment/app/prop.name
It has some unique features:
  - the ability to determine the environment from IPv4 netmasks
  - support for encrypted propertes
  - Flexible configuration sources, default options include:
      - clojure maps
      - Java property files
      - environment variables
      - java properties
      - mongo-db
  - Multiple configuration  sources can be chanined together (eg. environment then a poperty file)

## Usage

FIXME

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
