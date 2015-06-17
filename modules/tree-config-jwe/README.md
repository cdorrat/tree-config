# config-tree-jwe

A tree config encryption strategy that uses the JWE compact representation
The plugin uses the nimbus-jose-jwt library (https://bitbucket.org/connect2id/nimbus-jose-jwt) and supports
the followign JWE algorithms:
  - RSA-OAEP
  - RSA1_5
  - RSA-OAEP-256

The following JWE encryption methods are supported:
  - A192CBC-HS384
  - A256CBC+HS512
  - A192GCM
  - A128CBC+HS256
  - A256CBC-HS512
  - A256GCM
  - A128GCM
  - A128CBC-HS256
