# Custom gRPC TLS

Master and agents communicate over gRPC with TLS on port `50051`. By default CraftPanel manages the
certificates for you; this page covers that default and how to bring your own.

Node authentication is by **node key**, not client certificates — there is no mutual-TLS
requirement. Master presents a server certificate, and each agent verifies it. See
[Security](../tech-stack/security.md) for the auth model.

## Default: auto-generated certificates

On first startup, if no explicit certificate is configured, master generates a CA and a server
certificate into `GRPC_CERT_STORE_PATH` (default `/app/certs`). This directory **must be a
persistent volume** (`master-certs` in the bundled compose file) — regenerating the CA breaks every
agent that cached the old one.

When an agent registers, master hands it the CA certificate, and the agent caches it at
`GRPC_CA_CERT_FILE` (default `/app/config/grpc-ca.crt`) for all later connections. Mount a writable
volume for that path too (`agent-config`).

### SANs

Agents verify the certificate against the hostname or IP they dial. Add every name an agent might
use to reach master via `GRPC_TLS_SANS` (comma-separated):

```yaml
GRPC_TLS_SANS: master,panel.example.com,203.0.113.10
```

The bundled compose file sets `GRPC_TLS_SANS: master` for the co-located agent (the Docker service
name). If you attach agents from other hosts, append master's public hostname or IP. A missing SAN
shows up as a TLS verification failure in the agent log.

## Bring your own certificates

To use your own certificate instead of the auto-generated one, set **both** on master:

```yaml
GRPC_TLS_CERT: /app/tls/server.crt
GRPC_TLS_KEY: /app/tls/server.key
```

`GRPC_TLS_CERT` and `GRPC_TLS_KEY` take priority over auto-generation; master logs that it is using
the provided certificate and will not generate or serve a CA. Mount the files into the container
read-only.

Because master no longer delivers a CA to agents, each agent must be told which CA signed the
server certificate. Do **one** of:

- Set `GRPC_TLS_CERT` on the agent to the path of the **CA certificate** (the agent treats it as the
  trust anchor), or
- Place the CA PEM at the agent's `GRPC_CA_CERT_FILE` path (default `/app/config/grpc-ca.crt`) before
  the agent starts.

The server certificate must cover the hostname/IP each agent dials (via SANs), exactly as above.

## Rotating or regenerating certificates

| Scenario                            | Action                                                                                             |
|-------------------------------------|----------------------------------------------------------------------------------------------------|
| Regenerate the auto-generated CA    | Delete the contents of `GRPC_CERT_STORE_PATH`, restart master, then update/redeploy agents so they pick up the new CA. |
| Replace a BYOC certificate          | Update the mounted files, restart master, then update the CA on every agent.                        |
| Add a new master hostname/IP        | Add it to `GRPC_TLS_SANS` (auto-gen) or reissue the cert with the new SAN (BYOC), restart master.    |

!!! warning
    Rotating the CA invalidates every agent's cached trust until they are updated. Plan the rotation
    so agents can be restarted with the new CA in the same window.

## Related

- [Environment Variables, Ports & Volumes](environment-variables.md) — `GRPC_TLS_*` reference
- [Adding a Node](adding-a-node.md) — pointing an agent at master
- [Security](../tech-stack/security.md) — node-key auth and TLS model
