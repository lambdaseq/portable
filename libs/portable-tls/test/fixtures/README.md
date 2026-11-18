# TLS loopback credentials

These public credentials serve local test fixtures and are excluded from library jars.
`server-key.pem` and `ec-server-key.pem` are public test keys, not production secrets.

| File | Purpose |
| --- | --- |
| `ca.pem` | Private test authority's public certificate |
| `server.pem` | Signed server certificate, valid through 2049-12-31 UTC |
| `expired.pem` | Signed server certificate that expired on 2020-01-01 UTC |
| `dns-only.pem` | Signed certificate without an IP identity, for rejection tests |
| `server-key.pem` | RSA 2048-bit key shared by the server certificates |
| `ec-server.pem` | Self-signed P-256 server certificate with localhost.test and 127.0.0.1 identities |
| `ec-server-key.pem` | Unencrypted PKCS#8 P-256 key for server-handshake contracts |
| `client.cjs` | Independent native client checking server TLS versions, ALPN, trust, and echoed bytes |
| `server.cjs` | Ephemeral loopback TLS and stalled-handshake fixtures |

Server identities are `localhost`, `localhost.test`, and `127.0.0.1`.
The CA signing key is not stored in the repository.
Regeneration requires rotating the CA and all signed certificates together.

The EC pair is independent of the RSA CA. Regenerate it together from this fixture directory:

```sh
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout ec-server-key.pem -out ec-server.pem -days 10000 \
  -subj '/CN=localhost.test' -addext 'subjectAltName=DNS:localhost.test,IP:127.0.0.1'
```
