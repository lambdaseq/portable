# TLS loopback credentials

These public credentials serve local test fixtures and are excluded from library jars.
`server-key.pem` is a public test key, not a production secret.

| File | Purpose |
| --- | --- |
| `ca.pem` | Private test authority's public certificate |
| `server.pem` | Signed server certificate, valid through 2049-12-31 UTC |
| `expired.pem` | Signed server certificate that expired on 2020-01-01 UTC |
| `dns-only.pem` | Signed certificate without an IP identity, for rejection tests |
| `server-key.pem` | RSA 2048-bit key shared by the server certificates |
| `server.cjs` | Ephemeral loopback TLS and stalled-handshake fixtures |

Server identities are `localhost`, `localhost.test`, and `127.0.0.1`.
The CA signing key is not stored in the repository.
Regeneration requires rotating the CA and all signed certificates together.
